package io.github.trevarj.motd.data.prefs

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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
    DRACULA_LIGHT,
    TOKYO_NIGHT_LIGHT,
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
            ColorThemePreset.DRACULA_LIGHT,
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
            ColorThemePreset.TOKYO_NIGHT_LIGHT,
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
            ColorThemePreset.DRACULA -> ColorThemePreset.DRACULA_LIGHT
            ColorThemePreset.DRACULA_LIGHT -> ColorThemePreset.DRACULA
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
            ColorThemePreset.TOKYO_NIGHT -> ColorThemePreset.TOKYO_NIGHT_LIGHT
            ColorThemePreset.TOKYO_NIGHT_LIGHT -> ColorThemePreset.TOKYO_NIGHT
            else -> null
        }

/** Stable family identity, independent of the currently displayed mode. */
val ColorThemePreset.canonicalPalette: ColorThemePreset
    get() =
        when {
            this == ColorThemePreset.AMOLED -> ColorThemePreset.DARK
            this == ColorThemePreset.SYSTEM || isDark -> this
            else -> systemPartner ?: this
        }

fun resolvePalettePreset(
    themePreset: ColorThemePreset,
    mode: PaletteMode,
    systemDark: Boolean,
): ColorThemePreset {
    val builtin = themePreset.canonicalPalette
    if (builtin == ColorThemePreset.SYSTEM) return builtin
    val dark =
        when (mode) {
            PaletteMode.SYSTEM -> systemDark
            PaletteMode.DARK -> true
            PaletteMode.LIGHT -> false
        }
    return if (builtin.isDark == dark) builtin else builtin.systemPartner ?: builtin
}

fun normalizePaletteSelection(
    selection: PaletteSelection,
    palettes: List<InstalledPalette>,
): PaletteSelection {
    val builtin = selection.builtin.canonicalPalette
    val document =
        selection.installedId?.let { id ->
            requireNotNull(palettes.find { it.document.id == id }) { "Selected palette is not installed" }.document
        }
    val mode =
        if (document != null) {
            require(document.light != null || document.dark != null) { "Palette needs at least one mode" }
            when {
                document.light == null -> PaletteMode.DARK
                document.dark == null -> PaletteMode.LIGHT
                else -> selection.mode
            }
        } else if (builtin != ColorThemePreset.SYSTEM && builtin.systemPartner == null) {
            if (builtin.isDark) PaletteMode.DARK else PaletteMode.LIGHT
        } else {
            selection.mode
        }
    return selection.copy(builtin = builtin, mode = mode)
}

enum class PaletteReadError { INVALID, TOO_LARGE }

val AppearanceConfig.paletteSelection: PaletteSelection
    get() {
        val fallback = PaletteSelection(ColorThemePreset.SYSTEM, null, PaletteMode.SYSTEM)
        if (customPaletteId != null &&
            (customPaletteReadError != null || customPalettes.none { it.document.id == customPaletteId })
        ) {
            return fallback
        }
        val mode =
            paletteMode ?: when {
                theme == ColorThemePreset.SYSTEM -> PaletteMode.SYSTEM

                followSystem && (theme.systemPartner != null || theme == ColorThemePreset.AMOLED) &&
                    theme !in listOf(ColorThemePreset.DRACULA, ColorThemePreset.TOKYO_NIGHT) -> PaletteMode.SYSTEM

                theme.isDark -> PaletteMode.DARK

                else -> PaletteMode.LIGHT
            }
        return normalizePaletteSelection(PaletteSelection(theme, customPaletteId, mode), customPalettes)
    }

val AppearanceConfig.selectedCustomPalette: PaletteDocument?
    get() = paletteSelection.installedId?.let { id -> customPalettes.find { it.document.id == id }?.document }

