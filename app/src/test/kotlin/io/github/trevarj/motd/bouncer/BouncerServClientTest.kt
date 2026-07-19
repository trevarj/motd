package io.github.trevarj.motd.bouncer

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BouncerServClientTest {
    @Test fun ignores_notice_and_finishes_after_privmsg_quiet_window() = runTest {
        val events = MutableSharedFlow<IrcEvent>(extraBufferCapacity = 8)
        val provider = FakeSessions(events) { wire ->
            events.emit(chat(IrcEvent.ChatKind.NOTICE, "relay"))
            events.emit(chat(IrcEvent.ChatKind.PRIVMSG, "reply to $wire"))
        }
        val result = async { BouncerServClientImpl(provider).execute(1, BouncerServCommand("network status")) }
        runCurrent()
        advanceTimeBy(401)

        assertEquals(
            BouncerServResult.Success("network status", listOf("reply to network status")),
            result.await(),
        )
    }

    @Test fun first_reply_timeout_is_retryable_and_stale_session_replies_are_discarded() = runTest {
        val events = MutableSharedFlow<IrcEvent>(extraBufferCapacity = 2)
        var current = true
        val provider = FakeSessions(events, isCurrent = { current }) {
            current = false
            events.emit(chat(IrcEvent.ChatKind.PRIVMSG, "late stale reply"))
        }
        val result = async { BouncerServClientImpl(provider).execute(1, BouncerServCommand("help")) }
        runCurrent()
        advanceTimeBy(5_001)

        assertTrue(result.await() is BouncerServResult.Timeout)
    }

    @Test fun commands_for_one_root_are_serialized() = runTest {
        val events = MutableSharedFlow<IrcEvent>(extraBufferCapacity = 8)
        val sent = mutableListOf<String>()
        val provider = FakeSessions(events) { wire ->
            sent += wire
            events.emit(chat(IrcEvent.ChatKind.PRIVMSG, wire))
        }
        val client = BouncerServClientImpl(provider)
        val first = async { client.execute(1, BouncerServCommand("network status")) }
        val second = async { client.execute(1, BouncerServCommand("server status")) }
        runCurrent()
        assertEquals(listOf("network status"), sent)
        advanceTimeBy(401)
        runCurrent()
        assertEquals(listOf("network status", "server status"), sent)
        advanceTimeBy(401)
        first.await(); second.await()
    }

    @Test fun known_silent_command_completes_after_write_without_inventing_state() = runTest {
        val events = MutableSharedFlow<IrcEvent>()
        val sent = mutableListOf<String>()
        val result = BouncerServClientImpl(FakeSessions(events) { sent += it })
            .execute(1, BouncerServCommands.serverDebug(true))

        assertEquals(listOf("server debug true"), sent)
        assertEquals(BouncerServResult.Success("server debug true", emptyList()), result)
    }

    @Test fun durable_but_disconnected_command_does_not_report_success_or_timeout() = runTest {
        val events = MutableSharedFlow<IrcEvent>()
        val provider = FakeSessions(
            events = events,
            acceptance = io.github.trevarj.motd.service.SendAcceptance.Accepted(
                emptyList(),
                io.github.trevarj.motd.service.ImmediateWireAcceptance.DISCONNECTED,
            ),
        ) { }

        val result = BouncerServClientImpl(provider)
            .execute(1, BouncerServCommands.serverDebug(true))

        assertEquals(BouncerServResult.Disconnected("server debug true"), result)
    }

    @Test fun durable_but_failed_wire_command_reports_failure_immediately() = runTest {
        val events = MutableSharedFlow<IrcEvent>()
        val provider = FakeSessions(
            events = events,
            acceptance = io.github.trevarj.motd.service.SendAcceptance.Accepted(
                emptyList(),
                io.github.trevarj.motd.service.ImmediateWireAcceptance.FAILED,
            ),
        ) { }

        val result = BouncerServClientImpl(provider)
            .execute(1, BouncerServCommand("network status"))

        assertTrue(result is BouncerServResult.Failed)
    }

    @Test fun probe_expands_advertised_command_families() = runTest {
        val events = MutableSharedFlow<IrcEvent>(extraBufferCapacity = 16)
        val provider = FakeSessions(events) { wire ->
            val replies = when (wire) {
                "help" -> listOf(
                    "available commands: network create, network status, server status, server debug",
                )
                "help network" -> listOf("available commands: network create, network status")
                "help server" -> listOf("available commands: server status, server debug")
                else -> emptyList()
            }
            replies.forEach { events.emit(chat(IrcEvent.ChatKind.PRIVMSG, it)) }
        }
        val result = async { BouncerServClientImpl(provider).probe(1) }

        repeat(3) {
            runCurrent()
            advanceTimeBy(401)
        }

        assertEquals(
            setOf("network create", "network status", "server status", "server debug"),
            result.await().commandPaths,
        )
        assertTrue(result.await().verified)
        assertTrue(result.await().administrator)
    }

    private class FakeSessions(
        private val events: MutableSharedFlow<IrcEvent>,
        private val isCurrent: () -> Boolean = { true },
        private val acceptance: io.github.trevarj.motd.service.SendAcceptance =
            io.github.trevarj.motd.service.SendAcceptance.Accepted(emptyList()),
        private val sender: suspend (String) -> Unit,
    ) : BouncerServSessionProvider {
        override suspend fun session(rootNetworkId: Long) = BouncerServSession(
            token = this,
            events = events,
            send = {
                sender(it)
                acceptance
            },
            isCurrent = isCurrent,
        )
    }

    private fun chat(kind: IrcEvent.ChatKind, text: String) = IrcEvent.ChatMessage(
        ctx = MessageContext(null, 1, null, null, null),
        kind = kind,
        source = Prefix("BouncerServ"),
        target = "me",
        text = text,
        isSelf = false,
        replyToMsgid = null,
    )
}
