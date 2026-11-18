package io.github.trevarj.motd.ui.theme

import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
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

internal val MotdLightRaw =
    lightColorScheme(
        primary = MotdTeal,
        secondary = MotdOrchid,
        tertiary = MotdCoral,
    )

internal val MotdDarkRaw =
    darkColorScheme(
        primary = MotdTeal,
        secondary = MotdOrchid,
        tertiary = MotdCoral,
    )

internal val MotdLightScheme: ColorScheme by lazy { paletteColorScheme(MotdLightRaw, dark = false, builtIn = true) }
internal val MotdDarkScheme: ColorScheme by lazy { paletteColorScheme(MotdDarkRaw, dark = true, builtIn = true) }
private val MotdLightAuthoredScheme: ColorScheme by lazy { paletteColorScheme(MotdLightRaw, dark = false, useThemeTextColors = true, builtIn = true) }
private val MotdDarkAuthoredScheme: ColorScheme by lazy { paletteColorScheme(MotdDarkRaw, dark = true, useThemeTextColors = true, builtIn = true) }
internal val MotdLightThemeTextScheme: ColorScheme by lazy { paletteColorScheme(MotdLightRaw, dark = false, useThemeTextColors = true, adjustPaletteReadability = true) }
internal val MotdDarkThemeTextScheme: ColorScheme by lazy { paletteColorScheme(MotdDarkRaw, dark = true, useThemeTextColors = true, adjustPaletteReadability = true) }
private val MotdLightNeutralScheme: ColorScheme by lazy { paletteColorScheme(MotdLightRaw, dark = false, adjustPaletteReadability = true) }
private val MotdDarkNeutralScheme: ColorScheme by lazy { paletteColorScheme(MotdDarkRaw, dark = true, adjustPaletteReadability = true) }

internal fun motdThemeScheme(
    dark: Boolean,
    useThemeTextColors: Boolean = false,
    adjustPaletteReadability: Boolean = false,
): ColorScheme =
    when {
        !adjustPaletteReadability && useThemeTextColors -> if (dark) MotdDarkAuthoredScheme else MotdLightAuthoredScheme
        !adjustPaletteReadability -> if (dark) MotdDarkScheme else MotdLightScheme
        useThemeTextColors -> if (dark) MotdDarkThemeTextScheme else MotdLightThemeTextScheme
        else -> if (dark) MotdDarkNeutralScheme else MotdLightNeutralScheme
    }

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

private fun renderedMinimumContrast(
    foreground: Color,
    backgrounds: List<Color>,
): Double = backgrounds.minOf { contrastRatio(foreground.compositeOver(it), it) }

private fun selectReadableInk(
    preferred: Color,
    backgrounds: List<Color>,
    minimum: Double,
    fallback: Color,
    neutral: Color,
): Color {
    if (renderedMinimumContrast(preferred, backgrounds) >= minimum) return preferred
    if (renderedMinimumContrast(fallback, backgrounds) >= minimum) return fallback
    if (renderedMinimumContrast(neutral, backgrounds) >= minimum) return neutral
    if (renderedMinimumContrast(Color.Black, backgrounds) >= minimum) return Color.Black
    if (renderedMinimumContrast(Color.White, backgrounds) >= minimum) return Color.White
    return if (renderedMinimumContrast(Color.Black, backgrounds) >= renderedMinimumContrast(Color.White, backgrounds)) Color.Black else Color.White
}

private fun ColorScheme.neutralReadingSurfaces(): List<Color> =
    listOf(
        background,
        surface,
        surfaceContainerLowest,
        surfaceContainerLow,
        surfaceContainer,
        surfaceContainerHigh,
        surfaceContainerHighest,
        surfaceBright,
        surfaceDim,
        surfaceVariant,
    )

private fun ColorScheme.readingSurfaces(fill: Color): List<Color> =
    when {
        fill == Color.Unspecified -> neutralReadingSurfaces()
        fill.alpha < 1f -> neutralReadingSurfaces().map { fill.compositeOver(it) }
        else -> listOf(fill)
    }

