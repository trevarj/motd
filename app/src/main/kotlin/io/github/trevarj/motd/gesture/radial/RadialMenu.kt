package io.github.trevarj.motd.gesture.radial

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.gesture.vector
import io.github.trevarj.motd.ui.components.effectSurface
import kotlin.math.roundToInt
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

/** Test tag for the full-screen dimmer behind an open ring. */
const val GESTURE_MENU_SCRIM_TAG = "gesture_menu_scrim"

/** Test tag for one slice of the active ring, keyed by the node it came from. */
fun gestureMenuSliceTag(nodeId: String): String = "gesture_menu_slice_$nodeId"

/**
 * The radial menu's fixed distances.
 *
 * The deadzone and the band's inner edge are the same number on purpose: the centre well ends
 * exactly where slices begin, so there is no dead gap the finger can sit in. The commit radius sits
 * a little beyond the drawn band, and that gap is the whole of the descend hysteresis — a slice must
 * be left deliberately, not merely brushed past.
 */
object RadialDimens {
    val Deadzone = 40.dp
    val BandInner = 40.dp
    val BandOuter = 104.dp
    val Descend = 116.dp
    val Label = 124.dp
    val EdgeMargin = 16.dp

    /** The resting tab: a half-circle nub, small enough to ignore and wide enough to find. */
    val OrbWidth = 24.dp
    val OrbHeight = 48.dp

    /** The tab's touch target, comfortably above the 48dp minimum in both directions. */
    val OrbTouchWidth = 48.dp
    val OrbTouchHeight = 64.dp
}

/** Scrim opacity: enough to mute the screen behind the ring without hiding where the orb was. */
private const val SCRIM_ALPHA = 0.45f

/** Rings below the active one stay visible as a trail, but must not compete with it. */
private const val PARENT_RING_ALPHA = 0.35f

/** Angular gap between neighbouring slices, so the ring reads as separate targets. */
private const val SLICE_GAP_DEGREES = 1.5f

@Composable
internal fun rememberRadialMetrics(): RadialMetrics {
    val density = LocalDensity.current
    return remember(density) {
        with(density) {
            RadialMetrics(
                deadzoneRadius = RadialDimens.Deadzone.toPx(),
                bandInnerRadius = RadialDimens.BandInner.toPx(),
                bandOuterRadius = RadialDimens.BandOuter.toPx(),
                descendRadius = RadialDimens.Descend.toPx(),
                labelRadius = RadialDimens.Label.toPx(),
                edgeMargin = RadialDimens.EdgeMargin.toPx(),
            )
        }
    }
}

/**
 * Draws an open menu. Entirely stateless: every transition already happened in [RadialMenuMachine],
 * so what is on screen is exactly what the next release will act on.
 */
@Composable
internal fun RadialMenu(
    state: RadialMenuState,
    metrics: RadialMetrics,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val bandColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val wellColor = MaterialTheme.colorScheme.surfaceContainerLow
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .testTag(GESTURE_MENU_SCRIM_TAG)
                .background(Color.Black.copy(alpha = SCRIM_ALPHA)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            state.rings.forEachIndexed { index, ring ->
                val active = index == state.rings.lastIndex
                drawRadialRing(
                    ring = ring,
                    metrics = metrics,
                    bandColor = bandColor,
                    wellColor = wellColor,
                    accent = accent,
                    alpha = if (active) 1f else PARENT_RING_ALPHA,
                )
            }
        }
        RadialSliceLabels(ring = state.active, metrics = metrics)
    }
}

/**
 * Icons and labels for the active ring.
 *
 * Laid out by hand rather than with offsets because each label has to be *centred* on its slice
 * anchor, and the width that centring depends on is only known after measuring.
 */
@Composable
private fun RadialSliceLabels(
    ring: RadialRing,
    metrics: RadialMetrics,
) {
    val comfortable = ring.arc.isComfortable()
    Layout(
        content = {
            ring.entries.forEachIndexed { index, entry ->
                RadialSliceLabel(
                    entry = entry,
                    focused = ring.focus == index,
                    // A cramped ring would overlap its own labels, so only the live slice is named.
                    showLabel = comfortable || ring.focus == index,
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val itemConstraints = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val placeables = measurables.map { it.measure(itemConstraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val anchor = sliceAnchor(ring.center, ring.arc, index, metrics.labelRadius)
                placeable.place(
                    x = (anchor.x - placeable.width / 2f).roundToInt(),
                    y = (anchor.y - placeable.height / 2f).roundToInt(),
                )
            }
        }
    }
}

@Composable
private fun RadialSliceLabel(
    entry: RadialEntry,
    focused: Boolean,
    showLabel: Boolean,
) {
    val container =
        if (focused) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
    val content =
        if (focused) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier =
            Modifier
                .testTag(gestureMenuSliceTag(entry.id))
                .widthIn(max = 112.dp)
                .clip(RoundedCornerShape(12.dp))
                .effectSurface(io.github.trevarj.motd.data.prefs.EffectSurface.MENU, container, RoundedCornerShape(12.dp))
                .then(if (focused) Modifier.border(2.dp, content, RoundedCornerShape(12.dp)) else Modifier)
                .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = entry.icon.vector,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(20.dp),
        )
        if (showLabel) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.labelMedium,
                color = content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun DrawScope.drawRadialRing(
    ring: RadialRing,
    metrics: RadialMetrics,
    bandColor: Color,
    wellColor: Color,
    accent: Color,
    alpha: Float,
) {
    // A stroked arc is centred on its own radius, so the band's mid-line carries its full thickness.
    val radius = (metrics.bandInnerRadius + metrics.bandOuterRadius) / 2f
    val thickness = metrics.bandOuterRadius - metrics.bandInnerRadius
    val topLeft = Offset(ring.center.x - radius, ring.center.y - radius)
    val size = Size(radius * 2f, radius * 2f)
    val style = Stroke(width = thickness)
    ring.entries.indices.forEach { index ->
        drawArc(
            color = if (ring.focus == index) accent else bandColor,
            startAngle = ring.arc.sliceStartDegrees(index) + SLICE_GAP_DEGREES / 2f,
            sweepAngle = (ring.arc.sliceDegrees - SLICE_GAP_DEGREES).coerceAtLeast(0f),
            useCenter = false,
            topLeft = topLeft,
            size = size,
            style = style,
            alpha = alpha,
        )
    }
    drawCircle(color = wellColor, radius = metrics.deadzoneRadius, center = ring.center, alpha = alpha)
}
