package io.github.trevarj.motd.agentwire

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1200dp")
class AgentwireActivityUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settledRunExpandsFullDetailsAndKeepsItsExpansionWhenItGrows() {
        val expanded = mutableStateMapOf<String, Boolean>()
        val first =
            tool("read", "file read", "Read source").copy(
                data =
                    buildJsonObject {
                        put("kind", "file read")
                        put("input", "cat source.kt")
                        put("output", "full tool output")
                        put("diff", "+retained change")
                    },
            )
        var run by mutableStateOf(AgentwireDisplayRow.ToolRun(listOf(first, tool("check", "shell", "Execute check").copy(success = false))))
        compose.setContent {
            MotdTheme(dynamicColor = false) { AgentwireToolRunCard(run, expanded) }
        }
        compose.onNodeWithText("Observed activity: 2 tools · 1 failed").assertIsDisplayed().performClick()
        compose.onNodeWithText("Commands 1 · Reads 1").assertIsDisplayed()
        compose.onNodeWithText("Read source").performClick()
        compose.onNodeWithText("cat source.kt", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("full tool output", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("+retained change", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { run = AgentwireDisplayRow.ToolRun(run.tools + tool("web", "web search", "Search reference")) }
        compose.onNodeWithText("Observed activity: 3 tools · 1 failed").assertIsDisplayed()
        compose.onNodeWithText("full tool output", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Observed activity: 3 tools · 1 failed").performClick()
        compose.onNodeWithText("full tool output", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun observedTurnSummaryKeepsNarrationPlanPromptAndRunningToolVisible() {
        val tool = tool("running", "shell", "Running command").copy(kind = "tool.started", running = true)
        val base = tool.copy(running = false, success = null)
        val activity = agentwireToolActivity(listOf(tool))
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Column {
                    AgentwireTimelineCard(base.copy(kind = "user.prompt", title = "You", body = "Please inspect"), null, null, {})
                    AgentwireTimelineCard(base.copy(kind = "assistant.completed", title = "Assistant", body = "Inspecting source"), null, null, {})
                    AgentwireTimelineCard(base.copy(kind = "plan.updated", title = "Plan", body = "Read then check"), null, null, {})
                    AgentwireTimelineCard(base.copy(kind = "turn.started", title = "Turn running"), null, null, {}, activity)
                    AgentwireToolCard(tool, "tool:running", mutableStateMapOf())
                }
            }
        }
        compose.onNodeWithText("Please inspect").assertIsDisplayed()
        compose.onNodeWithText("Inspecting source").assertIsDisplayed()
        compose.onNodeWithText("Plan").assertIsDisplayed()
        compose.onNodeWithText("Turn running").assertIsDisplayed()
        compose.onNodeWithText("Observed activity: 1 tool").assertIsDisplayed()
        compose.onNodeWithText("Running command").assertIsDisplayed()
    }

    private fun tool(
        id: String,
        kind: String,
        title: String,
    ) = AgentwireTimelineItem(
        id = id,
        kind = "tool.completed",
        at = 1,
        sid = "session",
        tid = "turn",
        title = title,
        body = null,
        data = buildJsonObject { put("kind", kind) },
        backendItemId = id,
    )
}
