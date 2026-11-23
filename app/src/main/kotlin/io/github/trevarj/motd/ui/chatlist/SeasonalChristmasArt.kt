package io.github.trevarj.motd.ui.chatlist

import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.sqrt

private val feltTimes = floatArrayOf(0f, 0.21f, 0.41f, 0.66f, 1f)
private val feltEasing =
    arrayOf(
        CubicBezierEasing(0.4f, 0f, 0.6f, 1f),
        CubicBezierEasing(0.16f, 0f, 0.15f, 1f),
        CubicBezierEasing(0.28f, 0f, 0.45f, 1f),
        CubicBezierEasing(0.2f, 0f, 0.5f, 1f),
    )
private val feltTop = floatArrayOf(5.777f, 9.833f, 4.120f, 7.069f)
private val feltRight = floatArrayOf(20.1f, 20.3f, 24.2f, 19.7f)
private val pomTimes = floatArrayOf(0f, 0.18f, 0.265f, 0.37f, 0.43f, 0.58f, 0.76f, 1f)
private val pomEasing =
    arrayOf(
        feltEasing[0],
        CubicBezierEasing(0.45f, 0f, 0.75f, 0.7f),
        CubicBezierEasing(0.3f, 0.3f, 0.2f, 1f),
        CubicBezierEasing(0.4f, 0f, 0.65f, 0.65f),
        CubicBezierEasing(0.25f, 0.3f, 0.3f, 1f),
        CubicBezierEasing(0.3f, 0f, 0.5f, 1f),
        CubicBezierEasing(0.3f, 0f, 0.5f, 1f),
    )
private val pomX = floatArrayOf(20f, 20.5f, 22.5f, 25.9f, 24.1f, 19.5f, 20.6f, 20f)
private val pomY = floatArrayOf(16f, 18.6f, 13f, 8.1f, 10.9f, 18.1f, 15.5f, 16f)
private val brim = seasonalPath("M2.2 20.4 C3.5 19.1 5.1 19.7 6.3 19.2 C8.7 18.7 11.4 18.4 13.2 18.5 C15 18.1 16.8 18.3 17.3 19.5 C18.1 20.7 18.4 22.9 17.1 23.7 C16 24.3 14.6 23.8 13.1 24.2 C10.8 24.6 8.6 24.1 6.8 24.8 C4.8 25.3 2.7 25.6 2.1 24.3 C1.4 23.3 1.4 21.7 2.2 20.4Z")
private val nap = seasonalPath("M3.5 21 Q5 20.2 6 20.5 M8 20 Q9.4 19.4 10.5 19.7 M14 19.7 Q15.2 19.1 16 19.8")
private val pom = seasonalPath("M-.7 -2.6 Q.1 -3.1 .8 -2.55 Q2.2 -2.7 2.45 -1.4 Q3.3 -.7 2.7 .35 Q3 1.55 1.85 1.95 Q1.25 3.05 .15 2.65 Q-.95 3.2 -1.65 2.25 Q-2.95 2.2 -2.8 .95 Q-3.45 -.1 -2.6 -.85 Q-2.75 -2.2 -1.55 -2.15 Q-1.4 -2.8 -.7 -2.6Z")
private val wool =
    Brush.linearGradient(
        0f to Color.White,
        0.57f to Color(0xFFF3F3F3),
        1f to Color(0xFFC6C6C6),
        start = Offset(1.5f, 18.3f),
        end = Offset(2.26f, 25.6f),
    )
private val tuft =
    Brush.radialGradient(
        0f to Color.White,
        0.6f to Color(0xFFF5F5F5),
        1f to Color(0xFFC8C8C8),
        center = Offset(-0.94f, -1.42f),
        radius = 4.56f,
    )
private val brimStroke = Stroke(0.36f)
private val napStroke = Stroke(0.65f, cap = StrokeCap.Round)
private val pomStroke = Stroke(0.3f)

private fun mix(
    a: Float,
    b: Float,
    t: Float,
) = a + (b - a) * t

private fun segment(
    times: FloatArray,
    progress: Float,
): Int {
    var i = 0
    while (i < times.size - 2 && progress >= times[i + 1]) i++
    return i
}

