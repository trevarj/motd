package io.github.trevarj.motd.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.CustomWallpaperStore
import io.github.trevarj.motd.data.prefs.WallpaperRenderSpec
import io.github.trevarj.motd.data.prefs.WallpaperTexture
import io.github.trevarj.motd.ui.theme.contrastSafeOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import android.graphics.Color as AndroidColor

@Composable
internal fun rememberWallpaperPhotoLoaded(wallpaper: WallpaperRenderSpec): MutableState<Boolean> = remember(wallpaper.selection.localImageName) { mutableStateOf(false) }

/** The wallpaper plane is independent of the chat content and its insets. */
@Composable
fun ChatWallpaperBackground(
    wallpaper: WallpaperRenderSpec,
    modifier: Modifier = Modifier,
    tilePeriodDp: Float = WALLPAPER_TILE_PERIOD_DP,
    animate: Boolean = true,
    photoLoaded: MutableState<Boolean> = rememberWallpaperPhotoLoaded(wallpaper),
) {
    val context = LocalContext.current
    val selection = wallpaper.selection
    if (selection.localImageName != null) {
        val file = remember(selection.localImageName) { CustomWallpaperStore.resolve(context, selection.localImageName) }
        var loaded by photoLoaded
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (!loaded) {
                ChatWallpaperBackground(wallpaper.copy(selection = selection.copy(localImageName = null)), Modifier.matchParentSize(), tilePeriodDp, animate)
            }
            if (file != null) {
                AsyncImage(
                    model =
                        remember(file) {
                            ImageRequest
                                .Builder(context)
                                .data(file)
                                .crossfade(false)
                                .build()
                        },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = wallpaperInkAlpha(selection.intensity),
                    onSuccess = { loaded = true },
                    onError = { loaded = false },
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        return
    }
    if (selection.preset == ChatWallpaperPreset.NONE) {
        Box(modifier)
        return
    }
    if (selection.preset.isScenic) {
        ScenicWallpaperBackground(selection, modifier, animate)
        return
    }

    val scheme = MaterialTheme.colorScheme
    val fixedBackground = wallpaper.pattern?.background
    val patternPaint = rememberWallpaperPatternPaint(wallpaper, scheme.onSurfaceVariant, tilePeriodDp)
    val texturePaint = rememberWallpaperTexturePaint(wallpaper.pattern?.texture ?: WallpaperTexture.NONE)
    val gradient =
        remember(scheme, fixedBackground) {
            if (fixedBackground == null) {
                val foregrounds = listOf(scheme.onBackground, scheme.onSurface, scheme.onSurfaceVariant)
                gradientColors(scheme.background, scheme.primary, scheme.secondary, scheme.tertiary, foregrounds)
                    .map(Color::toArgb)
                    .toIntArray()
            } else {
                null
            }
        }
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
                val backdropPaint =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        if (fixedBackground != null) {
                            color = fixedBackground
                        } else {
                            shader =
                                LinearGradient(
                                    0f,
                                    0f,
                                    coverage.width.toFloat(),
                                    coverage.height.toFloat(),
                                    checkNotNull(gradient),
                                    null,
                                    Shader.TileMode.CLAMP,
                                )
                        }
                    }
                onDrawBehind {
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, backdropPaint)
                        texturePaint?.let { canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, it) }
                        patternPaint?.let { canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, it) }
                    }
                }
            },
    )
}

internal const val WALLPAPER_TEXTURE_MAX_ALPHA = 40
private const val WALLPAPER_TEXTURE_PERIOD_DP = 128f
private val textureTiles = ConcurrentHashMap<WallpaperTexture, Bitmap>(4)

internal suspend fun loadWallpaperTexture(
    context: Context,
    texture: WallpaperTexture,
): Bitmap? {
    if (texture == WallpaperTexture.NONE) return null
    return textureTiles[texture] ?: withContext(Dispatchers.IO) {
        try {
            textureTiles.computeIfAbsent(texture) {
                val file = it.name.lowercase().replace('_', '-')
                context.assets.open("wallpaper-textures/$file.png").use { stream ->
                    BitmapFactory.decodeStream(stream) ?: throw IOException("Invalid bundled texture: $file")
                }
            }
        } catch (_: IOException) {
            null
        }
    }
}

@Composable
private fun rememberWallpaperTexturePaint(texture: WallpaperTexture): Paint? {
    if (texture == WallpaperTexture.NONE) return null
    val context = LocalContext.current.applicationContext
    val bitmap =
        key(texture) {
            val tile by produceState(textureTiles[texture]) {
                if (value == null) value = loadWallpaperTexture(context, texture)
            }
            tile
        } ?: return null
    val periodPx = LocalDensity.current.density * WALLPAPER_TEXTURE_PERIOD_DP
    return remember(bitmap, periodPx) { wallpaperPatternPaint(bitmap, periodPx, intensity = 100) }
}

internal fun Modifier.fadeChatEdges(
    color: Color,
    readTopHostHeightPx: () -> Int,
    readBottomHostHeightPx: () -> Int,
    readBottomOffsetPx: () -> Int = { 0 },
    blendMode: BlendMode = BlendMode.SrcOver,
): Modifier =
    drawWithCache {
        val height = size.height.coerceAtLeast(1f)
        val bottom = (size.height - readBottomOffsetPx()).coerceIn(0f, size.height)
        val topExtent = readTopHostHeightPx().toFloat().coerceIn(0f, bottom)
        val bottomExtent = readBottomHostHeightPx().toFloat().coerceIn(0f, bottom)
        val scale = if (topExtent + bottomExtent > bottom) bottom / (topExtent + bottomExtent) else 1f
        val transparent = color.copy(alpha = 0f)
        val fade =
            Brush.verticalGradient(
                0f to color,
                topExtent * scale / height to transparent,
                (bottom - bottomExtent * scale) / height to transparent,
                bottom / height to color,
            )
        onDrawWithContent {
            drawContent()
            drawRect(fade, blendMode = blendMode)
        }
    }

internal fun gradientColors(
    base: Color,
    primary: Color,
    secondary: Color,
    tertiary: Color,
    foregrounds: List<Color>,
): List<Color> {
    // AMOLED remains genuinely black: the pattern supplies texture without colored surface glow.
    if (base.toArgb() == AndroidColor.BLACK) return List(4) { base }
    val accents = listOf(primary, secondary, tertiary)
    return listOf(.06f, .04f, .05f, .03f).mapIndexed { index, alpha ->
        contrastSafeOverlay(base, accents[index % accents.size], alpha, foregrounds).compositeOver(base)
    }
}

/** Intensity controls opacity directly. */
internal fun wallpaperInkAlpha(intensity: Int): Float = intensity.coerceIn(0, 100) / 100f

/** Keep the gradient stationary when a transient inset shrinks the chat viewport. */
internal fun expandedWallpaperCoverage(
    current: IntSize,
    measured: IntSize,
): IntSize =
    IntSize(
        width = maxOf(current.width, measured.width),
        height = maxOf(current.height, measured.height),
    )

internal const val WALLPAPER_TILE_PERIOD_DP = 512f
internal const val WALLPAPER_THUMBNAIL_TILE_PERIOD_DP = 128f
