package io.github.trevarj.motd.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.github.trevarj.motd.data.prefs.AvatarShape
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.BubbleCornerStyle
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.ComfortableBubbleColor
import io.github.trevarj.motd.data.prefs.ComfortableBubbleColorRole
import io.github.trevarj.motd.data.prefs.DEFAULT_CUSTOM_TIME_FORMAT
import io.github.trevarj.motd.data.prefs.DEFAULT_FONT_SCALE_PERCENT
import io.github.trevarj.motd.data.prefs.FontChoice
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.MessageSpacing
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.TimeFormat
import io.github.trevarj.motd.data.prefs.UiCornerStyle
import io.github.trevarj.motd.data.prefs.UiIconSet
import io.github.trevarj.motd.data.prefs.isDark
import io.github.trevarj.motd.data.prefs.normalizeFontScalePercent
import io.github.trevarj.motd.data.prefs.resolveAutoPalette
import java.io.File

private val BaseTypography = Typography()

internal fun typographyScaleFactor(percent: Int): Float = normalizeFontScalePercent(percent) / 100f

internal fun conversationTypographyScaleFactor(percent: Int): Float = normalizeFontScalePercent(percent) / 100f

private fun TextUnit.scaledBy(factor: Float): TextUnit = if (this != TextUnit.Unspecified) this * factor else this

// Keep Material's per-role tracking intact; it is tuned for each type role rather than being a
// proportional dimension of the glyph size.
internal fun TextStyle.scaledBy(factor: Float): TextStyle =
    copy(
        fontSize = fontSize.scaledBy(factor),
        lineHeight = lineHeight.scaledBy(factor),
    )

private fun scaledTypographyBy(
    base: Typography,
    factor: Float,
): Typography =
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

internal fun scaledTypography(
    percent: Int,
    base: Typography = BaseTypography,
): Typography = scaledTypographyBy(base, typographyScaleFactor(percent))

internal fun scaledConversationTypography(
    percent: Int,
    base: Typography = BaseTypography,
): Typography = scaledTypographyBy(base, conversationTypographyScaleFactor(percent))

/** Override text tokens only; colors, shapes, density, icons, and geometry remain unchanged. */
@Composable
fun ConversationTypography(
    scalePercent: Int,
    content: @Composable () -> Unit,
) {
    val family = LocalAppFontFamily.current
    val typography =
        remember(scalePercent, family) {
            scaledConversationTypography(scalePercent, BaseTypography.withFontFamily(family))
        }
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

val LocalInputCornerStyle: ProvidableCompositionLocal<UiCornerStyle> =
    staticCompositionLocalOf { UiCornerStyle.DEFAULT }

val LocalAvatarShape: ProvidableCompositionLocal<AvatarShape> =
    staticCompositionLocalOf { AvatarShape.DEFAULT }

val LocalChatCardCornerStyle: ProvidableCompositionLocal<UiCornerStyle> =
    staticCompositionLocalOf { UiCornerStyle.DEFAULT }

val LocalMediaCornerStyle: ProvidableCompositionLocal<UiCornerStyle> =
    staticCompositionLocalOf { UiCornerStyle.DEFAULT }

val LocalUiIconSet: ProvidableCompositionLocal<UiIconSet> =
    staticCompositionLocalOf { UiIconSet.MATERIAL }

/** CompositionLocal carrying the active app-wide font family; null means the platform default. */
val LocalAppFontFamily: ProvidableCompositionLocal<FontFamily?> = staticCompositionLocalOf { null }

/** Whether message timestamps are shown and, when shown, which 12h/24h format they resolve to. */
data class TimestampConfig(
    val show: Boolean = true,
    val format: TimeFormat = TimeFormat.AUTO,
    // SimpleDateFormat pattern, only consulted when format == CUSTOM.
    val customPattern: String = DEFAULT_CUSTOM_TIME_FORMAT,
)

/** CompositionLocal carrying the active timestamp display config; defaults to always-shown/AUTO. */
val LocalTimestampConfig: ProvidableCompositionLocal<TimestampConfig> =
    staticCompositionLocalOf { TimestampConfig() }

/** The Comfortable message renderer reads this without changing unrelated surfaces. */
val LocalChatShadowsEnabled: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/** Controls only the ordinary sender wash in Compact and Two-line message rows. */
val LocalNicknameBackgroundsEnabled: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { true }

/** Ordinary dense-row backing only, when nickname backgrounds are disabled; zero paints nothing. */
val LocalMessageBackgroundOpacityPercent: ProvidableCompositionLocal<Int> = staticCompositionLocalOf { 100 }

/** Resolved fills for ordinary Comfortable PRIVMSG bubbles; null preserves the theme default. */
data class ComfortableBubbleColors(
    val sent: Color? = null,
    val received: Color? = null,
)

val LocalComfortableBubbleColors: ProvidableCompositionLocal<ComfortableBubbleColors> =
    staticCompositionLocalOf { ComfortableBubbleColors() }

internal fun resolveComfortableBubbleColor(
    selection: ComfortableBubbleColor,
    scheme: androidx.compose.material3.ColorScheme,
): Color? =
    when (selection.role) {
        ComfortableBubbleColorRole.DEFAULT -> null
        ComfortableBubbleColorRole.SURFACE_CONTAINER -> scheme.surfaceContainer
        ComfortableBubbleColorRole.SURFACE_CONTAINER_HIGH -> scheme.surfaceContainerHigh
        ComfortableBubbleColorRole.PRIMARY -> scheme.primary
        ComfortableBubbleColorRole.SECONDARY -> scheme.secondary
        ComfortableBubbleColorRole.TERTIARY -> scheme.tertiary
        ComfortableBubbleColorRole.PRIMARY_CONTAINER -> scheme.primaryContainer
        ComfortableBubbleColorRole.SECONDARY_CONTAINER -> scheme.secondaryContainer
        ComfortableBubbleColorRole.TERTIARY_CONTAINER -> scheme.tertiaryContainer
        ComfortableBubbleColorRole.CUSTOM -> selection.customColor?.let(::Color)
    }

/** Resolved application palette mode for content that can opt out of OS-mode following. */
val LocalMotdDark: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/**
 * Opt-in marker for activities whose system bars [MotdTheme] may restyle.
 *
 * Restyling calls `enableEdgeToEdge`, which forces a window relayout. Only the real app shell wants
 * that; in instrumentation hosts the relayout lands asynchronously to Compose idleness and can move
 * content mid-gesture, which made precision multi-touch tests flake on slow CI devices.
 */
interface SystemBarThemeHost

// Previews and Compose tests host content in a non-activity context; system-bar styling is
// activity-only, so resolve to null there instead of requiring a ComponentActivity.
private tailrec fun Context.findComponentActivity(): ComponentActivity? =
    when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findComponentActivity()
        else -> null
    }

