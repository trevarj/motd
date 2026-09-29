package io.github.trevarj.motd.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.irc.format.IRC_BOLD
import io.github.trevarj.motd.irc.format.IRC_COLOR
import io.github.trevarj.motd.irc.format.IRC_RESET
import io.github.trevarj.motd.irc.format.IrcColor
import io.github.trevarj.motd.irc.format.IrcTextStyle
import io.github.trevarj.motd.irc.format.ircStateAtRawOffset
import io.github.trevarj.motd.irc.format.parseIrcFormatting
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.NickColorScheme
import io.github.trevarj.motd.ui.theme.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun ircColorsFollowComposerSurfaceWhenThemeChangesWithoutChangingDraftOrCaret() {
        val raw = "${IRC_COLOR}15grey ${IRC_COLOR}01black ${IRC_COLOR}15,00paper$IRC_RESET"
        val draft = mutableStateOf(TextFieldValue(raw, TextRange(raw.length)))
        val preset = mutableStateOf(ColorThemePreset.LIGHT)
        var surface = Color.Unspecified
        compose.setContent {
            MotdTheme(dynamicColor = false, themePreset = preset.value) {
                surface = MaterialTheme.colorScheme.surfaceContainerHigh
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        val field = compose.onNodeWithTag("chat_composer_field")

        fun renderedStyle(word: String): SpanStyle {
            val text = field.textLayout().layoutInput.text
            val offset = text.indexOf(word)
            assertTrue("Missing $word in transformed draft", offset >= 0)
            return text.spanStyles.last { offset in it.start until it.end }.item
        }

        compose.runOnIdle {
            assertEquals(raw, draft.value.text)
            assertEquals(TextRange(raw.length), draft.value.selection)
        }
        assertTrue(contrastRatio(renderedStyle("grey").color, surface) >= 4.49)
        assertTrue(contrastRatio(renderedStyle("paper").color, Color.White) >= 4.49)
        assertEquals(Color.White, renderedStyle("paper").background)

        compose.runOnIdle { preset.value = ColorThemePreset.DARK }
        compose.waitForIdle()
        assertTrue(contrastRatio(renderedStyle("black").color, surface) >= 4.49)
        compose.runOnIdle {
            assertEquals(raw, draft.value.text)
            assertEquals(TextRange(raw.length), draft.value.selection)
        }
        field.performTextInput("!")
        compose.runOnIdle {
            val parsed = parseIrcFormatting(draft.value.text)
            assertEquals("grey black paper!", parsed.visibleText)
            assertEquals(parsed.visibleText.length, parsed.visibleOffset(draft.value.selection.start))
            assertTrue(parsed.stateAtVisible(parsed.visibleText.lastIndex).isDefault)
        }
    }

    @Test
    fun colorSheetAdjustsPreviewButKeepsSelectedPaletteCodes() {
        val draft = mutableStateOf(TextFieldValue("sample", TextRange(0, 6)))
        val previewBackground = Color(0xFFD2D2D2)
        compose.setContent {
            MotdTheme(dynamicColor = false, themePreset = ColorThemePreset.LIGHT) {
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
        compose.onNodeWithTag("chat_color_15").performClick().assertIsSelected()
        compose.onNodeWithText("Background").performClick()
        compose.onNodeWithTag("chat_color_15").performClick().assertIsSelected()
        val preview = compose.onNodeWithTag("chat_composer_color_preview")
        val previewColor =
            preview
                .textLayout()
                .layoutInput.style.color
        assertTrue(contrastRatio(previewColor, previewBackground) >= 4.49)

        compose.onNodeWithTag("chat_composer_color_apply").performClick()
        compose.runOnIdle {
            val parsed = parseIrcFormatting(draft.value.text)
            assertEquals("sample", parsed.visibleText)
            assertTrue(
                parsed.runs.all {
                    it.state.foreground == IrcColor.Numeric(15) && it.state.background == IrcColor.Numeric(15)
                },
            )
        }
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

    @Test
    fun plainDraftColorsKnownNicksWithoutChangingEditsOrSentText() = verifyNickStyling(ircFormattingEnabled = false)

    @Test
    fun formattedDraftColorsKnownNicksWithoutChangingEditsOrSentText() = verifyNickStyling(ircFormattingEnabled = true)

    private fun verifyNickStyling(ircFormattingEnabled: Boolean) {
        val rules = IrcIdentityRules()
        val visible = "[Alice]: hello @BoB and unknown"
        val original = if (ircFormattingEnabled) "$IRC_BOLD$visible$IRC_RESET" else visible
        val draft = mutableStateOf(TextFieldValue(original, TextRange(original.length)))
        val roster = mutableStateOf(setOf(rules.normalize("{alice}"), rules.normalize("bob")))
        val nickColorsEnabled = mutableStateOf(true)
        val overrides = mutableStateOf(emptyMap<String, Int>())
        var scheme: NickColorScheme? = null
        var fieldBackground = Color.Unspecified
        var sent: String? = null
        compose.setContent {
            MotdTheme(dynamicColor = false, nickColorsEnabled = nickColorsEnabled.value, nickColorOverrides = overrides.value) {
                scheme = LocalNickColors.current
                fieldBackground = MaterialTheme.colorScheme.surfaceContainerHigh
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = { sent = draft.value.text },
                    enabled = true,
                    ircFormattingEnabled = ircFormattingEnabled,
                    knownNicks = roster.value,
                    identityRules = rules,
                )
            }
        }
        val field = compose.onNodeWithTag("chat_composer_field")

        fun layout() = field.textLayout().layoutInput.text

        fun isColored(token: String): Boolean {
            val displayed = layout()
            val start = displayed.indexOf(token)
            assertTrue("Missing $token", start >= 0)
            val color = scheme!!.nick(token.removePrefix("@"), Color.Unspecified)
            return displayed.spanStyles.any { it.start == start && it.end == start + token.length && it.item.color == color }
        }

        val chip = compose.onNodeWithTag("chat_composer_nick_chip", useUnmergedTree = true)

        fun chipPixel(): Int {
            chip.assertExists()
            val pixels = chip.captureToImage().asAndroidBitmap()
            val y = pixels.height / 2
            val fill = pixels.getPixel(1, y)
            assertNotEquals(fieldBackground.toArgb(), fill)
            return fill
        }

        fun noChip() {
            chip.assertDoesNotExist()
            assertTrue(layout().spanStyles.none { it.item.background != Color.Unspecified })
        }

        assertEquals(visible, layout().text)
        assertTrue(layout().spanStyles.none { it.item.background != Color.Unspecified })
        val firstPixel = chipPixel()
        assertTrue(isColored("[Alice]"))
        assertTrue(isColored("@BoB"))
        assertTrue(!isColored("unknown"))
        if (ircFormattingEnabled) {
            assertTrue(layout().spanStyles.any { it.start == 0 && it.end == visible.length && it.item.fontWeight != null })
        }
        compose.runOnIdle {
            assertEquals(original, draft.value.text)
            assertEquals(TextRange(original.length), draft.value.selection)
        }

        val moved = "hello [Alice]: @BoB and unknown"
        compose.runOnIdle { draft.value = TextFieldValue(moved, TextRange(moved.length)) }
        compose.waitForIdle()
        noChip()
        compose.runOnIdle { draft.value = TextFieldValue("[Alice]2: hello", TextRange(15)) }
        compose.waitForIdle()
        noChip()
        compose.runOnIdle { draft.value = TextFieldValue(moved, TextRange(moved.length)) }
        compose.waitForIdle()
        assertTrue(isColored("[Alice]"))
        compose.runOnIdle { roster.value = setOf(rules.normalize("{alice}")) }
        compose.waitForIdle()
        assertTrue(!isColored("@BoB"))
        compose.runOnIdle { roster.value = setOf(rules.normalize("{alice}"), rules.normalize("bob")) }
        compose.waitForIdle()
        assertTrue(isColored("@BoB"))
        compose.runOnIdle { roster.value = setOf(rules.normalize("bob")) }
        compose.waitForIdle()
        assertTrue(!isColored("[Alice]"))
        noChip()
        compose.runOnIdle { roster.value = setOf(rules.normalize("{alice}"), rules.normalize("bob")) }
        compose.waitForIdle()
        compose.runOnIdle { draft.value = TextFieldValue(visible, TextRange(visible.length)) }
        compose.waitForIdle()
        compose.runOnIdle { nickColorsEnabled.value = false }
        compose.waitForIdle()
        assertTrue(!isColored("[Alice]"))
        noChip()
        compose.runOnIdle { nickColorsEnabled.value = true }
        compose.waitForIdle()
        assertTrue(isColored("[Alice]"))
        assertEquals(firstPixel, chipPixel())
        compose.runOnIdle { overrides.value = mapOf("[alice]" to 180) }
        compose.waitForIdle()
        assertNotEquals(firstPixel, chipPixel())

        val command = "/msg [Alice] @BoB"
        compose.runOnIdle { draft.value = TextFieldValue(command, TextRange(command.length)) }
        compose.waitForIdle()
        assertTrue(!isColored("[Alice]"))
        assertTrue(!isColored("@BoB"))
        noChip()
        if (ircFormattingEnabled) compose.onNodeWithTag("chat_composer_command_chip", useUnmergedTree = true).assertExists()

        compose.runOnIdle { draft.value = TextFieldValue(original, TextRange(original.length)) }
        compose.waitForIdle()
        field.performTextInput("!")
        compose.runOnIdle {
            val expected = if (ircFormattingEnabled) parseIrcFormatting(draft.value.text).visibleText else draft.value.text
            assertEquals("$visible!", expected)
            val caret = if (ircFormattingEnabled) parseIrcFormatting(draft.value.text).visibleOffset(draft.value.selection.start) else draft.value.selection.start
            assertEquals(expected.length, caret)
        }
        chipPixel()
        val editedRaw = draft.value.text
        compose.onNodeWithTag("chat_composer_send").performClick()
        compose.runOnIdle { assertEquals(editedRaw, sent) }
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
}
