package io.github.trevarj.motd.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.normalizeNick
import kotlin.math.abs
import kotlin.math.floor

// Golden-ratio conjugate: spreads sequential hashes into well-separated slots.
private const val GOLDEN_RATIO_CONJUGATE = 0.618033988749895

/**
 * Deterministic per-nick color used for sender names and avatar backgrounds. Equivalent to the
 * CLASSIC hash palette with no theme context.
 */
fun nickColor(
    nick: String,
    isDark: Boolean,
): Color = paletteNickColor(nick, isDark, NickColorPalette.CLASSIC)

/**
 * Hash seed in 0..1, scattered by *multiplying* with the golden-ratio conjugate. Adjacent hashes
 * (`bob1`/`bob2`, `alice`/`alice_` -- the x31 hash is linear in trailing chars) land ~0.618 apart
 * instead of adjacent; the previous additive form was a constant rotation that spread nothing.
 */
private fun nickHueSeed(nick: String): Double {
    // Stable, case-insensitive hash so "Alice" and "alice" share a color.
    var hash = 0
    for (c in nick.lowercase()) {
        hash = hash * 31 + c.code
    }
    val spread = abs(hash.toLong()) * GOLDEN_RATIO_CONJUGATE
    return spread - floor(spread)
}

/**
 * Curated identity bins for the theme-agnostic palettes: 16 hues x 2 saturation/lightness tiers.
 * Slots are spaced wider through the perceptually compressed green/cyan (95..195) and blue
 * (200..280) HSL bands, so any two different bins stay clearly distinguishable; hash collisions
 * become exact shares rather than misleading near-misses.
 */
internal val NICK_BIN_HUES =
    floatArrayOf(
        0f,
        16f,
        32f,
        48f,
        66f,
        85f,
        110f,
        140f,
        170f,
        195f,
        220f,
        248f,
        275f,
        297f,
        318f,
        340f,
    )
internal const val NICK_BIN_COUNT = 32

internal fun nickBin(nick: String): Int = (nickHueSeed(nick) * NICK_BIN_COUNT).toInt().coerceIn(0, NICK_BIN_COUNT - 1)

/** Bold tier is saturated and mode-deep; soft tier is a clearly separated pastel of the same hue. */
internal fun nickBinSaturation(
    palette: NickColorPalette,
    isDark: Boolean,
    bold: Boolean,
): Float =
    when (palette) {
        NickColorPalette.THEME,
        NickColorPalette.CLASSIC,
        -> if (isDark) (if (bold) 0.72f else 0.48f) else (if (bold) 0.75f else 0.55f)

        NickColorPalette.VIVID -> if (isDark) (if (bold) 0.85f else 0.62f) else (if (bold) 0.88f else 0.68f)
    }

internal fun nickBinLightness(
    palette: NickColorPalette,
    isDark: Boolean,
    bold: Boolean,
): Float =
    when (palette) {
        NickColorPalette.THEME,
        NickColorPalette.CLASSIC,
        -> if (isDark) (if (bold) 0.60f else 0.76f) else (if (bold) 0.38f else 0.52f)

        NickColorPalette.VIVID -> if (isDark) (if (bold) 0.56f else 0.72f) else (if (bold) 0.36f else 0.50f)
    }

/** Bin identity for CLASSIC/VIVID: a fixed-table hue with the palette's saturation tier. */
private fun binIdentity(
    nick: String,
    isDark: Boolean,
    palette: NickColorPalette,
): Color {
    val bin = nickBin(nick)
    val bold = bin < NICK_BIN_HUES.size
    return hslColor(
        NICK_BIN_HUES[bin % NICK_BIN_HUES.size],
        nickBinSaturation(palette, isDark, bold),
        nickBinLightness(palette, isDark, bold),
    )
}

// ---------------------------------------------------------------------------
// Theme-anchored identity
// ---------------------------------------------------------------------------

/**
 * Identity coordinate for a name: a hue slot on the wheel plus a lightness tier.
 *
 * The coordinate is the identity; sender name and avatar are two renderings of it with different
 * budgets. Deriving the avatar from the finished text color -- which is what the hue-only sprite
 * ramp did -- discards the tier and collapses the whole app to one color per hue slot.
 */
internal data class NickCoord(
    val hueSlot: Int,
    val tier: Int,
)

internal const val NICK_HUE_SLOTS = 24
internal const val NICK_TIER_COUNT = 3

/** Sender names must stay readable on chat surfaces, which constrains lightness and chroma. */
private const val NICK_TEXT_CHROMA_FLOOR = 0.70f
private const val NICK_TEXT_TIER_SPREAD = 0.13f

/**
 * A filled disc carries no 4.5:1 obligation because its ink adapts, so avatars can spend chroma
 * the sender name cannot.
 */
