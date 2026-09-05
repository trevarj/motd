package io.github.trevarj.motd

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.ui.components.CHAT_GAP_DIVIDER_FAILED_TAG
import io.github.trevarj.motd.ui.components.CHAT_GAP_DIVIDER_IDLE_TAG
import io.github.trevarj.motd.ui.components.CHAT_GAP_DIVIDER_LOADING_TAG
import io.github.trevarj.motd.ui.components.CHAT_GAP_DIVIDER_TAG
import io.github.trevarj.motd.ui.components.CHAT_GAP_DIVIDER_UNRECOVERABLE_TAG
import io.github.trevarj.motd.ui.components.HistoryGapDivider
import io.github.trevarj.motd.ui.components.HistoryGapState
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The seam divider's four presentation shapes, exercised as a plain component — no timeline, no
 * ViewModel. Loading, Idle, and Failed all share [CHAT_GAP_DIVIDER_TAG] on the root, which is what
 * lets E2E scroll to "the seam" without caring which shape composed; their inner variant tags are
 * only discoverable in the unmerged semantics tree, since the interactive/spinner nodes merge their
 * descendants for accessibility exactly as a real screen reader would see them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class HistoryGapDividerUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun loadingSharesTheRootTagAndTagsItsSpinner() {
        compose.setContent {
            MotdTheme {
                HistoryGapDivider(state = HistoryGapState.Loading, onLoad = {})
            }
        }

        compose.onNodeWithTag(CHAT_GAP_DIVIDER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CHAT_GAP_DIVIDER_LOADING_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun idleOffersTheSameLoadTapAsFailedWithStableTagAndMinimumTouchHeight() {
        // Idle is the honest resting state — nothing is fetching this gap yet — but it must still
        // route a tap through the exact same callback ChatViewModel wires Failed's retry to.
        var loads = 0
        compose.setContent {
            MotdTheme {
                HistoryGapDivider(state = HistoryGapState.Idle, onLoad = { loads++ })
            }
        }

        compose
            .onNodeWithTag(CHAT_GAP_DIVIDER_TAG)
            .assertHeightIsAtLeast(48.dp)
            .assertHasClickAction()
            .performClick()
        assertEquals(1, loads)
        compose.onNodeWithTag(CHAT_GAP_DIVIDER_IDLE_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun failedOffersARetryTapWithStableTagAndMinimumTouchHeight() {
        var retries = 0
        compose.setContent {
            MotdTheme {
                HistoryGapDivider(state = HistoryGapState.Failed, onLoad = { retries++ })
            }
        }

        compose
            .onNodeWithTag(CHAT_GAP_DIVIDER_TAG)
            .assertHeightIsAtLeast(48.dp)
            .assertHasClickAction()
            .performClick()
        assertEquals(1, retries)
        compose.onNodeWithTag(CHAT_GAP_DIVIDER_FAILED_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun unrecoverableIsPermanentAndNonInteractiveUnderItsOwnTag() {
        var loads = 0
        compose.setContent {
            MotdTheme {
                HistoryGapDivider(state = HistoryGapState.Unrecoverable, onLoad = { loads++ })
            }
        }

        // Its own distinct root tag, never the shared recoverable one, and no tap to swallow.
        compose.onNodeWithTag(CHAT_GAP_DIVIDER_UNRECOVERABLE_TAG).assertHasNoClickAction()
        assertEquals(0, loads)
    }
}
