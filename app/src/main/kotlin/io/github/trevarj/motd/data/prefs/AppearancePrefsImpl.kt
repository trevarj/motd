package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.appearanceDataStore by preferencesDataStore("appearance")
private val THEME = stringPreferencesKey("theme_preset_v1")
private val TRUE_BLACK = booleanPreferencesKey("true_black_v1")
private val FOLLOW_SYSTEM = booleanPreferencesKey("follow_system_v1")
private val WALLPAPER = stringPreferencesKey("wallpaper_preset_v1")
private val WALLPAPER_INTENSITY = intPreferencesKey("wallpaper_intensity_v1")
private val UI_FONT_SCALE = intPreferencesKey("ui_font_scale_percent_v1")
private val CONVERSATION_FONT_SCALE = intPreferencesKey("conversation_font_scale_percent_v1")
private val FONT_CHOICE = stringPreferencesKey("font_choice_v1")
private val SHOW_TIMESTAMPS = booleanPreferencesKey("show_timestamps_v1")
private val TIME_FORMAT = stringPreferencesKey("time_format_v1")
private val CUSTOM_TIME_FORMAT_PATTERN = stringPreferencesKey("custom_time_format_pattern_v1")
private val MESSAGE_SPACING = stringPreferencesKey("message_spacing_v1")
private val BUBBLE_CORNER_STYLE = stringPreferencesKey("bubble_corner_style_v1")
private val CHAT_SHADOWS_ENABLED = booleanPreferencesKey("chat_shadows_enabled_v1")
private val LAUNCHER_ICON = stringPreferencesKey("launcher_icon_v1")
private val CUSTOM_FONT_NAME = stringPreferencesKey("custom_font_name_v1")

internal data class StoredThemeResolution(
    val theme: ColorThemePreset,
    val trueBlack: Boolean,
)

internal fun resolveStoredTheme(
    name: String?,
    explicitTrueBlack: Boolean?,
): StoredThemeResolution {
    val storedTheme =
        name?.let { runCatching { ColorThemePreset.valueOf(it) }.getOrNull() }
            ?: ColorThemePreset.SYSTEM
    val legacyAmoled = storedTheme == ColorThemePreset.AMOLED
    return StoredThemeResolution(
        theme = if (legacyAmoled) ColorThemePreset.DARK else storedTheme,
        trueBlack = explicitTrueBlack ?: legacyAmoled,
    )
}

