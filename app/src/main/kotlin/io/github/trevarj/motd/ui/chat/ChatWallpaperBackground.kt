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
 *  - CLASSIC (`irc-*-classic.svg`): stroked chat bubbles, hashtags, prompt chevrons, TLS shields,
 *    with a single stroked blue accent group.
 *  - NETWORK (`irc-*-network.svg`): faint topology link polylines behind stroked server/node
 *    glyphs.
 *  - PIXEL (`irc-*-pixel.svg`): filled pixel-block glyphs with blue + green accent blocks.
 *
 * THEME MAPPING (SVG hex is NOT baked): the base fill is the theme background (AMOLED keeps a true
 * black base); the main motif group is [MaterialTheme.colorScheme] `onSurfaceVariant`, the primary
 * accent is `primary`, and the pixel green accent is `tertiary`. All at low alpha so bubbles/text
 * stay readable; alphas run a touch higher on dark, chosen via [isAppliedThemeDark] so forced
 * DARK/AMOLED read dark even under a light OS. NONE keeps the plain background. There is no
 * animation.
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
                    ChatWallpaper.CLASSIC -> drawClassic(paths, ink, accent, mainAlpha, accentAlpha)
                    ChatWallpaper.NETWORK -> drawNetwork(paths, ink, mainAlpha, faintAlpha)
                    ChatWallpaper.PIXEL -> drawPixel(ink, accent, accent2, mainAlpha, accentAlpha)
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

/**
 * Preset geometry parsed once. [main] is the primary stroked-glyph group; [secondary] is the
 * classic blue accent stroke / network topology links. Pixel rects are drawn from raw constants.
 */
private class WallpaperPaths(val main: Path, val secondary: Path? = null)

private fun pathsFor(wallpaper: ChatWallpaper): WallpaperPaths = when (wallpaper) {
    ChatWallpaper.NONE -> WallpaperPaths(Path())
    ChatWallpaper.CLASSIC -> WallpaperPaths(parsePaths(*CLASSIC_PATHS), parsePaths(*CLASSIC_ACCENT_PATHS))
    ChatWallpaper.NETWORK -> WallpaperPaths(parsePaths(*NETWORK_PATHS), parsePaths(*NETWORK_LINK_PATHS))
    ChatWallpaper.PIXEL -> WallpaperPaths(Path())
}

// -- Stroke styles (mirror the SVG stroke-width / caps / joins at 1080-unit scale) ---------------

private val roundStroke5 = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val roundStroke6 = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val roundStroke4 = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)

// -- CLASSIC (irc-*-classic.svg) -----------------------------------------------------------------

private fun DrawScope.drawClassic(
    paths: WallpaperPaths,
    ink: Color,
    accent: Color,
    mainAlpha: Float,
    accentAlpha: Float,
) = tiled {
    drawPath(paths.main, ink.copy(alpha = mainAlpha), style = roundStroke5)
    // Blue accent stroke group (SVG stroke-width 6, round cap/join).
    paths.secondary?.let {
        drawPath(it, accent.copy(alpha = accentAlpha), style = roundStroke6)
    }
}

// -- NETWORK (irc-*-network.svg) -----------------------------------------------------------------

private fun DrawScope.drawNetwork(
    paths: WallpaperPaths,
    ink: Color,
    mainAlpha: Float,
    faintAlpha: Float,
) = tiled {
    // Faint topology links behind the node glyphs (SVG stroke-width 4, round cap).
    paths.secondary?.let {
        drawPath(it, ink.copy(alpha = faintAlpha), style = roundStroke4)
    }
    drawPath(paths.main, ink.copy(alpha = mainAlpha), style = roundStroke5)
}

// -- PIXEL (irc-*-pixel.svg) ---------------------------------------------------------------------

