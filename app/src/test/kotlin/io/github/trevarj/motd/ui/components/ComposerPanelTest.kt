package io.github.trevarj.motd.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerPanelTest {
    @Test
    fun `voice gesture locks on a dominant upward swipe`() {
        assertEquals(
            VoiceGestureTarget.LOCK,
            voiceGestureTarget(
                holdActivated = true,
                pointerPressed = true,
                deltaX = -30f,
                deltaY = -90f,
                cancelThreshold = 72f,
                lockThreshold = 72f,
            ),
        )
    }

    @Test
    fun `voice gesture cannot lock before hold activates`() {
        assertEquals(
            VoiceGestureTarget.NONE,
            voiceGestureTarget(
                holdActivated = false,
                pointerPressed = true,
                deltaX = -30f,
                deltaY = -90f,
                cancelThreshold = 72f,
                lockThreshold = 72f,
            ),
        )
    }

    @Test
    fun `voice gesture cannot lock after the pointer is released`() {
        assertEquals(
            VoiceGestureTarget.NONE,
            voiceGestureTarget(
                holdActivated = true,
                pointerPressed = false,
                deltaX = -30f,
                deltaY = -90f,
                cancelThreshold = 72f,
                lockThreshold = 72f,
            ),
        )
    }

    @Test
    fun `voice recording requires at least half a second hold`() {
        assertEquals(500L, voiceRecordHoldDelay(longPressTimeoutMillis = 300L))
        assertEquals(700L, voiceRecordHoldDelay(longPressTimeoutMillis = 700L))
    }

    @Test
    fun `voice gesture cancels on a left swipe`() {
        assertEquals(
            VoiceGestureTarget.CANCEL,
            voiceGestureTarget(
                holdActivated = true,
                pointerPressed = true,
                deltaX = -90f,
                deltaY = -20f,
                cancelThreshold = 72f,
                lockThreshold = 72f,
            ),
        )
    }

    @Test
    fun `voice gesture ignores movement below both thresholds`() {
        assertEquals(
            VoiceGestureTarget.NONE,
            voiceGestureTarget(
                holdActivated = true,
                pointerPressed = true,
                deltaX = -40f,
                deltaY = -40f,
                cancelThreshold = 72f,
                lockThreshold = 72f,
            ),
        )
    }

    @Test
    fun autocomplete_popup_is_placed_above_the_composer_without_layout_height() {
        assertEquals(
            IntOffset(12, 456),
            autocompletePopupPosition(
                anchorBounds = IntRect(left = 12, top = 600, right = 412, bottom = 668),
                popupContentSize = IntSize(width = 400, height = 144),
                layoutDirection = LayoutDirection.Ltr,
            ),
        )
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
    fun composerToolsFollowEnabledCapabilities() {
        assertTrue(composerToolsAvailable(showEmojiTool = true, showFormattingTools = false, ircFormattingEnabled = false))
        assertTrue(composerToolsAvailable(showEmojiTool = false, showFormattingTools = true, ircFormattingEnabled = true))
        assertFalse(composerToolsAvailable(showEmojiTool = false, showFormattingTools = true, ircFormattingEnabled = false))
        assertFalse(composerToolsAvailable(showEmojiTool = false, showFormattingTools = false, ircFormattingEnabled = true))
    }

    @Test
    fun composerToolsPlusRotatesIntoClose() {
        assertEquals(0f, composerToolsRotation(open = false))
        assertEquals(45f, composerToolsRotation(open = true))
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
        val session =
            openEmojiPickerSession(
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
        val session =
            openEmojiPickerSession(
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
        val session =
            openEmojiPickerSession(
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
    fun `ime content height ignores the separately consumed navigation bar inset`() {
        assertEquals(320, imeContentHeightPx(imeBottomPx = 380, navigationBarsBottomPx = 60))
        assertEquals(0, imeContentHeightPx(imeBottomPx = 40, navigationBarsBottomPx = 60))
    }

    @Test
    fun `ime inset tracker only remembers a height the keyboard actually rested at`() {
        val tracker = ImeInsetTracker(settledFrameCount = 3)

        // Mid-animation samples are not resting positions, however tall they get.
        listOf(0, 220, 640, 885).forEach { tracker.update(it) }
        assertEquals(885, tracker.currentImeHeightPx)
        assertEquals(0, tracker.lastVisibleImeHeightPx)

        tracker.settleAt(885)
        assertEquals(885, tracker.lastVisibleImeHeightPx)

        // A keyboard switch that settles shorter replaces the remembered height instead of keeping
        // the stale maximum that used to leave the panel with residual height.
        listOf(600, 400).forEach { tracker.update(it) }
        tracker.settleAt(720)
        assertEquals(720, tracker.lastVisibleImeHeightPx)

        // Hiding the keyboard never overwrites the last visible resting height.
        tracker.settleAt(0)
        assertEquals(720, tracker.lastVisibleImeHeightPx)
        assertEquals(0, tracker.currentImeHeightPx)
    }

    @Test
    fun `ime inset tracker reports a new settle generation per resting position`() {
        val tracker = ImeInsetTracker(settledFrameCount = 3)
        val start = tracker.settleGeneration

        tracker.update(400)
        tracker.update(400)
        assertEquals(false, tracker.settled)
        assertEquals(start, tracker.settleGeneration)

        tracker.update(400)
        assertEquals(true, tracker.settled)
        assertEquals(start + 1, tracker.settleGeneration)

        // Holding still does not keep bumping the generation; only the next settle does.
        repeat(4) { tracker.update(400) }
        assertEquals(start + 1, tracker.settleGeneration)
        tracker.settleAt(0)
        assertEquals(start + 2, tracker.settleGeneration)
    }

    @Test
    fun `panel height complements the ime across a full hide and restore including a shorter keyboard`() {
        val tracker = ImeInsetTracker(settledFrameCount = 3)
        tracker.settleAt(320)
        val session =
            openEmojiPickerSession(
                imeHeightPx = tracker.currentImeHeightPx,
                lastVisibleImeHeightPx = tracker.lastVisibleImeHeightPx,
                inputFocused = true,
                compactPickerHeightPx = 250,
            )

        // Hide, then restore to a shorter keyboard: every frame must still add up to the captured
        // height, which is what keeps the message-list viewport from resizing.
        listOf(320, 240, 120, 0, 90, 200, 280).forEach { currentImeHeightPx ->
            tracker.update(currentImeHeightPx)
            assertEquals(
                session.capturedImeHeightPx,
                currentImeHeightPx +
                    emojiPickerReplacementHeight(
                        session.capturedImeHeightPx,
                        currentImeHeightPx,
                    ),
            )
        }
    }

    private fun ImeInsetTracker.settleAt(heightPx: Int) {
        repeat(4) { update(heightPx) }
    }
}
