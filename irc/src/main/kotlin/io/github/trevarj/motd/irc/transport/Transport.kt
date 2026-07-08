package io.github.trevarj.motd.irc.transport

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
    host: String,
    port: Int,
    tls: Boolean,
    /** Optional client certificate for SASL EXTERNAL; app supplies via its own factory. */
    sslContext: javax.net.ssl.SSLContext? = null,
) : IrcTransport {
    override suspend fun connect(): Unit = TODO("WP2")
    override val incoming: kotlinx.coroutines.flow.Flow<String> get() = TODO("WP2")
    override suspend fun send(line: String): Unit = TODO("WP2")
    override suspend fun close(): Unit = TODO("WP2")
}
