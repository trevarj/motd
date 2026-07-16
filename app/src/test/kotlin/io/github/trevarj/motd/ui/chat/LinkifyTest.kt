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

    @Test fun resolves_image_and_preview_link_in_one_result() {
        assertEquals(
            MessageUrls(
                imageUrl = "https://example.com/photo.webp",
                linkUrl = "https://example.com/article",
            ),
            messageUrls("read https://example.com/article then https://example.com/photo.webp"),
        )
        assertEquals(MessageUrls.Empty, messageUrls("ordinary IRC line"))
    }

    @Test fun ignores_urls_inside_code_but_keeps_urls_outside() {
        assertEquals(
            listOf("https://outside.example"),
            extractUrls("`https://inside.example` https://outside.example"),
        )
        assertEquals(
            MessageUrls.Empty,
            messageUrls("`https://inside.example/photo.png'"),
        )
    }

    @Test fun process_cache_retains_positive_and_empty_url_parses() {
        MessageUrlCache.clearForTest()
        val richText = "read https://example.com/article and https://example.com/photo.webp"
        val richUrls = messageUrls(richText)

        MessageUrlCache.put(richText, richUrls)
        MessageUrlCache.put("plain", MessageUrls.Empty)

        assertEquals(richUrls, MessageUrlCache.get(richText))
        assertEquals(MessageUrls.Empty, MessageUrlCache.get("plain"))
    }
}
