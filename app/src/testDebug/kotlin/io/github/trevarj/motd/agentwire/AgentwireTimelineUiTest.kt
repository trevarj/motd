package io.github.trevarj.motd.agentwire

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
