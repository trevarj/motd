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
import org.junit.Assert.assertNull
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

        val targetResponse = targets.await() as ChatHistoryResponse.Targets
        assertEquals(listOf(ChatHistoryTarget("#room", 1_784_055_600_000L)), targetResponse.targets)

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

        val response = latest.await() as ChatHistoryResponse.Messages
        val event = response.events.single() as IrcEvent.ChatMessage
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

    @Test
    fun `message response preserves root end tag and excludes context from boundaries`() = runTest {
        val transport = FakeTransport()
        val client = registeredWithoutLabeledResponse(transport)
        val request = async {
            client.chathistory(
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#room", limit = 100),
            )
        }
        runCurrent()

        transport.feed("@draft/chathistory-end BATCH +history chathistory #room")
        transport.feed(
            "@batch=history;msgid=FirstCase;time=2026-07-14T19:00:00.000Z " +
                ":alice!user@example.test PRIVMSG #room :primary",
        )
        transport.feed(
            "@batch=history;draft/chathistory-context;msgid=context;time=2026-07-14T19:00:01.000Z;" +
                "+draft/react=+1;+reply=FirstCase :bob!user@example.test TAGMSG #room",
        )
        transport.feed(
            "@batch=history;msgid=secondCase;time=2026-07-14T19:00:00.000Z " +
                ":alice!user@example.test PRIVMSG #room :second primary",
        )
        transport.feed("BATCH -history")
        runCurrent()

        val response = request.await() as ChatHistoryResponse.Messages
        assertEquals(3, response.events.size)
        assertEquals(2, response.primaryMessageCount)
        assertEquals(ChatHistoryReference("FirstCase", 1_784_055_600_000L), response.oldest)
        assertEquals(ChatHistoryReference("secondCase", 1_784_055_600_000L), response.newest)
        assertTrue(response.endOfHistory)
    }

    @Test
    fun `msgid-only boundary does not inherit EventMapper local time`() = runTest {
        val transport = FakeTransport()
        val client = registeredWithoutLabeledResponse(transport)
        val request = async {
            client.chathistory(
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#room", limit = 100),
            )
        }
        runCurrent()

        transport.feed("BATCH +history chathistory #room")
        transport.feed("@batch=history;msgid=ExactCase :alice!user@example.test PRIVMSG #room :no time")
        transport.feed("BATCH -history")
        runCurrent()

        val response = request.await() as ChatHistoryResponse.Messages
        assertEquals(1, response.primaryMessageCount)
        assertEquals(ChatHistoryReference("ExactCase", null), response.oldest)
        assertEquals(response.oldest, response.newest)
    }

    @Test
    fun `primary without msgid or valid raw time has no boundary`() = runTest {
        val transport = FakeTransport()
        val client = registeredWithoutLabeledResponse(transport)
        val request = async {
            client.chathistory(
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#room", limit = 100),
            )
        }
        runCurrent()

        transport.feed("BATCH +history chathistory #room")
        transport.feed("@batch=history;time=not-an-instant :alice!user@example.test PRIVMSG #room :no reference")
        transport.feed("BATCH -history")
        runCurrent()

        val response = request.await() as ChatHistoryResponse.Messages
        assertEquals(1, response.events.size)
        assertEquals(1, response.primaryMessageCount)
        assertNull(response.oldest)
        assertNull(response.newest)
    }

    @Test
    fun `completed context-only batch reports zero primary messages`() = runTest {
        val transport = FakeTransport()
        val client = registeredWithoutLabeledResponse(transport)
        val request = async {
            client.chathistory(
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#room", limit = 100),
            )
        }
        runCurrent()

        transport.feed("BATCH +history chathistory #room")
        transport.feed(
            "@batch=history;draft/chathistory-context;msgid=context;" +
                "time=2026-07-14T19:00:01.000Z;+draft/react=+1;+reply=missing " +
                ":bob!user@example.test TAGMSG #room",
        )
        transport.feed("BATCH -history")
        runCurrent()

        val response = request.await() as ChatHistoryResponse.Messages
        assertEquals(1, response.events.size)
        assertEquals(0, response.primaryMessageCount)
        assertNull(response.oldest)
        assertNull(response.newest)
    }

    @Test
    fun `malformed TARGETS record fails the completed page instead of being dropped`() = runTest {
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

        transport.feed("BATCH +targets draft/chathistory-targets")
        transport.feed("@batch=targets CHATHISTORY TARGETS #valid 2026-07-14T19:00:00.000Z")
        transport.feed("@batch=targets CHATHISTORY TARGETS #missing-timestamp")
        transport.feed("BATCH -targets")
        runCurrent()

        assertTrue(request.await() is IrcProtocolException)
    }

    @Test
    fun `invalid TARGETS timestamp fails the completed page`() = runTest {
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

        transport.feed("BATCH +targets draft/chathistory-targets")
        transport.feed("@batch=targets CHATHISTORY TARGETS #room definitely-not-a-time")
        transport.feed("BATCH -targets")
        runCurrent()

        assertTrue(request.await() is IrcProtocolException)
    }

    @Test
    fun `unlabeled root close with open nested batch fails completeness proof`() = runTest {
        val transport = FakeTransport()
        val client = registeredWithoutLabeledResponse(transport)
        val request = async {
            runCatching {
                client.chathistory(
                    ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#room", limit = 100),
                )
            }.exceptionOrNull()
        }
        runCurrent()

        transport.feed("BATCH +history chathistory #room")
        transport.feed("@batch=history BATCH +nested draft/example")
        transport.feed("BATCH -history")
        runCurrent()

        assertTrue(request.await() is IrcProtocolException)

        val retry = async {
            client.chathistory(
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#room", limit = 100),
            )
        }
        runCurrent()
        transport.feed("BATCH +nested chathistory #room")
        transport.feed("BATCH -nested")
        runCurrent()

        assertTrue(retry.await() is ChatHistoryResponse.Messages)
    }

    @Test
    fun `closed empty batch is a complete message response`() = runTest {
        val transport = FakeTransport()
        val client = registeredWithoutLabeledResponse(transport)
        val request = async {
            client.chathistory(
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.BEFORE, "#room", "timestamp=epoch", limit = 100),
            )
        }
        runCurrent()

        transport.feed("BATCH +history chathistory #room")
        transport.feed("BATCH -history")
        runCurrent()

        val response = request.await() as ChatHistoryResponse.Messages
        assertTrue(response.events.isEmpty())
        assertEquals(0, response.primaryMessageCount)
        assertNull(response.oldest)
        assertNull(response.newest)
    }

    @Test
    fun `request without transport throws disconnected instead of returning empty history`() = runTest {
        val client = IrcClient(
            IrcClientConfig("irc.example", 6697, true, "motd", "motd", "MOTD"),
            FakeTransport().factory(),
            clientScope(),
        )

        val error = runCatching {
            client.chathistory(
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#room", limit = 100),
            )
        }.exceptionOrNull()

        assertTrue(error is IrcDisconnectedException)
        assertEquals("CHATHISTORY", (error as IrcDisconnectedException).ircCommand)
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
