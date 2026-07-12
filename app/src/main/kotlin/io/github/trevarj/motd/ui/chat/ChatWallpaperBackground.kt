package io.github.trevarj.motd.ui.chat

import android.content.res.AssetManager
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.Shader
import android.util.LruCache
import android.util.Xml
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.ui.components.isAppliedThemeDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import kotlin.coroutines.coroutineContext

/** Theme-adaptive gradient plus a seamless monochrome SVG tile, cached as one draw-per-frame bitmap. */
@Composable
fun ChatWallpaperBackground(
    wallpaper: WallpaperSelection,
    modifier: Modifier = Modifier,
) {
    if (wallpaper.preset == ChatWallpaperPreset.NONE) {
        Box(modifier)
        return
    }

    val context = LocalContext.current
    val density = LocalDensity.current.density
    val dark = isAppliedThemeDark()
    val scheme = MaterialTheme.colorScheme
    val base = scheme.background
    val gradient = remember(wallpaper.preset, base, scheme.primary, scheme.secondary, scheme.tertiary) {
        gradientColors(wallpaper.preset, base, scheme.primary, scheme.secondary, scheme.tertiary)
    }
    val maxAlpha = if (dark) .22f else .18f
    val pattern = scheme.onSurfaceVariant.copy(alpha = maxAlpha * wallpaper.intensity.coerceIn(0, 100) / 100f)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val rasterKey = remember(wallpaper, canvasSize, density, gradient, pattern) {
        canvasSize.takeIf { it.width > 0 && it.height > 0 }?.let {
            WallpaperRasterKey(
                preset = wallpaper.preset,
                width = rasterDimension(it.width),
                height = rasterDimension(it.height),
                gradientArgb = gradient.map(Color::toArgb),
                patternArgb = pattern.toArgb(),
                tileSizePx = (TILE_SIZE_DP * density * WALLPAPER_RASTER_SCALE).toInt().coerceAtLeast(1),
            )
        }
    }
    val raster by produceState<ImageBitmap?>(initialValue = null, rasterKey) {
        val key = rasterKey ?: return@produceState
        value = WallpaperRasterCache.get(key)
        if (value == null) {
            value = withContext(Dispatchers.Default) {
                WallpaperRasterCache.get(key) ?: renderWallpaperRaster(context.assets, key)
                    .also { WallpaperRasterCache.put(key, it) }
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { if (canvasSize != it) canvasSize = it }
            .drawBehind {
                val image = raster
                if (image == null) drawRect(base) else drawImage(
                    image = image,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.Medium,
                )
            },
    )
}

private fun gradientColors(
    preset: ChatWallpaperPreset,
    base: Color,
    primary: Color,
    secondary: Color,
    tertiary: Color,
): List<Color> {
    // AMOLED remains genuinely black: the pattern supplies texture without colored surface glow.
    if (base.toArgb() == AndroidColor.BLACK) return List(4) { base }
    val accents = when (preset) {
        ChatWallpaperPreset.CHATTER -> listOf(primary, tertiary, secondary)
        ChatWallpaperPreset.CHANNELS -> listOf(secondary, primary, tertiary)
        ChatWallpaperPreset.TERMINAL -> listOf(primary, secondary, tertiary)
        ChatWallpaperPreset.RELAY -> listOf(secondary, tertiary, primary)
        ChatWallpaperPreset.SIGNALS -> listOf(tertiary, primary, secondary)
        ChatWallpaperPreset.PIXELS -> listOf(primary, tertiary, secondary)
        ChatWallpaperPreset.NONE -> listOf(base, base, base)
    }
    return listOf(
        lerp(base, accents[0], .10f),
        lerp(base, accents[1], .07f),
        lerp(base, accents[2], .09f),
        lerp(base, accents[0], .05f),
    )
}

private data class PatternPath(
    val path: AndroidPath,
    val x: Float,
    val y: Float,
    val scale: Float,
    val rotation: Float,
    val strokeWidth: Float,
    val opacity: Float,
)

private data class WallpaperRasterKey(
    val preset: ChatWallpaperPreset,
    val width: Int,
    val height: Int,
    val gradientArgb: List<Int>,
    val patternArgb: Int,
    val tileSizePx: Int,
)

private object WallpaperRasterCache : LruCache<WallpaperRasterKey, ImageBitmap>(12 * 1024) {
    override fun sizeOf(key: WallpaperRasterKey, value: ImageBitmap): Int =
        (value.width * value.height * 4) / 1024
}

private object PatternCache {
    private val cache = mutableMapOf<ChatWallpaperPreset, List<PatternPath>>()
    @Synchronized fun get(preset: ChatWallpaperPreset): List<PatternPath>? = cache[preset]
    @Synchronized fun put(preset: ChatWallpaperPreset, value: List<PatternPath>) { cache[preset] = value }
}

private suspend fun renderWallpaperRaster(assets: AssetManager, key: WallpaperRasterKey): ImageBitmap {
    val bitmap = createBitmap(key.width, key.height)
    val canvas = AndroidCanvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, key.width.toFloat(), key.height.toFloat(),
            key.gradientArgb.toIntArray(), null, Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, key.width.toFloat(), key.height.toFloat(), fill)
    if (AndroidColor.alpha(key.patternArgb) == 0) return bitmap.asImageBitmap()

    val paths = PatternCache.get(key.preset) ?: parsePattern(assets, key.preset).also {
        PatternCache.put(key.preset, it)
    }
    val tileScale = key.tileSizePx / SVG_TILE_SIZE
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = key.patternArgb
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    var tileY = -key.tileSizePx
    while (tileY < key.height + key.tileSizePx) {
        coroutineContext.ensureActive()
        var tileX = -key.tileSizePx
        while (tileX < key.width + key.tileSizePx) {
            for (item in paths) {
                paint.alpha = (AndroidColor.alpha(key.patternArgb) * item.opacity).toInt().coerceIn(0, 255)
                paint.strokeWidth = item.strokeWidth
                canvas.withTranslation(tileX + item.x * tileScale, tileY + item.y * tileScale) {
                    withRotation(item.rotation, 32f * tileScale, 32f * tileScale) {
                        withScale(item.scale * tileScale, item.scale * tileScale) {
                            drawPath(item.path, paint)
                        }
                    }
                }
            }
            tileX += key.tileSizePx
        }
        tileY += key.tileSizePx
    }
    return bitmap.asImageBitmap()
}

internal fun assetName(preset: ChatWallpaperPreset): String = when (preset) {
    ChatWallpaperPreset.NONE -> error("NONE has no SVG asset")
    ChatWallpaperPreset.CHATTER -> "chatter.svg"
    ChatWallpaperPreset.CHANNELS -> "channels.svg"
    ChatWallpaperPreset.TERMINAL -> "terminal.svg"
    ChatWallpaperPreset.RELAY -> "relay.svg"
    ChatWallpaperPreset.SIGNALS -> "signals.svg"
    ChatWallpaperPreset.PIXELS -> "pixels.svg"
}

private fun parsePattern(assets: AssetManager, preset: ChatWallpaperPreset): List<PatternPath> {
    val parser = Xml.newPullParser()
    assets.open("chat-wallpapers/${assetName(preset)}").use { input ->
        parser.setInput(input, "UTF-8")
        val out = ArrayList<PatternPath>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "path") {
                val d = parser.getAttributeValue(null, "d") ?: error("wallpaper path missing d")
                require(parser.getAttributeValue(null, "stroke") == "#000000") { "wallpaper paths must be black" }
                val transform = parseTransform(parser.getAttributeValue(null, "transform").orEmpty())
                out += PatternPath(
                    path = AndroidPath(PathParser().parsePathString(d).toPath().asAndroidPath()),
                    x = transform[0], y = transform[1], rotation = transform[2], scale = transform[3],
                    strokeWidth = parser.getAttributeValue(null, "stroke-width")?.toFloatOrNull() ?: 4f,
                    opacity = parser.getAttributeValue(null, "stroke-opacity")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f,
                )
            }
            parser.next()
        }
        require(out.isNotEmpty()) { "wallpaper asset is empty" }
        return out
    }
}

private val TRANSFORM = Regex(
    "translate\\(([-.0-9]+) ([-.0-9]+)\\) rotate\\(([-.0-9]+) 32 32\\) scale\\(([-.0-9]+)\\)",
)

private fun parseTransform(value: String): FloatArray {
    val match = requireNotNull(TRANSFORM.matchEntire(value)) { "unsupported wallpaper transform: $value" }
    return FloatArray(4) { match.groupValues[it + 1].toFloat() }
}

private fun rasterDimension(fullSize: Int) = (fullSize * WALLPAPER_RASTER_SCALE).toInt().coerceAtLeast(1)

private const val SVG_TILE_SIZE = 512f
private const val TILE_SIZE_DP = 244f
private const val WALLPAPER_RASTER_SCALE = .5f
