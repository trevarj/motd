package io.github.trevarj.motd.ui.chat

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.scale
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.ui.theme.contrastSafeOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.graphics.Color as AndroidColor

/** Theme-adaptive gradient plus a stable, repeated monochrome PNG tile. */
@Composable
fun ChatWallpaperBackground(
    wallpaper: WallpaperSelection,
    modifier: Modifier = Modifier,
    tilePeriodDp: Float = WALLPAPER_TILE_PERIOD_DP,
) {
    if (wallpaper.preset == ChatWallpaperPreset.NONE) {
        Box(modifier)
        return
    }

    val context = LocalContext.current
    val density = LocalDensity.current.density
    val requestedTileKey =
        remember(wallpaper.preset, density, tilePeriodDp) {
            wallpaperTileKey(wallpaper.preset, density, tilePeriodDp)
        }
    val tile by produceState<WallpaperTile?>(
        initialValue = WallpaperTileCache[requestedTileKey]?.let { WallpaperTile(requestedTileKey, it) },
        requestedTileKey,
    ) {
        value =
            WallpaperTile(
                requestedTileKey,
                withContext(Dispatchers.Default) {
                    WallpaperTileCache.getOrRender(context.assets, requestedTileKey)
                },
            )
    }
    // A preset switch keeps the previous complete wallpaper until its replacement tile is ready.
    val renderedPreset = tile?.key?.preset ?: wallpaper.preset
    val scheme = MaterialTheme.colorScheme
    val base = scheme.background
    val foregrounds = listOf(scheme.onBackground, scheme.onSurface, scheme.onSurfaceVariant)
    val gradient =
        remember(renderedPreset, scheme) {
            gradientColors(renderedPreset, base, scheme.primary, scheme.secondary, scheme.tertiary, foregrounds)
        }
    // The source PNG carries the anti-alias alpha; intensity controls the ink opacity directly.
    val pattern = wallpaperInkColor(scheme.onSurfaceVariant, wallpaper.intensity)
    var gradientCoverage by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged {
                val expanded = expandedWallpaperCoverage(gradientCoverage, it)
                if (gradientCoverage != expanded) gradientCoverage = expanded
            }.drawWithCache {
                val canvasSize = IntSize(size.width.toInt(), size.height.toInt())
                val coverage = expandedWallpaperCoverage(gradientCoverage, canvasSize)
                val gradientPaint =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader =
                            LinearGradient(
                                0f,
                                0f,
                                coverage.width.toFloat(),
                                coverage.height.toFloat(),
                                gradient.map(Color::toArgb).toIntArray(),
                                null,
                                Shader.TileMode.CLAMP,
                            )
                    }
                val patternPaint =
                    tile?.bitmap?.let { bitmap ->
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                            shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                            colorFilter = PorterDuffColorFilter(pattern.toArgb(), PorterDuff.Mode.SRC_IN)
                        }
                    }
                onDrawBehind {
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, gradientPaint)
                        patternPaint?.let { canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, it) }
                    }
                }
            },
    )
}

/** Warm the selected full-size motif before a chat destination needs to draw it. */
@Composable
internal fun PreloadChatWallpaperTile(wallpaper: WallpaperSelection) {
    if (wallpaper.preset == ChatWallpaperPreset.NONE) return
    val assets = LocalContext.current.assets
    val density = LocalDensity.current.density
    val key = remember(wallpaper.preset, density) { wallpaperTileKey(wallpaper.preset, density) }
    LaunchedEffect(key) {
        withContext(Dispatchers.Default) { WallpaperTileCache.getOrRender(assets, key) }
    }
}

internal fun gradientColors(
    preset: ChatWallpaperPreset,
    base: Color,
    primary: Color,
    secondary: Color,
    tertiary: Color,
    foregrounds: List<Color>,
): List<Color> {
    // AMOLED remains genuinely black: the pattern supplies texture without colored surface glow.
    if (base.toArgb() == AndroidColor.BLACK) return List(4) { base }
    val accents =
        when (preset) {
            ChatWallpaperPreset.MOTD -> listOf(primary, secondary, tertiary)
            ChatWallpaperPreset.DEEP_SPACE -> listOf(tertiary, primary, secondary)
            ChatWallpaperPreset.RETRO_GAMING -> listOf(primary, tertiary, secondary)
            ChatWallpaperPreset.RADIO_CLUB -> listOf(tertiary, primary, secondary)
            ChatWallpaperPreset.INTERNET_ODDITIES -> listOf(secondary, tertiary, primary)
            ChatWallpaperPreset.RETRO_CHAT -> listOf(primary, tertiary, secondary)
            ChatWallpaperPreset.MEMES -> listOf(secondary, primary, tertiary)
            ChatWallpaperPreset.NONE -> listOf(base, base, base)
        }
    return listOf(.06f, .04f, .05f, .03f).mapIndexed { index, alpha ->
        contrastSafeOverlay(base, accents[index % accents.size], alpha, foregrounds).compositeOver(base)
    }
}

