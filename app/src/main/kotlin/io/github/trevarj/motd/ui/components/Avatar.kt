package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.ui.theme.LocalAvatarStyle
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.NICK_GRID_COLS
import io.github.trevarj.motd.ui.theme.NICK_GRID_ROWS
import io.github.trevarj.motd.ui.theme.nickPixelGrid

/**
 * Darkness of the *applied* theme, derived from the resolved background luminance rather than
 * [isSystemInDarkTheme] -- so forced DARK/AMOLED read as dark even when the OS is light (plans/15
 * #22). Used to pick nick-color palettes and on-color contrast across the chat components.
 */
@Composable
@ReadOnlyComposable
internal fun isAppliedThemeDark(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Pick a legible on-color (black/white) for text/initials over [bg] by its luminance. */
internal fun onColorFor(bg: Color): Color =
    if (bg.luminance() < 0.5f) Color.White else Color.Black

/**
 * Circular avatar: either a pixel-art identicon or initials (first two significant chars) over a
 * color background derived from [name] via the current LocalNickColors scheme. The rendering style
 * is controlled by [LocalAvatarStyle].
 *
 * [isChannel] uses the name as-is (channels keep the leading `#`); queries fall back to their nick.
 */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    isChannel: Boolean = false,
) {
    // Avatars keep their generated/override color even when nick coloring is disabled (an all-gray
    // avatar column would be unusable, plans/13 confirmed decision #5); avatar() ignores the flag.
    val bg = LocalNickColors.current.avatar(name)
    val style = LocalAvatarStyle.current

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        when (style) {
            AvatarStyle.PIXEL_ART -> PixelArtAvatar(name = name, fg = onColorFor(bg), bg = bg)
            AvatarStyle.INITIALS -> InitialsAvatar(name = name, bg = bg, size = size, isChannel = isChannel)
        }
    }
}

/**
 * Draws a 5x5 identicon grid onto a [Canvas] that fills the clip area. "On" cells use [fg];
 * "off" cells are transparent so the circle background ([bg]) shows through at reduced opacity,
 * giving a subtle low-contrast shadow without a hard off-color tile.
 *
 * `FilterQuality.None` / `StrokeCap.Square` would be ideal but Canvas rects are already pixel-
 * aligned at integer boundaries, so the result is already crisp without explicit anti-alias config.
 */
@Composable
private fun PixelArtAvatar(name: String, fg: Color, bg: Color) {
    // Recompute only when the nick changes.
    val grid = remember(name) { nickPixelGrid(name) }
    // Slightly dimmed "off" color: bg at 20% opacity over itself -- appears as a muted tile.
    val offColor = bg.copy(alpha = 0.20f)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val cellW = size.width / NICK_GRID_COLS
        val cellH = size.height / NICK_GRID_ROWS
        drawPixelGrid(grid, cellW, cellH, fg, offColor)
    }
}

/** Draws the pre-computed boolean grid as colored cells. Pure function so it is easy to test. */
private fun DrawScope.drawPixelGrid(
    grid: BooleanArray,
    cellW: Float,
    cellH: Float,
    onColor: Color,
    offColor: Color,
) {
    for (row in 0 until NICK_GRID_ROWS) {
        for (col in 0 until NICK_GRID_COLS) {
            val on = grid[row * NICK_GRID_COLS + col]
            drawRect(
                color = if (on) onColor else offColor,
                topLeft = Offset(col * cellW, row * cellH),
                size = Size(cellW, cellH),
            )
        }
    }
}

/** Initials (first two significant chars) over the background; the original avatar style. */
@Composable
private fun InitialsAvatar(name: String, bg: Color, size: Dp, isChannel: Boolean) {
    Text(
        text = initials(name, isChannel),
        // Contrast the initials against the (possibly light) nick-color background (plans/15 #23).
        color = onColorFor(bg),
        fontWeight = FontWeight.SemiBold,
        fontSize = (size.value * 0.4f).sp,
    )
}

/** First two significant characters. Skips channel sigils and non-letter/digit leading chars. */
private fun initials(name: String, isChannel: Boolean): String {
    val stripped = name.trimStart('#', '&', '@', '+', '~', '%', '!').ifEmpty { name }
    val words = stripped.split(' ', '-', '_', '.').filter { it.isNotBlank() }
    val chars = when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}"
        stripped.length >= 2 -> stripped.take(2)
        else -> stripped.take(1).ifEmpty { "?" }
    }
    return chars.uppercase()
}

@Preview
@Composable
private fun AvatarPixelArtPreview() {
    MotdTheme {
        Avatar(name = "alice")
    }
}

@Preview
@Composable
private fun AvatarInitialsPreview() {
    MotdTheme(avatarStyle = AvatarStyle.INITIALS) {
        Avatar(name = "alice")
    }
}

@Preview
@Composable
private fun AvatarChannelPreview() {
    MotdTheme {
        Avatar(name = "#libera", isChannel = true)
    }
}
