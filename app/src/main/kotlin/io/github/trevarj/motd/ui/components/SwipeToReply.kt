package io.github.trevarj.motd.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal const val SWIPE_REPLY_RESISTANCE = 0.65f
internal const val SWIPE_REPLY_THRESHOLD_DP = 56f
internal const val SWIPE_REPLY_MAX_OFFSET_DP = 80f

/** Pure geometry used by the pointer handler and focused gesture tests. */
internal fun swipeReplyVisualOffset(
    rawDeltaPx: Float,
    direction: Float,
    maxOffsetPx: Float,
): Float = (rawDeltaPx * direction).coerceAtLeast(0f)
    .times(SWIPE_REPLY_RESISTANCE)
    .coerceAtMost(maxOffsetPx)

internal fun swipeReplyArmed(visualOffsetPx: Float, thresholdPx: Float): Boolean =
    visualOffsetPx >= thresholdPx

internal fun shouldCommitSwipeReply(completed: Boolean, visualOffsetPx: Float, thresholdPx: Float): Boolean =
    completed && swipeReplyArmed(visualOffsetPx, thresholdPx)

internal fun shouldHapticSwipeReply(alreadySent: Boolean, visualOffsetPx: Float, thresholdPx: Float): Boolean =
    !alreadySent && swipeReplyArmed(visualOffsetPx, thresholdPx)

internal fun isReplySystemEdge(
    downX: Float,
    widthPx: Float,
    layoutDirection: LayoutDirection,
    leftInsetPx: Float,
    rightInsetPx: Float,
): Boolean = when (layoutDirection) {
    LayoutDirection.Ltr -> downX < leftInsetPx
    LayoutDirection.Rtl -> downX > widthPx - rightInsetPx
}

/**
 * Shared row wrapper for a Telegram-style reply drag. It observes without consuming until a
 * reply-direction horizontal drag wins touch slop, so vertical LazyColumn scrolling, links,
 * selection, and long-press remain owned by their existing handlers.
 */
@Composable
internal fun SwipeToReplyContainer(
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable (Modifier) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val direction = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val currentOnReply by rememberUpdatedState(onReply)
    var offsetPx by remember { mutableFloatStateOf(0f) }
    val thresholdPx = with(density) { SWIPE_REPLY_THRESHOLD_DP.dp.toPx() }
    val maxOffsetPx = with(density) { SWIPE_REPLY_MAX_OFFSET_DP.dp.toPx() }
    val systemGestures = WindowInsets.systemGestures
    val leftGestureInset = systemGestures.getLeft(density, layoutDirection).toFloat()
    val rightGestureInset = systemGestures.getRight(density, layoutDirection).toFloat()
    val replyLabel = stringResource(R.string.chat_action_reply)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(
                enabled,
                layoutDirection,
                leftGestureInset,
                rightGestureInset,
                thresholdPx,
                maxOffsetPx,
            ) {
                if (!enabled) return@pointerInput
                coroutineScope {
                    val animationScope = this
                    var snapBackJob: Job? = null
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (isReplySystemEdge(
                                down.position.x,
                                size.width.toFloat(),
                                layoutDirection,
                                leftGestureInset,
                                rightGestureInset,
                            )
                        ) {
                            return@awaitEachGesture
                        }

                        var rawDelta = 0f
                        var hapticSent = false
                        var accepted = false
                        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                            if (overSlop * direction > 0f) {
                                change.consume()
                                rawDelta = overSlop
                                accepted = true
                            }
                        }
                        if (drag == null || !accepted) return@awaitEachGesture

                        fun updateOffset() {
                            snapBackJob?.cancel()
                            val visual = swipeReplyVisualOffset(rawDelta, direction, maxOffsetPx)
                            offsetPx = direction * visual
                            if (shouldHapticSwipeReply(hapticSent, visual, thresholdPx)) {
                                hapticSent = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }

                        updateOffset()
                        val completed = horizontalDrag(drag.id) { change ->
                            rawDelta += change.positionChange().x
                            change.consume()
                            updateOffset()
                        }
                        if (shouldCommitSwipeReply(completed, abs(offsetPx), thresholdPx)) currentOnReply()
                        val releasedOffset = offsetPx
                        snapBackJob = animationScope.launch {
                            animate(releasedOffset, 0f, animationSpec = spring()) { value, _ -> offsetPx = value }
                        }
                    }
                }
            },
    ) {
        val progress = (abs(offsetPx) / thresholdPx).coerceIn(0f, 1f)
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Reply,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(if (layoutDirection == LayoutDirection.Ltr) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 16.dp)
                .graphicsLayer {
                    alpha = progress
                    scaleX = 0.75f + 0.25f * progress
                    scaleY = 0.75f + 0.25f * progress
                },
        )
        Box(modifier = Modifier.offset { IntOffset(offsetPx.roundToInt(), 0) }) {
            content(
                modifier.semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(replyLabel) {
                            currentOnReply()
                            true
                        },
                    )
                },
            )
        }
    }
}
