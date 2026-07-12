package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appearanceDataStore by preferencesDataStore("appearance")
private val THEME = stringPreferencesKey("theme_preset_v1")
private val WALLPAPER = stringPreferencesKey("wallpaper_preset_v1")
private val WALLPAPER_INTENSITY = intPreferencesKey("wallpaper_intensity_v1")

@Singleton
class AppearancePrefsImpl @Inject constructor(
    @ApplicationContext context: Context,
) : AppearancePrefs {
    private val store = context.appearanceDataStore

    override val config: Flow<AppearanceConfig> = store.data.map { prefs ->
        AppearanceConfig(
            theme = prefs[THEME]?.let { runCatching { ColorThemePreset.valueOf(it) }.getOrNull() }
                ?: ColorThemePreset.SYSTEM,
            wallpaper = WallpaperSelection(
                preset = prefs[WALLPAPER]?.let { runCatching { ChatWallpaperPreset.valueOf(it) }.getOrNull() }
                    ?: ChatWallpaperPreset.CHATTER,
                intensity = (prefs[WALLPAPER_INTENSITY] ?: DEFAULT_WALLPAPER_INTENSITY).coerceIn(0, 100),
            ),
        )
    }

    override suspend fun setTheme(theme: ColorThemePreset) {
        store.edit { it[THEME] = theme.name }
    }

    override suspend fun setWallpaper(selection: WallpaperSelection) {
        val normalized = selection.normalized()
        store.edit {
            it[WALLPAPER] = normalized.preset.name
            it[WALLPAPER_INTENSITY] = normalized.intensity
        }
    }
}