fun ColorScheme.readableForeground(
    preferred: Color,
    background: Color = Color.Unspecified,
    minimumContrast: Double = TEXT_CONTRAST,
    fallback: Color = onSurface,
): Color {
    if (preferred == Color.Transparent) return preferred
    val backgrounds = readingSurfaces(background)
    val paired =
        when (background) {
            primary -> onPrimary
            secondary -> onSecondary
            tertiary -> onTertiary
            primaryContainer -> onPrimaryContainer
            secondaryContainer -> onSecondaryContainer
            tertiaryContainer -> onTertiaryContainer
            error -> onError
            errorContainer -> onErrorContainer
            inverseSurface -> inverseOnSurface
            else -> fallback
        }
    return selectReadableInk(preferred, backgrounds, minimumContrast, paired, fallback)
}

internal fun ColorScheme.readableSelectionColors(
    background: Color = Color.Unspecified,
    ink: Color = onSurface,
    preferred: TextSelectionColors? = null,
    errorBackground: Color = background,
    errorInk: Color = ink,
): TextSelectionColors {
    val surfaces = readingSurfaces(background)
    val errorSurfaces = readingSurfaces(errorBackground)
    val handle = readableForeground(preferred?.handleColor ?: primary, background, NON_TEXT_CONTRAST)
    val backing = preferred?.backgroundColor ?: primary.copy(alpha = 0.4f)
    val inks = if (background == Color.Unspecified) listOf(ink, onBackground, onSurfaceVariant) else listOf(ink)

    fun fit(candidate: Color): Color {
        var alpha = candidate.alpha
        for (surface in surfaces) {
            alpha = min(alpha, contrastSafeOverlay(surface, candidate, alpha, inks).alpha)
        }
        for (surface in errorSurfaces) {
            alpha = min(alpha, contrastSafeOverlay(surface, candidate, alpha, listOf(errorInk)).alpha)
        }
        return candidate.copy(alpha = alpha)
    }

    fun visibility(candidate: Color): Double =
        min(
            surfaces.minOf { contrastRatio(candidate.compositeOver(it), it) },
            errorSurfaces.minOf { contrastRatio(candidate.compositeOver(it), it) },
        )
    var selection = fit(backing)
    val explicitTransparent = preferred != null && backing.alpha == 0f
    if (!explicitTransparent && (selection.alpha < 0.08f || visibility(selection) < 1.08)) {
        var bestVisibility = visibility(selection)
        for (role in listOf(primaryContainer, secondaryContainer, tertiaryContainer, errorContainer, surfaceContainerLowest, surfaceContainerHighest, inverseSurface)) {
            val candidate = fit(role.copy(alpha = 0.4f))
            val candidateVisibility = visibility(candidate)
            if (candidateVisibility > bestVisibility) {
                selection = candidate
                bestVisibility = candidateVisibility
            }
        }
    }
    return if (preferred != null && handle == preferred.handleColor && selection == preferred.backgroundColor) {
        preferred
    } else {
        TextSelectionColors(handle, selection)
    }
}

@Composable
fun readableTextButtonColors(
    background: Color = Color.Unspecified,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
): ButtonColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme, background, colors) { scheme.readableButtonColors(background, colors) }
}

internal fun ColorScheme.readableButtonColors(
    background: Color,
    colors: ButtonColors,
): ButtonColors {
    val fill = colors.containerColor
    val surfaces = readingSurfaces(background)
    val backgrounds =
        if (fill == Color.Unspecified || fill.alpha == 0f) {
            surfaces
        } else if (fill.alpha < 1f) {
            surfaces.map { fill.compositeOver(it) }
        } else {
            listOf(fill)
        }
    val ink =
        if (colors.contentColor == Color.Transparent) {
            colors.contentColor
        } else {
            selectReadableInk(colors.contentColor, backgrounds, TEXT_CONTRAST, onSurface, staticTextInk(this))
        }
    return if (ink == colors.contentColor) colors else colors.copy(contentColor = ink)
}

@Composable
fun readableOutlinedTextFieldColors(
    background: Color = Color.Unspecified,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
): TextFieldColors = readableFieldColors(background, colors)

@Composable
fun readableFilledTextFieldColors(
    background: Color = Color.Unspecified,
    colors: TextFieldColors = TextFieldDefaults.colors(),
): TextFieldColors = readableFieldColors(background, colors)

