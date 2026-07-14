package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IrcClientChathistoryTest {
    @Test
    fun `unlabeled bouncer batches return TARGETS and LATEST results`() = runTest {
        val transport = FakeTransport()
        val client = registeredWithoutLabeledResponse(transport)

        val targets = async {
            client.chathistory(
                ChatHistoryRequest(
                    subcommand = ChatHistoryRequest.Subcommand.TARGETS,
                    target = "*",
                    bound1 = "timestamp=2030-01-01T00:00:00.000Z",
                    bound2 = "timestamp=1970-01-01T00:00:00.000Z",
                    limit = 100,
                ),
            )
        }
        runCurrent()
        assertEquals(
            "CHATHISTORY TARGETS timestamp=2030-01-01T00:00:00.000Z timestamp=1970-01-01T00:00:00.000Z 100",
            transport.sent.last(),
        )
        transport.feed("BATCH +targets draft/chathistory-targets")
        transport.feed("@batch=targets CHATHISTORY TARGETS #room 2026-07-14T19:00:00.000Z")
        transport.feed("BATCH -targets")
        runCurrent()

        assertEquals(listOf("#room" to 1_784_055_600_000L), targets.await().targets)

        val latest = async {
            client.chathistory(
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#room", limit = 100),
            )
        }
        runCurrent()
        assertEquals("CHATHISTORY LATEST #room * 100", transport.sent.last())
        transport.feed("BATCH +history chathistory #room")
        transport.feed(
            "@batch=history;msgid=seed;time=2026-07-14T19:00:00.000Z " +
                ":alice!user@example.test PRIVMSG #room :retained history",
        )
        transport.feed("BATCH -history")
        runCurrent()

        val event = latest.await().events.single() as IrcEvent.ChatMessage
        assertEquals("retained history", event.text)
        assertEquals("seed", event.ctx.msgid)
    }

    @Test
    fun `unlabeled bouncer failure reaches the CHATHISTORY caller`() = runTest {
        val transport = FakeTransport()
        val client = registeredWithoutLabeledResponse(transport)
        val request = async {
            runCatching {
                client.chathistory(
                    ChatHistoryRequest(
                        ChatHistoryRequest.Subcommand.TARGETS,
                        "*",
                        bound1 = "timestamp=2030-01-01T00:00:00.000Z",
                        bound2 = "timestamp=1970-01-01T00:00:00.000Z",
                        limit = 100,
                    ),
                )
            }.exceptionOrNull()
        }
        runCurrent()
        transport.feed("FAIL CHATHISTORY INVALID_PARAMS TARGETS :Invalid second bound")
        runCurrent()

        val failure = request.await()
        assertTrue(failure is IrcCommandException)
        assertEquals("INVALID_PARAMS", (failure as IrcCommandException).code)
    }

    private fun TestScope.clientScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    private suspend fun TestScope.registeredWithoutLabeledResponse(transport: FakeTransport): IrcClient {
        val client = IrcClient(
            IrcClientConfig("irc.example", 6697, true, "motd", "motd", "MOTD"),
            transport.factory(),
            clientScope(),
        )
        client.start()
        runCurrent()
        transport.feed(":srv CAP * LS :batch message-tags server-time draft/chathistory")
        runCurrent()
        transport.feed(":srv CAP motd ACK :batch message-tags server-time draft/chathistory")
        transport.feed(":srv 005 motd CHATHISTORY=100 CASEMAPPING=rfc1459 :supported")
        transport.feed(":srv 001 motd :Welcome")
        runCurrent()
        assertTrue(!client.hasCap("labeled-response"))
        assertTrue(client.hasCap("draft/chathistory"))
        return client
    }
}
