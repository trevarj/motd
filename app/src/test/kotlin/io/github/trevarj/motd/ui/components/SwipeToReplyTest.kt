package io.github.trevarj.motd.ui.components

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeToReplyTest {
    @Test
    fun ltr_and_rtl_only_move_toward_reply_with_resistance_and_a_cap() {
        assertEquals(26f, swipeReplyVisualOffset(40f, 1f, 80f), 0.001f)
        assertEquals(0f, swipeReplyVisualOffset(-40f, 1f, 80f), 0.001f)
        assertEquals(26f, swipeReplyVisualOffset(-40f, -1f, 80f), 0.001f)
        assertEquals(0f, swipeReplyVisualOffset(40f, -1f, 80f), 0.001f)
        assertEquals(80f, swipeReplyVisualOffset(1_000f, 1f, 80f), 0.001f)
    }

    @Test
    fun threshold_is_inclusive() {
        assertFalse(swipeReplyArmed(55.99f, 56f))
        assertTrue(swipeReplyArmed(56f, 56f))
        assertTrue(swipeReplyArmed(80f, 56f))
    }

    @Test
    fun only_a_completed_armed_drag_commits_and_haptics_once() {
        assertFalse(shouldCommitSwipeReply(completed = true, visualOffsetPx = 55f, thresholdPx = 56f))
        assertFalse(shouldCommitSwipeReply(completed = false, visualOffsetPx = 80f, thresholdPx = 56f))
        assertTrue(shouldCommitSwipeReply(completed = true, visualOffsetPx = 56f, thresholdPx = 56f))
        assertTrue(shouldHapticSwipeReply(alreadySent = false, visualOffsetPx = 56f, thresholdPx = 56f))
        assertFalse(shouldHapticSwipeReply(alreadySent = true, visualOffsetPx = 80f, thresholdPx = 56f))
    }

    @Test
    fun reply_side_respects_the_physical_system_gesture_edge() {
        assertTrue(isReplySystemEdge(10f, 400f, LayoutDirection.Ltr, 24f, 24f))
        assertFalse(isReplySystemEdge(30f, 400f, LayoutDirection.Ltr, 24f, 24f))
        assertTrue(isReplySystemEdge(390f, 400f, LayoutDirection.Rtl, 24f, 24f))
        assertFalse(isReplySystemEdge(360f, 400f, LayoutDirection.Rtl, 24f, 24f))
    }
}
