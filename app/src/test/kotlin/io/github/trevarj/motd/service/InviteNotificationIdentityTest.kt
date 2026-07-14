package io.github.trevarj.motd.service

import org.junit.Assert.assertEquals
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
}
