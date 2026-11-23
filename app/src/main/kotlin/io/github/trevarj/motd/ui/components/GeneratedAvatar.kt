package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.avatar.IrcSpriteV2Renderer
import io.github.trevarj.motd.avatar.IrcSpriteV2Theme
import io.github.trevarj.motd.data.prefs.AvatarShape
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.theme.LocalAvatarShape
import io.github.trevarj.motd.ui.theme.LocalMotdSemanticColors
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.contrastRatio
import io.github.trevarj.motd.ui.theme.cornerPercent
import io.github.trevarj.motd.ui.theme.ensureContrast
import io.github.trevarj.motd.ui.theme.readableForeground
import io.github.trevarj.motd.ui.theme.shape

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
    val avatarShape = LocalAvatarShape.current
    val shape = if (avatarShape == AvatarShape.DEFAULT) CircleShape else avatarShape.shape(isChannel = false)
    val cornerPercent = avatarShape.cornerPercent(isChannel = false)
    val bitmap =
        remember(context, name, accent, base, sizePx, includeAccessory, dark, cornerPercent) {
            IrcSpriteV2Renderer.render(
                context = context,
                name = name,
                accent = accent.toArgb(),
                sizePx = sizePx,
                baseColor = base.toArgb(),
                ringColor = accent.copy(alpha = 0.52f).toArgb(),
                includeAccessory = includeAccessory,
                theme = if (dark) IrcSpriteV2Theme.DARK else IrcSpriteV2Theme.LIGHT,
                cornerPercent = cornerPercent,
            )
        }
    if (bitmap == null) {
        // A missing/corrupt optional catalog must not turn a contact list into blank space.
        InitialsAvatar(name, accent, size, isChannel = false, shape = shape, modifier = modifier)
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.size(size).clip(shape),
        )
    }
}

@Composable
internal fun IrcNetworkBadge(
    size: Dp,
    state: IrcClientState,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val surface = scheme.surfaceContainerHighest
    val foreground = scheme.readableForeground(scheme.onSurfaceVariant, surface, minimumContrast = 3.0)
    val semanticColors = LocalMotdSemanticColors.current
    val filledCount =
        when (state) {
            IrcClientState.Disconnected, is IrcClientState.Failed -> 1
            IrcClientState.Connecting, IrcClientState.Registering -> 2
            is IrcClientState.Ready -> 3
        }
    val accent =
        when (filledCount) {
            1 -> scheme.error
            2 -> semanticColors.warning
            else -> semanticColors.success
        }
    val statusColor = if (contrastRatio(accent, surface) < 3.0) ensureContrast(accent, listOf(surface), minimum = 3.0) else accent
    val shape = LocalAvatarShape.current.shape(isChannel = true)
    Canvas(modifier = modifier.size(size).clip(shape).background(surface)) {
        val side = this.size.minDimension
        val top = Offset(side * 0.5f, side * 0.35625f)
        val left = Offset(side * 0.3f, side * 0.64375f)
        val right = Offset(side * 0.7f, side * 0.64375f)
        val strokeWidth = side * 0.05f
        val radius = side * 0.1f
        drawLine(foreground, top, left, strokeWidth, StrokeCap.Round)
        drawLine(foreground, top, right, strokeWidth, StrokeCap.Round)
        drawLine(foreground, left, right, strokeWidth, StrokeCap.Round)
        repeat(3) { index ->
            val center =
                when (index) {
                    0 -> left
                    1 -> top
                    else -> right
                }
            drawCircle(if (index == filledCount - 1) statusColor else foreground, radius, center)
            if (index >= filledCount) drawCircle(surface, radius - strokeWidth, center)
        }
    }
}
