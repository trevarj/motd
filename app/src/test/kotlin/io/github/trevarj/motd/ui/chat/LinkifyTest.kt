package io.github.trevarj.motd.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import io.github.trevarj.motd.ui.components.NON_WEB_LINK_RANGE
import io.github.trevarj.motd.ui.components.linkifiedBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkifyTest {
    @Test fun palette_links_keep_first_url_order_repeats_punctuation_and_code_rules() {
        val palette = "https://files.example/pair.MOTD-PALETTE.JSON?download=1#sample"
        val ordinary = "https://example.com/article"
        assertEquals(palette, messageUrls("($palette), $palette").linkUrl)
        assertEquals(ordinary, messageUrls("$ordinary $palette").linkUrl)
        assertEquals(palette, messageUrls("`$ordinary` $palette").linkUrl)
        assertEquals(MessageUrls.Empty, messageUrls("`$palette`"))
        assertEquals(listOf(palette, palette), extractUrls("$palette $palette"))
    }

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
                mediaUrls =
                    listOf(
                        "https://example.com/photo.webp",
                        "https://example.com/second.PNG?size=large#photo",
                        "https://example.com/photo.webp",
                    ),
                linkUrl = "https://example.com/article",
            ),
            messageUrls(
                "read https://example.com/article then https://example.com/photo.webp, " +
                    "`https://example.com/code.png` https://example.com/second.PNG?size=large#photo " +
                    "https://example.com/photo.webp https://example.com/another-article",
            ),
        )
        assertEquals(MessageUrls.Empty, messageUrls("ordinary IRC line"))
    }

    @Test fun resolves_direct_videos_as_inline_media() {
        assertEquals(
            MessageUrls(
                mediaUrls = listOf("https://cdn.example.com/clip.webm?quality=high", "https://cdn.example.com/photo.jpg"),
                linkUrl = "https://example.com/article",
            ),
            messageUrls(
                "watch https://cdn.example.com/clip.webm?quality=high then " +
                    "https://example.com/article and https://cdn.example.com/photo.jpg",
            ),
        )
        assertTrue(isVideoUrl("https://cdn.example.com/clip.MP4#start"))
        assertFalse(isVideoUrl("https://cdn.example.com/clip.mp4.txt"))
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

    @Test fun recognizes_only_curated_external_and_irc_links() {
        listOf(
            "GeMiNi://[2001:db8::1]:1965/capsule",
            "gopher://gopher.example/1/docs",
            "ipfs://QmRaZ1/here",
            "ipns://docs.example/",
            "ftp://files.example/file",
            "ftps://files.example:990/file",
            "TG://resolve?domain=example",
            "tg://resolve?domain=example&profile",
            "tg://resolve?domain=example_123&post=42",
        ).forEach { assertEquals(it, ChatLinkKind.EXTERNAL, chatLinkKind(it)) }
        listOf("irc://chat.example/#motd", "IRCS://chat.example/%23motd").forEach {
            assertEquals(it, ChatLinkKind.IRC, chatLinkKind(it))
        }
        listOf(
            "gemini:/capsule",
            "gopher:///1/docs",
            "ftp://alice@files.example/file",
            "ftps://files.example:/file",
            "ftp://files.example:65536/file",
            "ftp://[broken]/file",
            "ipfs://id%40other",
            "tg://resolve?domain=",
            "tg://resolve?domain=example&post=0",
            "tg://resolve?domain=example&post=-1",
            "tg://resolve?domain=example&profile&post=1",
            "tg://resolve?domain=example&profile&start=payload",
            "tg://resolve?domain=example&post=1&post=2",
            "tg://resolve?domain=example&domain=other",
            "tg://resolve?domain=example&start=payload",
            "tg://resolve?domain=example%26start%3Dpayload",
            "tg://resolve/?domain=example",
            "tg://resolve:99?domain=example",
            "tg://user@resolve?domain=example",
            "tg://resolve?domain=example#fragment",
            "tg://login?token=abc",
            "tg://proxy?server=example",
            "tg://invoice?slug=example",
            "tg://join?invite=example",
            "irc://chat.example",
            "ircs://chat.example/#motd,other",
            "ssh://example.org/",
        ).forEach { assertNull(it, chatLinkKind(it)) }
    }

    @Test fun rendered_ranges_use_whole_uri_tokens_and_skip_code_and_nicks() {
        val gemini = "GeMiNi://[2001:db8::1]/peer"
        val irc = "ircs://chat.example/#peer"
        val web = "https://example.test/peer"
        val body = "[$gemini], ...$gemini unknown:https://example.test/@peer $irc `$gemini` $web @peer:)"
        val annotated =
            linkifiedBody(
                body,
                Color.Blue,
                mentionColor = { if (it == "peer") Color.Red else null },
                onMentionClick = {},
                includeNonWebLinks = true,
            )
        assertEquals(body.replace("`", ""), annotated.text)
        val ranges = annotated.getStringAnnotations(NON_WEB_LINK_RANGE, 0, annotated.length)
        assertEquals(listOf(gemini, gemini, irc), ranges.map { it.item })
        assertEquals(ranges.map { it.item }, ranges.map { annotated.text.substring(it.start, it.end) })
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(listOf(LinkAnnotation.Url(web)), links.mapNotNull { it.item as? LinkAnnotation.Url })
        val mention = links.single { (it.item as? LinkAnnotation.Clickable)?.tag == "mention:peer" }
        assertEquals(annotated.text.lastIndexOf("@peer"), mention.start)
    }

    @Test fun conversation_body_links_match_rendered_links_and_preserve_repetitions() {
        val web = "https://Example.test/a_(b)?x=3#frag"
        val gemini = "GeMiNi://[2001:db8::1]:1965/capsule"
        val irc = "IRCS://chat.example/%23motd"
        val telegram = "tg://resolve?domain=example&post=42"
        val body =
            "(https\u0002://Example.test/a_(b)?x=3#frag\u000f), " +
                "[$gemini] $web ...$gemini $irc $telegram " +
                "`\u000303https://example.test/backtick\u000f` " +
                "javascript:https://example.test/wrapped intent://https://example.test/injected " +
                "ftp://user@files.example/private tg://login?token=secret ssh://example.test/path " +
                "`ircs://chat.example/#hidden'"

        val occurrences = conversationBodyLinks(body)
        assertEquals(listOf(web, gemini, web, gemini, irc, telegram), occurrences)
        val rendered = linkifiedBody(body, Color.Blue, mentionsActive = false, includeNonWebLinks = true)
        val webLinks =
            rendered.getLinkAnnotations(0, rendered.length).mapNotNull { range ->
                (range.item as? LinkAnnotation.Url)?.let { range.start to it.url }
            }
        val otherLinks =
            rendered.getStringAnnotations(NON_WEB_LINK_RANGE, 0, rendered.length).map { it.start to it.item }
        assertEquals((webLinks + otherLinks).sortedBy { it.first }.map { it.second }, occurrences)
    }

    @Test fun non_web_tokens_cannot_supply_network_urls() {
        val text =
            "gemini://host/photo.jpg gopher://host/clip.mp4 ipfs://cid/track.opus " +
                "ftp://host/sample.motd-palette.json ircs://host/channel " +
                "other://https://files.test/photo.png intent:https://files.test/swap.motd-palette.json " +
                "javascript:https://files.test/clip.webm tg://resolve?domain=peer&post=https://files.test/example " +
                "...gemini://capsule.example/?next=https://files.test/pixel.png"
        assertEquals(emptyList<String>(), extractUrls(text))
        assertEquals(MessageUrls.Empty, messageUrls(text))
        val real = "https://files.test/real.motd-palette.json"
        assertEquals(listOf(real), extractUrls("$text $real"))
        assertEquals(real, messageUrls("$text $real").linkUrl)
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
