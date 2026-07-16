package io.github.trevarj.motd.service

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusNotificationTextTest {
    @Test
    fun initial_service_state_is_neutral() {
        assertEquals(
            "Keeping chats connected",
            statusNotificationText(connectedCount = 0, reconnecting = false, starting = true),
        )
    }

    @Test
    fun sustained_reconnect_and_connected_states_remain_explicit() {
        assertEquals(
            "Reconnecting…",
            statusNotificationText(connectedCount = 0, reconnecting = true, starting = false),
        )
        assertEquals(
            "Connected to 2 networks",
            statusNotificationText(connectedCount = 2, reconnecting = false, starting = false),
        )
    }
}
