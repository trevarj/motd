package io.github.trevarj.motd.ui.chatlist

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate

private class BatPaint(
    val edge: Color,
    val wing: Color,
    val plane: Color,
    val rib: Color,
    val far: Color,
    val fur: Color,
    val ear: Color,
    val claw: Color,
)

private val darkBat =
    BatPaint(
        edge = Color(0xFFB29BBD),
        wing = Color(0xFF51396A),
        plane = Color(0xFF967E9F),
        rib = Color(0xFFD2B6C6),
        far = Color(0xFF624E71),
        fur = Color(0xFF816984),
        ear = Color(0xFFC59CA7),
        claw = Color(0xFFD9BAAB),
    )

private val lightBat =
    BatPaint(
        edge = Color(0xFF39273F),
        wing = Color(0xFF4B325F),
        plane = Color(0xFF8A7196),
        rib = Color(0xFFB899AD),
        far = Color(0xFF73657C),
        fur = Color(0xFF70576F),
        ear = Color(0xFFC798A0),
        claw = Color(0xFF694956),
    )

private val batEasing = CubicBezierEasing(.32f, .05f, .26f, .99f)
private val wingTimes = floatArrayOf(0f, .19f, .37f, .59f, .75f, .89f, 1f)
private val farTimes = floatArrayOf(0f, .14f, .32f, .55f, .71f, .85f, 1f)
private val bodyTimes = floatArrayOf(0f, .21f, .40f, .58f, .75f, .88f, 1f)
private val eyeTimes = floatArrayOf(0f, .12f, .25f, .48f, .76f, .90f, 1f)
private val bodyX = floatArrayOf(0f, 0f, 2f, 5f, 1f, 0f, 0f)
private val bodyY = floatArrayOf(0f, 1.4f, -1.6f, -2f, -1f, .7f, 0f)
private val eyeY = floatArrayOf(1f, .16f, 1.16f, 1.10f, .91f, 1f, 1f)

private fun FloatArray.phase(time: Float): Float {
    if (time == 0f) return 0f
    var i = 0
    while (time > this[i + 1]) i++
    return i + batEasing.transform((time - this[i]) / (this[i + 1] - this[i]))
}

private fun FloatArray.at(phase: Float): Float {
    val i = phase.toInt().coerceAtMost(lastIndex - 1)
    return this[i] + (this[i + 1] - this[i]) * (phase - i)
}

private fun SeasonalPathMorph.at(phase: Float) = phase.toInt().let { path(it % 6, (it + 1) % 6, phase - it) }

private fun batStroke(width: Float) = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

private val fineStroke = batStroke(.65f)
private val bodyStroke = batStroke(.8f)
private val wingStroke = batStroke(.72f)
private val eyeStroke = batStroke(.7f)
private val footStroke = batStroke(1.15f)
private val eyeWhite = Color(0xFFFFF4DB)
private val eyeEdge = Color(0xFF392E40)
private val pupil = Color(0xFF302536)
private val muzzle = Color(0xFF443044)
private val batFur = seasonalPath("M14.8 12 Q13.4 9.1 13.9 5.9 Q17 6.4 18.9 9.1 Q21 8.4 23.1 9.1 Q24.7 6.1 28.3 5.4 Q28.4 9.7 26.6 12 Q29.8 14.9 29 18.9 Q28.5 22 25.7 23.1 L24.2 22.5 L23.3 23.4 Q20.2 22.8 19.2 19.6 Q14.8 19.3 13.9 16.2 Q13.2 14 14.8 12Z")
private val batEars = seasonalPath("M15 7.4 Q17.1 8.1 18 10.1 L15.7 11.2 Q15.1 9.5 15 7.4Z M24 10.4 Q25.3 8.1 27.1 7.2 Q26.9 9.2 25.7 11.1Z")
private val batNose = seasonalPath("M17.9 17.2 Q19.3 16.6 20.5 17.1 Q19.7 18.9 18.9 18.3Z")

internal class SeasonalHalloweenArt {
    private val nearWing =
        SeasonalPathMorph(
            "M25 16 Q28 9 32 9 Q38 12 41 24 Q37 20 36 22 Q32 19 31 22 Q28 19 26 21 Q23 19 25 16Z",
            "M25 17 Q27 12 31 12 Q35 15 37 24 Q34 21 33 23 Q30 20 29 23 Q27 21 25 22 Q23 20 25 17Z",
            "M25 16 Q32 7 44 7 Q54 9 61 23 Q54 17 52 24 Q45 16 42 23 Q36 16 31 22 Q26 20 25 16Z",
            "M25 15 Q29 8 35 6 Q38 5 40 6 Q38 8 37 9 Q34 9 33 12 Q30 12 28 16 Q26 18 25 15Z",
            "M25 16 Q31 6 38 6 Q43 8 47 19 Q42 16 41 19 Q36 15 34 19 Q30 17 28 21 Q24 20 25 16Z",
            "M25 16 Q27 11 31 10 Q35 14 38 25 Q35 22 34 23 Q30 20 30 23 Q27 20 26 22 Q23 19 25 16Z",
        )

