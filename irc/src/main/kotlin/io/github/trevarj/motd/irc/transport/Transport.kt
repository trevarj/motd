package io.github.trevarj.motd.irc.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Shared defensive ceiling for one IRC line, excluding CRLF. */
const val MAX_IRC_LINE_BYTES = 16_384

interface IrcTransport {
    /** Open the connection. Throws on failure. */
    suspend fun connect()

    /** Cold flow of received lines (no CRLF). Completes on EOF, throws on socket error. */
    val incoming: kotlinx.coroutines.flow.Flow<String>

    /** Send one line; CRLF appended by the transport. */
    suspend fun send(line: String)

    suspend fun close()
}

/**
 * A persisted transport setting is invalid or asks for a combination this build cannot route.
 * Unlike a transient IO failure, retrying cannot fix it; [IrcClient] exposes it as a fatal failed
 * state so the connection actor parks until the user changes the setting.
 */
class TransportConfigurationException(
    message: String,
) : IllegalStateException(message)

fun interface TransportFactory {
    /**
     * Build a transport for [host]:[port]. When [wsUrl] is non-null the factory may
     * return an IRC-over-WebSocket transport dialing that URL instead of a raw TCP/TLS socket; the
     * pure-JVM default factory ignores it. When [proxy] is non-null
     * the connection is dialed through a SOCKS5 proxy with remote DNS; the pure-JVM default ignores
     * it too. TLS/pinning still key on the real [host]:[port] through the tunnel.
     */
    fun create(
        host: String,
        port: Int,
        tls: Boolean,
        wsUrl: String?,
        proxy: Proxy?,
    ): IrcTransport
}

/** okio-over-Socket/SSLSocket implementation lives in :irc (JVM default factory). */
class OkioLineTransport(
    private val host: String,
    private val port: Int,
    private val tls: Boolean,
    /** Optional client certificate for SASL EXTERNAL; app supplies via its own factory. */
    private val sslContext: SSLContext? = null,
    /**
     * Enforce TLS hostname verification (endpoint identification). Default true. The app factory
     * sets this false for leaf-pinned hosts, where the exact-cert pin is a stronger guarantee than
     * hostname matching and lets bare-IP / self-signed bouncer certs connect. SNI is unaffected.
     */
    private val verifyHostname: Boolean = true,
    /**
     * Optional SOCKS5 proxy to tunnel the connection through.
     * When non-null the raw socket is a `Socket(proxy)` and the destination is dialed *unresolved*
     * so DNS is performed remotely by the proxy — Java's SOCKS impl resolves locally otherwise,
     * which both leaks the destination and breaks `.onion`. TLS is layered on top exactly as on the
     * direct path, so SNI + hostname verification still key on the real [host]:[port].
     */
    private val proxy: Proxy? = null,
    /**
     * Optional dial-phase observer: invoked with a phase name (`socket_connected` once the raw
     * socket — including any SOCKS/tunnel path — is up, `tls_done` after the TLS handshake) and the
     * milliseconds elapsed since [connect] began. A pure seam so the app layer can attribute dial
     * time to transport establishment versus registration in its diagnostics journal; the default
     * is silent and the module stays free of any logging dependency. Runs on the connect dispatcher
     * — implementations must be cheap and non-throwing.
     */
    private val onConnectPhase: (phase: String, elapsedMs: Long) -> Unit = { _, _ -> },
) : IrcTransport {
    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
    }

    private var socket: Socket? = null
    private var source: BufferedSource? = null
    private var sink: BufferedSink? = null

    // Serializes writes so interleaved sends never corrupt the wire.
    private val sendMutex = Mutex()

    override suspend fun connect() {
        runInterruptible(Dispatchers.IO) {
            val dialStartMs = System.nanoTime() / 1_000_000
            // Proxied path: Socket(proxy) + an UNRESOLVED destination forces remote DNS through the
            // SOCKS5 proxy (leak-free, .onion-capable). Direct path keeps the resolving address.
            val raw = if (proxy != null) Socket(proxy) else Socket()
            val dest =
                if (proxy != null) {
                    InetSocketAddress.createUnresolved(host, port)
                } else {
                    InetSocketAddress(host, port)
                }
            raw.connect(dest, CONNECT_TIMEOUT_MS)
            raw.keepAlive = true
            raw.soTimeout = 0 // reads block; watchdog handles death.
            onConnectPhase("socket_connected", System.nanoTime() / 1_000_000 - dialStartMs)

            val finalSocket: Socket =
                if (tls) {
                    val ctx = sslContext ?: SSLContext.getDefault()
                    val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
                    // createSocket(host, ...) sets SNI; endpoint identification adds hostname
                    // verification on top, which we skip for leaf-pinned hosts (verifyHostname=false).
                    if (verifyHostname) {
                        ssl.sslParameters =
                            ssl.sslParameters.apply {
                                endpointIdentificationAlgorithm = "HTTPS"
                            }
                    }
                    ssl.startHandshake()
                    onConnectPhase("tls_done", System.nanoTime() / 1_000_000 - dialStartMs)
                    ssl
                } else {
                    raw
                }

            socket = finalSocket
            source = finalSocket.source().buffer()
            sink = finalSocket.sink().buffer()
        }
    }

    override val incoming: Flow<String> =
        channelFlow {
            val src = source ?: throw IllegalStateException("connect() not called")
            while (true) {
                val line =
                    try {
                        src.readUtf8LineStrict(MAX_IRC_LINE_BYTES.toLong())
                    } catch (e: EOFException) {
                        // Clean EOF: complete the flow normally.
                        break
                    }
                // Suspending send; respects backpressure and channel closure.
                send(line)
            }
            // Returning from the block completes the channelFlow normally.
        }.flowOn(Dispatchers.IO)

    override suspend fun send(line: String) {
        val s = sink ?: throw IllegalStateException("connect() not called")
        sendMutex.withLock {
            runInterruptible(Dispatchers.IO) { s.writeIrcLines(listOf(line)) }
        }
    }

    override suspend fun close() {
        runInterruptible(Dispatchers.IO) {
            try {
                source?.close()
            } catch (_: Exception) {
            }
            try {
                sink?.close()
            } catch (_: Exception) {
            }
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
        source = null
        sink = null
        socket = null
    }
}

internal fun BufferedSink.writeIrcLines(lines: Iterable<String>) {
    lines.forEach { line ->
        writeUtf8(line)
        writeUtf8("\r\n")
    }
    flush()
}
