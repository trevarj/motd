// Native geometry converted from lucide-static 1.69.0 (ISC; Feather-derived icons MIT).
// Source SVG subset/digests: third_party/lucide-static/provenance.json.
// Full upstream notice: app/src/main/assets/lucide-static-LICENSE.txt.
package io.github.trevarj.motd.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// ponytail: only the used SVG subset, converted ahead of time; no SVG parser or icon-pack runtime.
internal enum class LucideIcon(
    private val draw: ImageVector.Builder.() -> Unit,
) {
    ARCHIVE({
        stroke {
            moveTo(3.0f, 3.0f)
            horizontalLineToRelative(18.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, 1.0f)
            verticalLineToRelative(3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, 1.0f)
            horizontalLineToRelative(-18.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, -1.0f)
            verticalLineToRelative(-3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, -1.0f)
            close()
        }
        stroke {
            moveTo(4.0f, 8.0f)
            verticalLineToRelative(11.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.0f, 2.0f)
            horizontalLineToRelative(12.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.0f, -2.0f)
            verticalLineTo(8.0f)
        }
        stroke {
            moveTo(10.0f, 12.0f)
            horizontalLineToRelative(4.0f)
        }
    }),
    ARCHIVE_RESTORE({
        stroke {
            moveTo(3.0f, 3.0f)
            horizontalLineToRelative(18.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, 1.0f)
            verticalLineToRelative(3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, 1.0f)
            horizontalLineToRelative(-18.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, -1.0f)
            verticalLineToRelative(-3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, -1.0f)
            close()
        }
        stroke {
            moveTo(4.0f, 8.0f)
            verticalLineToRelative(11.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.0f, 2.0f)
            horizontalLineToRelative(2.0f)
        }
        stroke {
            moveTo(20.0f, 8.0f)
            verticalLineToRelative(11.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineToRelative(-2.0f)
        }
        stroke {
            moveToRelative(9.0f, 15.0f)
            lineToRelative(3.0f, -3.0f)
            lineToRelative(3.0f, 3.0f)
        }
        stroke {
            moveTo(12.0f, 12.0f)
            verticalLineToRelative(9.0f)
        }
    }),
    ARROW_DOWN({
        stroke {
            moveTo(12.0f, 5.0f)
            verticalLineToRelative(14.0f)
        }
        stroke {
            moveToRelative(19.0f, 12.0f)
            lineToRelative(-7.0f, 7.0f)
            lineToRelative(-7.0f, -7.0f)
        }
    }),
    ARROW_DOWN_UP({
        stroke {
            moveToRelative(3.0f, 16.0f)
            lineToRelative(4.0f, 4.0f)
            lineToRelative(4.0f, -4.0f)
        }
        stroke {
            moveTo(7.0f, 20.0f)
            verticalLineTo(4.0f)
        }
        stroke {
            moveToRelative(21.0f, 8.0f)
            lineToRelative(-4.0f, -4.0f)
            lineToRelative(-4.0f, 4.0f)
        }
        stroke {
            moveTo(17.0f, 4.0f)
            verticalLineToRelative(16.0f)
        }
    }),
    ARROW_LEFT({
        stroke {
            moveToRelative(12.0f, 19.0f)
            lineToRelative(-7.0f, -7.0f)
            lineToRelative(7.0f, -7.0f)
        }
        stroke {
            moveTo(19.0f, 12.0f)
            horizontalLineTo(5.0f)
        }
    }),
    ARROW_UP({
        stroke {
            moveToRelative(5.0f, 12.0f)
            lineToRelative(7.0f, -7.0f)
            lineToRelative(7.0f, 7.0f)
        }
        stroke {
            moveTo(12.0f, 19.0f)
            verticalLineTo(5.0f)
        }
    }),
    AT_SIGN({
        stroke {
            moveTo(16.0f, 12.0f)
            arcToRelative(4.0f, 4.0f, 0.0f, true, false, -8.0f, 0.0f)
            arcToRelative(4.0f, 4.0f, 0.0f, true, false, 8.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(16.0f, 8.0f)
            verticalLineToRelative(5.0f)
            arcToRelative(3.0f, 3.0f, 0.0f, false, false, 6.0f, 0.0f)
            verticalLineToRelative(-1.0f)
            arcToRelative(10.0f, 10.0f, 0.0f, true, false, -4.0f, 8.0f)
        }
    }),
    BAN({
        stroke {
            moveTo(22.0f, 12.0f)
            arcToRelative(10.0f, 10.0f, 0.0f, true, false, -20.0f, 0.0f)
            arcToRelative(10.0f, 10.0f, 0.0f, true, false, 20.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(4.929f, 4.929f)
            lineTo(19.07f, 19.071f)
        }
    }),
    BASELINE({
        stroke {
            moveTo(4.0f, 20.0f)
            horizontalLineToRelative(16.0f)
        }
        stroke {
            moveToRelative(6.0f, 16.0f)
            lineToRelative(6.0f, -12.0f)
            lineToRelative(6.0f, 12.0f)
        }
        stroke {
            moveTo(8.0f, 12.0f)
            horizontalLineToRelative(8.0f)
        }
    }),
    BELL({
        stroke {
            moveTo(10.268f, 21.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 3.464f, 0.0f)
        }
        stroke {
            moveTo(3.262f, 15.326f)
            arcTo(1.0f, 1.0f, 0.0f, false, false, 4.0f, 17.0f)
            horizontalLineToRelative(16.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, false, 0.74f, -1.673f)
            curveTo(19.41f, 13.956f, 18.0f, 12.499f, 18.0f, 8.0f)
            arcTo(6.0f, 6.0f, 0.0f, false, false, 6.0f, 8.0f)
            curveToRelative(0.0f, 4.499f, -1.411f, 5.956f, -2.738f, 7.326f)
        }
    }),
    BELL_OFF({
        stroke {
            moveTo(10.268f, 21.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 3.464f, 0.0f)
        }
        stroke {
            moveTo(17.0f, 17.0f)
            horizontalLineTo(4.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -0.74f, -1.673f)
            curveTo(4.59f, 13.956f, 6.0f, 12.499f, 6.0f, 8.0f)
            arcToRelative(6.0f, 6.0f, 0.0f, false, true, 0.258f, -1.742f)
        }
        stroke {
            moveToRelative(2.0f, 2.0f)
            lineToRelative(20.0f, 20.0f)
        }
        stroke {
            moveTo(8.668f, 3.01f)
            arcTo(6.0f, 6.0f, 0.0f, false, true, 18.0f, 8.0f)
            curveToRelative(0.0f, 2.687f, 0.77f, 4.653f, 1.707f, 6.05f)
        }
    }),
    BELL_RING({
        stroke {
            moveTo(10.268f, 21.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 3.464f, 0.0f)
        }
        stroke {
            moveTo(22.0f, 8.0f)
            curveToRelative(0.0f, -2.3f, -0.8f, -4.3f, -2.0f, -6.0f)
        }
        stroke {
            moveTo(3.262f, 15.326f)
            arcTo(1.0f, 1.0f, 0.0f, false, false, 4.0f, 17.0f)
            horizontalLineToRelative(16.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, false, 0.74f, -1.673f)
            curveTo(19.41f, 13.956f, 18.0f, 12.499f, 18.0f, 8.0f)
            arcTo(6.0f, 6.0f, 0.0f, false, false, 6.0f, 8.0f)
            curveToRelative(0.0f, 4.499f, -1.411f, 5.956f, -2.738f, 7.326f)
        }
        stroke {
            moveTo(4.0f, 2.0f)
            curveTo(2.8f, 3.7f, 2.0f, 5.7f, 2.0f, 8.0f)
        }
    }),
    BOLD({
        stroke {
            moveTo(6.0f, 12.0f)
            horizontalLineToRelative(9.0f)
            arcToRelative(4.0f, 4.0f, 0.0f, false, true, 0.0f, 8.0f)
            horizontalLineTo(7.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, -1.0f)
            verticalLineTo(5.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, -1.0f)
            horizontalLineToRelative(7.0f)
            arcToRelative(4.0f, 4.0f, 0.0f, false, true, 0.0f, 8.0f)
        }
    }),
    BRIEFCASE_BUSINESS({
        stroke {
            moveTo(12.0f, 12.0f)
            horizontalLineToRelative(0.01f)
        }
        stroke {
            moveTo(16.0f, 6.0f)
            verticalLineTo(4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -2.0f, -2.0f)
            horizontalLineToRelative(-4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -2.0f, 2.0f)
            verticalLineToRelative(2.0f)
        }
        stroke {
            moveTo(22.0f, 13.0f)
            arcToRelative(18.15f, 18.15f, 0.0f, false, true, -20.0f, 0.0f)
        }
        stroke {
            moveTo(4.0f, 6.0f)
            horizontalLineToRelative(16.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            verticalLineToRelative(10.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineToRelative(-16.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineToRelative(-10.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            close()
        }
    }),
    CAMERA({
        stroke {
            moveTo(13.997f, 4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 1.76f, 1.05f)
            lineToRelative(0.486f, 0.9f)
            arcTo(2.0f, 2.0f, 0.0f, false, false, 18.003f, 7.0f)
            horizontalLineTo(20.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            verticalLineToRelative(9.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineTo(4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineTo(9.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            horizontalLineToRelative(1.997f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.759f, -1.048f)
            lineToRelative(0.489f, -0.904f)
            arcTo(2.0f, 2.0f, 0.0f, false, true, 10.004f, 4.0f)
            close()
        }
        stroke {
            moveTo(15.0f, 13.0f)
            arcToRelative(3.0f, 3.0f, 0.0f, true, false, -6.0f, 0.0f)
            arcToRelative(3.0f, 3.0f, 0.0f, true, false, 6.0f, 0.0f)
            close()
        }
    }),
    CHECK({
        stroke {
            moveTo(20.0f, 6.0f)
            lineTo(9.0f, 17.0f)
            lineToRelative(-5.0f, -5.0f)
        }
    }),
    CHECK_CHECK({
        stroke {
            moveTo(18.0f, 6.0f)
            lineTo(7.0f, 17.0f)
            lineToRelative(-5.0f, -5.0f)
        }
        stroke {
            moveToRelative(22.0f, 10.0f)
            lineToRelative(-7.5f, 7.5f)
            lineTo(13.0f, 16.0f)
        }
    }),
    CHEVRON_DOWN({
        stroke {
            moveToRelative(6.0f, 9.0f)
            lineToRelative(6.0f, 6.0f)
            lineToRelative(6.0f, -6.0f)
        }
    }),
    CHEVRON_RIGHT({
        stroke {
            moveToRelative(9.0f, 18.0f)
            lineToRelative(6.0f, -6.0f)
            lineToRelative(-6.0f, -6.0f)
        }
    }),
    CHEVRON_UP({
        stroke {
            moveToRelative(18.0f, 15.0f)
            lineToRelative(-6.0f, -6.0f)
            lineToRelative(-6.0f, 6.0f)
        }
    }),
    CHEVRONS_RIGHT({
        stroke {
            moveToRelative(6.0f, 17.0f)
            lineToRelative(5.0f, -5.0f)
            lineToRelative(-5.0f, -5.0f)
        }
        stroke {
            moveToRelative(13.0f, 17.0f)
            lineToRelative(5.0f, -5.0f)
            lineToRelative(-5.0f, -5.0f)
        }
    }),
    CIRCLE_PLAY({
        stroke {
            moveTo(9.0f, 9.003f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.517f, -0.859f)
            lineToRelative(4.997f, 2.997f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 0.0f, 1.718f)
            lineToRelative(-4.997f, 2.997f)
            arcTo(1.0f, 1.0f, 0.0f, false, true, 9.0f, 14.996f)
            close()
        }
        stroke {
            moveTo(22.0f, 12.0f)
            arcToRelative(10.0f, 10.0f, 0.0f, true, false, -20.0f, 0.0f)
            arcToRelative(10.0f, 10.0f, 0.0f, true, false, 20.0f, 0.0f)
            close()
        }
    }),
    CIRCLE_PLUS({
        stroke {
            moveTo(22.0f, 12.0f)
            arcToRelative(10.0f, 10.0f, 0.0f, true, false, -20.0f, 0.0f)
            arcToRelative(10.0f, 10.0f, 0.0f, true, false, 20.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(8.0f, 12.0f)
            horizontalLineToRelative(8.0f)
        }
        stroke {
            moveTo(12.0f, 8.0f)
            verticalLineToRelative(8.0f)
        }
    }),
    CIRCLE_QUESTION_MARK({
        stroke {
            moveTo(22.0f, 12.0f)
            arcToRelative(10.0f, 10.0f, 0.0f, true, false, -20.0f, 0.0f)
            arcToRelative(10.0f, 10.0f, 0.0f, true, false, 20.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(9.09f, 9.0f)
            arcToRelative(3.0f, 3.0f, 0.0f, false, true, 5.83f, 1.0f)
            curveToRelative(0.0f, 2.0f, -3.0f, 3.0f, -3.0f, 3.0f)
        }
        stroke {
            moveTo(12.0f, 17.0f)
            horizontalLineToRelative(0.01f)
        }
    }),
    CLOCK({
        stroke {
            moveTo(22.0f, 12.0f)
            arcToRelative(10.0f, 10.0f, 0.0f, true, false, -20.0f, 0.0f)
            arcToRelative(10.0f, 10.0f, 0.0f, true, false, 20.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(12.0f, 6.0f)
            verticalLineToRelative(6.0f)
            lineToRelative(4.0f, 2.0f)
        }
    }),
    CLOUD({
        stroke {
            moveTo(17.5f, 19.0f)
            horizontalLineTo(9.0f)
            arcToRelative(7.0f, 7.0f, 0.0f, true, true, 6.71f, -9.0f)
            horizontalLineToRelative(1.79f)
            arcToRelative(4.5f, 4.5f, 0.0f, true, true, 0.0f, 9.0f)
            close()
        }
    }),
    CLOUD_OFF({
        stroke {
            moveTo(10.94f, 5.274f)
            arcTo(7.0f, 7.0f, 0.0f, false, true, 15.71f, 10.0f)
            horizontalLineToRelative(1.79f)
            arcToRelative(4.5f, 4.5f, 0.0f, false, true, 4.222f, 6.057f)
        }
        stroke {
            moveTo(18.796f, 18.81f)
            arcTo(4.5f, 4.5f, 0.0f, false, true, 17.5f, 19.0f)
            horizontalLineTo(9.0f)
            arcTo(7.0f, 7.0f, 0.0f, false, true, 5.79f, 5.78f)
        }
        stroke {
            moveToRelative(2.0f, 2.0f)
            lineToRelative(20.0f, 20.0f)
        }
    }),
    CLOUD_UPLOAD({
        stroke {
            moveTo(12.0f, 13.0f)
            verticalLineToRelative(8.0f)
        }
        stroke {
            moveTo(4.0f, 14.899f)
            arcTo(7.0f, 7.0f, 0.0f, true, true, 15.71f, 8.0f)
            horizontalLineToRelative(1.79f)
            arcToRelative(4.5f, 4.5f, 0.0f, false, true, 2.5f, 8.242f)
        }
        stroke {
            moveToRelative(8.0f, 17.0f)
            lineToRelative(4.0f, -4.0f)
            lineToRelative(4.0f, 4.0f)
        }
    }),
    CODE({
        stroke {
            moveToRelative(16.0f, 18.0f)
            lineToRelative(6.0f, -6.0f)
            lineToRelative(-6.0f, -6.0f)
        }
        stroke {
            moveToRelative(8.0f, 6.0f)
            lineToRelative(-6.0f, 6.0f)
            lineToRelative(6.0f, 6.0f)
        }
    }),
    COPY({
        stroke {
            moveTo(10.0f, 8.0f)
            horizontalLineToRelative(10.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            verticalLineToRelative(10.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineToRelative(-10.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineToRelative(-10.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            close()
        }
        stroke {
            moveTo(4.0f, 16.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
            verticalLineTo(4.0f)
            curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
            horizontalLineToRelative(10.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, 0.9f, 2.0f, 2.0f)
        }
    }),
    DOWNLOAD({
        stroke {
            moveTo(12.0f, 15.0f)
            verticalLineTo(3.0f)
        }
        stroke {
            moveTo(21.0f, 15.0f)
            verticalLineToRelative(4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineTo(5.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineToRelative(-4.0f)
        }
        stroke {
            moveToRelative(7.0f, 10.0f)
            lineToRelative(5.0f, 5.0f)
            lineToRelative(5.0f, -5.0f)
        }
    }),
    ELLIPSIS({
        stroke {
            moveTo(13.0f, 12.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, -2.0f, 0.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, 2.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(20.0f, 12.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, -2.0f, 0.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, 2.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(6.0f, 12.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, -2.0f, 0.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, 2.0f, 0.0f)
            close()
        }
    }),
    ELLIPSIS_VERTICAL({
        stroke {
            moveTo(13.0f, 12.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, -2.0f, 0.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, 2.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(13.0f, 5.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, -2.0f, 0.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, 2.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(13.0f, 19.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, -2.0f, 0.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, 2.0f, 0.0f)
            close()
        }
    }),
    EXTERNAL_LINK({
        stroke {
            moveTo(15.0f, 3.0f)
            horizontalLineToRelative(6.0f)
            verticalLineToRelative(6.0f)
        }
        stroke {
            moveTo(10.0f, 14.0f)
            lineTo(21.0f, 3.0f)
        }
        stroke {
            moveTo(18.0f, 13.0f)
            verticalLineToRelative(6.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineTo(5.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineTo(8.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            horizontalLineToRelative(6.0f)
        }
    }),
    EYE({
        stroke {
            moveTo(2.062f, 12.348f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 0.0f, -0.696f)
            arcToRelative(10.75f, 10.75f, 0.0f, false, true, 19.876f, 0.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 0.0f, 0.696f)
            arcToRelative(10.75f, 10.75f, 0.0f, false, true, -19.876f, 0.0f)
        }
        stroke {
            moveTo(15.0f, 12.0f)
            arcToRelative(3.0f, 3.0f, 0.0f, true, false, -6.0f, 0.0f)
            arcToRelative(3.0f, 3.0f, 0.0f, true, false, 6.0f, 0.0f)
            close()
        }
    }),
    EYE_OFF({
        stroke {
            moveTo(10.733f, 5.076f)
            arcToRelative(10.744f, 10.744f, 0.0f, false, true, 11.205f, 6.575f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 0.0f, 0.696f)
            arcToRelative(10.747f, 10.747f, 0.0f, false, true, -1.444f, 2.49f)
        }
        stroke {
            moveTo(14.084f, 14.158f)
            arcToRelative(3.0f, 3.0f, 0.0f, false, true, -4.242f, -4.242f)
        }
        stroke {
            moveTo(17.479f, 17.499f)
            arcToRelative(10.75f, 10.75f, 0.0f, false, true, -15.417f, -5.151f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 0.0f, -0.696f)
            arcToRelative(10.75f, 10.75f, 0.0f, false, true, 4.446f, -5.143f)
        }
        stroke {
            moveToRelative(2.0f, 2.0f)
            lineToRelative(20.0f, 20.0f)
        }
    }),
    FILE({
        stroke {
            moveTo(6.0f, 22.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineTo(4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            horizontalLineToRelative(8.0f)
            arcToRelative(2.4f, 2.4f, 0.0f, false, true, 1.704f, 0.706f)
            lineToRelative(3.588f, 3.588f)
            arcTo(2.4f, 2.4f, 0.0f, false, true, 20.0f, 8.0f)
            verticalLineToRelative(12.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            close()
        }
        stroke {
            moveTo(14.0f, 2.0f)
            verticalLineToRelative(5.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, false, 1.0f, 1.0f)
            horizontalLineToRelative(5.0f)
        }
    }),
    FILE_TEXT({
        stroke {
            moveTo(6.0f, 22.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineTo(4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            horizontalLineToRelative(8.0f)
            arcToRelative(2.4f, 2.4f, 0.0f, false, true, 1.704f, 0.706f)
            lineToRelative(3.588f, 3.588f)
            arcTo(2.4f, 2.4f, 0.0f, false, true, 20.0f, 8.0f)
            verticalLineToRelative(12.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            close()
        }
        stroke {
            moveTo(14.0f, 2.0f)
            verticalLineToRelative(5.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, false, 1.0f, 1.0f)
            horizontalLineToRelative(5.0f)
        }
        stroke {
            moveTo(10.0f, 9.0f)
            horizontalLineTo(8.0f)
        }
        stroke {
            moveTo(16.0f, 13.0f)
            horizontalLineTo(8.0f)
        }
        stroke {
            moveTo(16.0f, 17.0f)
            horizontalLineTo(8.0f)
        }
    }),
    FILE_UP({
        stroke {
            moveTo(6.0f, 22.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineTo(4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            horizontalLineToRelative(8.0f)
            arcToRelative(2.4f, 2.4f, 0.0f, false, true, 1.704f, 0.706f)
            lineToRelative(3.588f, 3.588f)
            arcTo(2.4f, 2.4f, 0.0f, false, true, 20.0f, 8.0f)
            verticalLineToRelative(12.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            close()
        }
        stroke {
            moveTo(14.0f, 2.0f)
            verticalLineToRelative(5.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, false, 1.0f, 1.0f)
            horizontalLineToRelative(5.0f)
        }
        stroke {
            moveTo(12.0f, 12.0f)
            verticalLineToRelative(6.0f)
        }
        stroke {
            moveToRelative(15.0f, 15.0f)
            lineToRelative(-3.0f, -3.0f)
            lineToRelative(-3.0f, 3.0f)
        }
    }),
    FLASK_CONICAL({
        stroke {
            moveTo(14.0f, 2.0f)
            verticalLineToRelative(6.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 0.245f, 0.96f)
            lineToRelative(5.51f, 10.08f)
            arcTo(2.0f, 2.0f, 0.0f, false, true, 18.0f, 22.0f)
            horizontalLineTo(6.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -1.755f, -2.96f)
            lineToRelative(5.51f, -10.08f)
            arcTo(2.0f, 2.0f, 0.0f, false, false, 10.0f, 8.0f)
            verticalLineTo(2.0f)
        }
        stroke {
            moveTo(6.453f, 15.0f)
            horizontalLineToRelative(11.094f)
        }
        stroke {
            moveTo(8.5f, 2.0f)
            horizontalLineToRelative(7.0f)
        }
    }),
    FOLDER({
        stroke {
            moveTo(20.0f, 20.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.0f, -2.0f)
            verticalLineTo(8.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -2.0f, -2.0f)
            horizontalLineToRelative(-7.9f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -1.69f, -0.9f)
            lineTo(9.6f, 3.9f)
            arcTo(2.0f, 2.0f, 0.0f, false, false, 7.93f, 3.0f)
            horizontalLineTo(4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -2.0f, 2.0f)
            verticalLineToRelative(13.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.0f, 2.0f)
            close()
        }
    }),
    FOLDER_OPEN({
        stroke {
            moveToRelative(6.0f, 14.0f)
            lineToRelative(1.5f, -2.9f)
            arcTo(2.0f, 2.0f, 0.0f, false, true, 9.24f, 10.0f)
            horizontalLineTo(20.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 1.94f, 2.5f)
            lineToRelative(-1.54f, 6.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -1.95f, 1.5f)
            horizontalLineTo(4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineTo(5.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            horizontalLineToRelative(3.9f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 1.69f, 0.9f)
            lineToRelative(0.81f, 1.2f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.67f, 0.9f)
            horizontalLineTo(18.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            verticalLineToRelative(2.0f)
        }
    }),
    FOLDER_PLUS({
        stroke {
            moveTo(12.0f, 10.0f)
            verticalLineToRelative(6.0f)
        }
        stroke {
            moveTo(9.0f, 13.0f)
            horizontalLineToRelative(6.0f)
        }
        stroke {
            moveTo(20.0f, 20.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.0f, -2.0f)
            verticalLineTo(8.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -2.0f, -2.0f)
            horizontalLineToRelative(-7.9f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -1.69f, -0.9f)
            lineTo(9.6f, 3.9f)
            arcTo(2.0f, 2.0f, 0.0f, false, false, 7.93f, 3.0f)
            horizontalLineTo(4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -2.0f, 2.0f)
            verticalLineToRelative(13.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.0f, 2.0f)
            close()
        }
    }),
    FOLDER_X({
        stroke {
            moveTo(20.0f, 20.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.0f, -2.0f)
            verticalLineTo(8.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -2.0f, -2.0f)
            horizontalLineToRelative(-7.9f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -1.69f, -0.9f)
            lineTo(9.6f, 3.9f)
            arcTo(2.0f, 2.0f, 0.0f, false, false, 7.93f, 3.0f)
            horizontalLineTo(4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -2.0f, 2.0f)
            verticalLineToRelative(13.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.0f, 2.0f)
            close()
        }
        stroke {
            moveToRelative(9.5f, 10.5f)
            lineToRelative(5.0f, 5.0f)
        }
        stroke {
            moveToRelative(14.5f, 10.5f)
            lineToRelative(-5.0f, 5.0f)
        }
    }),
    GAVEL({
        stroke {
            moveToRelative(14.0f, 13.0f)
            lineToRelative(-8.381f, 8.38f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -3.001f, -3.0f)
            lineToRelative(8.384f, -8.381f)
        }
        stroke {
            moveToRelative(16.0f, 16.0f)
            lineToRelative(6.0f, -6.0f)
        }
        stroke {
            moveToRelative(21.5f, 10.5f)
            lineToRelative(-8.0f, -8.0f)
        }
        stroke {
            moveToRelative(8.0f, 8.0f)
            lineToRelative(6.0f, -6.0f)
        }
        stroke {
            moveToRelative(8.5f, 7.5f)
            lineToRelative(8.0f, 8.0f)
        }
    }),
    GLOBE({
        stroke {
            moveTo(22.0f, 12.0f)
            arcToRelative(10.0f, 10.0f, 0.0f, true, false, -20.0f, 0.0f)
            arcToRelative(10.0f, 10.0f, 0.0f, true, false, 20.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(12.0f, 2.0f)
            arcToRelative(14.5f, 14.5f, 0.0f, false, false, 0.0f, 20.0f)
            arcToRelative(14.5f, 14.5f, 0.0f, false, false, 0.0f, -20.0f)
        }
        stroke {
            moveTo(2.0f, 12.0f)
            horizontalLineToRelative(20.0f)
        }
    }),
    GRID_2X2({
        stroke {
            moveTo(12.0f, 3.0f)
            verticalLineToRelative(18.0f)
        }
        stroke {
            moveTo(3.0f, 12.0f)
            horizontalLineToRelative(18.0f)
        }
        stroke {
            moveTo(5.0f, 3.0f)
            horizontalLineToRelative(14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineToRelative(-14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineToRelative(-14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            close()
        }
    }),
    GRIP_HORIZONTAL({
        stroke {
            moveTo(13.0f, 9.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, -2.0f, 0.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, 2.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(20.0f, 9.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, -2.0f, 0.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, 2.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(6.0f, 9.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, -2.0f, 0.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, 2.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(13.0f, 15.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, -2.0f, 0.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, 2.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(20.0f, 15.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, -2.0f, 0.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, 2.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(6.0f, 15.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, -2.0f, 0.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, 2.0f, 0.0f)
            close()
        }
    }),
    HISTORY({
        stroke {
            moveTo(3.0f, 12.0f)
            arcToRelative(9.0f, 9.0f, 0.0f, true, false, 9.0f, -9.0f)
            arcToRelative(9.75f, 9.75f, 0.0f, false, false, -6.74f, 2.74f)
            lineTo(3.0f, 8.0f)
        }
        stroke {
            moveTo(3.0f, 3.0f)
            verticalLineToRelative(5.0f)
            horizontalLineToRelative(5.0f)
        }
        stroke {
            moveTo(12.0f, 7.0f)
            verticalLineToRelative(5.0f)
            lineToRelative(4.0f, 2.0f)
        }
    }),
    IMAGE({
        stroke {
            moveTo(5.0f, 3.0f)
            horizontalLineToRelative(14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineToRelative(-14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineToRelative(-14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            close()
        }
        stroke {
            moveTo(11.0f, 9.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, true, false, -4.0f, 0.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, true, false, 4.0f, 0.0f)
            close()
        }
        stroke {
            moveToRelative(21.0f, 15.0f)
            lineToRelative(-3.086f, -3.086f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -2.828f, 0.0f)
            lineTo(6.0f, 21.0f)
        }
    }),
    INDENT_DECREASE({
        stroke {
            moveTo(21.0f, 5.0f)
            horizontalLineTo(11.0f)
        }
        stroke {
            moveTo(21.0f, 12.0f)
            horizontalLineTo(11.0f)
        }
        stroke {
            moveTo(21.0f, 19.0f)
            horizontalLineTo(11.0f)
        }
        stroke {
            moveToRelative(7.0f, 8.0f)
            lineToRelative(-4.0f, 4.0f)
            lineToRelative(4.0f, 4.0f)
        }
    }),
    INDENT_INCREASE({
        stroke {
            moveTo(21.0f, 5.0f)
            horizontalLineTo(11.0f)
        }
        stroke {
            moveTo(21.0f, 12.0f)
            horizontalLineTo(11.0f)
        }
        stroke {
            moveTo(21.0f, 19.0f)
            horizontalLineTo(11.0f)
        }
        stroke {
            moveToRelative(3.0f, 8.0f)
            lineToRelative(4.0f, 4.0f)
            lineToRelative(-4.0f, 4.0f)
        }
    }),
    INFO({
        stroke {
            moveTo(22.0f, 12.0f)
            arcToRelative(10.0f, 10.0f, 0.0f, true, false, -20.0f, 0.0f)
            arcToRelative(10.0f, 10.0f, 0.0f, true, false, 20.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(12.0f, 16.0f)
            verticalLineToRelative(-4.0f)
        }
        stroke {
            moveTo(12.0f, 8.0f)
            horizontalLineToRelative(0.01f)
        }
    }),
    ITALIC({
        stroke {
            moveTo(19.0f, 4.0f)
            lineTo(10.0f, 4.0f)
        }
        stroke {
            moveTo(14.0f, 20.0f)
            lineTo(5.0f, 20.0f)
        }
        stroke {
            moveTo(15.0f, 4.0f)
            lineTo(9.0f, 20.0f)
        }
    }),
    LANGUAGES({
        stroke {
            moveToRelative(5.0f, 8.0f)
            lineToRelative(6.0f, 6.0f)
        }
        stroke {
            moveToRelative(4.0f, 14.0f)
            lineToRelative(6.0f, -6.0f)
            lineToRelative(2.0f, -3.0f)
        }
        stroke {
            moveTo(2.0f, 5.0f)
            horizontalLineToRelative(12.0f)
        }
        stroke {
            moveTo(7.0f, 2.0f)
            horizontalLineToRelative(1.0f)
        }
        stroke {
            moveToRelative(22.0f, 22.0f)
            lineToRelative(-5.0f, -10.0f)
            lineToRelative(-5.0f, 10.0f)
        }
        stroke {
            moveTo(14.0f, 18.0f)
            horizontalLineToRelative(6.0f)
        }
    }),
    LINK({
        stroke {
            moveTo(10.0f, 13.0f)
            arcToRelative(5.0f, 5.0f, 0.0f, false, false, 7.54f, 0.54f)
            lineToRelative(3.0f, -3.0f)
            arcToRelative(5.0f, 5.0f, 0.0f, false, false, -7.07f, -7.07f)
            lineToRelative(-1.72f, 1.71f)
        }
        stroke {
            moveTo(14.0f, 11.0f)
            arcToRelative(5.0f, 5.0f, 0.0f, false, false, -7.54f, -0.54f)
            lineToRelative(-3.0f, 3.0f)
            arcToRelative(5.0f, 5.0f, 0.0f, false, false, 7.07f, 7.07f)
            lineToRelative(1.71f, -1.71f)
        }
    }),
    LOCK_KEYHOLE({
        stroke {
            moveTo(13.0f, 16.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, -2.0f, 0.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, true, false, 2.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(5.0f, 10.0f)
            horizontalLineToRelative(14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            verticalLineToRelative(8.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineToRelative(-14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineToRelative(-8.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            close()
        }
        stroke {
            moveTo(7.0f, 10.0f)
            verticalLineTo(7.0f)
            arcToRelative(5.0f, 5.0f, 0.0f, false, true, 10.0f, 0.0f)
            verticalLineToRelative(3.0f)
        }
    }),
    LOG_IN({
        stroke {
            moveToRelative(10.0f, 17.0f)
            lineToRelative(5.0f, -5.0f)
            lineToRelative(-5.0f, -5.0f)
        }
        stroke {
            moveTo(15.0f, 12.0f)
            horizontalLineTo(3.0f)
        }
        stroke {
            moveTo(15.0f, 3.0f)
            horizontalLineToRelative(4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineToRelative(-4.0f)
        }
    }),
    LOG_OUT({
        stroke {
            moveToRelative(16.0f, 17.0f)
            lineToRelative(5.0f, -5.0f)
            lineToRelative(-5.0f, -5.0f)
        }
        stroke {
            moveTo(21.0f, 12.0f)
            horizontalLineTo(9.0f)
        }
        stroke {
            moveTo(9.0f, 21.0f)
            horizontalLineTo(5.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineTo(5.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            horizontalLineToRelative(4.0f)
        }
    }),
    MAIL({
        stroke {
            moveToRelative(22.0f, 7.0f)
            lineToRelative(-8.991f, 5.727f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.009f, 0.0f)
            lineTo(2.0f, 7.0f)
        }
        stroke {
            moveTo(4.0f, 4.0f)
            horizontalLineToRelative(16.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            verticalLineToRelative(12.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineToRelative(-16.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineToRelative(-12.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            close()
        }
    }),
    MAXIMIZE({
        stroke {
            moveTo(8.0f, 3.0f)
            horizontalLineTo(5.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -2.0f, 2.0f)
            verticalLineToRelative(3.0f)
        }
        stroke {
            moveTo(21.0f, 8.0f)
            verticalLineTo(5.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -2.0f, -2.0f)
            horizontalLineToRelative(-3.0f)
        }
        stroke {
            moveTo(3.0f, 16.0f)
            verticalLineToRelative(3.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.0f, 2.0f)
            horizontalLineToRelative(3.0f)
        }
        stroke {
            moveTo(16.0f, 21.0f)
            horizontalLineToRelative(3.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.0f, -2.0f)
            verticalLineToRelative(-3.0f)
        }
    }),
    MENU({
        stroke {
            moveTo(4.0f, 5.0f)
            horizontalLineToRelative(16.0f)
        }
        stroke {
            moveTo(4.0f, 12.0f)
            horizontalLineToRelative(16.0f)
        }
        stroke {
            moveTo(4.0f, 19.0f)
            horizontalLineToRelative(16.0f)
        }
    }),
    MESSAGE_SQUARE({
        stroke {
            moveTo(22.0f, 17.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineTo(6.828f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -1.414f, 0.586f)
            lineToRelative(-2.202f, 2.202f)
            arcTo(0.71f, 0.71f, 0.0f, false, true, 2.0f, 21.286f)
            verticalLineTo(5.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            horizontalLineToRelative(16.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            close()
        }
    }),
    MESSAGE_SQUARE_CHECK({
        stroke {
            moveTo(22.0f, 17.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineTo(6.828f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -1.414f, 0.586f)
            lineToRelative(-2.202f, 2.202f)
            arcTo(0.7f, 0.7f, 0.0f, false, true, 2.0f, 21.286f)
            verticalLineTo(5.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            horizontalLineToRelative(16.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            close()
        }
        stroke {
            moveToRelative(9.0f, 11.0f)
            lineToRelative(2.0f, 2.0f)
            lineToRelative(4.0f, -4.0f)
        }
    }),
    MESSAGE_SQUARE_DOT({
        stroke {
            moveTo(12.7f, 3.0f)
            horizontalLineTo(4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -2.0f, 2.0f)
            verticalLineToRelative(16.286f)
            arcToRelative(0.71f, 0.71f, 0.0f, false, false, 1.212f, 0.502f)
            lineToRelative(2.202f, -2.202f)
            arcTo(2.0f, 2.0f, 0.0f, false, true, 6.828f, 19.0f)
            horizontalLineTo(20.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.0f, -2.0f)
            verticalLineToRelative(-4.7f)
        }
        stroke {
            moveTo(22.0f, 6.0f)
            arcToRelative(3.0f, 3.0f, 0.0f, true, false, -6.0f, 0.0f)
            arcToRelative(3.0f, 3.0f, 0.0f, true, false, 6.0f, 0.0f)
            close()
        }
    }),
    MESSAGES_SQUARE({
        stroke {
            moveTo(16.0f, 10.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineTo(6.828f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -1.414f, 0.586f)
            lineToRelative(-2.202f, 2.202f)
            arcTo(0.71f, 0.71f, 0.0f, false, true, 2.0f, 14.286f)
            verticalLineTo(4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            horizontalLineToRelative(10.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            close()
        }
        stroke {
            moveTo(20.0f, 9.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            verticalLineToRelative(10.286f)
            arcToRelative(0.71f, 0.71f, 0.0f, false, true, -1.212f, 0.502f)
            lineToRelative(-2.202f, -2.202f)
            arcTo(2.0f, 2.0f, 0.0f, false, false, 17.172f, 19.0f)
            horizontalLineTo(10.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineToRelative(-1.0f)
        }
    }),
    MIC({
        stroke {
            moveTo(12.0f, 19.0f)
            verticalLineToRelative(3.0f)
        }
        stroke {
            moveTo(19.0f, 10.0f)
            verticalLineToRelative(2.0f)
            arcToRelative(7.0f, 7.0f, 0.0f, false, true, -14.0f, 0.0f)
            verticalLineToRelative(-2.0f)
        }
        stroke {
            moveTo(12.0f, 2.0f)
            horizontalLineToRelative(0.0f)
            arcToRelative(3.0f, 3.0f, 0.0f, false, true, 3.0f, 3.0f)
            verticalLineToRelative(7.0f)
            arcToRelative(3.0f, 3.0f, 0.0f, false, true, -3.0f, 3.0f)
            horizontalLineToRelative(0.0f)
            arcToRelative(3.0f, 3.0f, 0.0f, false, true, -3.0f, -3.0f)
            verticalLineToRelative(-7.0f)
            arcToRelative(3.0f, 3.0f, 0.0f, false, true, 3.0f, -3.0f)
            close()
        }
    }),
    MINIMIZE({
        stroke {
            moveTo(8.0f, 3.0f)
            verticalLineToRelative(3.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineTo(3.0f)
        }
        stroke {
            moveTo(21.0f, 8.0f)
            horizontalLineToRelative(-3.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineTo(3.0f)
        }
        stroke {
            moveTo(3.0f, 16.0f)
            horizontalLineToRelative(3.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            verticalLineToRelative(3.0f)
        }
        stroke {
            moveTo(16.0f, 21.0f)
            verticalLineToRelative(-3.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            horizontalLineToRelative(3.0f)
        }
    }),
    MINUS({
        stroke {
            moveTo(5.0f, 12.0f)
            horizontalLineToRelative(14.0f)
        }
    }),
    MOON({
        stroke {
            moveTo(20.985f, 12.486f)
            arcToRelative(9.0f, 9.0f, 0.0f, true, true, -9.473f, -9.472f)
            curveToRelative(0.405f, -0.022f, 0.617f, 0.46f, 0.402f, 0.803f)
            arcToRelative(6.0f, 6.0f, 0.0f, false, false, 8.268f, 8.268f)
            curveToRelative(0.344f, -0.215f, 0.825f, -0.004f, 0.803f, 0.401f)
        }
    }),
    NETWORK({
        stroke {
            moveTo(17.0f, 16.0f)
            horizontalLineToRelative(4.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, 1.0f)
            verticalLineToRelative(4.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, 1.0f)
            horizontalLineToRelative(-4.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, -1.0f)
            verticalLineToRelative(-4.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, -1.0f)
            close()
        }
        stroke {
            moveTo(3.0f, 16.0f)
            horizontalLineToRelative(4.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, 1.0f)
            verticalLineToRelative(4.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, 1.0f)
            horizontalLineToRelative(-4.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, -1.0f)
            verticalLineToRelative(-4.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, -1.0f)
            close()
        }
        stroke {
            moveTo(10.0f, 2.0f)
            horizontalLineToRelative(4.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, 1.0f)
            verticalLineToRelative(4.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, 1.0f)
            horizontalLineToRelative(-4.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, -1.0f)
            verticalLineToRelative(-4.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, -1.0f)
            close()
        }
        stroke {
            moveTo(5.0f, 16.0f)
            verticalLineToRelative(-3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, -1.0f)
            horizontalLineToRelative(12.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, 1.0f)
            verticalLineToRelative(3.0f)
        }
        stroke {
            moveTo(12.0f, 12.0f)
            verticalLineTo(8.0f)
        }
    }),
    PALETTE({
        stroke {
            moveTo(12.0f, 22.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 0.0f, -20.0f)
            arcToRelative(10.0f, 9.0f, 0.0f, false, true, 10.0f, 9.0f)
            arcToRelative(5.0f, 5.0f, 0.0f, false, true, -5.0f, 5.0f)
            horizontalLineToRelative(-2.25f)
            arcToRelative(1.75f, 1.75f, 0.0f, false, false, -1.4f, 2.8f)
            lineToRelative(0.3f, 0.4f)
            arcToRelative(1.75f, 1.75f, 0.0f, false, true, -1.4f, 2.8f)
            close()
        }
        stroke(fill = true) {
            moveTo(14.0f, 6.5f)
            arcToRelative(0.5f, 0.5f, 0.0f, true, false, -1.0f, 0.0f)
            arcToRelative(0.5f, 0.5f, 0.0f, true, false, 1.0f, 0.0f)
            close()
        }
        stroke(fill = true) {
            moveTo(18.0f, 10.5f)
            arcToRelative(0.5f, 0.5f, 0.0f, true, false, -1.0f, 0.0f)
            arcToRelative(0.5f, 0.5f, 0.0f, true, false, 1.0f, 0.0f)
            close()
        }
        stroke(fill = true) {
            moveTo(7.0f, 12.5f)
            arcToRelative(0.5f, 0.5f, 0.0f, true, false, -1.0f, 0.0f)
            arcToRelative(0.5f, 0.5f, 0.0f, true, false, 1.0f, 0.0f)
            close()
        }
        stroke(fill = true) {
            moveTo(9.0f, 7.5f)
            arcToRelative(0.5f, 0.5f, 0.0f, true, false, -1.0f, 0.0f)
            arcToRelative(0.5f, 0.5f, 0.0f, true, false, 1.0f, 0.0f)
            close()
        }
    }),
    PANELS_TOP_LEFT({
        stroke {
            moveTo(5.0f, 3.0f)
            horizontalLineToRelative(14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineToRelative(-14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineToRelative(-14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            close()
        }
        stroke {
            moveTo(3.0f, 9.0f)
            horizontalLineToRelative(18.0f)
        }
        stroke {
            moveTo(9.0f, 21.0f)
            verticalLineTo(9.0f)
        }
    }),
    PAPERCLIP({
        stroke {
            moveToRelative(16.0f, 6.0f)
            lineToRelative(-8.414f, 8.586f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.829f, 2.829f)
            lineToRelative(8.414f, -8.586f)
            arcToRelative(4.0f, 4.0f, 0.0f, true, false, -5.657f, -5.657f)
            lineToRelative(-8.379f, 8.551f)
            arcToRelative(6.0f, 6.0f, 0.0f, true, false, 8.485f, 8.485f)
            lineToRelative(8.379f, -8.551f)
        }
    }),
    PAUSE({
        stroke {
            moveTo(15.0f, 3.0f)
            horizontalLineToRelative(3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, 1.0f)
            verticalLineToRelative(16.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, 1.0f)
            horizontalLineToRelative(-3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, -1.0f)
            verticalLineToRelative(-16.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, -1.0f)
            close()
        }
        stroke {
            moveTo(6.0f, 3.0f)
            horizontalLineToRelative(3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, 1.0f)
            verticalLineToRelative(16.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, 1.0f)
            horizontalLineToRelative(-3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, -1.0f)
            verticalLineToRelative(-16.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, -1.0f)
            close()
        }
    }),
    PENCIL({
        stroke {
            moveTo(21.174f, 6.812f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, false, -3.986f, -3.987f)
            lineTo(3.842f, 16.174f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -0.5f, 0.83f)
            lineToRelative(-1.321f, 4.352f)
            arcToRelative(0.5f, 0.5f, 0.0f, false, false, 0.623f, 0.622f)
            lineToRelative(4.353f, -1.32f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 0.83f, -0.497f)
            close()
        }
        stroke {
            moveToRelative(15.0f, 5.0f)
            lineToRelative(4.0f, 4.0f)
        }
    }),
    PIN({
        stroke {
            moveTo(12.0f, 17.0f)
            verticalLineToRelative(5.0f)
        }
        stroke {
            moveTo(9.0f, 10.76f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -1.11f, 1.79f)
            lineToRelative(-1.78f, 0.9f)
            arcTo(2.0f, 2.0f, 0.0f, false, false, 5.0f, 15.24f)
            verticalLineTo(16.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, false, 1.0f, 1.0f)
            horizontalLineToRelative(12.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, false, 1.0f, -1.0f)
            verticalLineToRelative(-0.76f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -1.11f, -1.79f)
            lineToRelative(-1.78f, -0.9f)
            arcTo(2.0f, 2.0f, 0.0f, false, true, 15.0f, 10.76f)
            verticalLineTo(7.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, -1.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 0.0f, -4.0f)
            horizontalLineTo(8.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 0.0f, 4.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, 1.0f)
            close()
        }
    }),
    PLAY({
        stroke {
            moveTo(5.0f, 5.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 3.008f, -1.728f)
            lineToRelative(11.997f, 6.998f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 0.003f, 3.458f)
            lineToRelative(-12.0f, 7.0f)
            arcTo(2.0f, 2.0f, 0.0f, false, true, 5.0f, 19.0f)
            close()
        }
    }),
    PLUS({
        stroke {
            moveTo(5.0f, 12.0f)
            horizontalLineToRelative(14.0f)
        }
        stroke {
            moveTo(12.0f, 5.0f)
            verticalLineToRelative(14.0f)
        }
    }),
    POWER({
        stroke {
            moveTo(12.0f, 2.0f)
            verticalLineToRelative(10.0f)
        }
        stroke {
            moveTo(18.4f, 6.6f)
            arcToRelative(9.0f, 9.0f, 0.0f, true, true, -12.77f, 0.04f)
        }
    }),
    QR_CODE({
        stroke {
            moveTo(4.0f, 3.0f)
            horizontalLineToRelative(3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, 1.0f)
            verticalLineToRelative(3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, 1.0f)
            horizontalLineToRelative(-3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, -1.0f)
            verticalLineToRelative(-3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, -1.0f)
            close()
        }
        stroke {
            moveTo(17.0f, 3.0f)
            horizontalLineToRelative(3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, 1.0f)
            verticalLineToRelative(3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, 1.0f)
            horizontalLineToRelative(-3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, -1.0f)
            verticalLineToRelative(-3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, -1.0f)
            close()
        }
        stroke {
            moveTo(4.0f, 16.0f)
            horizontalLineToRelative(3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, 1.0f)
            verticalLineToRelative(3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, 1.0f)
            horizontalLineToRelative(-3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.0f, -1.0f)
            verticalLineToRelative(-3.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, -1.0f)
            close()
        }
        stroke {
            moveTo(21.0f, 16.0f)
            horizontalLineToRelative(-3.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -2.0f, 2.0f)
            verticalLineToRelative(3.0f)
        }
        stroke {
            moveTo(21.0f, 21.0f)
            verticalLineToRelative(0.01f)
        }
        stroke {
            moveTo(12.0f, 7.0f)
            verticalLineToRelative(3.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineTo(7.0f)
        }
        stroke {
            moveTo(3.0f, 12.0f)
            horizontalLineToRelative(0.01f)
        }
        stroke {
            moveTo(12.0f, 3.0f)
            horizontalLineToRelative(0.01f)
        }
        stroke {
            moveTo(12.0f, 16.0f)
            verticalLineToRelative(0.01f)
        }
        stroke {
            moveTo(16.0f, 12.0f)
            horizontalLineToRelative(1.0f)
        }
        stroke {
            moveTo(21.0f, 12.0f)
            verticalLineToRelative(0.01f)
        }
        stroke {
            moveTo(12.0f, 21.0f)
            verticalLineToRelative(-1.0f)
        }
    }),
    QUOTE({
        stroke {
            moveTo(16.0f, 3.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -2.0f, 2.0f)
            verticalLineToRelative(6.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.0f, 2.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, 1.0f)
            verticalLineToRelative(1.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, false, -1.0f, 1.0f)
            verticalLineToRelative(2.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, false, 1.0f, 1.0f)
            arcToRelative(6.0f, 6.0f, 0.0f, false, false, 6.0f, -6.0f)
            verticalLineTo(5.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -2.0f, -2.0f)
            close()
        }
        stroke {
            moveTo(5.0f, 3.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -2.0f, 2.0f)
            verticalLineToRelative(6.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.0f, 2.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, 1.0f)
            verticalLineToRelative(1.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, false, -1.0f, 1.0f)
            verticalLineToRelative(2.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, false, 1.0f, 1.0f)
            arcToRelative(6.0f, 6.0f, 0.0f, false, false, 6.0f, -6.0f)
            verticalLineTo(5.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -2.0f, -2.0f)
            close()
        }
    }),
    REFRESH_CW({
        stroke {
            moveTo(3.0f, 12.0f)
            arcToRelative(9.0f, 9.0f, 0.0f, false, true, 9.0f, -9.0f)
            arcToRelative(9.75f, 9.75f, 0.0f, false, true, 6.74f, 2.74f)
            lineTo(21.0f, 8.0f)
        }
        stroke {
            moveTo(21.0f, 3.0f)
            verticalLineToRelative(5.0f)
            horizontalLineToRelative(-5.0f)
        }
        stroke {
            moveTo(21.0f, 12.0f)
            arcToRelative(9.0f, 9.0f, 0.0f, false, true, -9.0f, 9.0f)
            arcToRelative(9.75f, 9.75f, 0.0f, false, true, -6.74f, -2.74f)
            lineTo(3.0f, 16.0f)
        }
        stroke {
            moveTo(8.0f, 16.0f)
            horizontalLineTo(3.0f)
            verticalLineToRelative(5.0f)
        }
    }),
    REMOVE_FORMATTING({
        stroke {
            moveTo(4.0f, 7.0f)
            verticalLineTo(4.0f)
            horizontalLineToRelative(16.0f)
            verticalLineToRelative(3.0f)
        }
        stroke {
            moveTo(5.0f, 20.0f)
            horizontalLineToRelative(6.0f)
        }
        stroke {
            moveTo(13.0f, 4.0f)
            lineTo(8.0f, 20.0f)
        }
        stroke {
            moveToRelative(15.0f, 15.0f)
            lineToRelative(5.0f, 5.0f)
        }
        stroke {
            moveToRelative(20.0f, 15.0f)
            lineToRelative(-5.0f, 5.0f)
        }
    }),
    REPLY({
        stroke {
            moveTo(20.0f, 18.0f)
            verticalLineToRelative(-2.0f)
            arcToRelative(4.0f, 4.0f, 0.0f, false, false, -4.0f, -4.0f)
            horizontalLineTo(4.0f)
        }
        stroke {
            moveToRelative(9.0f, 17.0f)
            lineToRelative(-5.0f, -5.0f)
            lineToRelative(5.0f, -5.0f)
        }
    }),
    ROTATE_CW({
        stroke {
            moveTo(21.0f, 12.0f)
            arcToRelative(9.0f, 9.0f, 0.0f, true, true, -9.0f, -9.0f)
            curveToRelative(2.52f, 0.0f, 4.93f, 1.0f, 6.74f, 2.74f)
            lineTo(21.0f, 8.0f)
        }
        stroke {
            moveTo(21.0f, 3.0f)
            verticalLineToRelative(5.0f)
            horizontalLineToRelative(-5.0f)
        }
    }),
    SCAN_LINE({
        stroke {
            moveTo(3.0f, 7.0f)
            verticalLineTo(5.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            horizontalLineToRelative(2.0f)
        }
        stroke {
            moveTo(17.0f, 3.0f)
            horizontalLineToRelative(2.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            verticalLineToRelative(2.0f)
        }
        stroke {
            moveTo(21.0f, 17.0f)
            verticalLineToRelative(2.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineToRelative(-2.0f)
        }
        stroke {
            moveTo(7.0f, 21.0f)
            horizontalLineTo(5.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineToRelative(-2.0f)
        }
        stroke {
            moveTo(7.0f, 12.0f)
            horizontalLineToRelative(10.0f)
        }
    }),
    SEARCH({
        stroke {
            moveToRelative(21.0f, 21.0f)
            lineToRelative(-4.34f, -4.34f)
        }
        stroke {
            moveTo(19.0f, 11.0f)
            arcToRelative(8.0f, 8.0f, 0.0f, true, false, -16.0f, 0.0f)
            arcToRelative(8.0f, 8.0f, 0.0f, true, false, 16.0f, 0.0f)
            close()
        }
    }),
    SEARCH_X({
        stroke {
            moveToRelative(13.5f, 8.5f)
            lineToRelative(-5.0f, 5.0f)
        }
        stroke {
            moveToRelative(8.5f, 8.5f)
            lineToRelative(5.0f, 5.0f)
        }
        stroke {
            moveTo(19.0f, 11.0f)
            arcToRelative(8.0f, 8.0f, 0.0f, true, false, -16.0f, 0.0f)
            arcToRelative(8.0f, 8.0f, 0.0f, true, false, 16.0f, 0.0f)
            close()
        }
        stroke {
            moveToRelative(21.0f, 21.0f)
            lineToRelative(-4.3f, -4.3f)
        }
    }),
    SEND({
        stroke {
            moveTo(14.536f, 21.686f)
            arcToRelative(0.5f, 0.5f, 0.0f, false, false, 0.937f, -0.024f)
            lineToRelative(6.5f, -19.0f)
            arcToRelative(0.496f, 0.496f, 0.0f, false, false, -0.635f, -0.635f)
            lineToRelative(-19.0f, 6.5f)
            arcToRelative(0.5f, 0.5f, 0.0f, false, false, -0.024f, 0.937f)
            lineToRelative(7.93f, 3.18f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 1.112f, 1.11f)
            close()
        }
        stroke {
            moveToRelative(21.854f, 2.147f)
            lineToRelative(-10.94f, 10.939f)
        }
    }),
    SERVER({
        stroke {
            moveTo(4.0f, 2.0f)
            horizontalLineToRelative(16.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            verticalLineToRelative(4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineToRelative(-16.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineToRelative(-4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            close()
        }
        stroke {
            moveTo(4.0f, 14.0f)
            horizontalLineToRelative(16.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            verticalLineToRelative(4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineToRelative(-16.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineToRelative(-4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            close()
        }
        stroke {
            moveTo(6.0f, 6.0f)
            lineTo(6.01f, 6.0f)
        }
        stroke {
            moveTo(6.0f, 18.0f)
            lineTo(6.01f, 18.0f)
        }
    }),
    SETTINGS({
        stroke {
            moveTo(9.671f, 4.136f)
            arcToRelative(2.34f, 2.34f, 0.0f, false, true, 4.659f, 0.0f)
            arcToRelative(2.34f, 2.34f, 0.0f, false, false, 3.319f, 1.915f)
            arcToRelative(2.34f, 2.34f, 0.0f, false, true, 2.33f, 4.033f)
            arcToRelative(2.34f, 2.34f, 0.0f, false, false, 0.0f, 3.831f)
            arcToRelative(2.34f, 2.34f, 0.0f, false, true, -2.33f, 4.033f)
            arcToRelative(2.34f, 2.34f, 0.0f, false, false, -3.319f, 1.915f)
            arcToRelative(2.34f, 2.34f, 0.0f, false, true, -4.659f, 0.0f)
            arcToRelative(2.34f, 2.34f, 0.0f, false, false, -3.32f, -1.915f)
            arcToRelative(2.34f, 2.34f, 0.0f, false, true, -2.33f, -4.033f)
            arcToRelative(2.34f, 2.34f, 0.0f, false, false, 0.0f, -3.831f)
            arcTo(2.34f, 2.34f, 0.0f, false, true, 6.35f, 6.051f)
            arcToRelative(2.34f, 2.34f, 0.0f, false, false, 3.319f, -1.915f)
        }
        stroke {
            moveTo(15.0f, 12.0f)
            arcToRelative(3.0f, 3.0f, 0.0f, true, false, -6.0f, 0.0f)
            arcToRelative(3.0f, 3.0f, 0.0f, true, false, 6.0f, 0.0f)
            close()
        }
    }),
    SHARE_2({
        stroke {
            moveTo(21.0f, 5.0f)
            arcToRelative(3.0f, 3.0f, 0.0f, true, false, -6.0f, 0.0f)
            arcToRelative(3.0f, 3.0f, 0.0f, true, false, 6.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(9.0f, 12.0f)
            arcToRelative(3.0f, 3.0f, 0.0f, true, false, -6.0f, 0.0f)
            arcToRelative(3.0f, 3.0f, 0.0f, true, false, 6.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(21.0f, 19.0f)
            arcToRelative(3.0f, 3.0f, 0.0f, true, false, -6.0f, 0.0f)
            arcToRelative(3.0f, 3.0f, 0.0f, true, false, 6.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(8.59f, 13.51f)
            lineTo(15.42f, 17.49f)
        }
        stroke {
            moveTo(15.41f, 6.51f)
            lineTo(8.59f, 10.49f)
        }
    }),
    SHIELD({
        stroke {
            moveTo(20.0f, 13.0f)
            curveToRelative(0.0f, 5.0f, -3.5f, 7.5f, -7.66f, 8.95f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -0.67f, -0.01f)
            curveTo(7.5f, 20.5f, 4.0f, 18.0f, 4.0f, 13.0f)
            verticalLineTo(6.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, -1.0f)
            curveToRelative(2.0f, 0.0f, 4.5f, -1.2f, 6.24f, -2.72f)
            arcToRelative(1.17f, 1.17f, 0.0f, false, true, 1.52f, 0.0f)
            curveTo(14.51f, 3.81f, 17.0f, 5.0f, 19.0f, 5.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, 1.0f)
            close()
        }
    }),
    SHIELD_CHECK({
        stroke {
            moveTo(20.0f, 13.0f)
            curveToRelative(0.0f, 5.0f, -3.5f, 7.5f, -7.66f, 8.95f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -0.67f, -0.01f)
            curveTo(7.5f, 20.5f, 4.0f, 18.0f, 4.0f, 13.0f)
            verticalLineTo(6.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, -1.0f)
            curveToRelative(2.0f, 0.0f, 4.5f, -1.2f, 6.24f, -2.72f)
            arcToRelative(1.17f, 1.17f, 0.0f, false, true, 1.52f, 0.0f)
            curveTo(14.51f, 3.81f, 17.0f, 5.0f, 19.0f, 5.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, 1.0f)
            close()
        }
        stroke {
            moveToRelative(9.0f, 12.0f)
            lineToRelative(2.0f, 2.0f)
            lineToRelative(4.0f, -4.0f)
        }
    }),
    SKIP_BACK({
        stroke {
            moveTo(17.971f, 4.285f)
            arcTo(2.0f, 2.0f, 0.0f, false, true, 21.0f, 6.0f)
            verticalLineToRelative(12.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -3.029f, 1.715f)
            lineToRelative(-9.997f, -5.998f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -0.003f, -3.432f)
            close()
        }
        stroke {
            moveTo(3.0f, 20.0f)
            verticalLineTo(4.0f)
        }
    }),
    SKIP_FORWARD({
        stroke {
            moveTo(21.0f, 4.0f)
            verticalLineToRelative(16.0f)
        }
        stroke {
            moveTo(6.029f, 4.285f)
            arcTo(2.0f, 2.0f, 0.0f, false, false, 3.0f, 6.0f)
            verticalLineToRelative(12.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 3.029f, 1.715f)
            lineToRelative(9.997f, -5.998f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 0.003f, -3.432f)
            close()
        }
    }),
    SLIDERS_HORIZONTAL({
        stroke {
            moveTo(10.0f, 5.0f)
            horizontalLineTo(3.0f)
        }
        stroke {
            moveTo(12.0f, 19.0f)
            horizontalLineTo(3.0f)
        }
        stroke {
            moveTo(14.0f, 3.0f)
            verticalLineToRelative(4.0f)
        }
        stroke {
            moveTo(16.0f, 17.0f)
            verticalLineToRelative(4.0f)
        }
        stroke {
            moveTo(21.0f, 12.0f)
            horizontalLineToRelative(-9.0f)
        }
        stroke {
            moveTo(21.0f, 19.0f)
            horizontalLineToRelative(-5.0f)
        }
        stroke {
            moveTo(21.0f, 5.0f)
            horizontalLineToRelative(-7.0f)
        }
        stroke {
            moveTo(8.0f, 10.0f)
            verticalLineToRelative(4.0f)
        }
        stroke {
            moveTo(8.0f, 12.0f)
            horizontalLineTo(3.0f)
        }
    }),
    SMILE({
        stroke {
            moveTo(15.0f, 10.0f)
            verticalLineTo(9.0f)
        }
        stroke {
            moveTo(16.472f, 15.0f)
            arcToRelative(6.0f, 6.0f, 0.0f, false, true, -8.943f, 0.0f)
        }
        stroke {
            moveTo(9.0f, 10.0f)
            verticalLineTo(9.0f)
        }
        stroke {
            moveTo(22.0f, 12.0f)
            arcToRelative(10.0f, 10.0f, 0.0f, true, false, -20.0f, 0.0f)
            arcToRelative(10.0f, 10.0f, 0.0f, true, false, 20.0f, 0.0f)
            close()
        }
    }),
    SPARKLES({
        stroke {
            moveTo(11.017f, 2.814f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.966f, 0.0f)
            lineToRelative(1.051f, 5.558f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.594f, 1.594f)
            lineToRelative(5.558f, 1.051f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 0.0f, 1.966f)
            lineToRelative(-5.558f, 1.051f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -1.594f, 1.594f)
            lineToRelative(-1.051f, 5.558f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, -1.966f, 0.0f)
            lineToRelative(-1.051f, -5.558f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -1.594f, -1.594f)
            lineToRelative(-5.558f, -1.051f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 0.0f, -1.966f)
            lineToRelative(5.558f, -1.051f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.594f, -1.594f)
            close()
        }
        stroke {
            moveTo(20.0f, 2.0f)
            verticalLineToRelative(4.0f)
        }
        stroke {
            moveTo(22.0f, 4.0f)
            horizontalLineToRelative(-4.0f)
        }
        stroke {
            moveTo(6.0f, 20.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, true, false, -4.0f, 0.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, true, false, 4.0f, 0.0f)
            close()
        }
    }),
    SPEECH({
        stroke {
            moveTo(8.8f, 20.0f)
            verticalLineToRelative(-4.1f)
            lineToRelative(1.9f, 0.2f)
            arcToRelative(2.3f, 2.3f, 0.0f, false, false, 2.164f, -2.1f)
            verticalLineTo(8.3f)
            arcTo(5.37f, 5.37f, 0.0f, false, false, 2.0f, 8.25f)
            curveToRelative(0.0f, 2.8f, 0.656f, 3.054f, 1.0f, 4.55f)
            arcToRelative(5.77f, 5.77f, 0.0f, false, true, 0.029f, 2.758f)
            lineTo(2.0f, 20.0f)
        }
        stroke {
            moveTo(19.8f, 17.8f)
            arcToRelative(7.5f, 7.5f, 0.0f, false, false, 0.003f, -10.603f)
        }
        stroke {
            moveTo(17.0f, 15.0f)
            arcToRelative(3.5f, 3.5f, 0.0f, false, false, -0.025f, -4.975f)
        }
    }),
    SPELL_CHECK({
        stroke {
            moveToRelative(20.0f, 15.0f)
            lineToRelative(-5.5f, 5.5f)
            lineTo(12.0f, 18.0f)
        }
        stroke {
            moveToRelative(4.0f, 16.0f)
            lineToRelative(6.0f, -12.0f)
            lineToRelative(5.115f, 10.23f)
        }
        stroke {
            moveTo(6.0f, 12.0f)
            horizontalLineToRelative(8.0f)
        }
    }),
    SQUARE({
        stroke {
            moveTo(5.0f, 3.0f)
            horizontalLineToRelative(14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineToRelative(-14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineToRelative(-14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            close()
        }
    }),
    STAR({
        stroke {
            moveTo(11.525f, 2.295f)
            arcToRelative(0.53f, 0.53f, 0.0f, false, true, 0.95f, 0.0f)
            lineToRelative(2.31f, 4.679f)
            arcToRelative(2.123f, 2.123f, 0.0f, false, false, 1.595f, 1.16f)
            lineToRelative(5.166f, 0.756f)
            arcToRelative(0.53f, 0.53f, 0.0f, false, true, 0.294f, 0.904f)
            lineToRelative(-3.736f, 3.638f)
            arcToRelative(2.123f, 2.123f, 0.0f, false, false, -0.611f, 1.878f)
            lineToRelative(0.882f, 5.14f)
            arcToRelative(0.53f, 0.53f, 0.0f, false, true, -0.771f, 0.56f)
            lineToRelative(-4.618f, -2.428f)
            arcToRelative(2.122f, 2.122f, 0.0f, false, false, -1.973f, 0.0f)
            lineTo(6.396f, 21.01f)
            arcToRelative(0.53f, 0.53f, 0.0f, false, true, -0.77f, -0.56f)
            lineToRelative(0.881f, -5.139f)
            arcToRelative(2.122f, 2.122f, 0.0f, false, false, -0.611f, -1.879f)
            lineTo(2.16f, 9.795f)
            arcToRelative(0.53f, 0.53f, 0.0f, false, true, 0.294f, -0.906f)
            lineToRelative(5.165f, -0.755f)
            arcToRelative(2.122f, 2.122f, 0.0f, false, false, 1.597f, -1.16f)
            close()
        }
    }),
    SUN({
        stroke {
            moveTo(16.0f, 12.0f)
            arcToRelative(4.0f, 4.0f, 0.0f, true, false, -8.0f, 0.0f)
            arcToRelative(4.0f, 4.0f, 0.0f, true, false, 8.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(12.0f, 2.0f)
            verticalLineToRelative(2.0f)
        }
        stroke {
            moveTo(12.0f, 20.0f)
            verticalLineToRelative(2.0f)
        }
        stroke {
            moveToRelative(4.93f, 4.93f)
            lineToRelative(1.41f, 1.41f)
        }
        stroke {
            moveToRelative(17.66f, 17.66f)
            lineToRelative(1.41f, 1.41f)
        }
        stroke {
            moveTo(2.0f, 12.0f)
            horizontalLineToRelative(2.0f)
        }
        stroke {
            moveTo(20.0f, 12.0f)
            horizontalLineToRelative(2.0f)
        }
        stroke {
            moveToRelative(6.34f, 17.66f)
            lineToRelative(-1.41f, 1.41f)
        }
        stroke {
            moveToRelative(19.07f, 4.93f)
            lineToRelative(-1.41f, 1.41f)
        }
    }),
    SWATCH_BOOK({
        stroke {
            moveTo(11.0f, 17.0f)
            arcToRelative(4.0f, 4.0f, 0.0f, false, true, -8.0f, 0.0f)
            verticalLineTo(5.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            horizontalLineToRelative(4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            close()
        }
        stroke {
            moveTo(16.7f, 13.0f)
            horizontalLineTo(19.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            verticalLineToRelative(4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineTo(7.0f)
        }
        stroke {
            moveTo(7.0f, 17.0f)
            horizontalLineToRelative(0.01f)
        }
        stroke {
            moveToRelative(11.0f, 8.0f)
            lineToRelative(2.3f, -2.3f)
            arcToRelative(2.4f, 2.4f, 0.0f, false, true, 3.404f, 0.004f)
            lineTo(18.6f, 7.6f)
            arcToRelative(2.4f, 2.4f, 0.0f, false, true, 0.026f, 3.434f)
            lineTo(9.9f, 19.8f)
        }
    }),
    TERMINAL({
        stroke {
            moveTo(12.0f, 19.0f)
            horizontalLineToRelative(8.0f)
        }
        stroke {
            moveToRelative(4.0f, 17.0f)
            lineToRelative(6.0f, -6.0f)
            lineToRelative(-6.0f, -6.0f)
        }
    }),
    TRASH_2({
        stroke {
            moveTo(10.0f, 11.0f)
            verticalLineToRelative(6.0f)
        }
        stroke {
            moveTo(14.0f, 11.0f)
            verticalLineToRelative(6.0f)
        }
        stroke {
            moveTo(19.0f, 6.0f)
            verticalLineToRelative(14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, 2.0f)
            horizontalLineTo(7.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, -2.0f, -2.0f)
            verticalLineTo(6.0f)
        }
        stroke {
            moveTo(3.0f, 6.0f)
            horizontalLineToRelative(18.0f)
        }
        stroke {
            moveTo(8.0f, 6.0f)
            verticalLineTo(4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, -2.0f)
            horizontalLineToRelative(4.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, true, 2.0f, 2.0f)
            verticalLineToRelative(2.0f)
        }
    }),
    TRIANGLE_ALERT({
        stroke {
            moveToRelative(21.73f, 18.0f)
            lineToRelative(-8.0f, -14.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, -3.48f, 0.0f)
            lineToRelative(-8.0f, 14.0f)
            arcTo(2.0f, 2.0f, 0.0f, false, false, 4.0f, 21.0f)
            horizontalLineToRelative(16.0f)
            arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.73f, -3.0f)
        }
        stroke {
            moveTo(12.0f, 9.0f)
            verticalLineToRelative(4.0f)
        }
        stroke {
            moveTo(12.0f, 17.0f)
            horizontalLineToRelative(0.01f)
        }
    }),
    TYPE({
        stroke {
            moveTo(12.0f, 4.0f)
            verticalLineToRelative(16.0f)
        }
        stroke {
            moveTo(4.0f, 7.0f)
            verticalLineTo(5.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, -1.0f)
            horizontalLineToRelative(14.0f)
            arcToRelative(1.0f, 1.0f, 0.0f, false, true, 1.0f, 1.0f)
            verticalLineToRelative(2.0f)
        }
        stroke {
            moveTo(9.0f, 20.0f)
            horizontalLineToRelative(6.0f)
        }
    }),
    UNDERLINE({
        stroke {
            moveTo(6.0f, 4.0f)
            verticalLineToRelative(6.0f)
            arcToRelative(6.0f, 6.0f, 0.0f, false, false, 12.0f, 0.0f)
            verticalLineTo(4.0f)
        }
        stroke {
            moveTo(4.0f, 20.0f)
            lineTo(20.0f, 20.0f)
        }
    }),
    USER_ROUND({
        stroke {
            moveTo(17.0f, 8.0f)
            arcToRelative(5.0f, 5.0f, 0.0f, true, false, -10.0f, 0.0f)
            arcToRelative(5.0f, 5.0f, 0.0f, true, false, 10.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(20.0f, 21.0f)
            arcToRelative(8.0f, 8.0f, 0.0f, false, false, -16.0f, 0.0f)
        }
    }),
    USERS({
        stroke {
            moveTo(16.0f, 21.0f)
            verticalLineToRelative(-2.0f)
            arcToRelative(4.0f, 4.0f, 0.0f, false, false, -4.0f, -4.0f)
            horizontalLineTo(6.0f)
            arcToRelative(4.0f, 4.0f, 0.0f, false, false, -4.0f, 4.0f)
            verticalLineToRelative(2.0f)
        }
        stroke {
            moveTo(16.0f, 3.128f)
            arcToRelative(4.0f, 4.0f, 0.0f, false, true, 0.0f, 7.744f)
        }
        stroke {
            moveTo(22.0f, 21.0f)
            verticalLineToRelative(-2.0f)
            arcToRelative(4.0f, 4.0f, 0.0f, false, false, -3.0f, -3.87f)
        }
        stroke {
            moveTo(13.0f, 7.0f)
            arcToRelative(4.0f, 4.0f, 0.0f, true, false, -8.0f, 0.0f)
            arcToRelative(4.0f, 4.0f, 0.0f, true, false, 8.0f, 0.0f)
            close()
        }
    }),
    USERS_ROUND({
        stroke {
            moveTo(18.0f, 21.0f)
            arcToRelative(8.0f, 8.0f, 0.0f, false, false, -16.0f, 0.0f)
        }
        stroke {
            moveTo(15.0f, 8.0f)
            arcToRelative(5.0f, 5.0f, 0.0f, true, false, -10.0f, 0.0f)
            arcToRelative(5.0f, 5.0f, 0.0f, true, false, 10.0f, 0.0f)
            close()
        }
        stroke {
            moveTo(22.0f, 20.0f)
            curveToRelative(0.0f, -3.37f, -2.0f, -6.5f, -4.0f, -8.0f)
            arcToRelative(5.0f, 5.0f, 0.0f, false, false, -0.45f, -8.3f)
        }
    }),
    WAND_SPARKLES({
        stroke {
            moveToRelative(21.64f, 3.64f)
            lineToRelative(-1.28f, -1.28f)
            arcToRelative(1.21f, 1.21f, 0.0f, false, false, -1.72f, 0.0f)
            lineTo(2.36f, 18.64f)
            arcToRelative(1.21f, 1.21f, 0.0f, false, false, 0.0f, 1.72f)
            lineToRelative(1.28f, 1.28f)
            arcToRelative(1.2f, 1.2f, 0.0f, false, false, 1.72f, 0.0f)
            lineTo(21.64f, 5.36f)
            arcToRelative(1.2f, 1.2f, 0.0f, false, false, 0.0f, -1.72f)
        }
        stroke {
            moveToRelative(14.0f, 7.0f)
            lineToRelative(3.0f, 3.0f)
        }
        stroke {
            moveTo(5.0f, 6.0f)
            verticalLineToRelative(4.0f)
        }
        stroke {
            moveTo(19.0f, 14.0f)
            verticalLineToRelative(4.0f)
        }
        stroke {
            moveTo(10.0f, 2.0f)
            verticalLineToRelative(2.0f)
        }
        stroke {
            moveTo(7.0f, 8.0f)
            horizontalLineTo(3.0f)
        }
        stroke {
            moveTo(21.0f, 16.0f)
            horizontalLineToRelative(-4.0f)
        }
        stroke {
            moveTo(11.0f, 3.0f)
            horizontalLineTo(9.0f)
        }
    }),
    X({
        stroke {
            moveTo(18.0f, 6.0f)
            lineTo(6.0f, 18.0f)
        }
        stroke {
            moveToRelative(6.0f, 6.0f)
            lineToRelative(12.0f, 12.0f)
        }
    }),
    ZAP({
        stroke {
            moveTo(15.914f, 4.0f)
            arcToRelative(1.5f, 1.5f, 0.0f, false, false, -2.474f, -1.561f)
            lineToRelative(-9.0f, 9.0f)
            arcTo(1.5f, 1.5f, 0.0f, false, false, 5.5f, 14.0f)
            horizontalLineToRelative(4.002f)
            arcToRelative(0.5f, 0.5f, 0.0f, false, true, 0.471f, 0.666f)
            lineTo(8.086f, 20.0f)
            arcToRelative(1.5f, 1.5f, 0.0f, false, false, 2.475f, 1.56f)
            lineToRelative(9.0f, -9.0f)
            arcTo(1.5f, 1.5f, 0.0f, false, false, 18.5f, 10.0f)
            horizontalLineToRelative(-3.997f)
            arcToRelative(0.5f, 0.5f, 0.0f, false, true, -0.472f, -0.667f)
            close()
        }
    }),
    ZAP_OFF({
        stroke {
            moveTo(10.768f, 5.111f)
            lineTo(13.44f, 2.44f)
            arcToRelative(1.5f, 1.5f, 0.0f, false, true, 2.474f, 1.561f)
            lineToRelative(-1.633f, 4.625f)
        }
        stroke {
            moveToRelative(18.889f, 13.232f)
            lineToRelative(0.672f, -0.672f)
            arcTo(1.5f, 1.5f, 0.0f, false, false, 18.5f, 10.0f)
            horizontalLineToRelative(-2.844f)
        }
        stroke {
            moveToRelative(2.0f, 2.0f)
            lineToRelative(20.0f, 20.0f)
        }
        stroke {
            moveToRelative(7.94f, 7.94f)
            lineToRelative(-3.5f, 3.499f)
            arcTo(1.5f, 1.5f, 0.0f, false, false, 5.5f, 14.0f)
            horizontalLineToRelative(4.002f)
            arcToRelative(0.5f, 0.5f, 0.0f, false, true, 0.471f, 0.666f)
            lineTo(8.086f, 20.0f)
            arcToRelative(1.5f, 1.5f, 0.0f, false, false, 2.475f, 1.56f)
            lineToRelative(5.5f, -5.5f)
        }
    }),
    ;

    private val regular by lazy { build(autoMirror = false) }
    private val mirrored by lazy { build(autoMirror = true) }

    fun vector(autoMirror: Boolean): ImageVector = if (autoMirror) mirrored else regular

    private fun build(autoMirror: Boolean): ImageVector =
        ImageVector
            .Builder(
                name = "Lucide.$name",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
                autoMirror = autoMirror,
            ).apply(draw)
            .build()
}

private fun ImageVector.Builder.stroke(
    fill: Boolean = false,
    block: PathBuilder.() -> Unit,
) {
    path(
        fill = if (fill) SolidColor(Color.Black) else null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )
}
