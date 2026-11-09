package io.github.trevarj.motd.gesture.radial

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.data.prefs.EffectSurface
import io.github.trevarj.motd.ui.components.effectSurface
import io.github.trevarj.motd.ui.theme.LocalSurfaceEffects
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

/** Test tag for the resting orb tab (and for its accessible button form). */
const val GESTURE_ORB_TAG = "gesture_orb"

/** The resting tab's paint. Never the pointer target: it slides while a reposition drag is in flight. */
@Composable
internal fun OrbTab(
    edge: OrbEdge,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    val shape =
        if (edge == OrbEdge.LEFT) {
            RoundedCornerShape(topEndPercent = 100, bottomEndPercent = 100)
        } else {
            RoundedCornerShape(topStartPercent = 100, bottomStartPercent = 100)
        }
    val effects = LocalSurfaceEffects.current
    val opacity = effects[EffectSurface.FLOATING_BUTTON].opacityPercent.coerceIn(0, 100)
    val opaque = opacity == 100 || (!effects.enabled && opacity > 0)
    Surface(
        modifier =
            modifier
                .size(RadialDimens.OrbWidth, RadialDimens.OrbHeight)
                .graphicsLayer { this.alpha = alpha }
                .then(if (opaque) Modifier else Modifier.effectSurface(EffectSurface.FLOATING_BUTTON, MaterialTheme.colorScheme.primaryContainer, shape)),
        shape = shape,
        color = if (opaque) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 1f) else androidx.compose.ui.graphics.Color.Transparent,
        tonalElevation = 3.dp,
        shadowElevation = if (opaque) 2.dp else 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.Apps,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * The orb's pointer target.
 *
 * Deliberately separate from [OrbTab] and pinned to the *committed* placement, because every
 * position a pointer reports is local to the node that received it: a target that slid along with
 * the finger would report a damped drag and the orb would never keep up with the thumb.
 *
 * A hold past the platform long-press timeout arms the menu; movement past touch slop before that
 * turns the gesture into a reposition drag instead. Both are decided here so the interpretation of
 * the drag — [RadialMenuMachine] or [placementForDrag] — is settled before any state moves.
 */
@Composable
internal fun GestureOrbTouchTarget(
    onHoldStart: (Offset) -> Unit,
    onHoldMove: (Offset) -> Unit,
    onHoldEnd: () -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val origin = remember { mutableStateOf(Offset.Zero) }
    // Read, never keyed on: a pointerInput restarted mid-gesture drops the pointer it was tracking
    // (the ServerDrawer trap), and this node's own position is exactly what moves after a drop.
    val latestOrigin by rememberUpdatedState(origin.value)
    val latestHoldStart by rememberUpdatedState(onHoldStart)
    val latestHoldMove by rememberUpdatedState(onHoldMove)
    val latestHoldEnd by rememberUpdatedState(onHoldEnd)
    val latestDragMove by rememberUpdatedState(onDragMove)
    val latestDragEnd by rememberUpdatedState(onDragEnd)
    Box(
        modifier =
            modifier
                .testTag(GESTURE_ORB_TAG)
                .size(RadialDimens.OrbTouchWidth, RadialDimens.OrbTouchHeight)
                // The tab is docked inside the system back-gesture edge zone, and a radial drag toward
                // the screen centre is byte-for-byte a back swipe. Without this exclusion the system
                // dispatches a back alongside the menu gesture, and that back can pop the chat the
                // released action is concurrently replacing — corrupting the NavHost transition into a
                // blank screen. Excluding the touch target keeps the whole gesture ours.
                .systemGestureExclusion()
                .onGloballyPositioned { origin.value = it.positionInRoot() }
                .pointerInput(Unit) {
                    coroutineScope {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            // Claim the press up front: the screen underneath must not also act on it.
                            down.consume()
                            var armed = false
                            var dragging = false
                            var last = latestOrigin + down.position
                            val hold =
                                this@coroutineScope.launch {
                                    delay(viewConfiguration.longPressTimeoutMillis)
                                    armed = true
                                    latestHoldStart(last)
                                }
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                last = latestOrigin + change.position
                                when {
                                    armed -> {
                                        latestHoldMove(last)
                                    }

                                    dragging -> {
                                        latestDragMove(last)
                                    }

                                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop -> {
                                        dragging = true
                                        hold.cancel()
                                        latestDragMove(last)
                                    }
                                }
                                if (!change.pressed) break
                            }
                            hold.cancel()
                            when {
                                armed -> latestHoldEnd()

                                dragging -> latestDragEnd(last)

                                // A plain tap does nothing: the semantic click is the accessible entry point.
                                else -> Unit
                            }
                        }
                    }
                },
    )
}

/**
 * The orb while touch exploration is on.
 *
 * A radial drag is unusable under a screen reader — the gesture is intercepted before it reaches the
 * app — so the same node becomes an ordinary button that opens the list form of the menu.
 */
@Composable
internal fun AccessibleGestureOrb(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestClick by rememberUpdatedState(onClick)
    Box(
        modifier =
            modifier
                .testTag(GESTURE_ORB_TAG)
                .size(RadialDimens.OrbTouchWidth, RadialDimens.OrbTouchHeight)
                .clickable(role = Role.Button) { latestClick() }
                .semantics(mergeDescendants = true) { this.contentDescription = contentDescription },
    )
}
