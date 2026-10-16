package io.github.trevarj.motd.ui.components

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.TimeFormat
import io.github.trevarj.motd.data.prefs.isDark
import io.github.trevarj.motd.ui.theme.MotdDarkScheme
import io.github.trevarj.motd.ui.theme.MotdDarkThemeTextScheme
import io.github.trevarj.motd.ui.theme.MotdLightScheme
import io.github.trevarj.motd.ui.theme.MotdLightThemeTextScheme
import io.github.trevarj.motd.ui.theme.accessibleColorScheme
import io.github.trevarj.motd.ui.theme.contrastRatio
import io.github.trevarj.motd.ui.theme.ensureContrast
import io.github.trevarj.motd.ui.theme.fixedThemeScheme
import io.github.trevarj.motd.ui.theme.readingInk
import io.github.trevarj.motd.ui.theme.semanticColors
import io.github.trevarj.motd.ui.theme.withTrueBlackSurfaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBubbleLayoutTest {
    @Test fun bubbleWidth_usesItsPaneAndCapsWideLayouts() {
        assertEquals(344, bubbleMaxWidthPx(420, 560))
        assertEquals(560, bubbleMaxWidthPx(1_000, 560))
        assertEquals(0, bubbleMaxWidthPx(0, 560))
    }

    @Test fun resolveIs24Hour_autoFollowsTheDeviceSetting() {
        assertTrue(resolveIs24Hour(TimeFormat.AUTO, deviceIs24 = true))
        assertFalse(resolveIs24Hour(TimeFormat.AUTO, deviceIs24 = false))
    }

    @Test fun resolveIs24Hour_h12AlwaysResolvesToFalse() {
        assertFalse(resolveIs24Hour(TimeFormat.H12, deviceIs24 = true))
        assertFalse(resolveIs24Hour(TimeFormat.H12, deviceIs24 = false))
    }

    @Test fun resolveIs24Hour_h24AlwaysResolvesToTrue() {
        assertTrue(resolveIs24Hour(TimeFormat.H24, deviceIs24 = true))
        assertTrue(resolveIs24Hour(TimeFormat.H24, deviceIs24 = false))
    }

    @Test fun bubbleRoles_targetStrongerReadingContrastAcrossPalettes() {
        messageContrastSchemes().forEach { (name, scheme, dark) ->
            val semantic = semanticColors(scheme, dark)
            for (kind in listOf(MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION)) {
                for (self in listOf(false, true)) {
                    for (mention in listOf(false, true)) {
                        val role = messageBubbleRoleColors(scheme, self, mention, kind, semantic)
                        assertReadingContrast("$name $kind self=$self mention=$mention", role.content, role.container)
                    }
                }
            }
        }
    }

    @Test fun readingInk_usesSelectedTextPolicyAndFitsActualFills() {
        for (useThemeTextColors in listOf(false, true)) {
            messageContrastSchemes(useThemeTextColors).forEach { (name, scheme, dark) ->
                val seed = readingInk(scheme, useThemeTextColors)
                val expected =
                    when {
                        useThemeTextColors -> scheme.onBackground
                        dark -> Color(0xFFF5F5F5)
                        else -> Color(0xFF171717)
                    }
                assertEquals(name, expected, seed)
                val semantic = semanticColors(scheme, dark)
                for (kind in listOf(MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION)) {
                    for (self in listOf(false, true)) {
                        for (mention in listOf(false, true)) {
                            val fill = messageBubbleRoleColors(scheme, self, mention, kind, semantic).container
                            assertReadingContrast("$name themed=$useThemeTextColors $kind self=$self mention=$mention", ensureContrast(seed, listOf(fill), MESSAGE_TEXT_CONTRAST), fill)
                        }
                    }
                }
            }
        }
    }

    @Test fun dimmedRoles_blendOpaqueFillAndNormalInkWithReadableFloorAcrossPalettes() {
        messageContrastSchemes().forEach { (name, scheme, dark) ->
            for (kind in listOf(MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION)) {
                val normal = messageBubbleRoleColors(scheme, false, false, kind, semanticColors(scheme, dark))
                val dimmed = messageBubbleRoleColors(scheme, false, false, kind, semanticColors(scheme, dark), dimmed = true)
                assertEquals("$name $kind fill", lerp(normal.container.compositeOver(scheme.background), scheme.background, 0.35f), dimmed.container)
                assertEquals("$name $kind ink", ensureContrast(lerp(normal.content, dimmed.container, 0.35f), listOf(dimmed.container), 4.5), dimmed.content)
                assertTrue("$name $kind opaque backing", dimmed.container.alpha == 1f)
                assertTrue("$name $kind dimmed contrast", contrastRatio(dimmed.content, dimmed.container) >= 4.5 - 0.00001)
                assertReadingContrast("$name $kind normal unchanged", normal.content, normal.container)
            }
        }
    }

    @Test fun dimmedRichSpans_keepTheLowerContrastTargetAcrossAllInlineRenderers() {
        val fill = Color(0xFFEEEEEE)
        val body = messageContentColor(Color.Black, fill, true)
        val raw = "hello https://example.com @bob `code` \u000315grey\u000f"
        val bodies =
            listOf(
                linkifiedBody(raw, Color.Blue, true, { Color.Magenta }, Color.LightGray, Color.Black, fill, body, DIMMED_MESSAGE_TEXT_CONTRAST),
                buildCompactLine("alice", raw, MessageKind.NOTICE, Color.Red, body, Color.Blue, Color.Unspecified, mentionColor = { Color.Magenta }, codeBackground = Color.LightGray, codeColor = Color.Black, containerColor = fill, minimumContrast = DIMMED_MESSAGE_TEXT_CONTRAST),
                buildActionLine("alice", raw, Color.Red, Color.Red, body, Color.Blue, mentionColor = { Color.Magenta }, codeBackground = Color.LightGray, codeColor = Color.Black, containerColor = fill, minimumContrast = DIMMED_MESSAGE_TEXT_CONTRAST),
            )
        for (text in bodies) {
            text.spanStyles.filter { it.item.color != Color.Unspecified }.forEach { span ->
                val backing = if (span.item.background == Color.Unspecified) fill else span.item.background.compositeOver(fill)
                assertTrue("${text.text} $span", contrastRatio(span.item.color, backing) >= 4.5 - 0.00001)
            }
        }
    }

    @Test fun midToneBubble_keepsItsFillAndUsesReadableFallback() {
        val fill = Color(0xFF777777)
        val scheme = MotdLightScheme.copy(primary = fill, primaryContainer = fill)
        val role = messageBubbleRoleColors(scheme, true, false, MessageKind.PRIVMSG, semanticColors(scheme, false))
        assertEquals(fill, role.container)
        assertTrue(contrastRatio(Color.Black, fill) < 7.0 && contrastRatio(Color.White, fill) < 7.0)
        assertTrue(contrastRatio(role.content, fill) >= 4.5)
        assertEquals(Color.Black, ensureContrast(readingInk(scheme, useThemeTextColors = false), listOf(fill), MESSAGE_TEXT_CONTRAST))
    }
}

