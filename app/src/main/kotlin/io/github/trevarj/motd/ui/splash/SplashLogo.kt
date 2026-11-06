package io.github.trevarj.motd.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import io.github.trevarj.motd.R

@Composable
fun StartupSplashGate(
    request: SplashOverlayRequest?,
    loading: Boolean,
    onShown: () -> Unit,
    onFinished: () -> Unit,
    content: @Composable () -> Unit,
) {
    val isCoveringContent = loading || request != null
    Box(Modifier.fillMaxSize()) {
        Box(if (isCoveringContent) Modifier.clearAndSetSemantics { } else Modifier) {
            content()
        }
        if (loading) {
            // The native launch surface stays black until its measured icon bounds are available.
            Box(Modifier.fillMaxSize().background(Color.Black).consumeSplashInput())
        } else if (request != null) {
            Box(Modifier.fillMaxSize().background(Color.Black).consumeSplashInput()) {
                val progress = remember(request) { Animatable(0f) }
                LaunchedEffect(request) {
                    withFrameNanos { }
                    onShown()
                    progress.animateTo(1f, tween(SPARK_CUT_DURATION_MS, easing = LinearEasing))
                    onFinished()
                }
                SplashMarkArtwork(
                    progress = progress.value,
                    size = request.logoSize,
                    modifier =
                        (
                            request.locationInWindow?.let { location -> Modifier.absoluteOffset { location } }
                                ?: Modifier.align(Alignment.Center)
                        ).testTag("splash_logo_mark"),
                )
            }
        }
    }
}

private fun Modifier.consumeSplashInput(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent().changes.forEach { change -> change.consume() }
            }
        }
    }

/** The sole launch treatment resolves to the unchanged ceramic splash artwork. */
@Composable
internal fun SplashMarkArtwork(
    progress: Float,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    // Use the original painter throughout the final hold, including its exact raster filtering.
    if (progress * SPARK_CUT_DURATION_MS < 860f) {
        SparkCutArtwork(progress, size, modifier)
    } else {
        Image(
            painter = painterResource(R.drawable.ic_splash_logo),
            contentDescription = null,
            modifier = modifier.size(size),
        )
    }
}

data class SplashOverlayRequest(
    val logoSize: Dp,
    val locationInWindow: IntOffset? = null,
)