private fun DrawScope.drawPixel(
    ink: Color,
    accent: Color,
    accent2: Color,
    mainAlpha: Float,
    accentAlpha: Float,
) = tiled {
    drawRects(PIXEL_MAIN_RECTS, ink.copy(alpha = mainAlpha))
    drawRects(PIXEL_ACCENT_BLUE, accent.copy(alpha = accentAlpha))
    drawRects(PIXEL_ACCENT_GREEN, accent2.copy(alpha = accentAlpha))
}

// -- Primitive helpers ---------------------------------------------------------------------------

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
    "M104 78 82 170m94-92-22 92M68 112h116M60 145h116",
    "M350 82h92a22 22 0 0 1 22 22v46a22 22 0 0 1-22 22h-48l-38 30v-30h-6a22 22 0 0 1-22-22v-46a22 22 0 0 1 22-22z",
    "M724 84h104a18 18 0 0 1 18 18v76a18 18 0 0 1-18 18H724a18 18 0 0 1-18-18v-76a18 18 0 0 1 18-18zm10 35h84m-84 38h84",
    "m932 102 30 30-30 30m52 0h54",
    "M72 334h54m0 0a34 34 0 0 1 68 0m0 0h54m-156 0a34 34 0 0 0 68 0m0 0a34 34 0 0 0 68 0",
    "M426 305h92v82h-92zm22 0v-24a24 24 0 0 1 48 0v24m-24 35v18",
    "M742 374a48 48 0 1 1 28-87v61c0 16 20 17 30 6 20-22 12-76-10-94-34-29-90-20-114 17-28 44-4 106 48 119 24 6 48 1 66-10m-48-60a22 22 0 1 0 0 44 22 22 0 0 0 0-44z",
    "M918 292h96m-96 42h72m-72 42h96",
    "M116 518a48 48 0 1 0 0 96 48 48 0 0 0 0-96zm-46 48h92m-46-46c18 28 18 64 0 92m0-92c-18 28-18 64 0 92",
    "M390 520h86l30 30v68H390zm86 0v30h30m-88 24h58m-58 24h42",
    "M672 546h42a30 30 0 0 1 0 60h-42a30 30 0 0 1 0-60zm42 0h42a30 30 0 0 1 0 60h-42m-20-30h40",
    "M918 532h104v76H918zM940 555h60m-60 30h36",
    "M82 802h132a22 22 0 0 1 22 22v42a22 22 0 0 1-22 22h-56l-38 30v-30H82a22 22 0 0 1-22-22v-42a22 22 0 0 1 22-22zm28 43h76",
    "M440 764 418 856m94-92-22 92m-86-58h116m-124 34h116",
    "m684 819 34 34 72-72m-42 38 28 28 54-54",
    "M952 766c24 18 50 24 76 24v48c0 52-32 78-76 96-44-18-76-44-76-96v-48c26 0 52-6 76-24zm-28 82 20 20 40-44",
    "m126 986 28 28-28 28m50 0h52",
    "M458 946h106a22 22 0 0 1 22 22v42a22 22 0 0 1-22 22h-50l-38 30v-30h-18a22 22 0 0 1-22-22v-42a22 22 0 0 1 22-22zm24 43h58",
)

// CLASSIC accent group: a single stroked path (blue accent segments).
private val CLASSIC_ACCENT_PATHS = arrayOf(
    "M472 340v18m242 218h40M944 868l40-44",
)

// NETWORK: topology link polylines (solid, connecting the node glyphs).
private val NETWORK_LINK_PATHS = arrayOf(
    "M92 180 246 116 410 218 574 118 746 206 930 126",
    "M160 492 318 398 488 506 650 410 828 510 994 414",
    "M82 836 248 746 414 852 584 744 762 850 946 758",
)

