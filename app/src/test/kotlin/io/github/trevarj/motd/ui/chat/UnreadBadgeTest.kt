package io.github.trevarj.motd.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure counting behind the scroll-to-bottom FAB badge (bug #7). [unreadBelowViewport] receives the
 * server times of the reverse-list rows scrolled off toward the bottom (indices below the fold,
 * newest first) and counts those newer than the frozen read marker. The badge must shrink as the
 * user scrolls down and be zero at the bottom — not a monotonic arrival tally.
 */
class UnreadBadgeTest {

    @Test fun `empty below-fold window is zero`() {
        assertEquals(0, unreadBelowViewport(emptyList(), marker = 100L))
    }

    @Test fun `counts only rows newer than the marker`() {
        // Reverse order: newest first. Marker at 100 → three rows above it are unread.
        val below = listOf(130L, 120L, 110L, 90L, 80L)
        assertEquals(3, unreadBelowViewport(below, marker = 100L))
    }

    @Test fun `all below-fold rows already read yields zero`() {
        assertEquals(0, unreadBelowViewport(listOf(90L, 80L, 70L), marker = 100L))
    }

    @Test fun `shrinks as fewer rows sit below the fold`() {
        val all = listOf(140L, 130L, 120L, 110L)
        // Scrolling down removes rows from the below-fold window newest-first.
        assertEquals(4, unreadBelowViewport(all, marker = 100L))
        assertEquals(2, unreadBelowViewport(all.drop(2), marker = 100L))
        assertEquals(0, unreadBelowViewport(emptyList(), marker = 100L))
    }

    @Test fun `marker equal to a row time excludes that row (strictly newer)`() {
        assertEquals(1, unreadBelowViewport(listOf(110L, 100L), marker = 100L))
    }
}
