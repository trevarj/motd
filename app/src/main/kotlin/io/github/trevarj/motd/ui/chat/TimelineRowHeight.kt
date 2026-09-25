package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

/*
 * How tall a not-yet-loaded timeline row should pretend to be.
 *
 * Every landed history page invalidates Room, which regenerates the PagingSource and collapses the
 * loaded window back to `initialLoadSize`. Rows that fall out of that window present as `null` and
 * render `MessagePlaceholderRow`. A skeleton that is not the size of the row it stands in for makes
 * the whole list below it jump on every skeleton -> real swap, which is what the reader sees as
 * flicker. The skeleton cannot be the EXACT size of the absent row — nothing at all is known about a
 * placeholder slot, `LazyPagingItems.peek` returns null there by construction, and the seam/day/
 * read-marker dividers a row may also carry are decided from data that is equally absent — so the
 * best available answer is the size of the rows the timeline is actually rendering right now.
 *
 * A smarter constant is not available either: the render style is a user setting. A COMPACT
 * single-line IRC row is ~22dp (one text line plus 2 x `compactRowVPad`), a COMFORTABLE bubble with
 * a sender header and an inter-group gap is several times that, and a bubble carrying a link preview
 * is several times *that* again. One number cannot be honest across all three, so this samples.
 *
 * Sampling rules, and why:
 * - Only real message rows count. A row's LazyColumn key is its `MessageEntity.id`, a Long; Paging's
 *   placeholder key and the footer's "append-state" are not, so `isTimelineRowKey` is an exact
 *   discriminator that does not depend on Paging internals.
 * - Suppressed run members measure 1dp to bound LazyColumn's subcomposition, but are not message
 *   rows. Ignore samples below the minimum real-row height when estimating skeletons.
 * - The statistic is the MEDIAN, not the mean. One image or link-preview row is worth ten ordinary
 *   lines, and a mean would let a single tall row inflate every skeleton on screen.
 *
 * What this does NOT do: it does not make a swap free. Rows genuinely vary from ~22dp to 200dp+, so
 * each individual swap still moves the rows below it by its own residual. What it removes is the
 * SYSTEMATIC error — a fixed 48dp bar that was wrong in the same direction for every row in the
 * conversation — leaving a residual that is roughly zero-mean and cancels across a page.
 */

/** Floor for the estimate. Below this a sample is degenerate rather than a genuinely short row. */
internal val MIN_TIMELINE_ROW_HEIGHT: Dp = 20.dp

/**
 * Ceiling for the estimate. A median this tall is possible (a run of link previews), but reserving
 * more than this would trade the current always-too-short error for an always-too-tall one, and an
 * over-reserved skeleton is the more jarring of the two: it collapses when the row arrives.
 */
internal val MAX_TIMELINE_ROW_HEIGHT: Dp = 160.dp

/** Height used until the timeline has measured a real row. Unchanged from the pre-estimate skeleton. */
internal val DEFAULT_TIMELINE_ROW_HEIGHT: Dp = 48.dp

/**
 * Lattice spacing and dead band for the estimate.
 *
 * The estimate is snapped to a multiple of this and only adopted once the sample has moved a whole
 * step away from the standing value. Without a dead band the median would wobble by a pixel or two
 * as rows scroll through the viewport and resize every on-screen skeleton on every measure pass. A
 * persistent one-step flip is still possible in principle and is bounded at 8dp, an order of
 * magnitude below the 48dp-versus-actual error being removed.
 */
internal val TIMELINE_ROW_HEIGHT_STEP: Dp = 8.dp

/** Sentinel for "no real row has been measured yet", distinct from any legal height. */
internal const val UNSAMPLED_ROW_HEIGHT_PX: Int = -1

internal data class TimelineRowHeightBounds(
    val minPx: Int,
    val maxPx: Int,
    val stepPx: Int,
)

/**
 * True for the LazyColumn key of a real message row.
 *
 * `MessageList` keys its rows with `items.itemKey { it.id }`, so a materialized row's key is the
 * entity id. Paging substitutes its own placeholder key for a null item and the append footer uses a
 * String, and neither is a Long. Changing the row key would silently empty the sample set, which is
 * why the key contract is asserted rather than inferred.
 */
internal fun isTimelineRowKey(key: Any?): Boolean = key is Long

/** Median of [heightsPx], or null when there is nothing to sample. Does not mutate its argument. */
internal fun medianTimelineRowHeightPx(heightsPx: List<Int>): Int? {
    if (heightsPx.isEmpty()) return null
    val sorted = heightsPx.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        // Even sample: the lower-middle pair averaged, rounded down. Rounding direction is
        // immaterial once the value is snapped to the lattice below.
        (sorted[middle - 1] + sorted[middle]) / 2
    }
}

