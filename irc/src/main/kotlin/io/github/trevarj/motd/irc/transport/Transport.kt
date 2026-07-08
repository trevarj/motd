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
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
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
    fun create(host: String, port: Int, tls: Boolean): IrcTransport
}

/** okio-over-Socket/SSLSocket implementation lives in :irc (JVM default factory). */
class OkioLineTransport(
    private val host: String,
    private val port: Int,
    private val tls: Boolean,
    /** Optional client certificate for SASL EXTERNAL; app supplies via its own factory. */
    private val sslContext: SSLContext? = null,
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
            val raw = Socket()
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            raw.keepAlive = true
            raw.soTimeout = 0 // reads block; watchdog handles death.

            val finalSocket: Socket = if (tls) {
                val ctx = sslContext ?: SSLContext.getDefault()
                val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
                // SNI + hostname verification via endpoint identification.
                ssl.sslParameters = ssl.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
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
