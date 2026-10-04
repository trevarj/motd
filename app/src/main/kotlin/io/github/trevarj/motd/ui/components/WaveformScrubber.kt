package io.github.trevarj.motd.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.audio.AudioWaveform
import io.github.trevarj.motd.ui.theme.MotdSizes

private const val WAVEFORM_SAMPLE_COUNT = 48
private const val RADIAL_WAVE_POINT_COUNT = 72
private const val RADIAL_WAVE_CYCLE_MILLIS = 900
private const val AUDIO_REACTIVE_EASING_MILLIS = 220

/** Compact audio timeline used by both received audio and staged voice-message previews. */
@Composable
fun WaveformScrubber(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    waveform: AudioWaveform? = null,
) {
    val fraction = value.coerceIn(0f, 1f)
    val displayedFraction = rememberUpdatedState(fraction)
    val changeValue = rememberUpdatedState(onValueChange)
    val finishChange = rememberUpdatedState(onValueChangeFinished)
    val samples =
        remember(waveform) {
            normalizeWaveformHeights(
                waveform?.normalized?.resampleBars(WAVEFORM_SAMPLE_COUNT).orEmpty(),
            )
        }
    val playedColor = MaterialTheme.colorScheme.tertiary
    val remainingColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    // ponytail: cache envelope geometry; playback only changes the clipping boundary.
    val drawing =
        remember(samples, playedColor, remainingColor, disabledColor, enabled) {
            Modifier.drawWithCache {
                val centerY = size.height / 2f
                val maximumHalfHeight = 10.dp.toPx().coerceAtMost(centerY)
                val ribbonPath =
                    if (samples.size >= 2) {
                        Path().apply {
                            val lastIndex = samples.lastIndex
                            var previousX = 0f
                            var previousY = centerY - maximumHalfHeight * samples[0]
                            moveTo(previousX, previousY)
                            for (index in 1..lastIndex) {
                                val x = size.width * index / lastIndex
                                val y = centerY - maximumHalfHeight * samples[index]
                                val controlOffset = (x - previousX) / 2f
                                cubicTo(previousX + controlOffset, previousY, x - controlOffset, y, x, y)
                                previousX = x
                                previousY = y
                            }
                            previousX = size.width
                            previousY = centerY + maximumHalfHeight * samples[lastIndex]
                            lineTo(previousX, previousY)
                            for (index in (lastIndex - 1) downTo 0) {
                                val x = size.width * index / lastIndex
                                val y = centerY + maximumHalfHeight * samples[index]
                                val controlOffset = (previousX - x) / 2f
                                cubicTo(previousX - controlOffset, previousY, x + controlOffset, y, x, y)
                                previousX = x
                                previousY = y
                            }
                            close()
                        }
                    } else {
                        null
                    }
                val trackWidth = 3.dp.toPx()
                onDrawBehind {
                    val progress = displayedFraction.value
                    val background = if (enabled) remainingColor else disabledColor
                    if (ribbonPath == null) {
                        drawLine(background, Offset(0f, centerY), Offset(size.width, centerY), trackWidth, StrokeCap.Round)
                        if (enabled && progress > 0f) {
                            drawLine(playedColor, Offset(0f, centerY), Offset(size.width * progress, centerY), trackWidth, StrokeCap.Round)
                        }
                    } else {
                        drawPath(ribbonPath, background)
                        if (enabled && progress > 0f) {
                            clipRect(right = size.width * progress) {
                                drawPath(ribbonPath, playedColor)
                            }
                        }
                    }
                }
            }
        }

    Spacer(
        modifier =
            modifier
                .fillMaxWidth()
                .height(MotdSizes.touchTarget)
                .semantics {
                    contentDescription = "Audio position"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    if (enabled) {
                        setProgress { target ->
                            changeValue.value(target.coerceIn(0f, 1f))
                            finishChange.value()
                            true
                        }
                    } else {
                        disabled()
                    }
                }.pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)

                        fun update(x: Float) {
                            changeValue.value((x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                        }
                        update(down.position.x)
                        down.consume()
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            update(change.position.x)
                            pressed = change.pressed
                            change.consume()
                        }
                        finishChange.value()
                    }
                }.then(drawing),
    )
}

