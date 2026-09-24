package io.github.trevarj.motd.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieDynamicProperties
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.model.KeyPath
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.LocalLottieMotionEnabled
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.lottieFillColor

/** One aggregated reaction, including the first-seen display spelling of each reactor nick. */
data class ReactionChip(
    val emoji: String,
    val count: Int,
    val mine: Boolean,
    val reactorDisplayNames: List<String> = emptyList(),
)

/**
 * How long [R.raw.reaction_burst] runs at an unscaled clock: 24 frames at the shared 60fps timebase.
 *
 * Documentation and a test anchor only. Nothing waits on it: the overlay unmounts when the
 * animation reports itself finished, because Lottie divides its own runtime by the platform
 * animator duration scale and a wall-clock timer would yank the sparks mid-flight at 4x.
 */
internal const val REACTION_BURST_DURATION_MS = 400L

/**
 * The overlay's edge length, equal to the asset's own 48-unit composition, so one unit is one dp.
 *
 * The sparks' 13-unit flight therefore ends 13dp from the chip's centre, just inside the ~13dp the
 * bubble's rounded clip allows below a 24dp-tall chip. A longer flight is simply cut off.
 */
private val REACTION_BURST_SIZE = 48.dp

/** The chip's own overshoot, deliberately shorter than the sparks so the chip settles first. */
private const val REACTION_POP_DURATION_MS = 280

/**
 * A single scale overshoot, defined here rather than in [MotdMotion]: it is this chip's own
 * gesture, not part of the app's shared vocabulary. Being an ordinary Compose spec, the platform
 * animator duration scale still applies to it for free.
 */
private val ReactionChipPop: FiniteAnimationSpec<Float> =
    keyframes {
        durationMillis = REACTION_POP_DURATION_MS
        1f at 0
        1.18f at 110
        1f at REACTION_POP_DURATION_MS
    }

/**
 * Whether a chip owes a burst.
 *
 * Three gates, all required. Only an own message celebrates -- someone reacting to a stranger's
 * line in a busy channel must not fire animations down the timeline. Only a genuine increase
 * counts, so an unreact never pops. And a null [previousCount] *is* the scrollback rule that
 * `MotdLottieMotion.playOnceOnTransition` spells out for the other call sites: a chip first
 * composed at a count of 12 has not been reacted to, it has been scrolled to. Running the helper
 * here as well would be a tautology, since the target of the transition is the incoming count.
 */
internal fun reactionBurstPlays(
    isSelf: Boolean,
    previousCount: Int?,
    count: Int,
    motionEnabled: Boolean,
): Boolean = isSelf && motionEnabled && previousCount != null && previousCount < count

