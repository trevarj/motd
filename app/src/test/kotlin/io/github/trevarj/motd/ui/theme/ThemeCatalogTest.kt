package io.github.trevarj.motd.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.isDark
import io.github.trevarj.motd.data.prefs.isFixedPalette
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeCatalogTest {
    @Test fun everyNamedTheme_resolvesToACompleteScheme() {
        ColorThemePreset.entries.filter { it.isFixedPalette }.forEach {
            assertNotNull("$it must resolve", fixedThemeScheme(it))
        }
    }

    @Test fun bodyTextRoles_meetNormalTextContrast() {
        ColorThemePreset.entries.filter { it.isFixedPalette }.forEach { preset ->
            val scheme = requireNotNull(fixedThemeScheme(preset))
            assertTrue("$preset background contrast", contrast(scheme.background, scheme.onBackground) >= 4.5)
            assertTrue("$preset surface contrast", contrast(scheme.surface, scheme.onSurface) >= 4.5)
        }
    }

    @Test fun onlinePresence_meetsNonTextContrastAgainstEveryNamedTheme() {
        ColorThemePreset.entries.filter { it.isFixedPalette }.forEach { preset ->
            val scheme = requireNotNull(fixedThemeScheme(preset))
            val online = presenceOnlineColor(preset.isDark)
            assertTrue("$preset online presence contrast", contrast(scheme.background, online) >= 3.0)
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val light = maxOf(a.luminance(), b.luminance()).toDouble()
        val dark = minOf(a.luminance(), b.luminance()).toDouble()
        return (light + .05) / (dark + .05)
    }
}