internal class SeasonalChristmasArt {
    private val crown =
        SeasonalPathMorph(
            "M3 21 C4 15 7 6 12 5.8 C16 5.4 18.8 10.2 20.1 15.5 C18.3 16 15.9 14.4 14.3 12.4 C15.4 15.1 16.9 17.6 16.7 20.6 C12.7 22.2 7 22.7 3 21Z",
            "M3 21 C3.7 17.4 6.3 10.1 11 9.9 C16.4 9.2 20 14.1 20.3 18.1 C18.2 18.3 15.8 16.8 14.5 15 C15.4 16.7 16.7 18.2 16.7 20.6 C12.7 22.2 7 22.7 3 21Z",
            "M3 21 C5.7 13.2 9.7 5 15.7 4.2 C19 3.7 22.4 5.6 24.2 8.4 C22.3 9.6 19.6 8.8 17.1 7.8 C16.3 11.2 16.7 16.8 16.7 20.6 C12.7 22.2 7 22.7 3 21Z",
            "M3 21 C3.6 15 7 7.5 11.8 7.1 C16.2 6.6 18.7 12.2 19.7 16.7 C17.7 16.9 15.6 15 14.1 13.2 C15.1 15.7 16.6 18 16.7 20.6 C12.7 22.2 7 22.7 3 21Z",
        )
    private val fold =
        SeasonalPathMorph(
            "M12 6.7 C15.9 5.9 19 10.6 20.1 15.5 C18.3 16 15.9 14.4 14.3 12.4 C13.4 10.2 14.2 8.1 12 6.7Z",
            "M11.4 10.7 C15.6 9.8 19.8 14.5 20.3 18.1 C18.2 18.3 15.8 16.8 14.5 15 C13.5 13.5 14 11.5 11.4 10.7Z",
            "M15.1 5.1 C18.5 3.7 22.4 5.7 24.2 8.4 C22.3 9.6 19.6 8.8 17.1 7.8 C17.5 6.5 16.8 5.5 15.1 5.1Z",
            "M11.5 8 C15.9 7.2 18.7 12.5 19.7 16.7 C17.7 16.9 15.6 15 14.1 13.2 C13.2 11.4 13.8 9.5 11.5 8Z",
        )
    private val crease =
        SeasonalPathMorph(
            "M5.2 18.7 C6.5 14.8 8.6 10.1 10.8 9 C8.3 12.1 7.8 16.2 5.2 18.7Z",
            "M5.2 19.6 C6.1 16.9 7.9 13.1 10 12.4 C8 14.6 7.6 17.9 5.2 19.6Z",
            "M5.7 18.5 C7.7 13.1 11.2 8.1 14.3 6.9 C11.3 9.5 9.2 15.8 5.7 18.5Z",
            "M5.2 19 C6.4 15.6 8.4 11 10.5 10.4 C8.6 12.6 7.7 16.7 5.2 19Z",
        )
    private val shader =
        LinearGradient(
            0f,
            0f,
            0.82f,
            0.85f,
            intArrayOf(0xFFFF745D.toInt(), 0xFFDE4149.toInt(), 0xFFA21836.toInt(), 0xFF76192F.toInt()),
            floatArrayOf(0f, 0.34f, 0.76f, 1f),
            Shader.TileMode.CLAMP,
        )
    private val matrix = Matrix()
    private val felt = ShaderBrush(shader)

    fun drawHat(
        scope: DrawScope,
        anchor: Offset,
        unit: Float,
        elapsedMillis: Float?,
    ) {
        val time = elapsedMillis ?: 0f
        val progress = if (time >= 540f) 0f else time / 540f
        val from = segment(feltTimes, progress)
        val to = (from + 1) % 4
        val t = feltEasing[from].transform((progress - feltTimes[from]) / (feltTimes[from + 1] - feltTimes[from]))
        val top = mix(feltTop[from], feltTop[to], t)
        matrix.setScale(mix(feltRight[from], feltRight[to], t) - 3f, 22.06f - top)
        matrix.postTranslate(3f, top)
        shader.setLocalMatrix(matrix)
        val p = if (time >= 660f) 0f else time / 660f
        val i = segment(pomTimes, p)
        val u = pomEasing[i].transform((p - pomTimes[i]) / (pomTimes[i + 1] - pomTimes[i]))
        scope.withTransform({
            translate(anchor.x, anchor.y + 2.4f * unit)
            scale(unit, unit, Offset.Zero)
            rotate(22f, Offset.Zero)
            translate(-9.6f, -23.1f)
        }) {
            drawPath(crown.path(from, to, t), felt)
            drawPath(fold.path(from, to, t), Color(0xFF861B35))
            drawPath(crease.path(from, to, t), Color(0xFFED665C))
            drawPath(brim, wool)
            drawPath(brim, Color(0xFF929292), style = brimStroke)
            drawPath(nap, Color.White, style = napStroke)
            translate(mix(pomX[i], pomX[i + 1], u), mix(pomY[i], pomY[i + 1], u)) {
                drawPath(pom, tuft)
                drawPath(pom, Color(0xFF979797), style = pomStroke)
            }
        }
    }
}

private val fieldColumns = floatArrayOf(0.035f, 0.10f, 0.17f, 0.24f, 0.30f, 0.39f, 0.46f, 0.53f, 0.61f, 0.68f, 0.76f, 0.83f, 0.89f, 0.96f)
private val plumeSpread = floatArrayOf(12f, 26f, 48f, 68f)
private val crystalPaths =
    arrayOf(
        seasonalPath("M0 -3.6V3.6 M-1.18 -2.65L0 -1.65L1.18 -2.65 M-1.18 2.65L0 1.65L1.18 2.65"),
        seasonalPath("M0 -3.8V3.8 M-.9 -2.7L0 -2L.9 -2.7 M-1.35 -1.7L0 -.85L1.35 -1.7 M-.9 2.7L0 2L.9 2.7"),
        seasonalPath("M0 -3.6L.68 -1.24L3.1 -1.8L1.42 0L3.1 1.8L.68 1.24L0 3.6L-.68 1.24L-3.1 1.8L-1.42 0L-3.1 -1.8L-.68 -1.24Z"),
    )
