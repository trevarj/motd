package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.data.prefs.ChatWallpaper
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.ui.components.isAppliedThemeDark
import io.github.trevarj.motd.ui.theme.MotdTheme

/**
 * Subtle, IRC-themed chat wallpaper drawn behind the message list (WhatsApp/Telegram style). The
 * artwork is a faithful, procedural reproduction of the source SVGs in
 * `docs/assets/chat-wallpapers/`: the exact `<path d="...">` geometry (parsed with Compose's
 * [PathParser], no new dependency) and `<rect>`/`<circle>` motif placements are baked into the
 * constants below and drawn on a [androidx.compose.foundation.Canvas]. The 1080-unit source tile is
 * scaled to the canvas width and repeated vertically to fill the height.
 *
 * Presets (matching the SVG pack):
 *  - CLASSIC (`irc-*-classic.svg`): stroked chat bubbles, hashtags, prompt chevrons, TLS checks,
 *    with blue + teal accent pips.
 *  - NETWORK (`irc-*-network.svg`): a faint dot grid, dashed topology links, stroked server/node
 *    glyphs, with blue + teal node accents.
 *  - PIXEL (`irc-*-pixel.svg`): filled pixel-block glyphs (blue + teal accent blocks) plus a
 *    stroked blocky-glyph group.
 *
 * THEME MAPPING (SVG hex is NOT baked): the base fill is the theme background (AMOLED keeps a true
 * black base); the main motif group is [MaterialTheme.colorScheme] `onSurfaceVariant`, the primary
 * accent is `primary`, and the secondary accent (classic/network teal, pixel green) is `tertiary`.
 * All at low alpha so bubbles/text stay readable; alphas run a touch higher on dark, chosen via
 * [isAppliedThemeDark] so forced DARK/AMOLED read dark even under a light OS. NONE keeps the plain
 * background. There is no animation.
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
    // Motif ink derived from the scheme so terminal/custom schemes stay restrained.
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary
    // Low alphas keep the pattern behind message bubbles; a touch stronger on dark to stay visible.
    val mainAlpha = if (dark) 0.09f else 0.07f
    val faintAlpha = if (dark) 0.06f else 0.045f
    val accentAlpha = if (dark) 0.10f else 0.08f

    // Parsing the path strings into Compose Paths is pure work; cache per preset so we don't reparse
    // on every recomposition/frame.
    val paths = remember(wallpaper) { pathsFor(wallpaper) }

    Box(
        modifier
            .fillMaxSize()
            .background(base)
            .drawBehind {
                when (wallpaper) {
                    ChatWallpaper.NONE -> Unit
                    ChatWallpaper.CLASSIC -> drawClassic(paths, ink, accent, accent2, mainAlpha, faintAlpha, accentAlpha)
                    ChatWallpaper.NETWORK -> drawNetwork(paths, ink, accent, accent2, mainAlpha, faintAlpha, accentAlpha)
                    ChatWallpaper.PIXEL -> drawPixel(paths, ink, accent, accent2, mainAlpha, accentAlpha)
                }
            },
    )
}

// -- Tiling --------------------------------------------------------------------------------------

/** Source SVG viewBox is 1080-wide/tall. */
private const val TILE = 1080f

/**
 * Scales the 1080-unit source tile to the canvas width and repeats it vertically to fill the height,
 * invoking [drawTile] once per row inside a scaled/translated draw context. One extra row bleeds off
 * the bottom so partial tiles at the edge stay covered.
 */
private fun DrawScope.tiled(drawTile: DrawScope.() -> Unit) {
    val s = size.width / TILE
    val tilePx = TILE * s
    val rows = (size.height / tilePx).toInt() + 1
    for (r in 0..rows) {
        translate(top = r * tilePx) {
            // Scale the 1080-unit tile to canvas width about the top-left origin.
            scale(s, s, pivot = Offset.Zero) { drawTile() }
        }
    }
}

// -- Path parsing --------------------------------------------------------------------------------

/** Parses one or more SVG `d` strings into a single Compose [Path]. */
private fun parsePaths(vararg ds: String): Path {
    val out = Path()
    for (d in ds) {
        // Each `d` gets its own parser so subpaths never bleed across glyphs.
        out.addPath(PathParser().parsePathString(d).toPath())
    }
    return out
}

/** Preset geometry parsed once (stroked-glyph groups); accents/rects are drawn from raw constants. */
private class WallpaperPaths(val main: Path, val secondary: Path? = null)

private fun pathsFor(wallpaper: ChatWallpaper): WallpaperPaths = when (wallpaper) {
    ChatWallpaper.NONE -> WallpaperPaths(Path())
    ChatWallpaper.CLASSIC -> WallpaperPaths(parsePaths(*CLASSIC_PATHS))
    ChatWallpaper.NETWORK -> WallpaperPaths(parsePaths(*NETWORK_PATHS), parsePaths(*NETWORK_LINK_PATHS))
    ChatWallpaper.PIXEL -> WallpaperPaths(parsePaths(*PIXEL_PATHS))
}

