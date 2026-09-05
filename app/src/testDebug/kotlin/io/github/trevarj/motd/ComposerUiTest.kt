package io.github.trevarj.motd

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.trevarj.motd.irc.format.IRC_BOLD
import io.github.trevarj.motd.irc.format.IrcColor
import io.github.trevarj.motd.irc.format.parseIrcFormatting
import io.github.trevarj.motd.ui.components.AutocompletePanel
import io.github.trevarj.motd.ui.components.Composer
import io.github.trevarj.motd.ui.components.ComposerReply
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComposerUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose: ComposeContentTestRule = createComposeRule()

    @Test
    fun emojiPicker_opensAlongsideTheComposerInput() {
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("draft"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("chat_composer_input_row").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
    }

    @Test
    fun emojiPicker_toggle_keepsTheComposerInputAvailable() {
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("draft"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_input_row").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()

        // Tools swaps the picker for the compact strip and restores the keyboard.
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("chat_composer_input_row").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        // The picker stays inflated but reports no height, so closing it frees the space without
        // throwing away the populated emoji grid.
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsNotDisplayed()
        assertEquals(
            0,
            compose
                .onNodeWithTag("chat_composer_emoji_panel")
                .fetchSemanticsNode()
                .size.height,
        )
    }

    @Test
    fun emojiPicker_reopensWithTheRetainedPicker() {
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("draft"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_emoji_grid").assertExists()

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.waitForIdle()
        // Retained across the close: reopening reveals the same view instead of re-inflating it and
        // re-running the async category load that made the picker flash blank.
        compose.onNodeWithTag("chat_composer_emoji_grid").assertExists()

        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_emoji_grid").assertExists()
    }

    @Test
    fun emojiPicker_panelHeightComplementsTheImeThroughoutTheAnimation() {
        val imeHeightPx = mutableStateOf(200)
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("draft"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    imeHeightPx = imeHeightPx.value,
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()

        // Simulated keyboard fall and rise. The panel is measured from the same inset value the
        // ancestor imePadding() consumes, so their sum — the space below the input row — never moves.
        var expectedSumPx = -1
        listOf(200, 150, 100, 40, 0, 80, 160, 200).forEach { currentImeHeightPx ->
            compose.runOnIdle { imeHeightPx.value = currentImeHeightPx }
            compose.waitForIdle()
            val panelHeightPx =
                compose
                    .onNodeWithTag("chat_composer_emoji_panel")
                    .fetchSemanticsNode()
                    .size
                    .height
            val sumPx = panelHeightPx + currentImeHeightPx
            if (expectedSumPx < 0) expectedSumPx = sumPx else assertEquals(expectedSumPx, sumPx)
        }
    }

    @Test
    fun autocompletePopup_rowIsClickableOutsideComposerBounds() {
        var picked: String? = null
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("ali"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    autocomplete = {
                        AutocompletePanel(
                            candidates = listOf("alice"),
                            onPick = { picked = it },
                        )
                    },
                )
            }
        }

        compose.onNodeWithText("alice").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("alice", picked)
        }
    }

    @Test
    fun replyWarning_isAboveReplyContentAndInput() {
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("reply"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    reply = ComposerReply("alice", "original"),
                    replyWarning = "alice isn’t currently in this channel",
                )
            }
        }

        val warning =
            compose
                .onNodeWithTag("chat_composer_reply_warning")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val reply =
            compose
                .onNodeWithText("original")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val input = compose.onNodeWithTag("chat_composer_input_row").fetchSemanticsNode().boundsInRoot
        assertTrue(warning.bottom <= reply.top)
        assertTrue(warning.bottom <= input.top)
    }

    @Test
    fun replyBanner_keepsItsContentWhileSendExitRuns() {
        val replyVisible = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("reply"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    reply = ComposerReply("alice", "original"),
                    replyVisible = replyVisible.value,
                )
            }
        }
        compose.onNodeWithText("original").assertIsDisplayed()

        compose.runOnUiThread { replyVisible.value = false }
        compose.mainClock.advanceTimeByFrame()

        // Send starts the exit immediately, while the old quote remains mounted for the fade.
        compose.onNodeWithText("original").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("original").assertDoesNotExist()
    }

    @Test
    fun attachmentBecomesExpandActionAndMovesToOverflowAfterTyping() {
        val draft = mutableStateOf(TextFieldValue())
        var uploads = 0
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    showEmojiTool = false,
                    ircFormattingEnabled = true,
                    onAttachment = {},
                    onUploadDraft = { uploads++ },
                )
            }
        }
        compose.onNodeWithTag("chat_composer_attachment").assertIsDisplayed()
        compose.onAllNodesWithTag("chat_composer_format_expand").assertCountEquals(0)

        compose.runOnIdle { draft.value = TextFieldValue("x", TextRange(1)) }
        compose.waitForIdle()
        compose.onAllNodesWithTag("chat_composer_attachment").assertCountEquals(0)
        compose.onNodeWithTag("chat_composer_format_expand").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat_composer_overflow").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat_composer_upload_draft").performClick()
        compose.runOnIdle {
            assertEquals(1, uploads)
            draft.value = TextFieldValue()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_format_expand").assertIsDisplayed()
        compose.onAllNodesWithTag("chat_composer_attachment").assertCountEquals(0)
    }

    @Test
    fun collapsedCursorOnBlankLineCanEnableFormattingBeforeTyping() {
        val draft = mutableStateOf(TextFieldValue("first\n", TextRange(6)))
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_format_bold").performClick()
        compose.onNodeWithTag("chat_composer_field").performTextInput("x")
        compose.runOnIdle {
            val formatted = parseIrcFormatting(draft.value.text)
            assertEquals("first\nx", formatted.visibleText)
            assertTrue(formatted.stateAtVisible(6).bold)
        }
    }

    @Test
    fun colorActionOpensAtCollapsedCursorOnBlankLine() {
        val draft = mutableStateOf(TextFieldValue("first\n", TextRange(6)))
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_format_color").performClick()
        compose.onNodeWithTag("chat_composer_color_sheet").assertIsDisplayed()
        compose.onNodeWithTag("chat_color_4").performClick()
        compose.onNodeWithTag("chat_composer_color_apply").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat_composer_field").performTextInput("x")
        compose.runOnIdle {
            val formatted = parseIrcFormatting(draft.value.text)
            assertEquals("first\nx", formatted.visibleText)
            assertEquals(IrcColor.Numeric(4), formatted.stateAtVisible(6).foreground)
        }
    }

    @Test
    fun clearFormattingIsTheOnlyStrikeoutToolbarAction() {
        val initial = "${IRC_BOLD}hello\nthere$IRC_BOLD"
        val draft = mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onAllNodesWithTag("chat_format_strike").assertCountEquals(0)
        compose.onNodeWithTag("chat_format_clear").performScrollTo().performClick()
        compose.runOnIdle {
            val formatted = parseIrcFormatting(draft.value.text)
            assertEquals("hello\nthere", formatted.visibleText)
            assertTrue(formatted.runs.all { it.state.isDefault })
        }
    }

    @Test
    fun richComposer_expandsAndFormatsSelectionWhilePlainComposerStaysPlain() {
        val draft = mutableStateOf(TextFieldValue("hello\nthere", TextRange(0, 11)))
        val formattingEnabled = mutableStateOf(true)
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = formattingEnabled.value,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.onNodeWithTag("chat_format_bold").performClick()
        compose.runOnIdle {
            val formatted = parseIrcFormatting(draft.value.text)
            assertEquals("hello\nthere", formatted.visibleText)
            assertTrue(formatted.runs.all { it.state.bold })
        }
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("hello\nthere", parseIrcFormatting(draft.value.text).visibleText)
            formattingEnabled.value = false
            draft.value = TextFieldValue("plain")
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("chat_composer_format_expand").assertCountEquals(0)
    }

    @Test
    fun compactAndExpandedModesShareOneToolbar() {
        val draft = mutableStateOf(TextFieldValue("hello", TextRange(5)))
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
    }

    @Test
    fun emojiReplacesExpandedToolsAndReturnsClosed() {
        val draft = mutableStateOf(TextFieldValue("hello", TextRange(5)))
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand rich editor").assertIsDisplayed()

        compose.onNodeWithContentDescription("Close emoji picker").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsNotDisplayed()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
    }

    @Test
    fun sendDismissesComposerTools() {
        var sends = 0
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("hello", TextRange(5)),
                    onValueChange = {},
                    onSend = { sends++ },
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_send").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, sends) }
    }

    @Test
    fun colorSheet_appliesForegroundAndBackgroundAndFormattingOnlyCannotSend() {
        val draft = mutableStateOf(TextFieldValue("hello\nthere", TextRange(0, 11)))
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_format_color").performClick()
        compose.onNodeWithTag("chat_color_4").performClick()
        compose.onNodeWithText("Background").performClick()
        compose.onNodeWithTag("chat_color_1").performClick()
        compose.onNodeWithTag("chat_composer_color_apply").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val state = parseIrcFormatting(draft.value.text).runs.single().state
            assertEquals(IrcColor.Numeric(4), state.foreground)
            assertEquals(IrcColor.Numeric(1), state.background)
        }

        compose.runOnIdle { draft.value = TextFieldValue("$IRC_BOLD$IRC_BOLD") }
        compose.onNodeWithTag("chat_composer_send").assertIsNotEnabled()
    }

    @Test
    fun semanticVoiceActivation_startsOneLockedRecordingAndStopsWhenActive() {
        var starts = 0
        var stops = 0
        val recording = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        val voiceEnabled = mutableStateOf(true)
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue(),
                    onValueChange = {},
                    onSend = {},
                    enabled = enabled.value,
                    voiceEnabled = voiceEnabled.value,
                    voiceRecording = recording.value,
                    onVoiceAccessibilityStart = {
                        starts++
                        recording.value = true
                    },
                    onVoiceHoldStop = {
                        stops++
                        recording.value = false
                    },
                )
            }
        }
        compose.onNodeWithTag("chat_composer_voice").performTouchInput { click() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, starts) }

        compose
            .onNodeWithTag("chat_composer_voice")
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, starts) }

        compose.onNodeWithTag("chat_composer_voice").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1, stops)
        }

        compose.runOnIdle { enabled.value = false }
        compose
            .onNodeWithTag("chat_composer_voice")
            .assertIsNotEnabled()

        compose.runOnIdle {
            enabled.value = true
            voiceEnabled.value = false
        }
        compose.onAllNodesWithTag("chat_composer_voice").assertCountEquals(0)
        compose.runOnIdle { assertEquals(1, starts) }
    }
}
