package io.github.trevarj.motd.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutocompleteTest {
    private val members = listOf("alice", "alicia", "bob", "Alan", "carol")

    @Test fun empty_prefix_yields_nothing() {
        assertTrue(rankNickCompletions("", members, emptyList()).isEmpty())
    }

    @Test fun prefix_matches_case_insensitively_and_ranks_alphabetically() {
        // No recent speakers -> pure alphabetical (case-insensitive).
        val out = rankNickCompletions("al", members, emptyList())
        assertEquals(listOf("Alan", "alice", "alicia"), out)
    }

    @Test fun recent_speakers_sort_first_in_recency_order() {
        // alicia spoke most recently, then alice; Alan never spoke -> alpha tail.
        val recent = listOf("alicia", "alice")
        val out = rankNickCompletions("al", members, recent)
        assertEquals(listOf("alicia", "alice", "Alan"), out)
    }

    @Test fun most_recent_occurrence_wins_for_a_repeated_speaker() {
        val recent = listOf("bob", "alice", "bob")
        val out = rankNickCompletions("b", members, recent)
        assertEquals(listOf("bob"), out)
    }

    @Test fun no_match_returns_empty() {
        assertTrue(rankNickCompletions("zz", members, emptyList()).isEmpty())
    }

    @Test fun duplicate_members_deduped_by_normalized_form() {
        val out = rankNickCompletions("ali", listOf("Alice", "alice", "alicia"), emptyList())
        assertEquals(listOf("Alice", "alicia"), out)
    }

    @Test fun limit_is_respected() {
        val many = (1..20).map { "user$it" }
        val out = rankNickCompletions("user", many, emptyList(), limit = 3)
        assertEquals(3, out.size)
    }

    // --- token detection ---

    @Test fun token_at_line_start() {
        val t = nickTokenAt("al", 2)!!
        assertEquals("al", t.text)
        assertTrue(t.atLineStart)
        assertEquals(0, t.start)
        assertEquals(2, t.end)
    }

    @Test fun token_mid_line_is_not_line_start() {
        val text = "hey al"
        val t = nickTokenAt(text, text.length)!!
        assertEquals("al", t.text)
        assertTrue(!t.atLineStart)
    }

    @Test fun at_sigil_stripped_but_bounds_cover_it() {
        val text = "@ali"
        val t = nickTokenAt(text, text.length)!!
        assertEquals("ali", t.text)
        // The @ stays inside the token bounds so applying a completion replaces (drops) the sigil.
        assertEquals(0, t.start)
    }

    @Test fun cursor_on_whitespace_yields_no_token() {
        assertNull(nickTokenAt("hey ", 4))
    }

    // --- insertion form ---

    @Test fun completion_uses_colon_at_line_start() {
        val text = "al"
        val token = nickTokenAt(text, 2)!!
        val r = applyCompletion(text, token, "alice")
        assertEquals("alice: ", r.text)
        assertEquals(7, r.cursor)
    }

    @Test fun completion_uses_space_mid_line() {
        val text = "hey al"
        val token = nickTokenAt(text, text.length)!!
        val r = applyCompletion(text, token, "alice")
        assertEquals("hey alice ", r.text)
    }

    @Test fun completion_replaces_at_prefixed_token() {
        val text = "hey @al"
        val token = nickTokenAt(text, text.length)!!
        val r = applyCompletion(text, token, "alice")
        assertEquals("hey alice ", r.text)
    }
}
