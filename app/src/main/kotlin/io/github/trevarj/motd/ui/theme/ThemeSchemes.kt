package io.github.trevarj.motd.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.isDark
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TEXT_CONTRAST = 4.5
private const val NON_TEXT_CONTRAST = 3.0
internal const val BUBBLE_CONTAINER_CONTRAST = 1.30

private val MotdLightRaw =
    lightColorScheme(
        primary = MotdTeal,
        secondary = MotdOrchid,
        tertiary = MotdCoral,
    )

private val MotdDarkRaw =
    darkColorScheme(
        primary = MotdTeal,
        secondary = MotdOrchid,
        tertiary = MotdCoral,
    )

internal val MotdLightScheme: ColorScheme = accessibleColorScheme(MotdLightRaw, dark = false)
internal val MotdDarkScheme: ColorScheme = accessibleColorScheme(MotdDarkRaw, dark = true)
internal val MotdLightThemeTextScheme: ColorScheme = accessibleColorScheme(MotdLightRaw, dark = false, useThemeTextColors = true)
internal val MotdDarkThemeTextScheme: ColorScheme = accessibleColorScheme(MotdDarkRaw, dark = true, useThemeTextColors = true)

internal val LocalUseThemeTextColors = staticCompositionLocalOf { false }

/** Static ink follows the applied light/dark canvas, independently of its palette's text hues. */
private fun staticTextInk(scheme: ColorScheme): Color = if (scheme.background.luminance() < 0.5f) Color(0xFFF5F5F5) else Color(0xFF171717)

/** Reading renderers fit the selected ink to their actual fill, not just the theme canvas. */
internal fun readingInk(
    scheme: ColorScheme,
    useThemeTextColors: Boolean,
): Color = if (useThemeTextColors) scheme.onBackground else staticTextInk(scheme)

@Composable
internal fun readingInk(scheme: ColorScheme): Color = readingInk(scheme, LocalUseThemeTextColors.current)

internal fun contrastRatio(
    a: Color,
    b: Color,
): Double {
    val lighter = max(a.luminance(), b.luminance()).toDouble()
    val darker = min(a.luminance(), b.luminance()).toDouble()
    return (lighter + 0.05) / (darker + 0.05)
}

private fun minimumContrast(
    foreground: Color,
    backgrounds: List<Color>,
): Double = backgrounds.minOf { contrastRatio(foreground, it) }

/** Move only the tone toward black or white, choosing the smallest contrast-safe change. */
internal fun ensureContrast(
    foreground: Color,
    backgrounds: List<Color>,
    minimum: Double = TEXT_CONTRAST,
): Color {
    if (backgrounds.isEmpty() || minimumContrast(foreground, backgrounds) >= minimum) return foreground

    val targets =
        listOf(Color.Black, Color.White)
            .filter { minimumContrast(it, backgrounds) >= minimum }
    if (targets.isEmpty()) {
        return listOf(Color.Black, Color.White).maxBy { minimumContrast(it, backgrounds) }
    }

    var best = targets.first()
    var bestAmount = 1f
    for (target in targets) {
        var low = 0f
        var high = 1f
        repeat(24) {
            val mid = (low + high) / 2f
            if (minimumContrast(lerp(foreground, target, mid), backgrounds) >= minimum) {
                high = mid
            } else {
                low = mid
            }
        }
        if (high < bestAmount) {
            bestAmount = high
            best = lerp(foreground, target, high)
        }
    }
    return best
}

internal fun bestOnColor(background: Color): Color =
    if (contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)) {
        Color.White
    } else {
        Color.Black
    }

/** Preserve hue where possible while making a filled surface visibly distinct from the canvas. */
private fun ensureContainerContrast(
    container: Color,
    background: Color,
    dark: Boolean,
    minimum: Double,
): Color {
    if (contrastRatio(container, background) >= minimum) return container
    val target = if (dark) Color.White else Color.Black
    var low = 0f
    var high = 1f
    repeat(24) {
        val mid = (low + high) / 2f
        if (contrastRatio(lerp(container, target, mid), background) >= minimum) {
            high = mid
        } else {
            low = mid
        }
    }
    return lerp(container, target, high)
}