private val crystalStroke = Stroke(0.81f, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun cubic(
    a: Float,
    b: Float,
    c: Float,
    t: Float,
): Float {
    val u = 1f - t
    return t * (3f * u * u * a + 3f * u * t * b + t * t * c)
}

private fun flightLengths(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    x3: Float,
    y3: Float,
): FloatArray {
    val lengths = FloatArray(33)
    var x = 0f
    var y = 0f
    for (i in 1..lengths.lastIndex) {
        val t = i.toFloat() / lengths.lastIndex
        val nextX = cubic(x1, x2, x3, t)
        val nextY = cubic(y1, y2, y3, t)
        val dx = nextX - x
        val dy = nextY - y
        lengths[i] = lengths[i - 1] + sqrt(dx * dx + dy * dy)
        x = nextX
        y = nextY
    }
    return lengths
}

private val fieldLengths = arrayOf(-9f, 12f).map { drift -> flightLengths(drift, 16f, -drift, 32f, drift * 0.6f, 64f) }
private val plumeLengths =
    Array(plumeSpread.size) { i ->
        val spread = plumeSpread[i]
        flightLengths(spread * 0.3f, -12f - i * 2f, spread * 0.85f, 4f, spread, 42f)
    }

private fun paced(
    lengths: FloatArray,
    progress: Float,
): Float {
    val target = lengths.last() * progress
    val found = lengths.binarySearch(target)
    if (found >= 0) return found.toFloat() / lengths.lastIndex
    val hi = -found - 1
    val lo = hi - 1
    return (lo + (target - lengths[lo]) / (lengths[hi] - lengths[lo])) / lengths.lastIndex
}

private fun DrawScope.crystal(
    i: Int,
    x: Float,
    y: Float,
    scale: Float,
    rotation: Float,
    progress: Float,
    still: Boolean,
    dark: Boolean,
) {
    val ice = if (dark) Color(0xFFE5F9FF) else Color(0xFF375E73)
    val blue = if (dark) Color(0xFFA6DBEE) else Color(0xFF55738D)
    val color = if (i % 2 == 0) ice else blue
    val alpha = if (still) 1f else minOf(progress / 0.08f, 1f, (1f - progress) / 0.24f)
    val shape = i % 3
    val pixelsPerDp = density
    withTransform({
        translate(x * pixelsPerDp, y * pixelsPerDp)
        scale(scale * pixelsPerDp, scale * pixelsPerDp, Offset.Zero)
        if (!still) rotate(rotation * progress, Offset.Zero)
    }) {
        if (shape == 2) {
            drawPath(crystalPaths[shape], color, alpha)
        } else {
            for (axis in 0..2) {
                rotate(60f * axis, Offset.Zero) {
                    drawPath(crystalPaths[shape], color, alpha, style = crystalStroke)
                }
            }
        }
    }
}

internal fun DrawScope.drawSeasonalSnow(
    anchor: Offset,
    elapsedMillis: Float,
    dark: Boolean,
) {
    val still = elapsedMillis.isInfinite()
    val width = size.width / density
    val height = size.height / density
    val direction = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
    val pomX = anchor.x / density + direction * 16.68f
    val pomY = anchor.y / density - 3.92f
    for (i in plumeSpread.indices) {
        val progress = (elapsedMillis - (205 + i * 28)) / (925 + i * 87)
        if (!still && (progress <= 0f || progress >= 1f)) continue
        val spread = plumeSpread[i] * direction
        val t = if (still) 0f else paced(plumeLengths[i], progress)
        val x = pomX + if (still) spread * 0.8f else cubic(spread * 0.3f, spread * 0.85f, spread, t)
        val y = pomY + if (still) 12f + i * 6f else cubic(-12f - i * 2f, 4f, height + 2f - pomY, t)
        crystal(i, x, y, 0.74f + i * 0.10f, if (i % 2 == 0) -58f else 44f, progress, still, dark)
    }
    for (i in fieldColumns.indices) {
        val progress = (elapsedMillis - (290 + i % 5 * 57 + i * 6)) / (900 + i % 4 * 86)
        if (!still && (progress <= 0f || progress >= 1f)) continue
        val drift = if (i % 3 == 0) -9f else 12f
        val t = if (still) 0f else paced(fieldLengths[if (i % 3 == 0) 0 else 1], progress)
        val x = fieldColumns[i] * width + if (still) drift * 0.35f else cubic(drift, -drift, drift * 0.6f, t)
        val y = if (still) 10f + i % 3 * 14f else -4f + cubic(16f, 32f, height + 8f, t)
        crystal(i, x, y, 0.58f + i % 4 * 0.14f, if (i % 2 == 0) -76f else 58f, progress, still, dark)
    }
}
