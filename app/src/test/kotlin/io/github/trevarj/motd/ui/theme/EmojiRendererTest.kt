package io.github.trevarj.motd.ui.theme

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.EmojiSupportMatch
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.trevarj.motd.data.emoji.EmojiDataset
import io.github.trevarj.motd.data.emoji.EmojiEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiRendererTest {
    private val smile = "😀"
    private val family = "😀\u200D😀"
    private val emojiFamily = FontFamily.Cursive
    private val dataset =
        EmojiDataset(
            listOf(
                EmojiEntry(smile, "smile", "Test", "test", smile, setOf("noto")),
                EmojiEntry(family, "family", "Test", "test", smile, emptySet()),
            ),
            listOf("Test"),
            emptyList(),
        )
    private val renderer = EmojiRenderer(dataset, "noto", emojiFamily)

    @Test
    fun exactEmojiStylingPreservesUnicodeIrcLinksInlineContentAndUtf16Ranges() {
        val original =
            buildAnnotatedString {
                append("𐐀 e\u0301 ")
                append(smile)
                append(" nick ")
                appendInlineContent("avatar", "@")
                addStyle(SpanStyle(color = Color.Red, fontWeight = FontWeight.Bold), 0, length)
                addStringAnnotation("irc", "foreground:4", 0, length)
                addLink(LinkAnnotation.Url("https://example.org/"), 0, length)
            }
        val emojiStart = original.text.indexOf(smile)
        val selection = TextRange(emojiStart, emojiStart + smile.length)
        val rendered = renderer.annotate(original)

        assertEquals(original.text, rendered.text)
        assertEquals(smile, rendered.subSequence(selection.start, selection.end).text)
        assertEquals(original.getStringAnnotations(0, original.length), rendered.getStringAnnotations(0, rendered.length))
        assertEquals(original.getLinkAnnotations(0, original.length), rendered.getLinkAnnotations(0, rendered.length))
        assertEquals(original.spanStyles, rendered.spanStyles.dropLast(1))
        assertEquals(
            AnnotatedString.Range(SpanStyle(fontFamily = emojiFamily), selection.start, selection.end),
            rendered.spanStyles.last(),
        )
    }

    @Test
    fun unsupportedLongerSequencesStayNativeInsteadOfReceivingAPrefixOverride() {
        val text = AnnotatedString("$family $smile🏻 🦄\u200D$smile $smile")
        val styled = renderer.annotate(text)
        assertEquals(text.text, styled.text)
        assertEquals(1, styled.spanStyles.size)
        assertEquals(text.length - smile.length, styled.spanStyles.single().start)
        assertEquals(text.length, styled.spanStyles.single().end)
        assertTrue(renderer.annotate(AnnotatedString(family)).spanStyles.isEmpty())
        assertSame(text, EmojiRenderer(dataset, "system", emojiFamily).annotate(text))
    }

    @Test
    fun selectedEmojiPolicyPreservesGeneralFontAndParagraphMetrics() {
        val general =
            TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                letterSpacing = 1.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = true),
            )
        val styled = renderer.textStyle(general)
        assertEquals(general.fontFamily, styled.fontFamily)
        assertEquals(general.fontSize, styled.fontSize)
        assertEquals(general.lineHeight, styled.lineHeight)
        assertEquals(general.letterSpacing, styled.letterSpacing)
        assertEquals(true, styled.platformStyle?.paragraphStyle?.includeFontPadding)
        assertEquals(EmojiSupportMatch.None, styled.platformStyle?.paragraphStyle?.emojiSupportMatch)
    }
}
