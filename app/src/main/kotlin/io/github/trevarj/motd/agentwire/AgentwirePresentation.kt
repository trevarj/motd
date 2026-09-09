package io.github.trevarj.motd.agentwire

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import io.github.trevarj.motd.ui.chat.AUTOSCROLL_BOTTOM_TOLERANCE_PX
import java.util.Calendar

/**
 * Pure presentation logic for the agentwire timeline: display-row grouping, follow/auto-scroll
 * intent, head+tail truncation, and timestamps. Nothing here touches composition state, so every
 * decision stays unit-testable without a Compose host.
 */

/** Fold consecutive settled tools only when the run is at least this long. */
internal const val MIN_TOOL_RUN = 2

internal val AGENTWIRE_TOOL_CATEGORIES = listOf("commands", "edits", "reads", "web", "agents", "other")

internal fun agentwireToolCategory(kind: String?): String =
    when (kind) {
        "shell" -> "commands"
        "file edit" -> "edits"
        "file read" -> "reads"
        "web", "web search" -> "web"
        "agent" -> "agents"
        else -> "other"
    }

internal data class AgentwireActivity(
    val total: Int,
    val failed: Int,
    val categories: Map<String, Int>,
)

internal fun AgentwireActivity.summary(): String =
    "Observed activity: $total ${if (total == 1) "tool" else "tools"}" +
        if (failed > 0) " · $failed failed" else ""

internal fun AgentwireActivity.categorySummary(): String =
    AGENTWIRE_TOOL_CATEGORIES
        .filter { (categories[it] ?: 0) > 0 }
        .joinToString(" · ") { "${it.replaceFirstChar(Char::uppercase)} ${categories.getValue(it)}" }

/** Counts only the currently loaded projection, which already merges tool lifecycles. */
internal fun agentwireToolActivity(tools: List<AgentwireTimelineItem>): AgentwireActivity {
    val observed = tools.filter { it.kind.startsWith("tool.") && it.backendItemId != null }.distinctBy { it.timelineKey() }
    val categories = observed.groupingBy { agentwireToolCategory(it.data.string("kind")) }.eachCount()
    return AgentwireActivity(
        total = observed.size,
        failed = observed.count { it.success == false },
        categories = AGENTWIRE_TOOL_CATEGORIES.associateWith { categories[it] ?: 0 },
    )
}

/** Missing turn IDs remain useful in runs, but never create guessed turn boundaries. */
internal fun agentwireTurnActivity(timeline: List<AgentwireTimelineItem>): Map<Pair<String, String>, AgentwireActivity> =
    timeline
        .filter { it.kind.startsWith("tool.") && it.backendItemId != null && !it.sid.isNullOrEmpty() && !it.tid.isNullOrEmpty() }
        .groupBy { requireNotNull(it.sid) to requireNotNull(it.tid) }
        .mapValues { (_, tools) -> agentwireToolActivity(tools) }

internal sealed interface AgentwireDisplayRow {
    /** Stable LazyColumn key, derived from [AgentwireTimelineItem.timelineKey]. */
    val key: String

    /** Any non-tool timeline item, rendered as a full card. */
    data class Card(
        val item: AgentwireTimelineItem,
    ) : AgentwireDisplayRow {
        override val key: String get() = "card:${item.timelineKey()}"
    }

    /** A single tool (including the currently running one), rendered as one compact row. */
    data class Tool(
        val item: AgentwireTimelineItem,
    ) : AgentwireDisplayRow {
        override val key: String get() = "tool:${item.timelineKey()}"
    }

    /** At least [MIN_TOOL_RUN] consecutive settled tools folded behind one summary header. */
    data class ToolRun(
        val tools: List<AgentwireTimelineItem>,
    ) : AgentwireDisplayRow {
        // Keyed on the first tool so the key survives the run growing at its tail (the live case).
        override val key: String get() = "run:${tools.first().timelineKey()}"
        val activity: AgentwireActivity get() = agentwireToolActivity(tools)
        val failedCount: Int get() = activity.failed
    }
}

/**
 * Derive the rendered rows from the raw timeline. `request.opened` is suppressed entirely: the
 * trailing [AgentwireUiState.requests] block is the single interactive surface for pending
 * approvals. It still breaks a run, and `request.resolved` remains inline as the historical record.
 */
internal fun agentwireDisplayRows(timeline: List<AgentwireTimelineItem>): List<AgentwireDisplayRow> {
    val rows = ArrayList<AgentwireDisplayRow>(timeline.size)
    val pending = ArrayList<AgentwireTimelineItem>()

    fun flush() {
        when {
            pending.size >= MIN_TOOL_RUN -> rows.add(AgentwireDisplayRow.ToolRun(pending.toList()))
            pending.size == 1 -> rows.add(AgentwireDisplayRow.Tool(pending.single()))
        }
        pending.clear()
    }
    timeline.forEach { item ->
        when {
            item.kind == "request.opened" -> {
                flush()
            }

            item.kind.startsWith("tool.") && !item.running && item.backendItemId != null -> {
                // Adjacent tools from different explicit sessions/turns never share a run.
                val previous = pending.lastOrNull()
                if (previous != null && (previous.sid != item.sid || previous.tid != item.tid)) flush()
                pending.add(item)
            }

            item.kind.startsWith("tool.") -> {
                // Running tools and events without a call identity stay visible as individual rows.
                flush()
                rows.add(AgentwireDisplayRow.Tool(item))
            }

            else -> {
                flush()
                rows.add(AgentwireDisplayRow.Card(item))
            }
        }
    }
    flush()
    return rows
}