@Composable
internal fun RadialPlaybackWave(
    playbackId: String,
    waveform: AudioWaveform?,
    positionMs: Long,
    durationMs: Long?,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val samples =
        remember(waveform) {
            waveform?.let { normalizeWaveformHeights(it.normalized) }.orEmpty()
        }
    if (!playing || samples.isEmpty()) return

    val amplitudeTarget = playbackWaveformAmplitude(samples, positionMs, durationMs)
    val amplitude by animateFloatAsState(
        targetValue = amplitudeTarget,
        animationSpec = tween(durationMillis = AUDIO_REACTIVE_EASING_MILLIS),
        label = "audio_playback_amplitude",
    )
    val phaseTarget = positionMs.toFloat() / RADIAL_WAVE_CYCLE_MILLIS
    val phase by animateFloatAsState(
        targetValue = phaseTarget,
        animationSpec = tween(durationMillis = AUDIO_REACTIVE_EASING_MILLIS, easing = LinearEasing),
        label = "audio_radial_wave_phase",
    )
    val seed = remember(playbackId) { playbackId.hashCode() }
    val color = MaterialTheme.colorScheme.tertiary
    val path = remember { Path() }

    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = size.minDimension * 0.38f
        val levelRadius = size.minDimension * 0.09f
        path.reset()
        repeat(RADIAL_WAVE_POINT_COUNT + 1) { point ->
            val index = point % RADIAL_WAVE_POINT_COUNT
            val angle = -Math.PI / 2.0 + index * Math.PI * 2.0 / RADIAL_WAVE_POINT_COUNT
            val radius =
                baseRadius +
                    levelRadius *
                    radialWaveLevel(
                        seed = seed,
                        index = index,
                        count = RADIAL_WAVE_POINT_COUNT,
                        amplitude = amplitude,
                        phase = phase,
                    )
            val offset =
                Offset(
                    center.x + kotlin.math.cos(angle).toFloat() * radius,
                    center.y + kotlin.math.sin(angle).toFloat() * radius,
                )
            if (point == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
        }
        path.close()
        drawPath(path = path, color = color)
        drawPath(
            path = path,
            color = color,
            style =
                Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
        )
    }
}

internal fun playbackWaveformAmplitude(
    samples: List<Float>,
    positionMs: Long,
    durationMs: Long?,
): Float {
    val duration = durationMs?.takeIf { it > 0 } ?: return 0f
    if (samples.isEmpty()) return 0f
    if (samples.size == 1) return samples.single().coerceIn(0f, 1f)

    val exactIndex = positionMs.coerceIn(0L, duration).toDouble() / duration * samples.lastIndex
    val lowerIndex = exactIndex.toInt()
    val upperIndex = (lowerIndex + 1).coerceAtMost(samples.lastIndex)
    val fraction = (exactIndex - lowerIndex).toFloat()
    return (samples[lowerIndex] + (samples[upperIndex] - samples[lowerIndex]) * fraction).coerceIn(0f, 1f)
}

/** Envelope-driven decorative radial wave; this is not frequency analysis. */
internal fun radialWaveLevel(
    seed: Int,
    index: Int,
    count: Int,
    amplitude: Float,
    phase: Float,
): Float {
    if (count <= 0 || index !in 0 until count) return 0f
    val seedPhase = ((seed ushr 8) and 0xffff) / 65_535f
    val angle = index.toFloat() / count
    val primary =
        0.5f +
            0.5f * kotlin.math.sin((angle * 2f + phase + seedPhase) * Math.PI * 2.0).toFloat()
    val detail =
        0.5f +
            0.5f *
            kotlin.math
                .sin(
                    (angle * 5f - phase * 0.6f + seedPhase * 0.5f) * Math.PI * 2.0,
                ).toFloat()
    val profile = 0.3f + 0.7f * (primary * 0.72f + detail * 0.28f)
    return (profile * amplitude.coerceIn(0f, 1f)).coerceIn(0f, 1f)
}

internal fun normalizeWaveformHeights(samples: List<Float>): List<Float> {
    val maximum = samples.maxOrNull()?.takeIf { it > 0f } ?: return samples
    return samples.map { (it / maximum).coerceIn(0.08f, 1f) }
}

internal fun List<Float>.resampleBars(count: Int): List<Float> {
    if (isEmpty()) return emptyList()
    return List(count.coerceAtLeast(1)) { index ->
        val start = index * size / count
        val end = ((index + 1) * size / count).coerceAtLeast(start + 1)
        subList(start.coerceAtMost(lastIndex), end.coerceAtMost(size)).maxOrNull().orEmptyPeak()
    }
}

private fun Float?.orEmptyPeak(): Float = (this ?: 0f).coerceIn(0.08f, 1f)
