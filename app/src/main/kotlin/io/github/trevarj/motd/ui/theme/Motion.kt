package io.github.trevarj.motd.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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

    private val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    private const val SoftSpringStiffness = 340f

    val fadeIn: FiniteAnimationSpec<Float> = tween(
        durationMillis = StandardDurationMs,
        easing = StandardEasing,
    )
    val fadeOut: FiniteAnimationSpec<Float> = tween(
        durationMillis = MicroDurationMs,
        easing = StandardEasing,
    )
    val microFadeIn: FiniteAnimationSpec<Float> = tween(
        durationMillis = MicroDurationMs,
        easing = StandardEasing,
    )
    val microFadeOut: FiniteAnimationSpec<Float> = tween(
        durationMillis = MicroDurationMs,
        easing = StandardEasing,
    )

    /** A calm spring: responsive and soft, without a playful bounce. */
    val softSpring: FiniteAnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = SoftSpringStiffness,
    )
    val softOffsetSpring: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = SoftSpringStiffness,
    )
    val rowPlacement: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = SoftSpringStiffness,
    )
    val contentSize: FiniteAnimationSpec<IntSize> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = SoftSpringStiffness,
    )

    fun messageEnter(): EnterTransition =
        fadeIn(animationSpec = fadeIn) +
            scaleIn(initialScale = 0.96f, animationSpec = softSpring) +
            slideInVertically(
                initialOffsetY = { it / 12 },
                animationSpec = softOffsetSpring,
            )

    fun messageExit(): ExitTransition =
        fadeOut(animationSpec = fadeOut) + scaleOut(targetScale = 0.98f, animationSpec = softSpring)
}
