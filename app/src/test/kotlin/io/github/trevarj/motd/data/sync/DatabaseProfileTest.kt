package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.PrunableRoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The planner must invert itself: the cap solved for a size target projects back within that target. */
class DatabaseProfileTest {
    // 1 000 bytes per row; 200 fixed rows; one busy channel, one quiet one, one direct-message room.
    private val profile =
        DatabaseProfile(
            liveBytes = 1_000L * 10_200,
            totalRows = 10_200,
            rooms =
                listOf(
                    PrunableRoom(BufferType.CHANNEL, 8_000),
                    PrunableRoom(BufferType.CHANNEL, 100),
                    PrunableRoom(BufferType.QUERY, 1_900),
                ),
        )

    @Test
    fun projectionCapsEachRoomAndKeepsFixedRows() {
        // 200 fixed + min(8000,500) + min(100,500) + min(1900, 500*5)
        assertEquals(1_000L * (200 + 500 + 100 + 1_900), profile.projectedBytes(500))
        assertEquals(1_000L * 10_200, profile.projectedBytes(100_000))
    }

    @Test
    fun capForTargetIsTheLargestThatFits() {
        val target = 1_000L * 3_000
        val cap = profile.channelRowsFor(target)
        assertTrue(profile.projectedBytes(cap) <= target)
        assertTrue(profile.projectedBytes(cap + 1) > target)
        assertEquals(800, cap) // 200 + 800 + 100 + 1900 = 3000
    }

    @Test
    fun targetsOutsideTheRangeClampToTheEnds() {
        assertEquals(8_000, profile.channelRowsFor(Long.MAX_VALUE))
        assertEquals(0, profile.channelRowsFor(0))
        assertEquals(0.0, DatabaseProfile(0, 0, emptyList()).bytesPerRow, 0.0)
        assertEquals(0, DatabaseProfile(0, 0, emptyList()).channelRowsFor(1))
    }
}
