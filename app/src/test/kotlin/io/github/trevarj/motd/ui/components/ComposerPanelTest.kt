package io.github.trevarj.motd.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerPanelTest {
    @Test fun emojiTakesPriorityOverAutocomplete() {
        assertEquals(ComposerPanel.EMOJI, composerPanel(showEmoji = true, hasAutocomplete = true))
    }

    @Test fun autocompleteShowsWhenEmojiIsClosed() {
        assertEquals(ComposerPanel.AUTOCOMPLETE, composerPanel(showEmoji = false, hasAutocomplete = true))
    }

    @Test fun noPanelWhenBothAreClosed() {
        assertEquals(ComposerPanel.NONE, composerPanel(showEmoji = false, hasAutocomplete = false))
    }

    @Test
    fun `emoji query follows the token at the cursor`() {
        assertEquals(
            EmojiQuery(6, 12, "smile"),
            activeEmojiQuery(TextFieldValue("hello :smile", selection = TextRange(12))),
        )
        assertEquals(null, activeEmojiQuery(TextFieldValue("hello:smile", selection = TextRange(11))))
    }

    @Test
    fun `emoji selection replaces only the active query`() {
        val value = TextFieldValue("hello :smile world", selection = TextRange(12))

        assertEquals("hello 😄 world", replaceEmojiQuery(value, EmojiQuery(6, 12, "smile"), "😄").text)
    }
}