/**
 * Growth fingerprint of the followable content. [contentLength] is what makes a streaming
 * `assistant.delta` (body grows, row count does not) count as growth; sync/error/queue header
 * items are deliberately excluded so they never yank the viewport.
 */
internal data class AgentwireTimelineStamp(
    val rowCount: Int,
    val contentLength: Long,
    /**
     * Timestamp of the newest timeline item. Once the timeline sits at [AGENTWIRE_TIMELINE_CAP],
     * an arrival evicts an older row, so neither the row count nor the total content length is a
     * reliable arrival signal any more; this one still is.
     */
    val newestAt: Long = 0,
)

internal fun agentwireTimelineStamp(
    timeline: List<AgentwireTimelineItem>,
    requestCount: Int,
): AgentwireTimelineStamp =
    AgentwireTimelineStamp(
        rowCount = timeline.size + requestCount,
        contentLength = timeline.sumOf { (it.body?.length ?: 0).toLong() },
        newestAt = timeline.lastOrNull()?.at ?: 0,
    )

/** A new row landed at the tail, as opposed to an existing row's body growing mid-stream. */
internal fun AgentwireTimelineStamp.arrivedSince(prior: AgentwireTimelineStamp): Boolean = rowCount > prior.rowCount || newestAt > prior.newestAt

/** Any followable change: an arrival, or a streaming `assistant.delta` extending the last card. */
internal fun AgentwireTimelineStamp.grewFrom(prior: AgentwireTimelineStamp): Boolean = arrivedSince(prior) || (rowCount == prior.rowCount && contentLength > prior.contentLength)

/**
 * Tracks the user's decision to follow live arrivals independently from the list's transient
 * physical position, mirroring the chat screen's AutoFollowTracker for a top-down non-Paging
 * list. Programmatic scrolls never clear the intent; a user scroll that settles at the bottom
 * re-arms it.
 */
internal class AgentwireAutoFollow(
    initialStamp: AgentwireTimelineStamp,
) {
    var following: Boolean = true
        private set

    private var stamp = initialStamp

    /** The last stamp consumed by [onTimelineChanged]; lets callers compute arrival deltas. */
    val presentedStamp: AgentwireTimelineStamp
        get() = stamp

    /** Consume a session rebind's first snapshot without treating it as a live arrival. */
    fun reset(
        stamp: AgentwireTimelineStamp,
        atBottom: Boolean,
    ) {
        this.stamp = stamp
        following = atBottom
    }

    /** Explicit send/FAB actions opt back into following the newest row. */
    fun requestFollow() {
        following = true
    }

    fun onScrollStateChanged(
        scrolling: Boolean,
        programmatic: Boolean,
        atBottom: Boolean,
    ) {
        if (programmatic) return
        following = if (scrolling) false else atBottom
    }

    /** Record the new stamp and return whether the caller should scroll to the bottom. */
    fun onTimelineChanged(new: AgentwireTimelineStamp): Boolean {
        // An empty-to-populated transition is initial load, not a live arrival.
        val follow = following && stamp.rowCount > 0 && new.grewFrom(stamp)
        stamp = new
        return follow
    }
}

/**
 * "At bottom" for the top-down timeline: the last item is visible and its bottom edge sits within
 * the shared autoscroll tolerance of the viewport end. Relies on the trailing spacer being the
 * final list item.
 */
internal fun isAtTimelineBottom(layoutInfo: LazyListLayoutInfo): Boolean {
    val last = layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
    if (last.index != layoutInfo.totalItemsCount - 1) return false
    return last.offset + last.size <= layoutInfo.viewportEndOffset + AUTOSCROLL_BOTTOM_TOLERANCE_PX
}

internal const val AGENTWIRE_HEAD_LINES = 8
internal const val AGENTWIRE_TAIL_LINES = 8

/** Never hide fewer lines than this: a marker that conceals two lines is worse than the lines. */
internal const val AGENTWIRE_MIN_HIDDEN_LINES = 4

internal data class TruncatedLines(
    val head: List<String>,
    /** 0 means no truncation: everything is in [head] and [tail] is empty. */
    val hiddenCount: Int,
    val tail: List<String>,
)

/** Keep the head and tail of long output; the tail carries a command's verdict. */
internal fun truncateMiddle(
    lines: List<String>,
    head: Int = AGENTWIRE_HEAD_LINES,
    tail: Int = AGENTWIRE_TAIL_LINES,
): TruncatedLines {
    if (lines.size < head + tail + AGENTWIRE_MIN_HIDDEN_LINES) {
        return TruncatedLines(head = lines, hiddenCount = 0, tail = emptyList())
    }
    return TruncatedLines(
        head = lines.take(head),
        hiddenCount = lines.size - head - tail,
        tail = lines.takeLast(tail),
    )
}

/**
 * Fixed clock-time stamp, not a relative one: this screen has no ticking recomposition source, so
 * "5m ago" would silently go stale.
 */
internal fun agentwireTimestamp(
    atMs: Long,
    nowMs: Long = System.currentTimeMillis(),
): String {
    val at = Calendar.getInstance().apply { timeInMillis = atMs }
    val now = Calendar.getInstance().apply { timeInMillis = nowMs }
    val sameDay =
        at.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            at.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val hour = at.get(Calendar.HOUR_OF_DAY)
    val minute = at.get(Calendar.MINUTE)
    val time = "%02d:%02d".format(hour, minute)
    if (sameDay) return time
    return "%02d/%02d $time".format(at.get(Calendar.DAY_OF_MONTH), at.get(Calendar.MONTH) + 1)
}
