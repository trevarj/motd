package io.github.trevarj.motd.ui.chatlist

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform

internal class SeasonalPaschaArt {
    private class Highlight(
        val delay: Float,
        val duration: Float,
        data: String,
    ) {
        val path = seasonalPath(data)
        val measure = PathMeasure().apply { setPath(path, false) }
        val length = measure.length
    }

    private class Palette(
        val metal: Color,
        val edge: Color,
        val cut: Color,
        val light: Color,
    )

    private val segment = Path()
    private val highlights =
        arrayOf(
            Highlight(0f, 590f, "M5.4 21.3V.8"),
            Highlight(240f, 370f, "M2.65 3.8H8.35"),
            Highlight(490f, 460f, "M.7 9.2H10.3"),
            Highlight(770f, 470f, "M3.4 15.08L7.6 17.12"),
        )

    fun draw(
        scope: DrawScope,
        topLeft: Offset,
        width: Float,
        height: Float,
        elapsedMillis: Float?,
        dark: Boolean,
    ) {
        val palette = if (dark) night else day
        with(scope) {
            withTransform({
                translate(topLeft.x, topLeft.y)
                scale(width / 11f, height / 22f, Offset.Zero)
            }) {
                drawPath(cross, palette.metal)
                drawPath(edge, palette.edge, style = edgeStroke)
                drawPath(cut, palette.cut, style = cutStroke)
                if (elapsedMillis != null &&
                    ((elapsedMillis > 0f && elapsedMillis < 1240f) || elapsedMillis == Float.POSITIVE_INFINITY)
                ) {
                    clipPath(cross) { drawHighlights(elapsedMillis, palette.light) }
                }
            }
        }
    }

    private fun DrawScope.drawHighlights(
        time: Float,
        color: Color,
    ) {
        for (highlight in highlights) {
            if (time == Float.POSITIVE_INFINITY) {
                drawPath(highlight.path, color, style = lightStroke)
                continue
            }
            val t = (time - highlight.delay) / highlight.duration
            if (t <= 0f || t >= 1f) continue
            val start = if (t <= 0.56f) 0f else ease.transform((t - 0.56f) / 0.44f)
            val end = if (t < 0.56f) ease.transform(t / 0.56f) else 1f
            val alpha =
                when {
                    t < 0.16f -> ease.transform(t / 0.16f)
                    t <= 0.56f -> 1f
                    else -> 1f - start
                }
            segment.rewind()
            if (highlight.measure.getSegment(start * highlight.length, end * highlight.length, segment, true)) {
                drawPath(segment, color, alpha = alpha, style = lightStroke)
            }
        }
    }

    private companion object {
        val cross =
            seasonalPath("M4.2 0H6.8V2.8H9V5H6.8V8H11V10.6H6.8V15.5L8 16.08V18.68L6.8 18.1V22H4.2V16.85L3 16.27V13.67L4.2 14.25V10.6H0V8H4.2V5H2V2.8H4.2Z")
        val edge = seasonalPath("M4.65 .4v2.8H2.4M4.65 5.4v3H.5M3.3 14.25l4.4 2.12")
        val cut = seasonalPath("M6.35 .45v2.8h2.2M10.6 10.15H6.35v5.5M6.35 18.45v3.1")
        val edgeStroke = Stroke(0.52f)
        val cutStroke = Stroke(0.55f)
        val lightStroke = Stroke(1.35f, cap = StrokeCap.Round)
        val ease = CubicBezierEasing(0.31f, 0.12f, 0.34f, 1f)
        val night = Palette(Color(0xFFD5AC71), Color(0xFFF1D9AD), Color(0xFF936641), Color(0xFFFFF5C6))
        val day = Palette(Color(0xFF82542C), Color(0xFFAF8048), Color(0xFF603E2B), Color(0xFFFFE3A0))
    }
}
