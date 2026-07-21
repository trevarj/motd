package io.github.trevarj.motd.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.isDark
import io.github.trevarj.motd.data.prefs.isFixedPalette
import io.github.trevarj.motd.ui.components.onColorFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeCatalogTest {
    @Test fun everyNamedTheme_resolvesToACompleteScheme() {
        ColorThemePreset.entries.filter { it.isFixedPalette }.forEach {
            assertNotNull("$it must resolve", fixedThemeScheme(it))
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

    @Test fun trueBlack_preservesPaletteIdentityAndContrastForEveryDarkPalette() {
        staticSchemes().filter { it.first.isDark }.forEach { (preset, source) ->
            val scheme = source.withTrueBlackSurfaces()
            assertEquals("$preset true-black background", Color.Black, scheme.background)
            assertEquals("$preset true-black surface", Color.Black, scheme.surface)
            assertEquals("$preset keeps primary", source.primary, scheme.primary)
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
                        val colors = NickColorScheme(
                            enabled = true,
                            palette = palette,
                            overrides = mapOf("nick" to hue),
                            isDark = preset.isDark,
                            textBackgrounds = backgrounds,
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

    private fun staticSchemes(): List<Pair<ColorThemePreset, ColorScheme>> = buildList {
        add(ColorThemePreset.LIGHT to MotdLightScheme)
        add(ColorThemePreset.DARK to MotdDarkScheme)
        ColorThemePreset.entries.filter { it.isFixedPalette }.forEach { preset ->
            add(preset to requireNotNull(fixedThemeScheme(preset)))
        }
    }

    private fun neutralSurfaces(scheme: ColorScheme): List<Color> = listOf(
        scheme.background,
        scheme.surface,
        scheme.surfaceContainerLowest,
        scheme.surfaceContainerLow,
        scheme.surfaceContainer,
        scheme.surfaceContainerHigh,
        scheme.surfaceContainerHighest,
        scheme.surfaceVariant,
    ).distinct()

    private fun nickBackgrounds(scheme: ColorScheme): List<Color> = (
        neutralSurfaces(scheme) + listOf(
            scheme.primaryContainer,
            scheme.secondaryContainer,
            scheme.tertiaryContainer,
        )
    ).distinct()

    private fun assertTextPair(preset: ColorThemePreset, role: String, foreground: Color, background: Color) {
        assertContrast(preset, role, foreground, background, 4.5)
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
