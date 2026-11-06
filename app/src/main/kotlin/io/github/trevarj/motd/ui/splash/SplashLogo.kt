package io.github.trevarj.motd.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.SplashScreenAnimation
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

private const val EDGE_TRACE_DURATION_MS = 550
private const val SOFT_WASH_DURATION_MS = 700
private const val TRAVELING_PULSE_DURATION_MS = 600

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
        Box(
            modifier =
                if (isCoveringContent) {
                    Modifier.clearAndSetSemantics { }
                } else {
                    Modifier
                },
        ) {
            content()
        }
        if (loading) {
            // Keep first content visually identical to the platform's black launch surface while
            // DataStore resolves the already-persisted choice.
            Box(
                Modifier.fillMaxSize().background(Color.Black).consumeSplashInput(),
                contentAlignment = Alignment.Center,
            ) {
                SplashMark(
                    animation = SplashScreenAnimation.STATIC,
                    replayKey = 0,
                    size = 180.dp,
                    onFinished = {},
                )
            }
        } else if (request != null) {
            SplashSurface(request = request, onShown = onShown, onFinished = onFinished)
        }
    }
}

@Composable
fun SplashLogoPreview(animation: SplashScreenAnimation) {
    var replayKey by remember { mutableIntStateOf(0) }
    var previewReady by remember(animation) { mutableStateOf(false) }
    // Let the choice sheet finish entering before its one automatic demonstration. Replay stays
    // immediate, and there is deliberately no loop once that demonstration has settled.
    LaunchedEffect(animation) {
        previewReady = false
        delay(400)
        if (!previewReady) {
            previewReady = true
            replayKey += 1
        }
    }
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (previewReady) {
                SplashMark(animation = animation, replayKey = replayKey, size = 180.dp, onFinished = {})
            } else {
                SplashMarkArtwork(SplashScreenAnimation.STATIC, progress = 0f, size = 180.dp)
            }
            TextButton(
                onClick = {
                    previewReady = true
                    replayKey += 1
                },
                modifier = Modifier.testTag("settings_splash_screen_replay"),
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            ) {
                Text(stringResource(R.string.settings_splash_screen_replay))
            }
        }
    }
}

@Composable
private fun SplashSurface(
    request: SplashOverlayRequest,
    onShown: () -> Unit,
    onFinished: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black).consumeSplashInput(),
    ) {
        SplashMark(
            animation = request.animation,
            replayKey = 0,
            size = request.logoSize,
            onShown = onShown,
            onFinished = onFinished,
            modifier =
                request.locationInWindow?.let { location -> Modifier.absoluteOffset { location } }
                    ?: Modifier.align(Alignment.Center),
        )
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

@Composable
private fun SplashMark(
    animation: SplashScreenAnimation,
    replayKey: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    onShown: () -> Unit = {},
    onFinished: () -> Unit,
) {
    val progress = remember(animation, replayKey) { Animatable(0f) }
    LaunchedEffect(animation, replayKey) {
        // Keep the platform icon for the first composited app frame. Its measured bitmap canvas
        // then hands off to the same positioned app bitmap without a blank or resize frame.
        withFrameNanos { }
        onShown()
        if (animation == SplashScreenAnimation.STATIC) {
            onFinished()
        } else {
            progress.animateTo(1f, tween(animation.durationMillis(), easing = LinearEasing))
            onFinished()
        }
    }
    SplashMarkArtwork(animation, progress.value, size, modifier.testTag("splash_logo_mark"))
}

/** Deterministic bitmap-plus-light paint used by both the timer and screenshot regression. */
@Composable
internal fun SplashMarkArtwork(
    animation: SplashScreenAnimation,
    progress: Float,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    // This is the source bitmap's intrinsic 200dp canvas. The platform icon and pre-31 layer
    // list stay untouched; no geometry is redrawn or transformed during handoff.
    Box(
        modifier =
            modifier
                .size(size)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    splashLights(animation, progress, this.size.width, this.size.height)
                        .forEach { light -> drawRect(light, blendMode = BlendMode.SrcAtop) }
                },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_splash_logo),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

data class SplashOverlayRequest(
    val animation: SplashScreenAnimation,
    val logoSize: Dp,
    val locationInWindow: IntOffset? = null,
)

private fun SplashScreenAnimation.durationMillis(): Int =
    when (this) {
        SplashScreenAnimation.EDGE_TRACE -> EDGE_TRACE_DURATION_MS
        SplashScreenAnimation.SOFT_WASH -> SOFT_WASH_DURATION_MS
        SplashScreenAnimation.TRAVELING_PULSE -> TRAVELING_PULSE_DURATION_MS
        SplashScreenAnimation.STATIC -> 0
    }

private fun splashLights(
    animation: SplashScreenAnimation,
    progress: Float,
    width: Float,
    height: Float,
): List<Brush> {
    if (animation == SplashScreenAnimation.STATIC) return emptyList()
    val localProgress = progress.coerceIn(0f, 1f)
    val center =
        when (animation) {
            SplashScreenAnimation.EDGE_TRACE,
            SplashScreenAnimation.SOFT_WASH,
            SplashScreenAnimation.TRAVELING_PULSE,
            -> Offset(width * (0.445f + 0.145f * localProgress), height * (0.65f - 0.28f * localProgress))

            SplashScreenAnimation.STATIC -> return emptyList()
        }
    val radius =
        when (animation) {
            SplashScreenAnimation.EDGE_TRACE -> width * 0.07f
            SplashScreenAnimation.SOFT_WASH -> width * 0.18f
            SplashScreenAnimation.TRAVELING_PULSE -> width * 0.10f
            SplashScreenAnimation.STATIC -> 0f
        }
    val alpha =
        when (animation) {
            SplashScreenAnimation.EDGE_TRACE -> 0.20f
            SplashScreenAnimation.SOFT_WASH -> 0.15f
            SplashScreenAnimation.TRAVELING_PULSE -> 0.23f
            SplashScreenAnimation.STATIC -> 0f
        }
    val envelope = sin(PI * localProgress).toFloat()
    val primary =
        Brush.radialGradient(
            0f to Color.White.copy(alpha = alpha * envelope * 2.4f),
            0.5f to Color.White.copy(alpha = alpha * envelope * 0.8f),
            1f to Color.Transparent,
            center = center,
            radius = max(radius, 1f),
        )
    val shoulder =
        Brush.radialGradient(
            0f to Color(0xff5f5f5f).copy(alpha = alpha * envelope * 0.95f),
            1f to Color.Transparent,
            center = Offset(center.x - radius * 1.3f, center.y + radius * 0.3f),
            radius = max(radius * 1.5f, 1f),
        )
    if (animation != SplashScreenAnimation.TRAVELING_PULSE) return listOf(shoulder, primary)
    val trailingProgress = (localProgress - 0.16f).coerceAtLeast(0f)
    val trailCenter = Offset(width * (0.445f + 0.145f * trailingProgress), height * (0.65f - 0.28f * trailingProgress))
    val trail =
        Brush.radialGradient(
            0f to Color.White.copy(alpha = alpha * envelope * 0.18f),
            1f to Color.Transparent,
            center = trailCenter,
            radius = max(radius * 1.5f, 1f),
        )
    return listOf(trail, shoulder, primary)
}
