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
        val rows = listOf(
            row(0, 140, true), row(1, 130, false), row(2, 120, true), row(3, 100, false),
        )
        val index = UnreadViewportIndex().also { it.update(rows.size) { rows[it] } }
        assertEquals(1, index.count(firstVisibleIndex = 3, marker = 100))
        // Equality is read, and the newest self row never inflates the badge.
        assertEquals(0, index.count(firstVisibleIndex = 4, marker = 130))
        assertEquals(1, index.count(firstVisibleIndex = 2, marker = 100))
    }

    @Test fun `append only growth reads only the appended page`() {
        val rows = (0 until 100).map { row(it, (1_000 - it).toLong(), isSelf = it % 7 == 0) }
        val reads = mutableListOf<Int>()
        val index = UnreadViewportIndex()
        index.update(50) { position -> reads += position; rows[position] }
        reads.clear()
        index.update(100) { position -> reads += position; rows[position] }
        // One index-zero identity probe detects a refresh; the old prefix is otherwise untouched.
        assertEquals(listOf(0) + (50 until 100).toList(), reads)
        assertEquals(42, index.count(50, 900))
    }

    @Test fun `index zero replacement rebuilds the compact window`() {
        val initial = listOf(row(0, 100, false), row(1, 90, false))
        val refreshed = listOf(row(9, 120, false), row(1, 90, false))
        val index = UnreadViewportIndex()
        index.update(2) { initial[it] }
        index.update(2) { refreshed[it] }
        assertEquals(1, index.count(1, 100))
    }

    @Test fun `empty Paging emission clears without probing index zero then accepts a new window`() {
        val index = UnreadViewportIndex()
        index.update(1) { row(0, 120, false) }
        index.update(0) { error("empty Paging emission must not call peek") }
        assertEquals(0, index.count(1, 0))

        index.update(2) { position -> row(position, 200L - position, false) }
        assertEquals(1, index.count(1, 100))
    }

    @Test fun `badge cap avoids indexing the entire loaded history`() {
        val rows = (0 until 2_000).map { row(it, (10_000 - it).toLong(), false) }
        var reads = 0
        val index = UnreadViewportIndex()
        index.update(rows.size, maxNonSelf = 100) { position -> reads++; rows[position] }

        assertEquals(100, index.count(firstVisibleIndex = rows.size, marker = 0))
        assertEquals(true, reads <= 101) // index-zero refresh probe + the capped prefix
    }

    @Test fun `read marker stops indexing once rows are already read`() {
        val rows = (0 until 2_000).map { row(it, (10_000 - it).toLong(), false) }
        var reads = 0
        val index = UnreadViewportIndex()
        index.update(
            itemCount = rows.size,
            maxNonSelf = 100,
            stopAtOrBefore = 9_990,
        ) { position -> reads++; rows[position] }

        assertEquals(10, index.count(firstVisibleIndex = rows.size, marker = 9_990))
        assertEquals(true, reads <= 12)
    }

    private fun row(index: Int, time: Long, isSelf: Boolean) =
        io.github.trevarj.motd.data.db.MessageEntity(
            id = index.toLong(), bufferId = 1, msgid = "m$index", serverTime = time,
            sender = "nick", kind = io.github.trevarj.motd.data.db.MessageKind.PRIVMSG,
            text = "text", dedupKey = "m$index", isSelf = isSelf,
        )
}
