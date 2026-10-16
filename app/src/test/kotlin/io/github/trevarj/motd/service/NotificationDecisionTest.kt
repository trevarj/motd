package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.sync.EventOrigin
import io.github.trevarj.motd.data.sync.incomingNotificationDecision
import io.github.trevarj.motd.irc.event.IrcEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth table for [shouldPostNotification]. Persisted scope/watch eligibility is applied
 * upstream in EventProcessor; ALL and MENTIONS never bypass mute, only a watch does.
 *
 * Precedence: already-read > foreground > mute > sender category permission.
 */
class NotificationDecisionTest {
    @Test
    fun `foreground buffer suppresses every category`() {
        for (muted in listOf(false, true)) {
            for (allowed in listOf(false, true)) {
                assertFalse(shouldPostNotification(foreground = true, muted = muted, senderAllowsNotification = allowed))
            }
        }
    }

    @Test
    fun `disabled sender category is silenced even with a watch`() {
        for (muted in listOf(false, true)) {
            for (watched in listOf(false, true)) {
                assertFalse(
                    shouldPostNotification(
                        foreground = false,
                        muted = muted,
                        senderAllowsNotification = false,
                        watched = watched,
                    ),
                )
            }
        }
    }

    @Test
    fun `enabled sender category still respects explicit mute`() {
        assertFalse(shouldPostNotification(foreground = false, muted = true, senderAllowsNotification = true))
        assertTrue(shouldPostNotification(foreground = false, muted = false, senderAllowsNotification = true))
    }

    @Test
    fun `message covered by durable marker is suppressed`() {
        assertFalse(
            shouldPostNotification(
                foreground = false,
                muted = false,
                senderAllowsNotification = true,
                alreadyRead = true,
            ),
        )
    }

    @Test
    fun `watched posts despite mute`() {
        for (muted in listOf(false, true)) {
            assertTrue(
                shouldPostNotification(
                    foreground = false,
                    muted = muted,
                    senderAllowsNotification = true,
                    watched = true,
                ),
            )
        }
    }

    @Test
    fun `watched still respects foreground category permission and already read`() {
        assertFalse(
            shouldPostNotification(
                foreground = true,
                muted = true,
                senderAllowsNotification = true,
                watched = true,
            ),
        )
        assertFalse(
            shouldPostNotification(
                foreground = false,
                muted = true,
                senderAllowsNotification = false,
                watched = true,
            ),
        )
        assertFalse(
            shouldPostNotification(
                foreground = false,
                muted = true,
                senderAllowsNotification = true,
                alreadyRead = true,
                watched = true,
            ),
        )
    }

    @Test
    fun `all and mentions policy respect mute without a watch`() {
        listOf(NotificationMode.ALL, NotificationMode.MENTIONS).forEach { mode ->
            val decision =
                incomingNotificationDecision(
                    type = BufferType.CHANNEL,
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    origin = EventOrigin.LIVE,
                    hasMention = true,
                    consoleNotice = false,
                    mode = mode,
                    watchActive = false,
                )
            assertTrue(decision.eligible)
            assertFalse(
                shouldPostNotification(
                    foreground = false,
                    muted = true,
                    senderAllowsNotification = true,
                    watched = decision.bypassesMute,
                ),
            )
        }
    }

    @Test
    fun `read marker dismisses only when it covers the newest notification`() {
        val latest =
            io.github.trevarj.motd.data.db
                .TimelineAnchor(100, 2)
        assertFalse(
            readMarkerCoversNotification(
                io.github.trevarj.motd.data.db
                    .TimelineAnchor(100, 1),
                latest,
            ),
        )
        assertTrue(readMarkerCoversNotification(latest, latest))
        assertTrue(
            readMarkerCoversNotification(
                io.github.trevarj.motd.data.db
                    .TimelineAnchor(101, 1),
                latest,
            ),
        )
    }
}
