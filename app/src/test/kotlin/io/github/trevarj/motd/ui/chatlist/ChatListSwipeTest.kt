package io.github.trevarj.motd.ui.chatlist

import androidx.compose.material3.SwipeToDismissBoxValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListSwipeTest {
    @Test
    fun `swipe requires sixty five percent of row width`() {
        assertEquals(130f, chatListSwipePositionalThreshold(200f), 0f)
        assertEquals(0.65f, CHAT_LIST_SWIPE_THRESHOLD_FRACTION, 0f)
    }

    @Test
    fun `haptic fires once per end to start arming`() {
        assertFalse(shouldPerformChatListSwipeHaptic(SwipeToDismissBoxValue.Settled, SwipeToDismissBoxValue.Settled, enabled = true))
        assertFalse(shouldPerformChatListSwipeHaptic(SwipeToDismissBoxValue.Settled, SwipeToDismissBoxValue.StartToEnd, enabled = true))
        assertTrue(shouldPerformChatListSwipeHaptic(SwipeToDismissBoxValue.Settled, SwipeToDismissBoxValue.EndToStart, enabled = true))
        assertFalse(shouldPerformChatListSwipeHaptic(SwipeToDismissBoxValue.EndToStart, SwipeToDismissBoxValue.EndToStart, enabled = true))
        assertFalse(shouldPerformChatListSwipeHaptic(SwipeToDismissBoxValue.EndToStart, SwipeToDismissBoxValue.Settled, enabled = true))
        assertTrue(shouldPerformChatListSwipeHaptic(SwipeToDismissBoxValue.Settled, SwipeToDismissBoxValue.EndToStart, enabled = true))
        assertFalse(shouldPerformChatListSwipeHaptic(SwipeToDismissBoxValue.Settled, SwipeToDismissBoxValue.EndToStart, enabled = false))
    }
}
