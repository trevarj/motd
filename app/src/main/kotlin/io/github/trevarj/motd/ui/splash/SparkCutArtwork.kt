package io.github.trevarj.motd.ui.splash

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import io.github.trevarj.motd.R
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal const val SPARK_CUT_DURATION_MS = 950
private const val SIZE = 800f
private const val AX = 0.54f
private const val AY = -0.842f
private const val NX = -AY
private const val NY = AX

// Derived from the true aperture plus its bevel by tools/splash-spark-cut.html.
private const val CUT_MIN = -277.432f
private const val CUT_MAX = 63.362f

/** One cached reconstructed face and the original raster; no bitmap reconstruction per frame. */
@Composable
internal fun SparkCutArtwork(
    progress: Float,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val original = ImageBitmap.imageResource(R.drawable.ic_splash_logo).asAndroidBitmap()
    val intact = ImageBitmap.imageResource(R.drawable.splash_spark_intact).asAndroidBitmap()
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val cut = remember { Path() }
    val destination = remember { RectF(0f, 0f, SIZE, SIZE) }
    val screen = remember { PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    val replace = remember { PorterDuffXfermode(PorterDuff.Mode.SRC) }
    val mask = remember { PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    val effectPaint = remember { Paint().apply { xfermode = screen } }
    Canvas(modifier.size(size)) {
        val canvas = drawContext.canvas.nativeCanvas
        val ms = progress.coerceIn(0f, 1f) * SPARK_CUT_DURATION_MS
        val threshold = CUT_MIN + (CUT_MAX - CUT_MIN) * smooth(120f, 700f, ms)
        canvas.save()
        canvas.scale(this.size.width / SIZE, this.size.height / SIZE)
        paint.shader = null
        paint.xfermode = null
        paint.color = android.graphics.Color.WHITE
        if (ms >= 700f) {
            canvas.drawBitmap(original, null, destination, paint)
        } else if (ms <= 120f) {
            canvas.drawBitmap(intact, null, destination, paint)
        } else {
            // Replace the revealed prefix in one layer; complementary alpha clips can leave a seam.
            val projection = threshold / (AX * AX + AY * AY)
            val px = AX * projection
            val py = AY * projection
            cut.reset()
            cut.moveTo(px + NX * 2000f, py + NY * 2000f)
            cut.lineTo(px - NX * 2000f, py - NY * 2000f)
            cut.lineTo(px - NX * 2000f - AX * 2000f, py - NY * 2000f - AY * 2000f)
            cut.lineTo(px + NX * 2000f - AX * 2000f, py + NY * 2000f - AY * 2000f)
            cut.close()
            canvas.saveLayer(destination, null)
            canvas.drawBitmap(intact, null, destination, paint)
            canvas.save()
            canvas.clipPath(cut)
            paint.xfermode = replace
            canvas.drawBitmap(original, null, destination, paint)
            paint.xfermode = null
            canvas.restore()
            canvas.restore()
        }
        val centerProjection = SIZE / 2f * (AX + AY)
        val x = SIZE / 2f + AX * (threshold - centerProjection)
        val y = SIZE / 2f + AY * (threshold - centerProjection)
        val hasEffect = ms >= 115f && ms < 860f
        if (hasEffect) canvas.saveLayer(destination, effectPaint)
        if (ms >= 115f && ms < 810f) {
            val radius = 128f * (0.78f + 0.22f * sin(ms * 0.075f))
            paint.xfermode = screen
            paint.shader =
                RadialGradient(
                    x,
                    y,
                    radius,
                    intArrayOf(0xfafffff4.toInt(), 0xd9ffcf5c.toInt(), 0x52f86f0f, 0x00ff5f00),
                    floatArrayOf(0f, 0.12f, 0.42f, 1f),
                    Shader.TileMode.CLAMP,
                )
            canvas.drawCircle(x, y, radius, paint)
            paint.shader = null
            val energy = sin(PI * ((ms - 120f) / 610f).coerceIn(0f, 1f)).toFloat()
            if (energy > 0f) {
                repeat(15) { index ->
                    val n = ((index * 47) % 97) / 97f
                    val side = if (index % 2 == 1) 1f else -1f
                    val angle = atan2(NY * side, NX * side) + (n - 0.5f) * 1.1f
                    val distance = (10f + n * 39f) * energy * 4f
                    val drift = ((ms * (0.012f + n * 0.006f) + index * 2.1f) % 1f) * 32f
                    val radius = (if (index % 3 == 0) 1.8f else 0.9f) * 4f * (0.55f + energy)
                    paint.color = if (index % 4 == 0) 0xf2ffffff.toInt() else 0xd9ff9d27.toInt()
                    canvas.drawCircle(x + cos(angle) * (distance + drift), y + sin(angle) * (distance + drift), radius, paint)
                }
            }
        } else if (ms >= 810f && ms < 860f) {
            val alpha = ((1f - (ms - 810f) / 50f) * 0.22f * 255f).toInt()
            paint.shader = RadialGradient(x, y, 80f, intArrayOf((alpha shl 24) or 0x00ffb937, 0x00ff6400), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, 80f, paint)
        }
        if (hasEffect) {
            // Surface-only light: the intact face includes the aperture but excludes outer space.
            paint.shader = null
            paint.color = android.graphics.Color.WHITE
            paint.xfermode = mask
            canvas.drawBitmap(intact, null, destination, paint)
            canvas.restore()
        }
        canvas.restore()
    }
}

private fun smooth(
    start: Float,
    end: Float,
    value: Float,
): Float {
    val fraction = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return fraction * fraction * (3f - 2f * fraction)
}
