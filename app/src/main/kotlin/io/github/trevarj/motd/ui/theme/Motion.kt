package io.github.trevarj.motd.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * The small, shared motion vocabulary used by the app's custom Compose transitions.
 *
 * Specs remain ordinary Compose animation specs so the platform animator scale is respected by
 * Compose's [androidx.compose.ui.MotionDurationScale]. The app deliberately has no separate motion
 * preference and does not add continuous decorative animations to the chat timeline.
 */
object MotdMotion {
    const val MicroDurationMs = 140
    const val StandardDurationMs = 210
    const val NavigationDurationMs = 340
    const val ChatBackDurationMs = 420
    const val ArchiveSettleMinimumDurationMs = 200
    const val ArchiveSettleMaximumDurationMs = 300

    private val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Quintic ease-out keeps the archive settle decisive without spring overshoot. */
    val archiveSettleEasing =
        Easing { fraction ->
            val inverse = 1f - fraction
            1f - inverse * inverse * inverse * inverse * inverse
        }
    private const val SoftSpringStiffness = 340f

    val fadeIn: FiniteAnimationSpec<Float> =
        tween(
            durationMillis = StandardDurationMs,
            easing = StandardEasing,
        )
    val fadeOut: FiniteAnimationSpec<Float> =
        tween(
            durationMillis = MicroDurationMs,
            easing = StandardEasing,
        )
    val microFadeIn: FiniteAnimationSpec<Float> =
        tween(
            durationMillis = MicroDurationMs,
            easing = StandardEasing,
        )
    val microFadeOut: FiniteAnimationSpec<Float> =
        tween(
            durationMillis = MicroDurationMs,
            easing = StandardEasing,
        )

    /** Container-color state changes: micro tempo, so a tint never lags the tap that caused it. */
    val colorFade: FiniteAnimationSpec<Color> =
        tween(
            durationMillis = MicroDurationMs,
            easing = StandardEasing,
        )

    /**
     * Screen-to-screen slides: chat entry at the tempo of Material 3's
     * [androidx.compose.material3.ModalNavigationDrawer], and the shared-axis push/pop between
     * sibling screens. Navigation specs are bounded tweens, never springs: a spring's settling
     * tail keeps the NavHost transition open long after the surface looks parked, and a
     * navigation that interrupts that window can wedge the NavHost into composing no destination
     * at all (full blank screen until the activity is recreated).
     */
    val navigationDrawerSpatial: FiniteAnimationSpec<IntOffset> =
        tween(
            durationMillis = NavigationDurationMs,
            easing = StandardEasing,
        )

    /**
     * Navigation-tempo fade for destinations that appear in place rather than sliding (the image
     * viewer). Bounded for the same reason as [navigationDrawerSpatial].
     */
    val navigationFade: FiniteAnimationSpec<Float> =
        tween(
            durationMillis = NavigationDurationMs,
            easing = StandardEasing,
        )

    /**
     * Returning from chat to the chat list, deliberately calmer than the entry. Bounded for the
     * same reason as [navigationDrawerSpatial].
     */
    val chatBackSpatial: FiniteAnimationSpec<IntOffset> =
        tween(
            durationMillis = ChatBackDurationMs,
            easing = StandardEasing,
        )

    /** A calm spring: responsive and soft, without a playful bounce. */
    val softSpring: FiniteAnimationSpec<Float> =
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = SoftSpringStiffness,
        )
    val rowPlacement: FiniteAnimationSpec<IntOffset> =
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = SoftSpringStiffness,
        )
    val contentSize: FiniteAnimationSpec<IntSize> =
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = SoftSpringStiffness,
        )

    /**
     * The composer-to-bubble send flight, and the same value opening the gap the bubble lands in.
     *
     * Deliberately underdamped, unlike [softSpring]: a send should read as launched, so the bubble
     * rises a few pixels past its slot and settles back. The gap clamps the same progress at 1,
     * which is what leaves the overshoot visible on the bubble alone. A softer stiffness gives
     * the launch and settle a little more breathing room.
     */
    val sendFlightSpring: FiniteAnimationSpec<Float> =
        spring(
            dampingRatio = 0.8f,
            stiffness = 260f,
        )

    /**
     * The send morph's transformation: field text into the real bubble (surface growth and
     * alignment transfer). Deliberately slower than [sendFlightSpring] and a bounded tween, not
     * a spring: riding the flight's spring compressed the transformation too far.
     * The row handoff waits for both animations, even when persistence finishes immediately.
     */
    val sendMorphGrow: FiniteAnimationSpec<Float> =
        tween(
            durationMillis = 500,
            easing = StandardEasing,
        )

    /** Duration grows monotonically with the remaining fraction and remains within 200–300ms. */
    fun archiveSettleDurationMillis(remainingFraction: Float): Int =
        (
            ArchiveSettleMinimumDurationMs +
                (ArchiveSettleMaximumDurationMs - ArchiveSettleMinimumDurationMs) *
                remainingFraction.coerceIn(0f, 1f)
        ).toInt()

    fun archiveSettleSpec(remainingFraction: Float): FiniteAnimationSpec<Float> =
        tween(
            durationMillis = archiveSettleDurationMillis(remainingFraction),
            easing = archiveSettleEasing,
        )
}
