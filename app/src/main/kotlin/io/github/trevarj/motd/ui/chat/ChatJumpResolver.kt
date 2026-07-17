package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.repo.MessageRepository

/**
 * Resolves a search/deep-jump target to a 0-based reverse-list index (plans/11 §C).
 *
 * The list is reverse-laid-out (index 0 == newest), matching the paging source
 * `ORDER BY serverTime DESC, id DESC`. [MessageRepository.countNewerThan] returns the strict
 * complement count, i.e. how many rows are newer than a given `(serverTime, id)` — which is
 * exactly that row's index.
 *
 * [fetchAround] wraps a CHATHISTORY AROUND fetch: `(bufferName, timeMs, limit) -> inserted?`.
 * Only used when a msgid target is not yet local; the caller supplies the network/cap/timeout
 * wrapper. For search-originated jumps the row is always local (FTS found it); this path serves
 * robustness and future entry points.
 */
class ChatJumpResolver(
    private val messages: MessageRepository,
    private val countNewer: suspend (bufferId: Long, serverTime: Long, id: Long) -> Int =
        { bufferId, serverTime, id -> messages.countNewerThan(bufferId, serverTime, id) },
    private val fetchAround: suspend (bufferName: String, timeMs: Long, limit: Int) -> Boolean,
) {
    sealed interface Result {
        data class Target(val index: Int, val highlightMsgid: String?) : Result
        data object NotFound : Result
    }

    /**
     * @param bufferId    target buffer row id
     * @param msgid       exact message id to land on and highlight; null → time approximation
     * @param timeMs      epoch-ms of the target (used for AROUND fetch and null-msgid approx)
     * @param bufferName  IRC target name (channel/nick) for the AROUND fetch; null disables it
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
                return Result.Target(
                    countNewer(bufferId, row.serverTime, row.id),
                    highlightMsgid = row.msgid,
                )
            }
        }
        if (msgid == null) {
            // No exact target: approximate by time. Long.MAX_VALUE id makes the count include
            // every row at the same serverTime, landing at (or just above) the time boundary.
            if (timeMs <= 0) return Result.NotFound
            val index = countNewer(bufferId, timeMs, Long.MAX_VALUE)
            return Result.Target(index, highlightMsgid = null)
        }

        // 1. Local hit → its index is the count of strictly-newer rows.
        messages.byMsgid(bufferId, msgid)?.let { row ->
            return Result.Target(countNewer(bufferId, row.serverTime, row.id), msgid)
        }

        // 2. Miss + we have a time + a name → fetch AROUND, then retry the local lookup once.
        if (timeMs > 0 && bufferName != null && fetchAround(bufferName, timeMs, 100)) {
            messages.byMsgid(bufferId, msgid)?.let { row ->
                return Result.Target(countNewer(bufferId, row.serverTime, row.id), msgid)
            }
        }

        return Result.NotFound
    }
}

/**
 * A deep jump may settle (and therefore open the mark-read gate) only after the row at its
 * resolved index is still the exact msgid requested. A time-only jump has no identity to check.
 */
internal fun deepJumpTargetMatches(expectedMsgid: String?, actualMsgid: String?): Boolean =
    expectedMsgid == null || expectedMsgid == actualMsgid
