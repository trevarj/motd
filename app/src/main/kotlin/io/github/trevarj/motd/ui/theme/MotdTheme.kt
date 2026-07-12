package io.github.trevarj.motd.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.isDark

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

/**
 * CompositionLocal carrying the active avatar rendering style. Defaults to MONOGRAM so previews
 * and un-provided contexts use the default without needing explicit provision.
 */
val LocalAvatarStyle: ProvidableCompositionLocal<AvatarStyle> =
    staticCompositionLocalOf { AvatarStyle.MONOGRAM }

/**
 * Return the fixed ColorScheme for terminal-palette ThemeModes, or null for the modes that use
 * dynamic color / the brand-seed default. "Dark" here means the scheme uses dark backgrounds.
 */
internal fun fixedThemeScheme(theme: ColorThemePreset): ColorScheme? = when (theme) {
    ColorThemePreset.AYU_DARK -> AyuDarkScheme
    ColorThemePreset.AYU_LIGHT -> AyuLightScheme
    ColorThemePreset.AYU_MIRAGE -> AyuMirageScheme
    ColorThemePreset.CATPPUCCIN_LATTE -> CatppuccinLatteScheme
    ColorThemePreset.CATPPUCCIN_MOCHA -> CatppuccinMochaScheme
    ColorThemePreset.DRACULA -> DraculaScheme
    ColorThemePreset.EVERFOREST_DARK -> EverforestDarkScheme
    ColorThemePreset.EVERFOREST_LIGHT -> EverforestLightScheme
    ColorThemePreset.GRUVBOX_DARK -> GruvboxDarkScheme
    ColorThemePreset.GRUVBOX_LIGHT -> GruvboxLightScheme
    ColorThemePreset.KANAGAWA_DRAGON -> KanagawaDragonScheme
    ColorThemePreset.KANAGAWA_LOTUS -> KanagawaLotusScheme
    ColorThemePreset.KANAGAWA_WAVE -> KanagawaWaveScheme
    ColorThemePreset.MODUS_OPERANDI -> ModusOperandiScheme
    ColorThemePreset.MODUS_VIVENDI -> ModusVivendiScheme
    ColorThemePreset.MONOKAI -> MonokaiScheme
    ColorThemePreset.NORD -> NordScheme
    ColorThemePreset.ONE_DARK -> OneDarkScheme
    ColorThemePreset.ROSE_PINE -> RosePineScheme
    ColorThemePreset.ROSE_PINE_DAWN -> RosePineDawnScheme
    ColorThemePreset.ROSE_PINE_MOON -> RosePineMoonScheme
    ColorThemePreset.SOLARIZED_DARK -> SolarizedDarkScheme
    ColorThemePreset.SOLARIZED_LIGHT -> SolarizedLightScheme
    ColorThemePreset.TOKYO_NIGHT -> TokyoNightScheme
    ColorThemePreset.ZENBURN -> ZenburnScheme
    // Base modes resolve through the normal dark/dynamic path.
    else -> null
}

@Composable
fun MotdTheme(
    themePreset: ColorThemePreset = ColorThemePreset.SYSTEM,
    dynamicColor: Boolean = true,
    // Round 4 (plans/13); all defaulted so existing call sites (incl. previews) stay unchanged.
    layoutDensity: LayoutDensity = LayoutDensity.COMFORTABLE,
    nickColorsEnabled: Boolean = true,
    nickColorPalette: NickColorPalette = NickColorPalette.DEFAULT,
    nickColorOverrides: Map<String, Int> = emptyMap(),
    avatarStyle: AvatarStyle = AvatarStyle.MONOGRAM,
    content: @Composable () -> Unit,
) {
    val dark = if (themePreset == ColorThemePreset.SYSTEM) isSystemInDarkTheme() else themePreset.isDark
    val colorScheme = fixedThemeScheme(themePreset) ?: when {
        // Material You dynamic color, API 31+. AMOLED still forces true-black surfaces below.
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            val scheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            if (themePreset == ColorThemePreset.AMOLED) {
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
        themePreset == ColorThemePreset.AMOLED -> AmoledColors
        dark -> DarkColors
        else -> LightColors
    }
    // Style-only concerns (spacing, nick colors, avatar style) flow through CompositionLocals so
    // components never receive them as parameters (plans/13 plumbing split).
    MaterialTheme(colorScheme = colorScheme) {
        CompositionLocalProvider(
            LocalSpacing provides spacingFor(layoutDensity),
            LocalNickColors provides NickColorScheme(nickColorsEnabled, nickColorPalette, nickColorOverrides, dark),
            LocalAvatarStyle provides avatarStyle,
            content = content,
        )
    }
}
