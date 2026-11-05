package io.github.trevarj.motd.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieDynamicProperties
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.model.KeyPath
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.LocalLottieMotionEnabled
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.lottieFillColor
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

/**
 * The ghost-row entrance, in the asset's own timebase.
 *
 * The three skeleton rows are the [R.raw.ghost_rows] asset (350ms rise+fade each, staggered by
 * 100ms). The caption is ordinary Compose text and animates here rather than in the asset: it is
 * localized, wraps, and carries an optional button, none of which belong in a bodymovin layer.
 */
internal object EmptyStateGhostRows {
    /**
     * One frame past the last keyframe (the third row settles at 33).
     *
     * Lottie parks `endFrame` a hundredth short of `op`, so an asset whose final keyframe lands on
     * `op` can never actually draw its settled state -- the animations-off snap would sit at 99.9%
     * opacity forever. The extra frame is what makes "settled" reachable.
     */
    const val TotalFrames = 34

    /**
     * The frame at which the caption is due -- 500ms at the asset's 60fps timebase, which is where
     * the mock put it.
     *
     * Expressed as a fraction of the asset rather than as a `delay`, and that is the point: Lottie
     * and Compose both honour the platform animator duration scale, a coroutine `delay` does not.
     * At 4x the rows would take 2.2s and a wall-clock caption would arrive first, in the wrong order.
     */
    const val CaptionFrame = 30
    val CaptionRevealProgress: Float = CaptionFrame.toFloat() / TotalFrames

    const val CaptionFadeMs = 350

    /** Rows lifted straight from the composition's own aspect, so nothing is stretched. */
    const val AspectRatio = 240f / 136f

    /** The rows are a fixed-size illustration, never stretched across a tablet's width. */
    val MaxWidth = 240.dp
}

/**
 * Centered empty-state placeholder: icon, title, message, and an optional call-to-action.
 *
 * @param icon the leading glyph. Ignored when [ghostRows] is true, which replaces it with the
 *   skeleton-row illustration; every call site still passes one so turning [ghostRows] off is a
 *   one-word change.
 * @param ghostRows draws three skeleton chat rows rising in, once, instead of [icon], and holds the
 *   caption back until they have risen. For surfaces that are an empty *list*; prompt and error
 *   states keep the icon.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    ghostRows: Boolean = false,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val captionDue: Boolean
        if (ghostRows) {
            val playback = rememberGhostRowsPlayback()
            GhostRows(playback)
            captionDue = playback.captionDue
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            captionDue = true
        }
        // The caption rises as one block so the title, message and action share a single beat
        // instead of racing each other. Without ghost rows it is simply already settled.
        CaptionReveal(revealed = captionDue) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    // Not tappable while it is still invisible. The block stays composed for the
                    // whole reveal so layout never shifts (and so TalkBack can still read the
                    // title, which is the content a screen-reader user came for); only the one
                    // interactive affordance in it is gated. It enables the instant the fade
                    // starts, so a disabled tint is never actually on screen.
                    enabled = captionDue,
                    modifier = Modifier.padding(top = 24.dp),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/**
 * Fades and lifts [content] once [revealed] turns true.
 *
 * Layout is never animated: the block occupies its final size from the first frame and only its
 * alpha and paint offset move, so an empty state inside a list never reflows as the caption lands.
 */
@Composable
private fun CaptionReveal(
    revealed: Boolean,
    content: @Composable () -> Unit,
) {
    val reveal by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = EmptyStateGhostRows.CaptionFadeMs),
        label = "empty_state_caption",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .alpha(reveal)
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        // Paint offset only: the reported height is the settled one.
                        placeable.placeRelative(0, ((1f - reveal) * 12.dp.toPx()).toInt())
                    }
                },
        content = { content() },
    )
}

/** The ghost rows' composition and clock, resolved once and shared by the rows and the caption. */
@Stable
private class GhostRowsPlayback(
    val composition: LottieComposition?,
    /** Read in the draw phase, so a rising row never recomposes the empty state. */
    val progress: () -> Float,
    private val captionCue: State<Boolean>,
) {
    /** Whether the caption has earned its entrance; flips exactly once, never per frame. */
    val captionDue: Boolean get() = captionCue.value
}

/**
 * Loads the ghost-row asset and starts its one-shot.
 *
 * The caption's cue comes off this clock rather than a `delay`, so the two beats stay ordered at
 * any animator duration scale, and is `derivedStateOf` so reading it costs one recomposition rather
 * than one per rendered frame.
 */
@Composable
private fun rememberGhostRowsPlayback(): GhostRowsPlayback {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.ghost_rows))
    val motionEnabled = LocalLottieMotionEnabled.current
    val animation =
        animateLottieCompositionAsState(
            composition = composition,
            isPlaying = motionEnabled,
            iterations = 1,
        )
    val captionCue =
        remember(animation, motionEnabled) {
            derivedStateOf {
                !motionEnabled || animation.value >= EmptyStateGhostRows.CaptionRevealProgress
            }
        }
    return remember(composition, animation, motionEnabled, captionCue) {
        GhostRowsPlayback(
            composition = composition,
            // Animations off parks on the settled frame: three rows, up and opaque, no entrance.
            progress = { if (motionEnabled) animation.value else 1f },
            captionCue = captionCue,
        )
    }
}

/**
 * Three skeleton chat rows rising in, once, when the empty container first appears.
 *
 * The asset ships placeholder-grey fills and takes the theme's own placeholder tone at runtime --
 * `surfaceContainerHighest`, the same surface the app already uses for unowned reaction chips and
 * the connection banner. Fills need [lottieFillColor]: the stroke helper resolves to nothing here.
 */
@Composable
private fun GhostRows(playback: GhostRowsPlayback) {
    val skeletonColor = MaterialTheme.colorScheme.surfaceContainerHighest.toArgb()
    val dynamicProperties =
        remember(skeletonColor) {
            // Built directly rather than through rememberLottieDynamicProperty, which keys on the
            // vararg keypath array's identity and so re-resolves every keypath on every pass.
            LottieDynamicProperties(
                listOf(
                    lottieFillColor(skeletonColor, KeyPath("ghost_row_1", "**")),
                    lottieFillColor(skeletonColor, KeyPath("ghost_row_2", "**")),
                    lottieFillColor(skeletonColor, KeyPath("ghost_row_3", "**")),
                ),
            )
        }
    LottieAnimation(
        composition = playback.composition,
        progress = playback.progress,
        dynamicProperties = dynamicProperties,
        contentScale = ContentScale.Fit,
        modifier =
            Modifier
                // widthIn first: it has to narrow the incoming constraint before fillMaxWidth expands
                // to it. The other order pins the width to the parent and the cap never applies, which
                // stretches a 240dp illustration across a tablet.
                .widthIn(max = EmptyStateGhostRows.MaxWidth)
                .fillMaxWidth()
                .aspectRatio(EmptyStateGhostRows.AspectRatio)
                .testTag("empty_state_ghost_rows"),
    )
}

@Preview
@Composable
private fun EmptyStatePreview() {
    MotdTheme {
        EmptyState(
            icon = Icons.Outlined.Forum,
            title = "No conversations yet",
            message = "Connect to a network to start chatting.",
            actionLabel = "Get started",
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun EmptyStateGhostRowsPreview() {
    MotdTheme {
        EmptyState(
            icon = Icons.Outlined.Forum,
            title = "No conversations yet",
            message = "Connect to a network to start chatting.",
            ghostRows = true,
        )
    }
}
