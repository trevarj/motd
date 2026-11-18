package io.github.trevarj.motd.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.NickMentionStyle
import io.github.trevarj.motd.irc.format.IRC_BOLD
import io.github.trevarj.motd.irc.format.IRC_HEX_COLOR
import io.github.trevarj.motd.irc.format.IRC_REVERSE
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.ui.theme.NickColorScheme
import io.github.trevarj.motd.ui.theme.contrastRatio
import io.github.trevarj.motd.ui.theme.ensureContrast
import io.github.trevarj.motd.ui.theme.readingInk
import io.github.trevarj.motd.ui.theme.semanticColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBubbleTextTest {
    @Test
    fun authoredThemeIdentityAndReadingRolesSurviveActionAndRichTextActualFills() {
        val authored = Color(0xFFDDDDDD)
        val fill = Color.White
        for (adjust in listOf(false, true)) {
            val nicks = NickColorScheme(true, NickColorPalette.THEME, emptyMap(), false, identityPalette = listOf(authored), adjustPaletteReadability = adjust)
            val bodyInk = messageContentColor(authored, fill, false, adjustPaletteReadability = adjust)
            val bodies =
                listOf(
                    buildActionLine(
                        "alice",
                        "Reading @bob https://example.org `code`",
                        Color.Blue,
                        nicks.nick("alice", authored),
                        bodyInk,
                        authored,
                        mentionColor = { nicks.nick(it, authored) },
                        codeBackground = fill,
                        codeColor = authored,
                        containerColor = fill,
                        adjustPaletteReadability = adjust,
                        fitSenderColor = !nicks.preservesAuthoredIdentity("alice"),
                        fitMentionColor = { !nicks.preservesAuthoredIdentity(it) },
                    ),
                    buildActionBody(
                        "Reading @bob https://example.org `code`",
                        bodyInk,
                        authored,
                        mentionColor = { nicks.nick(it, authored) },
                        codeBackground = fill,
                        codeColor = authored,
                        containerColor = fill,
                        adjustPaletteReadability = adjust,
                        fitMentionColor = { !nicks.preservesAuthoredIdentity(it) },
                    ),
                    linkifiedBody(
                        "Reading @bob https://example.org `code`",
                        authored,
                        mentionColor = { nicks.nick(it, authored) },
                        codeBackground = fill,
                        codeColor = authored,
                        containerColor = fill,
                        contentColor = bodyInk,
                        adjustPaletteReadability = adjust,
                        fitMentionColor = { !nicks.preservesAuthoredIdentity(it) },
                    ),
                )
            for (body in bodies) {
                for (word in listOf("@bob", "https://", "code")) {
                    val offset = body.text.indexOf(word)
                    val ink =
                        body.spanStyles
                            .last { offset in it.start until it.end && it.item.color != Color.Unspecified }
                            .item.color
                    if (adjust) assertReadingContrast(word, ink, fill) else assertEquals(word, authored, ink)
                }
                if (body.text.startsWith("* alice")) {
                    val senderInk =
                        body.spanStyles
                            .first { it.start == 2 }
                            .item.color
                    if (adjust) assertReadingContrast("sender", senderInk, fill) else assertEquals(authored, senderInk)
                }
            }
        }
    }

    @Test
    fun action_mentions_remain_interactive_without_styling_or_colors() {
        val clicked = mutableListOf<String>()
        val line =
            buildActionLine(
                sender = "alice",
                text = "@BoB unknown `bob`",
                accentColor = Color.Blue,
                nameColor = Color.Red,
                bodyColor = Color.Black,
                linkColor = Color.Blue,
                mentionsActive = false,
                mentionColor = {
                    if (matchesKnownMention(it, setOf("bob"), IrcIdentityRules())) Color.Unspecified else null
                },
                nickMentionStyle = NickMentionStyle.NONE,
                mentionColorsEnabled = false,
                onMentionClick = { clicked.add(it) },
            )
        val mention = line.getLinkAnnotations(0, line.length).single()
        assertEquals("@BoB", line.text.substring(mention.start, mention.end))
        mention.item.linkInteractionListener!!.onClick(mention.item)
        assertEquals(listOf("BoB"), clicked)
        assertTrue(line.getStringAnnotations(NICK_CHIP_RANGE, 0, line.length).isEmpty())
    }

    @Test
    fun mention_links_preserve_tokens_and_exclude_code_urls_and_unknown_nicks() {
        for (style in NickMentionStyle.entries) {
            val clicked = mutableListOf<String>()
            val body =
                linkifiedBody(
                    text = "@BoB, bob `bob` https://example.com/bob unknown",
                    linkColor = Color.Blue,
                    mentionsActive = false,
                    mentionColor = { if (it.equals("bob", ignoreCase = true)) Color.Red else null },
                    nickMentionStyle = style,
                    mentionColorsEnabled = false,
                    onMentionClick = { clicked.add(it) },
                )
            val mentions = body.getLinkAnnotations(0, body.length).filter { it.item is LinkAnnotation.Clickable }
            assertEquals(listOf("@BoB", "bob"), mentions.map { body.text.substring(it.start, it.end) })
            assertEquals(listOf("mention:BoB", "mention:bob"), mentions.map { (it.item as LinkAnnotation.Clickable).tag })
            mentions.forEach { it.item.linkInteractionListener!!.onClick(it.item) }
            assertEquals(listOf("BoB", "bob"), clicked)
            mentions.forEach { assertEquals(SpanStyle(), it.item.styles!!.style) }
            val markers = body.getStringAnnotations(NICK_CHIP_RANGE, 0, body.length)
            assertEquals(if (style == NickMentionStyle.CHIP) 2 else 0, markers.size)
            markers.forEach {
                assertEquals(Color.Red.value.toString(), it.item)
                assertTrue(body.spanStyles.any { span -> span.start == it.start && span.end == it.end && span.item.background == Color.Unspecified })
            }
            if (style == NickMentionStyle.NONE) {
                assertTrue(body.spanStyles.all { it.item.fontWeight == null })
                assertTrue(body.spanStyles.filter { it.start == 0 && it.end == 4 }.all { it.item.color == Color.Unspecified })
            }
        }
    }

    @Test
    fun native_background_and_code_are_not_automatic_chips() {
        val body =
            linkifiedBody(
                text = "\u000304,02plain\u000f `bob`",
                linkColor = Color.Blue,
                codeBackground = Color.Red,
                nickMentionStyle = NickMentionStyle.CHIP,
                mentionColor = { if (it == "bob") Color.Red else null },
            )
        assertTrue(body.getStringAnnotations(NICK_CHIP_RANGE, 0, body.length).isEmpty())
    }

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
    fun active_mentions_apply_selected_style_independently_of_coloring() {
        for (style in NickMentionStyle.entries) {
            for (colored in listOf(false, true)) {
                val body =
                    linkifiedBody(
                        text = "hello @bob, bob!",
                        linkColor = Color.Blue,
                        mentionColor = { if (it == "bob") Color.Red else null },
                        nickMentionStyle = style,
                        mentionColorsEnabled = colored,
                    )
                assertEquals("hello @bob, bob!", body.text)
                for ((start, end) in listOf(6 to 10, 12 to 15)) {
                    val mention = body.spanStyles.single { it.start == start && it.end == end }.item
                    assertEquals(style.name, if (style == NickMentionStyle.BOLD) FontWeight.Bold else null, mention.fontWeight)
                    assertEquals(style.name, Color.Unspecified, mention.background)
                    assertEquals(if (style == NickMentionStyle.CHIP) listOf(Color.Red.value.toString()) else emptyList<String>(), body.getStringAnnotations(NICK_CHIP_RANGE, start, end).map { it.item })
                    if (style == NickMentionStyle.CHIP) {
                        assertReadingContrast("chip colored=$colored", mention.color, Color.Red)
                    } else {
                        assertEquals(style.name, if (colored) Color.Red else Color.Unspecified, mention.color)
                    }
                }
            }
        }
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
    fun links_and_mentions_inside_code_are_inert_for_every_style() {
        for (style in NickMentionStyle.entries) {
            val resolved = mutableListOf<String>()
            val body =
                linkifiedBody(
                    text = "`https://inside.example/@bob @bob` https://outside.example/@bob @bob",
                    linkColor = Color.Blue,
                    mentionColor = { nick ->
                        resolved += nick
                        if (nick == "bob") Color.Red else null
                    },
                    codeBackground = Color.DarkGray,
                    codeColor = Color.White,
                    nickMentionStyle = style,
                )
            assertEquals("https://inside.example/@bob @bob https://outside.example/@bob @bob", body.text)
            assertEquals(
                listOf("https://outside.example/@bob"),
                body.getLinkAnnotations(0, body.length).map { (it.item as LinkAnnotation.Url).url },
            )
            assertEquals(listOf("bob"), resolved)
            val mentionStart = body.text.lastIndexOf("@bob")
            assertTrue(body.spanStyles.any { it.start == mentionStart && it.end == body.length })
        }
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
    fun none_style_preserves_explicit_irc_bold_without_automatic_weight() {
        val body =
            linkifiedBody(
                text = "bob ${IRC_BOLD}bob$IRC_BOLD",
                linkColor = Color.Blue,
                mentionColor = { if (it == "bob") Color.Red else null },
                nickMentionStyle = NickMentionStyle.NONE,
            )
        assertEquals("bob bob", body.text)
        assertTrue(body.spanStyles.filter { it.item.color == Color.Red }.all { it.item.fontWeight == null })
        assertTrue(body.spanStyles.none { it.start == 0 && it.item.fontWeight != null })
        assertTrue(body.spanStyles.any { it.start == 4 && it.end == 7 && it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun action_builders_forward_selected_style_with_coloring_disabled() {
        for (style in NickMentionStyle.entries) {
            val body =
                buildActionBody(
                    "greets @bob",
                    Color.Gray,
                    Color.Blue,
                    mentionColor = { if (it == "bob") Color.Red else null },
                    nickMentionStyle = style,
                    mentionColorsEnabled = false,
                )
            val line =
                buildActionLine(
                    "alice",
                    "greets @bob",
                    Color.Magenta,
                    Color.Green,
                    Color.Gray,
                    Color.Blue,
                    mentionColor = { if (it == "bob") Color.Red else null },
                    nickMentionStyle = style,
                    mentionColorsEnabled = false,
                )
            assertEquals("greets @bob", body.text)
            assertEquals("* alice greets @bob", line.text)
            val sender = line.spanStyles.first { it.start == 2 && it.end == 7 }.item
            assertEquals(Color.Green, sender.color)
            assertEquals(FontWeight.Bold, sender.fontWeight)
            assertEquals(Color.Unspecified, sender.background)
            for (text in listOf(body, line)) {
                val start = text.text.indexOf("@bob")
                val mention = text.spanStyles.single { it.start == start && it.end == start + 4 }.item
                assertEquals(if (style == NickMentionStyle.BOLD) FontWeight.Bold else null, mention.fontWeight)
                assertEquals(Color.Unspecified, mention.background)
                assertEquals(if (style == NickMentionStyle.CHIP) listOf(Color.Red.value.toString()) else emptyList<String>(), text.getStringAnnotations(NICK_CHIP_RANGE, start, start + 4).map { it.item })
                if (style != NickMentionStyle.CHIP) assertEquals(Color.Unspecified, mention.color)
            }
        }
    }

    @Test
    fun explicit_irc_colors_override_chip_colors_and_preserve_weight() {
        val body =
            linkifiedBody(
                text = "$IRC_BOLD\u000304,01bob\u000f bob",
                linkColor = Color.Blue,
                mentionColor = { if (it == "bob") Color.Yellow else null },
                nickMentionStyle = NickMentionStyle.CHIP,
                containerColor = Color.White,
                contentColor = Color.Black,
            )
        assertEquals("bob bob", body.text)
        val explicit = body.spanStyles.last { it.start == 0 && it.end == 3 }.item
        assertEquals(FontWeight.Bold, explicit.fontWeight)
        assertEquals(Color.Black, explicit.background)
        assertEquals(ensureContrast(Color.Red, listOf(Color.Black), MESSAGE_TEXT_CONTRAST), explicit.color)
        val automatic = body.spanStyles.last { it.start == 4 && it.end == 7 }.item
        assertEquals(Color.Unspecified, automatic.background)
        assertEquals(Color.Yellow.value.toString(), body.getStringAnnotations(NICK_CHIP_RANGE, 4, 7).single().item)
        assertEquals(null, automatic.fontWeight)
        assertReadingContrast("automatic chip", automatic.color, Color.Yellow)
    }

    @Test
    fun media_captions_remove_their_links_and_preserve_code_styles_and_other_links() {
        val media = "https://cdn.example/photo.png"
        val second = "https://cdn.example/second.webp"
        val other = "https://other.example/page"
        val body =
            linkifiedBody(
                text = "`$media` ${IRC_BOLD}caption$IRC_BOLD $media and $second then $other",
                linkColor = Color.Blue,
                codeColor = Color.White,
            ).withoutPreviewUrls(listOf(media, second))

        assertEquals("$media caption and then $other", body.text)
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
    fun media_only_body_disappears_and_repeated_previews_remove_each_linked_occurrence() {
        val media = "https://cdn.example/photo.png"
        assertEquals("", linkifiedBody("  $media  ", Color.Blue).withoutPreviewUrls(listOf(media)).text)
        val repeated = linkifiedBody("$media $media", Color.Blue).withoutPreviewUrls(listOf(media, media))
        assertEquals("", repeated.text)
        val singlePreview = linkifiedBody("$media $media", Color.Blue).withoutPreviewUrls(listOf(media))
        assertEquals(media, singlePreview.text)
        assertEquals(media, (singlePreview.getLinkAnnotations(0, singlePreview.length).single().item as LinkAnnotation.Url).url)
    }

    @Test
    fun audio_exclusion_preserves_styled_caption_code_and_unrelated_links() {
        val audio = "https://cdn.example/voice.ogg?ex=abc&is=def&hm=123"
        val other = "https://other.example/other.ogg"
        val body =
            linkifiedBody(
                text = "${IRC_BOLD}caption$IRC_BOLD <$audio> `$audio` and $audio then $other",
                linkColor = Color.Blue,
                codeColor = Color.White,
            ).withoutPreviewUrls(emptyList(), listOf(audio))

        assertEquals("caption $audio and then $other", body.text)
        assertEquals(listOf(other), body.getLinkAnnotations(0, body.length).map { (it.item as LinkAnnotation.Url).url })
        assertTrue(body.spanStyles.any { it.item.fontWeight == FontWeight.Bold && body.text.substring(it.start, it.end) == "caption" })
        assertTrue(body.spanStyles.any { it.item.fontFamily == FontFamily.Monospace && body.text.substring(it.start, it.end) == audio })
        listOf(audio, "<$audio>", " \n<$audio> $audio <$audio>\t ").forEach { text ->
            assertEquals("", linkifiedBody(text, Color.Blue).withoutPreviewUrls(emptyList(), listOf(audio)).text)
        }
        assertEquals(audio, linkifiedBody(audio, Color.Blue).withoutPreviewUrls(emptyList()).text)
        assertEquals("<$other>", linkifiedBody("<$other>", Color.Blue).withoutPreviewUrls(emptyList(), listOf(audio)).text)
    }

    @Test
    fun richMessageInks_fitEveryPaletteAndActualPaintedSurface() {
        val raw =
            "plain https://example.com @bob `code` \u000315grey\u000f \u000314,00paper\u000f " +
                "$IRC_HEX_COLOR" + "FF0000,0000FF${IRC_REVERSE}reverse\u000f"
        messageContrastSchemes().forEach { (name, scheme, dark) ->
            val semantic = semanticColors(scheme, dark)
            val readingInk = readingInk(scheme, useThemeTextColors = false)
            val surfaces =
                listOf(
                    "incoming" to messageBubbleRoleColors(scheme, false, false, MessageKind.PRIVMSG, semantic),
                    "outgoing" to messageBubbleRoleColors(scheme, true, false, MessageKind.PRIVMSG, semantic),
                    "mention" to messageBubbleRoleColors(scheme, false, true, MessageKind.PRIVMSG, semantic),
                    "notice/action" to messageBubbleRoleColors(scheme, false, false, MessageKind.ACTION, semantic),
                )
            surfaces.forEach { (roleName, role) ->
                val bodyColor = ensureContrast(readingInk, listOf(role.container), MESSAGE_TEXT_CONTRAST)
                val body =
                    linkifiedBody(
                        raw,
                        scheme.primary,
                        mentionColor = { if (it == "bob") scheme.secondary else null },
                        codeBackground = scheme.surfaceVariant.copy(alpha = 0.72f),
                        codeColor = readingInk,
                        containerColor = role.container,
                        contentColor = bodyColor,
                        adjustPaletteReadability = true,
                    )
                assertRenderedInks("$name $roleName", body, bodyColor, role.container)
            }
            val previewFill = scheme.primaryContainer
            val previewColor = ensureContrast(readingInk, listOf(previewFill), MESSAGE_TEXT_CONTRAST)
            val preview = mircFormattedText("quoted \u000315grey\u000f \u000314,00paper", previewFill, previewColor, MESSAGE_TEXT_CONTRAST)
            assertRenderedInks("$name reply", preview, previewColor, previewFill)

            // These are the solid flattened row fills, not a wallpaper composition.
            val rowFill = scheme.onSurfaceVariant.copy(alpha = 0.10f).compositeOver(scheme.background)
            val rowColor = ensureContrast(readingInk, listOf(rowFill), MESSAGE_TEXT_CONTRAST)
            val compact =
                buildCompactLine(
                    "bob",
                    raw,
                    MessageKind.PRIVMSG,
                    scheme.secondary,
                    rowColor,
                    scheme.primary,
                    mentionColor = { if (it == "bob") scheme.secondary else null },
                    codeBackground = scheme.surfaceVariant.copy(alpha = 0.72f),
                    codeColor = readingInk,
                    containerColor = rowFill,
                    adjustPaletteReadability = true,
                )
            assertRenderedInks("$name compact/two-line", compact, rowColor, rowFill)
            val actionFill = semantic.warningContainer.copy(alpha = MENTION_ROW_TINT_ALPHA).compositeOver(scheme.background)
            val actionColor = ensureContrast(readingInk, listOf(actionFill), MESSAGE_TEXT_CONTRAST)
            val action =
                buildActionLine(
                    "bob",
                    raw,
                    scheme.tertiary,
                    scheme.secondary,
                    actionColor,
                    scheme.primary,
                    friendTint = scheme.primary.copy(alpha = 0.12f),
                    mentionColor = { if (it == "bob") scheme.secondary else null },
                    codeBackground = scheme.surfaceVariant,
                    codeColor = readingInk,
                    containerColor = actionFill,
                    adjustPaletteReadability = true,
                )
            assertRenderedInks("$name action row", action, actionColor, actionFill)
        }
    }

    @Test
    fun irc_background_and_inline_code_use_the_background_actually_painted() {
        val container = Color.White
        val grey = Color(0xFFD2D2D2)
        val body =
            linkifiedBody(
                "\u000315outside `inside` outside",
                Color.Blue,
                mentionsActive = false,
                codeBackground = Color.Black,
                codeColor = Color.White,
                containerColor = container,
                contentColor = Color.Black,
            )
        val outside =
            body.spanStyles
                .last { it.start == 0 && it.item.color != Color.Unspecified }
                .item.color
        val insideIndex = body.text.indexOf("inside")
        val inside =
            body.spanStyles
                .last { it.start <= insideIndex && it.end > insideIndex && it.item.color != Color.Unspecified }
                .item.color
        assertReadingContrast("IRC outside code", outside, container)
        assertEquals(grey, inside)
        assertReadingContrast("IRC inside code", inside, Color.Black)

        val explicit =
            linkifiedBody(
                "\u000314,00dim \u000315,01safe",
                Color.Blue,
                mentionsActive = false,
                containerColor = Color.Black,
                contentColor = Color.White,
            )
        val dimStyle = explicit.spanStyles.last { it.start == 0 && it.item.color != Color.Unspecified }.item
        val safeIndex = explicit.text.indexOf("safe")
        val safeStyle = explicit.spanStyles.last { it.start <= safeIndex && it.end > safeIndex && it.item.color != Color.Unspecified }.item
        assertEquals(Color.White, dimStyle.background)
        assertReadingContrast("explicit white background", dimStyle.color, Color.White)
        assertEquals(Color.Black, safeStyle.background)
        assertEquals(grey, safeStyle.color)
    }

    @Test
    fun reversed_background_preserves_links_and_repairs_link_and_mention_ink() {
        val background = Color(0xFFD2D2D2)
        val body =
            linkifiedBody(
                "\u000315${IRC_REVERSE}text https://example.com @bob",
                Color.Blue,
                mentionColor = { if (it == "bob") Color.Red else null },
                containerColor = Color.White,
                contentColor = Color.Black,
            )
        val urlIndex = body.text.indexOf("https://")
        val mentionIndex = body.text.indexOf("@bob")
        val urlColor =
            body.spanStyles
                .last { it.start <= urlIndex && it.end > urlIndex && it.item.color != Color.Unspecified }
                .item.color
        val mentionColor =
            body.spanStyles
                .last { it.start <= mentionIndex && it.end > mentionIndex && it.item.color != Color.Unspecified }
                .item.color
        assertTrue(body.hasLinkAnnotations(urlIndex, urlIndex + 1))
        assertReadingContrast("reverse link", urlColor, background)
        assertReadingContrast("reverse mention", mentionColor, background)
        assertTrue(body.spanStyles.any { it.start <= urlIndex && it.end > urlIndex && it.item.background == background })
    }

    @Test
    fun reverse_hex_and_equal_colors_remain_readable() {
        val reversed = mircFormattedText("$IRC_HEX_COLOR" + "ff0000,0000ff${IRC_REVERSE}text")
        val equal = mircFormattedText("$IRC_HEX_COLOR" + "ffffff,fffffftext")

        val reversedStyle = reversed.spanStyles.last().item
        val equalStyle = equal.spanStyles.last().item
        assertEquals("text", reversed.text)
        assertTrue(contrastRatio(reversedStyle.color, Color.Red) >= 4.5)
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
        assertEquals(Color.Unspecified, sender.background)
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
                friendTint = Color.Yellow,
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
        assertEquals(Color.Yellow, sender.background)
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

private fun assertRenderedInks(
    label: String,
    body: AnnotatedString,
    inheritedInk: Color,
    container: Color,
) {
    for (word in listOf("bob", "plain", "https://", "@bob", "code", "grey", "paper", "reverse")) {
        val offset = body.text.indexOf(word)
        if (offset < 0) continue
        var ink = inheritedInk
        var fill = container
        body.spanStyles.filter { offset in it.start until it.end }.forEach { span ->
            if (span.item.color != Color.Unspecified) ink = span.item.color
            if (span.item.background != Color.Unspecified) fill = span.item.background.compositeOver(container)
        }
        assertReadingContrast("$label $word", ink, fill)
    }
}