@Composable
private fun readableFieldColors(
    background: Color,
    colors: TextFieldColors,
): TextFieldColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme, background, colors) {
        fun fill(container: Color): Color =
            if (container == Color.Unspecified || container.alpha == 0f) {
                background
            } else if (container.alpha < 1f && background != Color.Unspecified) {
                container.compositeOver(background)
            } else {
                container
            }
        val focused = fill(colors.focusedContainerColor)
        val unfocused = fill(colors.unfocusedContainerColor)
        val error = fill(colors.errorContainerColor)

        fun text(
            ink: Color,
            fill: Color,
        ) = scheme.readableForeground(ink, fill)

        fun boundary(
            ink: Color,
            fill: Color,
        ) = scheme.readableForeground(ink, fill, NON_TEXT_CONTRAST)

        fun errorText(ink: Color) = scheme.readableForeground(ink, error, fallback = scheme.onErrorContainer)
        val focusedInk = text(colors.focusedTextColor, focused)
        var changed = false

        fun corrected(
            original: Color,
            value: Color,
        ): Color {
            if (value != original) changed = true
            return value
        }
        val focusedTextColor = corrected(colors.focusedTextColor, focusedInk)
        val unfocusedTextColor = corrected(colors.unfocusedTextColor, text(colors.unfocusedTextColor, unfocused))
        val errorTextColor = corrected(colors.errorTextColor, errorText(colors.errorTextColor))
        val cursorColor = corrected(colors.cursorColor, boundary(colors.cursorColor, focused))
        val errorCursorColor = corrected(colors.errorCursorColor, boundary(colors.errorCursorColor, error))
        val textSelectionColors = scheme.readableSelectionColors(focused, focusedInk, colors.textSelectionColors, error, errorTextColor)
        if (textSelectionColors != colors.textSelectionColors) changed = true
        val focusedIndicatorColor = corrected(colors.focusedIndicatorColor, boundary(colors.focusedIndicatorColor, focused))
        val unfocusedIndicatorColor = corrected(colors.unfocusedIndicatorColor, boundary(colors.unfocusedIndicatorColor, unfocused))
        val errorIndicatorColor = corrected(colors.errorIndicatorColor, boundary(colors.errorIndicatorColor, error))
        val focusedLeadingIconColor = corrected(colors.focusedLeadingIconColor, boundary(colors.focusedLeadingIconColor, focused))
        val unfocusedLeadingIconColor = corrected(colors.unfocusedLeadingIconColor, boundary(colors.unfocusedLeadingIconColor, unfocused))
        val errorLeadingIconColor = corrected(colors.errorLeadingIconColor, boundary(colors.errorLeadingIconColor, error))
        val focusedTrailingIconColor = corrected(colors.focusedTrailingIconColor, boundary(colors.focusedTrailingIconColor, focused))
        val unfocusedTrailingIconColor = corrected(colors.unfocusedTrailingIconColor, boundary(colors.unfocusedTrailingIconColor, unfocused))
        val errorTrailingIconColor = corrected(colors.errorTrailingIconColor, boundary(colors.errorTrailingIconColor, error))
        val focusedLabelColor = corrected(colors.focusedLabelColor, text(colors.focusedLabelColor, focused))
        val unfocusedLabelColor = corrected(colors.unfocusedLabelColor, text(colors.unfocusedLabelColor, unfocused))
        val errorLabelColor = corrected(colors.errorLabelColor, errorText(colors.errorLabelColor))
        val focusedPlaceholderColor = corrected(colors.focusedPlaceholderColor, text(colors.focusedPlaceholderColor, focused))
        val unfocusedPlaceholderColor = corrected(colors.unfocusedPlaceholderColor, text(colors.unfocusedPlaceholderColor, unfocused))
        val errorPlaceholderColor = corrected(colors.errorPlaceholderColor, errorText(colors.errorPlaceholderColor))
        val focusedSupportingTextColor = corrected(colors.focusedSupportingTextColor, text(colors.focusedSupportingTextColor, background))
        val unfocusedSupportingTextColor = corrected(colors.unfocusedSupportingTextColor, text(colors.unfocusedSupportingTextColor, background))
        val errorSupportingTextColor = corrected(colors.errorSupportingTextColor, scheme.readableForeground(colors.errorSupportingTextColor, background, fallback = scheme.onErrorContainer))
        val focusedPrefixColor = corrected(colors.focusedPrefixColor, text(colors.focusedPrefixColor, focused))
        val unfocusedPrefixColor = corrected(colors.unfocusedPrefixColor, text(colors.unfocusedPrefixColor, unfocused))
        val errorPrefixColor = corrected(colors.errorPrefixColor, errorText(colors.errorPrefixColor))
        val focusedSuffixColor = corrected(colors.focusedSuffixColor, text(colors.focusedSuffixColor, focused))
        val unfocusedSuffixColor = corrected(colors.unfocusedSuffixColor, text(colors.unfocusedSuffixColor, unfocused))
        val errorSuffixColor = corrected(colors.errorSuffixColor, errorText(colors.errorSuffixColor))
        if (!changed) {
            colors
        } else {
            colors.copy(
                focusedTextColor = focusedTextColor,
                unfocusedTextColor = unfocusedTextColor,
                errorTextColor = errorTextColor,
                cursorColor = cursorColor,
                errorCursorColor = errorCursorColor,
                textSelectionColors = textSelectionColors,
                focusedIndicatorColor = focusedIndicatorColor,
                unfocusedIndicatorColor = unfocusedIndicatorColor,
                errorIndicatorColor = errorIndicatorColor,
                focusedLeadingIconColor = focusedLeadingIconColor,
                unfocusedLeadingIconColor = unfocusedLeadingIconColor,
                errorLeadingIconColor = errorLeadingIconColor,
                focusedTrailingIconColor = focusedTrailingIconColor,
                unfocusedTrailingIconColor = unfocusedTrailingIconColor,
                errorTrailingIconColor = errorTrailingIconColor,
                focusedLabelColor = focusedLabelColor,
                unfocusedLabelColor = unfocusedLabelColor,
                errorLabelColor = errorLabelColor,
                focusedPlaceholderColor = focusedPlaceholderColor,
                unfocusedPlaceholderColor = unfocusedPlaceholderColor,
                errorPlaceholderColor = errorPlaceholderColor,
                focusedSupportingTextColor = focusedSupportingTextColor,
                unfocusedSupportingTextColor = unfocusedSupportingTextColor,
                errorSupportingTextColor = errorSupportingTextColor,
                focusedPrefixColor = focusedPrefixColor,
                unfocusedPrefixColor = unfocusedPrefixColor,
                errorPrefixColor = errorPrefixColor,
                focusedSuffixColor = focusedSuffixColor,
                unfocusedSuffixColor = unfocusedSuffixColor,
                errorSuffixColor = errorSuffixColor,
            )
        }
    }
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