// -- Stroke styles (mirror the SVG stroke-width / caps / joins at 1080-unit scale) ---------------

private val roundStroke5 = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val roundStroke4 = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val squareStroke6 = Stroke(width = 6f, cap = StrokeCap.Square, join = StrokeJoin.Miter)

// -- CLASSIC (irc-*-classic.svg) -----------------------------------------------------------------

private fun DrawScope.drawClassic(
    paths: WallpaperPaths,
    ink: Color,
    accent: Color,
    accent2: Color,
    mainAlpha: Float,
    faintAlpha: Float,
    accentAlpha: Float,
) = tiled {
    drawPath(paths.main, ink.copy(alpha = mainAlpha), style = roundStroke5)
    drawDots(CLASSIC_ACCENT_BLUE, accent.copy(alpha = accentAlpha))
    drawDots(CLASSIC_ACCENT_TEAL, accent2.copy(alpha = accentAlpha))
    // The light SVG has a third, very faint ink dot cluster; reuse the ink at low alpha.
    drawDots(CLASSIC_ACCENT_FAINT, ink.copy(alpha = faintAlpha))
}

// -- NETWORK (irc-*-network.svg) -----------------------------------------------------------------

private fun DrawScope.drawNetwork(
    paths: WallpaperPaths,
    ink: Color,
    accent: Color,
    accent2: Color,
    mainAlpha: Float,
    faintAlpha: Float,
    accentAlpha: Float,
) = tiled {
    // Faint background dot grid (12 cols x 6 rows in the source).
    val dotColor = ink.copy(alpha = faintAlpha * 0.8f)
    for (y in NETWORK_GRID_Y) {
        for (x in NETWORK_GRID_X) {
            drawCircle(dotColor, radius = 3f, center = Offset(x, y))
        }
    }
    // Dashed topology links behind the node glyphs.
    paths.secondary?.let {
        drawPath(it, ink.copy(alpha = faintAlpha), style = networkLinkStroke())
    }
    drawPath(paths.main, ink.copy(alpha = mainAlpha), style = roundStroke5)
    drawDots(NETWORK_ACCENT_BLUE, accent.copy(alpha = accentAlpha))
    drawDots(NETWORK_ACCENT_TEAL, accent2.copy(alpha = accentAlpha))
}

/** Dashed stroke matching the source `stroke-dasharray="8 16"` links. */
private fun networkLinkStroke(): Stroke = Stroke(
    width = 4f,
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 16f)),
)

// -- PIXEL (irc-*-pixel.svg) ---------------------------------------------------------------------

private fun DrawScope.drawPixel(
    paths: WallpaperPaths,
    ink: Color,
    accent: Color,
    accent2: Color,
    mainAlpha: Float,
    accentAlpha: Float,
) = tiled {
    drawRects(PIXEL_MAIN_RECTS, ink.copy(alpha = mainAlpha))
    drawRects(PIXEL_ACCENT_BLUE, accent.copy(alpha = accentAlpha))
    drawRects(PIXEL_ACCENT_GREEN, accent2.copy(alpha = accentAlpha))
    // Blocky stroked glyph group (square caps, miter joins).
    drawPath(paths.main, ink.copy(alpha = mainAlpha), style = squareStroke6)
}

// -- Primitive helpers ---------------------------------------------------------------------------

/** Filled accent dots from a flat [cx, cy, r, cx, cy, r, ...] array. */
private fun DrawScope.drawDots(dots: FloatArray, color: Color) {
    var i = 0
    while (i < dots.size) {
        drawCircle(color, radius = dots[i + 2], center = Offset(dots[i], dots[i + 1]))
        i += 3
    }
}

/** Filled rects from a flat [x, y, w, h, ...] array. */
private fun DrawScope.drawRects(rects: FloatArray, color: Color) {
    var i = 0
    while (i < rects.size) {
        drawRect(
            color,
            topLeft = Offset(rects[i], rects[i + 1]),
            size = androidx.compose.ui.geometry.Size(rects[i + 2], rects[i + 3]),
        )
        i += 4
    }
}

// -- Baked geometry (verbatim from docs/assets/chat-wallpapers/*.svg, 1080 viewBox) --------------