    private val nearPlane =
        SeasonalPathMorph(
            "M26 17 Q30 12 32 10 Q32 15 36 21 Q32 18 31 21 Q28 18 26 20Z",
            "M26 18 Q28 14 31 13 Q31 17 33 22 Q30 20 29 22 Q27 20 26 21Z",
            "M27 16 Q35 10 44 8 Q44 15 51 22 Q45 16 42 22 Q36 16 31 21Z",
            "M26 15 Q30 9 35 7 Q34 9 33 11 Q30 12 28 15 Q27 16 26 16Z",
            "M26 17 Q32 10 38 7 Q38 13 41 18 Q36 15 34 18 Q30 17 28 20Z",
            "M26 17 Q28 13 31 11 Q31 16 34 22 Q31 20 30 22 Q28 19 26 21Z",
        )

    private val nearRib =
        SeasonalPathMorph(
            "M32 10 Q34 15 39 21",
            "M31 13 Q32 17 36 22",
            "M44 8 Q47 16 52 23",
            "M35 7 Q35 8 34 9",
            "M38 7 Q40 12 45 17",
            "M31 11 Q33 17 37 23",
        )

    private val farWing =
        SeasonalPathMorph(
            "M25 16 Q22 10 18 10 Q13 12 10 20 Q14 17 15 19 Q18 16 20 19 Q22 16 24 19Z",
            "M25 17 Q23 13 19 13 Q16 15 14 21 Q17 19 18 20 Q20 18 21 20 Q23 18 24 20Z",
            "M25 15 Q18 7 13 8 Q7 11 1 18 Q7 16 8 19 Q13 15 16 19 Q20 15 24 18Z",
            "M25 15 Q20 8 18 6 Q17 6 16 7 Q18 8 18 9 Q20 9 21 12 Q23 12 24 17Z",
            "M25 15 Q19 7 16 7 Q11 8 8 14 Q12 12 13 15 Q16 13 18 16 Q21 14 24 18Z",
            "M25 17 Q22 11 19 11 Q15 13 12 21 Q15 18 16 20 Q19 17 20 20 Q22 17 24 19Z",
        )

    private val talons =
        SeasonalPathMorph(
            "M22 20 Q21 22 21 24 l-2 .4 M25 21 Q25 23 24 24 l-1 1",
            "M22 21 Q20 22 21 24 l-2 .4 M25 21 Q24 23 24 24 l-1 1",
            "M24 18 Q24 21 23 22 l-2 .5 M27 18 Q27 20 26 21 l-1 1",
            "M27 17 Q28 19 27 20 l-1 .6 M30 17 Q31 19 30 20 l-1 1",
            "M23 19 Q21 21 21 24 l-2 .4 M26 19 Q26 21 25 23 l-1 1",
            "M22 21 Q20 23 21 24 l-2 .4 M25 21 Q25 23 24 24 l-1 1",
        )

    fun draw(
        scope: DrawScope,
        anchor: Offset,
        unit: Float,
        elapsedMillis: Float?,
        dark: Boolean,
    ) {
        val still = elapsedMillis == Float.POSITIVE_INFINITY
        val time = if (elapsedMillis == null || still || elapsedMillis >= 960f) 0f else elapsedMillis.coerceAtLeast(0f) / 960f
        val wing = if (still) 2f else wingTimes.phase(time)
        val far = if (still) 2f else farTimes.phase(time)
        val body = if (still) 2f else bodyTimes.phase(time)
        val eyes = if (still) 1f else eyeY.at(eyeTimes.phase(time))
        val paint = if (dark) darkBat else lightBat
        with(scope) {
            translate(anchor.x - 21f * unit, anchor.y - 24f * unit) {
                scale(unit, unit, Offset.Zero) {
                    translate(bodyX.at(body), bodyY.at(body)) {
                        val back = farWing.at(far)
                        drawPath(back, paint.far)
                        drawPath(back, paint.edge, style = fineStroke)
                        drawPath(batFur, paint.fur)
                        drawPath(batFur, paint.edge, style = bodyStroke)
                        drawPath(batEars, paint.ear)
                        val front = nearWing.at(wing)
                        drawPath(front, paint.wing)
                        drawPath(front, paint.edge, style = wingStroke)
                        drawPath(nearPlane.at(wing), paint.plane)
                        drawPath(nearRib.at(wing), paint.rib, style = fineStroke)
                        translate(19.7f, 13.7f) {
                            scale(1f, eyes, Offset.Zero) {
                                eye(-3.05f, .4f, 2.65f, 3.05f, -9f)
                                eye(3.1f, -.25f, 2.95f, 3.55f, 8f)
                                drawOval(pupil, Offset(-5f, -.92f), Size(2.3f, 3.24f))
                                drawOval(pupil, Offset(.9f, -1.9f), Size(2.6f, 3.7f))
                            }
                        }
                        drawPath(batNose, muzzle)
                    }
                    drawPath(talons.at(body), paint.claw, style = footStroke)
                }
            }
        }
    }

    private fun DrawScope.eye(
        x: Float,
        y: Float,
        rx: Float,
        ry: Float,
        degrees: Float,
    ) {
        rotate(degrees, Offset(x, y)) {
            val corner = Offset(x - rx, y - ry)
            val size = Size(rx * 2f, ry * 2f)
            drawOval(eyeWhite, corner, size)
            drawOval(eyeEdge, corner, size, style = eyeStroke)
        }
    }
}
