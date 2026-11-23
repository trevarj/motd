package io.github.trevarj.motd.ui.chatlist

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform

internal class SeasonalNewYearArt {
    private class Branch(
        val color: Int,
        width: Float,
        val delay: Float,
        data: String,
    ) {
        val path = seasonalPath(data)
        val measure = PathMeasure().apply { setPath(path, false) }
        val length = measure.length
        val stroke = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    private class Cinder(
        val x: Float,
        val y: Float,
        val angle: Float,
        val drift: Float,
        val delay: Float,
        val color: Int,
    )

    private val segment = Path()
    private val branches =
        arrayOf(
            Branch(PINK, 1.45f, 120f, "M0 0 Q-1 -5 -6 -9 Q-9 -11 -11 -10.5"),
            Branch(GOLD, 1f, 250f, "M-4.4 -7.3 Q-5.2 -11 -8 -13.4"),
            Branch(GOLD, 1.55f, 80f, "M0 0 C1 -6.7 -1 -12.8 -4.1 -16.3"),
            Branch(IVORY, 0.85f, 245f, "M.1 -7.8 Q1.6 -11.9 .5 -15"),
            Branch(TEAL, 1.65f, 110f, "M.2 -.2 C3.5 -10.3 6.7 -15.6 11.7 -16.8"),
            Branch(LAVENDER, 1f, 230f, "M4.5 -10.5 Q4.4 -15.4 6.7 -18.1"),
            Branch(TEAL, 1f, 275f, "M7.2 -13.7 Q10.6 -15 13.4 -14.5"),
            Branch(LAVENDER, 1.65f, 165f, "M.2 0 C7.7 -11.2 17 -15.8 24 -11.6 Q26 -10.3 27.1 -7.9"),
            Branch(PINK, 1.2f, 310f, "M12 -10.6 Q17 -16.5 21.2 -15.4"),
            Branch(LAVENDER, 1f, 340f, "M18 -13 Q22.7 -13.2 25.7 -12.1"),
            Branch(PINK, 1.55f, 190f, "M0 0 Q14 -11 25.4 -6.7 Q29.8 -4.7 31.6 -.6"),
            Branch(GOLD, 1f, 330f, "M17 -7.6 Q22 -10 25 -9.4"),
            Branch(GOLD, 1.4f, 235f, "M.5 .1 Q11 -4.8 16.4 -1.4 Q19.1 .5 20.5 3.2"),
            Branch(TEAL, 1f, 360f, "M11.2 -3 Q14.5 -4.1 16 -3.8"),
            Branch(LAVENDER, 1.2f, 180f, "M-.6 .1 Q-5.4 -4.4 -10.2 -3.7"),
        )

    fun draw(
        scope: DrawScope,
        anchor: Offset,
        unit: Float,
        elapsedMillis: Float?,
        dark: Boolean,
    ) {
        val colors = if (dark) night else day
        with(scope) {
            withTransform({
                translate(anchor.x, anchor.y)
                scale(unit, unit, Offset.Zero)
            }) {
                if (elapsedMillis != null) {
                    withTransform({
                        translate(2.5f, -4.3f)
                        scale(1f, 0.67f, Offset.Zero)
                    }) {
                        drawBurst(elapsedMillis, colors)
                    }
                }
                drawPath(stem, colors[COPPER], style = stemStroke)
                withTransform({ translate(2.5f, -4.3f) }) {
                    drawPath(rays, colors[GOLD], style = rayStroke)
                    drawPath(star, colors[IVORY])
                    drawPath(star, colors[COPPER], style = starStroke)
                    drawCircle(colors[COPPER], 0.67f, Offset(-5.8f, -3.7f))
                    drawCircle(colors[COPPER], 0.75f, Offset(4.1f, -5f))
                    drawCircle(colors[COPPER], 0.6f, Offset(6.2f, 0.5f))
                }
            }
        }
    }

    private fun DrawScope.drawBurst(
        time: Float,
        colors: Array<Color>,
    ) {
        val still = time == Float.POSITIVE_INFINITY
        for (branch in branches) {
            if (still) {
                drawPath(branch.path, colors[branch.color], style = branch.stroke)
                continue
            }
            val t = (time - branch.delay) / 1100f
            if (t <= 0f || t >= 1f) continue
            val trace = drawEase.transform(if (t < 0.5f) t * 2f else (t - 0.5f) * 2f)
            val start = if (t < 0.5f) 0f else trace * 0.98f
            val end = if (t < 0.5f) trace else 1f
            val alpha =
                when {
                    t < 0.1f -> drawEase.transform(t / 0.1f)
                    t < 0.5f -> 1f
                    else -> 1f - trace
                }
            segment.reset()
            if (branch.measure.getSegment(start * branch.length, end * branch.length, segment, true)) {
                drawPath(segment, colors[branch.color], alpha = alpha, style = branch.stroke)
            }
        }
        for (cinder in cinders) {
            val t = if (still) 0f else (time - cinder.delay) / 850f
            if (!still && (t <= 0f || t >= 1f)) continue
            val travel = if (t < 0.48f) t / 0.48f else (t - 0.48f) / 0.52f
            val dx = cinder.drift * (if (t < 0.48f) 0.66f * travel else 0.66f + 0.34f * travel)
            val dy = if (t < 0.48f) -travel else -1f + 7f * travel
            val alpha =
                when {
                    still -> 1f
                    t < 0.14f -> t / 0.14f
                    else -> (1f - t) / 0.86f
                }
            withTransform({
                translate(cinder.x + dx, cinder.y + dy)
                rotate(cinder.angle, Offset.Zero)
            }) {
                drawPath(nib, colors[cinder.color], alpha = alpha)
            }
        }
        bloom(time - 480f, 12f, -16f, colors[TEAL], false)
        bloom(time - 670f, 32f, -1f, colors[PINK], false)
        bloom(time, 0f, 0f, colors[GOLD], true)
    }

    private fun DrawScope.bloom(
        time: Float,
        x: Float,
        y: Float,
        color: Color,
        center: Boolean,
    ) {
        val t = time / 1150f
        val size: Float
        val alpha: Float
        when {
            time == Float.POSITIVE_INFINITY -> {
                size = 1f
                alpha = 1f
            }

            t <= 0f || t >= 1f -> {
                return
            }

            t < 0.18f -> {
                alpha = cssEase.transform(t / 0.18f)
                size = 0.38f + 0.72f * alpha
            }

            t < 0.42f -> {
                val f = cssEase.transform((t - 0.18f) / 0.24f)
                size = 1.1f - 0.27f * f
                alpha = 1f - 0.05f * f
            }

            else -> {
                val f = cssEase.transform((t - 0.42f) / 0.58f)
                size = 0.83f - 0.48f * f
                alpha = 0.95f * (1f - f)
            }
        }
        withTransform({
            translate(x, y)
            scale(size, size, Offset.Zero)
        }) {
            if (center) drawCircle(color, 3.6f, Offset.Zero, alpha = alpha) else drawPath(salute, color, alpha = alpha, style = saluteStroke)
        }
    }

    private companion object {
        const val COPPER = 0
        const val GOLD = 1
        const val IVORY = 2
        const val TEAL = 3
        const val PINK = 4
        const val LAVENDER = 5
        val night = arrayOf(Color(0xFFED8348), Color(0xFFFFBF62), Color(0xFFFFF0BF), Color(0xFF57E5CE), Color(0xFFFF80AC), Color(0xFFBAA3FF))
        val day = arrayOf(Color(0xFFA74419), Color(0xFFC77313), Color(0xFFFFDB89), Color(0xFF007E78), Color(0xFFC52B69), Color(0xFF7954BD))
        val drawEase = CubicBezierEasing(0.18f, 0.61f, 0.28f, 0.98f)
        val cssEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
        val stem = seasonalPath("M0 1.1L2.5 -4.3")
        val rays = seasonalPath("M-1.8 -.6l-3.4 -1.3M-1 -1.6l-1.2 -2.7M.6 -2l1.3 -4M2 -1l3.2 -1.6M2 .8l2.1 1.2M.7 2l.5 1.7")
        val star = seasonalPath("M-.5 -2.8L.7 -1.1 2.7 -.9 1.4 .5 1.4 2.4 -.3 1.2 -2.4 1.6 -1.5 -.3 -2.5 -1.7 -.7 -1.2Z")
        val nib = seasonalPath("M0 .7C-1.1 -.3 -.68 -1.4 .35 -3.1C.77 -1.65 .91 -.32 0 .7Z")
        val salute = seasonalPath("M-1 -.7l-2 -1.4M-.5 -1.5l-.7 -2.3M1 -.9l2 -1.7M1.4 .3l2.7 .6M.7 1.4l.9 2")
        val stemStroke = Stroke(1.55f, cap = StrokeCap.Round)
        val rayStroke = Stroke(1.25f, cap = StrokeCap.Round)
        val starStroke = Stroke(0.65f)
        val saluteStroke = Stroke(1.2f, cap = StrokeCap.Round)
        val cinders =
            arrayOf(
                Cinder(-11f, -10.5f, -40f, -3f, 490f, PINK),
                Cinder(-8f, -13.4f, -22f, -1.6f, 535f, GOLD),
                Cinder(-4.1f, -16.3f, -18f, -1.3f, 460f, GOLD),
                Cinder(6.7f, -18.1f, 20f, 1.8f, 490f, LAVENDER),
                Cinder(11.7f, -16.8f, 55f, 2.6f, 530f, TEAL),
                Cinder(21.2f, -15.4f, 67f, 3.5f, 620f, PINK),
                Cinder(27.1f, -7.9f, 126f, 3.3f, 605f, LAVENDER),
                Cinder(31.6f, -0.6f, 144f, 2.9f, 670f, PINK),
                Cinder(20.5f, 3.2f, 145f, 2.3f, 685f, GOLD),
            )
    }
}
