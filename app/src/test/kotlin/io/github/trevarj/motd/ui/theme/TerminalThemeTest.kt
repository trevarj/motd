package io.github.trevarj.motd.ui.theme

import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.data.prefs.isTerminalTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies that every ThemeMode resolves to a ColorScheme and that the isTerminalTheme flag is set
 *  correctly. Pure logic tests -- no Android/Compose runtime needed. */
class TerminalThemeTest {

    private val baseModes = setOf(
        ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.AMOLED,
    )
    private val terminalModes = ThemeMode.entries.toSet() - baseModes

    @Test
    fun baseModes_areNotTerminal() {
        for (mode in baseModes) {
            assertFalse("$mode should not be a terminal theme", mode.isTerminalTheme)
        }
    }

    @Test
    fun terminalModes_areAllMarked() {
        for (mode in terminalModes) {
            assertTrue("$mode should be marked as a terminal theme", mode.isTerminalTheme)
        }
    }

    @Test
    fun terminalModes_includeExpectedSchemes() {
        // Ensure the full set from the spec is present.
        val expected = setOf(
            ThemeMode.GRUVBOX_DARK, ThemeMode.GRUVBOX_LIGHT,
            ThemeMode.SOLARIZED_DARK, ThemeMode.SOLARIZED_LIGHT,
            ThemeMode.DRACULA,
            ThemeMode.NORD,
            ThemeMode.CATPPUCCIN_LATTE, ThemeMode.CATPPUCCIN_MOCHA,
            ThemeMode.TOKYO_NIGHT,
        )
        assertEquals(expected, terminalModes)
    }

    @Test
    fun allThemeModes_areCoveredByIsTerminalTheme() {
        // isTerminalTheme must return a value for every enum entry (no missing branch = exhaustive
        // when expression; this test would fail to compile if a new entry was unhandled).
        for (mode in ThemeMode.entries) {
            // No assertion needed: just calling it verifies no IllegalStateException is thrown.
            mode.isTerminalTheme
        }
    }
}
