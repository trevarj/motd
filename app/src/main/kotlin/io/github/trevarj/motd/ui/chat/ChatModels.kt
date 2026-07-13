package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.prefs.normalizeNick
import io.github.trevarj.motd.data.visibility.JOIN_PART_QUIT_KINDS
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.ui.components.ReactionChip

// --- timeline message filtering (plans/13 §2.4/§2.5) ---

/** JOIN/PART/QUIT kinds hidden when `showJoinPartQuit == false`. */
val JPQ_KINDS: Set<MessageKind> = JOIN_PART_QUIT_KINDS

/**
 * Behavioral filter spec fed into [keepMessage]. Derived from the observed Settings in
 * [ChatViewModel]; passed to `PagingData.filter` so grouping/day-separator/read-marker math only
 * sees visible rows.
 */
typealias MessageFilterSpec = MessageVisibilitySpec

/** True when [sender] is a fool (never for own messages). Normalized, case-insensitive compare. */
fun isFoolSender(sender: String, isSelf: Boolean, fools: Set<String>): Boolean =
    !isSelf && normalizeNick(sender) in fools

/**
 * `PagingData.filter` predicate: drops JPQ rows when hidden, and drops fool rows only in HIDE mode.
 * System-event kinds are never fool-treated (JPQ visibility governs those). COLLAPSE keeps the row
 * so it can render as a tap-to-expand placeholder in the timeline.
 */
fun keepMessage(msg: MessageEntity, spec: MessageFilterSpec): Boolean =
    MessageVisibilityPolicy(spec).timeline(msg)

/** Grouping window: consecutive same-sender messages within this span share one header. */
const val GROUP_WINDOW_MS: Long = 3 * 60 * 1000

/**
 * Scroll-offset slack (px) within which the reverse list still counts as "at bottom" for autoscroll.
 * Small so a barely-nudged newest row keeps auto-following, but the user is not pinned once they
 * deliberately scroll up. Compose scroll offsets are in raw pixels.
 */
const val AUTOSCROLL_BOTTOM_TOLERANCE_PX: Int = 64

/**
 * Count how many of the below-the-fold [serverTimes] are newer than the frozen read [marker].
 * [serverTimes] are the reverse-list rows scrolled off toward the bottom (indices `0 until
 * firstVisibleIndex`, newest first). Returns the FAB unread badge value: 0 at the bottom, shrinking
 * as the user scrolls down — viewport/read aware rather than a monotonic arrival tally (bug #7).
 */
fun unreadBelowViewport(serverTimes: List<Long>, marker: Long): Int {
    // The reverse list is newest-first. Find the first row that is not strictly newer than the
    // marker instead of walking (and, at the Compose call-site, copying) every below-fold row.
    var low = 0
    var high = serverTimes.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (serverTimes[middle] > marker) low = middle + 1 else high = middle
    }
    return low
}

/** A non-self timeline row captured once per Paging window, not once per scroll frame. */
data class UnreadViewportRow(val index: Int, val serverTime: Long, val isSelf: Boolean)

/**
 * Allocation-free-at-scroll-time unread index. Both arrays are sorted by reverse-list index and
 * server time respectively, so the viewport and marker bounds can each be found in O(log n).
 */
class UnreadViewportIndex {
    private var rowIndices = IntArray(0)
    private var serverTimes = LongArray(0)
    private var size = 0
    private var loadedCount = 0
    private var firstRowId: Long? = null
    private var stoppedAtMarker: Long? = null

    /**
     * Incorporate a Paging window without re-reading its already-indexed prefix. Paging appends
     * older rows at the end during history traversal, so that hot path only visits the new page.
     * A refresh, shrink, or replacement of index zero invalidates positional assumptions and
     * rebuilds the compact non-self index.
     */
    fun update(
        itemCount: Int,
        maxNonSelf: Int = Int.MAX_VALUE,
        stopAtOrBefore: Long? = null,
        include: (MessageEntity) -> Boolean = { !it.isSelf },
        peek: (Int) -> MessageEntity?,
    ) {
        // Paging emits an empty snapshot while invalidating/refreshing. Never probe index zero for
        // that transient state: requesting it can synchronously re-enter Paging on the UI thread.
        if (itemCount == 0) {
            clear()
            return
        }
        val currentFirstId = peek(0)?.id
        val rebuild = itemCount < loadedCount ||
            (loadedCount > 0 && currentFirstId != firstRowId)
        if (rebuild) clear()

        // The FAB renders 99+ at 100, so its caller can cap [maxNonSelf] and avoid rebuilding a
        // multi-thousand-row Paging window when a live message is prepended at index zero. A read
        // marker is an even stronger terminal boundary because server times are descending.
        val markerAlreadyCovered = stopAtOrBefore != null &&
            stoppedAtMarker?.let { stopAtOrBefore >= it } == true
        if (!markerAlreadyCovered && size < maxNonSelf && itemCount > loadedCount) {
            var index = loadedCount
            while (index < itemCount && size < maxNonSelf) {
                val row = peek(index)
                loadedCount = index + 1
                if (row == null) {
                    index++
                    continue
                }
                if (stopAtOrBefore != null && row.serverTime <= stopAtOrBefore) {
                    stoppedAtMarker = stopAtOrBefore
                    break
                }
                if (include(row)) {
                    ensureCapacity(size + 1)
                    rowIndices[size] = index
                    serverTimes[size] = row.serverTime
                    size++
                }
                index++
            }
        }
        firstRowId = currentFirstId
    }

