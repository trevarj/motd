package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.TimelineAnchor
import kotlinx.coroutines.CancellationException

/**
 * The chat-list "mark everything read" behavior, factored out of `ChatListViewModel` so the gesture
 * orb can run the same thing without a screen.
 *
 * Deliberately a pure selector plus one small suspend routine rather than a service object: the
 * decision of *which* rows participate is the interesting part, and it stays testable on plain data.
 */

internal fun isUnreadChat(row: ChatListRow): Boolean = !row.muted && row.type != BufferType.SERVER && row.unreadCount > 0

/** Rows a read sweep or an unread jump may land on, in chat-list order. */
internal fun unreadChatRows(rows: List<ChatListRow>): List<ChatListRow> = rows.filter(::isUnreadChat)

/** Pure selection seam: muted/SERVER/zero-unread rows never participate in mark-all. */
internal fun unreadBufferIds(rows: List<ChatListRow>): List<Long> = unreadChatRows(rows).map(ChatListRow::bufferId).distinct()

/**
 * Advance the read anchor of every buffer in [bufferIds] through the single mark-read entry point,
 * returning how many actually moved.
 *
 * One Room snapshot decides the boundaries, so a message arriving mid-sweep is not silently marked
 * read. A buffer with no incoming row yet has no authoritative boundary to send and is skipped, and
 * a write that fails takes only its own buffer with it.
 */
internal suspend fun markChatsRead(
    bufferIds: List<Long>,
    readMarkers: ReadMarkerSnapshotter,
    connections: ConnectionManager,
): Int {
    if (bufferIds.isEmpty()) return 0
    var marked = 0
    readMarkers.latestIncoming(bufferIds).forEach { marker ->
        val timestamp = marker.timestamp ?: return@forEach
        val eventId = marker.eventId ?: return@forEach
        try {
            connections.markRead(marker.bufferId, TimelineAnchor(timestamp, eventId))
            marked++
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // One unreachable network must not abandon the rest of the sweep.
        }
    }
    return marked
}
