package io.github.trevarj.motd.ui.theme

import androidx.compose.ui.graphics.Color
import io.github.trevarj.motd.data.prefs.NickColorPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Nick-color resolution order + DEFAULT-palette equality with the legacy nickColor generator. */
class NickColorResolveTest {

    private val nicks = listOf("alice", "Bob", "carol_", "#chan", "dave|away", "eve")
    private val fallback = Color(0.5f, 0.5f, 0.5f)

    @Test
    fun disabled_returnsFallback() {
        val c = resolveNickColor("alice", isDark = true, enabled = false,
            palette = NickColorPalette.VIVID, overrides = mapOf("alice" to 120), fallback = fallback)
        assertEquals(fallback, c)
    }

    @Test
    fun override_beats_hash() {
        // Override present -> hueColor for that hue; differs from the palette-hash color.
        val overridden = resolveNickColor("alice", isDark = false, enabled = true,
            palette = NickColorPalette.DEFAULT, overrides = mapOf("alice" to 210), fallback = fallback)
        assertEquals(hueColor(210, false, NickColorPalette.DEFAULT), overridden)
        assertNotEquals(paletteNickColor("alice", false, NickColorPalette.DEFAULT), overridden)
    }

    @Test
    fun override_lookup_is_normalized() {
        // Overrides are keyed by normalized nick; a raw " Alice " still resolves.
        val c = resolveNickColor(" Alice ", isDark = true, enabled = true,
            palette = NickColorPalette.DEFAULT, overrides = mapOf("alice" to 90), fallback = fallback)
        assertEquals(hueColor(90, true, NickColorPalette.DEFAULT), c)
    }

    @Test
    fun defaultPalette_noOverride_equals_legacy_nickColor() {
        for (nick in nicks) {
            for (isDark in listOf(true, false)) {
                assertEquals(
                    "DEFAULT palette must match legacy nickColor for $nick dark=$isDark",
                    nickColor(nick, isDark),
                    paletteNickColor(nick, isDark, NickColorPalette.DEFAULT),
                )
                // ...and through the full resolver with no override.
                assertEquals(
                    nickColor(nick, isDark),
                    resolveNickColor(nick, isDark, enabled = true,
                        palette = NickColorPalette.DEFAULT, overrides = emptyMap(), fallback = fallback),
                )
            }
        }
    }

    @Test
    fun hueColor_rendersEachPickerHue() {
        val hues = listOf(0, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330)
        for (h in hues) {
            for (palette in NickColorPalette.entries) {
                // Renders a concrete (non-Unspecified) color for each mode.
                assertNotEquals(Color.Unspecified, hueColor(h, true, palette))
                assertNotEquals(Color.Unspecified, hueColor(h, false, palette))
            }
        }
    }

    @Test
    fun hueColor_clampsOutOfRange() {
        // Out-of-range hues clamp into 0..359 (359 vs 400 both clamp to the same color).
        assertEquals(hueColor(359, true, NickColorPalette.VIVID), hueColor(400, true, NickColorPalette.VIVID))
        assertEquals(hueColor(0, false, NickColorPalette.PASTEL), hueColor(-20, false, NickColorPalette.PASTEL))
    }

    @Test
    fun scheme_avatar_ignoresEnabledFlag() {
        // With coloring disabled, sender text goes neutral but avatars keep their generated color.
        val scheme = NickColorScheme(enabled = false, palette = NickColorPalette.DEFAULT,
            overrides = emptyMap(), isDark = true)
        assertEquals(fallback, scheme.nick("alice", fallback))
        assertEquals(paletteNickColor("alice", true, NickColorPalette.DEFAULT), scheme.avatar("alice"))
    }
}
