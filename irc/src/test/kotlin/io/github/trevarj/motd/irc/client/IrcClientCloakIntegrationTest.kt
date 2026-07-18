package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.transport.OkioLineTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in proof against CLoak v0.4.0 backed by the local Ergo fixture.
 *
 * The fixture uses a CLoak user `motd`, network `libera`, password `motdtest`, autojoins
 * [CHANNEL], and listens without TLS on 127.0.0.1:6699. See docs/cloak.md for the product-facing
 * connection recipe; this test intentionally stays pure JVM and exercises the real wire client.
 */
class IrcClientCloakIntegrationTest {
    @Test
    fun `PASS live messaging and reconnect playback interoperate with CLoak`() = runBlocking {
        assumeTrue(System.getenv("MOTD_RUN_CLOAK_INTEROP") == "1")
        val cloakHost = System.getenv("MOTD_CLOAK_HOST") ?: "127.0.0.1"
        val cloakPort = System.getenv("MOTD_CLOAK_PORT")?.toIntOrNull() ?: 6699
        val cloakPassword = System.getenv("MOTD_CLOAK_PASSWORD") ?: "motd/libera:motdtest"
        val ergoPort = System.getenv("MOTD_ERGO_PORT")?.toIntOrNull() ?: 6667
        val suffix = (System.currentTimeMillis() % 100_000).toString()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cloaked = client(
            host = cloakHost,
            port = cloakPort,
            nick = "motdcloak$suffix",
            username = "motd-interop",
            serverPassword = cloakPassword,
            scope = scope,
        )
        val direct = client(
            host = "127.0.0.1",
            port = ergoPort,
            nick = "motddirect$suffix",
            username = "motddirect$suffix",
            scope = scope,
        )
        val cloakEvents = CopyOnWriteArrayList<IrcEvent>()
        val cloakRecorder = scope.launch { cloaked.broadcastEvents.collect(cloakEvents::add) }
        try {
            cloaked.start()
            direct.start()
            val ready = withTimeout(TIMEOUT_MS) {
                cloaked.state.filterIsInstance<IrcClientState.Ready>().first()
            }
            withTimeout(TIMEOUT_MS) { direct.state.filterIsInstance<IrcClientState.Ready>().first() }
            assertTrue(ready.caps.containsAll(setOf("batch", "message-tags", "server-time")))
            assertFalse("sasl" in ready.caps)
            assertFalse("draft/chathistory" in ready.caps)

            val directJoined = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(TIMEOUT_MS) {
                    direct.broadcastEvents.filterIsInstance<IrcEvent.Joined>()
                        .first { it.channel == CHANNEL && it.isSelf }
                }
            }
            direct.send(IrcMessage(command = "JOIN", params = listOf(CHANNEL)))
            directJoined.await()

            val inboundText = "cloak-inbound-$suffix"
            val inbound = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(TIMEOUT_MS) {
                    cloaked.broadcastEvents.filterIsInstance<IrcEvent.ChatMessage>().first { it.text == inboundText }
                }
            }
            direct.sendMessage(CHANNEL, inboundText, null)
            inbound.await()

            val outboundText = "cloak-outbound-$suffix"
            val outbound = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(TIMEOUT_MS) {
                    direct.broadcastEvents.filterIsInstance<IrcEvent.ChatMessage>().first { it.text == outboundText }
                }
            }
            cloaked.sendMessage(CHANNEL, outboundText, null)
            outbound.await()

            cloaked.stop()
            delay(DETACH_GRACE_MS)
            val gapText = "cloak-gap-$suffix"
            val gapSentAt = System.currentTimeMillis()
            direct.sendMessage(CHANNEL, gapText, null)
            delay(BUFFER_GRACE_MS)

            val replay = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(TIMEOUT_MS) {
                    cloaked.broadcastEvents.filterIsInstance<IrcEvent.ChatMessage>().first { it.text == gapText }
                }
            }
            cloaked.start()
            withTimeout(TIMEOUT_MS) { cloaked.state.filterIsInstance<IrcClientState.Ready>().first() }
            val replayed = replay.await()
            assertTrue(replayed.ctx.serverTime >= gapSentAt - SERVER_TIME_TOLERANCE_MS)
            delay(DUPLICATE_GRACE_MS)
            assertEquals(1, cloakEvents.filterIsInstance<IrcEvent.ChatMessage>().count { it.text == gapText })
        } finally {
            cloakRecorder.cancel()
            cloaked.stop()
            direct.stop()
            scope.cancel()
        }
    }

    private fun client(
        host: String,
        port: Int,
        nick: String,
        username: String,
        serverPassword: String? = null,
        scope: CoroutineScope,
    ): IrcClient {
        val factory = TransportFactory { targetHost, targetPort, tls, _, proxy ->
            OkioLineTransport(targetHost, targetPort, tls, proxy = proxy)
        }
        return IrcClient(
            IrcClientConfig(
                host = host,
                port = port,
                tls = false,
                nick = nick,
                username = username,
                realname = "MOTD CLoak interoperability test",
                serverPassword = serverPassword,
            ),
            factory,
            scope,
        )
    }

    private companion object {
        const val CHANNEL = "##motdtest"
        const val TIMEOUT_MS = 20_000L
        const val DETACH_GRACE_MS = 500L
        const val BUFFER_GRACE_MS = 500L
        const val DUPLICATE_GRACE_MS = 750L
        const val SERVER_TIME_TOLERANCE_MS = 2_000L
    }
}