private const val NICK_FILL_CHROMA_FLOOR = 0.85f
private const val NICK_FILL_LIGHTNESS = 0.55f
private const val NICK_FILL_TIER_SPREAD = 0.10f

private const val CONTRAST_WALK_STEPS = 45
private const val NICK_TEXT_CONTRAST = 4.5

internal fun nickCoord(nick: String): NickCoord {
    val bins = NICK_HUE_SLOTS * NICK_TIER_COUNT
    val index = (nickHueSeed(nick) * bins).toInt().coerceIn(0, bins - 1)
    return NickCoord(index % NICK_HUE_SLOTS, index / NICK_HUE_SLOTS)
}

/**
 * What the active theme's accents say about how colorful it is willing to be, and where it puts
 * that color in lightness. Relative chroma (not absolute) is the part that tells Zenburn apart
 * from Modus; absolute chroma mostly tracks which hues a theme happened to pick. The primary's hue
 * anchors the wheel so the theme's own colors are literally present in the palette.
 */
@Immutable
internal data class ThemeCharacter(
    val lightness: Float,
    val relativeChroma: Float,
    val anchorHue: Float,
) {
    companion object {
        /** Fallback for previews and un-provided contexts, which carry no accent list. */
        fun neutral(isDark: Boolean): ThemeCharacter = ThemeCharacter(if (isDark) 0.74f else 0.48f, 0.75f, 0f)

        fun of(
            themeColors: List<Color>,
            isDark: Boolean,
        ): ThemeCharacter {
            val accents = themeColors.distinct()
            if (accents.isEmpty()) return neutral(isDark)
            val lch = accents.map { it.toOklch() }
            return ThemeCharacter(
                lightness = lch.map { it.lightness }.average().toFloat(),
                relativeChroma = accents.map { it.relativeChroma() }.average().toFloat(),
                anchorHue = lch.first().hue,
            )
        }
    }
}

private fun slotHue(
    character: ThemeCharacter,
    hueSlot: Int,
): Float = (character.anchorHue + hueSlot * (360f / NICK_HUE_SLOTS)) % 360f

private fun tierOffset(
    tier: Int,
    spread: Float,
): Float =
    when (tier) {
        0 -> -spread
        1 -> 0f
        else -> spread
    }

/**
 * Make an identity color readable by walking lightness toward the mode's contrast pole, re-fitting
 * chroma to the gamut at each step.
 *
 * [ensureContrast] lerps toward black or white instead, which is right for arbitrary foregrounds
 * but destructive for an identity color: it strips chroma and shifts hue, and it was rewriting most
 * of the fixed-table palettes at a mean deltaE near 30. Walking lightness keeps the hue exactly and
 * gives up only the chroma the gamut cannot hold at the new lightness.
 */
internal fun contrastSafeIdentity(
    lightness: Float,
    relativeChroma: Float,
    hue: Float,
    backgrounds: List<Color>,
    isDark: Boolean,
): Color {
    val start = oklchColorAt(lightness, relativeChroma, hue)
    if (backgrounds.isEmpty()) return start
    val step = if (isDark) 0.01f else -0.01f
    for (i in 0..CONTRAST_WALK_STEPS) {
        val candidate = lightness + step * i
        if (candidate < 0.06f || candidate > 0.99f) break
        val color = oklchColorAt(candidate, relativeChroma, hue)
        if (backgrounds.minOf { contrastRatio(color, it) } >= NICK_TEXT_CONTRAST) return color
    }
    // No lightness works (a theme with surfaces at both poles); fall back to the generic fix so the
    // name stays readable even though the identity is compromised.
    return ensureContrast(start, backgrounds)
}

/** Keep an existing color's hue and relative chroma, but make it readable. */
private fun contrastSafe(
    color: Color,
    backgrounds: List<Color>,
    isDark: Boolean,
): Color {
    if (backgrounds.isEmpty()) return color
    val lch = color.toOklch()
    return contrastSafeIdentity(lch.lightness, color.relativeChroma(), lch.hue, backgrounds, isDark)
}

/**
 * Per-nick color scheme resolved once per theme and passed via [LocalNickColors]. Wraps the pure
 * resolvers so components only need `LocalNickColors.current.nick(...)` / `.avatar(...)`.
 */
