package io.github.trevarj.motd.data.prefs

import kotlinx.coroutines.flow.Flow

/** App-owned color presets kept separate from the frozen settings contract. */
enum class ColorThemePreset {
    SYSTEM, LIGHT, DARK, AMOLED,
    AYU_DARK, AYU_LIGHT, AYU_MIRAGE,
    CATPPUCCIN_LATTE, CATPPUCCIN_MOCHA,
    DRACULA,
    EVERFOREST_DARK, EVERFOREST_LIGHT,
    GRUVBOX_DARK, GRUVBOX_LIGHT,
    KANAGAWA_DRAGON, KANAGAWA_LOTUS, KANAGAWA_WAVE,
    MODUS_OPERANDI, MODUS_VIVENDI,
    MONOKAI,
    NORD,
    ONE_DARK,
    ROSE_PINE, ROSE_PINE_DAWN, ROSE_PINE_MOON,
    SOLARIZED_DARK, SOLARIZED_LIGHT,
    TOKYO_NIGHT,
    ZENBURN,
}

val ColorThemePreset.isFixedPalette: Boolean
    get() = this !in setOf(
        ColorThemePreset.SYSTEM,
        ColorThemePreset.LIGHT,
        ColorThemePreset.DARK,
        ColorThemePreset.AMOLED,
    )

val ColorThemePreset.isDark: Boolean
    get() = when (this) {
        ColorThemePreset.SYSTEM -> false // resolved from the OS by MotdTheme
        ColorThemePreset.LIGHT,
        ColorThemePreset.AYU_LIGHT,
        ColorThemePreset.CATPPUCCIN_LATTE,
        ColorThemePreset.EVERFOREST_LIGHT,
        ColorThemePreset.GRUVBOX_LIGHT,
        ColorThemePreset.KANAGAWA_LOTUS,
        ColorThemePreset.MODUS_OPERANDI,
        ColorThemePreset.ROSE_PINE_DAWN,
        ColorThemePreset.SOLARIZED_LIGHT,
        -> false
        else -> true
    }

enum class ChatWallpaperPreset { NONE, CHATTER, CHANNELS, TERMINAL, RELAY, SIGNALS, PIXELS }

data class WallpaperSelection(
    val preset: ChatWallpaperPreset = ChatWallpaperPreset.CHATTER,
    val intensity: Int = DEFAULT_WALLPAPER_INTENSITY,
) {
    fun normalized() = copy(intensity = intensity.coerceIn(0, 100))
}

data class AppearanceConfig(
    val theme: ColorThemePreset = ColorThemePreset.SYSTEM,
    val wallpaper: WallpaperSelection = WallpaperSelection(),
)

interface AppearancePrefs {
    val config: Flow<AppearanceConfig>
    suspend fun setTheme(theme: ColorThemePreset)
    suspend fun setWallpaper(selection: WallpaperSelection)
}

const val DEFAULT_WALLPAPER_INTENSITY = 40
