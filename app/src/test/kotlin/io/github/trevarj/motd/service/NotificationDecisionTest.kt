package io.github.trevarj.motd.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth table for [shouldPostNotification] (plans/13 §2.6). The `(DM || mention)` gate is applied
 * upstream in EventProcessor, so every case here already qualifies as a DM or mention.
 *
 * Precedence: already-read > foreground > mute > fool; friend status never bypasses a mute.
 */
class NotificationDecisionTest {

    @Test
    fun `foreground buffer suppresses everything`() {
        // Even a friend / normal / un-muted case is suppressed while foregrounded.
        assertFalse(shouldPostNotification(foreground = true, muted = false, senderIsFriend = false, senderIsFool = false))
        assertFalse(shouldPostNotification(foreground = true, muted = false, senderIsFriend = true, senderIsFool = false))
        assertFalse(shouldPostNotification(foreground = true, muted = true, senderIsFriend = true, senderIsFool = false))
    }

    @Test
    fun `fool sender is fully silenced`() {
        // A fool never notifies, regardless of mute / friend-ness (disjoint sets, but assert both).
        assertFalse(shouldPostNotification(foreground = false, muted = false, senderIsFriend = false, senderIsFool = true))
        assertFalse(shouldPostNotification(foreground = false, muted = true, senderIsFriend = false, senderIsFool = true))
        assertFalse(shouldPostNotification(foreground = false, muted = false, senderIsFriend = true, senderIsFool = true))
    }

    @Test
    fun `explicit mute silences friends too`() {
        assertFalse(shouldPostNotification(foreground = false, muted = true, senderIsFriend = true, senderIsFool = false))
        assertTrue(shouldPostNotification(foreground = false, muted = false, senderIsFriend = true, senderIsFool = false))
    }

    @Test
    fun `muted non-friend is suppressed`() {
        assertFalse(shouldPostNotification(foreground = false, muted = true, senderIsFriend = false, senderIsFool = false))
    }

    @Test
    fun `normal un-muted message notifies`() {
        assertTrue(shouldPostNotification(foreground = false, muted = false, senderIsFriend = false, senderIsFool = false))
    }

    @Test
    fun `message covered by durable marker is suppressed`() {
        assertFalse(
            shouldPostNotification(
                foreground = false,
                muted = false,
                senderIsFriend = true,
                senderIsFool = false,
                alreadyRead = true,
            ),
        )
    }

    @Test
    fun `fool beats friend`() {
        // If a nick were somehow in both, fool silencing wins.
        assertFalse(shouldPostNotification(foreground = false, muted = false, senderIsFriend = true, senderIsFool = true))
    }

    @Test
    fun `read marker dismisses only when it covers the newest notification`() {
        val latest = io.github.trevarj.motd.data.db.TimelineAnchor(100, 2)
        assertFalse(
            readMarkerCoversNotification(
                io.github.trevarj.motd.data.db.TimelineAnchor(100, 1),
                latest,
            ),
        )
        assertTrue(readMarkerCoversNotification(latest, latest))
        assertTrue(
            readMarkerCoversNotification(
                io.github.trevarj.motd.data.db.TimelineAnchor(101, 1),
                latest,
            ),
        )
    }
}
