package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.data.prefs.ChatWallpaper
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.ui.components.isAppliedThemeDark
import io.github.trevarj.motd.ui.theme.MotdTheme

/**
 * Subtle, IRC-themed chat wallpaper drawn behind the message list (WhatsApp/Telegram style). The
 * motifs are tiled on a repeating grid with low alpha so bubbles/text stay readable; there is no
 * animation. Presets:
 *  - CLASSIC: quiet chat-app feel — chat bubbles, hashtag/channel marks, prompt cursors, status pips.
 *  - NETWORK: server/network topology — nodes, bridge links (bouncer/network), TLS locks.
 *  - PIXEL: restrained pixel/terminal grid — hash blocks, prompt carets, small square pips.
 *
 * The base fill matches the current theme background (AMOLED keeps a true-black base) and the motif
 * ink derives from [MaterialTheme.colorScheme] so terminal/custom schemes stay restrained. Palette
 * lightness is chosen via [isAppliedThemeDark] so forced DARK/AMOLED read as dark even under a light OS.
 */
@Composable
fun ChatWallpaperBackground(
    wallpaper: ChatWallpaper,
    modifier: Modifier = Modifier,
) {
    // NONE is a no-op: the plain theme background shows through unchanged (opt-in, no behavior change).
    if (wallpaper == ChatWallpaper.NONE) {
        Box(modifier)
        return
    }

    val dark = isAppliedThemeDark()
    val base = MaterialTheme.colorScheme.background
    // Motif ink derived from the scheme: onSurfaceVariant reads well on both light and dark surfaces.
    // A very low alpha keeps the pattern in the background behind message bubbles.
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val strong = if (dark) 0.055f else 0.045f
    val faint = if (dark) 0.035f else 0.028f
    val accent = MaterialTheme.colorScheme.primary

    // Base fill first (opaque, AMOLED-black-safe), then the tiled motif behind content.
    Box(
        modifier
            .fillMaxSize()
            .background(base)
            .drawBehind {
                when (wallpaper) {
                    ChatWallpaper.NONE -> Unit
                    ChatWallpaper.CLASSIC -> drawClassic(ink, accent, strong, faint)
                    ChatWallpaper.NETWORK -> drawNetwork(ink, accent, strong, faint)
                    ChatWallpaper.PIXEL -> drawPixel(ink, accent, strong, faint)
                }
            },
    )
}

// -- Tiling helper ------------------------------------------------------------------------------

/** Tiles [drawCell] across the canvas on a [cell]-dp grid, offsetting alternate rows by half a cell
 *  for a natural, non-gridded scatter. The origin bleeds one cell off-screen so edges stay covered. */
private fun DrawScope.tile(cellPx: Float, drawCell: DrawScope.(row: Int, col: Int) -> Unit) {
    val cols = (size.width / cellPx).toInt() + 2
    val rows = (size.height / cellPx).toInt() + 2
    for (r in -1 until rows) {
        val rowOffset = if (r % 2 == 0) 0f else cellPx / 2f
        for (c in -1 until cols) {
            translate(left = c * cellPx + rowOffset, top = r * cellPx) {
                drawCell(r, c)
            }
        }
    }
}

// -- CLASSIC: chat bubbles, hashtags, prompt cursors, status pips -------------------------------

private fun DrawScope.drawClassic(ink: Color, accent: Color, strong: Float, faint: Float) {
    val cell = 132.dp.toPx()
    val s = cell / 132f // scale factor so px sizes below read like the 132-unit reference tile
    tile(cell) { r, c ->
        // Rotate motifs through the 4-tile cycle for variety without a noisy per-tile RNG.
        when ((r * 3 + c).mod(4)) {
            0 -> chatBubble(ink.copy(alpha = strong), 44f * s, 28f * s, 34f * s)
            1 -> hashtag(ink.copy(alpha = faint), 30f * s, 40f * s, 24f * s)
            2 -> promptCursor(ink.copy(alpha = strong), accent.copy(alpha = strong * 1.4f), 34f * s, 40f * s, 26f * s)
            else -> statusPip(accent.copy(alpha = strong * 1.5f), 46f * s, 44f * s, 4.5f * s)
        }
    }
}