/**
 * The estimate to stand on, given the current one and a fresh [sampledPx].
 *
 * The first sample is adopted outright so a cold timeline stops using the fallback as soon as it has
 * measured anything. After that the estimate only moves once the sample has left a whole step's band
 * around the standing value, and what is adopted is the lattice point rather than the raw median.
 *
 * The band is measured against the RAW sample, not against the snapped one. Snapping alone is not
 * hysteresis: a median hovering either side of a lattice boundary would flip the estimate by a full
 * step on every measure pass, which is precisely the churn this is meant to avoid.
 */
internal fun nextTimelineRowHeightPx(
    currentPx: Int,
    sampledPx: Int?,
    bounds: TimelineRowHeightBounds,
): Int {
    val sampled = sampledPx ?: return currentPx
    val clamped = sampled.coerceIn(bounds.minPx, bounds.maxPx)
    val step = bounds.stepPx.coerceAtLeast(1)
    if (currentPx != UNSAMPLED_ROW_HEIGHT_PX && abs(clamped - currentPx) < step) return currentPx
    // Round to the nearest lattice point, then re-clamp: rounding can step outside the bounds.
    return (((clamped + step / 2) / step) * step).coerceIn(bounds.minPx, bounds.maxPx)
}

/** Suppressed 1dp run members have real keys but cannot represent skeleton height. */
internal fun isTimelineRowHeightSample(
    key: Any?,
    sizePx: Int,
    minHeightPx: Int,
): Boolean = isTimelineRowKey(key) && sizePx >= minHeightPx

/**
 * A deferred read of the current row-height estimate for [listState].
 *
 * Returns a lambda rather than a `Dp` on purpose: the state read then happens inside the skeleton's
 * own composition, so adopting a new estimate invalidates the handful of composed placeholders
 * instead of recomposing the entire timeline.
 *
 * The sampler deliberately stops observing `layoutInfo` while a scroll is in progress, matching the
 * other viewport observers in `ChatScreen`. Row heights do not change character between frames, so
 * the estimate taken at the last resting layout is the same one a fling would compute, and keeping
 * this off the fling path costs nothing. The case that matters — an auto-APPEND cascade repainting
 * the list under a stationary reader — is measured at rest.
 */
@Composable
internal fun rememberTimelineRowHeight(
    listState: LazyListState,
    bufferId: Long?,
): () -> Dp {
    val density = LocalDensity.current
    val bounds = remember(density) { timelineRowHeightBounds(density) }
    // Reset per conversation: a channel of one-line joins and a DM full of link previews have
    // genuinely different typical rows, and carrying one into the other is worse than the fallback.
    // Paging generation swaps do NOT reset it — that is the whole point, since a regeneration is
    // exactly when the skeletons appear.
    val estimatePx = remember(bufferId, bounds) { mutableIntStateOf(UNSAMPLED_ROW_HEIGHT_PX) }
    LaunchedEffect(listState, bounds, estimatePx) {
        snapshotFlow {
            if (listState.isScrollInProgress) return@snapshotFlow null
            val visible = listState.layoutInfo.visibleItemsInfo
            val heights = ArrayList<Int>(visible.size)
            for (info in visible) {
                if (isTimelineRowHeightSample(info.key, info.size, bounds.minPx)) heights.add(info.size)
            }
            nextTimelineRowHeightPx(
                currentPx = estimatePx.intValue,
                sampledPx = medianTimelineRowHeightPx(heights),
                bounds = bounds,
            )
        }.distinctUntilChanged()
            .collect { next -> if (next != null) estimatePx.intValue = next }
    }
    return remember(estimatePx, density) {
        {
            val px = estimatePx.intValue
            if (px == UNSAMPLED_ROW_HEIGHT_PX) {
                DEFAULT_TIMELINE_ROW_HEIGHT
            } else {
                with(density) { px.toDp() }
            }
        }
    }
}

internal fun timelineRowHeightBounds(density: Density): TimelineRowHeightBounds =
    with(density) {
        TimelineRowHeightBounds(
            minPx = MIN_TIMELINE_ROW_HEIGHT.roundToPx(),
            maxPx = MAX_TIMELINE_ROW_HEIGHT.roundToPx(),
            stepPx = TIMELINE_ROW_HEIGHT_STEP.roundToPx(),
        )
    }