// NETWORK: main stroked glyph group (endpoints, hubs, servers, chevrons).
private val NETWORK_PATHS = arrayOf(
    "M56 145h72a18 18 0 0 1 18 18v42a18 18 0 0 1-18 18H92l-28 22v-22h-8a18 18 0 0 1-18-18v-42a18 18 0 0 1 18-18z",
    "M256 76 238 154m78-78-18 78m-72-50h102m-108 28h102",
    "M376 184h38a28 28 0 0 1 0 56h-38a28 28 0 0 1 0-56zm38 0h38a28 28 0 0 1 0 56h-38m-18-28h36",
    "M536 166h76v70h-76zm18 0v-20a20 20 0 0 1 40 0v20m-20 29v16",
    "M704 166h86v72h-86zm18 24h50m-50 24h50",
    "M898 86h82a18 18 0 0 1 18 18v48a18 18 0 0 1-18 18h-82a18 18 0 0 1-18-18v-48a18 18 0 0 1 18-18zm20 31h42m-42 24h26",
    "M118 442a46 46 0 1 0 0 92 46 46 0 0 0 0-92zm-44 46h88m-44-44c17 27 17 61 0 88m0-88c-17 27-17 61 0 88",
    "m278 374 28 28-28 28m48 0h48",
    "M446 464h84l26 26v58H446zm84 0v26h26m-86 27h54",
    "M610 370h80v78h-80zM628 396h44m-44 27h26",
    "M786 476h42a28 28 0 0 1 0 56h-42a28 28 0 0 1 0-56zm42 0h42a28 28 0 0 1 0 56h-42",
    "M974 362 956 440m78-78-18 78m-72-50h102m-108 28h102",
    "M46 796h82a18 18 0 0 1 18 18v40a18 18 0 0 1-18 18H92l-28 22v-22H46a18 18 0 0 1-18-18v-40a18 18 0 0 1 18-18z",
    "M204 706h88v76h-88zm18 25h52m-52 26h52",
    "M376 810h76v70h-76zm18 0v-20a20 20 0 0 1 40 0v20m-20 28v17",
    "m544 714 30 30 62-62m-32 32 26 26 48-48",
    "M718 812h38a28 28 0 0 1 0 56h-38a28 28 0 0 1 0-56zm38 0h38a28 28 0 0 1 0 56h-38m-18-28h36",
    "M904 716h82a18 18 0 0 1 18 18v42a18 18 0 0 1-18 18h-82a18 18 0 0 1-18-18v-42a18 18 0 0 1 18-18zm20 30h42",
)

