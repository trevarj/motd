package io.github.trevarj.motd.agentwire

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A stalled handshake must never present as a spinner with no explanation: each distinguishable
 * cause has its own copy and its own recovery action.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class AgentwireSyncFailureUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun eachSyncFailureNamesItsCauseAndOffersRecovery() {
        var retries = 0
        var joins = 0
        var sync by mutableStateOf<AgentwireSyncState>(
            AgentwireSyncState.Failed(
                AgentwireSyncFailure.Timeout(
                    attempts = 6,
                    counters = IgnoreCounters(mapOf(IgnoreReason.UNTRUSTED_ACCOUNT to 12)),
                ),
            ),
        )
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                when (val phase = sync) {
                    is AgentwireSyncState.Failed -> {
                        AgentwireSyncFailureCard(phase, "#claude", onRetry = { retries += 1 })
                    }

                    is AgentwireSyncState.NotJoined -> {
                        AgentwireNotJoinedCard("#claude", onJoin = { joins += 1 })
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }

        compose.onNodeWithTag("agentwire_sync_failure_card").assertIsDisplayed()
        compose.onNodeWithText("Agent sync timed out").assertIsDisplayed()
        compose
            .onNodeWithText("No response from the agent after 30 seconds", substring = true)
            .assertIsDisplayed()
        // The ignore counters are the diagnosability payload; the card must surface the tally.
        compose
            .onNodeWithText("12 agent event(s) arrived but were ignored", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithTag("agentwire_sync_retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }

        sync = AgentwireSyncState.Failed(AgentwireSyncFailure.Rejected("unknown topic agent"))
        compose.onNodeWithText("Agent sync rejected").assertIsDisplayed()
        compose
            .onNodeWithText("The bridge rejected the sync request: unknown topic agent")
            .assertIsDisplayed()
        compose.onNodeWithText("Retry sync").assertIsDisplayed()

        sync = AgentwireSyncState.Failed(AgentwireSyncFailure.ProtocolMismatch("unknown envelope field"))
        compose.onNodeWithText("Incompatible agent messages").assertIsDisplayed()
        compose.onNodeWithText("unknown envelope field", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Agentwire v1", substring = true).assertIsDisplayed()

        sync = AgentwireSyncState.Failed(AgentwireSyncFailure.SendFailed("the write did not reach the server"))
        compose.onNodeWithText("Cannot reach the channel").assertIsDisplayed()
        compose
            .onNodeWithText("Sending the sync request failed: the write did not reach the server")
            .assertIsDisplayed()

        sync = AgentwireSyncState.NotJoined
        compose.onNodeWithText("Not in this channel").assertIsDisplayed()
        compose.onNodeWithText("Agent events flow through #claude", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Join and sync").performClick()
        compose.runOnIdle {
            assertEquals(1, joins)
            assertEquals("joining must not be reported as a retry", 1, retries)
        }
    }
}
