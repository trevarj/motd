package io.github.trevarj.motd.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.NickColorOverride
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.isDark
import io.github.trevarj.motd.data.prefs.isFixedPalette
import io.github.trevarj.motd.data.prefs.nickColorPaletteFromPreference
import io.github.trevarj.motd.ui.components.customContrastPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Nick-color resolution order + CLASSIC-palette equality with the legacy nickColor generator. */
class NickColorResolveTest {
    private val nicks = listOf("alice", "Bob", "carol_", "#chan", "dave|away", "eve")
    private val fallback = Color(0.5f, 0.5f, 0.5f)
    private val themeColors = listOf(Color.Red, Color.Green, Color.Blue)

    @Test
    fun disabled_returnsFallback() {
        val c =
            resolveNickColor(
                "alice",
                isDark = true,
                enabled = false,
                palette = NickColorPalette.VIVID,
                overrides = mapOf("alice" to 120),
                fallback = fallback,
            )
        assertEquals(fallback, c)
    }

    @Test
    fun override_beats_hash() {
        // Override present -> hueColor for that hue; differs from the palette-hash color.
        val overridden =
            resolveNickColor(
                "alice",
                isDark = false,
                enabled = true,
                palette = NickColorPalette.CLASSIC,
                overrides = mapOf("alice" to 210),
                fallback = fallback,
            )
        assertEquals(hueColor(210, false, NickColorPalette.CLASSIC), overridden)
        assertNotEquals(paletteNickColor("alice", false, NickColorPalette.CLASSIC), overridden)
    }

    @Test
    fun override_lookup_is_normalized() {
        // Overrides are keyed by normalized nick; a raw " Alice " still resolves.
        val c =
            resolveNickColor(
                " Alice ",
                isDark = true,
                enabled = true,
                palette = NickColorPalette.CLASSIC,
                overrides = mapOf("alice" to 90),
                fallback = fallback,
            )
        assertEquals(hueColor(90, true, NickColorPalette.CLASSIC), c)
    }

