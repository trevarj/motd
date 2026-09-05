package io.github.trevarj.motd

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.ui.chat.CHAT_HISTORY_LOADING_TAG
import io.github.trevarj.motd.ui.chat.CHAT_HISTORY_LOAD_OLDER_TAG
import io.github.trevarj.motd.ui.chat.CHAT_HISTORY_MORE_TAG
import io.github.trevarj.motd.ui.chat.CHAT_HISTORY_RETRY_TAG
import io.github.trevarj.motd.ui.chat.ChatHistoryFooter
import io.github.trevarj.motd.ui.chat.ChatHistoryUiState
import io.github.trevarj.motd.ui.chat.FOOTER_APPEARANCE_DELAY_MS
import io.github.trevarj.motd.ui.chat.FOOTER_MIN_VISIBLE_MS
import io.github.trevarj.motd.ui.chat.rememberFooterUiState
import io.github.trevarj.motd.ui.theme.MotdTheme
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
class ChatHistoryFooterUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun recoverableAppendErrorRetryHasStableTagAndMinimumTouchHeight() {
        var retries = 0
        compose.setContent {
            MotdTheme {
                ChatHistoryFooter(
                    state = ChatHistoryUiState.Retry,
                    onRetry = { retries++ },
                )
            }
        }

        compose
            .onNodeWithTag(CHAT_HISTORY_RETRY_TAG)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, retries)
    }

    @Test
    fun appendLoadingRendersShimmerFooter() {
        compose.setContent {
            MotdTheme {
                ChatHistoryFooter(
                    state = ChatHistoryUiState.Loading,
                    onRetry = {},
                )
            }
        }

        // Scroll-driven APPEND surfaces the shimmer footer while an older page is in flight.
        compose.onNodeWithTag(CHAT_HISTORY_LOADING_TAG).assertIsDisplayed()
    }

    @Test
    fun armedLadderRendersStaticStatusLineWithoutASpinner() {
        compose.setContent {
            MotdTheme {
                ChatHistoryFooter(
                    state = ChatHistoryUiState.Armed,
                    onRetry = {},
                )
            }
        }

        // More history exists but nothing is on the wire: say so, and do not animate a fetch that
        // is not happening.
        compose.onNodeWithTag(CHAT_HISTORY_MORE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Older messages").assertExists()
        compose.onAllNodesWithTag(CHAT_HISTORY_LOADING_TAG).assertCountEquals(0)
    }

    /**
     * Window boundaries themselves are pinned deterministically by `ChatModelsTest`; what this test
     * owns is that the composable actually applies them off the frame clock. Advances are therefore
     * deliberately loose — a state change lands between frames, so every window starts up to one
     * frame late and exact-edge advances would only measure frame rounding.
     */
    @Test
    fun footerDebouncesTheShimmerAcrossItsAppearanceAndMinimumVisibleWindows() {
        var raw by mutableStateOf<ChatHistoryUiState>(ChatHistoryUiState.Armed)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MotdTheme {
                ChatHistoryFooter(state = rememberFooterUiState(raw), onRetry = {})
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(CHAT_HISTORY_MORE_TAG).assertIsDisplayed()

        // A page that starts and lands inside the appearance window never paints a spinner.
        compose.runOnUiThread { raw = ChatHistoryUiState.Loading }
        compose.mainClock.advanceTimeBy(FOOTER_APPEARANCE_DELAY_MS / 2)
        compose.onAllNodesWithTag(CHAT_HISTORY_LOADING_TAG).assertCountEquals(0)
        compose.onNodeWithTag(CHAT_HISTORY_MORE_TAG).assertIsDisplayed()

        compose.mainClock.advanceTimeUntil {
            compose.onAllNodesWithTag(CHAT_HISTORY_LOADING_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(CHAT_HISTORY_LOADING_TAG).assertIsDisplayed()

        // Once shown it holds, so a generation swap back to Armed cannot blink it out a frame later.
        compose.runOnUiThread { raw = ChatHistoryUiState.Armed }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(CHAT_HISTORY_LOADING_TAG).assertIsDisplayed()

        compose.mainClock.advanceTimeBy(FOOTER_MIN_VISIBLE_MS)
        compose.onNodeWithTag(CHAT_HISTORY_MORE_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(CHAT_HISTORY_LOADING_TAG).assertCountEquals(0)
    }

    @Test
    fun stalledLadderOffersLoadOlderWithStableTagAndMinimumTouchHeight() {
        // A stalled ladder is the one stop the reader can do something about, and it sits directly
        // above the oldest message — where they are already pulling for more.
        var loads = 0
        compose.setContent {
            MotdTheme {
                ChatHistoryFooter(
                    state = ChatHistoryUiState.LoadOlder,
                    onRetry = { loads++ },
                )
            }
        }

        compose
            .onNodeWithTag(CHAT_HISTORY_LOAD_OLDER_TAG)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, loads)
    }
}
