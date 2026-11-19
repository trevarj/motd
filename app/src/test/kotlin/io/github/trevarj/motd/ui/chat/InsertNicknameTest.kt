package io.github.trevarj.motd.ui.chat

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import io.github.trevarj.motd.irc.format.IRC_BOLD
import io.github.trevarj.motd.irc.format.IRC_COLOR
import io.github.trevarj.motd.irc.format.IrcColor
import io.github.trevarj.motd.irc.format.IrcFormatState
import io.github.trevarj.motd.irc.format.parseIrcFormatting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsertNicknameTest {
    @Test fun beginning_and_blank_prefix_use_colon_without_consuming_suffix() {
        for (prefix in listOf("", " ", "\n\t")) {
            val result = insertNickname(TextFieldValue(prefix + "!", TextRange(prefix.length)), "alice")
            assertEquals(prefix + "alice: !", result.text)
            assertEquals(TextRange(prefix.length + 7), result.selection)
        }
    }

    @Test fun middle_cursor_preserves_adjacent_punctuation_and_suffix() {
        val result = insertNickname(TextFieldValue("hello,! world", TextRange(6)), "alice")
        assertEquals("hello,alice! world", result.text)
        assertEquals(TextRange(11), result.selection)
    }

    @Test fun forward_and_reverse_selections_replace_the_same_range() {
        for (selection in listOf(TextRange(4, 7), TextRange(7, 4))) {
            val result = insertNickname(TextFieldValue("hey bob!", selection), "alice")
            assertEquals("hey alice!", result.text)
            assertEquals(TextRange(9), result.selection)
        }
    }

    @Test fun selection_at_message_start_uses_colon() {
        val result = insertNickname(TextFieldValue("bob!", TextRange(3, 0)), "alice")
        assertEquals("alice: !", result.text)
        assertEquals(TextRange(7), result.selection)
    }

    @Test fun explicit_newline_after_text_and_visual_wrapping_never_reset_addressing() {
        for (prefix in listOf("hello\n", "hello world ".repeat(40))) {
            val result = insertNickname(TextFieldValue(prefix + "tail", TextRange(prefix.length)), "alice")
            assertEquals(prefix + "alice tail", result.text)
            assertEquals(TextRange(prefix.length + 6), result.selection)
        }
    }

    @Test fun formatting_controls_are_not_message_content_and_remain_around_insertion() {
        val raw = "${IRC_BOLD} ${IRC_BOLD}!"
        val result = insertNickname(TextFieldValue(raw, TextRange(2)), "alice")
        assertEquals("${IRC_BOLD} alice: $IRC_BOLD!", result.text)
        val parsed = parseIrcFormatting(result.text)
        assertTrue(parsed.stateAtVisible(1).bold)
        assertTrue(!parsed.stateAtVisible(parsed.visibleText.lastIndex).bold)
        assertEquals(TextRange(9), result.selection)
    }

    @Test fun selection_preserves_controls_needed_by_untouched_suffix() {
        val raw = "hey b${IRC_BOLD}ob!"
        val result = insertNickname(TextFieldValue(raw, TextRange(4, 8)), "alice")
        assertEquals("hey alice$IRC_BOLD!", result.text)
        val parsed = parseIrcFormatting(result.text)
        assertTrue(parsed.stateAtVisible(parsed.visibleText.lastIndex).bold)
        assertEquals(TextRange(9), result.selection)
    }

    @Test fun moved_foreground_color_does_not_consume_the_unselected_suffix() {
        val raw = "hey a${IRC_COLOR}04l,05 tail"
        val suffixStart = raw.indexOf(',')
        for (selection in listOf(TextRange(4, suffixStart), TextRange(suffixStart, 4))) {
            val result = insertNickname(TextFieldValue(raw, selection), "alice")
            val parsed = parseIrcFormatting(result.text)

            assertEquals("hey alice,05 tail", parsed.visibleText)
            assertEquals(TextRange(9), result.selection)
            assertTrue(parsed.stateAtVisible(8).isDefault)
            assertEquals(IrcFormatState(foreground = IrcColor.Numeric(4)), parsed.stateAtVisible(9))
            assertEquals(parsed.stateAtVisible(9), parsed.stateAtVisible(parsed.visibleText.lastIndex))
        }
    }

    @Test fun moved_color_reset_does_not_consume_unselected_digits() {
        val raw = "hey ${IRC_COLOR}04a${IRC_COLOR}l05 tail"
        val result = insertNickname(TextFieldValue(raw, TextRange(7, raw.indexOf("05"))), "alice")
        val parsed = parseIrcFormatting(result.text)

        assertEquals("hey alice 05 tail", parsed.visibleText)
        assertEquals(TextRange(13), result.selection)
        assertEquals(IrcFormatState(foreground = IrcColor.Numeric(4)), parsed.stateAtVisible(8))
        assertTrue(parsed.stateAtVisible(10).isDefault)
    }

    @Test fun annotated_styles_on_surrounding_text_are_preserved() {
        val style = SpanStyle(fontWeight = FontWeight.Bold)
        val text =
            buildAnnotatedString {
                append("hey !")
                addStyle(style, 0, 3)
                addStyle(style, 4, 5)
            }
        val result = insertNickname(TextFieldValue(text, TextRange(4)), "alice")
        assertEquals("hey alice!", result.text)
        assertEquals(
            style,
            result.annotatedString.spanStyles
                .first()
                .item,
        )
        assertEquals(
            0,
            result.annotatedString.spanStyles
                .first()
                .start,
        )
        assertEquals(
            3,
            result.annotatedString.spanStyles
                .first()
                .end,
        )
        assertEquals(
            9,
            result.annotatedString.spanStyles
                .last()
                .start,
        )
        assertEquals(
            10,
            result.annotatedString.spanStyles
                .last()
                .end,
        )
    }

    @Test fun insertion_after_word_adds_leading_boundary_and_preserves_closing_punctuation() {
        val result = insertNickname(TextFieldValue("hi!", TextRange(2)), "alice")
        assertEquals("hi alice!", result.text)
        assertEquals(TextRange(8), result.selection)
    }

    @Test fun insertion_reuses_following_whitespace_without_removing_it() {
        val result = insertNickname(TextFieldValue("hi  tail", TextRange(2)), "alice")
        assertEquals("hi alice  tail", result.text)
        assertEquals(TextRange(8), result.selection)
    }
}