/**
 * Chip row under a bubble. Tapping an unowned chip adds the reaction; tapping an owned chip asks
 * the caller to remove it with `draft/unreact`.
 *
 * [isSelf] is the message's ownership, not the reaction's: it decides whether an incoming reaction
 * is worth a burst.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionRow(
    reactions: List<ReactionChip>,
    onReact: (String) -> Unit,
    modifier: Modifier = Modifier,
    isSelf: Boolean = false,
    chipElevation: Dp = 0.dp,
) {
    if (reactions.isEmpty()) return
    // animateContentSize clips to its measured bounds, so keep the chip shadow inside them.
    val shadowPadding = chipElevation * 3
    FlowRow(
        // Ease chip add/remove and wrap-line growth; rows scrolled in render at final size (no
        // first-layout animation). Tight top gap so chips sit snugly under the message body.
        modifier =
            modifier
                .animateContentSize(animationSpec = MotdMotion.contentSize)
                .testTag("chat_reaction_row")
                .padding(
                    start = shadowPadding,
                    top = 2.dp + shadowPadding,
                    end = shadowPadding,
                    bottom = shadowPadding,
                ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        reactions.forEach { chip ->
            key(chip.emoji) {
                ReactionChipView(chip = chip, isSelf = isSelf, elevation = chipElevation, onClick = { onReact(chip.emoji) })
            }
        }
    }
}

@Composable
private fun ReactionChipView(
    chip: ReactionChip,
    isSelf: Boolean,
    elevation: Dp,
    onClick: () -> Unit,
) {
    val bg =
        if (chip.mine) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
    val fg =
        if (chip.mine) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    val motionEnabled = LocalLottieMotionEnabled.current
    var previousCount by remember { mutableStateOf<Int?>(null) }
    var burstToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(chip.count) {
        if (reactionBurstPlays(isSelf, previousCount, chip.count, motionEnabled)) burstToken++
        previousCount = chip.count
    }
    var bursting by remember { mutableStateOf(false) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(burstToken) {
        if (burstToken == 0) return@LaunchedEffect
        bursting = true
        pop.snapTo(1f)
        pop.animateTo(1f, ReactionChipPop)
    }

    // The burst is drawn in an overlay that reports no size of its own, so the row's
    // animateContentSize never sees a change and cannot re-run on a pop.
    Box(contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Row(
            // Compact chip: a fixed short height keeps reactions snug under the message instead of
            // the 48dp minimumInteractiveComponentSize that ballooned the row; the tap target stays
            // usable via the chip's own horizontal/vertical padding.
            modifier =
                Modifier
                    .testTag("chat_reaction_chip_${chip.emoji}")
                    // Paint-only scale: layout keeps the settled size throughout the overshoot.
                    .graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                    }.wrapContentWidth()
                    .heightIn(min = 24.dp)
                    .then(if (elevation > 0.dp) Modifier.shadow(elevation, RoundedCornerShape(50)) else Modifier)
                    .background(bg, RoundedCornerShape(50))
                    .then(
                        if (chip.mine) {
                            Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                        } else {
                            Modifier
                        },
                    ).clickable { onClick() }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = chip.emoji, color = Color.Unspecified, style = MaterialTheme.typography.bodySmall)
            AnimatedContent(
                targetState = chip.count,
                transitionSpec = {
                    fadeIn(MotdMotion.microFadeIn) togetherWith fadeOut(MotdMotion.microFadeOut)
                },
                label = "reaction_count",
            ) { count ->
                Text(
                    text = count.toString(),
                    color = fg,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        // A fresh key per burst so each one gets its own animatable rather than resuming a
        // finished one. The sparks report their own completion; nothing times them from outside.
        if (bursting) key(burstToken) { ReactionBurst(onFinished = { bursting = false }) }
    }
}

/**
 * Six accent sparks radiating out of the chip and fading, once.
 *
 * Sized above the chip and hung off `matchParentSize`, so it contributes nothing to measurement:
 * the parent Box stays exactly chip-sized and the sparks simply paint past its edges. The asset's
 * circles are fills, so they recolor through [lottieFillColor].
 *
 * [onFinished] fires from Lottie's own end-of-animation state rather than a timer, so the overlay
 * survives exactly as long as the sparks do at whatever animator duration scale is in force.
 */
@Composable
private fun BoxScope.ReactionBurst(onFinished: () -> Unit) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.reaction_burst))
    val animation =
        animateLottieCompositionAsState(
            composition = composition,
            isPlaying = true,
            iterations = 1,
            restartOnPlay = false,
        )
    // Unmount once the sparks are spent: a chip that burst should not keep a LottieDrawable alive
    // for the rest of the timeline's life.
    LaunchedEffect(animation.isAtEnd) { if (animation.isAtEnd) onFinished() }
    val sparkColor = MaterialTheme.colorScheme.primary.toArgb()
    val dynamicProperties =
        remember(sparkColor) {
            // Built directly rather than through rememberLottieDynamicProperty, which keys on the
            // vararg keypath array's identity and so re-resolves the keypath on every pass.
            LottieDynamicProperties(listOf(lottieFillColor(sparkColor, KeyPath("burst", "**"))))
        }
    Box(
        modifier =
            Modifier
                .matchParentSize()
                .wrapContentSize(align = Alignment.Center, unbounded = true),
    ) {
        LottieAnimation(
            composition = composition,
            progress = { animation.value },
            dynamicProperties = dynamicProperties,
            modifier = Modifier.size(REACTION_BURST_SIZE),
        )
    }
}

@Preview
@Composable
private fun ReactionRowPreview() {
    MotdTheme {
        ReactionRow(
            reactions =
                listOf(
                    ReactionChip("👍", 3, mine = true),
                    ReactionChip("❤️", 1, mine = false),
                    ReactionChip("😂", 5, mine = false),
                ),
            onReact = {},
        )
    }
}
