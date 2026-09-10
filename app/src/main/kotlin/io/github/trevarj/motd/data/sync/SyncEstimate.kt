package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.data.db.RoomSpan

/**
 * How many messages a manual window fetch would roughly add, from what the device already holds:
 * each room's local rate (rows over the span they cover) extrapolated over the part of the window
 * below its oldest row. CHATHISTORY has no count operation, so this is the only number available
 * before the messages are actually sent. Rooms with fewer than two rows say nothing and count as
 * zero; chats the device has never seen are invisible here (discovery finds them). Null for an
 * unbounded window — extrapolating a rate back to epoch means nothing.
 */
fun estimateWindowFetch(
    spans: List<RoomSpan>,
    floorMs: Long?,
): Long? {
    if (floorMs == null) return null
    return spans.sumOf { room ->
        val oldest = room.oldest
        val newest = room.newest
        if (oldest == null || newest == null || room.rowCount < 2 || oldest <= floorMs) return@sumOf 0L
        // A single seeded page spans minutes; extrapolating that burst rate over a month would say
        // millions. Rate it over at least a day: conservative for a busy room, honest for the sample.
        val ratePerMs = room.rowCount.toDouble() / maxOf(newest - oldest, DAY_MS).toDouble()
        (ratePerMs * (oldest - floorMs)).toLong()
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1_000
