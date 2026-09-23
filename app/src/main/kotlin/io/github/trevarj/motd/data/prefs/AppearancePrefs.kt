package io.github.trevarj.motd.data.prefs

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** App-owned color presets kept separate from the frozen settings contract. */
enum class ColorThemePreset {
    SYSTEM,
    LIGHT,
    DARK,
    AMOLED,
    AYU_DARK,
    AYU_LIGHT,
    AYU_MIRAGE,
    CATPPUCCIN_LATTE,
    CATPPUCCIN_MOCHA,
    DRACULA,
    EVERFOREST_DARK,
    EVERFOREST_LIGHT,
    GRUVBOX_DARK,
    GRUVBOX_LIGHT,
    KANAGAWA_DRAGON,
    KANAGAWA_LOTUS,
    KANAGAWA_WAVE,
    MODUS_OPERANDI,
    MODUS_VIVENDI,
    MODUS_OPERANDI_TINTED,
    MODUS_VIVENDI_TINTED,
    MODUS_OPERANDI_DEUTERANOPIA,
    MODUS_VIVENDI_DEUTERANOPIA,
    MODUS_OPERANDI_TRITANOPIA,
    MODUS_VIVENDI_TRITANOPIA,
    MONOKAI,
    NORD,
    NORD_LIGHT,
    ONE_DARK,
    ROSE_PINE,
    ROSE_PINE_DAWN,
    ROSE_PINE_MOON,
    SOLARIZED_DARK,
    SOLARIZED_LIGHT,
    TOKYO_NIGHT,
    ZENBURN,
}

val ColorThemePreset.isFixedPalette: Boolean
    get() =
        this !in
            setOf(
                ColorThemePreset.SYSTEM,
                ColorThemePreset.LIGHT,
                ColorThemePreset.DARK,
                ColorThemePreset.AMOLED,
            )

val ColorThemePreset.isDark: Boolean
    get() =
        when (this) {
            ColorThemePreset.SYSTEM -> false

            // resolved from the OS by MotdTheme
            ColorThemePreset.LIGHT,
            ColorThemePreset.AYU_LIGHT,
            ColorThemePreset.CATPPUCCIN_LATTE,
            ColorThemePreset.EVERFOREST_LIGHT,
            ColorThemePreset.GRUVBOX_LIGHT,
            ColorThemePreset.KANAGAWA_LOTUS,
            ColorThemePreset.MODUS_OPERANDI,
            ColorThemePreset.MODUS_OPERANDI_TINTED,
            ColorThemePreset.MODUS_OPERANDI_DEUTERANOPIA,
            ColorThemePreset.MODUS_OPERANDI_TRITANOPIA,
            ColorThemePreset.NORD_LIGHT,
            ColorThemePreset.ROSE_PINE_DAWN,
            ColorThemePreset.SOLARIZED_LIGHT,
            -> false

            else -> true
        }

/**
 * Opposite-mode sibling for optional system-mode switching. Dark-only and alternate-dark palettes
 * keep their fixed mode.
 */
