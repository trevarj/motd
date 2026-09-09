package io.github.trevarj.motd.agentwire

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.irc.agentwire.AgentwireDiagnosticCheck
import io.github.trevarj.motd.irc.agentwire.AgentwireDiagnosticReport
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class AgentwireDiagnosticsUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun localFactsRemainVisibleWithoutBridgeSupport() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AgentwireDiagnosticsPanel(
                    state = AgentwireUiState(gate = AgentwireGate.ACTIVE, connected = true, sync = AgentwireSyncState.Ready),
                    onRefresh = {},
                )
            }
        }

        compose.onNodeWithText("Local connection").assertIsDisplayed()
        compose.onNodeWithText("Connection: Connected").assertIsDisplayed()
        compose.onNodeWithText("Topic: Valid").assertIsDisplayed()
        compose.onNodeWithText("IRC capabilities: Available").assertIsDisplayed()
        compose.onNodeWithText("Sync: Ready").assertIsDisplayed()
        compose.onNodeWithText("No bridge diagnostic report yet.").assertIsDisplayed()
        compose.onNodeWithText("Bridge diagnostics are unavailable until a supporting bridge is synchronized.").assertIsDisplayed()
        compose.onNodeWithText("Refresh").assertIsNotEnabled()
    }

    @Test
    fun reportMarksStalenessAndDisablesRefreshWhileLoading() {
        var state by mutableStateOf(
            AgentwireUiState(
                gate = AgentwireGate.ACTIVE,
                connected = true,
                sync = AgentwireSyncState.Ready,
                actions = setOf("diagnostics.request"),
                capabilities = setOf("diagnostics"),
                diagnosticReport =
                    AgentwireDiagnosticReport(
                        generatedAt = 1_785_400_000_000,
                        checks =
                            listOf(
                                AgentwireDiagnosticCheck(
                                    code = "backend.ready",
                                    status = "ok",
                                    explanation = "Backend is ready.",
                                    facts = buildJsonObject { put("ready", true) },
                                    nextStep = "Send a prompt.",
                                ),
                            ),
                    ),
            ),
        )
        var refreshes = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AgentwireDiagnosticsPanel(state = state, onRefresh = { refreshes += 1 })
            }
        }

        compose.onNodeWithText("Bridge report").assertIsDisplayed()
        compose.onNodeWithText("Ok: Backend is ready.").assertIsDisplayed()
        compose.onNodeWithText("ready: true").assertIsDisplayed()
        compose.onNodeWithText("Next step: Send a prompt.").assertIsDisplayed()
        compose.onNodeWithText("Refresh").performClick()
        compose.runOnIdle {
            assertEquals(1, refreshes)
            state = state.copy(diagnosticsStale = true, diagnosticsLoading = true)
        }
        compose.onNodeWithText("(stale)", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Refreshing…").assertIsNotEnabled()
    }
}