    private fun clear() {
        rowIndices = IntArray(0)
        serverTimes = LongArray(0)
        size = 0
        loadedCount = 0
        firstRowId = null
        stoppedAtMarker = null
    }

    fun count(firstVisibleIndex: Int, marker: Long): Int {
        val inViewport = lowerBound(rowIndices, firstVisibleIndex)
        val newerThanMarker = upperBound(serverTimes, marker)
        return minOf(inViewport, newerThanMarker)
    }

    private fun lowerBound(values: IntArray, target: Int): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (values[middle] < target) low = middle + 1 else high = middle
        }
        return low
    }

    // Descending values: number strictly greater than target.
    private fun upperBound(values: LongArray, target: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (values[middle] > target) low = middle + 1 else high = middle
        }
        return low
    }

    private fun ensureCapacity(required: Int) {
        if (required <= rowIndices.size) return
        val capacity = maxOf(required, rowIndices.size.coerceAtLeast(16) * 2)
        rowIndices = rowIndices.copyOf(capacity)
        serverTimes = serverTimes.copyOf(capacity)
    }

}

/**
 * Decide whether an incoming message should pin the reverse list to the newest row (index 0). Only
 * autoscroll when the user is already at/near the bottom ([atBottom]) AND an already-populated
 * window grew ([newCount] > [oldCount]) — never yank a user who has scrolled up to read history.
 * The first Paging page is deliberately excluded: a reverse list starts at index 0 already, and
 * animating it to index 0 while the enter transition is running adds needless layout work.
 * Own-send scrolls unconditionally at the call site and does not route through this helper.
 */
fun shouldAutoscrollToNewest(atBottom: Boolean, oldCount: Int, newCount: Int): Boolean =
    atBottom && oldCount > 0 && newCount > oldCount

/**
 * Tracks the user's decision to follow live arrivals independently from the reverse list's
 * transient physical position. Paging inserts and programmatic scrolls can both move index zero
 * without representing user intent, so deriving this state directly from the current bottom
 * position is racy.
 */
internal class AutoFollowTracker(initialItemCount: Int) {
    var following: Boolean = true
        private set

    private var itemCount: Int = initialItemCount
    private var newestEffectiveId: Long? = null

    val presentedItemCount: Int
        get() = itemCount

    /** Consume the first post-entry Paging snapshot without treating it as a live arrival. */
    fun reset(itemCount: Int, atBottom: Boolean, newestEffectiveId: Long? = null) {
        this.itemCount = itemCount
        this.newestEffectiveId = newestEffectiveId
        following = atBottom
    }

    /** Explicit send/FAB actions opt back into following the newest row. */
    fun requestFollow() {
        following = true
    }

    /**
     * Update follow intent only for real user scrolling. Programmatic motion and Paging anchor
     * shifts must not disable it. A user scroll that settles back at the bottom opts in again.
     */
    fun onScrollStateChanged(scrolling: Boolean, programmatic: Boolean, atBottom: Boolean) {
        if (programmatic) return
        following = if (scrolling) false else atBottom
    }

    /** Record a new presented count and return whether the viewport should pin to index zero. */
    fun onItemCountChanged(newItemCount: Int): Boolean {
        val shouldFollow = shouldAutoscrollToNewest(following, itemCount, newItemCount)
        itemCount = newItemCount
        return shouldFollow
    }

    /** Ignore Paging growth whose newest meaningful identity did not change (JPQ/fool tails). */
    fun onTimelineChanged(newItemCount: Int, newNewestEffectiveId: Long?): Boolean {
        val shouldFollow = following && itemCount > 0 && newItemCount > itemCount &&
            newNewestEffectiveId != null && newNewestEffectiveId != newestEffectiveId
        itemCount = newItemCount
        newestEffectiveId = newNewestEffectiveId
        return shouldFollow
    }
}

