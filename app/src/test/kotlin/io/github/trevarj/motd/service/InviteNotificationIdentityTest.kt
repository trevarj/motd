package io.github.trevarj.motd.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteNotificationIdentityTest {
    @Test fun `invitation notification ids are stable positive and distinct nearby`() {
        val first = MotdNotifications.invitationNotificationId(41)
        assertEquals(first, MotdNotifications.invitationNotificationId(41))
        assertNotEquals(first, MotdNotifications.invitationNotificationId(42))
        assertTrue(first >= 0x40000000)
    }

    /**
     * Buffer id 1 used to be posted (and cancelled) as raw notification id 1, which is the pinned
     * foreground-service status notification.
     */
    @Test fun `message notification ids never alias the status notification`() {
        for (bufferId in 0L..2_000L) {
            val id = MotdNotifications.messageNotificationId(bufferId)
            assertNotEquals(IrcForegroundService.STATUS_ID, id)
            assertTrue(MotdNotifications.isMessageNotificationId(id))
        }
    }

    @Test fun `message notification ids are stable distinct and outside the other ranges`() {
        val first = MotdNotifications.messageNotificationId(41)
        assertEquals(first, MotdNotifications.messageNotificationId(41))
        assertNotEquals(first, MotdNotifications.messageNotificationId(42))
        assertTrue(first in 0x10000000..0x1fffffff)
        assertNotEquals(first, MotdNotifications.invitationNotificationId(41))
        assertNotEquals(first, MotdNotifications.transferNotificationId(41))
    }

    @Test fun `only namespaced ids are recognized as message notifications`() {
        assertFalse(MotdNotifications.isMessageNotificationId(IrcForegroundService.STATUS_ID))
        assertFalse(MotdNotifications.isMessageNotificationId(1))
        assertFalse(MotdNotifications.isMessageNotificationId(4_242))
        assertFalse(MotdNotifications.isMessageNotificationId(MotdNotifications.invitationNotificationId(7)))
        assertFalse(MotdNotifications.isMessageNotificationId(MotdNotifications.transferNotificationId(7)))
    }

    @Test fun `transfer notification ids are stable positive and distinct from invitations`() {
        val first = MotdNotifications.transferNotificationId(41)
        assertEquals(first, MotdNotifications.transferNotificationId(41))
        assertNotEquals(first, MotdNotifications.transferNotificationId(42))
        assertNotEquals(first, MotdNotifications.invitationNotificationId(41))
        assertTrue(first >= 0x50000000)
    }

    @Test fun `invitation namespace is disjoint even for ids using the old two high bits`() {
        for (messageId in listOf(7L, 0x10000007L, 0x20000007L, 0x30000007L, (1L shl 60) + 7L)) {
            val invitation = MotdNotifications.invitationNotificationId(messageId)
            assertTrue(invitation in 0x40000000..0x4fffffff)
            assertNotEquals(MotdNotifications.transferNotificationId(messageId), invitation)
            assertNotEquals(MotdNotifications.watchEndedNotificationId(messageId), invitation)
            assertNotEquals(MotdNotifications.sendFailureNotificationId(messageId), invitation)
        }
    }
}