/** Rounded speech bubble outline with a small tail. */
private fun DrawScope.chatBubble(color: Color, x: Float, y: Float, w: Float) {
    val h = w * 0.66f
    val rr = w * 0.28f
    val path = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                Rect(Offset(x, y), Size(w, h)),
                androidx.compose.ui.geometry.CornerRadius(rr, rr),
            ),
        )
        // Tail at the bottom-left.
        moveTo(x + w * 0.22f, y + h)
        lineTo(x + w * 0.10f, y + h + h * 0.24f)
        lineTo(x + w * 0.40f, y + h)
        close()
    }
    drawPath(path, color, style = Stroke(width = 2.4f))
}

/** Two horizontal + two vertical strokes forming a `#` channel mark. */
private fun DrawScope.hashtag(color: Color, x: Float, y: Float, s: Float) {
    val g = s / 3f
    // Verticals (slightly slanted like a typeset hash).
    drawLine(color, Offset(x + g, y), Offset(x + g - s * 0.08f, y + s), strokeWidth = 2.4f, cap = StrokeCap.Round)
    drawLine(color, Offset(x + 2 * g, y), Offset(x + 2 * g - s * 0.08f, y + s), strokeWidth = 2.4f, cap = StrokeCap.Round)
    // Horizontals.
    drawLine(color, Offset(x, y + g), Offset(x + s, y + g), strokeWidth = 2.4f, cap = StrokeCap.Round)
    drawLine(color, Offset(x, y + 2 * g), Offset(x + s, y + 2 * g), strokeWidth = 2.4f, cap = StrokeCap.Round)
}

/** A shell prompt `>` chevron with a block cursor. */
private fun DrawScope.promptCursor(ink: Color, cursor: Color, x: Float, y: Float, s: Float) {
    drawLine(ink, Offset(x, y), Offset(x + s * 0.4f, y + s * 0.5f), strokeWidth = 2.6f, cap = StrokeCap.Round)
    drawLine(ink, Offset(x + s * 0.4f, y + s * 0.5f), Offset(x, y + s), strokeWidth = 2.6f, cap = StrokeCap.Round)
    // Block cursor to the right of the chevron.
    drawRect(cursor, topLeft = Offset(x + s * 0.6f, y + s * 0.18f), size = Size(s * 0.22f, s * 0.64f))
}

/** Small filled status dot (online pip). */
private fun DrawScope.statusPip(color: Color, x: Float, y: Float, radius: Float) {
    drawCircle(color, radius = radius, center = Offset(x, y))
}

// -- NETWORK: nodes, bridge links, TLS locks ----------------------------------------------------

private fun DrawScope.drawNetwork(ink: Color, accent: Color, strong: Float, faint: Float) {
    val cell = 148.dp.toPx()
    val s = cell / 148f
    tile(cell) { r, c ->
        // Faint bridge link between adjacent nodes (bouncer/network bridge feel).
        drawLine(
            ink.copy(alpha = faint),
            Offset(30f * s, 40f * s),
            Offset(118f * s, 96f * s),
            strokeWidth = 1.8f,
        )
        when ((r + c).mod(3)) {
            0 -> serverNode(ink.copy(alpha = strong), accent.copy(alpha = strong), 30f * s, 40f * s, 16f * s)
            1 -> tlsLock(ink.copy(alpha = strong), 100f * s, 34f * s, 22f * s)
            else -> serverNode(ink.copy(alpha = strong), accent.copy(alpha = strong), 108f * s, 92f * s, 13f * s)
        }
    }
}

/** A network node: ringed circle with a filled accent core. */
private fun DrawScope.serverNode(ring: Color, core: Color, x: Float, y: Float, radius: Float) {
    drawCircle(ring, radius = radius, center = Offset(x, y), style = Stroke(width = 2.2f))
    drawCircle(core, radius = radius * 0.34f, center = Offset(x, y))
}

/** A padlock outline (TLS): rounded body plus a shackle arc drawn as a stroked arc. */
private fun DrawScope.tlsLock(color: Color, x: Float, y: Float, s: Float) {
    val bodyW = s
    val bodyH = s * 0.78f
    val bodyTop = y + s * 0.5f
    drawRoundRect(
        color,
        topLeft = Offset(x, bodyTop),
        size = Size(bodyW, bodyH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.16f, s * 0.16f),
        style = Stroke(width = 2.2f),
    )
    // Shackle: a stroked semicircle arc above the body.
    val arcSize = s * 0.6f
    drawArc(
        color = color,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(x + (bodyW - arcSize) / 2f, bodyTop - arcSize * 0.7f),
        size = Size(arcSize, arcSize),
        style = Stroke(width = 2.2f),
    )
    // Keyhole dot.
    drawCircle(color, radius = s * 0.06f, center = Offset(x + bodyW / 2f, bodyTop + bodyH * 0.45f))
}

