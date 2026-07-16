package io.github.trevarj.motd.push

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.service.IrcEventSink
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushEventHandlerTest {

    private class RecordingHealthStore : PushHealthStore {
        override val health: Flow<Map<Long, NetworkPushHealth>> = MutableStateFlow(emptyMap())
        var probes = 0
        var deliveries = 0
        var warnings = 0
        override suspend fun snapshot() = emptyMap<Long, NetworkPushHealth>()
        override suspend fun requestingEndpoint(networkId: Long) = Unit
        override suspend fun endpointReceived(networkId: Long, endpoint: String) = Unit
        override suspend fun waitingForServer(networkId: Long) = Unit
        override suspend fun capability(networkId: Long, supported: Boolean) = Unit
        override suspend fun verifying(networkId: Long) = Unit
        override suspend fun registered(networkId: Long) = Unit
        override suspend fun probeDelivered(networkId: Long) { probes++ }
        override suspend fun messageDelivered(networkId: Long) { deliveries++ }
        override suspend fun failed(networkId: Long, code: String) = Unit
        override suspend fun warning(networkId: Long, code: String) { warnings++ }
        override suspend fun clear(networkId: Long) = Unit
        override suspend fun retain(networkIds: Set<Long>) = Unit
    }

    private class RecordingSink : IrcEventSink {
        val events = mutableListOf<Pair<Long, IrcEvent>>()
        val pushEvents = mutableListOf<Pair<Long, IrcEvent>>()
        override suspend fun process(networkId: Long, event: IrcEvent) {
            events.add(networkId to event)
        }

        override suspend fun processPush(networkId: Long, event: IrcEvent) {
            pushEvents.add(networkId to event)
        }
    }

    // --- pure mapper -------------------------------------------------------

    @Test
    fun mapToEvent_privmsg_to_channel() {
        val msg = IrcMessage.parse("@msgid=abc;time=2020-01-01T00:00:00.000Z :alice!a@h PRIVMSG #chan :hello there")
        val ev = PushEventHandler.mapToEvent(msg)
        assertTrue(ev is IrcEvent.ChatMessage)
        ev as IrcEvent.ChatMessage
        assertEquals(IrcEvent.ChatKind.PRIVMSG, ev.kind)
        assertEquals("#chan", ev.target)
        assertEquals("alice", ev.source.nick)
        assertEquals("hello there", ev.text)
        assertEquals("abc", ev.ctx.msgid)
        assertEquals(false, ev.isSelf)
    }

    @Test
    fun mapToEvent_ctcp_action() {
        val msg = IrcMessage.parse(":bob!b@h PRIVMSG #chan :ACTION waves")
        val ev = PushEventHandler.mapToEvent(msg) as IrcEvent.ChatMessage
        assertEquals(IrcEvent.ChatKind.ACTION, ev.kind)
        assertEquals("waves", ev.text)
    }

    @Test
    fun mapToEvent_notice() {
        val msg = IrcMessage.parse(":serv NOTICE me :heads up")
        val ev = PushEventHandler.mapToEvent(msg) as IrcEvent.ChatMessage
        assertEquals(IrcEvent.ChatKind.NOTICE, ev.kind)
    }

    @Test
    fun mapToEvent_invite() {
        val event = PushEventHandler.mapToEvent(
            IrcMessage.parse("@msgid=i1;time=2020-01-01T00:00:00.000Z :alice!a@h INVITE me #secret"),
        ) as IrcEvent.Invited
        assertEquals("alice", event.by)
        assertEquals("me", event.nick)
        assertEquals("#secret", event.channel)
        assertEquals("i1", event.ctx.msgid)
    }

    @Test
    fun mapToEvent_ratified_reply_wins_over_legacy_reply() {
        val msg = IrcMessage.parse(
            "@+reply=new;+draft/reply=old :alice!u@h PRIVMSG #chan :reply",
        )
        val event = PushEventHandler.mapToEvent(msg) as IrcEvent.ChatMessage
        assertEquals("new", event.replyToMsgid)
    }

    @Test
    fun mapToEvent_reaction_and_unreact_mutation() {
        val react = PushEventHandler.mapToEvent(
            IrcMessage.parse("@+draft/react=👍;+reply=m1 :alice!u@h TAGMSG #chan"),
        ) as IrcEvent.TagMessage
        assertEquals("👍", react.reactEmoji)
        assertEquals("m1", react.reactTargetMsgid)

        val unreact = PushEventHandler.mapToEvent(
            IrcMessage.parse("@+draft/unreact=👍;+reply=m1 :alice!u@h TAGMSG #chan"),
        ) as IrcEvent.Raw
        assertEquals("👍", unreact.message.tags["+draft/unreact"])
    }

    @Test
    fun mapToEvent_accepts_reaction_compatibility_aliases() {
        val react = PushEventHandler.mapToEvent(
            IrcMessage.parse("@+react=👍;draft/reply=m1 :alice!u@h TAGMSG #chan"),
        ) as IrcEvent.TagMessage
        assertEquals("👍", react.reactEmoji)
        assertEquals("m1", react.reactTargetMsgid)

        val unreact = PushEventHandler.mapToEvent(
            IrcMessage.parse("@+unreact=👍;draft/reply=m1 :alice!u@h TAGMSG #chan"),
        )
        assertTrue(unreact is IrcEvent.Raw)
    }

    @Test
    fun mapToEvent_ignores_non_chat_and_other_ctcp() {
        assertNull(PushEventHandler.mapToEvent(IrcMessage.parse("PING :x")))
        assertNull(PushEventHandler.mapToEvent(IrcMessage.parse(":a!a@h PRIVMSG #c :VERSION")))
    }

    // --- full handle path (real crypto facade) -----------------------------

    @Test
    fun handle_decrypts_maps_and_feeds_sink_exactlyOnce() = runTest {
        val receiver = WebPushCrypto.generateKeyMaterial()
        val sender = WebPushCrypto.generateEcKeyPair()
        val line = "@msgid=xyz :carol!c@h PRIVMSG #room :pushed message"
        val body = WebPushCrypto.encrypt(
            plaintext = line.toByteArray(Charsets.UTF_8),
            salt = ByteArray(16) { 7 },
            recordSize = 4096,
            receiverPublic = receiver.publicUncompressed,
            receiverAuth = receiver.auth,
            senderKeys = sender,
        )

        val sink = RecordingSink()
        val handler = PushEventHandler(WebPushCryptoFacade.Default, sink)

        val ev = handler.handle(networkId = 42L, body = body, keys = receiver)

        assertTrue(ev is IrcEvent.ChatMessage)
        assertTrue(sink.events.isEmpty())
        assertEquals(1, sink.pushEvents.size)
        assertEquals(42L, sink.pushEvents[0].first)
        assertEquals("pushed message", (sink.pushEvents[0].second as IrcEvent.ChatMessage).text)
    }

    @Test
    fun handle_swallows_undecryptable_body() = runTest {
        val sink = RecordingSink()
        val handler = PushEventHandler(
            crypto = { _, _ -> error("boom") },
            eventSink = sink,
        )
        val ev = handler.handle(1L, ByteArray(50), WebPushCrypto.generateKeyMaterial())
        assertNull(ev)
        assertTrue(sink.events.isEmpty())
        assertTrue(sink.pushEvents.isEmpty())
    }

    @Test
    fun handle_accepts_connector_decrypted_body_without_redecrypting() = runTest {
        val sink = RecordingSink()
        val handler = PushEventHandler(
            crypto = { _, _ -> error("connector already decrypted the payload") },
            eventSink = sink,
        )
        val body = ":carol!c@h PRIVMSG #room :already clear".toByteArray()

        val event = handler.handle(
            networkId = 42L,
            body = body,
            keys = WebPushCrypto.generateKeyMaterial(),
            alreadyDecrypted = true,
        )

        assertEquals("already clear", (event as IrcEvent.ChatMessage).text)
        assertEquals(1, sink.pushEvents.size)
    }

    @Test
    fun handle_swallows_unparseable_line() = runTest {
        val sink = RecordingSink()
        // Facade returns a line that IrcMessage.parse rejects.
        val handler = PushEventHandler(
            crypto = { _, _ -> "   ".toByteArray() },
            eventSink = sink,
        )
        val ev = handler.handle(1L, ByteArray(0), WebPushCrypto.generateKeyMaterial())
        assertNull(ev)
        assertTrue(sink.events.isEmpty())
        assertTrue(sink.pushEvents.isEmpty())
    }

    @Test
    fun registration_probe_updates_health_without_chat_or_notification() = runTest {
        val receiver = WebPushCrypto.generateKeyMaterial()
        val sender = WebPushCrypto.generateEcKeyPair()
        val body = WebPushCrypto.encrypt(
            plaintext = ":soju NOTE WEBPUSH REGISTERED".toByteArray(),
            salt = ByteArray(16) { 3 },
            recordSize = 4096,
            receiverPublic = receiver.publicUncompressed,
            receiverAuth = receiver.auth,
            senderKeys = sender,
        )
        val sink = RecordingSink()
        val health = RecordingHealthStore()
        val handler = PushEventHandler(WebPushCryptoFacade.Default, sink, health)

        assertNull(handler.handle(7L, body, receiver))
        assertEquals(1, health.probes)
        assertTrue(sink.events.isEmpty())
        assertTrue(sink.pushEvents.isEmpty())
    }
}