fun newestEffectiveMessageId(
    itemCount: Int,
    peek: (Int) -> MessageEntity?,
    policy: MessageVisibilityPolicy,
): Long? = (0 until itemCount).firstNotNullOfOrNull { index ->
    peek(index)?.takeIf(policy::effectiveBottom)?.id
}

/** Reverse-list bottom with any raw tail ignored by policy treated as already settled. */
fun isAtEffectiveBottom(
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    itemCount: Int,
    peek: (Int) -> MessageEntity?,
    policy: MessageVisibilityPolicy,
): Boolean {
    if (firstVisibleOffset > AUTOSCROLL_BOTTOM_TOLERANCE_PX) return false
    val belowViewport = minOf(firstVisibleIndex, itemCount)
    return (0 until belowViewport).none { index ->
        peek(index)?.let(policy::effectiveBottom) == true
    }
}

/** Prefer an eligible row at or older than the viewport; used to avoid saving fool anchors. */
fun nearestAnchorRow(
    firstVisibleIndex: Int,
    itemCount: Int,
    peek: (Int) -> MessageEntity?,
    policy: MessageVisibilityPolicy,
): Pair<Int, MessageEntity>? {
    for (index in firstVisibleIndex until itemCount) {
        val row = peek(index) ?: continue
        if (policy.anchor(row)) return index to row
    }
    for (index in minOf(firstVisibleIndex - 1, itemCount - 1) downTo 0) {
        val row = peek(index) ?: continue
        if (policy.anchor(row)) return index to row
    }
    return null
}

data class ChatInitialPosition(
    val index: Int,
    val offset: Int = 0,
    val fromSavedPosition: Boolean = false,
)

data class ChatScrollPosition(
    val index: Int,
    val offset: Int,
    val msgid: String?,
    val serverTime: Long,
    val rowId: Long,
)

/**
 * Normal entry scroll: saved viewports and older unread targets need explicit positioning; a fresh
 * newest target only scrolls if the list state was retained off-bottom.
 */
fun shouldScrollToInitialTarget(target: ChatInitialPosition, atBottom: Boolean): Boolean =
    target.fromSavedPosition || target.index > 0 || !atBottom

/**
 * Aggregate raw [ReactionEntity] rows into per-msgid chip lists: one chip per emoji with its count
 * and whether [myNick] is among the reactors. Ordered by first appearance for stability.
 *
 * Own-nick matching uses IRC casefolding ([normalizer]), not plain ASCII case-insensitivity, so
 * the `[]{}|~` equivalence (`nick[]` == `nick{}` under rfc1459) is honored. Callers should pass the
 * live client's isupport normalizer; the default applies rfc1459 folding, which is the safe
 * superset used elsewhere when no live client is reachable.
 */
fun aggregateReactions(
    reactions: List<ReactionEntity>,
    myNick: String?,
    normalizer: (String) -> String = ::foldNickRfc1459,
): Map<String, List<ReactionChip>> {
    val myNormalized = myNick?.let(normalizer)
    // msgid -> emoji -> (count, mine)
    val byMsg = LinkedHashMap<String, LinkedHashMap<String, MutableReactionAgg>>()
    for (r in reactions) {
        val emojiMap = byMsg.getOrPut(r.targetMsgid) { LinkedHashMap() }
        val agg = emojiMap.getOrPut(r.emoji) { MutableReactionAgg() }
        agg.count++
        if (myNormalized != null && normalizer(r.sender) == myNormalized) agg.mine = true
    }
    return byMsg.mapValues { (_, emojiMap) ->
        emojiMap.map { (emoji, agg) -> ReactionChip(emoji, agg.count, agg.mine) }
    }
}

/**
 * Pure rfc1459 nick casefolding fallback (uppercase → lowercase plus the `[]\~` → `{}|^` mapping).
 * Mirrors `Isupport.normalize` for the rfc1459 case; used when no live client normalizer is
 * available so own-nick reaction matching still folds the IRC-equivalence characters.
 */
fun foldNickRfc1459(name: String): String {
    val sb = StringBuilder(name.length)
    for (c in name) {
        sb.append(
            when {
                c in 'A'..'Z' -> c + 32
                c == '[' -> '{'
                c == ']' -> '}'
                c == '\\' -> '|'
                c == '~' -> '^'
                else -> c
            },
        )
    }
    return sb.toString()
}

private class MutableReactionAgg(var count: Int = 0, var mine: Boolean = false)
