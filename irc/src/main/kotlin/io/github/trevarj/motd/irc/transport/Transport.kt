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

interface IrcTransport {
    /** Open the connection. Throws on failure. */
    suspend fun connect()

    /** Cold flow of received lines (no CRLF). Completes on EOF, throws on socket error. */
    val incoming: kotlinx.coroutines.flow.Flow<String>

    /** Send one line; CRLF appended by the transport. */
    suspend fun send(line: String)
    suspend fun close()
}

fun interface TransportFactory {
    /**
     * Build a transport for [host]:[port]. When [wsUrl] is non-null (plans/19 §3.3) the factory may
     * return an IRC-over-WebSocket transport dialing that URL instead of a raw TCP/TLS socket; the
     * pure-JVM default factory ignores it. When [proxy] is non-null (plans/19 §3.4, plans/20 Phase 1)
     * the connection is dialed through a SOCKS5 proxy with remote DNS; the pure-JVM default ignores
     * it too. TLS/pinning still key on the real [host]:[port] through the tunnel.
     */
    fun create(host: String, port: Int, tls: Boolean, wsUrl: String?, proxy: Proxy?): IrcTransport
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
     * Optional SOCKS5 proxy to tunnel the connection through (plans/19 §3.4, plans/20 Phase 1).
     * When non-null the raw socket is a `Socket(proxy)` and the destination is dialed *unresolved*
     * so DNS is performed remotely by the proxy — Java's SOCKS impl resolves locally otherwise,
     * which both leaks the destination and breaks `.onion`. TLS is layered on top exactly as on the
     * direct path, so SNI + hostname verification still key on the real [host]:[port].
     */
    private val proxy: Proxy? = null,
) : IrcTransport {

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val LINE_LIMIT = 16_384L
    }

    private var socket: Socket? = null
    private var source: BufferedSource? = null
    private var sink: BufferedSink? = null

    // Serializes writes so interleaved sends never corrupt the wire.
    private val sendMutex = Mutex()

    override suspend fun connect() {
        runInterruptible(Dispatchers.IO) {
            // Proxied path: Socket(proxy) + an UNRESOLVED destination forces remote DNS through the
            // SOCKS5 proxy (leak-free, .onion-capable). Direct path keeps the resolving address.
            val raw = if (proxy != null) Socket(proxy) else Socket()
            val dest = if (proxy != null) {
                InetSocketAddress.createUnresolved(host, port)
            } else {
                InetSocketAddress(host, port)
            }
            raw.connect(dest, CONNECT_TIMEOUT_MS)
            raw.keepAlive = true
            raw.soTimeout = 0 // reads block; watchdog handles death.

            val finalSocket: Socket = if (tls) {
                val ctx = sslContext ?: SSLContext.getDefault()
                val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
                // createSocket(host, ...) sets SNI; endpoint identification adds hostname
                // verification on top, which we skip for leaf-pinned hosts (verifyHostname=false).
                if (verifyHostname) {
                    ssl.sslParameters = ssl.sslParameters.apply {
                        endpointIdentificationAlgorithm = "HTTPS"
                    }
                }
                ssl.startHandshake()
                ssl
            } else {
                raw
            }

            socket = finalSocket
            source = finalSocket.source().buffer()
            sink = finalSocket.sink().buffer()
        }
    }

    override val incoming: Flow<String> = channelFlow {
        val src = source ?: throw IllegalStateException("connect() not called")
        while (true) {
            val line = try {
                src.readUtf8LineStrict(LINE_LIMIT)
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
            runInterruptible(Dispatchers.IO) {
                s.writeUtf8(line)
                s.writeUtf8("\r\n")
                s.flush()
            }
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
