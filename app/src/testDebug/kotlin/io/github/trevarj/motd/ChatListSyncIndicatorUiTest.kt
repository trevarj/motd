package io.github.trevarj.motd

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.ui.chatlist.ChatListRowItem
import io.github.trevarj.motd.ui.chatlist.ChatListSyncIndicator
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Per-row sync cues on [ChatListRowItem]: every cue (spinner, queued/waiting ring, error dot,
 * unavailable glyph) renders inline on the title line after the network chip, so the trailing
 * unread count stays readable through the whole sync lifecycle.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ChatListSyncIndicatorUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun syncing_rendersSpinnerBadge() {
        setRow(ChatListSyncIndicator.SYNCING)

        compose.onNodeWithTag("chatlist_row_sync_syncing", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun syncing_spinnerCoexistsWithTheUnreadCount() {
        setRow(ChatListSyncIndicator.SYNCING, unreadCount = 7)

        compose.onNodeWithTag("chatlist_row_sync_syncing", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chatlist_row_unread_badge", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun queued_rendersDimmedRingBadge() {
        setRow(ChatListSyncIndicator.QUEUED)

        compose.onNodeWithTag("chatlist_row_sync_queued", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun waiting_rendersItsOwnRingBadge() {
        setRow(ChatListSyncIndicator.WAITING)

        compose.onNodeWithTag("chatlist_row_sync_waiting", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(
            0,
            compose
                .onAllNodesWithTag("chatlist_row_sync_queued", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
    }

    @Test
    fun unavailable_rendersMutedTerminalCue() {
        setRow(ChatListSyncIndicator.UNAVAILABLE)

        compose.onNodeWithTag("chatlist_row_sync_unavailable", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun error_rendersDotBadge() {
        setRow(ChatListSyncIndicator.ERROR)

        compose.onNodeWithTag("chatlist_row_sync_error", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun error_dotCoexistsWithTheUnreadCount() {
        setRow(ChatListSyncIndicator.ERROR, unreadCount = 7)

        compose.onNodeWithTag("chatlist_row_sync_error", useUnmergedTree = true).assertIsDisplayed()
        // A failed sync must never hide how much is unread: the dot lives on the title line.
        compose.onNodeWithTag("chatlist_row_unread_badge", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun none_rendersNoSyncBadge() {
        setRow(ChatListSyncIndicator.NONE)

        listOf(
            "chatlist_row_sync_syncing",
            "chatlist_row_sync_queued",
            "chatlist_row_sync_waiting",
            "chatlist_row_sync_unavailable",
            "chatlist_row_sync_error",
        ).forEach { tag ->
            assertEquals(0, compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size)
        }
    }

    private fun setRow(
        indicator: ChatListSyncIndicator,
        unreadCount: Int = 0,
    ) {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListRowItem(
                    row = queryRow(unreadCount = unreadCount),
                    showNetworkChip = false,
                    onClick = {},
                    onLongClick = {},
                    syncIndicator = indicator,
                )
            }
        }
    }

    private fun queryRow(unreadCount: Int = 0) =
        ChatListRow(
            bufferId = 1,
            networkId = 1,
            networkName = "Libera",
            displayName = "alice",
            type = BufferType.QUERY,
            pinned = false,
            muted = false,
            lastMessageText = "hello",
            lastMessageSender = "alice",
            lastMessageTime = 1L,
            unreadCount = unreadCount,
            mentionCount = 0,
        )
}
