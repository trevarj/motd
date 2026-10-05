package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.ComposerStyle
import io.github.trevarj.motd.irc.format.IRC_BOLD
import io.github.trevarj.motd.irc.format.IRC_COLOR
import io.github.trevarj.motd.irc.format.IRC_RESET
import io.github.trevarj.motd.irc.format.IrcColor
import io.github.trevarj.motd.irc.format.IrcTextStyle
import io.github.trevarj.motd.irc.format.ircStateAtRawOffset
import io.github.trevarj.motd.irc.format.parseIrcFormatting
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.ui.theme.ConversationTypography
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil

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
        val aiEnabled = mutableStateOf(false)
        var aiOpens = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Composer(
                    value = TextFieldValue("x", TextRange(1)),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    sendEnabled = false,
                    onAi = if (aiEnabled.value) ({ aiOpens++ }) else null,
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
        compose.onNodeWithTag("chat_format_markdown").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_upload_draft").assertDoesNotExist()

        compose.runOnIdle { showEmoji.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_tools").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()

        compose.runOnIdle { ircFormatting.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_tools").assertExists().performClick()
        compose.onNodeWithTag("chat_composer_emoji").assertDoesNotExist()
        compose.onNodeWithTag("chat_format_bold").assertExists()
        compose.onNodeWithTag("chat_format_markdown").assertExists()
        compose.onNodeWithTag("chat_composer_upload_draft").assertExists()

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

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.runOnIdle {
            showEmoji.value = false
            ircFormatting.value = false
            aiEnabled.value = true
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_send").assertIsNotEnabled()
        compose.onNodeWithTag("chat_composer_tools").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
        compose
            .onNodeWithTag("chat_composer_ai")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertEquals(1, aiOpens) }
        compose.runOnIdle { aiEnabled.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_ai").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_tools").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun narrowInputKeepsAiAndExpandAccessibleWhileSendIsDisabled() {
        val draft = mutableStateOf(TextFieldValue("draft", TextRange(5)))
        val aiEnabled = mutableStateOf(true)
        val formatting = mutableStateOf(true)
        var aiOpens = 0
        var attachments = 0
        var minimumTarget = 0f
        compose.setContent {
            minimumTarget = with(LocalDensity.current) { 48.dp.toPx() }
            MotdTheme(dynamicColor = false) {
                Box(Modifier.width(320.dp)) {
                    Composer(
                        value = draft.value,
                        onValueChange = { draft.value = it },
                        onSend = {},
                        enabled = true,
                        sendEnabled = false,
                        onAi = if (aiEnabled.value) ({ aiOpens++ }) else null,
                        onAttachment = { attachments++ },
                        showEmojiTool = false,
                        showFormattingTools = true,
                        ircFormattingEnabled = formatting.value,
                    )
                }
            }
        }

        fun assertInputTargets(trailingTag: String) {
            val ai =
                compose
                    .onNodeWithTag("chat_composer_ai")
                    .assertIsDisplayed()
                    .assertIsEnabled()
                    .fetchSemanticsNode()
            val trailing =
                compose
                    .onNodeWithTag(trailingTag)
                    .assertIsDisplayed()
                    .assertIsEnabled()
                    .fetchSemanticsNode()
            val field = compose.onNodeWithTag("chat_composer_field").fetchSemanticsNode().boundsInRoot
            for (target in listOf(ai.boundsInRoot, trailing.boundsInRoot)) {
                assertTrue(target.width >= minimumTarget - 0.5f)
                assertTrue(target.height >= minimumTarget - 0.5f)
            }
            assertTrue(field.width > 0f)
            assertTrue(field.right <= ai.boundsInRoot.left)
            assertTrue(ai.boundsInRoot.right <= trailing.boundsInRoot.left)
        }

        compose.onNodeWithTag("chat_composer_send").assertIsNotEnabled()
        assertInputTargets("chat_composer_format_expand")
        compose.onNodeWithTag("chat_composer_field").performTextInput(" typed")
        compose.runOnIdle { assertEquals("draft typed", draft.value.text) }
        compose.onNodeWithTag("chat_composer_ai").performClick()
        compose.onNodeWithTag("chat_composer_format_expand").performClick().assertIsSelected()
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onAllNodesWithTag("chat_composer_ai").assertCountEquals(1)
        assertInputTargets("chat_composer_format_expand")
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.runOnIdle { formatting.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_tools").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
        assertInputTargets("chat_composer_attachment")
        compose.onNodeWithTag("chat_composer_ai").performClick()
        compose.onNodeWithTag("chat_composer_attachment").performClick()
        compose.runOnIdle {
            assertEquals(2, aiOpens)
            assertEquals(1, attachments)
            aiEnabled.value = false
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_ai").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_tools").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_attachment").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("chat_composer_send").assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun auxiliaryActionsShareTheFloatingActionBand() {
        val draft = mutableStateOf(TextFieldValue())
        var pixelsPerDp = 0f
        var attachments = 0
        var aiOpens = 0
        compose.setContent {
            pixelsPerDp = LocalDensity.current.density
            MotdTheme(dynamicColor = false) {
                Box(Modifier.width(320.dp)) {
                    Composer(
                        value = draft.value,
                        onValueChange = { draft.value = it },
                        onSend = {},
                        enabled = true,
                        onAttachment = { attachments++ },
                        onAi = { aiOpens++ },
                        ircFormattingEnabled = true,
                    )
                }
            }
        }

        fun assertActionBand(trailing: String) {
            val area = compose.onNodeWithTag("chat_composer_input_area").fetchSemanticsNode().boundsInRoot
            val targets =
                listOf("chat_composer_tools", "chat_composer_ai", trailing, "chat_composer_send").map {
                    compose
                        .onNodeWithTag(it)
                        .assertIsDisplayed()
                        .fetchSemanticsNode()
                        .boundsInRoot
                }
            for (target in targets) {
                assertTrue(target.width >= 48 * pixelsPerDp - 0.5f)
                assertTrue(target.height >= 48 * pixelsPerDp - 0.5f)
                assertEquals(targets.last().center.y, target.center.y, 0.5f)
                assertEquals(area.bottom - 4 * pixelsPerDp, target.bottom, 0.5f)
            }
            targets.zipWithNext { left, right -> assertTrue(!left.overlaps(right)) }
        }

        assertActionBand("chat_composer_attachment")
        compose.onNodeWithTag("chat_composer_attachment").performClick()
        compose.onNodeWithTag("chat_composer_ai").performClick()
        compose.runOnIdle {
            assertEquals(1, attachments)
            assertEquals(1, aiOpens)
            draft.value = TextFieldValue("hello", TextRange(5))
        }
        assertActionBand("chat_composer_format_expand")
        compose.runOnIdle {
            draft.value = TextFieldValue("first\nsecond\nthird", TextRange(18))
        }
        assertActionBand("chat_composer_format_expand")
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        assertActionBand("chat_composer_format_expand")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun defaultAndNicknameToolsSingleRowScrollInsideExpandedPillWithoutRestartingInputMethod() {
        val draft = mutableStateOf(TextFieldValue("hello world", TextRange(0, 5)))
        val style = mutableStateOf(ComposerStyle.COMFORTABLE)
        var inputSessions = 0
        var attachments = 0
        var aiOpens = 0
        var uploads = 0
        var pixelsPerDp = 0f
        compose.setContent {
            pixelsPerDp = LocalDensity.current.density
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
                    Box(Modifier.width(320.dp)) {
                        Composer(
                            value = draft.value,
                            onValueChange = { draft.value = it },
                            onSend = {},
                            enabled = true,
                            composerStyle = style.value,
                            currentNick = "alex",
                            ircFormattingEnabled = true,
                            onAttachment = { attachments++ },
                            onAi = { aiOpens++ },
                            onUploadDraft = { uploads++ },
                        )
                    }
                }
            }
        }
        val field = compose.onNodeWithTag("chat_composer_field")
        val tools = compose.onNodeWithTag("chat_composer_tools")
        val toolbarTags =
            listOf(
                "chat_composer_emoji",
                "chat_format_bold",
                "chat_format_italic",
                "chat_format_underline",
                "chat_format_monospace",
                "chat_format_color",
                "chat_format_clear",
                "chat_format_markdown",
                "chat_composer_upload_draft",
            )
        for (selectedStyle in listOf(ComposerStyle.COMFORTABLE, ComposerStyle.COMPACT)) {
            compose.runOnIdle {
                style.value = selectedStyle
                draft.value = TextFieldValue("hello world", TextRange(0, 5))
            }
            field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            field.performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
            compose.waitForIdle()
            val sessionsBeforeOpening = inputSessions
            val closedHeight =
                compose
                    .onNodeWithTag("chat_composer_pill")
                    .fetchSemanticsNode()
                    .boundsInRoot.height
            tools.performClick().assertIsSelected()
            compose.waitForIdle()
            field.assertIsFocused()
            assertEquals(TextRange(0, 5), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            assertEquals(sessionsBeforeOpening, inputSessions)
            compose.onAllNodesWithTag("chat_composer_pill").assertCountEquals(1)
            val pill = compose.onNodeWithTag("chat_composer_pill").fetchSemanticsNode().boundsInRoot
            val toolbar = compose.onNodeWithTag("chat_composer_format_toolbar").fetchSemanticsNode()
            assertTrue("All styles expose native horizontal scrolling", toolbar.config.contains(SemanticsActions.ScrollBy))
            assertTrue(toolbar.boundsInRoot.top >= pill.top && toolbar.boundsInRoot.bottom <= pill.bottom)
            compose.onNodeWithTag("chat_composer_overflow").assertDoesNotExist()
            val rowTags =
                toolbarTags +
                    if (selectedStyle == ComposerStyle.COMPACT) {
                        listOf("chat_composer_attachment", "chat_composer_ai", "chat_composer_format_expand")
                    } else {
                        emptyList()
                    }
            var actionTop: Float? = null
            for (tag in rowTags) {
                compose.onAllNodesWithTag(tag).assertCountEquals(1)
                val target =
                    compose
                        .onNodeWithTag(tag)
                        .performScrollTo()
                        .assertIsDisplayed()
                        .fetchSemanticsNode()
                        .boundsInRoot
                assertTrue("$tag must be fully visible inside the shared pill", target.left >= pill.left && target.right <= pill.right && target.top >= pill.top && target.bottom <= pill.bottom)
                assertTrue("$tag keeps a full 48 dp target", target.width >= 48 * pixelsPerDp - 0.5f && target.height >= 48 * pixelsPerDp - 0.5f)
                actionTop?.let { assertEquals("All toolbar actions share one y band", it, target.top, 0.5f) }
                actionTop = target.top
            }
            val rowBounds = rowTags.map { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() }
            rowBounds.forEachIndexed { index, target ->
                rowBounds.drop(index + 1).forEach {
                    assertTrue("Toolbar targets must remain disjoint", target.right <= it.left || it.right <= target.left)
                }
            }
            val inputTags =
                listOf("chat_composer_tools", "chat_composer_send") +
                    if (selectedStyle == ComposerStyle.COMFORTABLE) listOf("chat_composer_ai", "chat_composer_format_expand") else emptyList()
            val inputBounds =
                inputTags.map { tag ->
                    val target =
                        compose
                            .onNodeWithTag(tag)
                            .assertIsDisplayed()
                            .fetchSemanticsNode()
                            .boundsInRoot
                    assertTrue("$tag stays inside the pill", target.left >= pill.left && target.right <= pill.right && target.top >= pill.top && target.bottom <= pill.bottom)
                    assertTrue(target.width >= 48 * pixelsPerDp - 0.5f && target.height >= 48 * pixelsPerDp - 0.5f)
                    assertTrue("Editor-row actions cannot overlap toolbar targets", target.top >= toolbar.boundsInRoot.bottom)
                    target
                }
            inputBounds.forEachIndexed { index, target ->
                inputBounds.drop(index + 1).forEach { assertTrue("Editor-row targets remain disjoint", !target.overlaps(it)) }
            }
            for (tag in listOf("chat_composer_emoji", "chat_composer_upload_draft")) {
                compose.onNodeWithTag(tag).performScrollTo()
                field.assertIsFocused()
                assertEquals(TextRange(0, 5), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
                compose.runOnIdle {
                    assertEquals("hello world", draft.value.text)
                    assertEquals(sessionsBeforeOpening, inputSessions)
                }
            }

            compose.onNodeWithTag("chat_format_bold").performScrollTo().performClick()
            compose.runOnIdle {
                val parsed = parseIrcFormatting(draft.value.text)
                assertEquals("hello world", parsed.visibleText)
                assertTrue(parsed.runs.any { it.start == 0 && it.end == 5 && it.state.enabled(IrcTextStyle.BOLD) })
                assertTrue(parsed.runs.none { it.end > 5 && it.state.enabled(IrcTextStyle.BOLD) })
            }
            if (selectedStyle == ComposerStyle.COMPACT) {
                compose.onNodeWithTag("chat_composer_ai").performScrollTo()
                compose.onNodeWithTag("chat_composer_ai").performClick()
                compose.onNodeWithTag("chat_composer_format_expand").performScrollTo()
            } else {
                compose.onNodeWithTag("chat_composer_ai").performClick()
            }
            compose.onNodeWithTag("chat_composer_format_expand").performClick().assertIsSelected()
            compose.onNodeWithTag("chat_composer_format_expand").performClick().assertIsNotSelected()
            tools.performClick().assertIsNotSelected()
            compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
            assertEquals(
                closedHeight,
                compose
                    .onNodeWithTag("chat_composer_pill")
                    .fetchSemanticsNode()
                    .boundsInRoot.height,
                0.5f,
            )
            field.assertIsFocused()
            assertEquals(TextRange(0, 5), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            assertEquals(sessionsBeforeOpening, inputSessions)
            // Default retains attachment-to-expand substitution; Nickname keeps attachment with a draft.
            if (selectedStyle == ComposerStyle.COMFORTABLE) {
                compose.runOnIdle { draft.value = TextFieldValue() }
            } else {
                tools.performClick()
            }
            if (selectedStyle == ComposerStyle.COMPACT) compose.onNodeWithTag("chat_composer_attachment").performScrollTo()
            compose.onNodeWithTag("chat_composer_attachment").assertIsDisplayed().performClick()
            field.assertIsNotFocused()
            if (selectedStyle == ComposerStyle.COMFORTABLE) tools.performClick()
            compose
                .onNodeWithTag("chat_composer_upload_draft")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            tools.performClick()
        }
        compose.runOnIdle {
            assertEquals(2, attachments)
            assertEquals(2, aiOpens)
            assertEquals(2, uploads)
            style.value = ComposerStyle.LARGE
        }
        compose.onNodeWithTag("chat_composer_tools").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        val toolbar = compose.onNodeWithTag("chat_composer_format_toolbar").fetchSemanticsNode()
        val card = compose.onNodeWithTag("chat_composer_pill").fetchSemanticsNode().boundsInRoot
        assertTrue("Large keeps the toolbar outside its card", toolbar.boundsInRoot.bottom <= card.top)
        assertTrue("Large retains horizontal scrolling", toolbar.config.contains(SemanticsActions.ScrollBy))
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun composerStylesKeepFloatingPaintAndAccessibleTargets() {
        val draft = mutableStateOf(TextFieldValue("Hi", TextRange(2)))
        val style = mutableStateOf(ComposerStyle.COMFORTABLE)
        val systemScale = mutableStateOf(1f)
        val conversationScale = mutableStateOf(100)
        val formatting = mutableStateOf(false)
        val longNick = "alex-with-an-authentically-long-nickname"
        var textOrigin = Offset.Zero
        var fieldWindow = Rect.Zero
        var pixelsPerDp = 0f
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, systemScale.value)) {
                pixelsPerDp = LocalDensity.current.density
                MotdTheme(dynamicColor = false) {
                    ConversationTypography(conversationScale.value) {
                        Box(Modifier.width(320.dp)) {
                            Composer(
                                value = draft.value,
                                onValueChange = { draft.value = it },
                                onSend = {},
                                enabled = true,
                                composerStyle = style.value,
                                currentNick = longNick,
                                onAttachment = {},
                                ircFormattingEnabled = formatting.value,
                                onFieldPositioned = { fieldWindow = it },
                                onFieldTextPositioned = { textOrigin = it },
                            )
                        }
                    }
                }
            }
        }
        val field = compose.onNodeWithTag("chat_composer_field")

        fun assertPaintAndTargets(
            insetDp: Int,
            toolsVisible: Boolean = false,
        ) {
            compose.waitForIdle()
            val layout = field.textLayout()
            val pill = compose.onNodeWithTag("chat_composer_pill").fetchSemanticsNode().boundsInRoot
            val fieldBounds = field.fetchSemanticsNode().boundsInRoot
            val originInRoot = textOrigin + fieldBounds.topLeft - fieldWindow.topLeft
            val top = originInRoot.y + layout.getLineTop(0)
            val bottom = originInRoot.y + layout.getLineBottom(layout.lineCount - 1)
            val inset = insetDp * pixelsPerDp
            assertTrue("Text top must retain the style inset", top >= pill.top + inset - 1f)
            assertTrue("Text bottom must retain the style inset", bottom <= pill.bottom - inset + 1f)
            val cursor = layout.getCursorRect(draft.value.selection.end).translate(originInRoot)
            assertTrue(cursor.top >= pill.top && cursor.bottom <= pill.bottom)
            assertTrue(cursor.left >= pill.left && cursor.right <= pill.right)
            val minimumPillHeight = if (style.value == ComposerStyle.COMFORTABLE) 56 else 52
            if (toolsVisible) {
                val toolbar = compose.onNodeWithTag("chat_composer_format_toolbar").fetchSemanticsNode().boundsInRoot
                assertTrue(toolbar.top >= pill.top && toolbar.bottom <= pill.bottom)
                assertTrue("Tools must remain above the editable region", toolbar.bottom <= fieldBounds.top)
            } else {
                assertEquals(maxOf(minimumPillHeight * pixelsPerDp, ceil(bottom - top) + inset * 2), pill.height, 1f)
            }
            assertTrue(fieldBounds.top >= pill.top && fieldBounds.bottom <= pill.bottom)
            assertTrue(fieldBounds.left >= pill.left && fieldBounds.right <= pill.right)
            assertTrue(fieldBounds.height >= 48 * pixelsPerDp - 0.5f)
            val targets =
                listOf("chat_composer_tools", "chat_composer_send", "chat_composer_attachment").map {
                    compose
                        .onNodeWithTag(it)
                        .assertIsDisplayed()
                        .fetchSemanticsNode()
                        .boundsInRoot
                }
            targets.forEach {
                assertTrue(it.width >= 48 * pixelsPerDp - 0.5f)
                assertTrue(it.height >= 48 * pixelsPerDp - 0.5f)
                assertTrue(!it.overlaps(fieldBounds))
            }
            targets.forEachIndexed { index, target ->
                targets.drop(index + 1).forEach { assertTrue(!target.overlaps(it)) }
            }
            val actionInset = (if (style.value == ComposerStyle.COMFORTABLE) 4 else 2) * pixelsPerDp
            for (target in targets.take(2)) {
                assertTrue(target.left >= pill.left && target.right <= pill.right)
                assertTrue(target.top >= pill.top + actionInset - 0.5f)
                assertEquals(pill.bottom - actionInset, target.bottom, 0.5f)
            }
            assertEquals(pill.right - actionInset, targets[1].right, 0.5f)
            if (style.value == ComposerStyle.COMPACT) {
                val chip = compose.onNodeWithTag("chat_composer_self_nick", useUnmergedTree = true).fetchSemanticsNode()
                assertTrue(chip.boundsInRoot.width <= minOf(92 * pixelsPerDp, pill.width * 0.30f) + 0.5f)
                assertTrue(chip.boundsInRoot.right <= fieldBounds.left)
                assertEquals(
                    originInRoot.y + (layout.getLineTop(0) + layout.getLineBottom(0)) / 2,
                    chip.boundsInRoot.center.y,
                    1f,
                )
                assertTrue(!chip.config.contains(SemanticsActions.OnClick))
                assertTrue(!chip.config.contains(SemanticsActions.RequestFocus))
                assertTrue(compose.onNodeWithText(longNick, useUnmergedTree = true).textLayout().isLineEllipsized(0))
            }
        }

        for ((system, conversation) in listOf(1f to 100, 2f to 140)) {
            compose.runOnIdle {
                systemScale.value = system
                conversationScale.value = conversation
            }
            for (selectedStyle in listOf(ComposerStyle.COMFORTABLE, ComposerStyle.COMPACT)) {
                compose.runOnIdle { style.value = selectedStyle }
                if (selectedStyle == ComposerStyle.COMPACT) compose.onNodeWithTag("chat_composer_tools").performClick()
                assertPaintAndTargets(if (selectedStyle == ComposerStyle.COMFORTABLE) 6 else 4, toolsVisible = selectedStyle == ComposerStyle.COMPACT)
                if (selectedStyle == ComposerStyle.COMPACT) compose.onNodeWithTag("chat_composer_tools").performClick()
            }
        }
        val multiline = (1..12).joinToString("\n") { "Line $it" }
        compose.runOnIdle {
            draft.value = TextFieldValue(multiline, TextRange.Zero)
            formatting.value = true
        }
        field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }

        fun assertVisiblePromptLine(scrollPx: Float) {
            val layout = field.textLayout()
            val fieldBounds = field.fetchSemanticsNode().boundsInRoot
            val originInRoot = textOrigin + fieldBounds.topLeft - fieldWindow.topLeft
            val line = layout.getLineForVerticalPosition(scrollPx)
            val chip = compose.onNodeWithTag("chat_composer_self_nick", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val lineCenter = originInRoot.y + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2 - scrollPx
            // A partly scrolled-away first line cannot take the prompt out of the viewport.
            assertEquals(maxOf(fieldBounds.top + chip.height / 2, lineCenter), chip.center.y, 1f)
            assertTrue(chip.top >= fieldBounds.top && chip.bottom <= fieldBounds.bottom)
        }

        fun assertPromptAfterScrollingToEnd(bottomPaddingDp: Int) {
            field.performSemanticsAction(SemanticsActions.SetSelection) { it(multiline.length, multiline.length, false) }
            compose.waitForIdle()
            val layout = field.textLayout()
            val bounds = field.fetchSemanticsNode().boundsInRoot
            val originInRoot = textOrigin + bounds.topLeft - fieldWindow.topLeft
            val visibleHeight = bounds.bottom - originInRoot.y - bottomPaddingDp * pixelsPerDp
            val endCursor = layout.getCursorRect(multiline.length)
            val scroll =
                minOf(
                    ceil(endCursor.bottom - visibleHeight).coerceAtLeast(0f),
                    (layout.size.height - visibleHeight).coerceAtLeast(0f),
                )
            assertTrue("The oversized draft must scroll", scroll > 0f)
            assertVisiblePromptLine(scroll)
            compose.runOnIdle { assertEquals(multiline, draft.value.text) }
        }

        assertVisiblePromptLine(0f)
        assertPromptAfterScrollingToEnd(bottomPaddingDp = 4)
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_format_expand").performScrollTo().performClick()
        field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        assertPromptAfterScrollingToEnd(bottomPaddingDp = 4)
        field.performSemanticsAction(SemanticsActions.SetSelection) { it(0, 0, false) }
        assertVisiblePromptLine(0f)
        compose.onNodeWithTag("chat_composer_format_expand").performScrollTo().performClick()
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.runOnIdle {
            systemScale.value = 1f
            conversationScale.value = 100
            style.value = ComposerStyle.LARGE
            draft.value = TextFieldValue()
            formatting.value = false
        }
        compose.waitForIdle()
        assertEquals(1, field.textLayout().lineCount)
        val oneLinePaint =
            compose
                .onNodeWithTag("chat_composer_pill")
                .fetchSemanticsNode()
                .boundsInRoot.height
        val oneLineLayout = field.textLayout()
        assertEquals(
            ceil(oneLineLayout.getLineBottom(0) - oneLineLayout.getLineTop(0)) + 24 * pixelsPerDp,
            oneLinePaint,
            1f,
        )
        compose.runOnIdle { draft.value = TextFieldValue("one\ntwo\nthree", TextRange(13)) }
        compose.waitForIdle()
        assertEquals(3, field.textLayout().lineCount)
        assertTrue(
            compose
                .onNodeWithTag("chat_composer_pill")
                .fetchSemanticsNode()
                .boundsInRoot.height > oneLinePaint,
        )
        val send = compose.onNodeWithTag("chat_composer_send").fetchSemanticsNode().boundsInRoot
        val area = compose.onNodeWithTag("chat_composer_input_area").fetchSemanticsNode().boundsInRoot
        assertEquals(area.bottom, send.bottom, 0.5f)
        val largePill = compose.onNodeWithTag("chat_composer_pill").fetchSemanticsNode().boundsInRoot
        assertTrue(send.left >= largePill.right + 8 * pixelsPerDp - 0.5f)
    }

    @Test
    fun compactPromptNeverEntersDraftOrSubmittedText() {
        val draft = mutableStateOf(TextFieldValue("hello", TextRange(5)))
        val nick = mutableStateOf<String?>("alex")
        val style = mutableStateOf(ComposerStyle.COMPACT)
        var sent: String? = null
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = { sent = draft.value.text },
                    enabled = true,
                    composerStyle = style.value,
                    currentNick = nick.value,
                    ircFormattingEnabled = true,
                )
            }
        }
        val field = compose.onNodeWithTag("chat_composer_field")
        field.assertTextEquals("hello")
        compose.onNodeWithText("alex", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_send").performClick()
        compose.runOnIdle { assertEquals("hello", sent) }
        field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        field.performSemanticsAction(SemanticsActions.SetSelection) { it(1, 4, false) }
        compose.runOnIdle {
            assertEquals(TextRange(1, 4), draft.value.selection)
            nick.value = "alex2"
        }
        compose.onNodeWithText("alex2", useUnmergedTree = true).assertIsDisplayed()
        for (selectedStyle in listOf(ComposerStyle.COMFORTABLE, ComposerStyle.LARGE, ComposerStyle.COMPACT)) {
            compose.runOnIdle { style.value = selectedStyle }
            compose.waitForIdle()
            field.assertTextEquals("hello")
            compose.runOnIdle { assertEquals(TextRange(1, 4), draft.value.selection) }
        }
        for (text in listOf("", "/msg peer hello", "one\ntwo\nthree")) {
            compose.runOnIdle { draft.value = TextFieldValue(text, TextRange(text.length)) }
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))
            compose.onNodeWithTag("chat_composer_self_nick", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("alex2", useUnmergedTree = true).assertIsDisplayed()
            compose.runOnIdle { assertEquals(text, draft.value.text) }
        }
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_self_nick", useUnmergedTree = true).assertIsDisplayed()
        field.assertTextEquals("one\ntwo\nthree")
        for (missingNick in listOf<String?>(null, "", "  ")) {
            compose.runOnIdle { nick.value = missingNick }
            compose.onNodeWithTag("chat_composer_self_nick", useUnmergedTree = true).assertDoesNotExist()
            field.assertTextEquals("one\ntwo\nthree")
        }
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
        compose.onNodeWithTag("chat_format_color").performScrollTo().performTouchInput { click() }
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
        compose.onNodeWithTag("chat_format_color").performScrollTo().performClick()
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
        compose.onNodeWithTag("chat_format_color").performScrollTo().performTouchInput { click() }
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
        compose
            .onNodeWithTag("chat_format_markdown")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle {
            val parsed = parseIrcFormatting(draft.value.text)
            assertEquals("/msg alice bold and italic", parsed.visibleText)
            assertTrue(parsed.stateAtVisible(11).bold)
            assertTrue(parsed.stateAtVisible(parsed.visibleText.lastIndex).italic)
        }
        compose.runOnIdle { draft.value = TextFieldValue("/join #room", TextRange(11)) }
        compose
            .onNodeWithTag("chat_format_markdown")
            .performScrollTo()
            .assertIsNotEnabled()
            .performClick()
        compose.runOnIdle { assertEquals("/join #room", draft.value.text) }
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
