package io.github.trevarj.motd.ui.components

import android.content.ClipboardManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.emoji.EmojiDataset
import io.github.trevarj.motd.data.emoji.EmojiEntry
import io.github.trevarj.motd.irc.format.IRC_BOLD
import io.github.trevarj.motd.irc.format.IRC_RESET
import io.github.trevarj.motd.irc.format.IrcEditorDocument
import io.github.trevarj.motd.ui.theme.EmojiRenderer
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp")
class ComposerEmojiIntegrationTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    private val zwj = "👩🏽‍💻"
    private val mixedTone = "🫱🏻‍🫲🏿"
    private val body = "hello $zwj $mixedTone"

    // Default is intentional: inspect complete-sequence annotations, not downloaded font pixels.
    private val renderer =
        EmojiRenderer(
            dataset =
                EmojiDataset(
                    entries =
                        listOf(
                            EmojiEntry(zwj, "woman technologist: medium skin tone", "People", "person-role", "👩‍💻", listOf("test")),
                            EmojiEntry(mixedTone, "handshake: light and dark skin tones", "People", "hands", "🤝", listOf("test")),
                        ),
                    groups = listOf("People"),
                    aliases = emptyList(),
                ),
            setId = "test",
            fontFamily = FontFamily.Default,
        )

    @Test
    fun rendererSwitchKeepsFormattedDraftCaretNickPrefixAndInputSession() = assertRendererSwitch(prefix = "alice: ", rangeSelection = false)

    @Test
    fun rendererSwitchKeepsFormattedDraftSelectionCommandPrefixAndInputSession() = assertRendererSwitch(prefix = "/msg alice ", rangeSelection = true)

    private fun assertRendererSwitch(
        prefix: String,
        rangeSelection: Boolean,
    ) {
        val visible = prefix + body
        val raw =
            IrcEditorDocument
                .fromRaw("$prefix$IRC_BOLD$body$IRC_RESET")
                .first
                .toRawValue(visible.length, visible.length)
                .text
        val draft = mutableStateOf(TextFieldValue(raw, TextRange(raw.length)))
        val selectedRenderer = mutableStateOf<EmojiRenderer?>(null)
        val inputRequests = mutableListOf<PlatformTextInputMethodRequest>()
        val interceptor =
            object : PlatformTextInputInterceptor {
                override suspend fun interceptStartInputMethod(
                    request: PlatformTextInputMethodRequest,
                    nextHandler: PlatformTextInputSession,
                ): Nothing {
                    inputRequests += request
                    return nextHandler.startInputMethod(request)
                }
            }
        compose.setContent {
            InterceptPlatformTextInput(interceptor = interceptor) {
                MotdTheme(dynamicColor = false, emojiRenderer = selectedRenderer.value) {
                    Composer(
                        value = draft.value,
                        onValueChange = { draft.value = it },
                        onSend = {},
                        enabled = true,
                        ircFormattingEnabled = true,
                        knownNicks = setOf("alice"),
                    )
                }
            }
        }
        val field = compose.onNodeWithTag("chat_composer_field")
        field.performClick().assertIsFocused()
        val selection = if (rangeSelection) TextRange(visible.length, prefix.length) else TextRange(prefix.length + 2)
        field.performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(selection.start, selection.end, false)) }
        val originalDraft =
            compose.runOnIdle {
                assertEquals(raw, draft.value.text)
                assertEquals(1, inputRequests.size)
                draft.value
            }
        val originalRequest = inputRequests.single()
        val fieldId = field.fetchSemanticsNode().id
        val original = field.displayedText()
        assertEquals(visible, original.text)
        assertTrue(original.spanStyles.any { it.start == prefix.length && it.end == visible.length && it.item.fontWeight == FontWeight.Bold })
        val prefixTag = if (rangeSelection) "chat_composer_command_chip" else "chat_composer_nick_chip"
        compose.onNodeWithTag(prefixTag, useUnmergedTree = true).assertExists()
        if (rangeSelection) {
            assertTrue(original.spanStyles.any { it.start == 0 && it.item.fontFamily == FontFamily.Monospace })
        } else {
            assertTrue(original.spanStyles.any { it.start == 0 && it.end == "alice".length && it.item.color != androidx.compose.ui.graphics.Color.Unspecified })
        }

        for (next in listOf(renderer, null)) {
            compose.runOnIdle { selectedRenderer.value = next }
            field.assertIsFocused()
            assertEquals(fieldId, field.fetchSemanticsNode().id)
            assertEquals(selection, field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            compose.onNodeWithTag(prefixTag, useUnmergedTree = true).assertExists()
            val displayed = field.displayedText()
            assertEquals(visible, displayed.text)
            assertEquals(original.spanStyles, displayed.spanStyles.filterNot { it.item.fontFamily == FontFamily.Default })
            assertEmojiSpans(displayed, selected = next != null)
            compose.runOnIdle {
                assertEquals(originalDraft, draft.value)
                assertEquals(1, inputRequests.size)
                assertSame(originalRequest, inputRequests.single())
            }
        }
        assertEquals(original, field.displayedText())
    }

    @Test
    fun rendererSwitchKeepsPlainDraftSelectionAndInputSession() {
        val text = "before $zwj and $mixedTone after"
        val selection = TextRange("before ".length)
        val original = TextFieldValue(text, selection)
        val draft = mutableStateOf(original)
        val selectedRenderer = mutableStateOf<EmojiRenderer?>(null)
        val inputRequests = mutableListOf<PlatformTextInputMethodRequest>()
        val interceptor =
            object : PlatformTextInputInterceptor {
                override suspend fun interceptStartInputMethod(
                    request: PlatformTextInputMethodRequest,
                    nextHandler: PlatformTextInputSession,
                ): Nothing {
                    inputRequests += request
                    return nextHandler.startInputMethod(request)
                }
            }
        compose.setContent {
            InterceptPlatformTextInput(interceptor = interceptor) {
                MotdTheme(dynamicColor = false, emojiRenderer = selectedRenderer.value) {
                    Composer(
                        value = draft.value,
                        onValueChange = { draft.value = it },
                        onSend = {},
                        enabled = true,
                        ircFormattingEnabled = false,
                    )
                }
            }
        }
        val field = compose.onNodeWithTag("chat_composer_field")
        field.performClick().assertIsFocused()
        field.performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(selection.start, selection.end, false)) }
        val originalRequest = compose.runOnIdle { inputRequests.single() }
        for (next in listOf(renderer, null)) {
            compose.runOnIdle { selectedRenderer.value = next }
            field.assertIsFocused()
            assertEquals(selection, field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            assertEmojiSpans(field.displayedText(), selected = next != null)
            compose.runOnIdle {
                assertEquals(original, draft.value)
                assertEquals(1, inputRequests.size)
                assertSame(originalRequest, inputRequests.single())
            }
        }
    }

    @Test
    fun fullZwjAndMixedToneSequencesCopyAndSendUnchangedForEveryRenderer() {
        val text = "before $zwj and $mixedTone after"
        val draft = mutableStateOf(TextFieldValue(text, TextRange(text.length)))
        val selectedRenderer = mutableStateOf<EmojiRenderer?>(null)
        val sent = mutableListOf<String>()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
        compose.setContent {
            MotdTheme(dynamicColor = false, emojiRenderer = selectedRenderer.value) {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = { sent += draft.value.text },
                    enabled = true,
                )
            }
        }
        val field = compose.onNodeWithTag("chat_composer_field")
        field.performClick()
        for (next in listOf(null, renderer, null)) {
            compose.runOnIdle { selectedRenderer.value = next }
            assertEmojiSpans(field.displayedText(), selected = next != null)
            field.performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(0, text.length, false)) }
            field.performSemanticsAction(SemanticsActions.CopyText) { assertTrue(it()) }
            compose.runOnIdle {
                assertEquals(
                    text,
                    clipboard.primaryClip
                        ?.getItemAt(0)
                        ?.text
                        ?.toString(),
                )
                assertEquals(text, draft.value.text)
            }
            compose.onNodeWithTag("chat_composer_send").performClick()
            compose.runOnIdle { assertEquals(text, sent.last()) }
        }
        compose.runOnIdle { assertEquals(listOf(text, text, text), sent) }
    }

    private fun assertEmojiSpans(
        text: AnnotatedString,
        selected: Boolean,
    ) {
        val spans = text.spanStyles.filter { it.item.fontFamily == FontFamily.Default }
        val expected = if (selected) listOf(zwj, mixedTone).map { text.indexOf(it) to text.indexOf(it) + it.length } else emptyList()
        assertEquals(expected, spans.map { it.start to it.end })
        spans.forEach { assertEquals(SpanStyle(fontFamily = FontFamily.Default), it.item) }
    }

    private fun SemanticsNodeInteraction.displayedText(): AnnotatedString {
        val results = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single().layoutInput.text
    }
}
