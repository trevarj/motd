package io.github.trevarj.motd.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.trevarj.motd.irc.format.IRC_BOLD
import io.github.trevarj.motd.irc.format.IRC_COLOR
import io.github.trevarj.motd.irc.format.IRC_HEX_COLOR
import io.github.trevarj.motd.irc.format.IRC_RESET
import io.github.trevarj.motd.irc.format.IrcColor
import io.github.trevarj.motd.irc.format.parseIrcFormatting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerAutocompleteTest {
    private val members = listOf("alice", "alicia", "bob")

    private fun completions(text: String): List<Completion> =
        autocompleteFor(
            TextFieldValue(text, TextRange(text.length)),
            members,
            recentSpeakers = emptyList(),
            normalize = { it.lowercase() },
        )

    @Test fun single_char_token_yields_no_nick_completions() {
        // "a" is one char and not @-prefixed -> suppressed.
        assertTrue(completions("a").isEmpty())
    }

    @Test fun two_char_token_yields_completions() {
        val out = completions("al").map { it.display }
        assertEquals(listOf("alice", "alicia"), out)
    }

    @Test fun single_char_after_at_sigil_still_completes() {
        val out = completions("@a").map { it.display }
        assertEquals(listOf("alice", "alicia"), out)
    }

    @Test fun command_hints_unaffected_by_min_length() {
        val out = completions("/")
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it.isCommand })
    }

    @Test fun pick_replaces_actual_token_not_selection_or_message_suffix() {
        val result = applyPick(TextFieldValue("hey alicia !", TextRange(5, 6)), "alice")
        assertEquals("hey alice !", result.text)
        assertEquals(TextRange(9), result.selection)
    }

    @Test fun at_prefixed_pick_strips_sigil_and_keeps_beginning_suffix_rule() {
        val result = applyPick(TextFieldValue("@al tail", TextRange(2)), "alice")
        assertEquals("alice:  tail", result.text)
        assertEquals(TextRange(7), result.selection)
    }

    @Test fun picks_after_explicit_newlines_or_visual_wrapping_use_space() {
        for (prefix in listOf("hello\n", "hello world ".repeat(40))) {
            val text = prefix + "@al"
            val result = applyPick(TextFieldValue(text, TextRange(text.length)), "alice")
            assertEquals(prefix + "alice ", result.text)
            assertEquals(TextRange(prefix.length + 6), result.selection)
        }
    }

    @Test fun formatted_token_maps_to_raw_bounds_and_preserves_color_and_punctuation() {
        val raw = "${IRC_COLOR}04@al$IRC_RESET !"
        val value = TextFieldValue(raw, TextRange(5))
        val candidates = autocompleteFor(value, members, emptyList(), { it.lowercase() })
        assertEquals(listOf("alice", "alicia"), candidates.map { it.display })
        val result = applyPick(value, candidates.first().display)
        assertEquals("${IRC_COLOR}04alice: $IRC_RESET !", result.text)
        assertEquals(TextRange(10), result.selection)
        val parsed = parseIrcFormatting(result.text)
        assertEquals(IrcColor.Numeric(4), parsed.stateAtVisible(0).foreground)
        assertTrue(parsed.stateAtVisible(parsed.visibleText.lastIndex).isDefault)
    }

    @Test fun formatting_in_blank_prefix_does_not_prevent_address_colon() {
        val raw = "$IRC_BOLD \n@al$IRC_BOLD"
        val result = applyPick(TextFieldValue(raw, TextRange(raw.length - 1)), "alice")
        assertEquals("$IRC_BOLD \nalice: $IRC_BOLD", result.text)
        assertEquals(TextRange(10), result.selection)
    }

    @Test fun controls_inside_replaced_token_preserve_suffix_style() {
        val raw = "hey a${IRC_BOLD}l tail"
        val result = applyPick(TextFieldValue(raw, TextRange(7)), "alice")
        assertEquals("hey alice$IRC_BOLD tail", result.text)
        assertEquals(TextRange(9), result.selection)
        val parsed = parseIrcFormatting(result.text)
        assertTrue(!parsed.stateAtVisible(4).bold)
        assertTrue(parsed.stateAtVisible(parsed.visibleText.lastIndex).bold)
    }

    @Test fun autocomplete_and_pick_preserve_adjacent_punctuation() {
        val value = TextFieldValue("hey @al! tail", TextRange(7))
        val candidates = autocompleteFor(value, members, emptyList(), { it.lowercase() })
        assertEquals(listOf("alice", "alicia"), candidates.map { it.display })
        val result = applyPick(value, candidates.first().display)
        assertEquals("hey alice! tail", result.text)
        assertEquals(TextRange(9), result.selection)
    }

    @Test fun moved_foreground_color_does_not_consume_the_literal_suffix() {
        for ((control, suffix) in listOf("${IRC_COLOR}04" to ",05 tail", "${IRC_HEX_COLOR}ff0000" to ",abcdef tail")) {
            val raw = "hey a${control}l$suffix"
            val value = TextFieldValue(raw, TextRange(raw.indexOf(',')))
            val candidates = autocompleteFor(value, members, emptyList(), { it.lowercase() })
            val result = applyPick(value, candidates.first().display)
            val original = parseIrcFormatting(raw)
            val parsed = parseIrcFormatting(result.text)

            assertEquals("hey alice$suffix", parsed.visibleText)
            assertEquals(TextRange(9), result.selection)
            assertTrue(parsed.stateAtVisible(8).isDefault)
            for (offset in suffix.indices) {
                assertEquals(original.stateAtVisible(6 + offset), parsed.stateAtVisible(9 + offset))
            }
        }
    }
}
