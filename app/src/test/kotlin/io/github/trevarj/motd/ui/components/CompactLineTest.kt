package io.github.trevarj.motd.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
    fun nick_is_smaller_and_bold() {
        val line = buildCompactLine("alice", "hello", MessageKind.PRIVMSG, nick, body, link, noTint)
        val style = line.spanStyles.first { it.start == 0 && it.end == "alice".length }.item

        assertEquals(14.sp, style.fontSize)
        assertEquals(FontWeight.Bold, style.fontWeight)
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

    @Test
    fun continuation_drops_nick_prefix() {
        // showSender=false => a grouped continuation line renders body only, no `nick:` prefix.
        val line = buildCompactLine(
            "alice", "second line", MessageKind.PRIVMSG, nick, body, link, noTint,
            showSender = false,
        )
        assertEquals("second line", line.text)
    }

    @Test
    fun continuation_action_keeps_star_marker() {
        val line = buildCompactLine(
            "bob", "still waving", MessageKind.ACTION, nick, body, link, noTint,
            showSender = false,
        )
        assertEquals("* still waving", line.text)
    }

    @Test
    fun known_nick_mention_gets_nick_color() {
        val mention = Color(0xFFAABBCC)
        // "bob" is a known nick; "hi bob:" should color the bob token with the mention color.
        val line = buildCompactLine(
            "alice", "hi bob: welcome", MessageKind.PRIVMSG, nick, body, link, noTint,
            mentionColor = { token -> if (token.lowercase() == "bob") mention else null },
        )
        assertEquals("alice: hi bob: welcome", line.text)
        val start = line.text.indexOf("bob")
        val spanAtBob = line.spanStyles.firstOrNull {
            it.start <= start && it.end >= start + 3 && it.item.color == mention
        }
        assertTrue("expected the bob mention colored with the nick color", spanAtBob != null)
    }

    @Test
    fun at_mention_form_is_colored() {
        val mention = Color(0xFFAABBCC)
        val line = buildCompactLine(
            "alice", "ping @carol now", MessageKind.PRIVMSG, nick, body, link, noTint,
            mentionColor = { token -> if (token.lowercase() == "carol") mention else null },
        )
        // The '@' is folded into the colored token so the mention reads as one unit.
        val at = line.text.indexOf("@carol")
        val span = line.spanStyles.firstOrNull {
            it.start == at && it.end == at + "@carol".length && it.item.color == mention
        }
        assertTrue("expected @carol colored as a single mention token", span != null)
    }

    @Test
    fun inline_code_is_shared_by_privmsg_action_and_notice() {
        for (kind in listOf(MessageKind.PRIVMSG, MessageKind.ACTION, MessageKind.NOTICE)) {
            val line = buildCompactLine(
                "alice", "run `echo hi'", kind, nick, body, link, noTint,
                codeBackground = Color.DarkGray,
                codeColor = Color.White,
            )
            assertTrue(line.text.endsWith("run echo hi"))
            assertTrue(
                line.spanStyles.any {
                    it.item.fontFamily == FontFamily.Monospace &&
                        line.text.substring(it.start, it.end) == "echo hi"
                },
            )
        }
    }
}
