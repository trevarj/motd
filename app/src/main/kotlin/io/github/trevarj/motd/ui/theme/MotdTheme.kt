package io.github.trevarj.motd.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.ThemeMode

// Static schemes seeded from the indigo brand color; used when dynamic color is off or pre-API-31.
private val LightColors = lightColorScheme(primary = Indigo)
private val DarkColors = darkColorScheme(primary = Indigo)

// AMOLED = dark scheme with true-black background/surface and near-black surface containers.
private val AmoledColors = DarkColors.copy(
    background = AmoledBackground,
    surface = AmoledSurface,
    surfaceContainerLowest = AmoledSurfaceContainerLowest,
    surfaceContainerLow = AmoledSurfaceContainerLow,
    surfaceContainer = AmoledSurfaceContainer,
    surfaceContainerHigh = AmoledSurfaceContainerHigh,
    surfaceContainerHighest = AmoledSurfaceContainerHighest,
)

@Composable
fun MotdTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    // Round 4 (plans/13); all defaulted so existing call sites (incl. previews) stay unchanged.
    layoutDensity: LayoutDensity = LayoutDensity.COMFORTABLE,
    nickColorsEnabled: Boolean = true,
    nickColorPalette: NickColorPalette = NickColorPalette.DEFAULT,
    nickColorOverrides: Map<String, Int> = emptyMap(),
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val colorScheme = when {
        // Material You dynamic color, API 31+. AMOLED still forces true-black surfaces below.
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            val scheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            if (themeMode == ThemeMode.AMOLED) {
                scheme.copy(
                    background = AmoledBackground,
                    surface = AmoledSurface,
                    surfaceContainerLowest = AmoledSurfaceContainerLowest,
                    surfaceContainerLow = AmoledSurfaceContainerLow,
                    surfaceContainer = AmoledSurfaceContainer,
                    surfaceContainerHigh = AmoledSurfaceContainerHigh,
                    surfaceContainerHighest = AmoledSurfaceContainerHighest,
                )
            } else {
                scheme
            }
        }
        themeMode == ThemeMode.AMOLED -> AmoledColors
        dark -> DarkColors
        else -> LightColors
    }
    // Style-only concerns (spacing, nick colors) flow through CompositionLocals so components never
    // receive them as parameters (plans/13 plumbing split).
    MaterialTheme(colorScheme = colorScheme) {
        CompositionLocalProvider(
            LocalSpacing provides spacingFor(layoutDensity),
            LocalNickColors provides NickColorScheme(nickColorsEnabled, nickColorPalette, nickColorOverrides, dark),
            content = content,
        )
    }
}
