package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
    private fun TestScope.clientScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

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
        realname = "MOTD User",
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

    private fun responseLabel(line: String): String =
        checkNotNull(Regex("label=(motd-\\d+)").find(line)) { "missing response label in $line" }
            .groupValues[1]

    @Test
    fun `disconnected chat send reports no transport acceptance`() = runTest {
        val client = IrcClient(config(), FakeTransport().factory(), clientScope())

        val accepted = client.sendMessage("#chan", "not sent", null, "motd-disconnected")

        assertFalse(accepted)
    }

    @Test
    fun `registration happy path with SASL PLAIN reaches Ready`() = runTest {
        val ft = FakeTransport()
        val client = IrcClient(config(SaslMechanism.PLAIN, "alice", "s3cret"), ft.factory(), clientScope())
        client.start()
        runCurrent()

        // Opening lines.
        assertEquals("CAP LS 302", ft.sent[0])
        assertEquals("NICK motd", ft.sent[1])
        assertEquals("USER motd 0 * :MOTD User", ft.sent[2])

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
    fun `SASL 904 is fatal Failed`() = runTest {
        val ft = FakeTransport()
        val client = IrcClient(config(SaslMechanism.PLAIN, "bob", "wrong"), ft.factory(), clientScope())
        client.start()
        val critical = clientScope().async {
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
    fun `SASL required but cap absent is fatal`() = runTest {
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
    fun `BOUNCER BIND ignores post-bind CAP changes while waiting for welcome`() = runTest {
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
    fun `labeled PRIVMSG echo correlates`() = runTest {
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
    fun `reply and reaction sends use the ratified reply tag`() = runTest {
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
    fun `chat labels reject values outside the wire-safe contract`() = runTest {
        val ft = FakeTransport()
        val client = registered(ft)

        val failure = runCatching {
            client.sendMessage("#chan", "hello", null, "bad label")
        }

        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
        assertFalse(ft.sent.last().contains("PRIVMSG #chan"))
    }

    @Test
    fun `live mapper prefers ratified reply and retains legacy fallback`() = runTest {
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
    fun `unreact remains raw for the app-owned mutation store`() = runTest {
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
    fun `post-welcome CLIENTTAGDENY updates Ready state`() = runTest {
        val ft = FakeTransport()
        val client = registered(ft)

        ft.feed(":srv 005 motd CLIENTTAGDENY=*,-reply :are supported")
        runCurrent()

        val ready = client.state.value as IrcClientState.Ready
        assertEquals("*,-reply", ready.isupport["CLIENTTAGDENY"])
    }

    @Test
    fun `history availability defaults references and honors zero as unlimited`() = runTest {
        val defaultTransport = FakeTransport()
        val defaultClient = registeredWithIsupport(defaultTransport, "CHATHISTORY=25")
        assertEquals(
            HistoryAvailability.Ready(
                setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID),
                25,
            ),
            defaultClient.historyAvailability,
        )

        val timestampTransport = FakeTransport()
        val timestampClient = registeredWithIsupport(
            timestampTransport,
            "CHATHISTORY=0 MSGREFTYPES=timestamp",
        )
        assertEquals(
            HistoryAvailability.Ready(setOf(HistoryReferenceType.TIMESTAMP), Int.MAX_VALUE),
            timestampClient.historyAvailability,
        )
        assertEquals("timestamp", (timestampClient.state.value as IrcClientState.Ready).isupport["MSGREFTYPES"])

        val unlimitedRequest = clientScope().async {
            timestampClient.chathistory(
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#chan", limit = 250),
            )
        }
        runCurrent()
        assertTrue(timestampTransport.sent.last { it.contains("CHATHISTORY") }.contains(" #chan * 250"))
        unlimitedRequest.cancelAndJoin()
    }

    @Test
    fun `labeled chathistory reassembles nested batch`() = runTest {
        val ft = FakeTransport()
        val client = registered(ft)

        val result = clientScope().async {
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
            res.events.filterIsInstance<IrcEvent.ChatMessage>().last().replyToMsgid,
        )
        assertEquals(
            "parent-1",
            res.events.filterIsInstance<IrcEvent.TagMessage>().single().reactTargetMsgid,
        )
        assertEquals(
            "👍",
            res.events.filterIsInstance<IrcEvent.Raw>().single().message.tags["+draft/unreact"],
        )
    }

    @Test
    fun `labeled root close with open nested batch fails and clears aliases`() = runTest {
        val ft = FakeTransport()
        val client = registered(ft)
        val request = clientScope().async {
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

        val retry = clientScope().async {
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
    fun `concurrent labeled requests remain independently correlated`() = runTest {
        val ft = FakeTransport()
        val client = registered(ft)

        val first = clientScope().async {
            client.sendLabeled(io.github.trevarj.motd.irc.proto.IrcMessage(command = "WHOIS", params = listOf("alice")))
        }
        val second = clientScope().async {
            client.sendLabeled(io.github.trevarj.motd.irc.proto.IrcMessage(command = "WHOIS", params = listOf("bob")))
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
    fun `cancelled labeled request unregisters before a late response`() = runTest {
        val ft = FakeTransport()
        val client = registered(ft)
        val request = clientScope().async {
            client.sendLabeled(io.github.trevarj.motd.irc.proto.IrcMessage(command = "WHOIS", params = listOf("alice")))
        }
        runCurrent()
        val label = responseLabel(ft.sent.last { it.contains("WHOIS alice") })
        request.cancelAndJoin()
        val late = clientScope().async {
            client.broadcastEvents.first { event ->
                event is IrcEvent.Raw && event.message.tags["label"] == label
            }
        }

        ft.feed("@label=$label :srv 318 motd alice :late")
        runCurrent()

        assertTrue(late.await() is IrcEvent.Raw)
    }

    @Test
    fun `timed out labeled request unregisters before a late response`() = runTest {
        val ft = FakeTransport()
        val client = registered(ft)
        val request = clientScope().async {
            runCatching {
                client.sendLabeled(io.github.trevarj.motd.irc.proto.IrcMessage(command = "WHOIS", params = listOf("alice")))
            }.exceptionOrNull()
        }
        runCurrent()
        val label = responseLabel(ft.sent.last { it.contains("WHOIS alice") })
        advanceTimeBy(30_001L)
        runCurrent()
        assertTrue(request.await() is IrcTimeoutException)
        val late = clientScope().async {
            client.broadcastEvents.first { event ->
                event is IrcEvent.Raw && event.message.tags["label"] == label
            }
        }

        ft.feed("@label=$label :srv 318 motd alice :late")
        runCurrent()

        assertTrue(late.await() is IrcEvent.Raw)
    }

    @Test
    fun `labeled write failure unregisters before a late response`() = runTest {
        val ft = FakeTransport()
        val client = registered(ft)
        ft.sendFailure = IOException("write failed")

        val error = runCatching {
            client.sendLabeled(io.github.trevarj.motd.irc.proto.IrcMessage(command = "WHOIS", params = listOf("alice")))
        }.exceptionOrNull()
        val label = responseLabel(ft.sent.last { it.contains("WHOIS alice") })
        assertTrue(error is IOException)
        ft.sendFailure = null
        val late = clientScope().async {
            client.broadcastEvents.first { event ->
                event is IrcEvent.Raw && event.message.tags["label"] == label
            }
        }

        ft.feed("@label=$label :srv 318 motd alice :late")
        runCurrent()

        assertTrue(late.await() is IrcEvent.Raw)
    }

    @Test
    fun `critical channel retains ordered burst through clean EOF`() = runTest {
        val ft = FakeTransport()
        val client = registered(ft)
        val collected = clientScope().async {
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
    fun `socket read failure publishes one terminal event`() = runTest {
        val ft = FakeTransport()
        val client = registered(ft)
        val critical = clientScope().async {
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
    fun `CAP NEW mid session requests and emits CapsChanged`() = runTest {
        val ft = FakeTransport()
        val client = registered(ft)

        val collected = mutableListOf<IrcEvent>()
        val job = launch { client.broadcastEvents.toList(collected) }
        runCurrent() // ensure the subscriber is registered before emits

        ft.feed(":srv CAP motd NEW :draft/chathistory")
        runCurrent()
        // We should REQ the newly advertised cap.
        assertTrue(ft.sent.any { it.startsWith("CAP REQ") && it.contains("draft/chathistory") })

        ft.feed(":srv CAP motd ACK :draft/chathistory")
        runCurrent()
        assertTrue(client.hasCap("draft/chathistory"))
        job.cancel()

        assertTrue(collected.any { it is IrcEvent.CapsChanged && it.added.contains("draft/chathistory") })
    }

    @Test
    fun `CAP NEW preserves advertised values after value-less ACK`() = runTest {
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
    fun `bouncer pre-welcome CAP NEW preserves limits through deferred ACK`() = runTest {
        val ft = FakeTransport()
        val client = IrcClient(config(bouncerNetId = "42"), ft.factory(), clientScope())
        val metadata = "draft/metadata-2=before-connect,max-keys=0,max-value-bytes=1"
        client.start()
        runCurrent()

        ft.feed(":srv CAP * LS :$fullLs $metadata")
        runCurrent()
        ft.feed(":srv CAP motd ACK :sasl soju.im/bouncer-networks")
        runCurrent()
        ft.feed(":srv CAP motd NEW :$metadata")
        runCurrent()

        assertTrue(client.state.value is IrcClientState.Ready)
        advanceTimeBy(RegistrationStateMachine.FALLBACK_FEATURE_CAP_DELAY_MS)
        runCurrent()
        val deferredCaps = ft.sent
            .first { it == "CAP REQ :draft/metadata-2" }
            .substringAfter("CAP REQ :")
        ft.feed(":srv CAP motd ACK :$deferredCaps")
        runCurrent()

        assertTrue(client.caps.contains(metadata))
        assertEquals(client.caps, (client.state.value as IrcClientState.Ready).caps)
    }

    @Test
    fun `bouncer fallback restores message-tags after ready so typing works`() = runTest {
        val ft = FakeTransport()
        val client = IrcClient(config(bouncerNetId = "42"), ft.factory(), clientScope())
        client.start()
        runCurrent()

        ft.feed(":srv CAP * LS :$fullLs")
        runCurrent()

        val initialReq = ft.sent.first { it.startsWith("CAP REQ") }
        assertTrue(initialReq.contains("sasl"))
        assertTrue(initialReq.contains("soju.im/bouncer-networks"))
        assertTrue(!initialReq.contains("message-tags"))

        ft.feed(":srv CAP motd ACK :sasl soju.im/bouncer-networks")
        runCurrent()
        ft.feed(":srv CAP motd DEL :extended-monitor")
        runCurrent()

        assertTrue(client.state.value is IrcClientState.Ready)
        assertTrue(!client.hasCap("message-tags"))

        client.sendTyping("#chan", "active")
        runCurrent()
        assertTrue(ft.sent.none { it.contains("TAGMSG #chan") })

        advanceTimeBy(RegistrationStateMachine.FALLBACK_FEATURE_CAP_DELAY_MS)
        runCurrent()

        val deferredRequests = ft.sent
            .filter { it.startsWith("CAP REQ :") }
            .drop(1)
        assertTrue(deferredRequests.contains("CAP REQ :message-tags"))
        assertTrue(deferredRequests.contains("CAP REQ :draft/chathistory"))
        assertTrue(deferredRequests.contains("CAP REQ :draft/read-marker"))

        for (request in deferredRequests) {
            ft.feed(":srv CAP motd ACK :${request.substringAfter("CAP REQ :")}")
            runCurrent()
        }

        assertTrue(client.hasCap("message-tags"))
        val ready = client.state.value as IrcClientState.Ready
        assertTrue(ready.caps.contains("message-tags"))

        client.sendTyping("#chan", "active")
        runCurrent()
        assertTrue(ft.sent.any { it.startsWith("@+typing=active TAGMSG #chan") })
    }

    @Test
    fun `bouncer fallback isolates rejected cap so chathistory still activates`() = runTest {
        val ft = FakeTransport()
        val staleCap = "vendor/stale-after-bind"
        val client = IrcClient(
            config(bouncerNetId = "42", extraCaps = setOf(staleCap)),
            ft.factory(),
            clientScope(),
        )
        client.start()
        runCurrent()

        ft.feed(":srv CAP * LS :$fullLs $staleCap")
        runCurrent()
        ft.feed(":srv CAP motd ACK :sasl soju.im/bouncer-networks")
        runCurrent()

        // Soju changes its available capabilities after BOUNCER BIND. The first DEL also
        // completes the fallback registration path while the deferred feature requests wait.
        ft.feed(":srv CAP motd DEL :$staleCap")
        runCurrent()
        assertTrue(client.state.value is IrcClientState.Ready)

        advanceTimeBy(RegistrationStateMachine.FALLBACK_FEATURE_CAP_DELAY_MS)
        runCurrent()

        val deferredRequests = ft.sent
            .filter { it.startsWith("CAP REQ :") }
            .drop(1)
        assertTrue(deferredRequests.any { it.contains("draft/chathistory") })
        for (request in deferredRequests) {
            val caps = request.substringAfter("CAP REQ :")
            val reply = if (caps.split(' ').contains(staleCap)) "NAK" else "ACK"
            ft.feed(":srv CAP motd $reply :$caps")
            runCurrent()
        }

        assertTrue(client.hasCap("draft/chathistory"))
    }

    @Test
    fun `433 nick in use retries with underscore`() = runTest {
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
    fun `watchdog timeout disconnects`() = runTest {
        val ft = FakeTransport()
        val client = registered(ft)
        val critical = clientScope().async {
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
    fun `immediate liveness probe response preserves Ready connection`() = runTest {
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
    fun `immediate liveness probe timeout disconnects for actor recovery`() = runTest {
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

    // -- LIST / listChannels (plans/16 §5.7) --

    @Test
    fun `labeled LIST parses 321-322-323 batch into listings`() = runTest {
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
    fun `labeled LIST cap retains the most populated rows`() = runTest {
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
    fun `raw fallback collects 322s until 323 without labeled-response`() = runTest {
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
    fun `ELIST U gates the minUsers filter param`() = runTest {
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
    fun `listChannels truncates to cap`() = runTest {
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
    fun `listChannels cap retains the most populated raw rows`() = runTest {
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
    fun `WEBPUSH register waits for exact raw acknowledgement`() = runTest {
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
    fun `WEBPUSH register surfaces matching FAIL`() = runTest {
        val ft = FakeTransport()
        val client = registered(ft)
        val result = clientScope().async {
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
    fun `WEBPUSH register fails when connection closes`() = runTest {
        val ft = FakeTransport()
        val client = registered(ft)
        val result = clientScope().async {
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
    fun `WEBPUSH register times out`() = runTest {
        val ft = FakeTransport()
        val client = registered(ft)
        val result = clientScope().async {
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
    private suspend fun kotlinx.coroutines.test.TestScope.registered(ft: FakeTransport): IrcClient {
        val client = IrcClient(config(), ft.factory(), clientScope())
        client.start()
        runCurrent()
        ft.feed(":srv CAP * LS :$fullLs")
        runCurrent()
        ft.feed(":srv CAP motd ACK :$fullLs")
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
}
