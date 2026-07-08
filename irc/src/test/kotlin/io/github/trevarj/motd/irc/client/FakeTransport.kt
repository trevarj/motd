package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow

/**
 * Scriptable in-memory transport for client tests. The test pushes inbound lines with [feed]
 * (as if the server sent them) and inspects [sent] to assert what the client wrote. Reads block
 * until fed; [eof] completes the incoming flow.
 */
class FakeTransport : IrcTransport {
    private val inbound = Channel<String>(Channel.UNLIMITED)
    val sent = mutableListOf<String>()

    @Volatile var connected = false
    @Volatile var closed = false

    override suspend fun connect() {
        connected = true
    }

    override val incoming: Flow<String> = inbound.consumeAsFlow()

    override suspend fun send(line: String) {
        sent.add(line)
    }

    override suspend fun close() {
        closed = true
        inbound.close()
    }

    /** Simulate the server sending one line (no CRLF). */
    suspend fun feed(line: String) {
        inbound.send(line)
    }

    /** Complete the incoming flow (clean EOF). */
    fun eof() {
        inbound.close()
    }

    fun factory(): TransportFactory = TransportFactory { _, _, _ -> this }
}
