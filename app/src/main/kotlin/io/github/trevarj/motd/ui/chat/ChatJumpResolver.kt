package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.repo.MessageRepository
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec

/**
 * Resolves a search/deep-jump target to a 0-based reverse-list index.
 *
 * The list is reverse-laid-out (index 0 == newest), matching the paging source
 * `ORDER BY serverTime DESC, timelineOrder DESC, id DESC`. [MessageRepository.countNewerThan] returns the strict
 * complement count, i.e. how many rows are newer than a given `(serverTime, id)` — which is
 * exactly that row's index.
 *
 * [fetchAround] is the caller's seam over one CHATHISTORY AROUND page; the request, its
 * msgid→timestamp fallback, wire admission and persistence all belong to
 * [io.github.trevarj.motd.data.sync.HistoryPageLoader]. Only used when a msgid target is not yet
 * local. For search-originated jumps the row is always local (FTS found it); this path serves
 * robustness and future entry points.
 */
class ChatJumpResolver(
    private val messages: MessageRepository,
    private val countNewer: suspend (bufferId: Long, serverTime: Long, id: Long) -> Int =
        { bufferId, serverTime, id ->
            messages.countNewerThan(bufferId, serverTime, id, MessageVisibilitySpec())
        },
    private val fetchAround: suspend (
        bufferName: String,
        msgid: String,
        timeMs: Long,
        limit: Int,
    ) -> Boolean,
) {
    sealed interface Result {
        data class Resolved(
            val target: ChatPositionTarget,
        ) : Result

        data object NotFound : Result
    }

    /**
     * @param bufferId    target buffer row id
     * @param msgid       exact message id to land on and highlight; null → time approximation
     * @param timeMs      epoch-ms of the target (used for AROUND fetch and null-msgid approx)
     * @param bufferName  IRC target name (channel/nick) for the AROUND fetch; null disables it
     * @param eventId     exact local event ID; when found it also drives highlighting for msgid-less rows
     */
    suspend fun resolve(
        bufferId: Long,
        msgid: String?,
        timeMs: Long,
        bufferName: String?,
        eventId: Long? = null,
    ): Result {
        // Canonical local identity is exact even for msgid-less push observations. If a later
        // coalescence replaced this id, retain the wire/time fallbacks below.
        eventId?.let { id ->
            val canonicalRoomId = messages.canonicalRoomId(bufferId)
            messages.byId(id)?.takeIf { it.bufferId == canonicalRoomId }?.let { row ->
                return Result.Resolved(
                    row.toPositionTarget(
                        index = countNewer(bufferId, row.serverTime, row.id),
                        expectedMsgid = msgid ?: row.msgid,
                        highlightMsgid = msgid ?: row.msgid,
                    ),
                )
            }
        }
        if (msgid == null) {
            // No exact target: approximate by time. Long.MAX_VALUE id makes the count include
            // every row at the same serverTime, landing at (or just above) the time boundary.
            if (timeMs <= 0) return Result.NotFound
            val index = countNewer(bufferId, timeMs, Long.MAX_VALUE)
            return Result.Resolved(ChatPositionTarget(index = index, serverTime = timeMs))
        }

        // 1. Local hit → its index is the count of strictly-newer rows.
        messages.byMsgid(bufferId, msgid)?.let { row ->
            return Result.Resolved(
                row.toPositionTarget(
                    index = countNewer(bufferId, row.serverTime, row.id),
                    expectedMsgid = msgid,
                    highlightMsgid = msgid,
                ),
            )
        }

        // 2. Miss + a name → fetch AROUND by exact msgid (or timestamp), then retry once.
        if (bufferName != null && fetchAround(bufferName, msgid, timeMs, 100)) {
            messages.byMsgid(bufferId, msgid)?.let { row ->
                return Result.Resolved(
                    row.toPositionTarget(
                        index = countNewer(bufferId, row.serverTime, row.id),
                        expectedMsgid = msgid,
                        highlightMsgid = msgid,
                    ),
                )
            }
        }

        return Result.NotFound
    }
}

private fun io.github.trevarj.motd.data.db.MessageEntity.toPositionTarget(
    index: Int,
    expectedMsgid: String?,
    highlightMsgid: String?,
): ChatPositionTarget =
    ChatPositionTarget(
        index = index,
        expectedEventId = id,
        expectedMsgid = expectedMsgid,
        serverTime = serverTime,
        highlightMsgid = highlightMsgid,
        highlightEventId = id,
    )
