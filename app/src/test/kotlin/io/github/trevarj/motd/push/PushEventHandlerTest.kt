package io.github.trevarj.motd.push

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.service.IrcEventSink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushEventHandlerTest {

    private class RecordingSink : IrcEventSink {
        val events = mutableListOf<Pair<Long, IrcEvent>>()
        override suspend fun process(networkId: Long, event: IrcEvent) {
            events.add(networkId to event)
        }
    }

    private class RecordingNotifier : PushNotifier {
        val notified = mutableListOf<Pair<Long, IrcEvent.ChatMessage>>()
        override suspend fun notify(networkId: Long, message: IrcEvent.ChatMessage) {
            notified.add(networkId to message)
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
    fun mapToEvent_ignores_non_chat_and_other_ctcp() {
        assertNull(PushEventHandler.mapToEvent(IrcMessage.parse("PING :x")))
        assertNull(PushEventHandler.mapToEvent(IrcMessage.parse(":a!a@h PRIVMSG #c :VERSION")))
    }

    // --- full handle path (real crypto facade) -----------------------------

    @Test
    fun handle_decrypts_maps_feeds_sink_and_notifies() = runTest {
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
        val notifier = RecordingNotifier()
        val handler = PushEventHandler(WebPushCryptoFacade.Default, sink, notifier)

        val ev = handler.handle(networkId = 42L, body = body, keys = receiver)

        assertTrue(ev is IrcEvent.ChatMessage)
        assertEquals(1, sink.events.size)
        assertEquals(42L, sink.events[0].first)
        assertEquals(1, notifier.notified.size)
        assertEquals("pushed message", (sink.events[0].second as IrcEvent.ChatMessage).text)
    }

    @Test
    fun handle_swallows_undecryptable_body() = runTest {
        val sink = RecordingSink()
        val handler = PushEventHandler(
            crypto = { _, _ -> error("boom") },
            eventSink = sink,
            notifier = NoopPushNotifier,
        )
        val ev = handler.handle(1L, ByteArray(50), WebPushCrypto.generateKeyMaterial())
        assertNull(ev)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun handle_swallows_unparseable_line() = runTest {
        val sink = RecordingSink()
        // Facade returns a line that IrcMessage.parse rejects.
        val handler = PushEventHandler(
            crypto = { _, _ -> "   ".toByteArray() },
            eventSink = sink,
            notifier = NoopPushNotifier,
        )
        val ev = handler.handle(1L, ByteArray(0), WebPushCrypto.generateKeyMaterial())
        assertNull(ev)
        assertTrue(sink.events.isEmpty())
    }
}
