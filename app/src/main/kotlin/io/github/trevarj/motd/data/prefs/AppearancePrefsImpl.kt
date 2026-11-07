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
private val USE_THEME_TEXT_COLORS = booleanPreferencesKey("use_theme_text_colors_v1")
private val WALLPAPER = stringPreferencesKey("wallpaper_preset_v1")
private val WALLPAPER_INTENSITY = intPreferencesKey("wallpaper_intensity_v1")
private val WALLPAPER_IMAGE = stringPreferencesKey("wallpaper_local_image_v1")
private val WALLPAPER_OUTLINE_COLOR = intPreferencesKey("wallpaper_outline_color_v1")
private val WALLPAPER_SCENIC_FOLLOW_SYSTEM = booleanPreferencesKey("wallpaper_scenic_follow_system_v1")
private val WALLPAPER_SCENIC_ANIMATION = booleanPreferencesKey("wallpaper_scenic_animation_v1")
private val UI_FONT_SCALE = intPreferencesKey("ui_font_scale_percent_v1")
private val CONVERSATION_FONT_SCALE = intPreferencesKey("conversation_font_scale_percent_v1")
private val FONT_CHOICE = stringPreferencesKey("font_choice_v1")
private val SHOW_TIMESTAMPS = booleanPreferencesKey("show_timestamps_v1")
private val TIME_FORMAT = stringPreferencesKey("time_format_v1")
private val CUSTOM_TIME_FORMAT_PATTERN = stringPreferencesKey("custom_time_format_pattern_v1")
private val MESSAGE_SPACING = stringPreferencesKey("message_spacing_v1")
private val COMPOSER_STYLE = stringPreferencesKey("composer_style_v1")
private val INPUT_CORNER_STYLE = stringPreferencesKey("input_corner_style_v1")
private val AVATAR_SHAPE = stringPreferencesKey("avatar_shape_v1")
private val CHAT_CARD_CORNER_STYLE = stringPreferencesKey("chat_card_corner_style_v1")
private val MEDIA_CORNER_STYLE = stringPreferencesKey("media_corner_style_v1")
private val UI_ICON_SET = stringPreferencesKey("ui_icon_set_v1")
private val BUBBLE_CORNER_STYLE = stringPreferencesKey("bubble_corner_style_v1")
private val CHAT_SHADOWS_ENABLED = booleanPreferencesKey("chat_shadows_enabled_v1")
private val NICKNAME_BACKGROUNDS_ENABLED = booleanPreferencesKey("nickname_backgrounds_enabled_v1")
private val MESSAGE_BACKGROUND_OPACITY = intPreferencesKey("message_background_opacity_v1")
private val MESSAGE_BACKGROUND_BLUR = intPreferencesKey("message_background_blur_v1")
private val SENT_BUBBLE_COLOR_ROLE = stringPreferencesKey("sent_bubble_color_role_v1")
private val SENT_BUBBLE_COLOR_CUSTOM = intPreferencesKey("sent_bubble_color_custom_v1")
private val RECEIVED_BUBBLE_COLOR_ROLE = stringPreferencesKey("received_bubble_color_role_v1")
private val RECEIVED_BUBBLE_COLOR_CUSTOM = intPreferencesKey("received_bubble_color_custom_v1")
private val LAUNCHER_ICON = stringPreferencesKey("launcher_icon_v1")
private val SPLASH_SCREEN_ANIMATION = stringPreferencesKey("splash_screen_animation_v1")
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
                            localImageName = prefs[WALLPAPER_IMAGE],
                            outlineColor = prefs[WALLPAPER_OUTLINE_COLOR]?.or(0xff000000.toInt()),
                            scenicFollowSystem = prefs[WALLPAPER_SCENIC_FOLLOW_SYSTEM] ?: true,
                            scenicAnimationEnabled = prefs[WALLPAPER_SCENIC_ANIMATION] ?: true,
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
                    useThemeTextColors = prefs[USE_THEME_TEXT_COLORS] ?: false,
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
                    composerStyle =
                        prefs[COMPOSER_STYLE]?.let { runCatching { ComposerStyle.valueOf(it) }.getOrNull() }
                            ?: ComposerStyle.COMFORTABLE,
                    inputCornerStyle =
                        prefs[INPUT_CORNER_STYLE]?.let { runCatching { UiCornerStyle.valueOf(it) }.getOrNull() }
                            ?: UiCornerStyle.DEFAULT,
                    avatarShape =
                        prefs[AVATAR_SHAPE]?.let { runCatching { AvatarShape.valueOf(it) }.getOrNull() }
                            ?: AvatarShape.DEFAULT,
                    chatCardCornerStyle =
                        prefs[CHAT_CARD_CORNER_STYLE]?.let { runCatching { UiCornerStyle.valueOf(it) }.getOrNull() }
                            ?: UiCornerStyle.DEFAULT,
                    mediaCornerStyle =
                        prefs[MEDIA_CORNER_STYLE]?.let { runCatching { UiCornerStyle.valueOf(it) }.getOrNull() }
                            ?: UiCornerStyle.DEFAULT,
                    uiIconSet =
                        prefs[UI_ICON_SET]?.let { runCatching { UiIconSet.valueOf(it) }.getOrNull() }
                            ?: UiIconSet.MATERIAL,
                    bubbleCornerStyle =
                        prefs[BUBBLE_CORNER_STYLE]
                            ?.let { runCatching { BubbleCornerStyle.valueOf(it) }.getOrNull() }
                            ?: BubbleCornerStyle.ROUNDED,
                    chatShadowsEnabled = prefs[CHAT_SHADOWS_ENABLED] ?: false,
                    nicknameBackgroundsEnabled = prefs[NICKNAME_BACKGROUNDS_ENABLED] ?: true,
                    messageBackgroundOpacityPercent = (prefs[MESSAGE_BACKGROUND_OPACITY] ?: 100).coerceIn(0, 100),
                    messageBackgroundBlur = (prefs[MESSAGE_BACKGROUND_BLUR] ?: 0).coerceIn(0, 100),
                    sentBubbleColor = storedComfortableBubbleColor(prefs[SENT_BUBBLE_COLOR_ROLE], prefs[SENT_BUBBLE_COLOR_CUSTOM]),
                    receivedBubbleColor = storedComfortableBubbleColor(prefs[RECEIVED_BUBBLE_COLOR_ROLE], prefs[RECEIVED_BUBBLE_COLOR_CUSTOM]),
                    launcherIcon =
                        prefs[LAUNCHER_ICON]?.let { runCatching { LauncherIcon.valueOf(it) }.getOrNull() }
                            ?: LauncherIcon.DEFAULT,
                    splashScreenAnimation =
                        prefs[SPLASH_SCREEN_ANIMATION]
                            ?.let { runCatching { SplashScreenAnimation.valueOf(it) }.getOrNull() }
                            ?: SplashScreenAnimation.EDGE_TRACE,
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

        override suspend fun setUseThemeTextColors(enabled: Boolean) {
            store.edit { it[USE_THEME_TEXT_COLORS] = enabled }
        }

        override suspend fun setWallpaper(selection: WallpaperSelection) {
            val normalized = selection.normalized()
            store.edit {
                it[WALLPAPER] = normalized.preset.name
                it[WALLPAPER_INTENSITY] = normalized.intensity
                normalized.localImageName?.let { name -> it[WALLPAPER_IMAGE] = name }
                    ?: it.remove(WALLPAPER_IMAGE)
                normalized.outlineColor?.let { color -> it[WALLPAPER_OUTLINE_COLOR] = color }
                    ?: it.remove(WALLPAPER_OUTLINE_COLOR)
                it[WALLPAPER_SCENIC_FOLLOW_SYSTEM] = normalized.scenicFollowSystem
                it[WALLPAPER_SCENIC_ANIMATION] = normalized.scenicAnimationEnabled
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

        override suspend fun setComposerStyle(style: ComposerStyle) {
            store.edit { it[COMPOSER_STYLE] = style.name }
        }

        override suspend fun setInputCornerStyle(style: UiCornerStyle) {
            store.edit { it[INPUT_CORNER_STYLE] = style.name }
        }

        override suspend fun setAvatarShape(shape: AvatarShape) {
            store.edit { it[AVATAR_SHAPE] = shape.name }
        }

        override suspend fun setChatCardCornerStyle(style: UiCornerStyle) {
            store.edit { it[CHAT_CARD_CORNER_STYLE] = style.name }
        }

        override suspend fun setMediaCornerStyle(style: UiCornerStyle) {
            store.edit { it[MEDIA_CORNER_STYLE] = style.name }
        }

        override suspend fun setUiIconSet(iconSet: UiIconSet) {
            store.edit { it[UI_ICON_SET] = iconSet.name }
        }

        override suspend fun setBubbleCornerStyle(style: BubbleCornerStyle) {
            store.edit { it[BUBBLE_CORNER_STYLE] = style.name }
        }

        override suspend fun setChatShadowsEnabled(enabled: Boolean) {
            store.edit { it[CHAT_SHADOWS_ENABLED] = enabled }
        }

        override suspend fun setNicknameBackgroundsEnabled(enabled: Boolean) {
            store.edit { it[NICKNAME_BACKGROUNDS_ENABLED] = enabled }
        }

        override suspend fun setMessageBackgroundOpacity(percent: Int) {
            store.edit { it[MESSAGE_BACKGROUND_OPACITY] = percent.coerceIn(0, 100) }
        }

        override suspend fun setMessageBackgroundBlur(intensity: Int) {
            store.edit { it[MESSAGE_BACKGROUND_BLUR] = intensity.coerceIn(0, 100) }
        }

        override suspend fun setSentBubbleColor(color: ComfortableBubbleColor) {
            store.edit { it.storeComfortableBubbleColor(SENT_BUBBLE_COLOR_ROLE, SENT_BUBBLE_COLOR_CUSTOM, color) }
        }

        override suspend fun setReceivedBubbleColor(color: ComfortableBubbleColor) {
            store.edit { it.storeComfortableBubbleColor(RECEIVED_BUBBLE_COLOR_ROLE, RECEIVED_BUBBLE_COLOR_CUSTOM, color) }
        }

        override suspend fun setLauncherIcon(icon: LauncherIcon) {
            store.edit { it[LAUNCHER_ICON] = icon.name }
        }

        override suspend fun setSplashScreenAnimation(animation: SplashScreenAnimation) {
            store.edit { it[SPLASH_SCREEN_ANIMATION] = animation.name }
        }

        override suspend fun setCustomFontName(name: String) {
            store.edit { it[CUSTOM_FONT_NAME] = name }
        }
    }

private fun storedComfortableBubbleColor(
    role: String?,
    custom: Int?,
): ComfortableBubbleColor =
    ComfortableBubbleColor(
        role = role?.let { runCatching { ComfortableBubbleColorRole.valueOf(it) }.getOrNull() } ?: ComfortableBubbleColorRole.DEFAULT,
        customColor = custom,
    ).normalized()

private fun androidx.datastore.preferences.core.MutablePreferences.storeComfortableBubbleColor(
    roleKey: androidx.datastore.preferences.core.Preferences.Key<String>,
    customKey: androidx.datastore.preferences.core.Preferences.Key<Int>,
    color: ComfortableBubbleColor,
) {
    val normalized = color.normalized()
    this[roleKey] = normalized.role.name
    normalized.customColor?.let { this[customKey] = it } ?: remove(customKey)
}
