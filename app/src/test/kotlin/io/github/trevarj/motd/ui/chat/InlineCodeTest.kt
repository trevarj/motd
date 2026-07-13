package io.github.trevarj.motd.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class InlineCodeTest {
    @Test fun parses_backtick_and_emacs_delimiters_identically() {
        assertEquals(
            listOf(InlineTextSegment.Code("hello")),
            parseInlineCode("`hello`"),
        )
        assertEquals(
            listOf(InlineTextSegment.Code("hello")),
            parseInlineCode("`hello'"),
        )
    }

    @Test fun next_backtick_takes_precedence_over_apostrophe() {
        assertEquals(
            listOf(InlineTextSegment.Code("foo' bar")),
            parseInlineCode("`foo' bar`"),
        )
    }

    @Test fun parses_multiple_spans_and_unicode() {
        assertEquals(
            listOf(
                InlineTextSegment.Plain("one "),
                InlineTextSegment.Code("λ🙂"),
                InlineTextSegment.Plain(" two "),
                InlineTextSegment.Code("три"),
            ),
            parseInlineCode("one `λ🙂` two `три'"),
        )
    }

    @Test fun malformed_and_multibacktick_runs_stay_literal() {
        listOf("before `open", "``double``", "```triple```", "empty `` pair").forEach { raw ->
            assertEquals(listOf(InlineTextSegment.Plain(raw)), parseInlineCode(raw))
        }
    }

    @Test fun parsing_does_not_mutate_the_raw_message() {
        val raw = "copy `exact' source"
        parseInlineCode(raw)
        assertEquals("copy `exact' source", raw)
    }
}