/**
 * Sync a ModalBottomSheet's dialog window with the app theme. M3 sheets live in their own window,
 * which styles its system bars from the window theme rather than the activity's edge-to-edge
 * setup, so a dark app palette otherwise gets a light nav-bar scrim under every sheet (and vice
 * versa). Call from inside the sheet's content so LocalView resolves to the sheet's own window.
 */
@Composable
fun SheetSystemBars() {
    val view = LocalView.current
    val darkBars = MaterialTheme.colorScheme.background.luminance() < 0.5f
    LaunchedEffect(view, darkBars) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        @Suppress("DEPRECATION") // transparent bar color for pre-contrast-enforcement APIs
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !darkBars
        controller.isAppearanceLightNavigationBars = !darkBars
    }
}

@Composable
fun MotdTheme(
    themePreset: ColorThemePreset = ColorThemePreset.SYSTEM,
    trueBlack: Boolean = false,
    dynamicColor: Boolean = true,
    followSystem: Boolean = false,
    useThemeTextColors: Boolean = false,
    // Round 4; all defaulted so existing call sites (incl. previews) stay unchanged.
    layoutDensity: LayoutDensity = LayoutDensity.COMFORTABLE,
    nickColorsEnabled: Boolean = true,
    nickColorPalette: NickColorPalette = NickColorPalette.THEME,
    nickColorOverrides: Map<String, Int> = emptyMap(),
    avatarStyle: AvatarStyle = AvatarStyle.IRC_SPRITE,
    uiFontScalePercent: Int = DEFAULT_FONT_SCALE_PERCENT,
    fontChoice: FontChoice = FontChoice.SYSTEM,
    // Only meaningful when fontChoice is CUSTOM; see rememberAppFontFamily. Optional so every
    // existing call site (incl. previews) is unaffected.
    customFontFile: File? = null,
    timestampConfig: TimestampConfig = TimestampConfig(),
    messageSpacing: MessageSpacing = MessageSpacing.DEFAULT,
    bubbleCornerStyle: BubbleCornerStyle = BubbleCornerStyle.ROUNDED,
    inputCornerStyle: UiCornerStyle = UiCornerStyle.DEFAULT,
    avatarShape: AvatarShape = AvatarShape.DEFAULT,
    chatCardCornerStyle: UiCornerStyle = UiCornerStyle.DEFAULT,
    mediaCornerStyle: UiCornerStyle = UiCornerStyle.DEFAULT,
    uiIconSet: UiIconSet = LocalUiIconSet.current,
    chatShadowsEnabled: Boolean = false,
    nicknameBackgroundsEnabled: Boolean = true,
    messageBackgroundOpacityPercent: Int = 100,
    sentBubbleColor: ComfortableBubbleColor = ComfortableBubbleColor(),
    receivedBubbleColor: ComfortableBubbleColor = ComfortableBubbleColor(),
    // Nested previews can render a different palette from the app shell without restyling its bars.
    syncSystemBars: Boolean = true,
    content: @Composable () -> Unit,
) {
    // Read composable environment values unconditionally. Changing between dynamic/system and a
    // fixed palette must not change this function's slot structure and dispose stateful content.
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val resolvedPreset = resolveAutoPalette(themePreset, followSystem, systemDark)
    val dark = if (resolvedPreset == ColorThemePreset.SYSTEM) systemDark else resolvedPreset.isDark
    val effectivePreset = if (resolvedPreset == ColorThemePreset.AMOLED) ColorThemePreset.DARK else resolvedPreset
    val resolvedScheme =
        fixedThemeScheme(effectivePreset, useThemeTextColors) ?: when {
            // Material You dynamic color, API 31+.
            resolvedPreset == ColorThemePreset.SYSTEM &&
                dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                accessibleColorScheme(
                    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
                    dark,
                    useThemeTextColors,
                )
            }

            dark -> {
                if (useThemeTextColors) MotdDarkThemeTextScheme else MotdDarkScheme
            }

            else -> {
                if (useThemeTextColors) MotdLightThemeTextScheme else MotdLightScheme
            }
        }
    val useTrueBlack = dark && (trueBlack || themePreset == ColorThemePreset.AMOLED)
    val colorScheme = if (useTrueBlack) resolvedScheme.withTrueBlackSurfaces() else resolvedScheme
    val comfortableBubbleColors =
        remember(colorScheme, sentBubbleColor, receivedBubbleColor) {
            ComfortableBubbleColors(
                sent = resolveComfortableBubbleColor(sentBubbleColor, colorScheme),
                received = resolveComfortableBubbleColor(receivedBubbleColor, colorScheme),
            )
        }
    // System bars must follow the resolved palette, not the OS night mode: the bare
    // enableEdgeToEdge() in onCreate keys off uiMode, which disagrees with any fixed palette
    // opposing it (light nav scrim and invisible status icons over a dark theme, and vice versa).
    // Re-applying here also keeps the bars in sync when the theme changes at runtime.
    // Only hosts that opted in via [SystemBarThemeHost] get their window restyled: test hosts must
    // never see the enableEdgeToEdge relayout race mid-gesture.
    val activity = remember(context, syncSystemBars) { context.findComponentActivity()?.takeIf { it is SystemBarThemeHost && syncSystemBars } }
    LaunchedEffect(activity, dark) {
        if (activity == null) return@LaunchedEffect
        val barStyle =
            if (dark) {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            }
        activity.enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Let the root Surface background show through instead of the automatic nav scrim.
            activity.window.isNavigationBarContrastEnforced = false
        }
    }
    val nickTextBackgrounds =
        remember(colorScheme) {
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
    val nickThemeColors =
        remember(colorScheme) {
            listOf(colorScheme.primary, colorScheme.tertiary, colorScheme.secondary).distinct()
        }
    // Terminal and editor themes publish a real accent palette; identity colors are picked from it
    // rather than synthesized, so a nick is always one of the theme's own colors.
    val nickIdentityPalette = remember(effectivePreset) { themeIdentityPalette(effectivePreset) }
    // Style-only concerns (spacing, nick colors, avatar style) flow through CompositionLocals so
    // components never receive them as parameters.
    val appFontFamily = rememberAppFontFamily(fontChoice, customFontFile)
    val typography =
        remember(uiFontScalePercent, fontChoice, appFontFamily) {
            scaledTypography(uiFontScalePercent, BaseTypography.withFontFamily(appFontFamily))
        }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = MotdMaterialShapes,
        typography = typography,
    ) {
        CompositionLocalProvider(
            LocalUseThemeTextColors provides useThemeTextColors,
            LocalSpacing provides spacingFor(layoutDensity, messageSpacing, bubbleCornerStyle),
            LocalInputCornerStyle provides inputCornerStyle,
            LocalAvatarShape provides avatarShape,
            LocalChatCardCornerStyle provides chatCardCornerStyle,
            LocalMediaCornerStyle provides mediaCornerStyle,
            LocalUiIconSet provides uiIconSet,
            LocalChatShadowsEnabled provides chatShadowsEnabled,
            LocalNicknameBackgroundsEnabled provides nicknameBackgroundsEnabled,
            LocalMessageBackgroundOpacityPercent provides messageBackgroundOpacityPercent.coerceIn(0, 100),
            LocalComfortableBubbleColors provides comfortableBubbleColors,
            LocalMotdDark provides dark,
            LocalNickColors provides
                NickColorScheme(
                    nickColorsEnabled,
                    nickColorPalette,
                    nickColorOverrides,
                    dark,
                    nickTextBackgrounds,
                    nickThemeColors,
                    nickIdentityPalette,
                ),
            LocalAvatarStyle provides avatarStyle,
            LocalAppFontFamily provides appFontFamily,
            LocalTimestampConfig provides timestampConfig,
            LocalMotdSemanticColors provides semanticColors(colorScheme, dark),
            // Resolved once here: the timeline mounts a delivery tick per own row.
            LocalLottieMotionEnabled provides resolveLottieMotionEnabled(),
            content = content,
        )
    }
}