@Singleton
class AppearancePrefsImpl
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : AppearancePrefs {
        private val store = context.appearanceDataStore

        override val config: Flow<AppearanceConfig> =
            store.data.map { prefs ->
                val storedTheme = resolveStoredTheme(prefs[THEME], prefs[TRUE_BLACK])
                AppearanceConfig(
                    theme = storedTheme.theme,
                    wallpaper =
                        WallpaperSelection(
                            preset =
                                chatWallpaperPresetFromStored(prefs[WALLPAPER]),
                            intensity = (prefs[WALLPAPER_INTENSITY] ?: DEFAULT_WALLPAPER_INTENSITY).coerceIn(0, 100),
                        ),
                    uiFontScalePercent =
                        normalizeFontScalePercent(
                            prefs[UI_FONT_SCALE] ?: DEFAULT_FONT_SCALE_PERCENT,
                        ),
                    conversationFontScalePercent =
                        normalizeFontScalePercent(
                            prefs[CONVERSATION_FONT_SCALE] ?: DEFAULT_FONT_SCALE_PERCENT,
                        ),
                    trueBlack = storedTheme.trueBlack,
                    followSystem = prefs[FOLLOW_SYSTEM] ?: false,
                    fontChoice =
                        prefs[FONT_CHOICE]?.let { runCatching { FontChoice.valueOf(it) }.getOrNull() }
                            ?: FontChoice.SYSTEM,
                    showTimestamps = prefs[SHOW_TIMESTAMPS] ?: true,
                    timeFormat =
                        prefs[TIME_FORMAT]?.let { runCatching { TimeFormat.valueOf(it) }.getOrNull() }
                            ?: TimeFormat.AUTO,
                    customTimeFormatPattern =
                        prefs[CUSTOM_TIME_FORMAT_PATTERN]?.takeIf { it.isNotBlank() }
                            ?: DEFAULT_CUSTOM_TIME_FORMAT,
                    messageSpacing =
                        prefs[MESSAGE_SPACING]?.let { runCatching { MessageSpacing.valueOf(it) }.getOrNull() }
                            ?: MessageSpacing.DEFAULT,
                    bubbleCornerStyle =
                        prefs[BUBBLE_CORNER_STYLE]
                            ?.let { runCatching { BubbleCornerStyle.valueOf(it) }.getOrNull() }
                            ?: BubbleCornerStyle.ROUNDED,
                    chatShadowsEnabled = prefs[CHAT_SHADOWS_ENABLED] ?: true,
                    launcherIcon =
                        prefs[LAUNCHER_ICON]?.let { runCatching { LauncherIcon.valueOf(it) }.getOrNull() }
                            ?: LauncherIcon.DEFAULT,
                    customFontName = prefs[CUSTOM_FONT_NAME] ?: "",
                )
            }

        override suspend fun setTheme(theme: ColorThemePreset) {
            store.edit {
                if (theme == ColorThemePreset.AMOLED) {
                    it[THEME] = ColorThemePreset.DARK.name
                    it[TRUE_BLACK] = true
                } else {
                    it[THEME] = theme.name
                }
            }
        }

        override suspend fun setTrueBlack(enabled: Boolean) {
            store.edit { it[TRUE_BLACK] = enabled }
        }

        override suspend fun setFollowSystem(enabled: Boolean) {
            store.edit { it[FOLLOW_SYSTEM] = enabled }
        }

        override suspend fun setWallpaper(selection: WallpaperSelection) {
            val normalized = selection.normalized()
            store.edit {
                it[WALLPAPER] = normalized.preset.name
                it[WALLPAPER_INTENSITY] = normalized.intensity
            }
        }

        override suspend fun setUiFontScale(percent: Int) {
            store.edit { it[UI_FONT_SCALE] = normalizeFontScalePercent(percent) }
        }

        override suspend fun setConversationFontScale(percent: Int) {
            store.edit { it[CONVERSATION_FONT_SCALE] = normalizeFontScalePercent(percent) }
        }

        override suspend fun setFontChoice(choice: FontChoice) {
            store.edit { it[FONT_CHOICE] = choice.name }
        }

        override suspend fun setShowTimestamps(enabled: Boolean) {
            store.edit { it[SHOW_TIMESTAMPS] = enabled }
        }

        override suspend fun setTimeFormat(format: TimeFormat) {
            store.edit { it[TIME_FORMAT] = format.name }
        }

        override suspend fun setCustomTimeFormatPattern(pattern: String) {
            store.edit { it[CUSTOM_TIME_FORMAT_PATTERN] = pattern.ifBlank { DEFAULT_CUSTOM_TIME_FORMAT } }
        }

        override suspend fun setMessageSpacing(spacing: MessageSpacing) {
            store.edit { it[MESSAGE_SPACING] = spacing.name }
        }

        override suspend fun setBubbleCornerStyle(style: BubbleCornerStyle) {
            store.edit { it[BUBBLE_CORNER_STYLE] = style.name }
        }

        override suspend fun setChatShadowsEnabled(enabled: Boolean) {
            store.edit { it[CHAT_SHADOWS_ENABLED] = enabled }
        }

        override suspend fun setLauncherIcon(icon: LauncherIcon) {
            store.edit { it[LAUNCHER_ICON] = icon.name }
        }

        override suspend fun setCustomFontName(name: String) {
            store.edit { it[CUSTOM_FONT_NAME] = name }
        }
    }
