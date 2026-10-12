package io.github.trevarj.motd.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.isDark
import io.github.trevarj.motd.data.prefs.isFixedPalette
import io.github.trevarj.motd.data.prefs.resolveAutoPalette
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
                ColorThemePreset.DRACULA,
                ColorThemePreset.MONOKAI,
                ColorThemePreset.ONE_DARK,
                ColorThemePreset.TOKYO_NIGHT,
                ColorThemePreset.ZENBURN,
                ColorThemePreset.AYU_MIRAGE,
                ColorThemePreset.KANAGAWA_DRAGON,
                ColorThemePreset.ROSE_PINE_MOON,
            )
        neverPaired.forEach { assertNull("$it must not auto-swap", it.systemPartner) }
    }

    @Test fun resolveAutoPalette_keepsExplicitVariantWhenSystemFollowingIsDisabled() {
        assertEquals(
            ColorThemePreset.KANAGAWA_WAVE,
            resolveAutoPalette(ColorThemePreset.KANAGAWA_WAVE, followSystem = false, systemDark = false),
        )
        assertEquals(
            ColorThemePreset.GRUVBOX_LIGHT,
            resolveAutoPalette(ColorThemePreset.GRUVBOX_LIGHT, followSystem = false, systemDark = true),
        )
    }

    @Test fun resolveAutoPalette_swapsPairedFamiliesAndIgnoresDarkOnlyThemes() {
        assertEquals(ColorThemePreset.KANAGAWA_LOTUS, resolveAutoPalette(ColorThemePreset.KANAGAWA_WAVE, followSystem = true, systemDark = false))
        assertEquals(ColorThemePreset.KANAGAWA_WAVE, resolveAutoPalette(ColorThemePreset.KANAGAWA_WAVE, followSystem = true, systemDark = true))
        assertEquals(ColorThemePreset.GRUVBOX_DARK, resolveAutoPalette(ColorThemePreset.GRUVBOX_LIGHT, followSystem = true, systemDark = true))
        assertEquals(ColorThemePreset.GRUVBOX_LIGHT, resolveAutoPalette(ColorThemePreset.GRUVBOX_LIGHT, followSystem = true, systemDark = false))
        assertEquals(ColorThemePreset.DARK, resolveAutoPalette(ColorThemePreset.LIGHT, followSystem = true, systemDark = true))
        assertEquals(ColorThemePreset.LIGHT, resolveAutoPalette(ColorThemePreset.DARK, followSystem = true, systemDark = false))
        assertEquals(ColorThemePreset.NORD_LIGHT, resolveAutoPalette(ColorThemePreset.NORD, followSystem = true, systemDark = false))
        // Dark-only palettes have no partner and stay dark in both OS modes.
        assertEquals(ColorThemePreset.DRACULA, resolveAutoPalette(ColorThemePreset.DRACULA, followSystem = true, systemDark = false))
        assertEquals(ColorThemePreset.DRACULA, resolveAutoPalette(ColorThemePreset.DRACULA, followSystem = true, systemDark = true))
        assertEquals(ColorThemePreset.SYSTEM, resolveAutoPalette(ColorThemePreset.SYSTEM, followSystem = true, systemDark = true))
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

    @Test fun trueBlack_preservesPaletteIdentityAndContrastForEveryDarkPalette() {
        staticSchemes().filter { it.first.isDark }.forEach { (preset, source) ->
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

    private fun staticSchemes(): List<Pair<ColorThemePreset, ColorScheme>> =
        buildList {
            add(ColorThemePreset.LIGHT to MotdLightScheme)
            add(ColorThemePreset.DARK to MotdDarkScheme)
            ColorThemePreset.entries.filter { it.isFixedPalette }.forEach { preset ->
                add(preset to requireNotNull(fixedThemeScheme(preset)))
            }
        }

    private fun neutralSurfaces(scheme: ColorScheme): List<Color> =
        listOf(
            scheme.background,
            scheme.surface,
            scheme.surfaceContainerLowest,
            scheme.surfaceContainerLow,
            scheme.surfaceContainer,
            scheme.surfaceContainerHigh,
            scheme.surfaceContainerHighest,
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
