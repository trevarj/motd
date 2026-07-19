package io.github.trevarj.motd.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Truth table for [messageStatus], the pure status decision shared by every render site. Priority
 * is failed > pending > sent, and incoming messages ([isSelf] false) must always be [NONE] so no
 * check leaks onto other people's lines.
 */
class MessageStatusTest {

    @Test fun self_confirmed_is_sent() {
        assertEquals(MsgStatus.SENT, messageStatus(isSelf = true, pending = false, failed = false))
    }

    @Test fun self_pending_is_pending() {
        assertEquals(MsgStatus.PENDING, messageStatus(isSelf = true, pending = true, failed = false))
    }

    @Test fun self_failed_is_failed() {
        assertEquals(MsgStatus.FAILED, messageStatus(isSelf = true, pending = false, failed = true))
    }

    @Test fun failed_wins_over_pending() {
        assertEquals(MsgStatus.FAILED, messageStatus(isSelf = true, pending = true, failed = true))
    }

    @Test fun incoming_confirmed_is_none() {
        assertEquals(MsgStatus.NONE, messageStatus(isSelf = false, pending = false, failed = false))
    }

    @Test fun incoming_never_shows_a_check_even_if_flagged() {
        // Incoming messages are never pending/failed in practice, but the isSelf guard must hold
        // regardless of the other flags so a check can never appear on another user's line.
        assertEquals(MsgStatus.NONE, messageStatus(isSelf = false, pending = true, failed = false))
        assertEquals(MsgStatus.NONE, messageStatus(isSelf = false, pending = false, failed = true))
    }
}
