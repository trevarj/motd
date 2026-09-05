package io.github.trevarj.motd.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.irc.format.IRC_BOLD
import io.github.trevarj.motd.irc.format.IRC_COLOR
import io.github.trevarj.motd.irc.format.IRC_RESET
import io.github.trevarj.motd.irc.format.IrcColor
import io.github.trevarj.motd.irc.format.IrcTextStyle
import io.github.trevarj.motd.irc.format.ircStateAtRawOffset
import io.github.trevarj.motd.irc.format.parseIrcFormatting
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerEditorStateTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun typingDoesNotRestartTheInputMethod() {
        val draft = mutableStateOf(TextFieldValue())
        var inputSessions = 0
        compose.setContent {
            InterceptPlatformTextInput(
                interceptor =
                    object : PlatformTextInputInterceptor {
                        override suspend fun interceptStartInputMethod(
                            request: PlatformTextInputMethodRequest,
                            nextHandler: PlatformTextInputSession,
                        ): Nothing {
                            inputSessions++
                            return nextHandler.startInputMethod(request)
                        }
                    },
            ) {
                MotdTheme(dynamicColor = false) {
                    Composer(
                        value = draft.value,
                        onValueChange = { draft.value = it },
                        onSend = {},
                        enabled = true,
                        ircFormattingEnabled = true,
                    )
                }
            }
        }

        val field = compose.onNodeWithTag("chat_composer_field")
        field.performClick()
        compose.runOnIdle { assertEquals(1, inputSessions) }
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, inputSessions) }
        "flicker".forEach { character ->
            field.performTextInput(character.toString())
            compose.runOnIdle { assertEquals(1, inputSessions) }
        }
        compose.runOnIdle { assertEquals("flicker", draft.value.text) }
    }

    @Test
    fun noToolsInsetCursorAndPlaceholderFromTheComposerEdge() {
        var fieldLeft = 0f
        var textLeft = 0f
        var expectedInset = 0f
        compose.setContent {
            expectedInset = with(LocalDensity.current) { 16.dp.toPx() }
            MotdTheme(dynamicColor = false) {
                Composer(
                    value = TextFieldValue(),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    showEmojiTool = false,
                    showFormattingTools = false,
                    onFieldPositioned = { fieldLeft = it.left },
                    onFieldTextPositioned = { textLeft = it.x },
                )
            }
        }

        compose.waitForIdle()
        compose.runOnIdle { assertEquals(expectedInset, textLeft - fieldLeft, 0.5f) }
    }

    @Test
    fun toolsFollowCapabilitiesAndExposeSelectedSemantics() {
        val showEmoji = mutableStateOf(true)
        val showFormatting = mutableStateOf(true)
        val ircFormatting = mutableStateOf(false)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Composer(
                    value = TextFieldValue("x", TextRange(1)),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    showEmojiTool = showEmoji.value,
                    showFormattingTools = showFormatting.value,
                    onUploadDraft = {},
                    ircFormattingEnabled = ircFormatting.value,
                )
            }
        }

        compose.onNodeWithContentDescription("Open composer tools").assertExists()
        compose
            .onNodeWithTag("chat_composer_tools")
            .assertExists()
            .assertIsNotSelected()
            .performClick()
        compose.onNodeWithContentDescription("Close composer tools").assertExists()
        compose.onNodeWithTag("chat_composer_tools").assertIsSelected()
        compose.onNodeWithTag("chat_composer_emoji").assertExists()
        compose.onNodeWithTag("chat_format_bold").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_overflow").assertDoesNotExist()

        compose.runOnIdle { showEmoji.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_tools").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()

        compose.runOnIdle { ircFormatting.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_tools").assertExists().performClick()
        compose.onNodeWithTag("chat_composer_emoji").assertDoesNotExist()
        compose.onNodeWithTag("chat_format_bold").assertExists()
        compose.onNodeWithTag("chat_composer_overflow").assertExists()

        compose.runOnIdle { showFormatting.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_tools").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()

        compose.runOnIdle { showEmoji.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_emoji").assertExists()
        compose.onNodeWithTag("chat_format_bold").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_format_bold").assertExists()
    }

    @Test
    fun emptyDraftToolsApplyPendingFormattingAndStayOpenWhileTyping() {
        val draft = mutableStateOf(TextFieldValue())
        compose.setContent {
            MotdTheme(dynamicColor = false) {
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
        compose.onNodeWithTag("chat_format_bold").performClick()
        compose.onNodeWithTag("chat_composer_field").performTextInput("x")
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.runOnIdle {
            val parsed = parseIrcFormatting(draft.value.text)
            assertEquals("x", parsed.visibleText)
            assertTrue(
                parsed.runs
                    .single()
                    .state
                    .enabled(IrcTextStyle.BOLD),
            )
        }
    }

    @Test
    fun compactAndExpandedToolsShareStateAndDismissOnSend() {
        val draft = mutableStateOf(TextFieldValue("hello", TextRange(5)))
        var sends = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = { sends++ },
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_send").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, sends) }
    }

    @Test
    fun applyingColorKeepsVisibleCursorInPlace() {
        val draft = mutableStateOf(TextFieldValue("first\n", TextRange(6)))
        compose.setContent {
            MotdTheme(dynamicColor = false) {
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
        compose.onNodeWithTag("chat_format_color").performClick()
        compose.onNodeWithTag("chat_composer_color_sheet").assertIsDisplayed()
        compose.onNodeWithTag("chat_color_4").performClick()
        compose.onNodeWithTag("chat_composer_color_apply").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()

        compose.runOnIdle {
            val parsed = parseIrcFormatting(draft.value.text)
            assertEquals(6, parsed.visibleOffset(draft.value.selection.start))
        }
        compose.onNodeWithTag("chat_composer_field").performTextInput("x")
        compose.runOnIdle {
            val parsed = parseIrcFormatting(draft.value.text)
            assertEquals("first\nx", parsed.visibleText)
            assertEquals(7, parsed.visibleOffset(draft.value.selection.start))
            assertEquals(IrcColor.Numeric(4), parsed.stateAtVisible(parsed.visibleText.lastIndex).foreground)
        }
    }

    @Test
    fun applyingColorKeepsSelectedRangeAndText() {
        val draft = mutableStateOf(TextFieldValue("abcdef\nx", TextRange(2, 5)))
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_tools").performTouchInput { click() }
        compose.onNodeWithTag("chat_format_color").performTouchInput { click() }
        compose.onNodeWithTag("chat_color_4").performTouchInput { click() }
        compose.onNodeWithTag("chat_composer_color_apply").performTouchInput { click() }
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()

        compose.runOnIdle {
            val parsed = parseIrcFormatting(draft.value.text)
            assertEquals("abcdef\nx", parsed.visibleText)
            assertEquals(2, parsed.visibleOffset(draft.value.selection.start))
            assertEquals(5, parsed.visibleOffset(draft.value.selection.end))
            assertTrue(
                parsed.runs
                    .filter { it.end > 2 && it.start < 5 }
                    .all { it.state.foreground == IrcColor.Numeric(4) },
            )
        }
    }

    @Test
    fun clearingFormattingAfterApplyingItInTheSameEditorSession() {
        val text = "clearcheck"
        val draft = mutableStateOf(TextFieldValue(text, TextRange(0, text.length)))
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_tools").performTouchInput { click() }
        compose.runOnIdle { assertEquals(TextRange(0, text.length), draft.value.selection) }
        compose.onNodeWithTag("chat_format_bold").performTouchInput { click() }
        compose.runOnIdle {
            val parsed = parseIrcFormatting(draft.value.text)
            assertTrue(parsed.runs.all { it.state.enabled(IrcTextStyle.BOLD) })
            assertEquals(0, parsed.visibleOffset(draft.value.selection.start))
            assertEquals(text.length, parsed.visibleOffset(draft.value.selection.end))
        }
        compose
            .onNodeWithTag("chat_format_clear")
            .performScrollTo()
            .assertIsEnabled()
            .performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(text, parseIrcFormatting(draft.value.text).visibleText)
            assertTrue(parseIrcFormatting(draft.value.text).runs.all { it.state.isDefault })
        }
    }

    @Test
    fun markdownFormattingIsExplicitAndUpdatesTheVisibleEditor() {
        val source = "/msg alice **bold** and _italic_"
        val draft = mutableStateOf(TextFieldValue(source, TextRange(source.length)))
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.runOnIdle { assertEquals(source, draft.value.text) }
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_overflow").performScrollTo().performClick()
        compose.onNodeWithTag("chat_format_markdown").performClick()

        compose.runOnIdle {
            val parsed = parseIrcFormatting(draft.value.text)
            assertEquals("/msg alice bold and italic", parsed.visibleText)
            assertTrue(parsed.stateAtVisible(11).bold)
            assertTrue(parsed.stateAtVisible(parsed.visibleText.lastIndex).italic)
        }
    }

    @Test
    fun clearingFormattingNeverDeletesSelectedText() {
        val raw = "$IRC_BOLD${IRC_COLOR}04,01hello\nthere$IRC_RESET"
        val draft = mutableStateOf(TextFieldValue(raw, TextRange(0, raw.length)))
        compose.setContent {
            MotdTheme(dynamicColor = false) {
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
        compose.onNodeWithTag("chat_format_clear").performScrollTo().performClick()

        compose.runOnIdle {
            val parsed = parseIrcFormatting(draft.value.text)
            assertEquals("hello\nthere", parsed.visibleText)
            assertTrue(parsed.runs.all { it.state.isDefault })
        }
    }
}