@Immutable
class NickColorScheme(
    val enabled: Boolean,
    val palette: NickColorPalette,
    val overrides: Map<String, Int>, // normalized nick -> hue
    val isDark: Boolean,
    private val textBackgrounds: List<Color> = emptyList(),
    private val themeColors: List<Color> = emptyList(),
    /** The active theme's published accent palette; empty for schemes that declare none. */
    private val identityPalette: List<Color> = emptyList(),
) {
    // Memoize per nick: a new scheme instance is created whenever palette/overrides/theme change,
    // discarding these caches, so they never go stale. ConcurrentHashMap guards off-main
    // composition.
    private val fillCache = java.util.concurrent.ConcurrentHashMap<String, Color>()
    private val textCache = java.util.concurrent.ConcurrentHashMap<String, Color>()

    private fun fill(nick: String): Color =
        fillCache.getOrPut(nick) {
            val override = overrides[normalizeNick(nick)]
            if (override != null) {
                hueFill(override, isDark, palette, themeColors)
            } else {
                paletteNickFill(nick, isDark, palette, themeColors, identityPalette)
            }
        }

    private fun text(nick: String): Color =
        textCache.getOrPut(nick) {
            val override = overrides[normalizeNick(nick)]
            if (override != null) {
                hueColor(override, isDark, palette, themeColors, textBackgrounds)
            } else {
                paletteNickColor(nick, isDark, palette, themeColors, textBackgrounds, identityPalette)
            }
        }

    /** Sender-name/reply-accent color; [fallback] when coloring is disabled. */
    fun nick(
        nick: String,
        fallback: Color,
    ): Color = if (!enabled) fallback else text(nick)

    fun mention(nick: String): Color = text(nick)

    /** Avatar and channel-mark fill: override + palette always apply (never falls back to neutral). */
    fun avatar(name: String): Color = fill(name)

    /** Color-picker position rendered through the same palette as nick and channel identities. */
    fun hue(hue: Int): Color = hueColor(hue, isDark, palette, themeColors, textBackgrounds)
}

/** Theme-derived defaults for previews and un-provided contexts. */
val LocalNickColors: ProvidableCompositionLocal<NickColorScheme> =
    staticCompositionLocalOf {
        NickColorScheme(
            enabled = true,
            palette = NickColorPalette.THEME,
            overrides = emptyMap(),
            isDark = false,
            themeColors =
                listOf(
                    MotdLightScheme.primary,
                    MotdLightScheme.tertiary,
                    MotdLightScheme.secondary,
                ),
        )
    }

/** Resolution order: disabled -> fallback; override hue -> hueColor; else palette hash. Pure. */
fun resolveNickColor(
    nick: String,
    isDark: Boolean,
    enabled: Boolean,
    palette: NickColorPalette,
    overrides: Map<String, Int>,
    fallback: Color,
    themeColors: List<Color> = emptyList(),
    backgrounds: List<Color> = emptyList(),
): Color {
    if (!enabled) return fallback
    val override = overrides[normalizeNick(nick)]
    return if (override != null) {
        hueColor(override, isDark, palette, themeColors, backgrounds)
    } else {
        paletteNickColor(nick, isDark, palette, themeColors, backgrounds)
    }
}

/**
 * Picks the identity color a name gets.
 *
 * THEME draws from [identityPalette] -- the colors the active theme itself publishes -- so a nick is
 * literally one of the theme's own colors rather than something synthesized nearby. Only when the
 * theme declares no palette (the Material schemes and dynamic color) does it fall back to a hue
 * wheel anchored on the primary, which is Material's own way of widening a three-accent scheme.
 * CLASSIC and VIVID keep their theme-independent hue table. All three go through the hue-preserving
 * contrast fix when [backgrounds] are supplied. Pure.
 */
fun paletteNickColor(
    nick: String,
    isDark: Boolean,
    palette: NickColorPalette,
    themeColors: List<Color> = emptyList(),
    backgrounds: List<Color> = emptyList(),
    identityPalette: List<Color> = emptyList(),
): Color {
    if (palette != NickColorPalette.THEME) {
        return contrastSafe(binIdentity(nick, isDark, palette), backgrounds, isDark)
    }
    themeAccent(nick, identityPalette)?.let { return contrastSafe(it, backgrounds, isDark) }
    val character = ThemeCharacter.of(themeColors, isDark)
    val coord = nickCoord(nick)
    return contrastSafeIdentity(
        lightness =
            (character.lightness + tierOffset(coord.tier, NICK_TEXT_TIER_SPREAD))
                .coerceIn(0.20f, 0.95f),
        relativeChroma = maxOf(NICK_TEXT_CHROMA_FLOOR, character.relativeChroma),
        hue = slotHue(character, coord.hueSlot),
        backgrounds = backgrounds,
        isDark = isDark,
    )
}

/** The theme's own color for this name, or null when the theme publishes no palette. */
private fun themeAccent(
    nick: String,
    identityPalette: List<Color>,
): Color? {
    if (identityPalette.isEmpty()) return null
    val index =
        (nickHueSeed(nick) * identityPalette.size)
            .toInt()
            .coerceIn(0, identityPalette.lastIndex)
    return identityPalette[index]
}

