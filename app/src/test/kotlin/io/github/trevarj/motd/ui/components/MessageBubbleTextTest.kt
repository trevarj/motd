package io.github.trevarj.motd.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import io.github.trevarj.motd.irc.format.IRC_BOLD
import io.github.trevarj.motd.irc.format.IRC_HEX_COLOR
import io.github.trevarj.motd.irc.format.IRC_REVERSE
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBubbleTextTest {
    @Test
    fun bot_indicator_only_changes_the_display_label() {
        assertEquals("helper 🤖", botDisplayName("helper", isBot = true))
        assertEquals("helper", botDisplayName("helper", isBot = false))
    }

    @Test
    fun mention_membership_uses_active_irc_casemapping() {
        val rfc = IrcIdentityRules(IrcCaseMapping.Rfc1459)
        val strict = IrcIdentityRules(IrcCaseMapping.Rfc1459Strict)
        val known = setOf(rfc.normalize("nick^"))

        assertTrue(matchesKnownMention("nick~", known, rfc))
        assertTrue(!matchesKnownMention("nick~", known, strict))
    }

    @Test
    fun inactive_mentions_and_no_url_return_plain_body() {
        val body =
            linkifiedBody(
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
        val body =
            linkifiedBody(
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
        val body =
            linkifiedBody(
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
        val body =
            linkifiedBody(
                text = "run `one` then `two'",
                linkColor = Color.Blue,
                mentionsActive = false,
                codeBackground = Color.DarkGray,
                codeColor = Color.White,
            )

        assertEquals("run one then two", body.text)
        assertEquals(
            listOf("one", "two"),
            body.spanStyles
                .filter { it.item.fontFamily == FontFamily.Monospace }
                .map { body.text.substring(it.start, it.end) },
        )
    }

    @Test
    fun links_and_mentions_inside_code_are_inert() {
        val body =
            linkifiedBody(
                text = "`https://inside.example @bob` https://outside.example @bob",
                linkColor = Color.Blue,
                mentionColor = { nick -> if (nick == "bob") Color.Red else null },
                codeBackground = Color.DarkGray,
                codeColor = Color.White,
            )

        val links =
            body
                .getLinkAnnotations(0, body.length)
                .map { it.item }
                .filterIsInstance<LinkAnnotation.Url>()
                .map { it.url }
        assertEquals(listOf("https://outside.example"), links)
        val redRuns =
            body.spanStyles
                .filter { it.item.color == Color.Red }
                .map { body.text.substring(it.start, it.end) }
        assertEquals(listOf("@bob"), redRuns)
    }

    @Test
    fun mirc_color_overrides_plain_body_color() {
        val body =
            buildAnnotatedString {
                appendRichText(
                    text = "\u000304red",
                    plainStyle = SpanStyle(color = Color.Green),
                    linkStyle = SpanStyle(color = Color.Blue),
                    codeStyle = SpanStyle(fontFamily = FontFamily.Monospace),
                )
            }

        assertEquals("red", body.text)
        assertTrue(body.spanStyles.any { it.item.color == Color.Red })
    }

    @Test
    fun irc_styles_compose_with_links_mentions_and_inline_code() {
        val body =
            linkifiedBody(
                text = "$IRC_BOLD" + "https://exa${IRC_BOLD}mple.com @bob `code`",
                linkColor = Color.Blue,
                mentionColor = { nick -> if (nick == "bob") Color.Red else null },
                codeBackground = Color.DarkGray,
                codeColor = Color.White,
            )

        assertEquals("https://example.com @bob code", body.text)
        assertTrue(body.hasLinkAnnotations(0, "https://example.com".length))
        assertTrue(body.spanStyles.any { it.item.color == Color.Red && body.text.substring(it.start, it.end) == "@bob" })
        assertTrue(body.spanStyles.any { it.item.fontFamily == FontFamily.Monospace && body.text.substring(it.start, it.end) == "code" })
        assertTrue(body.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun media_caption_removes_only_its_link_and_preserves_code_styles_and_other_links() {
        val media = "https://cdn.example/photo.png"
        val other = "https://other.example/page"
        val body =
            linkifiedBody(
                text = "`$media` ${IRC_BOLD}caption$IRC_BOLD $media and $other",
                linkColor = Color.Blue,
                codeColor = Color.White,
            ).withoutMediaPreviewUrl(media)

        assertEquals("$media caption and $other", body.text)
        assertEquals(
            listOf(other),
            body.getLinkAnnotations(0, body.length).map { (it.item as LinkAnnotation.Url).url },
        )
        assertTrue(
            body.spanStyles.any {
                it.item.fontFamily == FontFamily.Monospace && body.text.substring(it.start, it.end) == media
            },
        )
        assertTrue(
            body.spanStyles.any {
                it.item.fontWeight == FontWeight.Bold && body.text.substring(it.start, it.end) == "caption"
            },
        )
    }

    @Test
    fun media_only_body_disappears_but_another_occurrence_stays_linked() {
        val media = "https://cdn.example/photo.png"
        assertEquals("", linkifiedBody("  $media  ", Color.Blue).withoutMediaPreviewUrl(media).text)
        val repeated = linkifiedBody("$media $media", Color.Blue).withoutMediaPreviewUrl(media)
        assertEquals(media, repeated.text)
        assertEquals(media, (repeated.getLinkAnnotations(0, repeated.length).single().item as LinkAnnotation.Url).url)
    }

    @Test
    fun reverse_hex_and_equal_colors_remain_readable() {
        val reversed = mircFormattedText("$IRC_HEX_COLOR" + "ff0000,0000ff${IRC_REVERSE}text")
        val equal = mircFormattedText("$IRC_HEX_COLOR" + "ffffff,fffffftext")

        val reversedStyle = reversed.spanStyles.last().item
        val equalStyle = equal.spanStyles.last().item
        assertEquals("text", reversed.text)
        assertEquals(Color.Blue, reversedStyle.color)
        assertEquals(Color.Red, reversedStyle.background)
        assertEquals(Color.Black, equalStyle.color)
        assertEquals(Color.White, equalStyle.background)
    }

    @Test
    fun mirc_preview_leaves_urls_and_backticks_inert() {
        val body = mircFormattedText("\u000304visit https://example.com and `code`")

        assertEquals("visit https://example.com and `code`", body.text)
        assertTrue(body.spanStyles.any { it.item.color == Color.Red })
        assertTrue(!body.hasLinkAnnotations(0, body.length))
    }

    @Test
    fun action_line_keeps_star_sender_and_body_visually_distinct() {
        val line =
            buildActionLine(
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
    fun action_line_without_star_starts_with_sender_and_keeps_link() {
        val senderLink = LinkAnnotation.Clickable(tag = "action-sender", linkInteractionListener = {})
        val line =
            buildActionLine(
                sender = "alice",
                text = "waves hello",
                accentColor = Color.Unspecified,
                nameColor = Color.Green,
                bodyColor = Color.Gray,
                linkColor = Color.Blue,
                mentionsActive = false,
                senderLink = senderLink,
                includeStar = false,
            )

        // The comfortable emote bubble drops the `* ` marker (the inline avatar plays that role);
        // the line opens directly with the nick.
        assertEquals("alice waves hello", line.text)
        val sender = line.spanStyles.first { it.start == 0 && it.end == 5 }.item
        val body = line.spanStyles.first { it.start == 6 && it.end == line.length }.item
        assertEquals(Color.Green, sender.color)
        assertEquals(FontWeight.Bold, sender.fontWeight)
        assertEquals(FontStyle.Normal, sender.fontStyle)
        assertEquals(Color.Gray, body.color)
        assertEquals(FontStyle.Italic, body.fontStyle)
        // Tappable nick survives the merge.
        assertTrue(line.hasLinkAnnotations(0, 5))
        assertTrue(!line.hasLinkAnnotations(6, line.length))
    }

    @Test
    fun action_accessibility_label_matches_classic_me_prefix() {
        assertEquals("* alice waves hello", actionAccessibilityLabel("alice", "waves hello"))
        assertEquals("* alice", actionAccessibilityLabel("alice", ""))
    }

    @Test
    fun action_body_preserves_links_mentions_code_and_friend_tint() {
        val friendTint = Color.Yellow
        val line =
            buildActionLine(
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

    @Test
    fun action_body_emits_italic_text_without_star_or_sender() {
        val body =
            buildActionBody(
                text = "waves hello",
                bodyColor = Color.Gray,
                linkColor = Color.Blue,
                mentionsActive = false,
            )

        // buildActionBody is the sender-less body primitive that buildActionLine composes; on its
        // own it is the raw text only. (The comfortable bubble now renders the full line via
        // buildActionLine(includeStar = false).)
        assertEquals("waves hello", body.text)
        assertTrue(body.spanStyles.isNotEmpty())
        // The whole run is italic; no bold sender span and no asterisk.
        val plain = body.spanStyles.first { it.start == 0 && it.end == body.length }.item
        assertEquals(Color.Gray, plain.color)
        assertEquals(FontStyle.Italic, plain.fontStyle)
        // Italic must be synthesized so emotes slant on fonts with no italic face (Nothing OS, etc.).
        assertEquals(FontSynthesis.Style, plain.fontSynthesis)
        assertTrue(body.spanStyles.none { it.item.fontWeight == FontWeight.Bold })
        assertTrue(!body.hasLinkAnnotations(0, body.length))
    }

    @Test
    fun action_body_preserves_links_mentions_and_code() {
        val body =
            buildActionBody(
                text = "greets @bob at https://example.com with `hello`",
                bodyColor = Color.Gray,
                linkColor = Color.Blue,
                mentionColor = { nick -> if (nick == "bob") Color.Red else null },
                codeBackground = Color.DarkGray,
                codeColor = Color.White,
            )

        assertEquals("greets @bob at https://example.com with hello", body.text)
        assertTrue(body.hasLinkAnnotations(0, body.length))
        assertTrue(
            body.spanStyles.any {
                it.item.color == Color.Red && body.text.substring(it.start, it.end) == "@bob"
            },
        )
        assertTrue(
            body.spanStyles.any {
                it.item.fontFamily == FontFamily.Monospace &&
                    it.item.fontStyle == FontStyle.Normal &&
                    body.text.substring(it.start, it.end) == "hello"
            },
        )
    }
}