internal fun accessibleColorScheme(
    raw: ColorScheme,
    dark: Boolean,
    useThemeTextColors: Boolean = false,
): ColorScheme {
    val surfaceContainerLow =
        ensureContainerContrast(
            raw.surfaceContainerLow,
            raw.background,
            dark,
            1.08,
        )
    val surfaceContainer =
        ensureContainerContrast(
            raw.surfaceContainer,
            raw.background,
            dark,
            1.16,
        )
    val surfaceContainerHigh =
        ensureContainerContrast(
            raw.surfaceContainerHigh,
            raw.background,
            dark,
            BUBBLE_CONTAINER_CONTRAST,
        )
    val surfaceContainerHighest =
        ensureContainerContrast(
            raw.surfaceContainerHighest,
            raw.background,
            dark,
            1.45,
        )
    val surfaceVariant =
        ensureContainerContrast(
            raw.surfaceVariant,
            raw.background,
            dark,
            BUBBLE_CONTAINER_CONTRAST,
        )
    val neutralSurfaces =
        listOf(
            raw.background,
            raw.surface,
            raw.surfaceContainerLowest,
            surfaceContainerLow,
            surfaceContainer,
            surfaceContainerHigh,
            surfaceContainerHighest,
            surfaceVariant,
        )
    val onSurface = ensureContrast(staticTextInk(raw), neutralSurfaces)
    val primary = ensureContrast(raw.primary, neutralSurfaces)
    val secondary = ensureContrast(raw.secondary, neutralSurfaces)
    val tertiary = ensureContrast(raw.tertiary, neutralSurfaces)

    fun container(accent: Color): Color =
        ensureContainerContrast(
            lerp(raw.background, accent, if (dark) 0.22f else 0.16f),
            raw.background,
            dark,
            BUBBLE_CONTAINER_CONTRAST,
        )
    val primaryContainer = container(raw.primary)
    val secondaryContainer = container(raw.secondary)
    val tertiaryContainer = container(raw.tertiary)

    val errorSeed = if (dark) Color(0xFFF2B8B5) else Color(0xFFB3261E)
    val error = ensureContrast(errorSeed, neutralSurfaces)
    val errorContainer = container(errorSeed)
    val outline = ensureContrast(raw.outline, neutralSurfaces, NON_TEXT_CONTRAST)
    val outlineVariant = ensureContrast(raw.outlineVariant, neutralSurfaces, NON_TEXT_CONTRAST)
    val inverseSurface = onSurface
    val inverseOnSurface = ensureContrast(raw.surface, listOf(inverseSurface))
    val inversePrimary = ensureContrast(raw.primary, listOf(inverseSurface))

    return raw.copy(
        primary = primary,
        onPrimary = bestOnColor(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = ensureContrast(onSurface, listOf(primaryContainer)),
        secondary = secondary,
        onSecondary = bestOnColor(secondary),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = ensureContrast(onSurface, listOf(secondaryContainer)),
        tertiary = tertiary,
        onTertiary = bestOnColor(tertiary),
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = ensureContrast(onSurface, listOf(tertiaryContainer)),
        background = raw.background,
        onBackground = if (useThemeTextColors) ensureContrast(raw.onBackground, neutralSurfaces) else onSurface,
        surface = raw.surface,
        onSurface = if (useThemeTextColors) ensureContrast(raw.onSurface, neutralSurfaces) else onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = if (useThemeTextColors) ensureContrast(raw.onSurfaceVariant, neutralSurfaces) else onSurface,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        outline = outline,
        outlineVariant = outlineVariant,
        error = error,
        onError = bestOnColor(error),
        errorContainer = errorContainer,
        onErrorContainer = ensureContrast(onSurface, listOf(errorContainer)),
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        inversePrimary = inversePrimary,
        surfaceTint = primary,
        scrim = Color.Black,
        surfaceDim = if (dark) raw.surfaceContainerLowest else surfaceContainerHighest,
        surfaceBright = if (dark) surfaceContainerHighest else raw.surfaceContainerLowest,
    )
}

private val RawFixedSchemes: Map<ColorThemePreset, ColorScheme> by lazy {
    mapOf(
        ColorThemePreset.AYU_DARK to AyuDarkScheme,
        ColorThemePreset.AYU_LIGHT to AyuLightScheme,
        ColorThemePreset.AYU_MIRAGE to AyuMirageScheme,
        ColorThemePreset.CATPPUCCIN_LATTE to CatppuccinLatteScheme,
        ColorThemePreset.CATPPUCCIN_MOCHA to CatppuccinMochaScheme,
        ColorThemePreset.DRACULA to DraculaScheme,
        ColorThemePreset.EVERFOREST_DARK to EverforestDarkScheme,
        ColorThemePreset.EVERFOREST_LIGHT to EverforestLightScheme,
        ColorThemePreset.GRUVBOX_DARK to GruvboxDarkScheme,
        ColorThemePreset.GRUVBOX_LIGHT to GruvboxLightScheme,
        ColorThemePreset.KANAGAWA_DRAGON to KanagawaDragonScheme,
        ColorThemePreset.KANAGAWA_LOTUS to KanagawaLotusScheme,
        ColorThemePreset.KANAGAWA_WAVE to KanagawaWaveScheme,
        ColorThemePreset.MODUS_OPERANDI to ModusOperandiScheme,
        ColorThemePreset.MODUS_VIVENDI to ModusVivendiScheme,
        ColorThemePreset.MODUS_OPERANDI_TINTED to ModusOperandiTintedScheme,
        ColorThemePreset.MODUS_VIVENDI_TINTED to ModusVivendiTintedScheme,
        ColorThemePreset.MODUS_OPERANDI_DEUTERANOPIA to ModusOperandiDeuteranopiaScheme,
        ColorThemePreset.MODUS_VIVENDI_DEUTERANOPIA to ModusVivendiDeuteranopiaScheme,
        ColorThemePreset.MODUS_OPERANDI_TRITANOPIA to ModusOperandiTritanopiaScheme,
        ColorThemePreset.MODUS_VIVENDI_TRITANOPIA to ModusVivendiTritanopiaScheme,
        ColorThemePreset.MONOKAI to MonokaiScheme,
        ColorThemePreset.NORD to NordScheme,
        ColorThemePreset.NORD_LIGHT to NordLightScheme,
        ColorThemePreset.ONE_DARK to OneDarkScheme,
        ColorThemePreset.ROSE_PINE to RosePineScheme,
        ColorThemePreset.ROSE_PINE_DAWN to RosePineDawnScheme,
        ColorThemePreset.ROSE_PINE_MOON to RosePineMoonScheme,
        ColorThemePreset.SOLARIZED_DARK to SolarizedDarkScheme,
        ColorThemePreset.SOLARIZED_LIGHT to SolarizedLightScheme,
        ColorThemePreset.TOKYO_NIGHT to TokyoNightScheme,
        ColorThemePreset.ZENBURN to ZenburnScheme,
    )
}

private val FixedSchemes by lazy {
    RawFixedSchemes.mapValues { (preset, raw) -> accessibleColorScheme(raw, preset.isDark) }
}
private val ThemeTextFixedSchemes by lazy {
    RawFixedSchemes.mapValues { (preset, raw) -> accessibleColorScheme(raw, preset.isDark, useThemeTextColors = true) }
}

internal fun fixedThemeScheme(
    theme: ColorThemePreset,
    useThemeTextColors: Boolean = false,
): ColorScheme? = if (useThemeTextColors) ThemeTextFixedSchemes[theme] else FixedSchemes[theme]

private data class Hsl(
    val hue: Float,
    val saturation: Float,
)

private fun Color.hslIdentity(): Hsl {
    val maximum = max(red, max(green, blue))
    val minimum = min(red, min(green, blue))
    val delta = maximum - minimum
    val lightness = (maximum + minimum) / 2f
    if (delta == 0f) return Hsl(0f, 0f)
    val saturation = delta / (1f - abs(2f * lightness - 1f))
    val hue =
        when (maximum) {
            red -> 60f * (((green - blue) / delta) % 6f)
            green -> 60f * (((blue - red) / delta) + 2f)
            else -> 60f * (((red - green) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }
    return Hsl(hue, saturation)
}

/** True-black canvas with a consistent, subtly palette-tinted elevation ladder. */
internal fun ColorScheme.withTrueBlackSurfaces(): ColorScheme {
    val identity = surfaceContainerHighest.hslIdentity()
    val saturation = identity.saturation.coerceAtMost(0.18f)

    fun layer(lightness: Float) = hslColor(identity.hue, saturation, lightness)
    val low = layer(0.05f)
    val container = layer(0.085f)
    val high =
        ensureContainerContrast(
            layer(0.14f),
            Color.Black,
            dark = true,
            minimum = BUBBLE_CONTAINER_CONTRAST,
        )
    val highest =
        ensureContainerContrast(
            layer(0.18f),
            Color.Black,
            dark = true,
            minimum = 1.45,
        )
    return copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = low,
        surfaceContainer = container,
        surfaceContainerHigh = high,
        surfaceContainerHighest = highest,
        surfaceVariant = highest,
        surfaceBright = layer(0.22f),
    )
}

@Immutable
data class MotdSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val DefaultSemanticColors =
    MotdSemanticColors(
        success = Color(0xFF147D3F),
        onSuccess = Color.White,
        successContainer = Color(0xFFD7F7DF),
        onSuccessContainer = Color(0xFF082713),
        warning = Color(0xFF8A4F00),
        onWarning = Color.White,
        warningContainer = Color(0xFFFFDDB5),
        onWarningContainer = Color(0xFF2C1600),
    )

val LocalMotdSemanticColors: ProvidableCompositionLocal<MotdSemanticColors> =
    staticCompositionLocalOf { DefaultSemanticColors }

internal fun semanticColors(
    scheme: ColorScheme,
    dark: Boolean,
): MotdSemanticColors {
    val surfaces =
        listOf(
            scheme.background,
            scheme.surface,
            scheme.surfaceContainerLow,
            scheme.surfaceContainerHigh,
            scheme.surfaceContainerHighest,
        )

    fun family(seed: Color): Pair<Color, Color> {
        val role = ensureContrast(seed, surfaces)
        val container = lerp(scheme.background, seed, if (dark) 0.22f else 0.16f)
        return role to container
    }
    val (success, successContainer) = family(if (dark) Color(0xFF6DD58C) else Color(0xFF147D3F))
    val (warning, warningContainer) = family(if (dark) Color(0xFFFFB95C) else Color(0xFF8A4F00))
    // The inverse fill retains the normalized neutral ink, even when ordinary text uses palette hues.
    val containerInk = scheme.inverseSurface
    return MotdSemanticColors(
        success = success,
        onSuccess = bestOnColor(success),
        successContainer = successContainer,
        onSuccessContainer = ensureContrast(containerInk, listOf(successContainer)),
        warning = warning,
        onWarning = bestOnColor(warning),
        warningContainer = warningContainer,
        onWarningContainer = ensureContrast(containerInk, listOf(warningContainer)),
    )
}

/** Composite [overlay] at [alpha], then reduce alpha only when needed to keep [foregrounds] readable. */
internal fun contrastSafeOverlay(
    base: Color,
    overlay: Color,
    requestedAlpha: Float,
    foregrounds: List<Color>,
    minimum: Double = TEXT_CONTRAST,
): Color {
    val requested = requestedAlpha.coerceIn(0f, 1f)

    fun composite(alpha: Float) = overlay.copy(alpha = alpha).compositeOver(base)
    if (foregrounds.all { contrastRatio(it, composite(requested)) >= minimum }) {
        return overlay.copy(alpha = requested)
    }
    var low = 0f
    var high = requested
    repeat(24) {
        val mid = (low + high) / 2f
        if (foregrounds.all { contrastRatio(it, composite(mid)) >= minimum }) low = mid else high = mid
    }
    return overlay.copy(alpha = low)
}
