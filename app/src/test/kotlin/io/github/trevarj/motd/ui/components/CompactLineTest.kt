package io.github.trevarj.motd.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.CompactNickBrackets
import io.github.trevarj.motd.irc.format.IRC_BOLD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure text-shape assertions for the COMPACT single-line renderer's line builder. Verifies the
 * per-kind bracketed / "-nick- " prefix and that body URLs stay linkified.
 * Color is a plain value class here, so no Android runtime is needed.
 */
class CompactLineTest {
    private val nick = Color(0xFF112233)
    private val body = Color(0xFF445566)
    private val link = Color(0xFF778899)

    @Test
    fun privmsg_renders_every_bracket_pair_as_copyable_text() {
        val prefixes =
            mapOf(
                CompactNickBrackets.ANGLE to "<alice> hello world",
                CompactNickBrackets.SQUARE to "[alice] hello world",
                CompactNickBrackets.ROUND to "(alice) hello world",
                CompactNickBrackets.NONE to "alice: hello world",
            )
        for ((brackets, expected) in prefixes) {
            val line = buildCompactLine("alice", "hello world", MessageKind.PRIVMSG, nick, body, link, brackets)
            assertEquals(expected, line.text)
            assertTrue(line.spanStyles.all { it.item.background == Color.Unspecified })
        }
    }

    @Test
    fun nick_is_smaller_and_bold() {
        val line = buildCompactLine("alice", "hello", MessageKind.PRIVMSG, nick, body, link)
        val style = line.spanStyles.first { it.start == 0 && it.end == "<alice>".length }.item

        assertEquals(14.sp, style.fontSize)
        assertEquals(FontWeight.Bold, style.fontWeight)
        assertEquals(nick, style.color)
        assertEquals(Color.Unspecified, style.background)
        assertTrue(line.spanStyles.none { it.start <= 7 && it.end > 7 && it.item.fontWeight != null })
    }

    @Test
    fun no_brackets_keeps_colon_in_normal_body_style() {
        val line = buildCompactLine("alice", "hello", MessageKind.PRIVMSG, nick, body, link, CompactNickBrackets.NONE)
        assertEquals("alice: hello", line.text)
        val nickStyle = line.spanStyles.first { it.start == 0 && it.end == "alice".length }.item
        assertEquals(nick, nickStyle.color)
        assertEquals(FontWeight.Bold, nickStyle.fontWeight)
        val colonStyle = line.spanStyles.first { it.start == 5 && it.end == 7 }.item
        assertEquals(body, colonStyle.color)
        assertEquals(null, colonStyle.fontWeight)
        assertTrue(line.spanStyles.none { it.start <= 5 && it.end > 5 && it.item.fontWeight != null })
    }

    @Test
    fun notice_renders_dash_nick_dash_marker() {
        for (brackets in CompactNickBrackets.entries) {
            val line = buildCompactLine("ChanServ", "registered", MessageKind.NOTICE, nick, body, link, brackets)
            assertEquals("-ChanServ- registered", line.text)
        }
    }

    @Test
    fun body_urls_are_linkified() {
        val line = buildCompactLine("alice", "see https://example.com now", MessageKind.PRIVMSG, nick, body, link)
        assertEquals("<alice> see https://example.com now", line.text)
        val links =
            line
                .getLinkAnnotations(0, line.length)
                .map { it.item }
                .filterIsInstance<LinkAnnotation.Url>()
                .map { it.url }
        assertTrue("expected an https link annotation", links.contains("https://example.com"))
    }

    @Test
    fun continuation_drops_nick_prefix() {
        // Grouped continuations render only the body.
        val line =
            buildCompactLine(
                "alice",
                "second line",
                MessageKind.PRIVMSG,
                nick,
                body,
                link,
                showSender = false,
            )
        assertEquals("second line", line.text)
    }

    @Test
    fun known_nick_mention_gets_nick_color() {
        val mention = Color(0xFFAABBCC)
        // "bob" is a known nick; "hi bob:" should color the bob token with the mention color.
        val line =
            buildCompactLine(
                "alice",
                "hi bob: welcome",
                MessageKind.PRIVMSG,
                nick,
                body,
                link,
                mentionColor = { token -> if (token.lowercase() == "bob") mention else null },
            )
        assertEquals("<alice> hi bob: welcome", line.text)
        val start = line.text.indexOf("bob")
        val spanAtBob =
            line.spanStyles.firstOrNull {
                it.start <= start && it.end >= start + 3 && it.item.color == mention
            }
        assertTrue("expected the bob mention colored with the nick color", spanAtBob != null)
        assertEquals(null, spanAtBob!!.item.fontWeight)
    }

    @Test
    fun at_mention_form_is_colored() {
        val mention = Color(0xFFAABBCC)
        val line =
            buildCompactLine(
                "alice",
                "ping @carol now",
                MessageKind.PRIVMSG,
                nick,
                body,
                link,
                mentionColor = { token -> if (token.lowercase() == "carol") mention else null },
            )
        // The '@' is folded into the colored token so the mention reads as one unit.
        val at = line.text.indexOf("@carol")
        val span =
            line.spanStyles.firstOrNull {
                it.start == at && it.end == at + "@carol".length && it.item.color == mention
            }
        assertTrue("expected @carol colored as a single mention token", span != null)
    }

    @Test
    fun inline_code_is_shared_by_privmsg_and_notice() {
        for (kind in listOf(MessageKind.PRIVMSG, MessageKind.NOTICE)) {
            val line =
                buildCompactLine(
                    "alice",
                    "run `echo hi'",
                    kind,
                    nick,
                    body,
                    link,
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

    @Test
    fun explicit_irc_bold_is_preserved_on_colored_mentions() {
        val line =
            buildCompactLine(
                "alice",
                "bob ${IRC_BOLD}bob$IRC_BOLD",
                MessageKind.PRIVMSG,
                nick,
                body,
                link,
                mentionColor = { if (it == "bob") Color.Red else null },
            )
        val first = line.text.indexOf("bob")
        val second = line.text.lastIndexOf("bob")
        assertTrue(line.spanStyles.none { it.start <= first && it.end > first && it.item.fontWeight != null })
        assertTrue(line.spanStyles.any { it.start <= second && it.end >= second + 3 && it.item.fontWeight == FontWeight.Bold })
    }
}
