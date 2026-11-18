package io.github.trevarj.motd.ui.theme

import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.ComfortableBubbleColor
import io.github.trevarj.motd.data.prefs.ComfortableBubbleColorRole
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.PaletteColors
import io.github.trevarj.motd.data.prefs.PaletteDocument
import io.github.trevarj.motd.data.prefs.PaletteMode
import io.github.trevarj.motd.data.prefs.decodePalette
import io.github.trevarj.motd.data.prefs.encodePalette
import io.github.trevarj.motd.data.prefs.isDark
import io.github.trevarj.motd.data.prefs.isFixedPalette
import io.github.trevarj.motd.data.prefs.resolvePalettePreset
import io.github.trevarj.motd.data.prefs.systemPartner
import io.github.trevarj.motd.ui.components.messageContrastSchemes
import io.github.trevarj.motd.ui.components.onColorFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeCatalogTest {
    @Test fun allBuiltIns_keepReadingRolesSafeInAllFourModesWithoutChangingAuthoredSources() {
        val sources =
            ColorThemePreset.entries.filter { it.isFixedPalette }.map { it to requireNotNull(rawFixedThemeScheme(it)) } +
                listOf(ColorThemePreset.LIGHT to MotdLightRaw, ColorThemePreset.DARK to MotdDarkRaw)
        for ((preset, raw) in sources) {
            val original = authoredRoles(raw)
            for (adjust in listOf(false, true)) {
                fun resolve(themed: Boolean): ColorScheme = fixedThemeScheme(preset, themed, adjust) ?: motdThemeScheme(preset.isDark, themed, adjust)
                val neutral = resolve(false)
                val themed = resolve(true)
                assertEquals("$preset/$adjust surfaces", neutralSurfaces(neutral), neutralSurfaces(themed))
                assertEquals("$preset/$adjust fills", filledRoles(neutral), filledRoles(themed))
                for (scheme in listOf(neutral, themed)) {
                    for (fill in neutralSurfaces(scheme)) {
                        for (ink in listOf(scheme.onBackground, scheme.onSurface, scheme.onSurfaceVariant)) {
                            assertContrast(preset, "body $adjust", ink, fill, 4.5)
                        }
                        assertContrast(preset, "boundary $adjust", scheme.outline, fill, 3.0)
                        assertContrast(preset, "action $adjust", scheme.readableForeground(scheme.primary), fill, 4.5)
                    }
                    val selection = scheme.readableSelectionColors()
                    for (fill in neutralSurfaces(scheme)) {
                        assertContrast(preset, "selection handle", selection.handleColor, fill, 3.0)
                        assertContrast(preset, "selection ink", scheme.onSurface, selection.backgroundColor.compositeOver(fill), 4.5)
                    }
                }
                if (!adjust) {
                    assertEquals(raw.primary, neutral.primary)
                    assertEquals(raw.secondary, neutral.secondary)
                    assertEquals(raw.tertiary, neutral.tertiary)
                    assertEquals(raw.background, neutral.background)
                    assertEquals(raw.surface, neutral.surface)
                    assertEquals(raw.surfaceContainer, neutral.surfaceContainer)
                    val existingInks = listOf(raw.onBackground, raw.onSurface, raw.onSurfaceVariant, Color(0xFF171717), Color(0xFFF5F5F5), Color.Black, Color.White)
                    assertTrue("$preset selected existing body ink", themed.onSurface in existingInks)
                    assertTrue("$preset selected existing metadata ink", themed.onSurfaceVariant in existingInks)
                    val fills = neutralSurfaces(themed)
                    if (fills.all { contrastRatio(raw.onSurface, it) >= 4.5 }) {
                        assertEquals("$preset keeps legible authored body", raw.onSurface, themed.onSurface)
                    }
                    if (fills.all { contrastRatio(raw.onSurfaceVariant, it) >= 4.5 }) {
                        assertEquals("$preset keeps legible authored metadata", raw.onSurfaceVariant, themed.onSurfaceVariant)
                    }
                    val staticInk = if (raw.background.luminance() < 0.5f) Color(0xFFF5F5F5) else Color(0xFF171717)
                    if (fills.all { contrastRatio(staticInk, it) >= 4.5 }) {
                        assertEquals("$preset neutral preference", staticInk, neutral.onSurface)
                    }
                }
            }
            assertEquals("$preset source unchanged", original, authoredRoles(raw))
        }
    }

    @Test fun readableForeground_preservesLegibleAndTransparentOverridesAndPairsActualFills() {
        val scheme = requireNotNull(fixedThemeScheme(ColorThemePreset.GRUVBOX_LIGHT))
        assertEquals(Color.Transparent, scheme.readableForeground(Color.Transparent))
        assertEquals(scheme.onPrimary, scheme.readableForeground(scheme.primary, scheme.primary))
        assertEquals(scheme.onSurface, scheme.readableForeground(scheme.onSurface))
        assertTrue(contrastRatio(scheme.readableForeground(scheme.secondary, scheme.secondaryContainer), scheme.secondaryContainer) >= 4.5)
    }

    @Test fun readableForeground_checksRenderedAlphaAndKeepsLegibleAlphaOverrides() {
        val scheme = MotdDarkScheme
        val faint = Color.White.copy(alpha = 0.1f)
        val ink = scheme.readableForeground(faint, Color.Black)
        assertNotEquals(faint, ink)
        assertTrue(contrastRatio(ink.compositeOver(Color.Black), Color.Black) >= 4.5)
        val legible = Color.White.copy(alpha = 0.9f)
        assertEquals(legible, scheme.readableForeground(legible, Color.Black))
        assertEquals(Color.Transparent, scheme.readableForeground(Color.Transparent, Color.Black))
    }

    @Test fun textButtons_checkTranslucentContainersAgainstActualAndNeutralBackgrounds() {
        val scheme = MotdLightScheme
        val colors =
            ButtonColors(
                containerColor = Color.Black.copy(alpha = 0.1f),
                contentColor = Color.White,
                disabledContainerColor = Color.Red.copy(alpha = 0.1f),
                disabledContentColor = Color.White.copy(alpha = 0.1f),
            )
        for (background in listOf(Color.White, Color.Unspecified)) {
            val corrected = scheme.readableButtonColors(background, colors)
            val surfaces = if (background == Color.Unspecified) neutralSurfaces(scheme) else listOf(background)
            for (surface in surfaces) {
                val fill = colors.containerColor.compositeOver(surface)
                assertTrue(contrastRatio(corrected.contentColor.compositeOver(fill), fill) >= 4.5)
            }
            assertEquals(colors.containerColor, corrected.containerColor)
            assertEquals(colors.disabledContainerColor, corrected.disabledContainerColor)
            assertEquals(colors.disabledContentColor, corrected.disabledContentColor)
        }
        val faintInk = colors.copy(containerColor = Color.Black, contentColor = Color.White.copy(alpha = 0.1f))
        val corrected = scheme.readableButtonColors(Color.White, faintInk)
        assertTrue(contrastRatio(corrected.contentColor.compositeOver(Color.Black), Color.Black) >= 4.5)
    }

    @Test fun fieldSelection_keepsFocusedAndErrorInkReadableWithoutRemovingTheCue() {
        val scheme = MotdLightScheme
        val preferred = TextSelectionColors(Color.Black, Color.White.copy(alpha = 0.8f))
        val focusedInk = scheme.readableForeground(Color.Black, Color.White)
        val errorInk = scheme.readableForeground(Color.White, Color.Black)
        val selection = scheme.readableSelectionColors(Color.White, focusedInk, preferred, Color.Black, errorInk)
        assertTrue(selection.backgroundColor.alpha > 0f)
        assertTrue(selection.backgroundColor.alpha < preferred.backgroundColor.alpha)
        for ((fill, ink) in listOf(Color.White to focusedInk, Color.Black to errorInk)) {
            val selectedFill = selection.backgroundColor.compositeOver(fill)
            assertNotEquals(fill, selectedFill)
            assertTrue(contrastRatio(ink.compositeOver(selectedFill), selectedFill) >= 4.5)
        }
    }

    @Test fun draculaMatchedAdjustedSelection_staysVisibleGloballyAndInFields() {
        val scheme = requireNotNull(fixedThemeScheme(ColorThemePreset.DRACULA, useThemeTextColors = true, adjustPaletteReadability = true))
        val global = scheme.readableSelectionColors()
        assertTrue(global.backgroundColor.alpha > 0f)
        for (fill in neutralSurfaces(scheme)) {
            val selectedFill = global.backgroundColor.compositeOver(fill)
            assertNotEquals("visible global selection on $fill", fill, selectedFill)
            for (ink in listOf(scheme.onSurface, scheme.onBackground, scheme.onSurfaceVariant)) {
                assertTrue(contrastRatio(ink.compositeOver(selectedFill), selectedFill) >= 4.5)
            }
            val focusedInk = scheme.readableForeground(scheme.onSurface, fill)
            val errorInk = scheme.readableForeground(scheme.onSurface, scheme.surface)
            val field = scheme.readableSelectionColors(fill, focusedInk, global, scheme.surface, errorInk)
            for ((activeFill, ink) in listOf(fill to focusedInk, scheme.surface to errorInk)) {
                val selectedFieldFill = field.backgroundColor.compositeOver(activeFill)
                assertNotEquals("visible field selection on $activeFill", activeFill, selectedFieldFill)
                assertTrue(contrastRatio(ink.compositeOver(selectedFieldFill), selectedFieldFill) >= 4.5)
            }
        }
        val transparent = TextSelectionColors(global.handleColor, Color.Transparent)
        assertEquals(transparent, scheme.readableSelectionColors(preferred = transparent))
        val visible = scheme.readableSelectionColors(scheme.surface, scheme.onSurface)
        assertEquals(visible, scheme.readableSelectionColors(scheme.surface, scheme.onSurface, visible))
    }

    @Test fun authoredTextStillSeedsFilledInkIndependentlyOfReadingMode() {
        val authoredInk = Color(0xFF001060)
        val raw = MotdLightRaw.copy(onSurface = authoredInk)
        val neutral = paletteColorScheme(raw, dark = false)
        val themed = paletteColorScheme(raw, dark = false, useThemeTextColors = true)
        assertEquals(authoredInk, themed.onSurface)
        assertNotEquals(authoredInk, neutral.onSurface)
        assertEquals(filledRoles(neutral), filledRoles(themed))
        assertEquals(authoredInk, neutral.onSecondaryContainer)
    }

    @Test fun comfortableBubbleThemeRolesFollowTheResolvedColorSchemeWhileCustomIsFixed() {
        val primary = ComfortableBubbleColor(ComfortableBubbleColorRole.PRIMARY)
        val custom = ComfortableBubbleColor(ComfortableBubbleColorRole.CUSTOM, 0xff12ab34.toInt())
        val gruvbox = requireNotNull(fixedThemeScheme(ColorThemePreset.GRUVBOX_DARK))
        val nord = requireNotNull(fixedThemeScheme(ColorThemePreset.NORD))
        assertEquals(gruvbox.primary, resolveComfortableBubbleColor(primary, gruvbox))
        assertEquals(nord.primary, resolveComfortableBubbleColor(primary, nord))
        assertNotEquals(resolveComfortableBubbleColor(primary, gruvbox), resolveComfortableBubbleColor(primary, nord))
        assertEquals(Color(0xff12ab34.toInt()), resolveComfortableBubbleColor(custom, gruvbox))
        assertEquals(Color(0xff12ab34.toInt()), resolveComfortableBubbleColor(custom, nord))
        assertNull(resolveComfortableBubbleColor(ComfortableBubbleColor(), gruvbox))
    }

    @Test fun motdBrandSeeds_useTheApprovedRoleCodedTrio() {
        assertEquals(0xFF006C70.toInt(), MotdTeal.toArgb())
        assertEquals(0xFF8B4FA5.toInt(), MotdOrchid.toArgb())
        assertEquals(0xFFB44C46.toInt(), MotdCoral.toArgb())
    }

    @Test fun everyNamedTheme_resolvesToACompleteScheme() {
        ColorThemePreset.entries.filter { it.isFixedPalette }.forEach {
            assertNotNull("$it must resolve", fixedThemeScheme(it))
        }
    }

    @Test fun modusCatalog_includesEveryOfficialLightAndDarkVariant() {
        val pairs =
            mapOf(
                ColorThemePreset.MODUS_OPERANDI to ColorThemePreset.MODUS_VIVENDI,
                ColorThemePreset.MODUS_OPERANDI_TINTED to ColorThemePreset.MODUS_VIVENDI_TINTED,
                ColorThemePreset.MODUS_OPERANDI_DEUTERANOPIA to ColorThemePreset.MODUS_VIVENDI_DEUTERANOPIA,
                ColorThemePreset.MODUS_OPERANDI_TRITANOPIA to ColorThemePreset.MODUS_VIVENDI_TRITANOPIA,
            )

        pairs.forEach { (light, dark) ->
            assertTrue("$light must be light", !light.isDark)
            assertTrue("$dark must be dark", dark.isDark)
            assertEquals(dark, light.systemPartner)
            assertEquals(light, dark.systemPartner)
            assertNotNull(fixedThemeScheme(light))
            assertNotNull(fixedThemeScheme(dark))
        }
    }

    @Test fun newlyPairedPalettes_resolveBothModes() {
        listOf(
            ColorThemePreset.DRACULA to ColorThemePreset.DRACULA_LIGHT,
            ColorThemePreset.TOKYO_NIGHT to ColorThemePreset.TOKYO_NIGHT_LIGHT,
        ).forEach { (dark, light) ->
            assertTrue(dark.isDark)
            assertTrue(!light.isDark)
            assertEquals(light, dark.systemPartner)
            assertEquals(dark, light.systemPartner)
            assertEquals(light, resolvePalettePreset(dark, PaletteMode.SYSTEM, systemDark = false))
            assertEquals(dark, resolvePalettePreset(light, PaletteMode.SYSTEM, systemDark = true))
            assertNotNull(rawFixedThemeScheme(dark))
            assertNotNull(rawFixedThemeScheme(light))
            assertNotEquals(fixedThemeScheme(dark)!!.background, fixedThemeScheme(light)!!.background)
        }
    }

    @Test fun systemPartners_resolveToOppositeModeSchemeAndAreSymmetric() {
        ColorThemePreset.entries.forEach { preset ->
            val partner = preset.systemPartner
            if (partner == null) {
                // Meta presets, dark-only palettes, and alternate darks never auto-swap.
                return@forEach
            }
            assertEquals("$partner is the reverse partner of $preset", preset, partner.systemPartner)
            if (partner.isFixedPalette) {
                assertNotNull("$partner must resolve as a fixed palette", fixedThemeScheme(partner))
            }
            // A paired preset always swaps to the opposite OS mode.
            assertNotEquals(
                "$preset and partner $partner must differ in isDark",
                preset.isDark,
                partner.isDark,
            )
        }
    }

    @Test fun systemPartners_stayNullForDarkOnlyAndAlternateDarkPalettes() {
        val neverPaired =
            setOf(
                ColorThemePreset.SYSTEM,
                ColorThemePreset.AMOLED,
                ColorThemePreset.MONOKAI,
                ColorThemePreset.ONE_DARK,
                ColorThemePreset.ZENBURN,
                ColorThemePreset.AYU_MIRAGE,
                ColorThemePreset.KANAGAWA_DRAGON,
                ColorThemePreset.ROSE_PINE_MOON,
            )
        neverPaired.forEach { assertNull("$it must not auto-swap", it.systemPartner) }
    }

    @Test fun explicitModes_ignoreSystemModeAndResolveTheRequestedVariant() {
        assertEquals(
            ColorThemePreset.KANAGAWA_WAVE,
            resolvePalettePreset(ColorThemePreset.KANAGAWA_WAVE, PaletteMode.DARK, systemDark = false),
        )
        assertEquals(
            ColorThemePreset.GRUVBOX_LIGHT,
            resolvePalettePreset(ColorThemePreset.GRUVBOX_DARK, PaletteMode.LIGHT, systemDark = true),
        )
    }

    @Test fun systemMode_swapsPairedFamiliesAndKeepsSingleModePalettes() {
        ColorThemePreset.entries.forEach { preset ->
            val partner = preset.systemPartner
            if (partner != null) {
                val light = if (preset.isDark) partner else preset
                val dark = if (preset.isDark) preset else partner
                assertEquals(light, resolvePalettePreset(preset, PaletteMode.SYSTEM, systemDark = false))
                assertEquals(dark, resolvePalettePreset(preset, PaletteMode.SYSTEM, systemDark = true))
            } else if (preset != ColorThemePreset.SYSTEM && preset != ColorThemePreset.AMOLED) {
                PaletteMode.entries.forEach { mode ->
                    assertEquals(preset, resolvePalettePreset(preset, mode, systemDark = false))
                    assertEquals(preset, resolvePalettePreset(preset, mode, systemDark = true))
                }
            }
        }
        PaletteMode.entries.forEach { mode ->
            assertEquals(ColorThemePreset.SYSTEM, resolvePalettePreset(ColorThemePreset.SYSTEM, mode, systemDark = true))
            assertEquals(ColorThemePreset.SYSTEM, resolvePalettePreset(ColorThemePreset.SYSTEM, mode, systemDark = false))
        }
    }

    @Test fun everyStaticPalette_meetsReadableRoleContrast() {
        staticSchemes().forEach { (preset, scheme) ->
            assertTextPair(preset, "background", scheme.onBackground, scheme.background)
            assertTextPair(preset, "surface", scheme.onSurface, scheme.surface)
            assertTextPair(preset, "surface variant", scheme.onSurfaceVariant, scheme.surfaceVariant)
            assertTextPair(preset, "primary", scheme.onPrimary, scheme.primary)
            assertTextPair(preset, "primary container", scheme.onPrimaryContainer, scheme.primaryContainer)
            assertTextPair(preset, "secondary", scheme.onSecondary, scheme.secondary)
            assertTextPair(preset, "secondary container", scheme.onSecondaryContainer, scheme.secondaryContainer)
            assertTextPair(preset, "tertiary", scheme.onTertiary, scheme.tertiary)
            assertTextPair(preset, "tertiary container", scheme.onTertiaryContainer, scheme.tertiaryContainer)
            assertTextPair(preset, "error", scheme.onError, scheme.error)
            assertTextPair(preset, "error container", scheme.onErrorContainer, scheme.errorContainer)
            assertTextPair(preset, "snackbar content", scheme.inverseOnSurface, scheme.inverseSurface)
            assertTextPair(preset, "snackbar action", scheme.inversePrimary, scheme.inverseSurface)
            assertBubbleContainers(preset, scheme)

            neutralSurfaces(scheme).forEachIndexed { index, surface ->
                assertContrast(preset, "onSurface on neutral $index", scheme.onSurface, surface, 4.5)
                assertContrast(preset, "onSurfaceVariant on neutral $index", scheme.onSurfaceVariant, surface, 4.5)
                assertContrast(preset, "primary on neutral $index", scheme.primary, surface, 4.5)
                assertContrast(preset, "secondary on neutral $index", scheme.secondary, surface, 4.5)
                assertContrast(preset, "tertiary on neutral $index", scheme.tertiary, surface, 4.5)
                assertContrast(preset, "error on neutral $index", scheme.error, surface, 4.5)
                assertContrast(preset, "outline on neutral $index", scheme.outline, surface, 3.0)
                assertContrast(preset, "outlineVariant on neutral $index", scheme.outlineVariant, surface, 3.0)
            }
        }
    }

    @Test fun ordinaryText_isNeutralAcrossFixedDynamicAndTrueBlackThemesWhileSemanticFillsStayReadable() {
        messageContrastSchemes().forEach { (name, scheme, dark) ->
            val ink = scheme.onSurface
            assertEquals("$name background ink", ink, scheme.onBackground)
            assertEquals("$name supporting ink", ink, scheme.onSurfaceVariant)
            assertEquals("$name neutral red/green", ink.red, ink.green, 0.00001f)
            assertEquals("$name neutral green/blue", ink.green, ink.blue, 0.00001f)
            assertTrue("$name applied mode", if (dark) ink.luminance() > 0.5f else ink.luminance() < 0.5f)
            neutralSurfaces(scheme).forEachIndexed { index, fill ->
                assertTrue("$name ordinary ink on surface $index", contrastRatio(ink, fill) >= 4.5)
            }
            val semantic = semanticColors(scheme, dark)
            listOf(
                scheme.onPrimary to scheme.primary,
                scheme.onSecondary to scheme.secondary,
                scheme.onTertiary to scheme.tertiary,
                scheme.onError to scheme.error,
                scheme.onPrimaryContainer to scheme.primaryContainer,
                scheme.onSecondaryContainer to scheme.secondaryContainer,
                scheme.onTertiaryContainer to scheme.tertiaryContainer,
                scheme.onErrorContainer to scheme.errorContainer,
                scheme.inverseOnSurface to scheme.inverseSurface,
                scheme.inversePrimary to scheme.inverseSurface,
                semantic.onSuccess to semantic.success,
                semantic.onWarning to semantic.warning,
                semantic.onSuccessContainer to semantic.successContainer,
                semantic.onWarningContainer to semantic.warningContainer,
            ).forEachIndexed { index, (foreground, fill) ->
                assertTrue("$name semantic fill $index", contrastRatio(foreground, fill) >= 4.5)
            }
            assertNotEquals("$name primary remains accented", ink, scheme.primary)
            assertNotEquals("$name error remains semantic", ink, scheme.error)
            assertNotEquals("$name success remains semantic", ink, semantic.success)
            assertNotEquals("$name warning remains semantic", ink, semantic.warning)
        }
    }

    @Test fun themeTextRoles_restoreRawPaletteHuesBeforeContrastAdaptation() {
        for ((preset, raw) in listOf(ColorThemePreset.GRUVBOX_LIGHT to GruvboxLightScheme, ColorThemePreset.GRUVBOX_DARK to GruvboxDarkScheme)) {
            val themed = requireNotNull(fixedThemeScheme(preset, useThemeTextColors = true, adjustPaletteReadability = true))
            val static = requireNotNull(fixedThemeScheme(preset, adjustPaletteReadability = true))
            val surfaces = neutralSurfaces(themed)
            assertEquals("$preset background", ensureContrast(raw.onBackground, surfaces), themed.onBackground)
            assertEquals("$preset surface", ensureContrast(raw.onSurface, surfaces), themed.onSurface)
            assertEquals("$preset supporting", ensureContrast(raw.onSurfaceVariant, surfaces), themed.onSurfaceVariant)
            assertNotEquals("$preset ordinary text changes", static.onSurface, themed.onSurface)
            assertNotEquals("$preset reading text changes", readingInk(static, false), readingInk(themed, true))
        }
    }

    @Test fun bothTextModes_keepContrastAndLeaveFillsInverseAndSemanticColorsUnchanged() {
        val static = messageContrastSchemes()
        val themed = messageContrastSchemes(useThemeTextColors = true)
        assertEquals(static.map { it.first }, themed.map { it.first })
        static.zip(themed).forEach { (source, selected) ->
            val (name, scheme, dark) = source
            val themedScheme = selected.second
            neutralSurfaces(themedScheme).forEachIndexed { index, fill ->
                for (ink in listOf(themedScheme.onBackground, themedScheme.onSurface, themedScheme.onSurfaceVariant)) {
                    assertTrue("$name themed ordinary ink on $index", contrastRatio(ink, fill) >= 4.5)
                }
            }
            if (name.startsWith("Dynamic")) {
                val body = if (dark) Color(0xFFE6E0E9) else Color(0xFF1B1C17)
                val supporting = if (dark) Color(0xFFCAC4D0) else Color(0xFF44483C)
                val surfaces = neutralSurfaces(themedScheme)
                assertEquals("$name palette background", ensureContrast(body, surfaces), themedScheme.onBackground)
                assertEquals("$name palette surface", ensureContrast(body, surfaces), themedScheme.onSurface)
                assertEquals("$name palette supporting", ensureContrast(supporting, surfaces), themedScheme.onSurfaceVariant)
                assertNotEquals("$name switches ordinary ink", scheme.onSurface, themedScheme.onSurface)
                assertNotEquals("$name switches reading ink", readingInk(scheme, false), readingInk(themedScheme, true))
            }
            assertEquals("$name neutral surfaces", neutralSurfaces(scheme), neutralSurfaces(themedScheme))
            listOf(
                scheme.primary to themedScheme.primary,
                scheme.onPrimary to themedScheme.onPrimary,
                scheme.primaryContainer to themedScheme.primaryContainer,
                scheme.onPrimaryContainer to themedScheme.onPrimaryContainer,
                scheme.secondary to themedScheme.secondary,
                scheme.onSecondary to themedScheme.onSecondary,
                scheme.secondaryContainer to themedScheme.secondaryContainer,
                scheme.onSecondaryContainer to themedScheme.onSecondaryContainer,
                scheme.tertiary to themedScheme.tertiary,
                scheme.onTertiary to themedScheme.onTertiary,
                scheme.tertiaryContainer to themedScheme.tertiaryContainer,
                scheme.onTertiaryContainer to themedScheme.onTertiaryContainer,
                scheme.error to themedScheme.error,
                scheme.onError to themedScheme.onError,
                scheme.errorContainer to themedScheme.errorContainer,
                scheme.onErrorContainer to themedScheme.onErrorContainer,
                scheme.inverseSurface to themedScheme.inverseSurface,
                scheme.inverseOnSurface to themedScheme.inverseOnSurface,
                scheme.inversePrimary to themedScheme.inversePrimary,
                scheme.outline to themedScheme.outline,
                scheme.outlineVariant to themedScheme.outlineVariant,
                scheme.surfaceTint to themedScheme.surfaceTint,
            ).forEachIndexed { index, (expected, actual) ->
                assertEquals("$name unchanged non-ordinary role $index", expected, actual)
            }
            assertEquals("$name semantic colors", semanticColors(scheme, dark), semanticColors(themedScheme, dark))
        }
    }

    @Test fun customAuthoredRoles_surviveExportWhileBothTextModesAndTrueBlackStayReadable() {
        for (dark in listOf(false, true)) {
            val authored =
                PaletteColors(
                    primary = "#826CA0",
                    secondary = "#648A80",
                    tertiary = "#AA8070",
                    background = if (dark) "#202020" else "#FAFAFA",
                    surface = if (dark) "#242424" else "#F7F7F7",
                    surfaceContainer = if (dark) "#303030" else "#EEEEEE",
                    onBackground = if (dark) "#555050" else "#AAAAA0",
                    onSurface = if (dark) "#565151" else "#ABABA1",
                    onSurfaceVariant = if (dark) "#575252" else "#ACACA2",
                    outlineVariant = "#888080",
                )
            val document =
                PaletteDocument(
                    id = "authored-gray",
                    name = "Authored gray",
                    revision = 1,
                    light = authored.takeUnless { dark },
                    dark = authored.takeIf { dark },
                )
            val exported = decodePalette(encodePalette(document))
            val colors = requireNotNull(if (dark) exported.dark else exported.light)
            val raw = colors.toRawColorScheme(dark)
            assertTrue("authored low contrast is not fitted on export", contrastRatio(raw.onBackground, raw.background) < 4.5)
            assertNotEquals(raw.background, raw.surface)
            assertEquals(authored.onBackground, colors.onBackground)
            for (themeText in listOf(false, true)) {
                val unadjusted = paletteColorScheme(raw, dark, themeText)
                if (themeText) {
                    assertEquals(authoredRoles(raw), authoredRoles(unadjusted))
                    assertTrue(contrastRatio(unadjusted.onBackground, unadjusted.background) < 4.5)
                } else {
                    assertTrue(contrastRatio(unadjusted.onBackground, unadjusted.background) >= 4.5)
                    assertEquals(unadjusted.onBackground, unadjusted.onSurfaceVariant)
                }
                listOf(
                    unadjusted.onPrimary to unadjusted.primary,
                    unadjusted.onSecondary to unadjusted.secondary,
                    unadjusted.onTertiary to unadjusted.tertiary,
                    unadjusted.onPrimaryContainer to unadjusted.primaryContainer,
                    unadjusted.onError to unadjusted.error,
                ).forEach { (ink, fill) -> assertTrue(contrastRatio(ink, fill) >= 4.5) }
                if (dark) {
                    val black = unadjusted.withTrueBlackSurfaces()
                    assertEquals(Color.Black, black.background)
                    assertEquals(unadjusted.onBackground, black.onBackground)
                    assertEquals(unadjusted.primary, black.primary)
                }
            }
            for (themeText in listOf(false, true)) {
                val applied = paletteColorScheme(raw, dark, themeText, adjustPaletteReadability = true)
                assertEquals(raw.background, applied.background)
                assertEquals(raw.surface, applied.surface)
                assertNotEquals(raw.onBackground, applied.onBackground)
                if (themeText) {
                    assertEquals(ensureContrast(raw.onBackground, neutralSurfaces(applied)), applied.onBackground)
                    assertEquals(ensureContrast(raw.onSurfaceVariant, neutralSurfaces(applied)), applied.onSurfaceVariant)
                } else {
                    assertEquals(applied.onBackground, applied.onSurface)
                    assertEquals(applied.onSurface, applied.onSurfaceVariant)
                    assertEquals(applied.onSurface.red, applied.onSurface.green, 0.00001f)
                }
                val variants = if (dark) listOf(applied, applied.withTrueBlackSurfaces()) else listOf(applied)
                for (variant in variants) {
                    if (variant !== applied) assertEquals(Color.Black, variant.background)
                    neutralSurfaces(variant).forEach { fill ->
                        listOf(variant.onBackground, variant.onSurface, variant.onSurfaceVariant).forEach { ink ->
                            assertTrue("custom $dark/$themeText ink", contrastRatio(ink, fill) >= 4.5)
                        }
                        assertTrue("custom outline", contrastRatio(variant.outlineVariant, fill) >= 3.0)
                    }
                }
            }
            // Applying either preference never mutates the shareable authored document.
            assertEquals(exported, decodePalette(encodePalette(document)))
        }
    }

    @Test fun trueBlack_preservesPaletteIdentityAndContrastForEveryDarkPalette() {
        listOf(false, true).flatMap { staticSchemes(it) }.filter { it.first.isDark }.forEach { (preset, source) ->
            val scheme = source.withTrueBlackSurfaces()
            assertEquals("$preset true-black background", Color.Black, scheme.background)
            assertEquals("$preset true-black surface", Color.Black, scheme.surface)
            assertEquals("$preset keeps primary", source.primary, scheme.primary)
            assertBubbleContainers(preset, scheme)
            assertTrue("$preset elevation order", scheme.surfaceContainerLow.luminanceValue() < scheme.surfaceContainerHigh.luminanceValue())
            assertTrue("$preset raised surface", scheme.surfaceContainerHigh.luminanceValue() < scheme.surfaceContainerHighest.luminanceValue())
            neutralSurfaces(scheme).forEachIndexed { index, surface ->
                assertContrast(preset, "true-black onSurface $index", scheme.onSurface, surface, 4.5)
                assertContrast(preset, "true-black onSurfaceVariant $index", scheme.onSurfaceVariant, surface, 4.5)
                assertContrast(preset, "true-black primary $index", scheme.primary, surface, 4.5)
                assertContrast(preset, "true-black outline $index", scheme.outline, surface, 3.0)
            }
        }
    }

    @Test fun semanticStateColors_meetTextAndIconContrast() {
        staticSchemes().forEach { (preset, scheme) ->
            val semantic = semanticColors(scheme, preset.isDark)
            val surfaces = neutralSurfaces(scheme)
            surfaces.forEachIndexed { index, surface ->
                assertContrast(preset, "success on neutral $index", semantic.success, surface, 4.5)
                assertContrast(preset, "warning on neutral $index", semantic.warning, surface, 4.5)
            }
            assertTextPair(preset, "success", semantic.onSuccess, semantic.success)
            assertTextPair(preset, "success container", semantic.onSuccessContainer, semantic.successContainer)
            assertTextPair(preset, "warning", semantic.onWarning, semantic.warning)
            assertTextPair(preset, "warning container", semantic.onWarningContainer, semantic.warningContainer)
        }
    }

    @Test fun everyNickHueAndPalette_isReadableAcrossItsThemeSurfaces() {
        staticSchemes().forEach { (preset, scheme) ->
            val variants = if (preset.isDark) listOf(scheme, scheme.withTrueBlackSurfaces()) else listOf(scheme)
            variants.forEach { variant ->
                val backgrounds = nickBackgrounds(variant)
                NickColorPalette.entries.forEach { palette ->
                    for (hue in 0..359) {
                        val colors =
                            NickColorScheme(
                                enabled = true,
                                palette = palette,
                                overrides = mapOf("nick" to hue),
                                isDark = preset.isDark,
                                textBackgrounds = backgrounds,
                                themeColors = listOf(variant.primary, variant.tertiary, variant.secondary),
                            )
                        val color = colors.nick("nick", variant.onSurface)
                        backgrounds.forEachIndexed { index, background ->
                            assertContrast(preset, "$palette nick hue $hue on $index", color, background, 4.5)
                        }
                        val avatar = colors.avatar("nick")
                        assertTrue(
                            "$preset $palette avatar hue $hue foreground",
                            contrastRatio(onColorFor(avatar), avatar) >= 4.5,
                        )
                    }
                }
            }
        }
    }

    private fun staticSchemes(useThemeTextColors: Boolean = false): List<Pair<ColorThemePreset, ColorScheme>> =
        buildList {
            add(ColorThemePreset.LIGHT to motdThemeScheme(false, useThemeTextColors, adjustPaletteReadability = true))
            add(ColorThemePreset.DARK to motdThemeScheme(true, useThemeTextColors, adjustPaletteReadability = true))
            ColorThemePreset.entries.filter { it.isFixedPalette }.forEach { preset ->
                add(preset to requireNotNull(fixedThemeScheme(preset, useThemeTextColors, adjustPaletteReadability = true)))
            }
        }

    private fun authoredRoles(scheme: ColorScheme): List<Color> =
        listOf(
            scheme.primary,
            scheme.secondary,
            scheme.tertiary,
            scheme.background,
            scheme.surface,
            scheme.surfaceContainer,
            scheme.onBackground,
            scheme.onSurface,
            scheme.onSurfaceVariant,
            scheme.outlineVariant,
            scheme.outline,
        )

    private fun filledRoles(scheme: ColorScheme): List<Color> =
        listOf(
            scheme.primary,
            scheme.onPrimary,
            scheme.primaryContainer,
            scheme.onPrimaryContainer,
            scheme.secondary,
            scheme.onSecondary,
            scheme.secondaryContainer,
            scheme.onSecondaryContainer,
            scheme.tertiary,
            scheme.onTertiary,
            scheme.tertiaryContainer,
            scheme.onTertiaryContainer,
            scheme.error,
            scheme.onError,
            scheme.errorContainer,
            scheme.onErrorContainer,
            scheme.inverseSurface,
            scheme.inverseOnSurface,
            scheme.inversePrimary,
        )

    private fun neutralSurfaces(scheme: ColorScheme): List<Color> =
        listOf(
            scheme.background,
            scheme.surface,
            scheme.surfaceContainerLowest,
            scheme.surfaceContainerLow,
            scheme.surfaceContainer,
            scheme.surfaceContainerHigh,
            scheme.surfaceContainerHighest,
            scheme.surfaceBright,
            scheme.surfaceDim,
            scheme.surfaceVariant,
        ).distinct()

    private fun nickBackgrounds(scheme: ColorScheme): List<Color> =
        (
            neutralSurfaces(scheme) +
                listOf(
                    scheme.primaryContainer,
                    scheme.secondaryContainer,
                    scheme.tertiaryContainer,
                )
        ).distinct()

    private fun assertTextPair(
        preset: ColorThemePreset,
        role: String,
        foreground: Color,
        background: Color,
    ) {
        assertContrast(preset, role, foreground, background, 4.5)
    }

    private fun assertBubbleContainers(
        preset: ColorThemePreset,
        scheme: ColorScheme,
    ) {
        listOf(
            "other bubble" to scheme.surfaceContainerHigh,
            "own bubble" to scheme.primaryContainer,
            "mention bubble" to scheme.secondaryContainer,
        ).forEach { (role, container) ->
            assertContrast(preset, "$role against background", container, scheme.background, BUBBLE_CONTAINER_CONTRAST)
        }
    }

    private fun assertContrast(
        preset: ColorThemePreset,
        role: String,
        foreground: Color,
        background: Color,
        minimum: Double,
    ) {
        assertTrue(
            "$preset $role contrast was ${contrastRatio(foreground, background)}",
            contrastRatio(foreground, background) >= minimum,
        )
    }

    private fun Color.luminanceValue(): Float = luminance()
}