    @Test
    fun classicPalette_noOverride_equals_legacy_nickColor() {
        for (nick in nicks) {
            for (isDark in listOf(true, false)) {
                assertEquals(
                    "CLASSIC palette must match legacy nickColor for $nick dark=$isDark",
                    nickColor(nick, isDark),
                    paletteNickColor(nick, isDark, NickColorPalette.CLASSIC),
                )
                // ...and through the full resolver with no override.
                assertEquals(
                    nickColor(nick, isDark),
                    resolveNickColor(
                        nick,
                        isDark,
                        enabled = true,
                        palette = NickColorPalette.CLASSIC,
                        overrides = emptyMap(),
                        fallback = fallback,
                    ),
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
                assertNotEquals(Color.Unspecified, hueColor(h, true, palette, themeColors))
                assertNotEquals(Color.Unspecified, hueColor(h, false, palette, themeColors))
            }
        }
    }

    @Test
    fun hueColor_clampsOutOfRange() {
        // Out-of-range hues clamp into 0..359 (359 vs 400 both clamp to the same color).
        assertEquals(hueColor(359, true, NickColorPalette.VIVID), hueColor(400, true, NickColorPalette.VIVID))
        assertEquals(
            hueColor(0, false, NickColorPalette.THEME, themeColors),
            hueColor(-20, false, NickColorPalette.THEME, themeColors),
        )
    }

    @Test
    fun themePalette_usesActiveThemeAccents() {
        val warm = listOf(Color.Red, Color.Yellow, Color.Magenta)
        val cool = listOf(Color.Blue, Color.Cyan, Color.Green)

        // A picked hue is honored exactly; only lightness and chroma come from the theme.
        assertEquals(210f, hueColor(210, false, NickColorPalette.THEME, warm).toOklch().hue, 1f)

        // THEME no longer lerps the accent list, it anchors a hue wheel on the primary, so the
        // same name lands on a different hue under a warm and a cool accent set.
        assertNotEquals(
            paletteNickColor("alice", false, NickColorPalette.THEME, warm),
            paletteNickColor("alice", false, NickColorPalette.THEME, cool),
        )
    }

    @Test
    fun themePalette_isDefault_andMigratesPastel() {
        assertEquals(NickColorPalette.THEME, Settings().nickColorPalette)
        assertEquals(NickColorPalette.THEME, nickColorPaletteFromPreference(null))
        assertEquals(NickColorPalette.THEME, nickColorPaletteFromPreference("DEFAULT"))
        assertEquals(NickColorPalette.THEME, nickColorPaletteFromPreference("PASTEL"))
        assertEquals(NickColorPalette.CLASSIC, nickColorPaletteFromPreference("CLASSIC"))
        assertEquals(NickColorPalette.VIVID, nickColorPaletteFromPreference("VIVID"))
    }

    @Test
    fun scheme_avatar_ignoresEnabledFlag() {
        // With coloring disabled, sender text goes neutral but avatars keep their generated color.
        val scheme =
            NickColorScheme(
                enabled = false,
                palette = NickColorPalette.CLASSIC,
                overrides = emptyMap(),
                isDark = true,
            )
        assertEquals(fallback, scheme.nick("alice", fallback))
        assertEquals(paletteNickFill("alice", true, NickColorPalette.CLASSIC), scheme.avatar("alice"))
    }

    // --- identity colors must come from the theme's own published palette -------------------

    /** A 62-name crowd, the same one the palette study measured. */
    private val crowd =
        listOf(
            "alice",
            "bob",
            "trevor",
            "antti",
            "Lionyx",
            "morganw",
            "mango",
            "emily",
            "rustacean",
            "pyBot",
            "k8s-admin",
            "dockerfan",
            "ChanServ",
            "wumpus",
            "lambda",
            "quark",
            "moss",
            "roy",
            "jen",
            "denholm",
            "richmond",
            "zoe",
            "kai",
            "juno",
            "vex",
            "nova",
            "orbit",
            "flux",
            "cinder",
            "pixel",
            "gonzo",
            "ada",
            "gr4ce",
            "linus",
            "dennis",
            "ken",
            "bwk",
            "rob",
            "hopper",
            "lovelace",
            "turing",
            "stallman",
            "torvalds",
            "carmack",
            "notch",
            "shodan",
            "glados",
            "wheatley",
            "cortana",
            "hal9000",
            "tars",
            "case",
            "marvin",
            "bender",
            "data",
            "lore",
            "alice_",
            "alice1",
            "bob1",
            "bob2",
            "trevor_",
            "nickserv2",
        )

    private class ThemeFixture(
        val accents: List<Color>,
        val backgrounds: List<Color>,
        val identityPalette: List<Color>,
        val isDark: Boolean,
    )

    private fun fixture(preset: ColorThemePreset): ThemeFixture = fixture(fixedThemeScheme(preset, adjustPaletteReadability = true)!!, preset.isDark, themeIdentityPalette(preset))

    private fun fixture(
        scheme: ColorScheme,
        dark: Boolean,
        identities: List<Color>,
    ): ThemeFixture =
        ThemeFixture(
            accents = listOf(scheme.primary, scheme.tertiary, scheme.secondary).distinct(),
            backgrounds =
                listOf(
                    scheme.background,
                    scheme.surface,
                    scheme.surfaceContainerLow,
                    scheme.surfaceContainerHigh,
                    scheme.surfaceContainerHighest,
                    scheme.primaryContainer,
                    scheme.secondaryContainer,
                    scheme.tertiaryContainer,
                ).distinct(),
            identityPalette = identities,
            isDark = dark,
        )

    private fun customFixtures(identities: List<String> = emptyList()): List<ThemeFixture> =
        listOf(false, true).flatMap { dark ->
            val colors = customContrastPalette(dark).copy(nicknameColors = identities)
            val palette = colors.nicknameColors.map { Color(0xFF000000L or it.substring(1).toLong(16)) }
            listOf(false, true).flatMap { useThemeTextColors ->
                val scheme = paletteColorScheme(colors.toRawColorScheme(dark), dark, useThemeTextColors, adjustPaletteReadability = true)
                listOf(fixture(scheme, dark, palette)) +
                    if (dark) listOf(fixture(scheme.withTrueBlackSurfaces(), true, palette)) else emptyList()
            }
        }

    private fun nickScheme(
        f: ThemeFixture,
        palette: NickColorPalette = NickColorPalette.THEME,
        overrides: Map<String, Int> = emptyMap(),
        enabled: Boolean = true,
    ): NickColorScheme =
        NickColorScheme(
            enabled = enabled,
            palette = palette,
            overrides = overrides,
            isDark = f.isDark,
            textBackgrounds = f.backgrounds,
            themeColors = f.accents,
            identityPalette = f.identityPalette,
            adjustPaletteReadability = true,
        )

    @Test
    fun authoredIdentityRetainsExactColorOnActualFillUntilAdjustmentEnabled() {
        for (dark in listOf(false, true)) {
            val authored = if (dark) Color(0xFF252525) else Color(0xFFE0E0E0)
            val fill = if (dark) Color(0xFF202020) else Color(0xFFE8E8E8)

            fun scheme(adjust: Boolean = false) =
                NickColorScheme(
                    enabled = true,
                    palette = NickColorPalette.THEME,
                    overrides = emptyMap(),
                    isDark = dark,
                    textBackgrounds = listOf(fill),
                    identityPalette = listOf(authored),
                    adjustPaletteReadability = adjust,
                )
            val original = scheme()
            assertEquals(authored, original.nick("alice", fallback))
            assertEquals(authored, original.mention("alice"))
            assertEquals(authored, original.nick("alice", fallback, listOf(fill)))
            val adjusted = scheme(true).nick("alice", fallback, listOf(fill))
            assertNotEquals(authored, adjusted)
            assertTrue(contrastRatio(adjusted, fill) >= 6.99)
            assertEquals(authored, scheme(true).avatar("alice"))
            assertEquals(
                authored,
                resolveNickColor(
                    "alice",
                    dark,
                    true,
                    NickColorPalette.THEME,
                    emptyMap(),
                    fallback,
                    backgrounds = listOf(fill),
                    identityPalette = listOf(authored),
                ),
            )
        }
    }

    @Test
    fun disabledNickColorsPreserveOnlyAuthoredFallbackWithoutAdjustment() {
        for (dark in listOf(false, true)) {
            val authored = if (dark) Color(0xFF252525) else Color(0xFFE0E0E0)
            val fill = if (dark) Color(0xFF202020) else Color(0xFFE8E8E8)
            for (palette in NickColorPalette.entries) {
                fun scheme(adjust: Boolean) =
                    NickColorScheme(
                        enabled = false,
                        palette = palette,
                        overrides = mapOf("alice" to 210),
                        isDark = dark,
                        identityPalette = listOf(Color.Red),
                        adjustPaletteReadability = adjust,
                    )
                val original = scheme(false)
                assertEquals(authored, original.nick("alice", authored, listOf(fill), fallbackIsAuthored = true))
                assertTrue(original.preservesAuthoredIdentity("alice", fallbackIsAuthored = true))
                assertEquals(
                    ensureContrast(authored, listOf(fill), 7.0),
                    original.nick("alice", authored, listOf(fill)),
                )
                val adjusted = scheme(true).nick("alice", authored, listOf(fill), fallbackIsAuthored = true)
                assertNotEquals(authored, adjusted)
                assertTrue(contrastRatio(adjusted, fill) >= 6.99)
            }
        }
    }

    @Test
    fun readabilityPolicyDoesNotChangeGeneratedOrOverriddenIdentities() {
        for (dark in listOf(false, true)) {
            val fill = if (dark) Color(0xFF202020) else Color(0xFFE8E8E8)
            for (palette in NickColorPalette.entries) {
                for (overrides in listOf(emptyMap(), mapOf("alice" to 210))) {
                    for (identities in listOf(emptyList(), listOf(Color(0xFF999999)))) {
                        if (palette == NickColorPalette.THEME && overrides.isEmpty() && identities.isNotEmpty()) continue

                        fun scheme(adjust: Boolean) =
                            NickColorScheme(
                                true,
                                palette,
                                overrides,
                                dark,
                                textBackgrounds = listOf(fill),
                                identityPalette = identities,
                                adjustPaletteReadability = adjust,
                            )
                        val original = scheme(false)
                        val adjusted = scheme(true)
                        assertEquals(original.nick("alice", fallback), adjusted.nick("alice", fallback))
                        assertEquals(original.nick("alice", fallback, listOf(fill)), adjusted.nick("alice", fallback, listOf(fill)))
                        assertTrue(contrastRatio(original.nick("alice", fallback), fill) >= 4.49)
                    }
                }
            }
        }
    }

    @Test
    fun customIdentities_preserveAuthoredFillsAndReadableNickHuesAcrossModes() {
        for (f in customFixtures(listOf("#A84274", "#358F87", "#8664BB"))) {
            val scheme = nickScheme(f)
            val used = crowd.map { scheme.avatar(it) }.toSet()
            assertEquals(f.identityPalette.toSet(), used)
            for (nick in crowd) {
                val fill = scheme.avatar(nick)
                val text = scheme.nick(nick, fallback)
                assertEquals(0f, hueGap(fill.toOklch().hue, text.toOklch().hue), 2f)
                for (background in f.backgrounds) {
                    assertTrue("custom/$nick unreadable on $background", contrastRatio(text, background) >= 4.49)
                }
            }
        }
    }

    @Test
    fun customEmptyIdentities_useReadableAccentDerivedHueWheelAcrossModes() {
        for (f in customFixtures()) {
            val scheme = nickScheme(f)
            val fills = crowd.map { scheme.avatar(it) }.toSet()
            assertTrue("automatic identities must expand beyond three accents", fills.size > f.accents.size)
            val differentAccents =
                NickColorScheme(
                    enabled = true,
                    palette = NickColorPalette.THEME,
                    overrides = emptyMap(),
                    isDark = f.isDark,
                    textBackgrounds = f.backgrounds,
                    themeColors = listOf(Color.Cyan, Color.Blue, Color.Green),
                )
            assertNotEquals(scheme.avatar("alice"), differentAccents.avatar("alice"))
            for (nick in crowd) {
                val text = scheme.nick(nick, fallback)
                assertTrue(text.alpha == 1f)
                assertEquals(scheme.mention(nick), text)
                for (background in f.backgrounds) {
                    assertTrue("automatic/$nick unreadable on $background", contrastRatio(text, background) >= 4.49)
                }
            }
        }
    }

    @Test
    fun customIdentities_doNotOverrideClassicVividOrExplicitNickChoices() {
        for (f in customFixtures(listOf("#A84274", "#358F87", "#8664BB"))) {
            for (palette in listOf(NickColorPalette.CLASSIC, NickColorPalette.VIVID)) {
                val custom = nickScheme(f, palette)
                val withoutCustom = nickScheme(ThemeFixture(emptyList(), f.backgrounds, emptyList(), f.isDark), palette)
                for (nick in nicks) {
                    assertEquals(withoutCustom.avatar(nick), custom.avatar(nick))
                    assertEquals(withoutCustom.nick(nick, fallback), custom.nick(nick, fallback))
                    assertTrue(f.backgrounds.all { contrastRatio(custom.nick(nick, fallback), it) >= 4.49 })
                }
            }
            for (palette in NickColorPalette.entries) {
                val overridden = nickScheme(f, palette, mapOf("alice" to 210))
                assertEquals(hueFill(210, f.isDark, palette, f.accents), overridden.avatar(" Alice "))
                val text = overridden.nick(" Alice ", fallback)
                assertEquals(hueColor(210, f.isDark, palette, f.accents, f.backgrounds), text)
                if (palette == NickColorPalette.THEME) {
                    assertEquals(0f, hueGap(210f, text.toOklch().hue), 2f)
                }
                assertTrue(f.backgrounds.all { contrastRatio(text, it) >= 4.49 })
                val disabled = nickScheme(f, palette, mapOf("alice" to 210), enabled = false)
                assertEquals(fallback, disabled.nick("alice", fallback))
                assertEquals(overridden.avatar("alice"), disabled.avatar("alice"))
            }
        }
    }

    @Test
    fun exactOverrideUsesChosenAvatarColorAndReadableTextInEachMode() {
        for (dark in listOf(false, true)) {
            val background = if (dark) Color(0xFF303840) else Color(0xFFE7EDF3)
            val argb = if (dark) 0xFF384C60.toInt() else 0xFFC6D6E6.toInt()
            val backgrounds = listOf(background)
            val overrides = mapOf("alice" to NickColorOverride.fromArgb(argb))
            for (palette in NickColorPalette.entries) {
                val scheme = NickColorScheme(true, palette, overrides, dark, backgrounds, themeColors, listOf(Color.Red))
                assertEquals(Color(argb), scheme.avatar(" Alice "))
                val text = scheme.nick(" Alice ", fallback)
                assertEquals(text, scheme.mention("alice"))
                assertTrue(contrastRatio(text, background) >= 4.49)
                assertTrue(contrastRatio(scheme.nick("alice", fallback, backgrounds), background) >= 6.99)
                assertEquals(text, resolveNickColor("alice", dark, true, palette, overrides, fallback, themeColors, backgrounds))

                val disabled = NickColorScheme(false, palette, overrides, dark, backgrounds)
                assertEquals(fallback, disabled.nick("alice", fallback))
                assertEquals(Color(argb), disabled.avatar("alice"))
            }
        }
    }

    private fun themeNick(
        nick: String,
        f: ThemeFixture,
    ): Color =
        paletteNickColor(
            nick,
            f.isDark,
            NickColorPalette.THEME,
            f.accents,
            f.backgrounds,
            f.identityPalette,
            adjustPaletteReadability = true,
        )

    /** Circular hue distance in degrees, so 359.8 and 0.0 read as the same hue. */
    private fun hueGap(
        a: Float,
        b: Float,
    ): Float {
        val raw = abs(a - b) % 360f
        return min(raw, 360f - raw)
    }

    /** CIE Lab deltaE 76; below 12 two identity colors are too close to tell apart at a glance. */
    private fun deltaE(
        a: Color,
        b: Color,
    ): Double {
        fun lab(c: Color): Triple<Double, Double, Double> {
            fun lin(v: Float): Double {
                val d = v.toDouble()
                return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
            }
            val r = lin(c.red)
            val g = lin(c.green)
            val bl = lin(c.blue)
            val x = (r * .4124 + g * .3576 + bl * .1805) / .95047
            val y = r * .2126 + g * .7152 + bl * .0722
            val z = (r * .0193 + g * .1192 + bl * .9505) / 1.08883

            fun f(t: Double) = if (t > .008856) cbrt(t) else 7.787 * t + 16.0 / 116.0
            return Triple(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
        }
        val (l1, a1, b1) = lab(a)
        val (l2, a2, b2) = lab(b)
        return sqrt((l1 - l2).pow(2) + (a1 - a2).pow(2) + (b1 - b2).pow(2))
    }

    private val fixedPresets = ColorThemePreset.entries.filter { it.isFixedPalette }

    @Test
    fun avatarFill_isExactlyOneOfTheThemesOwnColors() {
        // The disc has no contrast obligation, so it uses the published color untouched.
        for (preset in fixedPresets) {
            val f = fixture(preset)
            for (nick in crowd) {
                val fill =
                    paletteNickFill(
                        nick,
                        f.isDark,
                        NickColorPalette.THEME,
                        f.accents,
                        f.identityPalette,
                    )
                assertTrue("$preset/$nick fill is not a declared color", fill in f.identityPalette)
            }
        }
    }

    @Test
    fun nickText_staysOnAHueTheThemeDeclares() {
        // The contrast fix may move lightness, but never hue -- this is the regression that made
        // generated colors look foreign to the theme.
        for (preset in fixedPresets) {
            val f = fixture(preset)
            val declared = f.identityPalette.map { it.toOklch().hue }
            for (nick in crowd) {
                val hue = themeNick(nick, f).toOklch().hue
                val drift = declared.minOf { hueGap(hue, it) }
                assertTrue("$preset/$nick drifted $drift deg off every declared hue", drift < 2f)
            }
        }
    }

    @Test
    fun nickText_meetsContrastOnEverySurface() {
        for (preset in fixedPresets) {
            val f = fixture(preset)
            for (nick in crowd) {
                val worst = f.backgrounds.minOf { contrastRatio(themeNick(nick, f), it) }
                assertTrue("$preset/$nick resolved to contrast $worst", worst >= 4.49)
            }
        }
    }

    @Test
    fun nickColors_stayDistinguishableEvenOnMutedThemes() {
        // Gruvbox, Zenburn and Nord have the narrowest palettes; they are the worst case.
        for (preset in listOf(
            ColorThemePreset.GRUVBOX_DARK,
            ColorThemePreset.ZENBURN,
            ColorThemePreset.NORD,
        )) {
            val f = fixture(preset)
            val colors = crowd.map { themeNick(it, f) }
            var confusable = 0
            for (i in colors.indices) {
                for (j in i + 1 until colors.size) {
                    if (colors[i] != colors[j] && deltaE(colors[i], colors[j]) < 12) confusable++
                }
            }
            assertTrue(
                "$preset: $confusable confusable pairs of ${crowd.size * (crowd.size - 1) / 2}",
                confusable < 350,
            )
        }
    }

    @Test
    fun everyDeclaredColorGetsUsed() {
        // The hash must reach the whole palette, not favour a prefix of it.
        val f = fixture(ColorThemePreset.GRUVBOX_DARK)
        val used =
            (0 until 4000)
                .map {
                    paletteNickFill("user$it", f.isDark, NickColorPalette.THEME, f.accents, f.identityPalette)
                }.toSet()
        assertEquals(f.identityPalette.size, used.size)
    }

    @Test
    fun themesWithoutAPublishedPalette_fallBackToTheHueWheel() {
        // Material light/dark/AMOLED and dynamic color declare only three accents, so they keep the
        // wheel -- Material's own way of widening a three-accent scheme.
        assertTrue(themeIdentityPalette(ColorThemePreset.DARK).isEmpty())
        val slots = mutableSetOf<Int>()
        val tiers = mutableSetOf<Int>()
        repeat(2000) { i ->
            val coord = nickCoord("user$i")
            slots += coord.hueSlot
            tiers += coord.tier
        }
        assertEquals(NICK_HUE_SLOTS, slots.size)
        assertEquals(NICK_TIER_COUNT, tiers.size)
    }

    @Test
    fun contrastFix_preservesHue() {
        // ensureContrast lerps toward black or white, which strips chroma and shifts hue. The
        // lightness walk must leave the hue alone.
        val f = fixture(ColorThemePreset.SOLARIZED_LIGHT)
        for (hue in 0 until 360 step 15) {
            val fixed = contrastSafeIdentity(0.85f, 0.9f, hue.toFloat(), f.backgrounds, isDark = false)
            assertEquals("hue $hue drifted", 0f, hueGap(hue.toFloat(), fixed.toOklch().hue), 1.5f)
        }
    }

    @Test
    fun identityRamp_staysOnTheFillHue() {
        for (hue in 0 until 360 step 30) {
            val fill = oklchColorAt(0.55f, 0.9f, hue.toFloat())
            val ramp = identityRamp(fill)
            assertEquals(0f, hueGap(hue.toFloat(), ramp.shade.toOklch().hue), 2f)
            assertEquals(0f, hueGap(hue.toFloat(), ramp.highlight.toOklch().hue), 2f)
            assertTrue(ramp.shade.toOklch().lightness < ramp.highlight.toOklch().lightness)
        }
    }
}
