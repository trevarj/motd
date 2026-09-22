package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.avatar.IrcSpriteV2Renderer
import io.github.trevarj.motd.avatar.IrcSpriteV2Theme
import io.github.trevarj.motd.ui.theme.LocalNickColors

internal fun nickTokens(nick: String): List<String> {
    val stripped = nick.trim().trimStart('~', '&', '@', '%', '+')
    if (stripped.isEmpty()) return emptyList()
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var previous: Char? = null

    fun flush() {
        if (current.isNotEmpty()) {
            tokens += current.toString().lowercase()
            current.clear()
        }
    }

    for (char in stripped) {
        if (!char.isLetterOrDigit()) {
            flush()
            previous = null
            continue
        }
        val previousChar = previous
        val boundary =
            previousChar != null && (
                (previousChar.isLowerCase() && char.isUpperCase()) ||
                    (previousChar.isLetter() && char.isDigit()) ||
                    (previousChar.isDigit() && char.isLetter())
            )
        if (boundary) flush()
        current.append(char)
        previous = char
    }
    flush()
    return tokens
}

internal fun tokenMatchesAlias(
    token: String,
    alias: String,
): Boolean = token == alias || (alias.length >= 4 && (token.startsWith(alias) || token.endsWith(alias)))

/** Rasterized pixel operators. Source layers and finished scenes are cached by the shared renderer. */
@Composable
internal fun IrcSpriteV2Avatar(
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val dark = isAppliedThemeDark()
    val accent = LocalNickColors.current.avatar(name)
    val base = if (dark) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh
    val sizePx = with(density) { size.roundToPx() }
    val includeAccessory = size >= 24.dp
    val bitmap =
        remember(context, name, accent, base, sizePx, includeAccessory, dark) {
            IrcSpriteV2Renderer.render(
                context = context,
                name = name,
                accent = accent.toArgb(),
                sizePx = sizePx,
                baseColor = base.toArgb(),
                ringColor = accent.copy(alpha = 0.52f).toArgb(),
                includeAccessory = includeAccessory,
                theme = if (dark) IrcSpriteV2Theme.DARK else IrcSpriteV2Theme.LIGHT,
            )
        }
    if (bitmap == null) {
        // A missing/corrupt optional catalog must not turn a contact list into blank space.
        InitialsAvatar(name, accent, size, isChannel = false, shape = CircleShape, modifier = modifier)
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.size(size).clip(CircleShape),
        )
    }
}

/** One neutral topology mark for every network; the drawer owns its connection-status dot. */
@Composable
internal fun IrcNetworkBadge(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest
    val foreground = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier.size(size)) {
        val side = this.size.minDimension
        val top = Offset(side * 0.5f, side * 0.35f)
        val left = Offset(side * 0.3f, side * 0.65f)
        val right = Offset(side * 0.7f, side * 0.65f)
        val stroke = Stroke(width = side * 0.05f, cap = StrokeCap.Round)
        val radius = side * 0.07f
        drawCircle(surface)
        drawLine(foreground, top, left, stroke.width, StrokeCap.Round)
        drawLine(foreground, top, right, stroke.width, StrokeCap.Round)
        drawLine(foreground, left, right, stroke.width, StrokeCap.Round)
        drawCircle(surface, radius, top)
        drawCircle(surface, radius, left)
        drawCircle(surface, radius, right)
        drawCircle(foreground, radius, top, style = stroke)
        drawCircle(foreground, radius, left, style = stroke)
        drawCircle(foreground, radius, right, style = stroke)
    }
}
