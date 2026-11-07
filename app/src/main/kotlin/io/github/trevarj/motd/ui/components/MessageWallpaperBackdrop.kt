package io.github.trevarj.motd.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.unit.dp

/** One wallpaper display list and one native blur, shared by the timeline and send-flight backings. */
internal class MessageWallpaperBackdrop(
    val raw: GraphicsLayer,
    val blurred: GraphicsLayer,
    initialIntensity: Int,
) {
    var intensity by mutableIntStateOf(initialIntensity)
    var coordinates: LayoutCoordinates? = null
    var revision by mutableIntStateOf(0)
}

internal val LocalMessageWallpaperBackdrop = staticCompositionLocalOf<MessageWallpaperBackdrop?> { null }

@Composable
internal fun rememberMessageWallpaperBackdrop(intensity: Int): MessageWallpaperBackdrop? {
    // ponytail: Android 8–11 keep the existing tint; no software copies or idle frame loop.
    if (intensity <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val raw = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    val backdrop = remember(raw, blurred) { MessageWallpaperBackdrop(raw, blurred, intensity.coerceIn(0, 100)) }
    SideEffect { backdrop.intensity = intensity.coerceIn(0, 100) }
    return backdrop
}

/** Attach only to the already-rendered wallpaper, never to a foreground/message host. */
internal fun Modifier.recordMessageWallpaper(
    backdrop: MessageWallpaperBackdrop?,
    canvasColor: Color,
): Modifier {
    if (backdrop == null) return this
    return this
        .onGloballyPositioned { backdrop.coordinates = it }
        .onLayoutRectChanged(throttleMillis = 0, debounceMillis = 0) { backdrop.revision++ }
        // Isolate source invalidation: consumer redraws must not re-record the wallpaper and
        // publish another revision. This retained host does not allocate an offscreen bitmap.
        .graphicsLayer()
        .drawWithCache {
            val radius = (32.dp * (backdrop.intensity / 100f)).toPx()
            backdrop.blurred.compositingStrategy = CompositingStrategy.Offscreen
            backdrop.blurred.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
            val recordWallpaper: ContentDrawScope.() -> Unit = {
                drawRect(canvasColor)
                drawContent()
            }
            val replayWallpaper: DrawScope.() -> Unit = { drawLayer(backdrop.raw) }
            onDrawWithContent {
                backdrop.raw.record(block = recordWallpaper)
                backdrop.blurred.record(block = replayWallpaper)
                // Consumers observe this only in draw. Do not subscribe this source to its own write.
                Snapshot.withoutReadObservation { backdrop.revision++ }
                drawLayer(backdrop.raw)
            }
        }
}

/** Blur the wallpaper behind the existing backing outline, then tint it; foreground stays sharp. */
@Composable
internal fun Modifier.messageBacking(
    tint: Color,
    shape: Shape = RectangleShape,
    morphProgress: (() -> Float)? = null,
    blurEnabled: Boolean = true,
): Modifier {
    val backdrop = LocalMessageWallpaperBackdrop.current
    // Opaque tint already covers the wallpaper. Keep its original single-paint clipped edge,
    // especially for incoming mention highlights; a second replay would compound edge coverage.
    if (!blurEnabled || backdrop == null || tint.alpha == 1f) {
        if (morphProgress == null) return background(tint)
        return drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            onDrawBehind {
                val progress = morphProgress().coerceIn(0f, 1f)
                if (progress == 1f) {
                    drawRect(tint)
                } else {
                    scale(0.85f + 0.15f * progress) {
                        drawOutline(outline, tint, alpha = (progress * 1.6f).coerceAtMost(1f))
                    }
                }
            }
        }
    }
    var coordinates by remember(backdrop) { mutableStateOf<LayoutCoordinates?>(null) }
    var positionRevision by remember(backdrop) { mutableIntStateOf(0) }
    val positioned =
        this
            .onGloballyPositioned { coordinates = it }
            // Compose 1.12 tracks ancestor graphicsLayer translations too (runway and flight).
            .onLayoutRectChanged(throttleMillis = 0, debounceMillis = 0) { positionRevision++ }
    return positioned.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val mask = Path().apply { addOutline(outline) }
        val bounds = Rect(Offset.Zero, size)
        val fadePaint = if (morphProgress != null) Paint() else null
        onDrawBehind {
            val progress = morphProgress?.invoke()?.coerceIn(0f, 1f) ?: 1f
            val alpha = if (progress == 1f) 1f else (progress * 1.6f).coerceAtMost(1f)
            if (alpha > 0f) {
                val source = backdrop.coordinates
                val consumer = coordinates
                // Read live coordinates in draw, not a saved first-layout position or rounded rect.
                positionRevision
                backdrop.revision
                val offset =
                    if (source?.isAttached == true && consumer?.isAttached == true) {
                        consumer.localPositionOf(source, Offset.Zero)
                    } else {
                        null
                    }
                val surfaceScale = if (progress == 1f) 1f else 0.85f + 0.15f * progress
                drawIntoCanvas { canvas ->
                    // Only the single in-flight morph needs a bounded alpha layer, never a row blur.
                    if (alpha < 1f) {
                        fadePaint!!.alpha = alpha
                        canvas.saveLayer(bounds, fadePaint)
                    }
                    if (progress == 1f) {
                        clipRect {
                            if (offset != null) translate(offset.x, offset.y) { drawLayer(backdrop.blurred) }
                            drawRect(tint)
                        }
                    } else {
                        scale(surfaceScale) {
                            clipPath(mask) {
                                // Scale the mask, not the wallpaper: sampling remains fixed in screen space.
                                scale(1f / surfaceScale) {
                                    if (offset != null) translate(offset.x, offset.y) { drawLayer(backdrop.blurred) }
                                }
                                drawRect(tint)
                            }
                        }
                    }
                    if (alpha < 1f) canvas.restore()
                }
            }
        }
    }
}