/** Editor-only revision handling; importing/replacing a snapshot keeps its supplied revision. */
fun prepareLocalPaletteSave(
    original: PaletteDocument,
    draft: PaletteDocument,
): PaletteDocument {
    require(original.id == draft.id) { "Local edit must retain its palette ID" }
    val previous = decodePalette(encodePalette(original))
    val candidate = decodePalette(encodePalette(draft.copy(revision = original.revision)))
    if (encodePalette(previous).contentEquals(encodePalette(candidate))) return previous
    require(original.revision < Int.MAX_VALUE) { "Revision limit reached; duplicate this palette" }
    return decodePalette(encodePalette(candidate.copy(revision = original.revision + 1)))
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
    LINUX,
    SYSTEMCRAFTERS,
    JAPANESE_COUNTRYSIDE,
    RAINY_CAFE,
    MOTD_ARCADE,
    MIDNIGHT_TERMINAL,
    CYBERPUNK_CANAL,
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
        "LINUX" -> ChatWallpaperPreset.LINUX
        "SYSTEMCRAFTERS" -> ChatWallpaperPreset.SYSTEMCRAFTERS
        "JAPANESE_COUNTRYSIDE" -> ChatWallpaperPreset.JAPANESE_COUNTRYSIDE
        "RAINY_CAFE" -> ChatWallpaperPreset.RAINY_CAFE
        "MOTD_ARCADE" -> ChatWallpaperPreset.MOTD_ARCADE
        "MIDNIGHT_TERMINAL" -> ChatWallpaperPreset.MIDNIGHT_TERMINAL
        "CYBERPUNK_CANAL" -> ChatWallpaperPreset.CYBERPUNK_CANAL
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

enum class ComposerStyle { COMFORTABLE, LARGE, COMPACT }

enum class BubbleCornerStyle { ROUNDED, SUBTLE, SQUARE }

enum class UiCornerStyle { DEFAULT, SQUARE, SOFT, ROUNDED }

enum class AvatarShape { DEFAULT, SQUARE, SOFT, ROUNDED, CIRCLE }

enum class UiIconSet { MATERIAL, LUCIDE }

/** A theme role stays adaptive; CUSTOM retains the user-picked opaque sRGB color. */
enum class ComfortableBubbleColorRole {
    DEFAULT,
    SURFACE_CONTAINER,
    SURFACE_CONTAINER_HIGH,
    PRIMARY,
    SECONDARY,
    TERTIARY,
    PRIMARY_CONTAINER,
    SECONDARY_CONTAINER,
    TERTIARY_CONTAINER,
    CUSTOM,
}

@Serializable
data class ComfortableBubbleColor(
    val role: ComfortableBubbleColorRole = ComfortableBubbleColorRole.DEFAULT,
    // Opaque sRGB ARGB when role is CUSTOM. Kept nullable so old backups use the default.
    val customColor: Int? = null,
) {
    fun normalized(): ComfortableBubbleColor =
        if (role == ComfortableBubbleColorRole.CUSTOM && customColor != null) {
            copy(customColor = customColor.or(0xff000000.toInt()))
        } else {
            copy(role = if (role == ComfortableBubbleColorRole.CUSTOM) ComfortableBubbleColorRole.DEFAULT else role, customColor = null)
        }
}

enum class LauncherIcon { DEFAULT, MONO, TERMINAL, GRUVBOX, CATPPUCCIN, NORD, LIGHT }

/** Retained for preference and configuration-backup compatibility; startup no longer reads it. */
enum class SplashScreenAnimation { EDGE_TRACE, SOFT_WASH, TRAVELING_PULSE, STATIC }

@Serializable
data class WallpaperSelection(
    val preset: ChatWallpaperPreset = ChatWallpaperPreset.MOTD,
    val intensity: Int = DEFAULT_WALLPAPER_INTENSITY,
    // Local-only; portable backups retain the builtin fallback instead of a private file reference.
    @Transient val localImageName: String? = null,
    // Opaque sRGB ARGB; null keeps the theme-adaptive ink used by existing selections.
    val outlineColor: Int? = null,
    // Scenic artwork follows the OS palette by default, independently of the app theme.
    val scenicFollowSystem: Boolean = true,
    // Static scenic artwork remains available when rare authored events are disabled.
    val scenicAnimationEnabled: Boolean = true,
) {
    fun normalized() = copy(intensity = intensity.coerceIn(0, 100), outlineColor = outlineColor?.or(0xff000000.toInt()))
}

@Serializable
data class SurfaceEffect(
    val opacityPercent: Int = 100,
    val blurPercent: Int = 0,
) {
    fun normalized() = copy(opacityPercent = opacityPercent.coerceIn(0, 100), blurPercent = blurPercent.coerceIn(0, 100))
}

private val OpaqueSurfaceEffect = SurfaceEffect()
private val ChatListSurfaceEffect = SurfaceEffect(60, 100)

@Serializable
enum class EffectSurface {
    CHAT_HEADER,
    INPUT_BAR,
    CHAT_LIST,
    MENU,
    SHEET,
    DRAWER,
    MINI_PLAYER,
    FLOATING_BUTTON,
}

@Serializable
data class SurfaceEffects(
    val enabled: Boolean = true,
    val surfaces: Map<EffectSurface, SurfaceEffect> = emptyMap(),
) {
    operator fun get(surface: EffectSurface): SurfaceEffect = surfaces[surface] ?: if (surface == EffectSurface.CHAT_LIST) ChatListSurfaceEffect else OpaqueSurfaceEffect

    fun normalized() = copy(surfaces = surfaces.mapValues { it.value.normalized() })
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
    val chatShadowsEnabled: Boolean = false,
    val nicknameBackgroundsEnabled: Boolean = true,
    val messageBackgroundOpacityPercent: Int = 100,
    val messageBackgroundBlur: Int = 0,
    val surfaceEffects: SurfaceEffects = SurfaceEffects(),
    val linkGlobalOpacity: Boolean = false,
    val linkGlobalBlur: Boolean = false,
    val sentBubbleColor: ComfortableBubbleColor = ComfortableBubbleColor(),
    val receivedBubbleColor: ComfortableBubbleColor = ComfortableBubbleColor(),
    val launcherIcon: LauncherIcon = LauncherIcon.DEFAULT,
    val splashScreenAnimation: SplashScreenAnimation = SplashScreenAnimation.EDGE_TRACE,
    // Display name of a user-imported custom font file; empty means nothing imported. The font
    // binary itself lives in CustomFontStore, not DataStore or backups.
    val customFontName: String = "",
    val composerStyle: ComposerStyle = ComposerStyle.COMFORTABLE,
    val useThemeTextColors: Boolean = false,
    val inputCornerStyle: UiCornerStyle = UiCornerStyle.DEFAULT,
    val avatarShape: AvatarShape = AvatarShape.DEFAULT,
    val chatCardCornerStyle: UiCornerStyle = UiCornerStyle.DEFAULT,
    val collapseTitleBar: Boolean = true,
    val mediaCornerStyle: UiCornerStyle = UiCornerStyle.DEFAULT,
    val uiIconSet: UiIconSet = UiIconSet.MATERIAL,
    val paletteMode: PaletteMode? = null,
    val customPaletteId: String? = null,
    val customPalettes: List<InstalledPalette> = emptyList(),
    @Transient val customPaletteReadError: PaletteReadError? = null,
)

interface AppearancePrefs {
    val config: Flow<AppearanceConfig>

    suspend fun selectPalette(selection: PaletteSelection)

    suspend fun savePalette(
        palette: InstalledPalette,
        apply: Boolean,
        mode: PaletteMode,
        targetInstalledId: String? = null,
        expectedTarget: InstalledPalette? = null,
    )

    suspend fun removePalette(id: String)

    suspend fun restorePalettes(
        palettes: List<InstalledPalette>,
        selection: PaletteSelection,
    )

    suspend fun setTrueBlack(enabled: Boolean)

    suspend fun setUseThemeTextColors(enabled: Boolean)

    suspend fun setWallpaper(selection: WallpaperSelection)

    suspend fun setUiFontScale(percent: Int)

    suspend fun setConversationFontScale(percent: Int)

    suspend fun setFontChoice(choice: FontChoice)

    suspend fun setShowTimestamps(enabled: Boolean)

    suspend fun setTimeFormat(format: TimeFormat)

    suspend fun setCustomTimeFormatPattern(pattern: String)

    suspend fun setMessageSpacing(spacing: MessageSpacing)

    suspend fun setComposerStyle(style: ComposerStyle)

    suspend fun setInputCornerStyle(style: UiCornerStyle)

    suspend fun setAvatarShape(shape: AvatarShape)

    suspend fun setChatCardCornerStyle(style: UiCornerStyle)

    suspend fun setCollapseTitleBar(enabled: Boolean)

    suspend fun setMediaCornerStyle(style: UiCornerStyle)

    suspend fun setUiIconSet(iconSet: UiIconSet)

    suspend fun setBubbleCornerStyle(style: BubbleCornerStyle)

    suspend fun setChatShadowsEnabled(enabled: Boolean)

    suspend fun setNicknameBackgroundsEnabled(enabled: Boolean)

    suspend fun setMessageBackgroundOpacity(percent: Int)

    suspend fun setMessageBackgroundBlur(intensity: Int)

    suspend fun setSurfaceEffectsEnabled(enabled: Boolean)

    suspend fun setSurfaceOpacity(
        surface: EffectSurface,
        percent: Int,
    )

    suspend fun setSurfaceBlur(
        surface: EffectSurface,
        percent: Int,
    )

    suspend fun setLinkGlobalOpacity(enabled: Boolean)

    suspend fun setLinkGlobalBlur(enabled: Boolean)

    /** Restore exact values, without applying interactive slider links. */
    suspend fun restoreGlobalEffects(config: AppearanceConfig)

    suspend fun setSentBubbleColor(color: ComfortableBubbleColor)

    suspend fun setReceivedBubbleColor(color: ComfortableBubbleColor)

    suspend fun setLauncherIcon(icon: LauncherIcon)

    suspend fun setSplashScreenAnimation(animation: SplashScreenAnimation)

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