// CLASSIC: main stroked glyph group (bubbles, hashtags, chevrons, checks).
private val CLASSIC_PATHS = arrayOf(
    "M98 105h64m-52-32-24 96m96-96-24 96",
    "M350 92h92a22 22 0 0 1 22 22v36a22 22 0 0 1-22 22h-52l-36 28v-28h-4a22 22 0 0 1-22-22v-36a22 22 0 0 1 22-22z",
    "M735 94h82a18 18 0 0 1 18 18v58a18 18 0 0 1-18 18h-82a18 18 0 0 1-18-18v-58a18 18 0 0 1 18-18zm18 29h42m-42 35h24",
    "M932 94l26 26-26 26m44 0h42",
    "M151 303a34 34 0 1 0 0 68h58a34 34 0 1 0 0-68h-58z",
    "M474 295v76m-38-38h76",
    "M714 296l60 36-60 36v-72z",
    "M892 291h96m-78 42h78m-96 42h96",
    "M84 534c30-42 62-42 96 0s66 42 96 0",
    "M407 517h80a20 20 0 0 1 20 20v48a20 20 0 0 1-20 20h-80a20 20 0 0 1-20-20v-48a20 20 0 0 1 20-20zm21 29h38m-38 30h62",
    "M676 514a46 46 0 1 0 0 92 46 46 0 0 0 0-92zm-44 46h88m-44-44c18 28 18 60 0 88m0-88c-18 28-18 60 0 88",
    "M920 522h38a34 34 0 0 1 0 68h-38a34 34 0 0 1 0-68zm38 0h38a34 34 0 0 1 0 68h-38",
    "M124 774h82a22 22 0 0 1 22 22v36a22 22 0 0 1-22 22h-43l-32 28v-28h-7a22 22 0 0 1-22-22v-36a22 22 0 0 1 22-22z",
    "M419 772h64m-52-32-24 96m96-96-24 96",
    "M712 766l44 44 82-82",
    "M926 782a44 44 0 1 0 88 0 44 44 0 0 0-88 0zm44-70v26m0 88v26m-70-70h26m88 0h26",
    "M258 964l26 26-26 26m44 0h42",
    "M562 942h92a22 22 0 0 1 22 22v36a22 22 0 0 1-22 22h-52l-36 28v-28h-4a22 22 0 0 1-22-22v-36a22 22 0 0 1 22-22z",
)

// CLASSIC accent groups: flat [cx, cy, r] triples.
private val CLASSIC_ACCENT_BLUE = floatArrayOf(
    264f, 132f, 8f, 548f, 132f, 7f, 619f, 292f, 7f, 318f, 488f, 6f,
    820f, 485f, 7f, 272f, 794f, 8f, 526f, 913f, 6f, 856f, 940f, 8f,
)
private val CLASSIC_ACCENT_TEAL = floatArrayOf(
    244f, 315f, 9f, 520f, 402f, 7f, 815f, 210f, 8f,
    236f, 612f, 6f, 596f, 656f, 8f, 884f, 832f, 7f,
)
private val CLASSIC_ACCENT_FAINT = floatArrayOf(
    82f, 238f, 5f, 257f, 238f, 5f, 432f, 238f, 5f,
    833f, 660f, 5f, 858f, 660f, 5f, 883f, 660f, 5f,
)

// NETWORK: dashed link polylines.
private val NETWORK_LINK_PATHS = arrayOf(
    "M124 144 286 244 460 168 664 286 872 182 1012 318",
    "M80 590 246 464 438 618 640 492 806 662 1010 542",
    "M134 930 312 780 510 900 700 766 926 894",
)

// NETWORK: main stroked glyph group (server nodes, bubbles, chevrons).
private val NETWORK_PATHS = arrayOf(
    "M222 172h70a18 18 0 0 1 18 18v38a18 18 0 0 1-18 18h-34l-28 24v-24h-8a18 18 0 0 1-18-18v-38a18 18 0 0 1 18-18z",
    "M540 112h72m-58-34-26 104m110-104-26 104",
    "M870 106h76a18 18 0 0 1 18 18v54a18 18 0 0 1-18 18h-76a18 18 0 0 1-18-18v-54a18 18 0 0 1 18-18zm16 27h44m-44 30h44",
    "M143 412a38 38 0 1 0 0 76 38 38 0 0 0 0-76zm0 38h116m-58-58c24 36 24 80 0 116m0-116c-24 36-24 80 0 116",
    "M414 400h52a20 20 0 0 1 20 20v46a20 20 0 0 1-20 20h-52a20 20 0 0 1-20-20v-46a20 20 0 0 1 20-20zm26-42v42",
    "M696 394l25 25-25 25m42 0h42",
    "M900 404h72m-58-34-26 104m110-104-26 104",
    "M224 716h74a18 18 0 0 1 18 18v38a18 18 0 0 1-18 18h-74a18 18 0 0 1-18-18v-38a18 18 0 0 1 18-18z",
    "M564 710h82a20 20 0 0 1 20 20v44a20 20 0 0 1-20 20h-82a20 20 0 0 1-20-20v-44a20 20 0 0 1 20-20z",
    "M860 714h46a32 32 0 0 1 0 64h-46a32 32 0 0 1 0-64zm46 0h46a32 32 0 0 1 0 64h-46",
)

