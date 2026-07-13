package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.transport.OkioLineTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in two-MOTD-client proof against `test/e2e/local-stack.sh up`. */
class IrcInteroperabilityIntegrationTest {
    @Test
    fun `reply react unreact and history interoperate over Ergo`() = runBlocking {
        assumeTrue(System.getenv("MOTD_RUN_IRC_INTEROP") == "1")
        val port = System.getenv("MOTD_ERGO_PORT")?.toIntOrNull() ?: 6667
        val suffix = (System.currentTimeMillis() % 100_000).toString()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = client("motdia$suffix", port, scope)
        val b = client("motdib$suffix", port, scope)
        val aEvents = java.util.concurrent.CopyOnWriteArrayList<IrcEvent>()
        val bEvents = java.util.concurrent.CopyOnWriteArrayList<IrcEvent>()
        val aRecorder = scope.launch { a.events.collect(aEvents::add) }
        val bRecorder = scope.launch { b.events.collect(bEvents::add) }
        try {
            a.start()
            b.start()
            withTimeout(TIMEOUT_MS) { a.state.filterIsInstance<IrcClientState.Ready>().first() }
            withTimeout(TIMEOUT_MS) { b.state.filterIsInstance<IrcClientState.Ready>().first() }

            val aJoined = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(TIMEOUT_MS) {
                    a.events.filterIsInstance<IrcEvent.Joined>().first { it.channel == CHANNEL && it.isSelf }
                }
            }
            val bJoined = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(TIMEOUT_MS) {
                    b.events.filterIsInstance<IrcEvent.Joined>().first { it.channel == CHANNEL && it.isSelf }
                }
            }
            a.send(IrcMessage(command = "JOIN", params = listOf(CHANNEL)))
            b.send(IrcMessage(command = "JOIN", params = listOf(CHANNEL)))
            aJoined.await()
            bJoined.await()
            val sequence = AtomicInteger()
            val parentText = "motd interop parent ${sequence.incrementAndGet()} $suffix"
            val parentArrival = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(TIMEOUT_MS) {
                    b.events.filterIsInstance<IrcEvent.ChatMessage>().first { it.text == parentText }
                }
            }
            a.sendMessage(CHANNEL, parentText, null)
            val parent = parentArrival.await()
            val parentMsgid = parent.ctx.msgid
            assertNotNull(parentMsgid)

            val replyText = "motd interop reply ${sequence.incrementAndGet()} $suffix"
            val replyArrival = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(TIMEOUT_MS) {
                    a.events.filterIsInstance<IrcEvent.ChatMessage>().first { it.text == replyText }
                }
            }
            b.sendMessage(CHANNEL, replyText, parentMsgid)
            val receivedReply = replyArrival.await()
            assertNotNull("A events=$aEvents; B events=$bEvents", receivedReply)
            assertEquals(parentMsgid, checkNotNull(receivedReply).replyToMsgid)

            val reactArrival = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(TIMEOUT_MS) {
                    a.events.filterIsInstance<IrcEvent.TagMessage>().first {
                        it.reactEmoji == "👍" && it.reactTargetMsgid == parentMsgid
                    }
                }
            }
            b.sendReact(CHANNEL, checkNotNull(parentMsgid), "👍")
            reactArrival.await()

            val unreactArrival = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(TIMEOUT_MS) {
                    a.events.filterIsInstance<IrcEvent.Raw>().first {
                        it.message.tags["+draft/unreact"] == "👍" &&
                            (it.message.tags["+reply"] ?: it.message.tags["+draft/reply"]) == parentMsgid
                    }
                }
            }
            b.send(
                IrcMessage(
                    tags = mapOf("+draft/unreact" to "👍", "+reply" to checkNotNull(parentMsgid)),
                    command = "TAGMSG",
                    params = listOf(CHANNEL),
                ),
            )
            val receivedUnreact = unreactArrival.await()
            assertNotNull(
                "A state=${a.state.value}; B state=${b.state.value}; A events=$aEvents; B events=$bEvents",
                receivedUnreact,
            )

            val replay = a.chathistory(
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, CHANNEL, limit = 50),
            )
            assertEquals(
                parentMsgid,
                replay.events.filterIsInstance<IrcEvent.ChatMessage>()
                    .first { it.text == replyText }.replyToMsgid,
            )
            assertNotNull(
                replay.events.filterIsInstance<IrcEvent.TagMessage>().firstOrNull {
                    it.reactEmoji == "👍" && it.reactTargetMsgid == parentMsgid
                },
            )
            assertNotNull(
                replay.events.filterIsInstance<IrcEvent.Raw>().firstOrNull {
                    it.message.tags["+draft/unreact"] == "👍"
                },
            )
        } finally {
            aRecorder.cancel()
            bRecorder.cancel()
            a.stop()
            b.stop()
            scope.cancel()
        }
    }

    private fun client(nick: String, port: Int, scope: CoroutineScope): IrcClient {
        val factory = TransportFactory { host, targetPort, tls, _, proxy ->
            OkioLineTransport(host, targetPort, tls, proxy = proxy)
        }
        return IrcClient(
            IrcClientConfig(
                host = "127.0.0.1",
                port = port,
                tls = false,
                nick = nick,
                username = nick,
                realname = "MOTD interoperability test",
            ),
            factory,
            scope,
        )
    }

    private companion object {
        const val CHANNEL = "##motdtest"
        const val TIMEOUT_MS = 15_000L
    }
}
