package io.github.trevarj.motd

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.ui.chat.CHAT_HISTORY_PARTIAL_CHIP_TAG
import io.github.trevarj.motd.ui.chat.CHAT_HISTORY_SYNC_FAILURE_TAG
import io.github.trevarj.motd.ui.chat.CHAT_HISTORY_SYNC_RETRY_TAG
import io.github.trevarj.motd.ui.chat.CHAT_TITLE_SYNC_SPINNER_TAG
import io.github.trevarj.motd.ui.chat.ChatTitleSyncSpinner
import io.github.trevarj.motd.ui.chat.TimelineHistoryStaleChip
import io.github.trevarj.motd.ui.chat.TimelineHistorySyncFailure
import io.github.trevarj.motd.ui.chat.TimelineTopOverlays
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
class ChatHistorySyncIndicatorUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun titleSpinnerReportsEveryInFlightSyncState() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MotdTheme {
                Column {
                    ChatTitleSyncSpinner(HistorySyncStatus.Queued)
                    ChatTitleSyncSpinner(HistorySyncStatus.Syncing)
                }
            }
        }
        compose.waitForIdle()

        // No appearance grace period: one micro fade is all that stands between the sync starting
        // and the reader seeing it.
        compose.mainClock.advanceTimeBy(FADE_BUDGET_MS)
        compose.onAllNodesWithTag(CHAT_TITLE_SYNC_SPINNER_TAG).assertCountEquals(2)
    }

    @Test
    fun settledStatesLeaveTheTitleAlone() {
        // Everything that is not Queued/Syncing: nothing is in flight, so the title carries no cue.
        // Failure is reported by its own pill, not here.
        compose.setContent {
            MotdTheme {
                Column {
                    ChatTitleSyncSpinner(HistorySyncStatus.Idle)
                    ChatTitleSyncSpinner(HistorySyncStatus.AwaitingConnection)
                    ChatTitleSyncSpinner(HistorySyncStatus.Unavailable)
                    ChatTitleSyncSpinner(HistorySyncStatus.Partial("fixture"))
                    ChatTitleSyncSpinner(HistorySyncStatus.Failed("fixture"))
                }
            }
        }

        compose.onAllNodesWithTag(CHAT_TITLE_SYNC_SPINNER_TAG).assertCountEquals(0)
    }

    @Test
    fun aLongSyncNeverEscalatesOverTheConversation() {
        // The point of moving the cue into the title bar: however long a sync runs, it stays a
        // spinner beside the name and never grows into chrome covering the rows being read.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MotdTheme {
                Column {
                    ChatTitleSyncSpinner(HistorySyncStatus.Syncing)
                    TimelineHistorySyncFailure(
                        status = HistorySyncStatus.Syncing,
                        retryEnabled = true,
                        onRetry = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.mainClock.advanceTimeBy(LONG_SYNC_MS)
        compose.mainClock.advanceTimeBy(FADE_BUDGET_MS)
        compose.onNodeWithTag(CHAT_TITLE_SYNC_SPINNER_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(CHAT_HISTORY_SYNC_FAILURE_TAG).assertCountEquals(0)
    }

    @Test
    fun titleSpinnerClearsWhenTheSyncSettles() {
        var status by mutableStateOf<HistorySyncStatus>(HistorySyncStatus.Syncing)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MotdTheme {
                ChatTitleSyncSpinner(status)
            }
        }
        compose.waitForIdle()

        compose.mainClock.advanceTimeBy(FADE_BUDGET_MS)
        compose.onNodeWithTag(CHAT_TITLE_SYNC_SPINNER_TAG).assertIsDisplayed()

        compose.runOnUiThread { status = HistorySyncStatus.Idle }
        compose.mainClock.advanceTimeBy(FADE_BUDGET_MS)
        compose.onAllNodesWithTag(CHAT_TITLE_SYNC_SPINNER_TAG).assertCountEquals(0)
    }

    @Test
    fun partialStateDoesNotCoverCachedMessages() {
        compose.setContent {
            MotdTheme {
                Column {
                    ChatTitleSyncSpinner(HistorySyncStatus.Partial("fixture"))
                    TimelineHistorySyncFailure(
                        status = HistorySyncStatus.Partial("fixture"),
                        retryEnabled = true,
                        onRetry = {},
                    )
                    TimelineHistoryStaleChip(
                        status = HistorySyncStatus.Partial("fixture"),
                        timelineEmpty = false,
                    )
                }
            }
        }

        compose.onAllNodesWithTag(CHAT_HISTORY_SYNC_FAILURE_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(CHAT_TITLE_SYNC_SPINNER_TAG).assertCountEquals(0)
        // The cached rows stay readable; only an advisory, action-free chip marks them stale.
        compose.onNodeWithTag(CHAT_HISTORY_PARTIAL_CHIP_TAG).assertIsDisplayed()
        compose.onNodeWithText("History may be incomplete").assertIsDisplayed()
        compose.onAllNodesWithTag(CHAT_HISTORY_SYNC_RETRY_TAG).assertCountEquals(0)
    }

    @Test
    fun staleChipStaysHiddenWithoutCachedRows() {
        compose.setContent {
            MotdTheme {
                Column {
                    TimelineHistoryStaleChip(
                        status = HistorySyncStatus.Partial("fixture"),
                        timelineEmpty = true,
                    )
                    TimelineHistoryStaleChip(
                        status = HistorySyncStatus.Failed("fixture"),
                        timelineEmpty = false,
                    )
                }
            }
        }

        compose.onAllNodesWithTag(CHAT_HISTORY_PARTIAL_CHIP_TAG).assertCountEquals(0)
    }

    @Test
    fun failedStateKeepsAccessibleManualRetry() {
        var syncRetries = 0
        var pagingRetries = 0
        compose.setContent {
            MotdTheme {
                Column {
                    ChatTitleSyncSpinner(HistorySyncStatus.Failed("fixture"))
                    TimelineHistorySyncFailure(
                        status = HistorySyncStatus.Failed("fixture"),
                        retryEnabled = true,
                        // Mirrors the screen's composed action: the coordinator re-runs the
                        // reconciliation pass and Paging re-attempts the failed fetch.
                        onRetry = {
                            syncRetries++
                            pagingRetries++
                        },
                    )
                }
            }
        }

        compose.onNodeWithText("Couldn't sync messages").assertIsDisplayed()
        // A failure is not progress: the title cue is gone, the pill carries the whole message.
        compose.onAllNodesWithTag(CHAT_TITLE_SYNC_SPINNER_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(CHAT_HISTORY_PARTIAL_CHIP_TAG).assertCountEquals(0)
        compose
            .onNodeWithTag(CHAT_HISTORY_SYNC_RETRY_TAG)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, syncRetries)
        assertEquals(1, pagingRetries)
    }

    @Test
    fun topOverlaysKeepSyncBelowAudioWithoutOverlap() {
        compose.setContent {
            Box(Modifier.fillMaxWidth().height(160.dp)) {
                TimelineTopOverlays(
                    audioPlayer = {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .testTag("fixture_audio_player"),
                        )
                    },
                    historyFailure = {
                        Box(Modifier.size(24.dp).testTag("fixture_history_failure"))
                    },
                    staleChip = {
                        Box(Modifier.size(24.dp).testTag("fixture_stale_chip"))
                    },
                )
            }
        }

        val audioBounds = compose.onNodeWithTag("fixture_audio_player").getUnclippedBoundsInRoot()
        val failureBounds = compose.onNodeWithTag("fixture_history_failure").getUnclippedBoundsInRoot()
        val chipBounds = compose.onNodeWithTag("fixture_stale_chip").getUnclippedBoundsInRoot()
        assertTrue("audio player was not pinned to the timeline top", audioBounds.top <= 1.dp)
        assertTrue("sync failure overlapped the audio player", failureBounds.top >= audioBounds.bottom)
        assertTrue("stale chip overlapped the audio player", chipBounds.top >= audioBounds.bottom)
    }

    private companion object {
        /** Frame budget that lets a `MotdMotion.micro*` fade finish under the manual clock. */
        const val FADE_BUDGET_MS = 500L

        /** Comfortably longer than any sync a reader would sit through without complaint. */
        const val LONG_SYNC_MS = 10_000L
    }
}