// NETWORK background dot grid coordinates.
private val NETWORK_GRID_X = floatArrayOf(80f, 160f, 240f, 320f, 400f, 480f, 560f, 640f, 720f, 800f, 880f, 960f)
private val NETWORK_GRID_Y = floatArrayOf(80f, 240f, 400f, 560f, 720f, 880f)

// NETWORK accent node groups: flat [cx, cy, r] triples.
private val NETWORK_ACCENT_BLUE = floatArrayOf(
    124f, 144f, 10f, 460f, 168f, 10f, 872f, 182f, 10f,
    438f, 618f, 10f, 806f, 662f, 10f, 700f, 766f, 10f,
)
private val NETWORK_ACCENT_TEAL = floatArrayOf(
    286f, 244f, 9f, 664f, 286f, 9f, 246f, 464f, 9f,
    640f, 492f, 9f, 312f, 780f, 9f, 926f, 894f, 9f,
)

// PIXEL: main filled block group, flat [x, y, w, h] quads.
private val PIXEL_MAIN_RECTS = floatArrayOf(
    94f, 94f, 18f, 18f, 112f, 94f, 18f, 18f, 148f, 94f, 18f, 18f, 166f, 94f, 18f, 18f,
    112f, 112f, 18f, 18f, 148f, 112f, 18f, 18f, 94f, 130f, 18f, 18f, 112f, 130f, 18f, 18f,
    148f, 130f, 18f, 18f, 166f, 130f, 18f, 18f,
    852f, 182f, 18f, 18f, 870f, 200f, 18f, 18f, 888f, 218f, 54f, 18f,
    214f, 382f, 72f, 18f, 214f, 418f, 72f, 18f, 232f, 364f, 18f, 90f, 268f, 364f, 18f, 90f,
    748f, 390f, 18f, 72f, 766f, 390f, 54f, 18f, 820f, 408f, 18f, 36f, 766f, 444f, 54f, 18f,
    110f, 776f, 18f, 18f, 128f, 794f, 18f, 18f, 146f, 812f, 54f, 18f,
    420f, 828f, 18f, 18f, 438f, 846f, 18f, 18f, 456f, 864f, 18f, 18f, 474f, 882f, 72f, 18f,
)

// PIXEL blue accent blocks.
private val PIXEL_ACCENT_BLUE = floatArrayOf(
    380f, 116f, 18f, 18f, 416f, 116f, 18f, 18f, 452f, 116f, 18f, 18f,
    602f, 264f, 18f, 18f, 638f, 264f, 18f, 18f, 674f, 264f, 18f, 18f,
    314f, 602f, 18f, 18f, 350f, 602f, 18f, 18f, 386f, 602f, 18f, 18f,
    788f, 908f, 18f, 18f, 824f, 908f, 18f, 18f, 860f, 908f, 18f, 18f,
)

// PIXEL green accent blocks.
private val PIXEL_ACCENT_GREEN = floatArrayOf(
    616f, 116f, 18f, 18f, 634f, 134f, 18f, 18f, 652f, 152f, 18f, 18f,
    102f, 556f, 18f, 18f, 120f, 556f, 18f, 18f, 138f, 556f, 18f, 18f,
    904f, 614f, 18f, 18f, 922f, 632f, 18f, 18f, 940f, 650f, 18f, 18f,
)

// PIXEL stroked blocky-glyph group (square caps, miter joins).
private val PIXEL_PATHS = arrayOf(
    "M348 300h94v58h-58l-36 30v-30h-30v-28",
    "M560 496h82v72h-82zM582 496v-26h38v26",
    "M833 496h100v68h-100zM858 522h24m24 0h2m-50 18h50",
    "M256 934h84m-70-42-28 126m126-126-28 126",
    "M628 768h86m-70-42-28 126m126-126-28 126",
    "M896 842c28-34 58-34 88 0s60 34 88 0",
)

// -- Previews (light + dark for each preset; review without installing) --------------------------

@Composable
private fun WallpaperPreviewCell(wallpaper: ChatWallpaper, themeMode: ThemeMode) {
    MotdTheme(themeMode = themeMode, dynamicColor = false) {
        Box(Modifier.size(300.dp)) {
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

@Preview(name = "Picker light")
@Composable
private fun PickerLightPreview() {
    MotdTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
        ChatWallpaperPicker(current = ChatWallpaper.CLASSIC, onSelect = {})
    }
}

@Preview(name = "Picker dark")
@Composable
private fun PickerDarkPreview() {
    MotdTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
        ChatWallpaperPicker(current = ChatWallpaper.NETWORK, onSelect = {})
    }
}
