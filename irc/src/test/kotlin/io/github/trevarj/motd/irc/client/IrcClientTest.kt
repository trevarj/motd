package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.ServerTimeSource
import io.github.trevarj.motd.irc.ext.SearchRequest
import io.github.trevarj.motd.irc.ext.SearchResultKind
import io.github.trevarj.motd.irc.ext.SearchResultMessage
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.transport.TransportFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class IrcClientTest {
    /**
     * Client scope for tests: an Unconfined test dispatcher on the test scheduler so launched
     * coroutines run eagerly, while delays still use virtual time. Child of backgroundScope so
     * it is torn down automatically.
     */
    private fun TestScope.clientScope(): CoroutineScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    private fun config(
        sasl: SaslMechanism = SaslMechanism.NONE,
        saslUser: String? = null,
        saslPassword: String? = null,
        bouncerNetId: String? = null,
        extraCaps: Set<String> = emptySet(),
    ) = IrcClientConfig(
        host = "irc.example.org",
        port = 6697,
        tls = true,
        nick = "motd",
        username = "motd",
        realname = "motd User",
        sasl = sasl,
        saslUser = saslUser,
        saslPassword = saslPassword,
        bouncerNetId = bouncerNetId,
        extraCaps = extraCaps,
    )

    /** Standard advertised caps line covering all tiers Libera/soju provide. */
    private val fullLs =
        "sasl cap-notify message-tags server-time batch labeled-response echo-message " +
            "multi-prefix account-tag extended-join userhost-in-names " +
            "draft/chathistory draft/read-marker soju.im/bouncer-networks"
    private val multilineLs =
        "$fullLs standard-replies draft/multiline=max-bytes=4096,max-lines=16"

    private fun responseLabel(line: String): String =
        checkNotNull(Regex("label=(motd-\\d+)").find(line)) { "missing response label in $line" }
            .groupValues[1]

    @Test
    fun `joined channels track only self membership before event publication`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            val observed = mutableListOf<Set<String>>()
            val job =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    client.broadcastEvents.collect { event ->
                        if (event is IrcEvent.Joined || event is IrcEvent.Parted || event is IrcEvent.Kicked) {
                            observed += client.joinedChannels.value
                        }
                    }
                }

            ft.feed(":alice!u@h JOIN #Room")
            runCurrent()
            assertEquals(emptySet<String>(), client.joinedChannels.value)
            ft.feed(":MOTD!u@h JOIN #Room")
            runCurrent()
            assertEquals(setOf("#Room"), client.joinedChannels.value)
            ft.feed(":motd!u@h JOIN #rOOM")
            ft.feed(":motd!u@h JOIN #Other")
            runCurrent()
            assertEquals(setOf("#Room", "#Other"), client.joinedChannels.value)
            ft.feed(":alice!u@h PART #Room :leaving")
            ft.feed(":op!u@h KICK #Room alice :removed")
            runCurrent()
            assertEquals(setOf("#Room", "#Other"), client.joinedChannels.value)
            ft.feed(":motd!u@h PART #ROOM :leaving")
            runCurrent()
            assertEquals(setOf("#Other"), client.joinedChannels.value)
            ft.feed(":motd!u@h JOIN #Room")
            ft.feed(":op!u@h KICK #rOoM MOTD :removed")
            runCurrent()
            assertEquals(setOf("#Other"), client.joinedChannels.value)
            job.cancelAndJoin()

            assertEquals(
                listOf(
                    emptySet(),
                    setOf("#Room"),
                    setOf("#Room"),
                    setOf("#Room", "#Other"),
                    setOf("#Room", "#Other"),
                    setOf("#Room", "#Other"),
                    setOf("#Other"),
                    setOf("#Room", "#Other"),
                    setOf("#Other"),
                ),
                observed,
            )
        }

    @Test
    fun `joined channels keep server spelling and use current CASEMAPPING`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredWithIsupport(ft, "CASEMAPPING=ascii")
            ft.feed(":motd!u@h JOIN #Room[")
            ft.feed(":motd!u@h JOIN #Room{")
            runCurrent()
            assertEquals(setOf("#Room[", "#Room{"), client.joinedChannels.value)

            ft.feed(":srv 005 motd CASEMAPPING=rfc1459 :are supported")
            ft.feed(":motd!u@h JOIN #ROOM{")
            runCurrent()
            assertEquals(setOf("#Room[", "#Room{"), client.joinedChannels.value)
            ft.feed(":motd!u@h PART #rOOM{ :leaving")
            runCurrent()
            assertEquals(emptySet<String>(), client.joinedChannels.value)

            ft.feed(":motd!u@h JOIN #Spelling[")
            ft.feed(":srv 005 motd CASEMAPPING=ascii :are supported")
            ft.feed(":motd!u@h PART #spelling{ :different channel")
            runCurrent()
            assertEquals(setOf("#Spelling["), client.joinedChannels.value)
            ft.feed(":op!u@h KICK #sPELLING[ motd :removed")
            runCurrent()
            assertEquals(emptySet<String>(), client.joinedChannels.value)
        }

    @Test
    fun `stop restart and EOF clear current socket membership`() =
        runTest {
            val first = FakeTransport()
            val second = FakeTransport()
            var currentTransport = first
            val client =
                registered(
                    first,
                    factory = TransportFactory { _, _, _, _, _ -> currentTransport },
                )
            first.feed(":motd!u@h JOIN #First")
            runCurrent()
            assertEquals(setOf("#First"), client.joinedChannels.value)

            client.stop()
            assertEquals(emptySet<String>(), client.joinedChannels.value)
            runCurrent()
            currentTransport = second
            client.start()
            assertEquals(emptySet<String>(), client.joinedChannels.value)
            runCurrent()
            second.feed(":srv CAP * LS :$fullLs")
            runCurrent()
            second.feed(":srv CAP motd ACK :$fullLs")
            runCurrent()
            second.feed(":srv 001 motd :Welcome")
            second.feed(":srv 005 motd CHATHISTORY=100 :are supported")
            runCurrent()
            assertTrue(client.state.value is IrcClientState.Ready)
            assertEquals(emptySet<String>(), client.joinedChannels.value)
            second.feed(":motd!u@h JOIN #Second")
            runCurrent()
            assertEquals(setOf("#Second"), client.joinedChannels.value)

            val membershipAtDisconnect =
                clientScope().async {
                    client.broadcastEvents.filterIsInstance<IrcEvent.Disconnected>().first()
                    client.joinedChannels.value
                }
            second.eof()
            runCurrent()
            client.awaitTermination()
            assertEquals(IrcClientState.Disconnected, client.state.value)
            assertEquals(emptySet<String>(), client.joinedChannels.value)
            assertEquals(emptySet<String>(), membershipAtDisconnect.await())
        }

    @Test
    fun `live netjoin updates membership but parsed context and playback do not`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            val observed = mutableListOf<Pair<IrcEvent, Set<String>>>()
            val job =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    client.broadcastEvents.collect { event ->
                        observed += event to client.joinedChannels.value
                    }
                }

            ft.feed("BATCH +peers netjoin server.a server.b")
            ft.feed("@batch=peers :alice!u@h JOIN #Peer")
            ft.feed("BATCH -peers")
            runCurrent()
            assertEquals(emptySet<String>(), client.joinedChannels.value)

            ft.feed("BATCH +live netjoin server.a server.b")
            ft.feed("@batch=live :motd!u@h JOIN #Live")
            ft.feed("@batch=live :alice!u@h JOIN #Peer")
            runCurrent()
            assertEquals(emptySet<String>(), client.joinedChannels.value)
            ft.feed("BATCH -live")
            runCurrent()
            assertEquals(setOf("#Live"), client.joinedChannels.value)
            val live = observed.last()
            val liveBatch = live.first as IrcEvent.NetworkBatch
            assertEquals(false, liveBatch.historyMetadata?.isContext)
            assertEquals(setOf("#Live"), live.second)

            ft.feed("@draft/chathistory-context BATCH +context netjoin server.a server.b")
            ft.feed("@batch=context :motd!u@h JOIN #Context")
            ft.feed("BATCH -context")
            runCurrent()
            assertEquals(setOf("#Live"), client.joinedChannels.value)
            val context = observed.last().first as IrcEvent.NetworkBatch
            assertEquals(true, context.historyMetadata?.isContext)
            assertEquals("#Context", (context.events.single() as IrcEvent.Joined).channel)

            for ((batchType, source, channel) in listOf(
                Triple("chathistory", IrcEvent.PlaybackSource.CHATHISTORY, "#History"),
                Triple("znc.in/playback", IrcEvent.PlaybackSource.ZNC_PLAYBACK, "#Playback"),
            )) {
                ft.feed("BATCH +history $batchType $channel")
                ft.feed("@batch=history BATCH +nested netjoin server.a server.b")
                ft.feed("@batch=nested :motd!u@h JOIN $channel")
                ft.feed("@batch=history BATCH -nested")
                ft.feed("BATCH -history")
                runCurrent()
                assertEquals(setOf("#Live"), client.joinedChannels.value)
                val playback = observed.last().first as IrcEvent.PlaybackBatch
                assertEquals(source, playback.source)
                val nested = playback.events.single() as IrcEvent.NetworkBatch
                assertEquals(false, nested.historyMetadata?.isContext)
                assertTrue((nested.events.single() as IrcEvent.Joined).isSelf)
                assertEquals(channel, (nested.events.single() as IrcEvent.Joined).channel)
                assertEquals(setOf("#Live"), observed.last().second)
            }
            job.cancelAndJoin()
        }

    @Test
    fun `CTCP VERSION replies with framed app version only to PRIVMSG`() =
        runTest {
            val ft = FakeTransport()
            registered(ft, clientVersion = "motd 0.19.0-debug")

            ft.feed(":alice!u@h PRIVMSG motd :\u0001VERSION\u0001")
            runCurrent()
            val notices = ft.sent.filter { it.startsWith("NOTICE ") }
            assertEquals(listOf("NOTICE alice :\u0001VERSION motd 0.19.0-debug\u0001"), notices)

            ft.feed(":alice!u@h NOTICE motd :\u0001VERSION\u0001")
            runCurrent()
            assertEquals(notices, ft.sent.filter { it.startsWith("NOTICE ") })
        }

    @Test
    fun `sensitive NickServ self echo is not published`() =
        runTest {
            val ft = FakeTransport()
            val client = IrcClient(config(), ft.factory(), clientScope())
            client.start()
            runCurrent()
            ft.feed(":srv CAP * LS :echo-message")
            runCurrent()
            ft.feed(":srv CAP motd ACK :echo-message")
            runCurrent()
            ft.feed(":srv 001 motd :welcome")
            runCurrent()
            val observed = async { client.broadcastEvents.filterIsInstance<IrcEvent.ChatMessage>().first() }
            runCurrent()
            val secret = "REGISTER generated-secret me@example.org"

            assertTrue(client.sendSensitivePrivmsg("NickServ", secret))
            ft.feed(":motd!u@h PRIVMSG NickServ :$secret")
            ft.feed(":alice!u@h PRIVMSG motd :ordinary")

            assertEquals("ordinary", observed.await().text)
        }

    @Test
    fun `draft account registration reports success and verification`() =
        runTest {
            val ft = FakeTransport()
            val client = IrcClient(config(), ft.factory(), clientScope())
            client.start()
            runCurrent()
            val cap = "draft/account-registration=email-required,min-password-length=16,max-password-length=64 standard-replies"
            ft.feed(":srv CAP * LS :$cap")
            runCurrent()
            ft.feed(":srv CAP motd ACK :$cap")
            runCurrent()
            ft.feed(":srv 001 motd :welcome")
            runCurrent()

            assertEquals(true, client.accountRegistrationPolicy?.emailRequired)
            val registering = async { client.registerAccount("*", "me@example.org", "0123456789abcdef") }
            runCurrent()
            assertTrue(ft.sent.last().startsWith("REGISTER * me@example.org "))
            ft.feed(":srv REGISTER VERIFICATION_REQUIRED motd :check email")
            assertEquals(
                AccountRegistrationResult.VerificationRequired("motd", "check email"),
                registering.await(),
            )

            val verifying = async { client.verifyAccount("motd", "code") }
            runCurrent()
            ft.feed(":srv VERIFY SUCCESS motd :done")
            assertEquals(AccountRegistrationResult.Success("motd", "done"), verifying.await())
        }

    @Test
    fun `draft account registration surfaces standard failure without password text`() =
        runTest {
            val ft = FakeTransport()
            val client = IrcClient(config(), ft.factory(), clientScope())
            client.start()
            runCurrent()
            val cap = "draft/account-registration standard-replies"
            ft.feed(":srv CAP * LS :$cap")
            runCurrent()
            ft.feed(":srv CAP motd ACK :$cap")
            runCurrent()
            ft.feed(":srv 001 motd :welcome")
            runCurrent()
            val result = async { runCatching { client.registerAccount("*", null, "never-log-this-password") } }
            runCurrent()
            ft.feed(":srv FAIL REGISTER ACCOUNT_EXISTS motd :already exists")
            val error = result.await().exceptionOrNull() as IrcCommandException
            assertEquals("ACCOUNT_EXISTS", error.code)
            assertFalse(error.message.orEmpty().contains("never-log"))
        }

    @Test
    fun `disconnected chat send reports no transport acceptance`() =
        runTest {
            val client = IrcClient(config(), FakeTransport().factory(), clientScope())

            val accepted = client.sendMessage("#chan", "not sent", null, "motd-disconnected")

            assertFalse(accepted)
        }

    @Test
    fun `bouncer list reports a disconnected or failed transport`() =
        runTest {
            val disconnected = IrcClient(config(), FakeTransport().factory(), clientScope())
            try {
                disconnected.bouncerListNetworks()
                fail("disconnected LISTNETWORKS must not look like an empty snapshot")
            } catch (_: IrcDisconnectedException) {
                // Expected: discovery can render a retryable connection failure.
            }

            val transport = FakeTransport()
            val connected = IrcClient(config(), transport.factory(), clientScope())
            connected.start()
            runCurrent()
            transport.sendFailure = IOException("socket closed")
            try {
                connected.bouncerListNetworks()
                fail("failed LISTNETWORKS write must propagate")
            } catch (error: IOException) {
                assertEquals("socket closed", error.message)
            }
        }

    @Test
    fun `bouncer list refuses to answer from cache when the link has gone silent`() =
        runTest {
            val ft = FakeTransport()
            val client = IrcClient(config(SaslMechanism.PLAIN, "alice", "s3cret"), ft.factory(), clientScope())
            client.start()
            runCurrent()
            ft.feed(":srv CAP * LS :$fullLs")
            runCurrent()
            ft.feed(":srv CAP motd ACK :$fullLs")
            runCurrent()
            ft.feed("AUTHENTICATE +")
            runCurrent()
            ft.feed(":srv 903 motd :SASL authentication successful")
            runCurrent()
            ft.feed(":srv 001 motd :Welcome to the network")
            ft.feed(":srv 005 motd PREFIX=(ov)@+ :are supported")
            runCurrent()
            assertTrue(client.state.value is IrcClientState.Ready)

            // A populated snapshot is the case that used to answer without touching the socket at all.
            ft.feed(":srv BOUNCER NETWORK 1 name=libera;state=connected")
            runCurrent()
            assertTrue(client.bouncerNetworks.value.isNotEmpty())

            // The peer is gone but the OS has not failed the socket yet, so the LISTNETWORKS write
            // still succeeds and nothing comes back. Returning the cached rows here would report a
            // successful refresh against a bouncer that is no longer there.
            try {
                client.bouncerListNetworks()
                fail("a refresh with no reply must not be answered from the cached snapshot")
            } catch (error: IrcDisconnectedException) {
                assertEquals("BOUNCER LISTNETWORKS", error.ircCommand)
            }
        }

    @Test
    fun `unconfirmed bouncer add is a server rejection`() =
        runTest {
            val transport = FakeTransport()
            val client = IrcClient(config(), transport.factory(), clientScope())
            client.start()
            runCurrent()

            try {
                client.bouncerAddNetwork(mapOf("name" to "New", "host" to "irc.new.example"))
                fail("missing ADDNETWORK confirmation must not report success")
            } catch (error: IrcCommandException) {
                assertEquals("BOUNCER ADDNETWORK", error.ircCommand)
                assertEquals("NO_RESPONSE", error.code)
            }
        }

    @Test
    fun `registration happy path with SASL PLAIN reaches Ready`() =
        runTest {
            val ft = FakeTransport()
            val client = IrcClient(config(SaslMechanism.PLAIN, "alice", "s3cret"), ft.factory(), clientScope())
            client.start()
            runCurrent()

            // Opening lines.
            assertEquals("CAP LS 302", ft.sent[0])
            assertEquals("NICK motd", ft.sent[1])
            assertEquals("USER motd 0 * :motd User", ft.sent[2])

            ft.feed(":srv CAP * LS :$fullLs")
            runCurrent()

            // A CAP REQ was issued containing sasl and echo-message.
            val reqLine = ft.sent.first { it.startsWith("CAP REQ") }
            assertTrue(reqLine.contains("sasl"))
            assertTrue(reqLine.contains("echo-message"))

            ft.feed(":srv CAP motd ACK :$fullLs")
            runCurrent()

            assertEquals("AUTHENTICATE PLAIN", ft.sent.last())
            ft.feed("AUTHENTICATE +")
            runCurrent()

            // Exact base64 of authzid\0authcid\0password.
            val expected = Base64.getEncoder().encodeToString("alice\u0000alice\u0000s3cret".toByteArray())
            assertEquals("AUTHENTICATE $expected", ft.sent.last())

            ft.feed(":srv 903 motd :SASL authentication successful")
            runCurrent()
            assertEquals("CAP END", ft.sent.last())

            ft.feed(":srv 001 motd :Welcome to the network")
            ft.feed(":srv 005 motd CHATHISTORY=100 MONITOR=10 PREFIX=(ov)@+ :are supported")
            runCurrent()

            val state = client.state.value
            assertTrue(state is IrcClientState.Ready)
            state as IrcClientState.Ready
            assertEquals("motd", state.nick)
            assertEquals("10", state.isupport["MONITOR"])
            assertTrue(client.hasCap("sasl"))
            assertTrue(client.hasCap("echo-message"))
        }

    @Test
    fun `live self nick change updates Ready and runs one recovery sequence`() =
        runTest {
            val ft = FakeTransport()
            val client =
                IrcClient(
                    config().copy(
                        nickServPassword = "nick-secret",
                        nickServRecoveryCommands = listOf("REGAIN"),
                    ),
                    ft.factory(),
                    clientScope(),
                )
            client.start()
            runCurrent()
            ft.feed(":srv CAP * LS :$fullLs")
            runCurrent()
            ft.feed(":srv CAP motd ACK :$fullLs")
            runCurrent()
            ft.feed(":srv 001 motd :Welcome")
            runCurrent()

            ft.feed(":motd!u@h NICK motd-away")
            runCurrent()

            assertEquals("motd-away", (client.state.value as IrcClientState.Ready).nick)
            assertEquals(
                listOf(
                    "PRIVMSG NickServ :REGAIN motd nick-secret",
                    "NICK motd",
                ),
                ft.sent.takeLast(2),
            )

            ft.feed(":motd-away!u@h NICK motd")
            runCurrent()
            assertEquals("motd", (client.state.value as IrcClientState.Ready).nick)

            val recoveryWrites = ft.sent.count { it.contains("REGAIN") }
            ft.feed(":motd!u@h NICK motd-away-again")
            runCurrent()
            assertEquals("motd-away-again", (client.state.value as IrcClientState.Ready).nick)
            assertEquals(recoveryWrites, ft.sent.count { it.contains("REGAIN") })
        }

    @Test
    fun `shared recovery guard suppresses recovery on automatic reconnect`() =
        runTest {
            val guard = NickRecoveryGuard()
            val cfg =
                config().copy(
                    nickServPassword = "nick-secret",
                    nickServRecoveryCommands = listOf("REGAIN"),
                )
            val firstTransport = FakeTransport()
            val first = IrcClient(cfg, firstTransport.factory(), clientScope(), nickRecoveryGuard = guard)
            first.start()
            runCurrent()
            firstTransport.feed(":srv CAP * LS :$fullLs")
            runCurrent()
            firstTransport.feed(":srv CAP motd ACK :$fullLs")
            runCurrent()
            firstTransport.feed(":srv 001 motd :Welcome")
            firstTransport.feed(":motd!u@h NICK motd-away")
            runCurrent()
            assertTrue(firstTransport.sent.any { it.contains("REGAIN") })
            first.stop()

            val secondTransport = FakeTransport()
            val second = IrcClient(cfg, secondTransport.factory(), clientScope(), nickRecoveryGuard = guard)
            second.start()
            runCurrent()
            secondTransport.feed(":srv 433 * motd :Nickname is already in use")
            secondTransport.feed(":srv CAP * LS :$fullLs")
            runCurrent()
            secondTransport.feed(":srv CAP motd_ ACK :$fullLs")
            runCurrent()
            secondTransport.feed(":srv 001 motd_ :Welcome")
            runCurrent()

            assertTrue(second.state.value is IrcClientState.Ready)
            assertEquals("motd_", (second.state.value as IrcClientState.Ready).nick)
            assertEquals(1, secondTransport.sent.count { it == "NICK motd" })
            assertTrue(secondTransport.sent.none { it.contains("REGAIN") })
        }

    @Test
    fun `SASL 904 is fatal Failed`() =
        runTest {
            val ft = FakeTransport()
            val client = IrcClient(config(SaslMechanism.PLAIN, "bob", "wrong"), ft.factory(), clientScope())
            client.start()
            val critical =
                clientScope().async {
                    buildList { for (event in client.criticalEvents) add(event) }
                }
            runCurrent()
            ft.feed(":srv CAP * LS :$fullLs")
            runCurrent()
            ft.feed(":srv CAP bob ACK :$fullLs")
            runCurrent()
            ft.feed("AUTHENTICATE +")
            runCurrent()
            ft.feed(":srv 904 bob :SASL authentication failed")
            runCurrent()

            val state = client.state.value
            assertTrue(state is IrcClientState.Failed)
            state as IrcClientState.Failed
            assertTrue(state.fatal)
            client.awaitTermination()
            assertEquals(1, critical.await().count { it is IrcEvent.Disconnected })
        }

    @Test
    fun `SASL required but cap absent is fatal`() =
        runTest {
            val ft = FakeTransport()
            val client = IrcClient(config(SaslMechanism.PLAIN, "bob", "pw"), ft.factory(), clientScope())
            client.start()
            runCurrent()
            // LS without sasl.
            ft.feed(":srv CAP * LS :message-tags server-time batch")
            runCurrent()
            ft.feed(":srv CAP bob ACK :message-tags server-time batch")
            runCurrent()

            val state = client.state.value
            assertTrue(state is IrcClientState.Failed)
            assertTrue((state as IrcClientState.Failed).fatal)
        }

    @Test
    fun `BOUNCER BIND ignores post-bind CAP changes while waiting for welcome`() =
        runTest {
            val ft = FakeTransport()
            val client = IrcClient(config(bouncerNetId = "42"), ft.factory(), clientScope())
            client.start()
            runCurrent()
            ft.feed(":srv CAP * LS :$fullLs")
            runCurrent()
            ft.feed(":srv CAP motd ACK :$fullLs")
            runCurrent()

            val bindIdx = ft.sent.indexOfFirst { it == "BOUNCER BIND 42" }
            val firstCapEndIdx = ft.sent.indexOfFirst { it == "CAP END" }
            val ackReqIdx = ft.sent.indexOfLast { it.startsWith("CAP REQ") }
            assertTrue("BIND present", bindIdx >= 0)
            assertTrue("BIND after REQ", bindIdx > ackReqIdx)
            assertTrue("initial CAP END after BIND", firstCapEndIdx > bindIdx)

            ft.feed(":srv CAP motd DEL :extended-monitor")
            runCurrent()
            ft.feed(":srv CAP motd DEL :draft/extended-monitor")
            runCurrent()

            val capEndCount = ft.sent.count { it == "CAP END" }
            assertEquals("CAP END must not repeat after post-bind capability mutation", 1, capEndCount)
        }

    @Test
    fun `labeled PRIVMSG echo correlates`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)

            val label = "motd-exact-label"
            assertTrue(client.sendMessage("#chan", "hello", null, label))
            runCurrent()
            val sent = ft.sent.last()
            assertTrue(sent.contains("label=$label"))
            assertTrue(sent.contains("PRIVMSG #chan"))
            assertTrue(sent.endsWith("hello"))

            // Echo carrying the label flows through as a self ChatMessage.
            val collected = mutableListOf<IrcEvent>()
            val job = launch { client.broadcastEvents.toList(collected) }
            runCurrent() // ensure the subscriber is registered before the emit
            ft.feed("@label=$label;msgid=abc :motd!u@h PRIVMSG #chan :hello")
            runCurrent()
            job.cancel()

            val chat = collected.filterIsInstance<IrcEvent.ChatMessage>().first()
            assertEquals("hello", chat.text)
            assertTrue(chat.isSelf)
            assertEquals(label, chat.ctx.label)
            assertEquals("abc", chat.ctx.msgid)
        }

    @Test
    fun clientTagsRespectNegotiationAndClientTagDeny() =
        runTest {
            val body = "[voice 0:01 audio/ogg] https://files.example/voice.ogg"
            val marker = mapOf("+trevarj.github.io/audio" to "1")
            val label = "motd-audio"

            val allowedTransport = FakeTransport()
            val allowed = registered(allowedTransport)
            assertTrue(allowed.sendMessage("#chan", body, null, label, clientTags = marker))

            val deniedTransport = FakeTransport()
            val denied = registered(deniedTransport)
            deniedTransport.feed(":srv 005 motd CLIENTTAGDENY=trevarj.github.io/audio :are supported")
            runCurrent()
            assertTrue(denied.sendMessage("#chan", body, null, label, clientTags = marker))

            val noTagsTransport = FakeTransport()
            val noTags = registeredWithCaps(noTagsTransport, fullLs.replace("message-tags ", ""))
            assertTrue(noTags.sendMessage("#chan", body, null, label, clientTags = marker))
            runCurrent()

            val messages =
                listOf(allowedTransport, deniedTransport, noTagsTransport).map { transport ->
                    IrcMessage.parse(transport.sent.last { it.contains("PRIVMSG #chan") })
                }
            assertEquals("1", messages[0].tags["+trevarj.github.io/audio"])
            assertFalse("+trevarj.github.io/audio" in messages[1].tags)
            assertFalse("+trevarj.github.io/audio" in messages[2].tags)
            assertEquals(List(3) { label }, messages.map { it.tags["label"] })
            assertEquals(List(3) { listOf("#chan", body) }, messages.map(IrcMessage::params))
        }

    @Test
    fun `sequenced observer exposes a gap when a slow subscriber overflows`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft, observerBufferCapacity = 1)
            val observed = mutableListOf<SequencedIrcEvent>()
            val firstDelivered = CompletableDeferred<Unit>()
            val releaseCollector = CompletableDeferred<Unit>()
            val collector =
                launch {
                    client.sequencedEvents.collect { event ->
                        observed += event
                        if (observed.size == 1) {
                            firstDelivered.complete(Unit)
                            releaseCollector.await()
                        }
                    }
                }
            runCurrent()

            ft.feed(":alice!u@h PRIVMSG #chan :first")
            runCurrent()
            firstDelivered.await()
            ft.feed(":alice!u@h PRIVMSG #chan :dropped")
            ft.feed(":alice!u@h PRIVMSG #chan :last")
            runCurrent()
            releaseCollector.complete(Unit)
            runCurrent()
            collector.cancelAndJoin()

            assertEquals(listOf("first", "last"), observed.map { (it.event as IrcEvent.ChatMessage).text })
            assertEquals(observed[0].sequence + 2, observed[1].sequence)
        }

    @Test
    fun `labeled multiline send opens one client batch with blank lines preserved`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft, multilineLs)

            assertTrue(client.sendMessage("#chan", "alpha\n\nbeta", null, "motd-multi"))
            runCurrent()

            assertEquals(
                listOf(
                    "@label=motd-multi BATCH +motd-multi draft/multiline #chan",
                    "@batch=motd-multi PRIVMSG #chan alpha",
                    "@batch=motd-multi PRIVMSG #chan :",
                    "@batch=motd-multi PRIVMSG #chan beta",
                    "BATCH -motd-multi",
                ),
                ft.sent.takeLast(5),
            )
        }

    @Test
    fun `long logical line uses multiline concat fragments`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft, multilineLs)
            val body = "x".repeat(410)

            assertTrue(client.sendMessage("#chan", body, null, "motd-long"))
            runCurrent()

            val sent = ft.sent.takeLast(4)
            assertEquals("@label=motd-long BATCH +motd-long draft/multiline #chan", sent[0])
            assertEquals("@batch=motd-long PRIVMSG #chan ${"x".repeat(400)}", sent[1])
            assertEquals("@batch=motd-long;draft/multiline-concat PRIVMSG #chan ${"x".repeat(10)}", sent[2])
            assertEquals("BATCH -motd-long", sent[3])
        }

    @Test
    fun `incoming multiline batch maps to one logical chat message`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft, multilineLs)
            val collected = mutableListOf<IrcEvent>()
            val job = launch { client.broadcastEvents.toList(collected) }
            runCurrent()

            ft.feed("@label=motd-multi;msgid=abc;+reply=parent BATCH +srv1 draft/multiline #chan")
            ft.feed("@batch=srv1 :alice!u@h PRIVMSG #chan alpha")
            ft.feed("@batch=srv1 :alice!u@h PRIVMSG #chan :")
            ft.feed("@batch=srv1 :alice!u@h PRIVMSG #chan beta")
            ft.feed("BATCH -srv1")
            runCurrent()
            job.cancel()

            val chat = collected.filterIsInstance<IrcEvent.ChatMessage>().single()
            assertEquals("alpha\n\nbeta", chat.text)
            assertEquals("abc", chat.ctx.msgid)
            assertEquals("motd-multi", chat.ctx.label)
            assertEquals("parent", chat.replyToMsgid)
        }

    @Test
    fun `multiline rejection is exposed as a typed event`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft, multilineLs)
            val collected = mutableListOf<IrcEvent>()
            val job = launch { client.broadcastEvents.toList(collected) }
            runCurrent()

            ft.feed("@label=motd-multi FAIL BATCH MULTILINE_MAX_LINES :too many lines")
            runCurrent()
            job.cancel()

            val rejection = collected.filterIsInstance<IrcEvent.MultilineRejected>().single()
            assertEquals("motd-multi", rejection.label)
            assertEquals("MULTILINE_MAX_LINES", rejection.code)
        }

    @Test
    fun `reply and reaction sends use the ratified reply tag`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)

            client.sendMessage("#chan", "child", "parent-1", "motd-reply")
            runCurrent()
            assertTrue(ft.sent.last().startsWith("@+reply=parent-1;label="))

            client.sendReact("#chan", "parent-1", "👍")
            runCurrent()
            assertEquals("@+draft/react=👍;+reply=parent-1 TAGMSG #chan", ft.sent.last())
        }

    @Test
    fun `private messages attach only valid allowed channel context`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)

            client.sendMessage("alice", "help", null, "motd-context", channelContext = "#support")
            runCurrent()
            assertTrue(ft.sent.last().startsWith("@+channel-context=#support;label=motd-context"))

            client.sendMessage("#public", "no context", null, "motd-public", channelContext = "#support")
            runCurrent()
            assertFalse(ft.sent.last().contains("channel-context"))
        }

    @Test
    fun `chat labels reject values outside the wire-safe contract`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)

            val failure =
                runCatching {
                    client.sendMessage("#chan", "hello", null, "bad label")
                }

            assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
            assertFalse(ft.sent.last().contains("PRIVMSG #chan"))
        }

    @Test
    fun `live mapper prefers ratified reply and retains legacy fallback`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            val collected = mutableListOf<IrcEvent>()
            val job = launch { client.broadcastEvents.toList(collected) }
            runCurrent()

            ft.feed("@+reply=new;+draft/reply=old :alice!u@h PRIVMSG #chan :new reply")
            ft.feed("@+draft/reply=legacy :alice!u@h PRIVMSG #chan :legacy reply")
            runCurrent()
            job.cancel()

            assertEquals(
                listOf("new", "legacy"),
                collected.filterIsInstance<IrcEvent.ChatMessage>().map { it.replyToMsgid },
            )
        }

    @Test
    fun `unreact remains raw for the app-owned mutation store`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            val collected = mutableListOf<IrcEvent>()
            val job = launch { client.broadcastEvents.toList(collected) }
            runCurrent()

            ft.feed("@+draft/unreact=👍;+reply=parent-1 :alice!u@h TAGMSG #chan")
            runCurrent()
            job.cancel()

            val raw = collected.filterIsInstance<IrcEvent.Raw>().single()
            assertEquals("👍", raw.message.tags["+draft/unreact"])
            assertEquals("parent-1", raw.message.tags["+reply"])
        }

    @Test
    fun `post-welcome CLIENTTAGDENY updates Ready state`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)

            ft.feed(":srv 005 motd CLIENTTAGDENY=*,-reply :are supported")
            runCurrent()

            val ready = client.state.value as IrcClientState.Ready
            assertEquals("*,-reply", ready.isupport["CLIENTTAGDENY"])
        }

    @Test
    fun `network icon is exposed in ready isupport`() =
        runTest {
            val client =
                registeredWithIsupport(
                    FakeTransport(),
                    "draft/ICON=https://example.org/icon-{size}.png",
                )

            assertEquals(
                "https://example.org/icon-{size}.png",
                (client.state.value as IrcClientState.Ready).isupport["draft/ICON"],
            )
        }

    @Test
    fun `target classification waits for CHANTYPES or end of registration burst`() =
        runTest {
            val explicitTransport = FakeTransport()
            val explicit = registered(explicitTransport)
            assertFalse(explicit.targetClassificationReady.value)

            explicitTransport.feed(":srv 005 motd CHANTYPES=+ :are supported")
            runCurrent()
            assertTrue(explicit.targetClassificationReady.value)

            val defaultTransport = FakeTransport()
            val defaults = registered(defaultTransport)
            assertFalse(defaults.targetClassificationReady.value)

            defaultTransport.feed(":srv 376 motd :End of motd")
            runCurrent()
            assertTrue(defaults.targetClassificationReady.value)
        }

    @Test
    fun `history availability defaults references and honors zero as unlimited`() =
        runTest {
            val defaultTransport = FakeTransport()
            val defaultClient = registeredWithIsupport(defaultTransport, "CHATHISTORY=25")
            assertEquals(
                HistoryAvailability.Ready(
                    setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID),
                    25,
                    supportsConcurrentRequests = true,
                ),
                defaultClient.historyAvailability,
            )

            val timestampTransport = FakeTransport()
            val timestampClient =
                registeredWithIsupport(
                    timestampTransport,
                    "CHATHISTORY=0 MSGREFTYPES=timestamp",
                )
            assertEquals(
                HistoryAvailability.Ready(
                    setOf(HistoryReferenceType.TIMESTAMP),
                    Int.MAX_VALUE,
                    supportsConcurrentRequests = true,
                ),
                timestampClient.historyAvailability,
            )
            assertEquals("timestamp", (timestampClient.state.value as IrcClientState.Ready).isupport["MSGREFTYPES"])

            val unlimitedRequest =
                clientScope().async {
                    timestampClient.chathistory(
                        ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#chan", limit = 250),
                    )
                }
            runCurrent()
            assertTrue(timestampTransport.sent.last { it.contains("CHATHISTORY") }.contains(" #chan * 250"))
            unlimitedRequest.cancelAndJoin()
        }

    @Test
    fun `history availability without labeled-response reports no concurrent request support`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft, caps = fullLs.replace(" labeled-response", ""))
            val ready = client.historyAvailability as HistoryAvailability.Ready
            assertFalse(ready.supportsConcurrentRequests)
        }

    @Test
    fun `bouncer child without chathistory settles on unsupported once deferred caps land`() =
        runTest {
            // ZNC shape: the authcid carries the '/' that marks a bouncer child, and the server never
            // advertises draft/chathistory. Reporting NegotiatingOrOffline here never converged, so the
            // Ready-session history gate that waits on it stayed held for the life of the connection.
            val ft = FakeTransport()
            val caps = fullLs.replace(" draft/chathistory", "")
            val client =
                IrcClient(
                    config(sasl = SaslMechanism.PLAIN, saslUser = "motd/libera", saslPassword = "pw"),
                    ft.factory(),
                    clientScope(),
                )
            client.start()
            runCurrent()
            ft.feed(":srv CAP * LS :$caps")
            runCurrent()
            ft.feed(":srv CAP motd ACK :sasl batch message-tags server-time")
            runCurrent()
            ft.feed("AUTHENTICATE +")
            runCurrent()
            ft.feed(":srv 903 motd :SASL authentication successful")
            runCurrent()
            ft.feed(":srv 001 motd :Welcome")
            runCurrent()

            assertTrue(client.state.value is IrcClientState.Ready)
            assertFalse(client.hasCap("draft/chathistory"))
            assertTrue(client.pendingFeatureCaps.value.none { it.startsWith("draft/chathistory") })
            assertEquals(HistoryAvailability.Unsupported, client.historyAvailability)
        }

    @Test
    fun `chathistory awaiting its deferred cap ack still reads as negotiating`() =
        runTest {
            // The window the old bouncer-child fallback actually existed for: the extension was
            // requested and no ACK/NAK has landed yet, so "unsupported" would be a premature verdict.
            val ft = FakeTransport()
            val client = registered(ft, caps = fullLs.replace(" draft/chathistory", ""))
            runCurrent()
            assertEquals(HistoryAvailability.Unsupported, client.historyAvailability)

            ft.feed(":srv CAP motd NEW :draft/chathistory")
            runCurrent()
            assertEquals(HistoryAvailability.NegotiatingOrOffline, client.historyAvailability)

            ft.feed(":srv CAP motd ACK :draft/chathistory")
            runCurrent()
            assertTrue(client.historyAvailability is HistoryAvailability.Ready)
        }

    @Test
    fun `labeled chathistory reassembles nested batch`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)

            val result =
                clientScope().async {
                    client.chathistory(
                        ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#chan", limit = 50),
                    )
                }
            runCurrent()

            val labeled = ft.sent.last { it.contains("CHATHISTORY") }
            val label = responseLabel(labeled)

            // Outer chathistory batch containing a nested batch and messages.
            ft.feed("@label=$label;draft/chathistory-end BATCH +hist chathistory #chan")
            ft.feed("@batch=hist BATCH +nested draft/foo")
            ft.feed("@batch=nested :a!u@h PRIVMSG #chan :nested-line")
            ft.feed("@batch=nested BATCH -nested")
            ft.feed("@batch=hist;+reply=parent-1 :b!u@h PRIVMSG #chan :outer-line")
            ft.feed("@batch=hist;+draft/react=👍;+reply=parent-1 :b!u@h TAGMSG #chan")
            ft.feed("@batch=hist;+draft/unreact=👍;+reply=parent-1 :b!u@h TAGMSG #chan")
            ft.feed("BATCH -hist")
            runCurrent()

            val res = result.await() as ChatHistoryResponse.Messages
            assertTrue(res.endOfHistory)
            val texts = res.events.filterIsInstance<IrcEvent.ChatMessage>().map { it.text }
            assertEquals(listOf("nested-line", "outer-line"), texts)
            assertEquals(
                "parent-1",
                res.events
                    .filterIsInstance<IrcEvent.ChatMessage>()
                    .last()
                    .replyToMsgid,
            )
            assertEquals(
                "parent-1",
                res.events
                    .filterIsInstance<IrcEvent.TagMessage>()
                    .single()
                    .reactTargetMsgid,
            )
            assertEquals(
                "👍",
                res.events
                    .filterIsInstance<IrcEvent.Raw>()
                    .single()
                    .message.tags["+draft/unreact"],
            )
        }

    @Test
    fun `labeled chathistory accepts a labeled-response wrapper`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)

            val result =
                clientScope().async {
                    client.chathistory(
                        ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#chan", limit = 50),
                    )
                }
            runCurrent()

            val label = responseLabel(ft.sent.last { it.contains("CHATHISTORY") })

            ft.feed("@label=$label BATCH +wrap labeled-response")
            ft.feed("@batch=wrap;draft/chathistory-end BATCH +hist chathistory #chan")
            ft.feed("@batch=hist :a!u@h PRIVMSG #chan :first")
            ft.feed("@batch=hist :b!u@h PRIVMSG #chan :second")
            ft.feed("@batch=wrap BATCH -hist")
            ft.feed("BATCH -wrap")
            runCurrent()

            val res = result.await() as ChatHistoryResponse.Messages
            assertTrue(res.endOfHistory)
            assertEquals(
                listOf("first", "second"),
                res.events.filterIsInstance<IrcEvent.ChatMessage>().map { it.text },
            )
        }

    @Test
    fun `labeled chathistory reconstructs nested multiline message`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft, multilineLs)

            val result =
                clientScope().async {
                    client.chathistory(
                        ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#chan", limit = 50),
                    )
                }
            runCurrent()

            val label = responseLabel(ft.sent.last { it.contains("CHATHISTORY") })
            ft.feed("@label=$label BATCH +hist chathistory #chan")
            ft.feed(
                "@batch=hist;msgid=multi-1;time=2026-07-28T20:17:04.564Z " +
                    ":motd!u@h BATCH +multi draft/multiline #chan",
            )
            ft.feed("@batch=multi :motd!u@h PRIVMSG #chan :first line")
            ft.feed("@batch=multi :motd!u@h PRIVMSG #chan :second line")
            ft.feed("@batch=hist BATCH -multi")
            ft.feed("BATCH -hist")
            runCurrent()

            val response = result.await() as ChatHistoryResponse.Messages
            assertEquals(1, response.primaryMessageCount)
            assertEquals(ChatHistoryReference("multi-1", 1_785_269_824_564L), response.oldest)
            assertEquals(response.oldest, response.newest)

            val message = response.events.single() as IrcEvent.ChatMessage
            assertEquals("first line\nsecond line", message.text)
            assertEquals("multi-1", message.ctx.msgid)
            assertEquals(1_785_269_824_564L, message.ctx.serverTime)
            assertEquals(ServerTimeSource.TAG, message.ctx.serverTimeSource)
            assertTrue(message.isSelf)
        }

    @Test
    fun `labeled root close with open nested batch fails and clears aliases`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            val request =
                clientScope().async {
                    runCatching {
                        client.chathistory(
                            ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#chan", limit = 50),
                        )
                    }.exceptionOrNull()
                }
            runCurrent()
            val label = responseLabel(ft.sent.last { it.contains("CHATHISTORY") })

            ft.feed("@label=$label BATCH +history chathistory #chan")
            ft.feed("@batch=history BATCH +nested draft/example")
            ft.feed("BATCH -history")
            runCurrent()
            assertTrue(request.await() is IrcProtocolException)

            val retry =
                clientScope().async {
                    client.chathistory(
                        ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#chan", limit = 50),
                    )
                }
            runCurrent()
            val retryLabel = responseLabel(ft.sent.last { it.contains("CHATHISTORY") })
            // Reusing the failed request's nested ref proves every alias was removed eagerly.
            ft.feed("@label=$retryLabel BATCH +nested chathistory #chan")
            ft.feed("BATCH -nested")
            runCurrent()

            assertTrue(retry.await() is ChatHistoryResponse.Messages)
        }

    @Test
    fun `concurrent labeled requests remain independently correlated`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)

            val first =
                clientScope().async {
                    client.sendLabeled(
                        io.github.trevarj.motd.irc.proto
                            .IrcMessage(command = "WHOIS", params = listOf("alice")),
                    )
                }
            val second =
                clientScope().async {
                    client.sendLabeled(
                        io.github.trevarj.motd.irc.proto
                            .IrcMessage(command = "WHOIS", params = listOf("bob")),
                    )
                }
            runCurrent()
            val firstLabel = responseLabel(ft.sent.first { it.contains("WHOIS alice") })
            val secondLabel = responseLabel(ft.sent.first { it.contains("WHOIS bob") })

            ft.feed("@label=$secondLabel :srv 318 motd bob :End of WHOIS")
            ft.feed("@label=$firstLabel :srv 318 motd alice :End of WHOIS")
            runCurrent()

            assertEquals("alice", first.await().single().params[1])
            assertEquals("bob", second.await().single().params[1])
        }

    @Test
    fun `unregistered labeled response batch keeps its label on mapped children`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            val motdLine =
                clientScope().async {
                    client.broadcastEvents.first { event ->
                        event is IrcEvent.Raw && event.message.command == "372"
                    } as IrcEvent.Raw
                }
            val motdEnd =
                clientScope().async {
                    client.broadcastEvents.first { event ->
                        event is IrcEvent.Raw && event.message.command == "376"
                    } as IrcEvent.Raw
                }
            val away =
                clientScope().async {
                    client.broadcastEvents.first { it is IrcEvent.SelfAwayChanged } as IrcEvent.SelfAwayChanged
                }

            client.send(
                io.github.trevarj.motd.irc.proto.IrcMessage(
                    tags = mapOf("label" to "chat-command"),
                    command = "MOTD",
                ),
            )
            ft.feed("@label=chat-command BATCH +reply labeled-response")
            ft.feed("@batch=reply :srv 372 motd :- hello")
            ft.feed("@batch=reply :srv 376 motd :End of MOTD")
            ft.feed("@batch=reply :srv 306 motd :You have been marked as being away")
            ft.feed("BATCH -reply")
            runCurrent()

            assertEquals("chat-command", motdLine.await().message.tags["label"])
            assertEquals("chat-command", motdEnd.await().message.tags["label"])
            assertEquals("chat-command", away.await().ctx?.label)
            assertNull(away.await().ctx?.batchId)
        }

    @Test
    fun `unlabeled whois is serialized and consumed until terminal reply`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredNoCaps(ft)
            val first = clientScope().async { client.whois("alice") }
            val second = clientScope().async { client.whois("bob") }
            runCurrent()
            assertEquals(1, ft.sent.count { it.startsWith("WHOIS ") })

            ft.feed(":srv 311 motd alice user host * :Alice")
            ft.feed(":srv 318 motd alice :End of WHOIS")
            runCurrent()
            assertEquals(listOf("311", "318"), first.await().map { it.command })
            assertTrue(ft.sent.last().startsWith("WHOIS bob"))

            ft.feed(":srv 311 motd bob user host * :Bob")
            ft.feed(":srv 318 motd bob :End of WHOIS")
            runCurrent()
            assertEquals("bob", second.await().first().params[1])
        }

    @Test
    fun `unlabeled whois ends on error and timeout`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredNoCaps(ft)
            val rejected = clientScope().async { runCatching { client.whois("missing") }.exceptionOrNull() }
            runCurrent()
            ft.feed(":srv 401 motd missing :No such nick")
            runCurrent()
            assertTrue(rejected.await() is IrcCommandException)

            val timedOut = clientScope().async { runCatching { client.whois("slow") }.exceptionOrNull() }
            runCurrent()
            advanceTimeBy(30_001)
            runCurrent()
            assertTrue(timedOut.await() is IrcTimeoutException)
        }

    @Test
    fun `unlabeled whois fails when connection closes`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredNoCaps(ft)
            val request = clientScope().async { runCatching { client.whois("alice") }.exceptionOrNull() }
            runCurrent()

            ft.eof()
            runCurrent()

            assertTrue(request.await() is IrcDisconnectedException)
        }

    @Test
    fun `cancelled labeled request unregisters before a late response`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            val request =
                clientScope().async {
                    client.sendLabeled(
                        io.github.trevarj.motd.irc.proto
                            .IrcMessage(command = "WHOIS", params = listOf("alice")),
                    )
                }
            runCurrent()
            val label = responseLabel(ft.sent.last { it.contains("WHOIS alice") })
            request.cancelAndJoin()
            val late =
                clientScope().async {
                    client.broadcastEvents.first { event ->
                        event is IrcEvent.Raw && event.message.tags["label"] == label
                    }
                }

            ft.feed("@label=$label :srv 318 motd alice :late")
            runCurrent()

            assertTrue(late.await() is IrcEvent.Raw)
        }

    @Test
    fun `timed out labeled request unregisters before a late response`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            val request =
                clientScope().async {
                    runCatching {
                        client.sendLabeled(
                            io.github.trevarj.motd.irc.proto
                                .IrcMessage(command = "WHOIS", params = listOf("alice")),
                        )
                    }.exceptionOrNull()
                }
            runCurrent()
            val label = responseLabel(ft.sent.last { it.contains("WHOIS alice") })
            advanceTimeBy(30_001L)
            runCurrent()
            assertTrue(request.await() is IrcTimeoutException)
            val late =
                clientScope().async {
                    client.broadcastEvents.first { event ->
                        event is IrcEvent.Raw && event.message.tags["label"] == label
                    }
                }

            ft.feed("@label=$label :srv 318 motd alice :late")
            runCurrent()

            assertTrue(late.await() is IrcEvent.Raw)
        }

    @Test
    fun `labeled write failure unregisters before a late response`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            ft.sendFailure = IOException("write failed")

            val error =
                runCatching {
                    client.sendLabeled(
                        io.github.trevarj.motd.irc.proto
                            .IrcMessage(command = "WHOIS", params = listOf("alice")),
                    )
                }.exceptionOrNull()
            val label = responseLabel(ft.sent.last { it.contains("WHOIS alice") })
            assertTrue(error is IOException)
            ft.sendFailure = null
            val late =
                clientScope().async {
                    client.broadcastEvents.first { event ->
                        event is IrcEvent.Raw && event.message.tags["label"] == label
                    }
                }

            ft.feed("@label=$label :srv 318 motd alice :late")
            runCurrent()

            assertTrue(late.await() is IrcEvent.Raw)
        }

    @Test
    fun `critical channel retains ordered burst through clean EOF`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            val collected =
                clientScope().async {
                    buildList {
                        for (event in client.criticalEvents) add(event)
                    }
                }
            repeat(500) { index ->
                ft.feed(":alice!u@h PRIVMSG #chan :line-$index")
            }
            ft.eof()
            runCurrent()
            client.awaitTermination()

            val events = collected.await()
            assertEquals(
                (0 until 500).map { "line-$it" },
                events.filterIsInstance<IrcEvent.ChatMessage>().map { it.text },
            )
            assertTrue(events.last() is IrcEvent.Disconnected)
            assertEquals(1, events.count { it is IrcEvent.Disconnected })
        }

    @Test
    fun `socket read failure publishes one terminal event`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            val critical =
                clientScope().async {
                    buildList { for (event in client.criticalEvents) add(event) }
                }

            ft.fail(IOException("read reset"))
            runCurrent()
            client.awaitTermination()

            val disconnects = critical.await().filterIsInstance<IrcEvent.Disconnected>()
            assertEquals(1, disconnects.size)
            assertEquals("read reset", disconnects.single().reason)
        }

    @Test
    fun `CAP NEW mid session requests and emits CapsChanged`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft, caps = fullLs.replace(" draft/chathistory", ""))

            val collected = mutableListOf<IrcEvent>()
            val job = launch { client.broadcastEvents.toList(collected) }
            runCurrent() // ensure the subscriber is registered before emits

            ft.feed(":srv CAP motd NEW :draft/chathistory")
            runCurrent()
            // We should REQ the newly advertised cap.
            assertTrue(ft.sent.any { it.startsWith("CAP REQ") && it.contains("draft/chathistory") })
            assertTrue("draft/chathistory" in client.pendingFeatureCaps.value)

            ft.feed(":srv CAP motd ACK :draft/chathistory")
            runCurrent()
            assertTrue(client.hasCap("draft/chathistory"))
            assertTrue(client.pendingFeatureCaps.value.isEmpty())
            job.cancel()

            assertTrue(collected.any { it is IrcEvent.CapsChanged && it.added.contains("draft/chathistory") })
        }

    @Test
    fun `CAP NEW preserves advertised values after value-less ACK`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)

            ft.feed(
                ":srv CAP motd NEW :draft/metadata-2=before-connect,max-keys=0,max-value-bytes=1",
            )
            runCurrent()
            assertTrue(ft.sent.any { it.startsWith("CAP REQ") && it.contains("draft/metadata-2") })

            ft.feed(":srv CAP motd ACK :draft/metadata-2")
            runCurrent()

            assertTrue(
                client.caps.contains(
                    "draft/metadata-2=before-connect,max-keys=0,max-value-bytes=1",
                ),
            )
            assertEquals(client.caps, (client.state.value as IrcClientState.Ready).caps)

            client.stop()
            assertFalse(client.hasCap("draft/metadata-2"))
        }

    @Test
    fun `bouncer pre-welcome CAP NEW preserves limits through deferred ACK`() =
        runTest {
            val ft = FakeTransport()
            val client = IrcClient(config(bouncerNetId = "42"), ft.factory(), clientScope())
            val metadata = "draft/metadata-2=before-connect,max-keys=0,max-value-bytes=1"
            client.start()
            runCurrent()

            ft.feed(":srv CAP * LS :$fullLs $metadata")
            runCurrent()
            val initialCaps = ft.sent.first { it.startsWith("CAP REQ :") }.substringAfter("CAP REQ :")
            ft.feed(":srv CAP motd ACK :$initialCaps")
            runCurrent()
            ft.feed(":srv CAP motd NEW :$metadata")
            runCurrent()

            assertTrue(client.state.value is IrcClientState.Ready)
            assertTrue(client.targetClassificationReady.value)
            advanceTimeBy(RegistrationStateMachine.FALLBACK_FEATURE_CAP_DELAY_MS)
            runCurrent()
            val deferredCaps =
                ft.sent
                    .first { it == "CAP REQ :draft/metadata-2" }
                    .substringAfter("CAP REQ :")
            ft.feed(":srv CAP motd ACK :$deferredCaps")
            runCurrent()

            assertTrue(client.caps.contains(metadata))
            assertEquals(client.caps, (client.state.value as IrcClientState.Ready).caps)
        }

    @Test
    fun `bouncer fallback sends deferred CAP REQs on the first post-ready line, not the ceiling`() =
        runTest {
            val ft = FakeTransport()
            val client = IrcClient(config(bouncerNetId = "42"), ft.factory(), clientScope())
            client.start()
            runCurrent()

            ft.feed(":srv CAP * LS :$fullLs")
            runCurrent()
            val initialCaps = ft.sent.first { it.startsWith("CAP REQ :") }.substringAfter("CAP REQ :")
            ft.feed(":srv CAP motd ACK :$initialCaps")
            runCurrent()
            // The pre-welcome CAP mutation marks the child Ready and queues the deferred feature REQs.
            ft.feed(":srv CAP motd DEL :extended-monitor")
            runCurrent()
            assertTrue(client.state.value is IrcClientState.Ready)
            assertTrue(ft.sent.none { it == "CAP REQ :draft/read-marker" })

            // The welcome burst did not stall: the first post-registration line is proof the stream is
            // flowing, so the deferred REQs go out now. No virtual time is advanced in this test, so a
            // REQ appearing at all means it did NOT wait the FALLBACK_FEATURE_CAP_DELAY_MS ceiling.
            ft.feed(":srv 005 motd CHANTYPES=# :are supported")
            runCurrent()

            assertTrue(ft.sent.any { it == "CAP REQ :draft/read-marker" })
        }

    @Test
    fun `bouncer fallback applies late welcome nick before synthetic self join`() =
        runTest {
            val ft = FakeTransport()
            val client =
                IrcClient(
                    config(SaslMechanism.PLAIN, "quaff/matrix2051", "secret").copy(
                        nick = "quaff",
                        username = "quaff",
                    ),
                    ft.factory(),
                    clientScope(),
                )
            val events = mutableListOf<IrcEvent>()
            val job = launch { client.broadcastEvents.toList(events) }
            runCurrent()
            client.start()
            runCurrent()
            ft.feed(":srv CAP * LS :$fullLs")
            runCurrent()
            val initialCaps = ft.sent.first { it.startsWith("CAP REQ :") }.substringAfter("CAP REQ :")
            ft.feed(":srv CAP quaff ACK :$initialCaps")
            runCurrent()
            ft.feed("AUTHENTICATE +")
            runCurrent()
            ft.feed(":srv 903 quaff :SASL authentication successful")
            runCurrent()
            assertTrue(ft.sent.contains("CAP END"))
            assertTrue(ft.sent.none { it.startsWith("BOUNCER BIND") })

            // soju mutates child capabilities before sending the upstream's authoritative nick.
            ft.feed(":srv CAP quaff DEL :extended-monitor")
            runCurrent()
            val fallback = client.state.value as IrcClientState.Ready
            assertEquals("quaff", fallback.nick)
            assertEquals("quaff", events.filterIsInstance<IrcEvent.Registered>().single().nick)
            events.clear()

            val actual = "matrixnick:matrix.example"
            val welcome = ":srv 001 $actual :Welcome to soju, $actual"
            ft.feed(welcome)
            ft.feed(":$actual!user@host JOIN #room")
            ft.feed(":quaff!peer@host JOIN #peer")
            runCurrent()

            val corrected = IrcEvent.Registered(actual, fallback.caps, fallback.isupport)
            assertEquals(fallback.copy(nick = actual), client.state.value)
            assertEquals(corrected, events[0])
            assertEquals(IrcEvent.Raw(IrcMessage.parse(welcome)), events[1])
            val joins = events.filterIsInstance<IrcEvent.Joined>()
            assertEquals(2, joins.size)
            assertEquals(joins, events.drop(2))
            assertEquals(actual, joins[0].nick)
            assertTrue(joins[0].isSelf)
            assertFalse(joins[1].isSelf)
            assertEquals(setOf("#room"), client.joinedChannels.value)

            // A repeated welcome stays Raw, while late 005 retains the corrected identity.
            ft.feed(welcome)
            ft.feed(":srv 005 $actual CHANTYPES=# CASEMAPPING=ascii :are supported")
            runCurrent()
            val ready = client.state.value as IrcClientState.Ready
            assertEquals(actual, ready.nick)
            assertEquals("#", ready.isupport["CHANTYPES"])
            assertEquals("ascii", ready.isupport["CASEMAPPING"])
            assertEquals(
                listOf(corrected, IrcEvent.Registered(actual, fallback.caps, ready.isupport)),
                events.filterIsInstance<IrcEvent.Registered>(),
            )
            assertEquals(2, events.filterIsInstance<IrcEvent.Raw>().count { it.message.command == "001" })
            assertEquals(setOf("#room"), client.joinedChannels.value)
            job.cancelAndJoin()
        }

    @Test
    fun `bouncer fallback has message-tags before ready so typing works immediately`() =
        runTest {
            val ft = FakeTransport()
            val client = IrcClient(config(bouncerNetId = "42"), ft.factory(), clientScope())
            client.start()
            runCurrent()

            ft.feed(":srv CAP * LS :$fullLs")
            runCurrent()

            val initialReq = ft.sent.first { it.startsWith("CAP REQ") }
            assertTrue(initialReq.contains("sasl"))
            assertTrue(initialReq.contains("soju.im/bouncer-networks"))
            assertTrue(initialReq.contains("draft/chathistory"))
            assertTrue(initialReq.contains("batch"))
            assertTrue(initialReq.contains("message-tags"))
            assertTrue(initialReq.contains("server-time"))

            ft.feed(":srv CAP motd ACK :${initialReq.substringAfter("CAP REQ :")}")
            runCurrent()
            ft.feed(":srv CAP motd DEL :extended-monitor")
            runCurrent()

            assertTrue(client.state.value is IrcClientState.Ready)
            assertTrue(client.hasCap("message-tags"))
            assertTrue(client.hasCap("draft/chathistory"))

            client.sendTyping("#chan", "active")
            runCurrent()
            assertTrue(ft.sent.any { it.startsWith("@+typing=active TAGMSG #chan") })

            advanceTimeBy(RegistrationStateMachine.FALLBACK_FEATURE_CAP_DELAY_MS)
            runCurrent()

            val deferredRequests =
                ft.sent
                    .filter { it.startsWith("CAP REQ :") }
                    .drop(1)
            assertTrue(deferredRequests.none { it == "CAP REQ :message-tags" })
            assertTrue(deferredRequests.none { it == "CAP REQ :draft/chathistory" })
            assertTrue(deferredRequests.contains("CAP REQ :draft/read-marker"))
            assertTrue("draft/read-marker" in client.pendingFeatureCaps.value)

            for (request in deferredRequests) {
                ft.feed(":srv CAP motd ACK :${request.substringAfter("CAP REQ :")}")
                runCurrent()
            }

            val ready = client.state.value as IrcClientState.Ready
            assertTrue(ready.caps.contains("message-tags"))
            assertTrue(client.pendingFeatureCaps.value.isEmpty())
        }

    @Test
    fun `bouncer fallback isolates rejected cap so other deferred features still activate`() =
        runTest {
            val ft = FakeTransport()
            val staleCap = "vendor/stale-after-bind"
            val client =
                IrcClient(
                    config(bouncerNetId = "42", extraCaps = setOf(staleCap)),
                    ft.factory(),
                    clientScope(),
                )
            client.start()
            runCurrent()

            ft.feed(":srv CAP * LS :$fullLs $staleCap")
            runCurrent()
            val initialCaps = ft.sent.first { it.startsWith("CAP REQ :") }.substringAfter("CAP REQ :")
            ft.feed(":srv CAP motd ACK :$initialCaps")
            runCurrent()

            // Soju changes its available capabilities after BOUNCER BIND. The first NEW also
            // completes the fallback registration path while the deferred feature requests wait.
            ft.feed(":srv CAP motd NEW :vendor/post-bind")
            runCurrent()
            assertTrue(client.state.value is IrcClientState.Ready)

            advanceTimeBy(RegistrationStateMachine.FALLBACK_FEATURE_CAP_DELAY_MS)
            runCurrent()

            val deferredRequests =
                ft.sent
                    .filter { it.startsWith("CAP REQ :") }
                    .drop(1)
            assertTrue(deferredRequests.any { it.contains("draft/read-marker") })
            assertTrue(staleCap in client.pendingFeatureCaps.value)
            for (request in deferredRequests) {
                val caps = request.substringAfter("CAP REQ :")
                val reply = if (caps.split(' ').contains(staleCap)) "NAK" else "ACK"
                ft.feed(":srv CAP motd $reply :$caps")
                runCurrent()
            }

            assertTrue(client.hasCap("draft/chathistory"))
            assertTrue(client.hasCap("draft/read-marker"))
            assertTrue(client.pendingFeatureCaps.value.isEmpty())
        }

    @Test
    fun `bouncer fallback emits tagged soju backlog as one history batch`() =
        runTest {
            val ft = FakeTransport()
            val client = IrcClient(config(bouncerNetId = "42"), ft.factory(), clientScope())
            val collected = mutableListOf<IrcEvent>()
            val job = launch { client.broadcastEvents.toList(collected) }
            client.start()
            runCurrent()

            ft.feed(":srv CAP * LS :$fullLs")
            runCurrent()
            val initialCaps = ft.sent.first { it.startsWith("CAP REQ :") }.substringAfter("CAP REQ :")
            ft.feed(":srv CAP motd ACK :$initialCaps")
            runCurrent()
            ft.feed(":srv CAP motd NEW :vendor/post-bind")
            runCurrent()

            assertTrue(client.state.value is IrcClientState.Ready)
            assertTrue(client.hasCap("draft/chathistory"))
            assertTrue(client.hasCap("batch"))
            assertTrue(client.hasCap("message-tags"))
            assertTrue(client.hasCap("server-time"))

            ft.feed("BATCH +history chathistory #chan")
            ft.feed(
                "@batch=history;msgid=history-1;time=2026-07-28T20:17:04.564Z " +
                    ":alice!u@h PRIVMSG #chan :retained message",
            )
            ft.feed("BATCH -history")
            runCurrent()
            job.cancel()

            val history = collected.filterIsInstance<IrcEvent.PlaybackBatch>().single()
            assertEquals(IrcEvent.PlaybackSource.CHATHISTORY, history.source)
            assertEquals("#chan", history.target)
            val message = history.events.single() as IrcEvent.ChatMessage
            assertEquals("retained message", message.text)
            assertEquals("history-1", message.ctx.msgid)
            assertEquals(1_785_269_824_564L, message.ctx.serverTime)
            assertEquals(ServerTimeSource.TAG, message.ctx.serverTimeSource)
            assertTrue(collected.none { it is IrcEvent.ChatMessage })
        }

    @Test
    fun `433 nick in use retries with underscore`() =
        runTest {
            val ft = FakeTransport()
            val client = IrcClient(config(), ft.factory(), clientScope())
            client.start()
            runCurrent()
            ft.feed(":srv 433 * motd :Nickname is already in use")
            runCurrent()
            assertEquals("NICK motd_", ft.sent.last())
            ft.feed(":srv 433 * motd_ :Nickname is already in use")
            runCurrent()
            assertEquals("NICK motd__", ft.sent.last())
        }

    @Test
    fun `watchdog timeout disconnects`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            val critical =
                clientScope().async {
                    buildList { for (event in client.criticalEvents) add(event) }
                }
            assertTrue(client.state.value is IrcClientState.Ready)

            // The idle window running when the welcome lines arrived is reset by them; the next
            // full 90s of silence triggers the PING, then +30s with no inbound -> disconnect.
            advanceTimeBy(90_001) // reset window (observed the registration inbound) restarts here
            runCurrent()
            advanceTimeBy(90_001) // a full silent window -> PING
            runCurrent()
            assertTrue("watchdog PING sent", ft.sent.any { it.startsWith("PING motd-") })
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(IrcClientState.Disconnected, client.state.value)
            client.awaitTermination()
            val disconnects = critical.await().filterIsInstance<IrcEvent.Disconnected>()
            assertEquals(1, disconnects.size)
            assertEquals("watchdog timeout", disconnects.single().reason)
        }

    @Test
    fun `immediate liveness probe response preserves Ready connection`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            val probe = clientScope().async { client.probeLiveness(5_000) }
            runCurrent()

            assertTrue(ft.sent.last().startsWith("PING motd-"))
            ft.feed(":srv PONG motd-foreground")
            runCurrent()
            advanceTimeBy(5_001)
            runCurrent()

            assertTrue(probe.await())
            assertTrue(client.state.value is IrcClientState.Ready)
            assertFalse(ft.closed)
        }

    @Test
    fun `immediate liveness probe timeout disconnects for actor recovery`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            val probe = clientScope().async { client.probeLiveness(5_000) }
            runCurrent()

            advanceTimeBy(5_001)
            runCurrent()

            assertFalse(probe.await())
            assertEquals(IrcClientState.Disconnected, client.state.value)
            assertTrue(ft.closed)
        }

    @Test
    fun `lag probe measures round trip and consumes its pong`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            val rawPongs = mutableListOf<IrcEvent.Raw>()
            val rawJob =
                launch {
                    client.broadcastEvents.collect {
                        if (it is IrcEvent.Raw && it.message.command == "PONG") rawPongs += it
                    }
                }
            runCurrent()

            assertNull(client.lag.value)
            // The LagMonitor cadence is 30s; advancing past it fires the first probe.
            advanceTimeBy(LagMonitor.DEFAULT_INTERVAL_MS + 1)
            runCurrent()
            val ping = ft.sent.last { it.startsWith("PING motd-lag-") }

            // The matching PONG is consumed by the monitor and surfaces as a latency reading.
            ft.feed(":srv PONG ${ping.substringAfter("PING ")}")
            runCurrent()
            val lag = client.lag.value
            assertTrue("lag reading published", lag != null)
            assertTrue("lag is non-negative", lag!! >= 0)

            // The consumed PONG must not leak through as a Raw event.
            rawJob.cancel()
            assertTrue("matched PONG was not dispatched", rawPongs.isEmpty())
        }

    @Test
    fun `unmatched pong still dispatches as raw`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            val raws =
                clientScope().async {
                    client.broadcastEvents.first { it is IrcEvent.Raw && it.message.command == "PONG" } as IrcEvent.Raw
                }
            runCurrent()

            // A watchdog keepalive PONG (motd-<epoch>) is not a lag probe and must still dispatch.
            ft.feed(":srv PONG motd-1700000000000")
            runCurrent()
            assertEquals(
                "motd-1700000000000",
                raws
                    .await()
                    .message.params
                    .first(),
            )
        }

    // -- LIST / listChannels --

    @Test
    fun `raw LIST collector subscribes before the request can be sent`() =
        runTest {
            val events = MutableSharedFlow<IrcEvent>()

            val collector = backgroundScope.launchUnlabeledChannelListCollector(events) {}

            assertEquals(1, events.subscriptionCount.value)
            collector.cancel()
        }

    @Test
    fun `labeled LIST parses 321-322-323 batch into listings`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)

            val result = clientScope().async { client.listChannels() }
            runCurrent()

            val labeled = ft.sent.last { it.contains("LIST") }
            val label = responseLabel(labeled)

            ft.feed("@label=$label BATCH +list draft/labeled-response")
            ft.feed("@batch=list :srv 321 motd Channel :Users Name")
            ft.feed("@batch=list :srv 322 motd #chan 42 :the topic")
            ft.feed("@batch=list :srv 322 motd #other 7 :another topic")
            ft.feed("@batch=list :srv 323 motd :End of /LIST")
            ft.feed("BATCH -list")
            runCurrent()

            val listings = result.await()
            assertEquals(
                listOf(
                    ChannelListing("#chan", 42, "the topic"),
                    ChannelListing("#other", 7, "another topic"),
                ),
                listings,
            )
        }

    @Test
    fun `labeled LIST cap retains the most populated rows`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)

            val result = clientScope().async { client.listChannels(cap = 2) }
            runCurrent()

            val label = responseLabel(ft.sent.last { it.contains("LIST") })
            ft.feed("@label=$label BATCH +list draft/labeled-response")
            ft.feed("@batch=list :srv 322 motd #small 3 :small")
            ft.feed("@batch=list :srv 322 motd #largest 300 :largest")
            ft.feed("@batch=list :srv 322 motd #large 200 :large")
            ft.feed("@batch=list :srv 323 motd :End of /LIST")
            ft.feed("BATCH -list")
            runCurrent()

            assertEquals(listOf("#largest", "#large"), result.await().map { it.name })
        }

    @Test
    fun `labeled LIST streams a hundred thousand rows into bounded top set`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            val result = clientScope().async { client.listChannels(cap = 2) }
            runCurrent()

            val label = responseLabel(ft.sent.last { it.contains("LIST") })
            ft.feed("@label=$label BATCH +list draft/labeled-response")
            repeat(100_000) { index ->
                ft.feed("@batch=list :srv 322 motd #c$index $index :topic")
                if (index % 1_000 == 0) runCurrent()
            }
            ft.feed("@batch=list :srv 323 motd :End of /LIST")
            ft.feed("BATCH -list")
            runCurrent()

            assertEquals(listOf("#c99999", "#c99998"), result.await().map { it.name })
        }

    @Test
    fun `raw fallback collects 322s until 323 without labeled-response`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredNoCaps(ft)

            val result = clientScope().async { client.listChannels() }
            runCurrent()

            // Unlabeled LIST goes out (no label tag).
            val listLine = ft.sent.last { it.startsWith("LIST") }
            assertTrue(!listLine.contains("label="))

            ft.feed(":srv 322 motd #a 10 :topic a")
            ft.feed(":srv 322 motd #b 5 :topic b")
            ft.feed(":srv 323 motd :End of /LIST")
            runCurrent()

            val listings = result.await()
            assertEquals(
                listOf(ChannelListing("#a", 10, "topic a"), ChannelListing("#b", 5, "topic b")),
                listings,
            )
        }

    @Test
    fun `raw LIST requests are serialized because their replies have no request identity`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredNoCaps(ft)

            val first = clientScope().async { client.listChannels(mask = "*first*") }
            val second = clientScope().async { client.listChannels(mask = "*second*") }
            runCurrent()

            assertEquals(1, ft.sent.count { it.startsWith("LIST") })
            ft.feed(":srv 322 motd #first 10 :first topic")
            ft.feed(":srv 323 motd :End of /LIST")
            runCurrent()

            assertEquals(2, ft.sent.count { it.startsWith("LIST") })
            ft.feed(":srv 322 motd #second 20 :second topic")
            ft.feed(":srv 323 motd :End of /LIST")
            runCurrent()

            assertEquals(listOf("#first"), first.await().map { it.name })
            assertEquals(listOf("#second"), second.await().map { it.name })
        }

    @Test
    fun `timed out raw LIST drains its response before sending the queued search`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredNoCaps(ft)

            val first = clientScope().async { runCatching { client.listChannels() } }
            runCurrent()
            assertEquals(1, ft.sent.count { it.startsWith("LIST") })

            advanceTimeBy(15_000)
            runCurrent()
            assertTrue(first.await().exceptionOrNull() is IrcTimeoutException)

            val search = clientScope().async { client.listChannels(mask = "*motd*") }
            runCurrent()
            assertEquals(1, ft.sent.count { it.startsWith("LIST") })

            // Late completion of the first response must be drained, not mistaken for the search.
            ft.feed(":srv 322 motd #old 500 :old response")
            ft.feed(":srv 323 motd :End of /LIST")
            runCurrent()
            assertEquals(2, ft.sent.count { it.startsWith("LIST") })

            ft.feed(":srv 322 motd #motd 42 :search response")
            ft.feed(":srv 323 motd :End of /LIST")
            runCurrent()
            assertEquals(listOf("#motd"), search.await().map { it.name })
        }

    @Test
    fun `ELIST U gates the minUsers filter param`() =
        runTest {
            // ELIST advertises 'U' -> the ">n" filter is appended.
            val ftU = FakeTransport()
            val clientU = registeredWithIsupport(ftU, "CHATHISTORY=100 ELIST=CMNTU")
            clientScope().async { clientU.listChannels(minUsers = 50) }
            runCurrent()
            assertTrue(ftU.sent.last { it.contains("LIST") }.contains(">50"))

            // ELIST without 'U' -> the filter is dropped.
            val ftNo = FakeTransport()
            val clientNo = registeredWithIsupport(ftNo, "CHATHISTORY=100 ELIST=CMNT")
            clientScope().async { clientNo.listChannels(minUsers = 50) }
            runCurrent()
            assertTrue(!ftNo.sent.last { it.contains("LIST") }.contains(">50"))
        }

    @Test
    fun `listChannels truncates to cap`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredNoCaps(ft)

            val result = clientScope().async { client.listChannels(cap = 2) }
            runCurrent()

            ft.feed(":srv 322 motd #a 3 :a")
            ft.feed(":srv 322 motd #b 3 :b")
            ft.feed(":srv 322 motd #c 3 :c")
            ft.feed(":srv 323 motd :End of /LIST")
            runCurrent()

            assertEquals(listOf("#a", "#b"), result.await().map { it.name })
        }

    @Test
    fun `listChannels cap retains the most populated raw rows`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredNoCaps(ft)

            val result = clientScope().async { client.listChannels(cap = 2) }
            runCurrent()

            ft.feed(":srv 322 motd #small 3 :small")
            ft.feed(":srv 322 motd #largest 300 :largest")
            ft.feed(":srv 322 motd #large 200 :large")
            ft.feed(":srv 323 motd :End of /LIST")
            runCurrent()

            assertEquals(listOf("#largest", "#large"), result.await().map { it.name })
        }

    @Test
    fun `WEBPUSH register waits for exact raw acknowledgement`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            val endpoint = "https://push.example/subscription"

            val result = clientScope().async { client.webpushRegister(endpoint, ByteArray(65), ByteArray(16)) }
            runCurrent()
            assertTrue(ft.sent.last().startsWith("WEBPUSH REGISTER $endpoint "))
            assertFalse(result.isCompleted)

            ft.feed(":srv WEBPUSH REGISTER https://push.example/other")
            runCurrent()
            assertFalse("an unrelated endpoint must not arm push", result.isCompleted)

            ft.feed(":srv WEBPUSH REGISTER $endpoint")
            runCurrent()
            result.await()
        }

    @Test
    fun `WEBPUSH register surfaces matching FAIL`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            val result =
                clientScope().async {
                    runCatching {
                        client.webpushRegister("https://push.example/subscription", ByteArray(65), ByteArray(16))
                    }
                }
            runCurrent()

            ft.feed(":srv FAIL WEBPUSH INVALID_PARAMS REGISTER :endpoint rejected")
            runCurrent()
            val error = result.await().exceptionOrNull()
            if (error !is IrcCommandException) fail("expected WEBPUSH failure, got $error")
            error as IrcCommandException
            assertEquals("INVALID_PARAMS", error.code)
            assertEquals("endpoint rejected", error.text)
        }

    @Test
    fun `WEBPUSH register fails when connection closes`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            val result =
                clientScope().async {
                    runCatching {
                        client.webpushRegister("https://push.example/subscription", ByteArray(65), ByteArray(16))
                    }
                }
            runCurrent()

            ft.eof()
            runCurrent()
            val error = result.await().exceptionOrNull()
            if (error !is IrcDisconnectedException) fail("expected disconnect, got $error")
            assertEquals("WEBPUSH REGISTER", (error as IrcDisconnectedException).ircCommand)
        }

    @Test
    fun `WEBPUSH register times out`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            val result =
                clientScope().async {
                    runCatching {
                        client.webpushRegister("https://push.example/subscription", ByteArray(65), ByteArray(16))
                    }
                }
            runCurrent()
            advanceTimeBy(30_001L)
            runCurrent()

            val error = result.await().exceptionOrNull()
            if (error !is IrcTimeoutException) fail("expected WEBPUSH timeout, got $error")
            assertEquals("WEBPUSH REGISTER", (error as IrcTimeoutException).label)
        }

    // -- helpers --

    /** Registers a client through the happy path (no SASL) and returns it in Ready state. */
    private suspend fun kotlinx.coroutines.test.TestScope.registered(
        ft: FakeTransport,
        caps: String = fullLs,
        observerBufferCapacity: Int = 4096,
        clientVersion: String = "motd",
        factory: TransportFactory = ft.factory(),
    ): IrcClient {
        val client =
            IrcClient(
                config(),
                factory,
                clientScope(),
                observerBufferCapacity,
                clientVersion = clientVersion,
            )
        client.start()
        runCurrent()
        ft.feed(":srv CAP * LS :$caps")
        runCurrent()
        ft.feed(":srv CAP motd ACK :$caps")
        runCurrent()
        ft.feed(":srv 001 motd :Welcome")
        ft.feed(":srv 005 motd CHATHISTORY=100 :are supported")
        runCurrent()
        return client
    }

    /**
     * Registers a client with the given 005 [isupport] tokens. Registration completes at 001, so
     * the 005 is fed BEFORE 001 to ensure the tokens (e.g. ELIST) are captured into the client's
     * ISUPPORT snapshot.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.registeredWithIsupport(
        ft: FakeTransport,
        isupport: String,
    ): IrcClient {
        val client = IrcClient(config(), ft.factory(), clientScope())
        client.start()
        runCurrent()
        ft.feed(":srv CAP * LS :$fullLs")
        runCurrent()
        ft.feed(":srv CAP motd ACK :$fullLs")
        runCurrent()
        ft.feed(":srv 005 motd $isupport :are supported")
        ft.feed(":srv 001 motd :Welcome")
        runCurrent()
        return client
    }

    /** Registers a client whose server offers no labeled-response cap (raw LIST fallback path). */
    private suspend fun kotlinx.coroutines.test.TestScope.registeredNoCaps(ft: FakeTransport): IrcClient {
        val caps = "message-tags server-time batch"
        val client = IrcClient(config(), ft.factory(), clientScope())
        client.start()
        runCurrent()
        ft.feed(":srv CAP * LS :$caps")
        runCurrent()
        ft.feed(":srv CAP motd ACK :$caps")
        runCurrent()
        ft.feed(":srv 001 motd :Welcome")
        ft.feed(":srv 005 motd CHATHISTORY=100 :are supported")
        runCurrent()
        return client
    }

    /** Registers a client whose server advertises exactly [caps] (ACK mirrors the same set). */
    private suspend fun kotlinx.coroutines.test.TestScope.registeredWithCaps(
        ft: FakeTransport,
        caps: String,
    ): IrcClient {
        val client = IrcClient(config(), ft.factory(), clientScope())
        client.start()
        runCurrent()
        ft.feed(":srv CAP * LS :$caps")
        runCurrent()
        ft.feed(":srv CAP motd ACK :$caps")
        runCurrent()
        ft.feed(":srv 001 motd :Welcome")
        ft.feed(":srv 005 motd CHATHISTORY=100 :are supported")
        runCurrent()
        return client
    }

    @Test
    fun `markRead sends MARKREAD when draft read marker is negotiated`() =
        runTest {
            val ft = FakeTransport()
            val client = registered(ft)
            assertTrue(client.hasReadMarkerCap())

            client.markRead("#motd", 1_000)
            runCurrent()
            assertEquals("MARKREAD #motd timestamp=1970-01-01T00:00:01.000Z", ft.sent.last())

            client.fetchReadMarker("#motd")
            runCurrent()
            assertEquals("MARKREAD #motd", ft.sent.last())
        }

    @Test
    fun `markRead sends READ when only soju im read is negotiated`() =
        runTest {
            val ft = FakeTransport()
            val caps = "message-tags server-time batch soju.im/read"
            val client = registeredWithCaps(ft, caps)
            assertTrue(client.hasReadMarkerCap())
            assertFalse(client.hasCap("draft/read-marker"))

            client.markRead("#motd", 1_000)
            runCurrent()
            assertEquals("READ #motd timestamp=1970-01-01T00:00:01.000Z", ft.sent.last())

            client.fetchReadMarker("#motd")
            runCurrent()
            assertEquals("READ #motd", ft.sent.last())
        }

    @Test
    fun `markRead is a no-op when neither read marker cap is negotiated`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredNoCaps(ft)
            assertFalse(client.hasReadMarkerCap())

            val before = ft.sent.size
            client.markRead("#motd", 1_000)
            client.fetchReadMarker("#motd")
            runCurrent()
            assertEquals(before, ft.sent.size)
        }

    @Test
    fun `inbound READ maps to ReadMarker only when soju im read is negotiated`() =
        runTest {
            val ft = FakeTransport()
            val caps = "message-tags server-time batch soju.im/read"
            val client = registeredWithCaps(ft, caps)

            val collected = mutableListOf<IrcEvent>()
            val job = launch { client.broadcastEvents.toList(collected) }
            runCurrent()
            ft.feed(":srv READ #motd timestamp=2026-07-25T12:00:00.000Z")
            runCurrent()
            job.cancel()

            val marker = collected.filterIsInstance<IrcEvent.ReadMarker>().first()
            assertEquals("#motd", marker.target)
        }

    /** SEARCH with labeled-response available (unreleased soju master); correlation is by label. */
    private val searchCaps = "message-tags server-time batch labeled-response soju.im/search"

    /**
     * The CAP LS a real released soju (0.10.x) advertises: `soju.im/search` is present but
     * `labeled-response` is NOT — that cap exists only in unreleased soju master. Fixtures built
     * from this set exercise the wire the feature actually ships against.
     */
    private val sojuReleaseLs =
        "draft/pre-away draft/no-implicit-names cap-notify soju.im/search message-tags batch " +
            "draft/chathistory extended-join draft/message-redaction invite-notify " +
            "soju.im/no-implicit-names sasl=PLAIN multi-prefix soju.im/account-required " +
            "account-notify chghost server-time draft/metadata-2=before-connect account-tag " +
            "away-notify soju.im/webpush draft/read-marker soju.im/bouncer-networks " +
            "soju.im/read soju.im/bouncer-networks-notify extended-monitor setname " +
            "echo-message draft/extended-monitor"

    @Test
    fun `soju search cap is requested when advertised`() =
        runTest {
            val ft = FakeTransport()
            val client = IrcClient(config(), ft.factory(), clientScope())
            client.start()
            runCurrent()

            ft.feed(":srv CAP * LS :$fullLs soju.im/search")
            runCurrent()

            assertTrue("soju.im/search must be negotiable", "soju.im/search" in CapTiers.ALL)
            assertTrue(
                ft.sent.toString(),
                ft.sent.any { it.startsWith("CAP REQ") && it.contains("soju.im/search") },
            )
        }

    @Test
    fun `search round trip assembles the soju search batch`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredWithCaps(ft, searchCaps)
            assertTrue(client.searchAvailable)

            val result =
                clientScope().async {
                    client.search(SearchRequest(target = "#motd", text = "coroutine", from = "alice"))
                }
            runCurrent()

            val sent = ft.sent.last { it.contains("SEARCH") }
            assertTrue(sent, sent.contains("in=#motd;text=coroutine;from=alice;limit=100"))
            val label = responseLabel(sent)
            ft.feed("@label=$label BATCH +s soju.im/search")
            ft.feed(
                "@batch=s;time=2026-07-14T19:00:00.000Z;msgid=older " +
                    ":alice!u@h PRIVMSG #motd :older coroutine hit",
            )
            ft.feed(
                "@batch=s;time=2026-07-14T19:05:00.000Z;msgid=newer " +
                    ":alice!u@h PRIVMSG #motd :ACTION mentions coroutines",
            )
            ft.feed("@batch=s :srv NOTICE #motd :not a chat hit but still a result")
            ft.feed("BATCH -s")
            runCurrent()

            val hits = result.await()
            // Server (ascending) order is preserved verbatim; nothing is sorted or deduplicated here.
            assertEquals(listOf("older", "newer", null), hits.map { it.msgid })
            assertEquals(
                listOf(SearchResultKind.PRIVMSG, SearchResultKind.ACTION, SearchResultKind.NOTICE),
                hits.map { it.kind },
            )
            assertEquals("mentions coroutines", hits[1].text)
            assertEquals(1_784_055_600_000L, hits[0].serverTime)
            assertNull(hits[2].serverTime)
        }

    @Test
    fun `search FAIL completes with the server code`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredWithCaps(ft, searchCaps)

            val result =
                clientScope().async {
                    runCatching { client.search(SearchRequest(target = "#motd", text = "x")) }
                }
            runCurrent()
            val label = responseLabel(ft.sent.last { it.contains("SEARCH") })
            ft.feed("@label=$label FAIL SEARCH INVALID_PARAMS :missing in attribute")
            runCurrent()

            val error = result.await().exceptionOrNull()
            assertTrue(error.toString(), error is IrcCommandException)
            assertEquals("SEARCH", (error as IrcCommandException).ircCommand)
            assertEquals("INVALID_PARAMS", error.code)
        }

    @Test
    fun `search rejects a wrong batch type`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredWithCaps(ft, searchCaps)

            val result =
                clientScope().async {
                    runCatching { client.search(SearchRequest(target = "#motd", text = "x")) }
                }
            runCurrent()
            val label = responseLabel(ft.sent.last { it.contains("SEARCH") })
            ft.feed("@label=$label BATCH +s chathistory #motd")
            ft.feed("BATCH -s")
            runCurrent()

            assertTrue(result.await().exceptionOrNull() is IrcProtocolException)
        }

    @Test
    fun `search without the cap throws before writing`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredWithCaps(ft, "message-tags server-time batch labeled-response")
            assertFalse(client.searchAvailable)
            val before = ft.sent.size

            val error =
                runCatching { client.search(SearchRequest(target = "#motd", text = "x")) }
                    .exceptionOrNull()

            assertTrue(error.toString(), error is IllegalStateException)
            assertEquals("an unavailable SEARCH must not touch the wire", before, ft.sent.size)
        }

    @Test
    fun `search empty batch yields no results`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredWithCaps(ft, searchCaps)

            val batched = clientScope().async { client.search(SearchRequest(target = "#motd", text = "x")) }
            runCurrent()
            val batchedLabel = responseLabel(ft.sent.last { it.contains("SEARCH") })
            ft.feed("@label=$batchedLabel BATCH +s soju.im/search")
            ft.feed("BATCH -s")
            runCurrent()
            assertEquals(emptyList<SearchResultMessage>(), batched.await())

            // Tolerated the other way too: a zero-result SEARCH that never opens a batch.
            val unbatched = clientScope().async { client.search(SearchRequest(target = "#motd", text = "y")) }
            runCurrent()
            val unbatchedLabel = responseLabel(ft.sent.last { it.contains("SEARCH") })
            ft.feed("@label=$unbatchedLabel ACK")
            runCurrent()
            assertEquals(emptyList<SearchResultMessage>(), unbatched.await())
        }

    @Test
    fun `search is available on released soju caps without labeled-response`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredWithCaps(ft, sojuReleaseLs)

            assertFalse(client.hasCap("labeled-response"))
            assertTrue(client.hasCap("soju.im/search"))
            assertTrue(
                "SEARCH must be available on released soju, which never offers labeled-response",
                client.searchAvailable,
            )
        }

    @Test
    fun `unlabeled search round trip assembles the soju search batch`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredWithCaps(ft, sojuReleaseLs)
            assertTrue(client.searchAvailable)

            val result =
                clientScope().async {
                    client.search(SearchRequest(target = "#motd", text = "coroutine", from = "alice"))
                }
            runCurrent()

            val sent = ft.sent.last { it.contains("SEARCH") }
            assertTrue(sent, sent.contains("in=#motd;text=coroutine;from=alice;limit=100"))
            assertFalse("no label without labeled-response", sent.contains("label="))
            // soju 0.10.x: a bare soju.im/search batch, no label tag anywhere.
            ft.feed("BATCH +s soju.im/search")
            ft.feed(
                "@batch=s;time=2026-07-14T19:00:00.000Z;msgid=older " +
                    ":alice!u@h PRIVMSG #motd :older coroutine hit",
            )
            ft.feed(
                "@batch=s;time=2026-07-14T19:05:00.000Z;msgid=newer " +
                    ":alice!u@h PRIVMSG #motd :ACTION mentions coroutines",
            )
            ft.feed("BATCH -s")
            runCurrent()

            val hits = result.await()
            assertEquals(listOf("older", "newer"), hits.map { it.msgid })
            assertEquals(listOf(SearchResultKind.PRIVMSG, SearchResultKind.ACTION), hits.map { it.kind })
            assertEquals("mentions coroutines", hits[1].text)
        }

    @Test
    fun `unlabeled search results stay out of the live event stream`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredWithCaps(ft, sojuReleaseLs)

            val collected = mutableListOf<IrcEvent>()
            val job = launch { client.broadcastEvents.toList(collected) }
            runCurrent()
            val result = clientScope().async { client.search(SearchRequest(target = "#motd", text = "x")) }
            runCurrent()
            ft.feed("BATCH +s soju.im/search")
            ft.feed("@batch=s :alice!u@h PRIVMSG #motd :a buffered hit")
            ft.feed("BATCH -s")
            runCurrent()
            job.cancel()

            assertEquals(1, result.await().size)
            assertTrue(
                "search hits must not surface as live chat events",
                collected.filterIsInstance<IrcEvent.ChatMessage>().isEmpty(),
            )
        }

    @Test
    fun `unlabeled search FAIL completes with the server code`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredWithCaps(ft, sojuReleaseLs)

            val result =
                clientScope().async {
                    runCatching { client.search(SearchRequest(target = "#motd", text = "x")) }
                }
            runCurrent()
            ft.feed(":srv FAIL SEARCH INTERNAL_ERROR :search failed")
            runCurrent()

            val error = result.await().exceptionOrNull()
            assertTrue(error.toString(), error is IrcCommandException)
            assertEquals("SEARCH", (error as IrcCommandException).ircCommand)
            assertEquals("INTERNAL_ERROR", error.code)
        }

    @Test
    fun `search accepts the soju master labeled-response wrapper batch`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredWithCaps(ft, searchCaps)

            val result = clientScope().async { client.search(SearchRequest(target = "#motd", text = "x")) }
            runCurrent()
            val label = responseLabel(ft.sent.last { it.contains("SEARCH") })
            // soju master (post-70c4ded): the search batch arrives nested in a labeled-response batch.
            ft.feed("@label=$label BATCH +outer labeled-response")
            ft.feed("@batch=outer BATCH +inner soju.im/search")
            ft.feed(
                "@batch=inner;time=2026-07-14T19:00:00.000Z;msgid=hit " +
                    ":alice!u@h PRIVMSG #motd :a wrapped hit",
            )
            ft.feed("@batch=outer BATCH -inner")
            ft.feed("BATCH -outer")
            runCurrent()

            val hits = result.await()
            assertEquals(listOf("hit"), hits.map { it.msgid })
            assertEquals("a wrapped hit", hits[0].text)
        }

    @Test
    fun `search rejects a labeled-response wrapper holding the wrong batch type`() =
        runTest {
            val ft = FakeTransport()
            val client = registeredWithCaps(ft, searchCaps)

            val result =
                clientScope().async {
                    runCatching { client.search(SearchRequest(target = "#motd", text = "x")) }
                }
            runCurrent()
            val label = responseLabel(ft.sent.last { it.contains("SEARCH") })
            ft.feed("@label=$label BATCH +outer labeled-response")
            ft.feed("@batch=outer BATCH +inner chathistory #motd")
            ft.feed("@batch=outer BATCH -inner")
            ft.feed("BATCH -outer")
            runCurrent()

            assertTrue(result.await().exceptionOrNull() is IrcProtocolException)
        }
}
