package io.github.trevarj.motd.agentwire

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.trevarj.motd.ai.text.TextTermination
import io.github.trevarj.motd.ai.text.TextTransformResult
import io.github.trevarj.motd.ui.ai.AiTextSheet
import io.github.trevarj.motd.ui.ai.AiTextSource
import io.github.trevarj.motd.ui.ai.AiTextUiState
import io.github.trevarj.motd.ui.ai.source
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
class AgentwireTimelineUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bodyHoldTranslationInvalidatesOnStreamAndSessionChange() {
        val user = AgentwireTimelineItem(id = "user", kind = "user.prompt", at = 0, sid = "session", tid = "turn", title = "You", body = "**literal** prompt")
        val assistant = user.copy(id = "assistant", kind = "assistant.delta", title = "Assistant", body = "**Ready** report", running = true)
        val tool = user.copy(id = "tool", kind = "plan.updated", title = "Plan", body = "Tool-only body")
        var state by mutableStateOf(AgentwireUiState(epoch = "one", activeSid = "session", timeline = listOf(user, assistant, tool)))
        var ai by mutableStateOf<AiTextUiState>(AiTextUiState.Closed)
        var kind: String? = null
        var session: String? = null
        var frozenBody: String? = null
        compose.setContent {
            LaunchedEffect(state, ai.source()) {
                val selected = ai.source() as? AiTextSource.TransientMessage ?: return@LaunchedEffect
                if (state.translationLeaseBody(selected.key, kind, session, frozenBody) != selected.text) ai = AiTextUiState.Closed
            }
            MotdTheme(dynamicColor = false) {
                Column {
                    state.timeline.forEach { item ->
                        AgentwireTimelineCard(item, null, true, {}, onTranslateBody = {
                            item.translationBody()?.let { body ->
                                kind = item.kind
                                session = state.activeSid
                                frozenBody = item.body
                                ai = AiTextUiState.Choosing(AiTextSource.TransientMessage("agentwire:${state.epoch}:${item.timelineKey()}", body))
                            }
                        })
                    }
                }
                AiTextSheet(ai, emptyList(), null, onGenerate = {
                    val source = (ai as AiTextUiState.Choosing).source as AiTextSource.TransientMessage
                    ai = AiTextUiState.Result(1, source, source.text, TextTransformResult("Rapport prêt.", TextTermination.EOG), 1)
                }, onTargetSelected = {}, onDismiss = { ai = AiTextUiState.Closed }, onOpenSetup = {}, onManageStyles = {})
            }
        }
        compose.onNodeWithText("**literal** prompt").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.runOnIdle { assertEquals(user.body, (ai.source() as AiTextSource.TransientMessage).text) }
        compose.onNodeWithTag("ai_text_close").performClick()
        compose.onNodeWithText("Ready report").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.runOnIdle { assertEquals("Ready report", (ai.source() as AiTextSource.TransientMessage).text) }
        compose.onNodeWithTag("ai_text_translate").performClick()
        compose.onNodeWithText("Rapport prêt.").assertIsDisplayed()
        compose.onNodeWithTag("ai_text_apply").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(timeline = listOf(user, assistant.copy(body = "**New** body"), tool)) }
        compose.onNodeWithTag("ai_text_sheet").assertDoesNotExist()
        compose.onNodeWithText("New body").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.runOnIdle { state = state.copy(epoch = "two") }
        compose.onNodeWithTag("ai_text_sheet").assertDoesNotExist()
        compose.onNodeWithText("New body").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.runOnIdle { state = state.copy(activeSid = "other") }
        compose.onNodeWithTag("ai_text_sheet").assertDoesNotExist()
        compose.onNodeWithText("New body").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.runOnIdle { state = state.copy(timeline = listOf(user, assistant.copy(kind = "assistant.completed", body = "**New** body"), tool)) }
        compose.onNodeWithTag("ai_text_sheet").assertDoesNotExist()
        compose.onNodeWithText("New body").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.runOnIdle { state = state.copy(timeline = listOf(user, assistant.copy(kind = "assistant.completed", body = "*New* body"), tool)) }
        compose.onNodeWithTag("ai_text_sheet").assertDoesNotExist()
        compose.onNodeWithText("Tool-only body").assertIsDisplayed()
        assertTrue(
            !compose
                .onNodeWithText("Tool-only body")
                .fetchSemanticsNode()
                .config
                .contains(SemanticsActions.OnLongClick),
        )
    }

    @Test
    fun assistantMarkdownFormatsStreamingAndReplayWithoutChangingUserText() {
        val markdown = "- **eyeoh** ... **Coverage:** _complete_"
        var assistant by mutableStateOf(
            AgentwireTimelineItem(
                id = "assistant",
                kind = "assistant.delta",
                at = 0,
                sid = "session",
                tid = "turn",
                title = "Assistant",
                body = "- **eye",
                running = true,
            ),
        )
        val user = assistant.copy(id = "user", kind = "user.prompt", title = "You", body = markdown, running = false)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Column {
                    AgentwireTimelineCard(assistant, actionStatus = null, expandedOverride = null, onToggleExpanded = {})
                    AgentwireTimelineCard(user, actionStatus = null, expandedOverride = null, onToggleExpanded = {})
                }
            }
        }

        compose.onNodeWithText("- **eye", useUnmergedTree = true).assertIsDisplayed()

        fun assertRenderedMarkdown() {
            val rendered =
                compose
                    .onNodeWithText("- eyeoh ... Coverage: complete", useUnmergedTree = true)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .single()
            assertEquals(
                listOf("eyeoh", "Coverage:"),
                rendered.spanStyles
                    .filter { it.item.fontWeight == FontWeight.Bold }
                    .map { rendered.text.substring(it.start, it.end) },
            )
            assertEquals(
                listOf("complete"),
                rendered.spanStyles
                    .filter { it.item.fontStyle == FontStyle.Italic }
                    .map { rendered.text.substring(it.start, it.end) },
            )
            val prompt =
                compose
                    .onNodeWithText(markdown, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .single()
            assertTrue(prompt.spanStyles.isEmpty())
        }

        compose.runOnIdle { assistant = assistant.copy(body = markdown) }
        assertRenderedMarkdown()
        compose.runOnIdle {
            assistant = assistant.copy(kind = "assistant.completed", running = false, historical = true)
        }
        assertRenderedMarkdown()
    }
}
