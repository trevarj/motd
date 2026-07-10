package io.github.trevarj.motd.ui.components

import androidx.compose.ui.graphics.Color
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
}
