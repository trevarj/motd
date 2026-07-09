package io.github.trevarj.motd.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import io.github.trevarj.motd.data.db.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure text-shape assertions for the COMPACT single-line renderer's line builder. Verifies the
 * per-kind prefix/separator ("nick: " / "* nick " / "-nick- ") and that body URLs stay linkified.
 * Color is a plain value class here, so no Android runtime is needed.
 */
class CompactLineTest {

    private val nick = Color(0xFF112233)
    private val body = Color(0xFF445566)
    private val link = Color(0xFF778899)
    private val noTint = Color.Unspecified

    @Test
    fun privmsg_renders_nick_colon_text() {
        val line = buildCompactLine("alice", "hello world", MessageKind.PRIVMSG, nick, body, link, noTint)
        assertEquals("alice: hello world", line.text)
    }

    @Test
    fun action_renders_star_nick_text_no_colon() {
        val line = buildCompactLine("bob", "waves", MessageKind.ACTION, nick, body, link, noTint)
        assertEquals("* bob waves", line.text)
    }

    @Test
    fun notice_renders_dash_nick_dash_marker() {
        val line = buildCompactLine("ChanServ", "registered", MessageKind.NOTICE, nick, body, link, noTint)
        assertEquals("-ChanServ- registered", line.text)
    }

    @Test
    fun body_urls_are_linkified() {
        val line = buildCompactLine("alice", "see https://example.com now", MessageKind.PRIVMSG, nick, body, link, noTint)
        assertEquals("alice: see https://example.com now", line.text)
        val links = line.getLinkAnnotations(0, line.length)
            .map { it.item }
            .filterIsInstance<LinkAnnotation.Url>()
            .map { it.url }
        assertTrue("expected an https link annotation", links.contains("https://example.com"))
    }
}