// -- PIXEL: restrained pixel/terminal grid ------------------------------------------------------

private fun DrawScope.drawPixel(ink: Color, accent: Color, strong: Float, faint: Float) {
    val cell = 108.dp.toPx()
    val s = cell / 108f
    val unit = 6f * s // pixel unit
    tile(cell) { r, c ->
        when ((r * 2 + c).mod(4)) {
            0 -> pixelHash(ink.copy(alpha = strong), 24f * s, 30f * s, unit)
            1 -> pixelCaret(accent.copy(alpha = strong), 62f * s, 40f * s, unit)
            2 -> pixelPip(ink.copy(alpha = faint), 40f * s, 74f * s, unit)
            else -> pixelPip(accent.copy(alpha = strong), 70f * s, 78f * s, unit)
        }
    }
}

/** A `#` rendered from small squares on a pixel grid (blocky, terminal feel). */
private fun DrawScope.pixelHash(color: Color, x: Float, y: Float, u: Float) {
    // Cells of a 5x5 grid forming a hash: two rows + two columns.
    val on = setOf(
        1 to 0, 3 to 0,
        0 to 1, 1 to 1, 2 to 1, 3 to 1, 4 to 1,
        1 to 2, 3 to 2,
        0 to 3, 1 to 3, 2 to 3, 3 to 3, 4 to 3,
        1 to 4, 3 to 4,
    )
    for ((cx, cy) in on) {
        drawRect(color, topLeft = Offset(x + cx * u, y + cy * u), size = Size(u * 0.9f, u * 0.9f))
    }
}

/** A blocky prompt caret `>_` from pixels. */
private fun DrawScope.pixelCaret(color: Color, x: Float, y: Float, u: Float) {
    val on = setOf(
        0 to 0,
        1 to 1,
        2 to 2,
        1 to 3,
        0 to 4,
    )
    for ((cx, cy) in on) {
        drawRect(color, topLeft = Offset(x + cx * u, y + cy * u), size = Size(u * 0.9f, u * 0.9f))
    }
    // Underscore cursor block to the right.
    drawRect(color, topLeft = Offset(x + 3.4f * u, y + 4 * u), size = Size(u * 1.8f, u * 0.9f))
}

/** Single square pixel pip. */
private fun DrawScope.pixelPip(color: Color, x: Float, y: Float, u: Float) {
    drawRect(color, topLeft = Offset(x, y), size = Size(u * 1.4f, u * 1.4f))
}

// -- Previews (light + dark for each preset; review without installing) --------------------------

@Composable
private fun WallpaperPreviewCell(wallpaper: ChatWallpaper, themeMode: ThemeMode) {
    MotdTheme(themeMode = themeMode, dynamicColor = false) {
        Box(Modifier.size(200.dp)) {
            ChatWallpaperBackground(wallpaper)
        }
    }
}

@Preview(name = "Classic light")
@Composable
private fun ClassicLightPreview() = WallpaperPreviewCell(ChatWallpaper.CLASSIC, ThemeMode.LIGHT)

@Preview(name = "Classic dark")
@Composable
private fun ClassicDarkPreview() = WallpaperPreviewCell(ChatWallpaper.CLASSIC, ThemeMode.DARK)

@Preview(name = "Network light")
@Composable
private fun NetworkLightPreview() = WallpaperPreviewCell(ChatWallpaper.NETWORK, ThemeMode.LIGHT)

@Preview(name = "Network dark")
@Composable
private fun NetworkDarkPreview() = WallpaperPreviewCell(ChatWallpaper.NETWORK, ThemeMode.DARK)

@Preview(name = "Pixel light")
@Composable
private fun PixelLightPreview() = WallpaperPreviewCell(ChatWallpaper.PIXEL, ThemeMode.LIGHT)

@Preview(name = "Pixel dark")
@Composable
private fun PixelDarkPreview() = WallpaperPreviewCell(ChatWallpaper.PIXEL, ThemeMode.DARK)

@Preview(name = "Pixel amoled")
@Composable
private fun PixelAmoledPreview() = WallpaperPreviewCell(ChatWallpaper.PIXEL, ThemeMode.AMOLED)
