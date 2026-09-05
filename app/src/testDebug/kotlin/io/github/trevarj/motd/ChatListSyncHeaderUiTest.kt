package io.github.trevarj.motd

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import io.github.trevarj.motd.ui.chatlist.ChatListSyncChrome
import io.github.trevarj.motd.ui.chatlist.ChatListSyncHeader
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pinned aggregate sync line: it appears only for a resolved (post-anti-flash) chrome state,
 * carries a determinate bar while a pass runs, drops the bar when nothing is connected, and keeps
 * the changing count out of the live region so TalkBack does not announce every tick.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ChatListSyncHeaderUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun hidden_rendersNothing() {
        setHeader(ChatListSyncChrome.Hidden)

        assertEquals(
            0,
            compose.onAllNodesWithTag("chatlist_sync_header", useUnmergedTree = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun syncing_rendersCountAndDeterminateProgress() {
        setHeader(ChatListSyncChrome.Syncing(done = 12, total = 42))

        compose.onNodeWithTag("chatlist_sync_header", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chatlist_sync_header_progress", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chatlist_sync_header_count", useUnmergedTree = true).assertTextEquals("12/42")
    }

    @Test
    fun waiting_dropsTheProgressBar() {
        setHeader(ChatListSyncChrome.Waiting(queued = 5))

        compose.onNodeWithTag("chatlist_sync_header_waiting", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(
            0,
            compose
                .onAllNodesWithTag("chatlist_sync_header_progress", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
    }

    @Test
    fun onlyTheStaticLabelIsALiveRegion() {
        setHeader(ChatListSyncChrome.Syncing(done = 12, total = 42))

        val label =
            compose
                .onNodeWithTag("chatlist_sync_header_label", useUnmergedTree = true)
                .fetchSemanticsNode()
        val count =
            compose
                .onNodeWithTag("chatlist_sync_header_count", useUnmergedTree = true)
                .fetchSemanticsNode()

        assertTrue(label.config.contains(SemanticsProperties.LiveRegion))
        // The count changes on every settled buffer; announcing it would spam TalkBack.
        assertFalse(count.config.contains(SemanticsProperties.LiveRegion))
    }

    private fun setHeader(chrome: ChatListSyncChrome) {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListSyncHeader(chrome = chrome)
            }
        }
    }
}
