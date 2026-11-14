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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

internal const val STARTUP_SPLASH_TIMEOUT_MS = 5_000L
internal const val STARTUP_SPLASH_READY_DURATION_MS = 200
private const val STARTUP_SPLASH_START_PROGRESS = 120f / SPARK_CUT_DURATION_MS
private const val STARTUP_SPLASH_END_PROGRESS = 860f / SPARK_CUT_DURATION_MS

@Composable
fun StartupSplashGate(
    request: SplashOverlayRequest?,
    loading: Boolean,
    rootReady: Boolean,
    onShown: () -> Unit,
    onFinished: () -> Unit,
    content: @Composable () -> Unit,
) {
    val isCoveringContent = loading || request != null
    val finish by rememberUpdatedState(onFinished)
    LaunchedEffect(isCoveringContent) {
        if (isCoveringContent) {
            // ponytail: one deadline spans the platform handoff and the cosmetic animation.
            delay(STARTUP_SPLASH_TIMEOUT_MS)
            finish()
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(if (isCoveringContent) Modifier.clearAndSetSemantics { } else Modifier) {
            content()
        }
        if (loading) {
            // The native launch surface stays black until its measured icon bounds are available.
            Box(Modifier.fillMaxSize().background(Color.Black).consumeSplashInput())
        } else if (request != null) {
            Box(Modifier.fillMaxSize().background(Color.Black).consumeSplashInput()) {
                val progress = remember(request) { Animatable(STARTUP_SPLASH_START_PROGRESS) }
                val ready by rememberUpdatedState(rootReady)
                val shown by rememberUpdatedState(onShown)
                LaunchedEffect(request) {
                    withFrameNanos { }
                    shown()
                    snapshotFlow { ready }.collectLatest { isReady ->
                        val remainingMs =
                            ((STARTUP_SPLASH_END_PROGRESS - progress.value) * SPARK_CUT_DURATION_MS)
                                .toInt().coerceAtLeast(0)
                        val durationMs =
                            if (isReady) minOf(remainingMs, STARTUP_SPLASH_READY_DURATION_MS) else remainingMs
                        progress.animateTo(STARTUP_SPLASH_END_PROGRESS, tween(durationMs, easing = LinearEasing))
                        progress.snapTo(1f)
                        finish()
                    }
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
