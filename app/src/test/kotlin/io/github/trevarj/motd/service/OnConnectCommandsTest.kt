package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.client.SaslMechanism
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnConnectCommandsTest {
    @Test
    fun orderedCommandsWaitForDelayAndSendAfterReady() =
        runTest {
            val transports = mutableListOf<RecordingTransport>()
            val scope = CoroutineScope(backgroundScope.coroutineContext)
            val actor =
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = {
                        val transport = RecordingTransport().also(transports::add)
                        IrcClientConnection(
                            IrcClient(
                                IrcClientConfig(
                                    "irc.example",
                                    6697,
                                    true,
                                    "me",
                                    "me",
                                    "Me",
                                    sasl = SaslMechanism.PLAIN,
                                    saslUser = "me",
                                    saslPassword = "secret",
                                ),
                                TransportFactory { _, _, _, _, _ -> transport },
                                scope,
                            ),
                        )
                    },
                    onState = { _, _ -> },
                    onEvents = { _, _ -> },
                    onReady = { connection ->
                        val client = (connection as IrcClientConnection).client
                        runOnConnectCommands(
                            "MODE me +i\n/delay 2\n/msg Gatekeeper hello\nOPER me password",
                            nick = { (client.state.value as? IrcClientState.Ready)?.nick },
                            send = { msg ->
                                if (client.state.value !is IrcClientState.Ready) {
                                    false
                                } else if (
                                    msg.command == "PRIVMSG" &&
                                    msg.params.size == 2 &&
                                    msg.tags.isEmpty() &&
                                    msg.source == null
                                ) {
                                    client.sendSensitivePrivmsg(msg.params[0], msg.params[1])
                                } else {
                                    client.sendIfConnected(msg)
                                }
                            },
                        )
                    },
                    random = { 0.5 },
                )
            actor.start()
            runCurrent()
            val first = transports.single()
            first.feed(":srv CAP * LS :sasl")
            runCurrent()
            first.feed(":srv CAP me ACK :sasl")
            runCurrent()
            first.feed("AUTHENTICATE +")
            runCurrent()
            first.feed(":srv 903 me :Authenticated")
            runCurrent()
            assertTrue(first.commands().isEmpty())
            first.feed(":srv 001 me :Welcome")
            runCurrent()
            assertEquals(listOf("MODE me +i"), first.commands())
            first.feed(":srv 005 me CHANTYPES=# :supported")
            runCurrent()
            advanceTimeBy(1_999)
            runCurrent()
            assertEquals(listOf("MODE me +i"), first.commands())
            advanceTimeBy(1)
            runCurrent()
            val expected = listOf("MODE me +i", "PRIVMSG Gatekeeper hello", "OPER me password")
            assertEquals(expected, first.commands())
            first.feed(":srv 005 me MONITOR=10 :supported")
            runCurrent()
            assertEquals(expected, first.commands())

            // A physical disconnect during a delay cancels its pending tail.
            first.finish()
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            val second = transports.last()
            assertEquals(2, transports.size)
            second.register()
            runCurrent()
            assertEquals(listOf("MODE me +i"), second.commands())
            second.finish()
            runCurrent()
            // First retry served 2s; the next failed socket escalates to 4s.
            advanceTimeBy(3_999)
            runCurrent()
            assertEquals(listOf("MODE me +i"), second.commands())
            assertEquals(2, transports.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(3, transports.size)
            val third = transports.last()
            third.register()
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(expected, third.commands())
            actor.stopAndJoin()
        }

    @Test
    fun skipsContextOnlyInvalidDelayAndMalformedWireButKeepsAllowedCommands() =
        runTest {
            val sent = mutableListOf<String>()
            val skipped = mutableListOf<Int>()
            val script =
                listOf(
                    "/me waves",
                    "//hello",
                    "/query friend",
                    "/part #a",
                    "/hop",
                    "/topic foo",
                    "/kick nick",
                    "/ban nick",
                    "/invite nick",
                    "/list",
                    "/",
                    "/delay -1",
                    "/DELAY NaN",
                    "/delay 3601",
                    "/delay 1 2",
                    "@tag=ok",
                    "X".repeat(511),
                    "/mode +i",
                    "/mode #room +o nick",
                    "/invite nick #room",
                    "/whois friend",
                    "/ctcp friend VERSION",
                    "/join #room key",
                    "/notice friend hey",
                    "/nick newer",
                    "/setname New Name",
                    "/away gone",
                    "/knock #room hi",
                    "/motd irc.example",
                    "/raw @a=b PRIVMSG friend :tagged",
                    "   ",
                ).joinToString("\n")
            runOnConnectCommands(
                script,
                nick = { "me" },
                send = {
                    sent += it.serialize()
                    true
                },
                onSkipped = skipped::add,
            )
            assertEquals((1..17).toList(), skipped)
            assertEquals(
                listOf(
                    "MODE me +i",
                    "MODE #room +o nick",
                    "INVITE nick #room",
                    "WHOIS friend",
                    "PRIVMSG friend \u0001VERSION\u0001",
                    "JOIN #room key",
                    "NOTICE friend hey",
                    "NICK newer",
                    "SETNAME :New Name",
                    "AWAY gone",
                    "KNOCK #room hi",
                    "MOTD irc.example",
                    "@a=b PRIVMSG friend tagged",
                ),
                sent,
            )
        }

    @Test
    fun fractionalDelayUsesVirtualTimeAndKeepsLineNumbers() =
        runTest {
            val sent = mutableListOf<String>()
            val skipped = mutableListOf<Int>()
            val job =
                backgroundScope.launch {
                    runOnConnectCommands(
                        "/delay .5\n/delay invalid\n/nick me",
                        { "me" },
                        {
                            sent += it.serialize()
                            true
                        },
                        onSkipped = skipped::add,
                    )
                }
            runCurrent()
            assertTrue(sent.isEmpty())
            advanceTimeBy(499)
            runCurrent()
            assertTrue(sent.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(2), skipped)
            assertEquals(listOf("NICK me"), sent)
            job.join()
        }

    @Test
    fun stopsOnRejectedWriteReportsOnlyFailedStepAndRethrowsCancellation() =
        runTest {
            val sent = mutableListOf<String>()
            val failures = mutableListOf<Pair<Int, String>>()
            val missingNick = mutableListOf<Int>()
            runOnConnectCommands(
                "/mode +i",
                { null },
                {
                    sent += it.command
                    true
                },
                onSkipped = missingNick::add,
            )
            assertEquals(listOf(1), missingNick)
            assertTrue(sent.isEmpty())
            runOnConnectCommands("/join #one\n/nick next", { "me" }, {
                sent += it.command
                false
            })
            assertEquals(listOf("JOIN"), sent)
            runOnConnectCommands(
                "/join #one\n/nick next",
                { "me" },
                { throw IllegalStateException("secret line") },
                onWriteFailed = { step, category -> failures += step to category },
            )
            assertEquals(listOf(1 to "IllegalStateException"), failures)
            try {
                runOnConnectCommands("/nick me", { "me" }, { throw kotlinx.coroutines.CancellationException("disconnect") })
                error("cancellation swallowed")
            } catch (_: kotlinx.coroutines.CancellationException) {
                // The actor owns cancellation, including a delay in progress.
            }
        }

    private class RecordingTransport : IrcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()

        override suspend fun connect() = Unit

        override val incoming = inbound.consumeAsFlow()

        override suspend fun send(line: String) {
            sent += line
        }

        override suspend fun close() {
            inbound.close()
        }

        suspend fun feed(line: String) {
            inbound.send(line)
        }

        fun finish() {
            inbound.close()
        }

        fun commands() = sent.filter { it.startsWith("MODE ") || it.startsWith("PRIVMSG ") || it.startsWith("OPER ") }

        suspend fun register() {
            feed(":srv CAP * LS :sasl")
            feed(":srv CAP me ACK :sasl")
            feed("AUTHENTICATE +")
            feed(":srv 903 me :Authenticated")
            feed(":srv 001 me :Welcome")
        }
    }
}
