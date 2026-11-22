package io.github.trevarj.motd.irc.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IrcFormattingTest {
    @Test
    fun formattingControlDetectionSkipsPlainPayloads() {
        assertFalse(containsIrcFormatting("plain é 😀"))
        listOf(
            IRC_BOLD,
            IRC_COLOR,
            IRC_HEX_COLOR,
            IRC_MONOSPACE,
            IRC_RESET,
            IRC_REVERSE,
            IRC_ITALIC,
            IRC_STRIKETHROUGH,
            IRC_UNDERLINE,
        ).forEach { control -> assertTrue(containsIrcFormatting("a${control}b")) }
    }

    @Test
    fun parsesEveryToggleResetAndOffsets() {
        val raw =
            "$IRC_BOLD${IRC_ITALIC}a$IRC_UNDERLINE" +
                "b$IRC_STRIKETHROUGH${IRC_MONOSPACE}c$IRC_RESET!"
        val parsed = parseIrcFormatting(raw)

        assertEquals("abc!", parsed.visibleText)
        assertTrue(parsed.runs[0].state.bold)
        assertTrue(parsed.runs[0].state.italic)
        assertTrue(parsed.runs[1].state.underline)
        assertTrue(parsed.runs[2].state.strikethrough)
        assertTrue(parsed.runs[2].state.monospace)
        assertTrue(
            parsed.runs
                .last()
                .state.isDefault,
        )
        assertEquals(0, parsed.visibleOffset(2))
        assertEquals(2, parsed.rawOffset(0))
        assertEquals(raw.length, parsed.rawOffset(parsed.visibleText.length))
    }

    @Test
    fun parsesNumericHexReverseAndDefaultColor() {
        val parsed =
            parseIrcFormatting(
                "$IRC_COLOR" + "04,02red$IRC_REVERSE reversed$IRC_COLOR" +
                    "99 default $IRC_HEX_COLOR" + "12ab34,abcdefhex",
            )

        assertEquals("red reversed default hex", parsed.visibleText)
        assertEquals(IrcColor.Numeric(4), parsed.runs[0].state.foreground)
        assertEquals(IrcColor.Numeric(2), parsed.runs[0].state.background)
        assertTrue(parsed.runs[1].state.reverse)
        assertEquals(null, parsed.runs[2].state.foreground)
        assertEquals(IrcColor.Numeric(2), parsed.runs[2].state.background)
        assertEquals(
            IrcColor.Hex(0x12ab34),
            parsed.runs
                .last()
                .state.foreground,
        )
        assertEquals(
            IrcColor.Hex(0xabcdef),
            parsed.runs
                .last()
                .state.background,
        )
    }

    @Test
    fun malformedColorsDoNotEatVisibleText() {
        assertEquals(",x", plainIrcText("$IRC_COLOR,x"))
        assertEquals("1", plainIrcText("$IRC_HEX_COLOR" + "1"))
        assertEquals(",12x", plainIrcText("$IRC_HEX_COLOR" + "abcdef,12x"))
        assertEquals(",x", plainIrcText("$IRC_COLOR" + "04,x"))
    }

    @Test
    fun selectedToggleAppliesMixedAndRemovesWholeStyle() {
        val mixed = "$IRC_BOLD" + "a$IRC_BOLD" + "b"
        val applied = toggleIrcStyle(mixed, 0, mixed.length, IrcTextStyle.BOLD)
        assertTrue(parseIrcFormatting(applied.text).runs.all { it.state.bold })

        val removed = toggleIrcStyle(applied.text, 0, applied.text.length, IrcTextStyle.BOLD)
        assertTrue(parseIrcFormatting(removed.text).runs.none { it.state.bold })
        assertEquals("ab", plainIrcText(removed.text))
    }

    @Test
    fun collapsedToggleInsertsPairWithCursorInside() {
        val edit = toggleIrcStyle("ab", 1, 1, IrcTextStyle.ITALIC)
        assertEquals("a$IRC_ITALIC$IRC_ITALIC" + "b", edit.text)
        assertEquals(2, edit.selectionStart)
    }

    @Test
    fun colorsUseTwoDigitsAndReadableBackgroundFallback() {
        val edit = applyIrcColors("42", 0, 2, foreground = 4, background = 8)
        assertTrue(edit.text.startsWith("$IRC_COLOR" + "04,08"))
        assertEquals("42", plainIrcText(edit.text))

        val backgroundOnly = applyIrcColors("x", 0, 1, foreground = null, background = 1)
        assertEquals(
            IrcColor.Numeric(0),
            parseIrcFormatting(backgroundOnly.text)
                .runs
                .single()
                .state.foreground,
        )
        assertEquals(
            IrcColor.Numeric(1),
            parseIrcFormatting(backgroundOnly.text)
                .runs
                .single()
                .state.background,
        )
    }

    @Test
    fun clearSelectionRetainsFormattingOutside() {
        val raw = "$IRC_BOLD" + "abc$IRC_BOLD"
        val parsed = parseIrcFormatting(raw)
        val edit = clearIrcFormatting(raw, parsed.rawOffset(1), parsed.rawOffset(2))
        val result = parseIrcFormatting(edit.text)

        assertTrue(result.stateAtVisible(0).bold)
        assertFalse(result.stateAtVisible(1).bold)
        assertTrue(result.stateAtVisible(2).bold)
    }

    @Test
    fun splitComponentsHandleMultibyteCodePointsAndTransitions() {
        val raw = "$IRC_BOLD" + "é 😀 words " + IRC_BOLD + "plain"
        val components = splitIrcFormattedUtf8(raw, 11)

        assertTrue(components.size > 1)
        assertTrue(components.all { it.toByteArray().size <= 11 })
        assertEquals("é 😀 words plain", components.joinToString("") { plainIrcText(it) })
        assertTrue(components.all { parseIrcFormatting(it).activeState.isDefault })
    }

    @Test
    fun markdownAndColorTokensBecomeIrcFormatting() {
        val raw =
            markdownToIrcFormatting(
                "**bold** _italic_ __under__ ~~strike~~ `code` \$cred,lightgreencolor\$c \\**plain\\**",
            )
        val parsed = parseIrcFormatting(raw)

        assertEquals("bold italic under strike code color **plain**", parsed.visibleText)
        assertTrue(parsed.stateAtVisible(0).bold)
        assertTrue(parsed.stateAtVisible(5).italic)
        assertTrue(parsed.stateAtVisible(12).underline)
        assertTrue(parsed.stateAtVisible(18).strikethrough)
        assertTrue(parsed.stateAtVisible(25).monospace)
        assertEquals(IrcColor.Numeric(4), parsed.stateAtVisible(30).foreground)
        assertEquals(IrcColor.Numeric(9), parsed.stateAtVisible(30).background)
        assertTrue(parsed.stateAtVisible(parsed.visibleText.lastIndex).isDefault)
    }

    @Test
    fun splitComponentsFitAndRenderIndependently() {
        val raw = "$IRC_BOLD" + "hello world" + IRC_BOLD
        val components = splitIrcFormattedUtf8(raw, 9)

        assertTrue(components.size > 1)
        assertTrue(components.all { it.toByteArray().size <= 9 })
        assertEquals("hello world", components.joinToString("") { plainIrcText(it) })
        assertTrue(components.all { component -> parseIrcFormatting(component).runs.all { it.state.bold } })
        assertTrue(components.all { parseIrcFormatting(it).activeState.isDefault })
    }

    @Test
    fun editorAndSplitSerializersPreserveLiteralColorArguments() {
        val text = "abc,05"
        val document = IrcEditorDocument(text, List(text.length) { IrcFormatState() }).applyColors(3, text.length, IrcColor.Numeric(4), null)
        val serialized = document.toRawValue(3, 3)
        val parsed = parseIrcFormatting(serialized.text)

        assertEquals(text, parsed.visibleText)
        assertEquals(document.states, parsed.characterStates)
        assertEquals(3, parsed.visibleOffset(serialized.selectionStart))

        for (maxBytes in listOf(5, 8)) {
            val components = splitIrcFormattedUtf8(serialized.text, maxBytes)
            assertTrue(components.size > 1)
            assertTrue(components.all { it.toByteArray().size <= maxBytes })
            val reparsed = components.map(::parseIrcFormatting)
            assertEquals(text, reparsed.joinToString("") { it.visibleText })
            assertEquals(document.states, reparsed.flatMap { it.characterStates })
        }
    }
}
