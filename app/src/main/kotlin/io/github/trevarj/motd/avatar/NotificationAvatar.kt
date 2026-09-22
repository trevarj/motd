package io.github.trevarj.motd.avatar

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.ui.theme.paletteNickColor

private const val AVATAR_SIZE_PX = 64

/**
 * System UI renders notification people from bitmaps rather than the app's Compose tree. Keep the
 * selectable avatar treatments recognizable at notification scale without loading remote content
 * while posting an incoming message.
 */
internal fun notificationAvatarIcon(
    context: Context,
    name: String,
    style: AvatarStyle,
): IconCompat = IconCompat.createWithBitmap(notificationAvatarBitmap(context, name, style))

internal fun notificationAvatarBitmap(
    context: Context,
    name: String,
    style: AvatarStyle,
): Bitmap {
    val dark =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    val accent = notificationNickColor(name, dark)
    return createBitmap(AVATAR_SIZE_PX, AVATAR_SIZE_PX).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (style) {
            AvatarStyle.MONOGRAM -> {
                drawMonogram(canvas, paint, name, accent, dark)
            }

            AvatarStyle.INITIALS -> {
                drawInitials(canvas, paint, name, accent)
            }

            AvatarStyle.IRC_SPRITE -> {
                IrcSpriteV2Renderer
                    .render(
                        context = context,
                        name = name,
                        accent = accent,
                        sizePx = AVATAR_SIZE_PX,
                        baseColor = if (dark) Color.rgb(54, 52, 59) else Color.rgb(243, 241, 248),
                        ringColor = ColorUtils.setAlphaComponent(accent, 133),
                        theme = if (dark) IrcSpriteV2Theme.DARK else IrcSpriteV2Theme.LIGHT,
                    )?.let { rendered -> canvas.drawBitmap(rendered, 0f, 0f, paint) }
                    ?: drawInitials(canvas, paint, name, accent)
            }

            // System UI always needs a person icon; "hide avatars" is an in-app choice, so fall
            // back to the plainest treatment rather than posting a blank square.
            AvatarStyle.NONE -> {
                drawInitials(canvas, paint, name, accent)
            }
        }
    }
}

private fun drawMonogram(
    canvas: Canvas,
    paint: Paint,
    name: String,
    accent: Int,
    dark: Boolean,
) {
    val base = if (dark) Color.rgb(37, 39, 43) else Color.rgb(243, 241, 248)
    val fill = ColorUtils.blendARGB(base, accent, if (dark) 0.26f else 0.18f)
    paint.style = Paint.Style.FILL
    paint.color = fill
    canvas.drawCircle(32f, 32f, 31f, paint)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 2f
    paint.color = ColorUtils.setAlphaComponent(accent, 102)
    canvas.drawCircle(32f, 32f, 30f, paint)
    drawCenteredText(canvas, paint, avatarInitials(name).take(1), ColorUtils.blendARGB(accent, if (dark) Color.WHITE else Color.BLACK, 0.30f), 27f)
}

private fun drawInitials(
    canvas: Canvas,
    paint: Paint,
    name: String,
    accent: Int,
) {
    paint.style = Paint.Style.FILL
    paint.color = accent
    canvas.drawCircle(32f, 32f, 31f, paint)
    drawCenteredText(canvas, paint, avatarInitials(name), onColorFor(accent), 23f)
}

private fun drawCenteredText(
    canvas: Canvas,
    paint: Paint,
    text: String,
    color: Int,
    size: Float,
) {
    paint.style = Paint.Style.FILL
    paint.color = color
    paint.textSize = size
    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    paint.textAlign = Paint.Align.CENTER
    val baseline = 32f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(text, 32f, baseline, paint)
}

private fun avatarInitials(name: String): String {
    val stripped = name.trimStart('#', '&', '@', '+', '~', '%', '!').ifEmpty { name }
    val words = stripped.split(' ', '-', '_', '.').filter { it.isNotBlank() }
    val chars =
        when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}"
            stripped.length >= 2 -> stripped.take(2)
            else -> stripped.take(1).ifEmpty { "?" }
        }
    return chars.uppercase()
}

// Reuse NickColor's CLASSIC resolvers directly so notification people match their in-app identity
// colors; the canonical nick is passed in because these hash the string they are given.
private fun notificationNickColor(
    name: String,
    dark: Boolean,
): Int = paletteNickColor(canonicalAvatarNick(name), dark, NickColorPalette.CLASSIC).toArgb()

private fun onColorFor(color: Int): Int = if (ColorUtils.calculateLuminance(color) < 0.5) Color.WHITE else Color.BLACK
