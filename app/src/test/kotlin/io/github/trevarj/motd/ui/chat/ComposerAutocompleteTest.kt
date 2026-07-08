package io.github.trevarj.motd.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
        // "a" is one char and not @-prefixed -> suppressed (plans/15 #30).
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
}