/** The selected intensity is direct ink opacity in every appearance mode. */
internal fun wallpaperInkAlpha(intensity: Int): Float = intensity.coerceIn(0, 100) / 100f

internal fun wallpaperInkColor(
    ink: Color,
    intensity: Int,
): Color = ink.copy(alpha = wallpaperInkAlpha(intensity))

internal data class WallpaperTileKey(
    val preset: ChatWallpaperPreset,
    val tileSizePx: Int,
)

private data class WallpaperTile(
    val key: WallpaperTileKey,
    val bitmap: Bitmap,
)

/** A 4 MiB cache keeps the theme- and intensity-independent alpha masks bounded. */
private object WallpaperTileCache : LruCache<WallpaperTileKey, Bitmap>(WALLPAPER_TILE_CACHE_KIB) {
    private val renderMutex = Mutex()

    override fun sizeOf(
        key: WallpaperTileKey,
        value: Bitmap,
    ): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)

    suspend fun getOrRender(
        assets: AssetManager,
        key: WallpaperTileKey,
    ): Bitmap {
        get(key)?.let { return it }
        return renderMutex.withLock {
            get(key) ?: renderWallpaperTile(assets, key).also { put(key, it) }
        }
    }
}

/** Decode the transparent PNG off the main thread, then keep only a compact alpha mask in memory. */
internal fun renderWallpaperTile(
    assets: AssetManager,
    key: WallpaperTileKey,
): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    assets.open("chat-wallpapers/${assetName(key.preset)}").use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "wallpaper PNG could not be decoded" }

    val decoded =
        assets.open("chat-wallpapers/${assetName(key.preset)}").use { input ->
            BitmapFactory.decodeStream(
                input,
                null,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = wallpaperDecodeSampleSize(bounds.outWidth, bounds.outHeight, key.tileSizePx)
                    inScaled = false
                },
            )
        } ?: error("wallpaper PNG could not be decoded")
    val alphaMask =
        try {
            decoded.extractAlpha()
        } finally {
            decoded.recycle()
        }
    return if (alphaMask.width == key.tileSizePx && alphaMask.height == key.tileSizePx) {
        alphaMask
    } else {
        alphaMask.scale(key.tileSizePx, key.tileSizePx, true).also {
            if (it !== alphaMask) alphaMask.recycle()
        }
    }
}

internal fun wallpaperDecodeSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetSizePx: Int,
): Int {
    var sample = 1
    while (sourceWidth / (sample * 2) >= targetSizePx && sourceHeight / (sample * 2) >= targetSizePx) {
        sample *= 2
    }
    return sample
}

internal fun wallpaperTileKey(
    preset: ChatWallpaperPreset,
    density: Float,
    tilePeriodDp: Float = WALLPAPER_TILE_PERIOD_DP,
): WallpaperTileKey =
    WallpaperTileKey(
        preset = preset,
        tileSizePx = (tilePeriodDp * density).toInt().coerceAtLeast(1),
    )

/** Keep the gradient stationary when a transient inset shrinks the chat viewport. */
internal fun expandedWallpaperCoverage(
    current: IntSize,
    measured: IntSize,
): IntSize =
    IntSize(
        width = maxOf(current.width, measured.width),
        height = maxOf(current.height, measured.height),
    )

internal fun assetName(preset: ChatWallpaperPreset): String =
    when (preset) {
        ChatWallpaperPreset.NONE -> error("NONE has no PNG asset")
        ChatWallpaperPreset.MOTD -> "motd.png"
        ChatWallpaperPreset.DEEP_SPACE -> "deep-space.png"
        ChatWallpaperPreset.RETRO_GAMING -> "retro-gaming.png"
        ChatWallpaperPreset.RADIO_CLUB -> "radio-club.png"
        ChatWallpaperPreset.INTERNET_ODDITIES -> "internet-oddities.png"
        ChatWallpaperPreset.RETRO_CHAT -> "retro-chat.png"
        ChatWallpaperPreset.MEMES -> "memes.png"
    }

internal const val WALLPAPER_TILE_PERIOD_DP = 512f
internal const val WALLPAPER_THUMBNAIL_TILE_PERIOD_DP = 128f
private const val WALLPAPER_TILE_CACHE_KIB = 4 * 1024
