package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.repo.MessageRepository
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors

/**
 * Resolves a search/deep-jump target to a 0-based reverse-list index (plans/11 §C).
 *
 * The list is reverse-laid-out (index 0 == newest), matching the paging source
 * `ORDER BY serverTime DESC, id DESC`. [MessageRepository.countNewerThan] returns the strict
 * complement count, i.e. how many rows are newer than a given `(serverTime, id)` — which is
 * exactly that row's index.
 *
 * [fetchAround] wraps a CHATHISTORY AROUND fetch with the exact msgid and timestamp fallback.
 * Only used when a msgid target is not yet local; the caller supplies the network/cap/timeout
 * wrapper. For search-originated jumps the row is always local (FTS found it); this path serves
 * robustness and future entry points.
 */
class ChatJumpResolver(
    private val messages: MessageRepository,
    private val countNewer: suspend (bufferId: Long, serverTime: Long, id: Long) -> Int =
        { bufferId, serverTime, id -> messages.countNewerThan(bufferId, serverTime, id) },
    private val fetchAround: suspend (
        bufferName: String,
        msgid: String,
        timeMs: Long,
        limit: Int,
    ) -> Boolean,
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

        // 2. Miss + a name → fetch AROUND by exact msgid (or timestamp), then retry once.
        if (bufferName != null && fetchAround(bufferName, msgid, timeMs, 100)) {
            messages.byMsgid(bufferId, msgid)?.let { row ->
                return Result.Target(countNewer(bufferId, row.serverTime, row.id), msgid)
            }
        }

        return Result.NotFound
    }
}

/** Fetch and persist one completed AROUND page using only selectors the server advertised. */
internal suspend fun fetchAroundHistoryPage(
    target: String,
    msgid: String,
    timeMs: Long,
    limit: Int,
    availability: HistoryAvailability.Ready,
    requestPage: suspend (ChatHistoryRequest) -> ChatHistoryResponse,
    persistPage: suspend (ChatHistoryRequest, ChatHistoryResponse.Messages) -> Unit,
): Boolean {
    val timestampSelector = timeMs.takeIf {
        it > 0 && HistoryReferenceType.TIMESTAMP in availability.referenceTypes
    }?.let(ChatHistorySelectors::timestamp)
    val msgidSelector = msgid.takeIf {
        it.isNotEmpty() && HistoryReferenceType.MSGID in availability.referenceTypes
    }?.let(ChatHistorySelectors::msgid)
    var request = ChatHistoryRequest(
        subcommand = ChatHistoryRequest.Subcommand.AROUND,
        target = target,
        bound1 = msgidSelector ?: timestampSelector ?: return false,
        limit = minOf(limit, availability.pageLimit).coerceAtLeast(1),
    )
    val response = try {
        requestPage(request)
    } catch (error: IrcCommandException) {
        if (
            msgidSelector == null || request.bound1 != msgidSelector ||
            error.code != INVALID_MSGREFTYPE || timestampSelector == null
        ) {
            throw error
        }
        request = request.copy(bound1 = timestampSelector)
        requestPage(request)
    }
    val page = response as? ChatHistoryResponse.Messages ?: return false
    persistPage(request, page)
    return true
}

/**
 * A deep jump may settle (and therefore open the mark-read gate) only after the row at its
 * resolved index is still the exact msgid requested. A time-only jump has no identity to check.
 */
internal fun deepJumpTargetMatches(expectedMsgid: String?, actualMsgid: String?): Boolean =
    expectedMsgid == null || expectedMsgid == actualMsgid

private const val INVALID_MSGREFTYPE = "INVALID_MSGREFTYPE"
