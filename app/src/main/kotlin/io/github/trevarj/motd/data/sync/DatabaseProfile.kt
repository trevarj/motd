package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.PrunableRoom
import io.github.trevarj.motd.data.prefs.QUERY_RETENTION_MULTIPLIER

/**
 * What a retention cap would do to the database, measured rather than guessed: [liveBytes] is the
 * file minus its free pages, so [bytesPerRow] already carries every index, the FTS table, and the
 * per-event satellite rows in proportion. Rows outside [rooms] (server buffers, dismissed rooms,
 * networks without server history) are never pruned and count as fixed cost.
 */
data class DatabaseProfile(
    val liveBytes: Long,
    val totalRows: Long,
    val rooms: List<PrunableRoom>,
) {
    val bytesPerRow: Double get() = if (totalRows == 0L) 0.0 else liveBytes.toDouble() / totalRows

    private val fixedRows: Long get() = totalRows - rooms.sumOf { it.rowCount.toLong() }

    /** Rows kept if every prunable room were capped at [channelRows] (direct messages at their multiple). */
    fun keptRows(channelRows: Int): Long =
        fixedRows +
            rooms.sumOf { room ->
                val cap = if (room.type == BufferType.QUERY) channelRows.toLong() * QUERY_RETENTION_MULTIPLIER else channelRows.toLong()
                minOf(room.rowCount.toLong(), cap)
            }

    /** Estimated database size after pruning at [channelRows]. */
    fun projectedBytes(channelRows: Int): Long = (keptRows(channelRows) * bytesPerRow).toLong()

    /**
     * Largest channel cap whose projection fits in [targetBytes]; 0 when even the fixed rows do not.
     * Projection is monotonic in the cap, so this is a binary search over the largest room's size.
     */
    fun channelRowsFor(targetBytes: Long): Int {
        var low = 0
        var high = rooms.maxOfOrNull { it.rowCount } ?: 0
        if (projectedBytes(high) <= targetBytes) return high
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (projectedBytes(mid) <= targetBytes) low = mid else high = mid - 1
        }
        return low
    }
}
