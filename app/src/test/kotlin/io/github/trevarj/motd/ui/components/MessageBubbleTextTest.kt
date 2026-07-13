package io.github.trevarj.motd.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBubbleTextTest {
    @Test
    fun inactive_mentions_and_no_url_return_plain_body() {
        val body = linkifiedBody(
            text = "plain chat message for bob",
            linkColor = Color.Blue,
            mentionsActive = false,
        )

        assertEquals("plain chat message for bob", body.text)
        assertTrue(body.spanStyles.isEmpty())
        assertTrue(!body.hasLinkAnnotations(0, body.length))
    }

    @Test
    fun url_stays_linkified_when_mentions_are_inactive() {
        val body = linkifiedBody(
            text = "read https://example.com/page",
            linkColor = Color.Blue,
            mentionsActive = false,
        )

        assertEquals("read https://example.com/page", body.text)
        assertTrue(body.spanStyles.any { it.item.color == Color.Blue })
        assertTrue(body.hasLinkAnnotations(0, body.length))
    }

    @Test
    fun active_mention_stays_colored_without_a_url() {
        val body = linkifiedBody(
            text = "hello bob",
            linkColor = Color.Blue,
            mentionsActive = true,
            mentionColor = { nick -> if (nick == "bob") Color.Red else null },
        )

        assertEquals("hello bob", body.text)
        assertTrue(body.spanStyles.any { it.item.color == Color.Red })
    }

    @Test
    fun both_inline_code_styles_strip_delimiters_and_use_monospace() {
        val body = linkifiedBody(
            text = "run `one` then `two'",
            linkColor = Color.Blue,
            mentionsActive = false,
            codeBackground = Color.DarkGray,
            codeColor = Color.White,
        )

        assertEquals("run one then two", body.text)
        assertEquals(
            listOf("one", "two"),
            body.spanStyles.filter { it.item.fontFamily == FontFamily.Monospace }
                .map { body.text.substring(it.start, it.end) },
        )
    }

    @Test
    fun links_and_mentions_inside_code_are_inert() {
        val body = linkifiedBody(
            text = "`https://inside.example @bob` https://outside.example @bob",
            linkColor = Color.Blue,
            mentionColor = { nick -> if (nick == "bob") Color.Red else null },
            codeBackground = Color.DarkGray,
            codeColor = Color.White,
        )

        val links = body.getLinkAnnotations(0, body.length)
            .map { it.item }
            .filterIsInstance<LinkAnnotation.Url>()
            .map { it.url }
        assertEquals(listOf("https://outside.example"), links)
        val redRuns = body.spanStyles.filter { it.item.color == Color.Red }
            .map { body.text.substring(it.start, it.end) }
        assertEquals(listOf("@bob"), redRuns)
    }
}