val ColorThemePreset.systemPartner: ColorThemePreset?
    get() =
        when (this) {
            ColorThemePreset.LIGHT -> ColorThemePreset.DARK
            ColorThemePreset.DARK -> ColorThemePreset.LIGHT
            ColorThemePreset.AYU_LIGHT -> ColorThemePreset.AYU_DARK
            ColorThemePreset.AYU_DARK -> ColorThemePreset.AYU_LIGHT
            ColorThemePreset.CATPPUCCIN_LATTE -> ColorThemePreset.CATPPUCCIN_MOCHA
            ColorThemePreset.CATPPUCCIN_MOCHA -> ColorThemePreset.CATPPUCCIN_LATTE
            ColorThemePreset.EVERFOREST_LIGHT -> ColorThemePreset.EVERFOREST_DARK
            ColorThemePreset.EVERFOREST_DARK -> ColorThemePreset.EVERFOREST_LIGHT
            ColorThemePreset.GRUVBOX_LIGHT -> ColorThemePreset.GRUVBOX_DARK
            ColorThemePreset.GRUVBOX_DARK -> ColorThemePreset.GRUVBOX_LIGHT
            ColorThemePreset.KANAGAWA_LOTUS -> ColorThemePreset.KANAGAWA_WAVE
            ColorThemePreset.KANAGAWA_WAVE -> ColorThemePreset.KANAGAWA_LOTUS
            ColorThemePreset.MODUS_OPERANDI -> ColorThemePreset.MODUS_VIVENDI
            ColorThemePreset.MODUS_VIVENDI -> ColorThemePreset.MODUS_OPERANDI
            ColorThemePreset.MODUS_OPERANDI_TINTED -> ColorThemePreset.MODUS_VIVENDI_TINTED
            ColorThemePreset.MODUS_VIVENDI_TINTED -> ColorThemePreset.MODUS_OPERANDI_TINTED
            ColorThemePreset.MODUS_OPERANDI_DEUTERANOPIA -> ColorThemePreset.MODUS_VIVENDI_DEUTERANOPIA
            ColorThemePreset.MODUS_VIVENDI_DEUTERANOPIA -> ColorThemePreset.MODUS_OPERANDI_DEUTERANOPIA
            ColorThemePreset.MODUS_OPERANDI_TRITANOPIA -> ColorThemePreset.MODUS_VIVENDI_TRITANOPIA
            ColorThemePreset.MODUS_VIVENDI_TRITANOPIA -> ColorThemePreset.MODUS_OPERANDI_TRITANOPIA
            ColorThemePreset.NORD_LIGHT -> ColorThemePreset.NORD
            ColorThemePreset.NORD -> ColorThemePreset.NORD_LIGHT
            ColorThemePreset.ROSE_PINE_DAWN -> ColorThemePreset.ROSE_PINE
            ColorThemePreset.ROSE_PINE -> ColorThemePreset.ROSE_PINE_DAWN
            ColorThemePreset.SOLARIZED_LIGHT -> ColorThemePreset.SOLARIZED_DARK
            ColorThemePreset.SOLARIZED_DARK -> ColorThemePreset.SOLARIZED_LIGHT
            else -> null
        }

/** Resolve a paired family against the OS only when the user opted into system-mode following. */
fun resolveAutoPalette(
    themePreset: ColorThemePreset,
    followSystem: Boolean,
    systemDark: Boolean,
): ColorThemePreset {
    if (!followSystem) return themePreset
    val partner = themePreset.systemPartner ?: return themePreset
    return listOf(themePreset, partner).first { it.isDark == systemDark }
}

@Serializable(with = ChatWallpaperPresetSerializer::class)
enum class ChatWallpaperPreset {
    NONE,
    MOTD,
    DEEP_SPACE,
    RETRO_GAMING,
    RADIO_CLUB,
    INTERNET_ODDITIES,
    RETRO_CHAT,
    MEMES,
}

/** Decode current wallpaper names plus the seven retired SVG selections from preferences and backups. */
internal fun chatWallpaperPresetFromStored(saved: String?): ChatWallpaperPreset =
    when (saved) {
        "NONE" -> ChatWallpaperPreset.NONE
        "MOTD", "NIGHT_SHIFT", "TERMINAL" -> ChatWallpaperPreset.MOTD
        "DEEP_SPACE" -> ChatWallpaperPreset.DEEP_SPACE
        "RETRO_GAMING", "PIXELS" -> ChatWallpaperPreset.RETRO_GAMING
        "RADIO_CLUB", "SIGNALS" -> ChatWallpaperPreset.RADIO_CLUB
        "INTERNET_ODDITIES", "RELAY" -> ChatWallpaperPreset.INTERNET_ODDITIES
        "RETRO_CHAT", "CHATTER", "CHANNELS" -> ChatWallpaperPreset.RETRO_CHAT
        "MEMES" -> ChatWallpaperPreset.MEMES
        null -> ChatWallpaperPreset.MOTD
        else -> ChatWallpaperPreset.MOTD
    }

internal object ChatWallpaperPresetSerializer : KSerializer<ChatWallpaperPreset> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.trevarj.motd.data.prefs.ChatWallpaperPreset", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: ChatWallpaperPreset,
    ) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): ChatWallpaperPreset = chatWallpaperPresetFromStored(decoder.decodeString())
}

