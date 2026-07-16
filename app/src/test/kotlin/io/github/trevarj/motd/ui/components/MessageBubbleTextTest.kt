package io.github.trevarj.motd.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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

    @Test
    fun action_line_keeps_star_sender_and_body_visually_distinct() {
        val line = buildActionLine(
            sender = "alice",
            text = "waves hello",
            accentColor = Color.Magenta,
            nameColor = Color.Green,
            bodyColor = Color.Gray,
            linkColor = Color.Blue,
            mentionsActive = false,
        )

        assertEquals("* alice waves hello", line.text)
        val star = line.spanStyles.first { it.start == 0 && it.end == 2 }.item
        val sender = line.spanStyles.first { it.start == 2 && it.end == 7 }.item
        val body = line.spanStyles.first { it.start == 8 && it.end == line.length }.item
        assertEquals(Color.Magenta, star.color)
        assertEquals(FontStyle.Normal, star.fontStyle)
        assertEquals(Color.Green, sender.color)
        assertEquals(FontWeight.Bold, sender.fontWeight)
        assertEquals(FontStyle.Normal, sender.fontStyle)
        assertEquals(Color.Gray, body.color)
        assertEquals(FontStyle.Italic, body.fontStyle)
    }

    @Test
    fun action_body_preserves_links_mentions_code_and_friend_tint() {
        val friendTint = Color.Yellow
        val line = buildActionLine(
            sender = "alice",
            text = "greets @bob at https://example.com with `hello`",
            accentColor = Color.Magenta,
            nameColor = Color.Green,
            bodyColor = Color.Gray,
            linkColor = Color.Blue,
            friendTint = friendTint,
            mentionColor = { nick -> if (nick == "bob") Color.Red else null },
            codeBackground = Color.DarkGray,
            codeColor = Color.White,
        )

        val sender = line.spanStyles.first { it.start == 2 && it.end == 7 }.item
        assertEquals(friendTint, sender.background)
        assertTrue(line.hasLinkAnnotations(0, line.length))
        assertTrue(
            line.spanStyles.any {
                it.item.color == Color.Red && line.text.substring(it.start, it.end) == "@bob"
            },
        )
        assertTrue(
            line.spanStyles.any {
                it.item.fontFamily == FontFamily.Monospace &&
                    it.item.fontStyle == FontStyle.Normal &&
                    line.text.substring(it.start, it.end) == "hello"
            },
        )
    }
}
