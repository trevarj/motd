package io.github.trevarj.motd.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TEXT_CONTRAST = 4.5
private const val NON_TEXT_CONTRAST = 3.0

private val MotdSecondary = Color(0xFF66708E)
private val MotdTertiary = Color(0xFF2E9B67)

private val MotdLightRaw = lightColorScheme(
    primary = Indigo,
    secondary = MotdSecondary,
    tertiary = MotdTertiary,
)

private val MotdDarkRaw = darkColorScheme(
    primary = Indigo,
    secondary = MotdSecondary,
    tertiary = MotdTertiary,
)

internal val MotdLightScheme: ColorScheme = accessibleColorScheme(MotdLightRaw, dark = false)
internal val MotdDarkScheme: ColorScheme = accessibleColorScheme(MotdDarkRaw, dark = true)

internal fun contrastRatio(a: Color, b: Color): Double {
    val lighter = max(a.luminance(), b.luminance()).toDouble()
    val darker = min(a.luminance(), b.luminance()).toDouble()
    return (lighter + 0.05) / (darker + 0.05)
}

private fun minimumContrast(foreground: Color, backgrounds: List<Color>): Double =
    backgrounds.minOf { contrastRatio(foreground, it) }

/** Move only the tone toward black or white, choosing the smallest contrast-safe change. */
internal fun ensureContrast(
    foreground: Color,
    backgrounds: List<Color>,
    minimum: Double = TEXT_CONTRAST,
): Color {
    if (backgrounds.isEmpty() || minimumContrast(foreground, backgrounds) >= minimum) return foreground

    val targets = listOf(Color.Black, Color.White)
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
            if (minimumContrast(lerp(foreground, target, mid), backgrounds) >= minimum) high = mid
            else low = mid
        }
        if (high < bestAmount) {
            bestAmount = high
            best = lerp(foreground, target, high)
        }
    }
    return best
}

internal fun bestOnColor(background: Color): Color =
    if (contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)) Color.White
    else Color.Black

internal fun accessibleColorScheme(raw: ColorScheme, dark: Boolean): ColorScheme {
    val neutralSurfaces = listOf(
        raw.background,
        raw.surface,
        raw.surfaceContainerLowest,
        raw.surfaceContainerLow,
        raw.surfaceContainer,
        raw.surfaceContainerHigh,
        raw.surfaceContainerHighest,
        raw.surfaceVariant,
    )
    val onSurface = ensureContrast(raw.onSurface, neutralSurfaces)
    val onSurfaceVariant = ensureContrast(raw.onSurfaceVariant, neutralSurfaces)
    val primary = ensureContrast(raw.primary, neutralSurfaces)
    val secondary = ensureContrast(raw.secondary, neutralSurfaces)
    val tertiary = ensureContrast(raw.tertiary, neutralSurfaces)

    fun container(accent: Color): Color = lerp(raw.background, accent, if (dark) 0.22f else 0.16f)
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
        onBackground = ensureContrast(raw.onBackground, neutralSurfaces),
        surface = raw.surface,
        onSurface = onSurface,
        surfaceVariant = raw.surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
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
        surfaceDim = if (dark) raw.surfaceContainerLowest else raw.surfaceContainerHighest,
        surfaceBright = if (dark) raw.surfaceContainerHighest else raw.surfaceContainerLowest,
    )
}

private val FixedSchemes: Map<ColorThemePreset, ColorScheme> by lazy {
    mapOf(
        ColorThemePreset.AYU_DARK to accessibleColorScheme(AyuDarkScheme, true),
        ColorThemePreset.AYU_LIGHT to accessibleColorScheme(AyuLightScheme, false),
        ColorThemePreset.AYU_MIRAGE to accessibleColorScheme(AyuMirageScheme, true),
        ColorThemePreset.CATPPUCCIN_LATTE to accessibleColorScheme(CatppuccinLatteScheme, false),
        ColorThemePreset.CATPPUCCIN_MOCHA to accessibleColorScheme(CatppuccinMochaScheme, true),
        ColorThemePreset.DRACULA to accessibleColorScheme(DraculaScheme, true),
        ColorThemePreset.EVERFOREST_DARK to accessibleColorScheme(EverforestDarkScheme, true),
        ColorThemePreset.EVERFOREST_LIGHT to accessibleColorScheme(EverforestLightScheme, false),
        ColorThemePreset.GRUVBOX_DARK to accessibleColorScheme(GruvboxDarkScheme, true),
        ColorThemePreset.GRUVBOX_LIGHT to accessibleColorScheme(GruvboxLightScheme, false),
        ColorThemePreset.KANAGAWA_DRAGON to accessibleColorScheme(KanagawaDragonScheme, true),
        ColorThemePreset.KANAGAWA_LOTUS to accessibleColorScheme(KanagawaLotusScheme, false),
        ColorThemePreset.KANAGAWA_WAVE to accessibleColorScheme(KanagawaWaveScheme, true),
        ColorThemePreset.MODUS_OPERANDI to accessibleColorScheme(ModusOperandiScheme, false),
        ColorThemePreset.MODUS_VIVENDI to accessibleColorScheme(ModusVivendiScheme, true),
        ColorThemePreset.MONOKAI to accessibleColorScheme(MonokaiScheme, true),
        ColorThemePreset.NORD to accessibleColorScheme(NordScheme, true),
        ColorThemePreset.ONE_DARK to accessibleColorScheme(OneDarkScheme, true),
        ColorThemePreset.ROSE_PINE to accessibleColorScheme(RosePineScheme, true),
        ColorThemePreset.ROSE_PINE_DAWN to accessibleColorScheme(RosePineDawnScheme, false),
        ColorThemePreset.ROSE_PINE_MOON to accessibleColorScheme(RosePineMoonScheme, true),
        ColorThemePreset.SOLARIZED_DARK to accessibleColorScheme(SolarizedDarkScheme, true),
        ColorThemePreset.SOLARIZED_LIGHT to accessibleColorScheme(SolarizedLightScheme, false),
        ColorThemePreset.TOKYO_NIGHT to accessibleColorScheme(TokyoNightScheme, true),
        ColorThemePreset.ZENBURN to accessibleColorScheme(ZenburnScheme, true),
    )
}

internal fun fixedThemeScheme(theme: ColorThemePreset): ColorScheme? = FixedSchemes[theme]

private data class Hsl(val hue: Float, val saturation: Float)

private fun Color.hslIdentity(): Hsl {
    val maximum = max(red, max(green, blue))
    val minimum = min(red, min(green, blue))
    val delta = maximum - minimum
    val lightness = (maximum + minimum) / 2f
    if (delta == 0f) return Hsl(0f, 0f)
    val saturation = delta / (1f - abs(2f * lightness - 1f))
    val hue = when (maximum) {
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
    val low = layer(0.04f)
    val container = layer(0.065f)
    val high = layer(0.09f)
    val highest = layer(0.12f)
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
        surfaceBright = layer(0.16f),
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

private val DefaultSemanticColors = MotdSemanticColors(
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

internal fun semanticColors(scheme: ColorScheme, dark: Boolean): MotdSemanticColors {
    val surfaces = listOf(
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
    return MotdSemanticColors(
        success = success,
        onSuccess = bestOnColor(success),
        successContainer = successContainer,
        onSuccessContainer = ensureContrast(scheme.onSurface, listOf(successContainer)),
        warning = warning,
        onWarning = bestOnColor(warning),
        warningContainer = warningContainer,
        onWarningContainer = ensureContrast(scheme.onSurface, listOf(warningContainer)),
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
