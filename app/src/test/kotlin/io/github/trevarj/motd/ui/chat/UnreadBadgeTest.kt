package io.github.trevarj.motd.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.AbstractList

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

    @Test fun `advancing the live marker past below-fold rows clears the badge`() {
        // Two unread rows below the fold against the entry marker (100).
        val below = listOf(130L, 120L)
        assertEquals(2, unreadBelowViewport(below, marker = 100L))
        // markRead at the bottom advances the LIVE marker to the newest row; scrolling back up now
        // counts zero (the badge clears without leaving the buffer). The frozen snapshot would have
        // kept counting 2 here — that was the bug.
        assertEquals(0, unreadBelowViewport(below, marker = 130L))
    }

    @Test fun `binary search honors equality and both bounds`() {
        val descending = listOf(500L, 400L, 300L, 200L, 100L)
        assertEquals(0, unreadBelowViewport(descending, marker = 500L))
        assertEquals(2, unreadBelowViewport(descending, marker = 300L))
        assertEquals(5, unreadBelowViewport(descending, marker = 99L))
    }

    @Test fun `large window probes logarithmically`() {
        val size = 4_096
        val values = object : AbstractList<Long>() {
            var reads = 0
            override val size: Int get() = size
            override fun get(index: Int): Long {
                reads++
                return (size - index).toLong()
            }
        }
        assertEquals(2_048, unreadBelowViewport(values, marker = 2_048L))
        // A linear implementation needs thousands of reads. Binary search needs at most log2(n)+1.
        assertEquals(true, values.reads <= 13)
    }

    @Test fun `viewport index excludes own rows while retaining strict marker boundary`() {
        val index = UnreadViewportIndex.from(
            listOf(
                UnreadViewportRow(index = 0, serverTime = 140, isSelf = true),
                UnreadViewportRow(index = 1, serverTime = 130, isSelf = false),
                UnreadViewportRow(index = 2, serverTime = 120, isSelf = true),
                UnreadViewportRow(index = 3, serverTime = 100, isSelf = false),
            ),
        )
        assertEquals(1, index.count(firstVisibleIndex = 3, marker = 100))
        // Equality is read, and the newest self row never inflates the badge.
        assertEquals(0, index.count(firstVisibleIndex = 4, marker = 130))
        assertEquals(1, index.count(firstVisibleIndex = 2, marker = 100))
    }
}
