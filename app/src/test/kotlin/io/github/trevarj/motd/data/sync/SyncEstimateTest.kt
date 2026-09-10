package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.data.db.RoomSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncEstimateTest {
    private val day = 24L * 60 * 60 * 1_000

    @Test
    fun extrapolatesEachRoomsLocalRateOverTheUncoveredWindow() {
        val now = 100 * day
        val spans =
            listOf(
                // 1000 rows over the last 10 days: 100/day; a 30-day window adds the 20 uncovered days.
                RoomSpan(roomId = 1, rowCount = 1_000, oldest = now - 10 * day, newest = now),
                // Already reaches past the floor: nothing to add.
                RoomSpan(roomId = 2, rowCount = 500, oldest = now - 40 * day, newest = now),
                // Too little to say anything.
                RoomSpan(roomId = 3, rowCount = 1, oldest = now - day, newest = now - day),
                RoomSpan(roomId = 4, rowCount = 0, oldest = null, newest = null),
            )
        assertEquals(2_000L, estimateWindowFetch(spans, now - 30 * day))
    }

    @Test
    fun aBurstIsRateLimitedToADayAndEverythingHasNoEstimate() {
        val now = 100 * day
        // 600 rows in one minute would extrapolate to millions; the day floor makes it 600/day.
        val burst = listOf(RoomSpan(roomId = 1, rowCount = 600, oldest = now - 60_000, newest = now))
        assertEquals(600L * 3, estimateWindowFetch(burst, now - 3 * day - 60_000))
        assertNull(estimateWindowFetch(burst, null))
    }
}
