package io.github.trevarj.motd.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkifyTest {
    @Test fun trims_trailing_sentence_punctuation() {
        assertEquals(listOf("https://example.com"), extractUrls("see https://example.com."))
        assertEquals(listOf("https://example.com"), extractUrls("(https://example.com)"))
    }

    @Test fun keeps_balanced_parens_in_wikipedia_url() {
        val text = "https://en.wikipedia.org/wiki/Foo_(bar)"
        assertEquals(listOf(text), extractUrls(text))
    }

    @Test fun trims_only_the_unbalanced_closing_paren() {
        // The URL itself has one matching pair; the extra ")" from prose is trimmed.
        val text = "(see https://en.wikipedia.org/wiki/Foo_(bar))"
        assertEquals(listOf("https://en.wikipedia.org/wiki/Foo_(bar)"), extractUrls(text))
    }

    @Test fun trims_balanced_brackets_check() {
        assertEquals(listOf("https://example.com/a[b]"), extractUrls("https://example.com/a[b]"))
        assertEquals(listOf("https://example.com/a"), extractUrls("[https://example.com/a]"))
    }
}