internal fun paletteColorScheme(
    raw: ColorScheme,
    dark: Boolean,
    useThemeTextColors: Boolean = false,
    adjustPaletteReadability: Boolean = false,
    builtIn: Boolean = false,
): ColorScheme {
    val surfaceContainerLow =
        ensureContainerContrast(
            raw.surfaceContainerLow,
            raw.background,
            dark,
            1.08,
        )
    val surfaceContainer =
        if (adjustPaletteReadability) {
            ensureContainerContrast(raw.surfaceContainer, raw.background, dark, 1.16)
        } else {
            raw.surfaceContainer
        }
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
    val fillInk = if (adjustPaletteReadability) ensureContrast(staticTextInk(raw), neutralSurfaces) else raw.onSurface

    fun readingRole(authored: Color): Color {
        val preferred = if (useThemeTextColors) authored else staticTextInk(raw)
        if (adjustPaletteReadability) return ensureContrast(preferred, neutralSurfaces)
        if (useThemeTextColors && !builtIn) return preferred
        return selectReadableInk(preferred, neutralSurfaces, TEXT_CONTRAST, raw.onSurface, staticTextInk(raw))
    }
    val primary = if (adjustPaletteReadability) ensureContrast(raw.primary, neutralSurfaces) else raw.primary
    val secondary = if (adjustPaletteReadability) ensureContrast(raw.secondary, neutralSurfaces) else raw.secondary
    val tertiary = if (adjustPaletteReadability) ensureContrast(raw.tertiary, neutralSurfaces) else raw.tertiary

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
    val outline =
        if (adjustPaletteReadability) {
            ensureContrast(raw.outline, neutralSurfaces, NON_TEXT_CONTRAST)
        } else if (builtIn) {
            selectReadableInk(raw.outline, neutralSurfaces, NON_TEXT_CONTRAST, raw.onSurface, staticTextInk(raw))
        } else {
            raw.outline
        }
    val outlineVariant = if (adjustPaletteReadability) ensureContrast(raw.outlineVariant, neutralSurfaces, NON_TEXT_CONTRAST) else raw.outlineVariant
    val inverseSurface = fillInk
    val inverseOnSurface = ensureContrast(raw.surface, listOf(inverseSurface))
    val inversePrimary = ensureContrast(raw.primary, listOf(inverseSurface))

    return raw.copy(
        primary = primary,
        onPrimary = bestOnColor(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = ensureContrast(fillInk, listOf(primaryContainer)),
        secondary = secondary,
        onSecondary = bestOnColor(secondary),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = ensureContrast(fillInk, listOf(secondaryContainer)),
        tertiary = tertiary,
        onTertiary = bestOnColor(tertiary),
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = ensureContrast(fillInk, listOf(tertiaryContainer)),
        background = raw.background,
        onBackground = readingRole(raw.onBackground),
        surface = raw.surface,
        onSurface = readingRole(raw.onSurface),
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = readingRole(raw.onSurfaceVariant),
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        outline = outline,
        outlineVariant = outlineVariant,
        error = error,
        onError = bestOnColor(error),
        errorContainer = errorContainer,
        onErrorContainer = ensureContrast(fillInk, listOf(errorContainer)),
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
        ColorThemePreset.DRACULA_LIGHT to DraculaLightScheme,
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
        ColorThemePreset.TOKYO_NIGHT_LIGHT to TokyoNightLightScheme,
        ColorThemePreset.ZENBURN to ZenburnScheme,
    )
}

/** Unfitted source roles for cloning; meta presets have no fixed raw palette. */
internal fun rawFixedThemeScheme(preset: ColorThemePreset): ColorScheme? = RawFixedSchemes[preset]

// Normalize only the requested palette/text mode; eager catalog normalization delays first paint.
private val FixedSchemes = java.util.concurrent.ConcurrentHashMap<ColorThemePreset, ColorScheme>()
private val ThemeTextFixedSchemes = java.util.concurrent.ConcurrentHashMap<ColorThemePreset, ColorScheme>()
private val AuthoredFixedSchemes = java.util.concurrent.ConcurrentHashMap<ColorThemePreset, ColorScheme>()
private val NeutralAuthoredFixedSchemes = java.util.concurrent.ConcurrentHashMap<ColorThemePreset, ColorScheme>()

internal fun fixedThemeScheme(
    theme: ColorThemePreset,
    useThemeTextColors: Boolean = false,
    adjustPaletteReadability: Boolean = false,
): ColorScheme? {
    when (theme) {
        ColorThemePreset.SYSTEM, ColorThemePreset.LIGHT, ColorThemePreset.DARK, ColorThemePreset.AMOLED -> return null
        else -> Unit
    }
    val cache =
        if (!adjustPaletteReadability) {
            if (useThemeTextColors) AuthoredFixedSchemes else NeutralAuthoredFixedSchemes
        } else if (useThemeTextColors) {
            ThemeTextFixedSchemes
        } else {
            FixedSchemes
        }
    return cache[theme] ?: cache.computeIfAbsent(theme) { preset ->
        paletteColorScheme(RawFixedSchemes.getValue(preset), preset.isDark, useThemeTextColors, adjustPaletteReadability, builtIn = true)
    }
}

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

    fun readable(alpha: Float): Boolean {
        val fill = overlay.copy(alpha = alpha).compositeOver(base)
        return foregrounds.all { contrastRatio(it.compositeOver(fill), fill) >= minimum }
    }
    if (readable(requested)) {
        return overlay.copy(alpha = requested)
    }
    var low = 0f
    var high = requested
    repeat(24) {
        val mid = (low + high) / 2f
        if (readable(mid)) low = mid else high = mid
    }
    return overlay.copy(alpha = low)
}
