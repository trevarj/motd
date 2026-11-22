package io.github.trevarj.motd.irc.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IrcEditorDocumentTest {
    @Test
    fun `clear formatting preserves visible text and selection`() {
        val raw = "$IRC_BOLD${IRC_COLOR}04,01hello\nworld$IRC_RESET"
        val (document, selection) = IrcEditorDocument.fromRaw(raw, 0, raw.length)
        val cleared = document.clearFormatting(selection.first, selection.last)
        val serialized = cleared.toRawValue(selection.first, selection.last)

        assertEquals("hello\nworld", cleared.text)
        assertTrue(cleared.states.all(IrcFormatState::isDefault))
        assertEquals("hello\nworld", plainIrcText(serialized.text))
        assertEquals(selection.first, parseIrcFormatting(serialized.text).visibleOffset(serialized.selectionStart))
        assertEquals(selection.last, parseIrcFormatting(serialized.text).visibleOffset(serialized.selectionEnd))
    }

    @Test
    fun `pending color preserves cursor and styles future input`() {
        val document =
            IrcEditorDocument("abcd", List(4) { IrcFormatState() })
                .moveCaret(2)
                .applyColors(2, 2, foreground = IrcColor.Numeric(4), background = IrcColor.Numeric(1))
                .replaceText("abXcd")
        val raw = document.toRawValue(3, 3)
        val reparsed = parseIrcFormatting(raw.text)

        assertEquals("abXcd", document.text)
        assertEquals(IrcColor.Numeric(4), document.states[2].foreground)
        assertEquals(IrcColor.Numeric(1), document.states[2].background)
        assertEquals(3, reparsed.visibleOffset(raw.selectionStart))
        assertEquals(3, reparsed.visibleOffset(raw.selectionEnd))
    }

    @Test
    fun `delete and replace preserve unaffected formatting`() {
        val bold = IrcFormatState(bold = true)
        val document = IrcEditorDocument("abcDEF", List(3) { bold } + List(3) { IrcFormatState() })
        val replaced = document.moveCaret(3).replaceText("abXYDEF")
        val deleted = replaced.replaceText("aXYDEF")

        assertEquals("aXYDEF", deleted.text)
        assertTrue(deleted.states.take(3).all { it.bold })
        assertTrue(deleted.states.drop(3).all { it.isDefault })
    }

    @Test
    fun `collapsed clear changes pending state without deleting text`() {
        val bold = IrcFormatState(bold = true)
        val document = IrcEditorDocument("hello", List(5) { bold }, pendingState = bold)
        val cleared = document.clearFormatting(2, 2).replaceText("heXllo")

        assertEquals("heXllo", cleared.text)
        assertTrue(cleared.states[2].isDefault)
        assertTrue(cleared.states[1].bold)
        assertTrue(cleared.states[3].bold)
    }
}
