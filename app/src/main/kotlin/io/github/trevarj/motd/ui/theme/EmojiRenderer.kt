package io.github.trevarj.motd.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.EmojiSupportMatch
import androidx.compose.ui.text.PlatformParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import io.github.trevarj.motd.data.emoji.EmojiDataset
import io.github.trevarj.motd.data.emoji.EmojiSequenceCatalog

/** A null renderer keeps native System rendering, without copying text or annotations. */
val LocalEmojiRenderer = staticCompositionLocalOf<EmojiRenderer?> { null }

/** Lazy metadata service, also provided when System rendering is selected. */
val LocalEmojiSequenceCatalog = staticCompositionLocalOf<EmojiSequenceCatalog?> { null }

/** Overrides only complete sequences supported by the installed font; Unicode stays untouched. */
@Immutable
class EmojiRenderer(
    val dataset: EmojiDataset,
    val setId: String,
    val fontFamily: FontFamily,
) {
    private val emojiStyle = SpanStyle(fontFamily = fontFamily)

    fun annotate(text: AnnotatedString): AnnotatedString {
        val matches = dataset.matches(text, setId)
        if (matches.isEmpty()) return text
        return AnnotatedString
            .Builder(text)
            .apply {
                for (match in matches) addStyle(emojiStyle, match.start, match.end)
            }.toAnnotatedString()
    }

    /** Apply after the editor's own output transformation; never mutate its value or selection. */
    @OptIn(ExperimentalFoundationApi::class)
    fun addStyles(buffer: TextFieldBuffer) {
        for (match in dataset.matches(buffer.asCharSequence(), setId)) {
            buffer.addStyle(emojiStyle, match.start, match.end)
        }
    }

    fun textStyle(style: TextStyle): TextStyle {
        if (style.platformStyle?.paragraphStyle?.emojiSupportMatch == EmojiSupportMatch.None) return style
        return style.copy(
            platformStyle =
                PlatformTextStyle(
                    spanStyle = style.platformStyle?.spanStyle,
                    paragraphStyle =
                        PlatformParagraphStyle(
                            includeFontPadding = style.platformStyle?.paragraphStyle?.includeFontPadding ?: false,
                            emojiSupportMatch = EmojiSupportMatch.None,
                        ),
                ),
        )
    }
}
