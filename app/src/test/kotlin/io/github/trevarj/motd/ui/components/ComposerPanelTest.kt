package io.github.trevarj.motd.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerPanelTest {
    @Test
    fun autocomplete_overlay_does_not_change_composer_height() {
        val placement = autocompleteOverlayPlacement(
            anchorHeightPx = 68,
            overlayHeightPx = 144,
        )

        assertEquals(68, placement.layoutHeightPx)
        assertEquals(-144, placement.overlayYPx)
    }

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

    @Test
    fun `emoji picker captures a visible ime and restores it when dismissed`() {
        val session = openEmojiPickerSession(
            imeHeightPx = 320,
            lastVisibleImeHeightPx = 320,
            inputFocused = true,
            compactPickerHeightPx = 250,
        )

        assertEquals(
            EmojiPickerSession(
                capturedImeHeightPx = 320,
                restoresKeyboard = true,
                phase = EmojiPickerPhase.OPEN,
            ),
            session,
        )
        assertEquals(
            session.copy(phase = EmojiPickerPhase.RESTORING_IME),
            closeEmojiPickerSession(session),
        )
    }

    @Test
    fun `reopening during ime restoration preserves the captured keyboard height`() {
        val session = openEmojiPickerSession(
            imeHeightPx = 320,
            lastVisibleImeHeightPx = 320,
            inputFocused = true,
            compactPickerHeightPx = 250,
        )
        val restoringSession = closeEmojiPickerSession(session)!!

        assertEquals(session, reopenEmojiPickerSession(restoringSession))
    }

    @Test
    fun `emoji picker opened without an ime uses compact panel and does not restore keyboard`() {
        val session = openEmojiPickerSession(
            imeHeightPx = 0,
            lastVisibleImeHeightPx = 320,
            inputFocused = false,
            compactPickerHeightPx = 250,
        )

        assertEquals(
            EmojiPickerSession(capturedImeHeightPx = 250, restoresKeyboard = false),
            session,
        )
        assertEquals(null, closeEmojiPickerSession(session))
    }

    @Test
    fun `tap-time inset race still restores the last visible keyboard`() {
        assertEquals(
            EmojiPickerSession(capturedImeHeightPx = 320, restoresKeyboard = true),
            openEmojiPickerSession(
                imeHeightPx = 0,
                lastVisibleImeHeightPx = 320,
                inputFocused = true,
                compactPickerHeightPx = 250,
            ),
        )
    }

    @Test
    fun `ime replacement height keeps the composer row at the captured keyboard position`() {
        val capturedImeHeightPx = 320

        listOf(320, 240, 160, 80, 0).forEach { currentImeHeightPx ->
            val replacementHeightPx = emojiPickerReplacementHeight(capturedImeHeightPx, currentImeHeightPx)

            assertEquals(capturedImeHeightPx, currentImeHeightPx + replacementHeightPx)
        }
    }

    @Test
    fun `ime replacement height never becomes negative`() {
        assertEquals(0, emojiPickerReplacementHeight(capturedImeHeightPx = 320, currentImeHeightPx = 400))
        assertEquals(0, emojiPickerReplacementHeight(capturedImeHeightPx = -1, currentImeHeightPx = 0))
    }

    @Test
    fun `adjust resize window delta is the keyboard height`() {
        assertEquals(885, keyboardResizeHeight(largestWindowHeightPx = 2203, currentWindowHeightPx = 1318))
        assertEquals(0, keyboardResizeHeight(largestWindowHeightPx = 2203, currentWindowHeightPx = 2203))
    }

    @Test
    fun `keyboard resize tracker follows the same measure pass and remembers the ime height`() {
        val tracker = KeyboardResizeTracker()

        assertEquals(0, tracker.update(2_203))
        assertEquals(400, tracker.update(1_803))
        assertEquals(885, tracker.update(1_318))
        assertEquals(885, tracker.lastVisibleKeyboardHeightPx)
        assertEquals(400, tracker.update(1_803))
        assertEquals(0, tracker.update(2_203))
        assertEquals(885, tracker.lastVisibleKeyboardHeightPx)
    }
}