enum class FontChoice { SYSTEM, SANS, SERIF, MONOSPACE, JETBRAINS_MONO, CUSTOM }

enum class TimeFormat { AUTO, H12, H24, CUSTOM }

/** Default custom-timestamp pattern, [java.text.SimpleDateFormat] syntax (not strftime). */
const val DEFAULT_CUSTOM_TIME_FORMAT = "dd/MM/yyyy - HH:mm:ss"

enum class MessageSpacing { COMPACT, DEFAULT, RELAXED }

enum class BubbleCornerStyle { ROUNDED, SUBTLE, SQUARE }

enum class LauncherIcon { DEFAULT, MONO, TERMINAL, GRUVBOX, CATPPUCCIN, NORD, LIGHT }

@Serializable
data class WallpaperSelection(
    val preset: ChatWallpaperPreset = ChatWallpaperPreset.MOTD,
    val intensity: Int = DEFAULT_WALLPAPER_INTENSITY,
) {
    fun normalized() = copy(intensity = intensity.coerceIn(0, 100))
}

@Serializable
data class AppearanceConfig(
    val theme: ColorThemePreset = ColorThemePreset.SYSTEM,
    val wallpaper: WallpaperSelection = WallpaperSelection(),
    val uiFontScalePercent: Int = DEFAULT_FONT_SCALE_PERCENT,
    val conversationFontScalePercent: Int = DEFAULT_FONT_SCALE_PERCENT,
    val trueBlack: Boolean = false,
    val followSystem: Boolean = false,
    val fontChoice: FontChoice = FontChoice.SYSTEM,
    val showTimestamps: Boolean = true,
    val timeFormat: TimeFormat = TimeFormat.AUTO,
    // SimpleDateFormat pattern, only consulted when timeFormat == CUSTOM.
    val customTimeFormatPattern: String = DEFAULT_CUSTOM_TIME_FORMAT,
    val messageSpacing: MessageSpacing = MessageSpacing.DEFAULT,
    val bubbleCornerStyle: BubbleCornerStyle = BubbleCornerStyle.ROUNDED,
    val launcherIcon: LauncherIcon = LauncherIcon.DEFAULT,
    // Display name of a user-imported custom font file; empty means nothing imported. The font
    // binary itself lives in CustomFontStore, not DataStore or backups.
    val customFontName: String = "",
)

interface AppearancePrefs {
    val config: Flow<AppearanceConfig>

    suspend fun setTheme(theme: ColorThemePreset)

    suspend fun setTrueBlack(enabled: Boolean)

    suspend fun setFollowSystem(enabled: Boolean)

    suspend fun setWallpaper(selection: WallpaperSelection)

    suspend fun setUiFontScale(percent: Int)

    suspend fun setConversationFontScale(percent: Int)

    suspend fun setFontChoice(choice: FontChoice)

    suspend fun setShowTimestamps(enabled: Boolean)

    suspend fun setTimeFormat(format: TimeFormat)

    suspend fun setCustomTimeFormatPattern(pattern: String)

    suspend fun setMessageSpacing(spacing: MessageSpacing)

    suspend fun setBubbleCornerStyle(style: BubbleCornerStyle)

    suspend fun setLauncherIcon(icon: LauncherIcon)

    suspend fun setCustomFontName(name: String)
}

const val DEFAULT_WALLPAPER_INTENSITY = 50
const val MIN_FONT_SCALE_PERCENT = 80
const val MAX_FONT_SCALE_PERCENT = 140
const val FONT_SCALE_STEP_PERCENT = 5
const val DEFAULT_FONT_SCALE_PERCENT = 100

fun normalizeFontScalePercent(percent: Int): Int {
    val clamped = percent.coerceIn(MIN_FONT_SCALE_PERCENT, MAX_FONT_SCALE_PERCENT)
    return ((clamped + FONT_SCALE_STEP_PERCENT / 2) / FONT_SCALE_STEP_PERCENT) * FONT_SCALE_STEP_PERCENT
}