internal fun messageContrastSchemes(useThemeTextColors: Boolean = false): List<Triple<String, ColorScheme, Boolean>> {
    val fixed =
        listOf(
            Triple("Light", if (useThemeTextColors) MotdLightThemeTextScheme else MotdLightScheme, false),
            Triple("Dark", if (useThemeTextColors) MotdDarkThemeTextScheme else MotdDarkScheme, true),
        ) +
            ColorThemePreset.entries.mapNotNull { preset ->
                fixedThemeScheme(preset, useThemeTextColors)?.let { Triple(preset.name, it, preset.isDark) }
            }
    // Representative Material You roles, normalized at the same boundary as Android dynamic colors.
    val dynamic =
        listOf(
            Triple(
                "Dynamic green light",
                accessibleColorScheme(
                    lightColorScheme(
                        primary = Color(0xFF4D662B),
                        primaryContainer = Color(0xFFCDEDA3),
                        background = Color(0xFFF7FAEF),
                        onBackground = Color(0xFF1B1C17),
                        surface = Color(0xFFF7FAEF),
                        surfaceVariant = Color(0xFFE1E4D5),
                        surfaceContainerHigh = Color(0xFFE6E9DF),
                        onSurface = Color(0xFF1B1C17),
                        onSurfaceVariant = Color(0xFF44483C),
                    ),
                    dark = false,
                    useThemeTextColors = useThemeTextColors,
                ),
                false,
            ),
            Triple(
                "Dynamic purple dark",
                accessibleColorScheme(
                    darkColorScheme(
                        primary = Color(0xFFCFBCFF),
                        primaryContainer = Color(0xFF4F378A),
                        background = Color(0xFF14121B),
                        surface = Color(0xFF14121B),
                        onBackground = Color(0xFFE6E0E9),
                        surfaceVariant = Color(0xFF49454F),
                        surfaceContainerHigh = Color(0xFF2B2930),
                        onSurface = Color(0xFFE6E0E9),
                        onSurfaceVariant = Color(0xFFCAC4D0),
                    ),
                    dark = true,
                    useThemeTextColors = useThemeTextColors,
                ),
                true,
            ),
        )
    return (fixed + dynamic).flatMap { (name, scheme, dark) ->
        listOf(Triple(name, scheme, dark)) +
            if (dark) listOf(Triple("$name true black", scheme.withTrueBlackSurfaces(), true)) else emptyList()
    }
}

internal fun assertReadingContrast(
    label: String,
    ink: Color,
    fill: Color,
) {
    val attainable = maxOf(contrastRatio(Color.Black, fill), contrastRatio(Color.White, fill))
    val minimum = if (attainable >= 7.0) 7.0 else 4.5
    assertTrue("$label contrast ${contrastRatio(ink, fill)} (target $minimum)", contrastRatio(ink, fill) >= minimum - 0.00001)
}
