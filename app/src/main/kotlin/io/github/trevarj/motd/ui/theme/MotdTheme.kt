package io.github.trevarj.motd.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.DEFAULT_FONT_SCALE_PERCENT
import io.github.trevarj.motd.data.prefs.isDark
import io.github.trevarj.motd.data.prefs.normalizeFontScalePercent

private val BaseTypography = Typography()

internal fun typographyScaleFactor(percent: Int): Float =
    normalizeFontScalePercent(percent) / 100f

internal fun conversationTypographyScaleFactor(percent: Int): Float =
    normalizeFontScalePercent(percent) / 100f

private fun TextUnit.scaledBy(factor: Float): TextUnit =
    if (this != TextUnit.Unspecified) this * factor else this

// Keep Material's per-role tracking intact; it is tuned for each type role rather than being a
// proportional dimension of the glyph size.
internal fun TextStyle.scaledBy(factor: Float): TextStyle = copy(
    fontSize = fontSize.scaledBy(factor),
    lineHeight = lineHeight.scaledBy(factor),
)

private fun scaledTypographyBy(base: Typography, factor: Float): Typography =
    base.copy(
        displayLarge = base.displayLarge.scaledBy(factor),
        displayMedium = base.displayMedium.scaledBy(factor),
        displaySmall = base.displaySmall.scaledBy(factor),
        headlineLarge = base.headlineLarge.scaledBy(factor),
        headlineMedium = base.headlineMedium.scaledBy(factor),
        headlineSmall = base.headlineSmall.scaledBy(factor),
        titleLarge = base.titleLarge.scaledBy(factor),
        titleMedium = base.titleMedium.scaledBy(factor),
        titleSmall = base.titleSmall.scaledBy(factor),
        bodyLarge = base.bodyLarge.scaledBy(factor),
        bodyMedium = base.bodyMedium.scaledBy(factor),
        bodySmall = base.bodySmall.scaledBy(factor),
        labelLarge = base.labelLarge.scaledBy(factor),
        labelMedium = base.labelMedium.scaledBy(factor),
        labelSmall = base.labelSmall.scaledBy(factor),
    )

internal fun scaledTypography(percent: Int, base: Typography = BaseTypography): Typography =
    scaledTypographyBy(base, typographyScaleFactor(percent))

internal fun scaledConversationTypography(percent: Int, base: Typography = BaseTypography): Typography =
    scaledTypographyBy(base, conversationTypographyScaleFactor(percent))

/** Override text tokens only; colors, shapes, density, icons, and geometry remain unchanged. */
@Composable
fun ConversationTypography(
    scalePercent: Int,
    content: @Composable () -> Unit,
) {
    val typography = remember(scalePercent) { scaledConversationTypography(scalePercent) }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        shapes = MaterialTheme.shapes,
        typography = typography,
        content = content,
    )
}

/**
 * CompositionLocal carrying the active avatar rendering style. Defaults to IRC_SPRITE so previews
 * and un-provided contexts use the default without needing explicit provision.
 */
val LocalAvatarStyle: ProvidableCompositionLocal<AvatarStyle> =
    staticCompositionLocalOf { AvatarStyle.IRC_SPRITE }

@Composable
fun MotdTheme(
    themePreset: ColorThemePreset = ColorThemePreset.SYSTEM,
    trueBlack: Boolean = false,
    dynamicColor: Boolean = true,
    // Round 4 (plans/13); all defaulted so existing call sites (incl. previews) stay unchanged.
    layoutDensity: LayoutDensity = LayoutDensity.COMFORTABLE,
    nickColorsEnabled: Boolean = true,
    nickColorPalette: NickColorPalette = NickColorPalette.DEFAULT,
    nickColorOverrides: Map<String, Int> = emptyMap(),
    avatarStyle: AvatarStyle = AvatarStyle.IRC_SPRITE,
    uiFontScalePercent: Int = DEFAULT_FONT_SCALE_PERCENT,
    content: @Composable () -> Unit,
) {
    // Read composable environment values unconditionally. Changing between dynamic/system and a
    // fixed palette must not change this function's slot structure and dispose stateful content.
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val dark = if (themePreset == ColorThemePreset.SYSTEM) systemDark else themePreset.isDark
    val effectivePreset = if (themePreset == ColorThemePreset.AMOLED) ColorThemePreset.DARK else themePreset
    val resolvedScheme = fixedThemeScheme(effectivePreset) ?: when {
        // Material You dynamic color, API 31+.
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            accessibleColorScheme(
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
                dark,
            )
        }
        dark -> MotdDarkScheme
        else -> MotdLightScheme
    }
    val useTrueBlack = dark && (trueBlack || themePreset == ColorThemePreset.AMOLED)
    val colorScheme = if (useTrueBlack) resolvedScheme.withTrueBlackSurfaces() else resolvedScheme
    val nickTextBackgrounds = remember(colorScheme) {
        listOf(
            colorScheme.background,
            colorScheme.surface,
            colorScheme.surfaceContainerLow,
            colorScheme.surfaceContainerHigh,
            colorScheme.surfaceContainerHighest,
            colorScheme.primaryContainer,
            colorScheme.secondaryContainer,
            colorScheme.tertiaryContainer,
        ).distinct()
    }
    // Style-only concerns (spacing, nick colors, avatar style) flow through CompositionLocals so
    // components never receive them as parameters (plans/13 plumbing split).
    val typography = remember(uiFontScalePercent) { scaledTypography(uiFontScalePercent) }
    MaterialTheme(colorScheme = colorScheme, typography = typography) {
        CompositionLocalProvider(
            LocalSpacing provides spacingFor(layoutDensity),
            LocalNickColors provides NickColorScheme(
                nickColorsEnabled,
                nickColorPalette,
                nickColorOverrides,
                dark,
                nickTextBackgrounds,
            ),
            LocalAvatarStyle provides avatarStyle,
            LocalMotdSemanticColors provides semanticColors(colorScheme, dark),
            content = content,
        )
    }
}
