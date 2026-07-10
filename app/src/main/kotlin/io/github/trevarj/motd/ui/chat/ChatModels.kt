package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.normalizeNick
import io.github.trevarj.motd.ui.components.ReactionChip

// --- timeline message filtering (plans/13 §2.4/§2.5) ---

/** JOIN/PART/QUIT kinds hidden when `showJoinPartQuit == false`. */
val JPQ_KINDS: Set<MessageKind> = setOf(MessageKind.JOIN, MessageKind.PART, MessageKind.QUIT)

/**
 * Behavioral filter spec fed into [keepMessage]. Derived from the observed Settings in
 * [ChatViewModel]; passed to `PagingData.filter` so grouping/day-separator/read-marker math only
 * sees visible rows.
 */
data class MessageFilterSpec(
    val showJoinPartQuit: Boolean = true,
    val fools: Set<String> = emptySet(),
    val foolsMode: FoolsMode = FoolsMode.COLLAPSE,
)

/** True when [sender] is a fool (never for own messages). Normalized, case-insensitive compare. */
fun isFoolSender(sender: String, isSelf: Boolean, fools: Set<String>): Boolean =
    !isSelf && normalizeNick(sender) in fools

/**
 * `PagingData.filter` predicate: drops JPQ rows when hidden, and drops fool rows only in HIDE mode.
 * System-event kinds are never fool-treated (JPQ visibility governs those). COLLAPSE keeps the row
 * so it can render as a tap-to-expand placeholder in the timeline.
 */
fun keepMessage(msg: MessageEntity, spec: MessageFilterSpec): Boolean =
    !(msg.kind in JPQ_KINDS && !spec.showJoinPartQuit) &&
        !(
            spec.foolsMode == FoolsMode.HIDE && !isSystemKind(msg.kind) &&
                isFoolSender(msg.sender, msg.isSelf, spec.fools)
            )

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
class UnreadViewportIndex private constructor(
    private val rowIndices: IntArray,
    private val serverTimes: LongArray,
) {
    fun count(firstVisibleIndex: Int, marker: Long): Int {
        val inViewport = lowerBound(rowIndices, firstVisibleIndex)
        val newerThanMarker = upperBound(serverTimes, marker)
        return minOf(inViewport, newerThanMarker)
    }

    private fun lowerBound(values: IntArray, target: Int): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (values[middle] < target) low = middle + 1 else high = middle
        }
        return low
    }

    // Descending values: number strictly greater than target.
    private fun upperBound(values: LongArray, target: Long): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (values[middle] > target) low = middle + 1 else high = middle
        }
        return low
    }

    companion object {
        fun from(rows: List<UnreadViewportRow>): UnreadViewportIndex {
            val others = rows.filterNot { it.isSelf }
            return UnreadViewportIndex(
                rowIndices = IntArray(others.size) { others[it].index },
                serverTimes = LongArray(others.size) { others[it].serverTime },
            )
        }
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