// PIXEL: main filled block group, flat [x, y, w, h] quads.
private val PIXEL_MAIN_RECTS = floatArrayOf(
    72f, 72f, 16f, 96f, 120f, 72f, 16f, 96f, 48f, 96f, 112f, 16f, 48f, 136f, 112f, 16f,
    304f, 72f, 112f, 16f, 288f, 88f, 16f, 64f, 416f, 88f, 16f, 64f, 304f, 152f, 48f, 16f,
    384f, 152f, 32f, 16f, 352f, 152f, 16f, 32f, 336f, 168f, 16f, 32f,
    614f, 88f, 16f, 16f, 630f, 104f, 16f, 16f, 614f, 120f, 16f, 16f, 662f, 136f, 64f, 16f,
    856f, 72f, 112f, 16f, 840f, 88f, 16f, 96f, 968f, 88f, 16f, 96f, 856f, 184f, 112f, 16f,
    872f, 104f, 56f, 16f, 872f, 152f, 56f, 16f,
    128f, 328f, 112f, 16f, 112f, 344f, 16f, 80f, 240f, 344f, 16f, 80f, 128f, 424f, 112f, 16f,
    144f, 296f, 16f, 32f, 208f, 296f, 16f, 32f, 160f, 280f, 48f, 16f, 176f, 360f, 16f, 32f,
    416f, 304f, 16f, 16f, 448f, 304f, 96f, 16f, 416f, 352f, 16f, 16f, 448f, 352f, 64f, 16f,
    416f, 400f, 16f, 16f, 448f, 400f, 96f, 16f,
    744f, 320f, 64f, 16f, 728f, 336f, 16f, 48f, 744f, 384f, 64f, 16f, 808f, 336f, 32f, 16f,
    808f, 368f, 32f, 16f, 840f, 320f, 64f, 16f, 904f, 336f, 16f, 48f, 840f, 384f, 64f, 16f,
    792f, 352f, 64f, 16f,
    64f, 576f, 16f, 64f, 80f, 560f, 32f, 16f, 112f, 544f, 48f, 16f, 160f, 560f, 32f, 16f,
    192f, 576f, 16f, 64f, 80f, 640f, 32f, 16f, 112f, 656f, 48f, 16f, 160f, 640f, 32f, 16f,
    64f, 600f, 144f, 16f, 128f, 544f, 16f, 128f,
    352f, 544f, 96f, 16f, 336f, 560f, 16f, 80f, 448f, 560f, 16f, 80f, 352f, 640f, 16f, 16f,
    432f, 640f, 16f, 16f, 368f, 656f, 16f, 16f, 416f, 656f, 16f, 16f, 384f, 672f, 32f, 16f,
    608f, 544f, 80f, 16f, 592f, 560f, 16f, 112f, 688f, 560f, 16f, 112f, 608f, 672f, 80f, 16f,
    688f, 560f, 32f, 16f, 704f, 576f, 16f, 32f, 624f, 608f, 48f, 16f, 624f, 640f, 64f, 16f,
    856f, 560f, 128f, 16f, 840f, 576f, 16f, 96f, 984f, 576f, 16f, 96f, 856f, 672f, 128f, 16f,
    880f, 608f, 16f, 16f, 896f, 624f, 16f, 16f, 880f, 640f, 16f, 16f, 928f, 640f, 32f, 16f,
    64f, 848f, 16f, 64f, 80f, 832f, 80f, 16f, 160f, 848f, 16f, 64f, 80f, 912f, 48f, 16f,
    112f, 864f, 32f, 16f, 96f, 880f, 16f, 32f, 144f, 880f, 16f, 32f,
    352f, 824f, 128f, 16f, 336f, 840f, 16f, 80f, 480f, 840f, 16f, 80f, 352f, 920f, 48f, 16f,
    448f, 920f, 32f, 16f, 400f, 920f, 16f, 32f, 384f, 936f, 16f, 32f, 376f, 872f, 80f, 16f,
    640f, 848f, 112f, 16f, 624f, 864f, 16f, 80f, 752f, 864f, 16f, 80f, 640f, 944f, 112f, 16f,
    656f, 816f, 16f, 32f, 720f, 816f, 16f, 32f, 672f, 800f, 48f, 16f, 688f, 880f, 16f, 32f,
    872f, 832f, 16f, 16f, 920f, 800f, 16f, 16f, 968f, 848f, 16f, 16f, 904f, 896f, 16f, 16f,
    952f, 928f, 16f, 16f, 888f, 816f, 32f, 16f, 936f, 816f, 32f, 16f, 888f, 848f, 80f, 16f,
    920f, 880f, 32f, 16f, 920f, 912f, 32f, 16f,
)

// PIXEL blue accent blocks.
private val PIXEL_ACCENT_BLUE = floatArrayOf(
    368f, 112f, 16f, 16f, 448f, 304f, 96f, 16f, 368f, 608f, 16f, 16f, 384f, 624f, 16f, 16f,
    400f, 608f, 32f, 16f, 880f, 608f, 16f, 16f, 896f, 624f, 16f, 16f,
)

// PIXEL green accent blocks.
private val PIXEL_ACCENT_GREEN = floatArrayOf(
    944f, 104f, 16f, 16f, 176f, 360f, 16f, 16f, 808f, 352f, 32f, 16f,
    688f, 880f, 16f, 16f, 920f, 800f, 16f, 16f, 952f, 928f, 16f, 16f,
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
