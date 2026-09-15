package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.service.NotificationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingNotificationDecisionTest {
    @Test
    fun `query notifies under mentions policy`() {
        val decision = decision(type = BufferType.QUERY)
        assertTrue(decision.eligible)
        assertTrue(shouldPresentIncoming(decision, BufferType.QUERY, EventOrigin.LIVE, false, false))
    }

    @Test
    fun `channel mention notifies`() {
        assertTrue(decision(hasMention = true).eligible)
    }

    @Test
    fun `channel without mention stays silent`() {
        assertFalse(decision().eligible)
    }

    @Test
    fun `watched channel chat notifies without a mention`() {
        assertEquals(IncomingNotificationDecision(true, true, true), decision(watchActive = true))
    }

    @Test
    fun `console notice notifies independently of policy`() {
        val decision =
            decision(
                type = BufferType.SERVER,
                kind = IrcEvent.ChatKind.NOTICE,
                mode = NotificationMode.OFF,
                origin = EventOrigin.PUSH,
                consoleNotice = true,
            )
        assertEquals(IncomingNotificationDecision(true, true, false), decision)
        assertTrue(shouldPresentIncoming(decision, BufferType.SERVER, EventOrigin.PUSH, false, true))
    }

    @Test
    fun `server buffer without console notice stays silent even when watched`() {
        assertFalse(
            decision(type = BufferType.SERVER, hasMention = true, mode = NotificationMode.ALL, watchActive = true).eligible,
        )
    }

    @Test
    fun `off suppresses direct chat and channel highlights for every chat kind`() {
        IrcEvent.ChatKind.entries.forEach { kind ->
            assertEquals(
                IncomingNotificationDecision(false, true, false),
                decision(type = BufferType.QUERY, kind = kind, mode = NotificationMode.OFF),
            )
            assertFalse(decision(kind = kind, hasMention = true, mode = NotificationMode.OFF).eligible)
            assertTrue(decision(type = BufferType.QUERY, kind = kind, mode = NotificationMode.MENTIONS).eligible)
            assertTrue(decision(kind = kind, hasMention = true, mode = NotificationMode.MENTIONS).eligible)
        }
    }

    @Test
    fun `all admits channel privmsg and action but not ordinary notices or mute bypass`() {
        IrcEvent.ChatKind.entries.forEach { kind ->
            assertEquals(
                IncomingNotificationDecision(kind != IrcEvent.ChatKind.NOTICE, true, false),
                decision(kind = kind, mode = NotificationMode.ALL),
            )
        }
    }

    @Test
    fun `push all stores ordinary channel eligibility without presenting it`() {
        val ordinary = decision(mode = NotificationMode.ALL, origin = EventOrigin.PUSH)
        assertEquals(IncomingNotificationDecision(true, true, false), ordinary)
        assertFalse(shouldPresentIncoming(ordinary, BufferType.CHANNEL, EventOrigin.PUSH, false, false))
        assertTrue(shouldPresentIncoming(ordinary, BufferType.CHANNEL, EventOrigin.LIVE, false, false))
        assertTrue(shouldPresentIncoming(ordinary, BufferType.CHANNEL, EventOrigin.PUSH, true, false))
        assertTrue(shouldPresentIncoming(ordinary, BufferType.QUERY, EventOrigin.PUSH, false, false))
    }

    @Test
    fun `history and replay capture context policy without resolving or watching`() {
        listOf(EventOrigin.HISTORY, EventOrigin.REPLAY).forEach { origin ->
            val context = decision(mode = NotificationMode.ALL, origin = origin, watchActive = true)
            assertEquals(IncomingNotificationDecision(true, false, false), context)
            assertFalse(shouldPresentIncoming(context, BufferType.CHANNEL, origin, false, false))
            assertFalse(shouldPresentIncoming(context, BufferType.CHANNEL, EventOrigin.LIVE, false, false))
            assertEquals(
                IncomingNotificationDecision(false, false, false),
                decision(mode = NotificationMode.OFF, hasMention = true, origin = origin, watchActive = true),
            )
            assertTrue(decision(type = BufferType.QUERY, origin = origin).eligible)
            assertTrue(decision(hasMention = true, origin = origin).eligible)
        }
    }

    @Test
    fun `watch overrides off only for live channel chat or pushed highlights`() {
        IrcEvent.ChatKind.entries.forEach { kind ->
            assertEquals(
                IncomingNotificationDecision(kind != IrcEvent.ChatKind.NOTICE, true, kind != IrcEvent.ChatKind.NOTICE),
                decision(kind = kind, mode = NotificationMode.OFF, watchActive = true),
            )
            assertEquals(
                IncomingNotificationDecision(false, true, false),
                decision(kind = kind, mode = NotificationMode.OFF, origin = EventOrigin.PUSH, watchActive = true),
            )
            assertEquals(
                IncomingNotificationDecision(true, true, true),
                decision(kind = kind, mode = NotificationMode.OFF, origin = EventOrigin.PUSH, hasMention = true, watchActive = true),
            )
        }
        assertFalse(decision(type = BufferType.QUERY, mode = NotificationMode.OFF, watchActive = true).eligible)
        assertFalse(
            decision(kind = IrcEvent.ChatKind.NOTICE, mode = NotificationMode.OFF, hasMention = true, watchActive = true).eligible,
        )
    }

    @Test
    fun `presentation never promotes a frozen false or a historical observation`() {
        val suppressed = IncomingNotificationDecision(false, true, false)
        assertFalse(shouldPresentIncoming(suppressed, BufferType.QUERY, EventOrigin.LIVE, true, false))
        val eligible = IncomingNotificationDecision(true, true, true)
        assertFalse(shouldPresentIncoming(eligible, BufferType.SERVER, EventOrigin.LIVE, true, false))
        assertFalse(shouldPresentIncoming(eligible, BufferType.SERVER, EventOrigin.PUSH, true, false))
        listOf(EventOrigin.HISTORY, EventOrigin.REPLAY).forEach { origin ->
            assertFalse(shouldPresentIncoming(eligible, BufferType.QUERY, origin, true, false))
        }
    }

    private fun decision(
        type: BufferType = BufferType.CHANNEL,
        kind: IrcEvent.ChatKind = IrcEvent.ChatKind.PRIVMSG,
        origin: EventOrigin = EventOrigin.LIVE,
        hasMention: Boolean = false,
        consoleNotice: Boolean = false,
        mode: NotificationMode = NotificationMode.MENTIONS,
        watchActive: Boolean = false,
    ) = incomingNotificationDecision(type, kind, origin, hasMention, consoleNotice, mode, watchActive)
}
