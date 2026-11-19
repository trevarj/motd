package io.github.trevarj.motd.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import io.github.trevarj.motd.data.prefs.WallpaperRenderSpec
import io.github.trevarj.motd.data.prefs.WallpaperTexture
import io.github.trevarj.motd.ui.theme.contrastRatio
import io.github.trevarj.motd.ui.theme.readingInk

internal val LocalWallpaperNeedsReadableBacking = staticCompositionLocalOf { false }

@Composable
internal fun wallpaperNeedsReadableBacking(
    wallpaper: WallpaperRenderSpec,
    photoVisible: Boolean,
): Boolean {
    val pattern = wallpaper.pattern ?: return false
    if (photoVisible) return false

    val scheme = MaterialTheme.colorScheme
    val foreground = readingInk(scheme)
    val fixedBackground = pattern.background
    val defaultInk = pattern.ink?.let(::Color) ?: scheme.onSurfaceVariant
    val intensity = wallpaperInkAlpha(pattern.intensity)
    val textured = pattern.texture != WallpaperTexture.NONE
    return remember(scheme, foreground, fixedBackground, defaultInk, intensity, pattern.icons, textured) {
        val backgrounds =
            if (fixedBackground != null) {
                listOf(Color(fixedBackground))
            } else {
                gradientColors(
                    scheme.background,
                    scheme.primary,
                    scheme.secondary,
                    scheme.tertiary,
                    listOf(scheme.onBackground, scheme.onSurface, scheme.onSurfaceVariant),
                )
            }

        fun lacksContrast(background: Color): Boolean =
            contrastRatio(foreground, background) < 4.5 ||
                (
                    intensity > 0f &&
                        pattern.icons.any { icon ->
                            val ink = (icon.ink?.let(::Color) ?: defaultInk).copy(alpha = intensity)
                            contrastRatio(foreground, ink.compositeOver(background)) < 4.5
                        }
                )

        val textureAlpha = WALLPAPER_TEXTURE_MAX_ALPHA / 255f
        (fixedBackground == null && contrastRatio(foreground, scheme.background) < 4.5) ||
            backgrounds.any { background ->
                lacksContrast(background) ||
                    (
                        textured &&
                            (
                                lacksContrast(Color.Black.copy(alpha = textureAlpha).compositeOver(background)) ||
                                    lacksContrast(Color.White.copy(alpha = textureAlpha).compositeOver(background))
                            )
                    )
            }
    }
}
