package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.TransportConfigurationException
import io.github.trevarj.motd.irc.transport.TransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IrcClientConfigurationFailureTest {

    private fun TestScope.clientScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `transport configuration failure is fatal without opening a network connection`() = runTest {
        var connectCalls = 0
        val noNetworkTransport = object : IrcTransport {
            override suspend fun connect() {
                connectCalls++
                throw TransportConfigurationException("SOCKS5 proxy host is required")
            }

            override val incoming = emptyFlow<String>()
            override suspend fun send(line: String) = Unit
            override suspend fun close() = Unit
        }
        val factory = TransportFactory { _, _, _, _, _ -> noNetworkTransport }
        val client = IrcClient(
            IrcClientConfig(
                host = "irc.example.org", port = 6697, tls = true,
                nick = "motd", username = "motd", realname = "MOTD User",
            ),
            factory,
            clientScope(),
        )

        client.start()
        runCurrent()

        val state = client.state.value as IrcClientState.Failed
        assertTrue(state.fatal)
        assertEquals("connect failed: SOCKS5 proxy host is required", state.reason)
        assertEquals(1, connectCalls)
    }
}