/**
 * Avatar and channel-mark fill for the same identity.
 *
 * A filled disc carries no contrast obligation -- its ink adapts -- so it can use the theme's color
 * exactly as published, with no readability adjustment at all. Sprites stay distinguishable when two
 * names share a color because their shape traits differ; color is only one axis of the avatar. Pure.
 */
fun paletteNickFill(
    nick: String,
    isDark: Boolean,
    palette: NickColorPalette,
    themeColors: List<Color> = emptyList(),
    identityPalette: List<Color> = emptyList(),
): Color {
    if (palette != NickColorPalette.THEME) return binIdentity(nick, isDark, palette)
    themeAccent(nick, identityPalette)?.let { return it }
    val character = ThemeCharacter.of(themeColors, isDark)
    val coord = nickCoord(nick)
    return oklchColorAt(
        lightness =
            (NICK_FILL_LIGHTNESS + tierOffset(coord.tier, NICK_FILL_TIER_SPREAD))
                .coerceIn(0.20f, 0.90f),
        relativeChroma = maxOf(NICK_FILL_CHROMA_FLOOR, character.relativeChroma),
        hue = slotHue(character, coord.hueSlot),
    )
}

/** Sender-name color for a manually picked hue (the picker remains a full wheel). */
fun hueColor(
    hue: Int,
    isDark: Boolean,
    palette: NickColorPalette,
    themeColors: List<Color> = emptyList(),
    backgrounds: List<Color> = emptyList(),
): Color {
    val position = hue.coerceIn(0, 359).toFloat()
    if (palette != NickColorPalette.THEME) {
        return contrastSafe(
            hslColor(position, paletteSaturation(palette, isDark), paletteLightness(palette, isDark)),
            backgrounds,
            isDark,
        )
    }
    val character = ThemeCharacter.of(themeColors, isDark)
    return contrastSafeIdentity(
        lightness = character.lightness.coerceIn(0.20f, 0.95f),
        relativeChroma = maxOf(NICK_TEXT_CHROMA_FLOOR, character.relativeChroma),
        hue = position,
        backgrounds = backgrounds,
        isDark = isDark,
    )
}

/** Avatar fill for a manually picked hue. */
fun hueFill(
    hue: Int,
    isDark: Boolean,
    palette: NickColorPalette,
    themeColors: List<Color> = emptyList(),
): Color {
    val position = hue.coerceIn(0, 359).toFloat()
    if (palette != NickColorPalette.THEME) {
        return hslColor(position, paletteSaturation(palette, isDark), paletteLightness(palette, isDark))
    }
    val character = ThemeCharacter.of(themeColors, isDark)
    return oklchColorAt(
        lightness = NICK_FILL_LIGHTNESS,
        relativeChroma = maxOf(NICK_FILL_CHROMA_FLOOR, character.relativeChroma),
        hue = position,
    )
}

// Continuous saturation/lightness for manual hue overrides on the fixed-table palettes.
private fun paletteSaturation(
    palette: NickColorPalette,
    isDark: Boolean,
): Float =
    when (palette) {
        NickColorPalette.THEME,
        NickColorPalette.CLASSIC,
        -> if (isDark) 0.55f else 0.65f

        NickColorPalette.VIVID -> if (isDark) 0.80f else 0.85f
    }

private fun paletteLightness(
    palette: NickColorPalette,
    isDark: Boolean,
): Float =
    when (palette) {
        NickColorPalette.THEME,
        NickColorPalette.CLASSIC,
        -> if (isDark) 0.68f else 0.42f

        NickColorPalette.VIVID -> if (isDark) 0.62f else 0.38f
    }

/**
 * Pixel-art ramp for a fill color: its own lightness stepped down and up at constant hue. Nothing
 * is rebuilt from constants, so a muted theme keeps its character and a loud one keeps its punch.
 * Shared by the Compose sprite, the channel badge, and the notification bitmap so all three agree.
 */
@Immutable
internal data class IdentityRamp(
    val shade: Color,
    val mid: Color,
    val highlight: Color,
)

internal fun identityRamp(fill: Color): IdentityRamp {
    val lch = fill.toOklch()
    return IdentityRamp(
        shade = oklchColor((lch.lightness - 0.18f).coerceAtLeast(0.14f), lch.chroma * 0.92f, lch.hue),
        mid = fill,
        highlight = oklchColor((lch.lightness + 0.14f).coerceAtMost(0.95f), lch.chroma, lch.hue),
    )
}

/** HSL -> RGB Color (h in degrees, s/l in 0..1). */
internal fun hslColor(
    h: Float,
    s: Float,
    l: Float,
): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val hp = h / 60f
    val x = c * (1f - abs(hp % 2f - 1f))
    val (r1, g1, b1) =
        when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
    val m = l - c / 2f
    return Color(r1 + m, g1 + m, b1 + m)
}
