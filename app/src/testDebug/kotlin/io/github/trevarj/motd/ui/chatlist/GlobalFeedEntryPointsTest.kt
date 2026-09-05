package io.github.trevarj.motd.ui.chatlist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The Global Feed lab owns both ways into the feed: the drawer row and the chat-list toolbar icon.
 * With the lab off (the default) neither exists, which is also what keeps `GlobalFeedRoute`
 * unreachable — there is no other navigation into it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlobalFeedEntryPointsTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test fun drawerRow_hidesByDefaultAndAppearsWithTheLab() {
        assertEquals(false, ChatListState().globalFeedEnabled)

        setDrawer(globalFeedEnabled = false)
        assertEquals(0, compose.onAllNodesWithTag("drawer_open_feed").fetchSemanticsNodes().size)
    }

    @Test fun drawerRow_showsWhileTheLabIsOn() {
        setDrawer(globalFeedEnabled = true)
        compose.onNodeWithTag("drawer_open_feed").assertIsDisplayed()
    }

    @Test fun moreMenu_hidesFeedWhileTheLabIsOff() {
        setChatList(globalFeedEnabled = false)
        compose.onNodeWithTag("chatlist_more").performClick()
        assertEquals(0, compose.onAllNodesWithTag("chatlist_open_feed").fetchSemanticsNodes().size)
        compose.onNodeWithTag("chatlist_open_settings").assertIsDisplayed()
    }

    @Test fun moreMenu_showsFeedWhileTheLabIsOn() {
        setChatList(globalFeedEnabled = true)
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_open_feed").assertIsDisplayed()
    }

    private fun setDrawer(globalFeedEnabled: Boolean) {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ServerDrawerContent(
                    drawerRows = listOf(drawerRow()),
                    selectedNetworkId = null,
                    allUnread = 0,
                    allMentions = 0,
                    scopedUnreadCount = 0,
                    allOffline = false,
                    onSelectNetwork = {},
                    onConnect = {},
                    onDisconnect = {},
                    onServerMessages = {},
                    onOpenNetworkSettings = {},
                    onAddNetwork = {},
                    onToggleOffline = {},
                    onOpenSettings = {},
                    globalFeedEnabled = globalFeedEnabled,
                    onMarkAllRead = {},
                )
            }
        }
    }

    private fun setChatList(globalFeedEnabled: Boolean) {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state =
                        ChatListState(
                            rows = listOf(row()),
                            networks = listOf(network()),
                            loading = false,
                            globalFeedEnabled = globalFeedEnabled,
                        ),
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { _, _ -> },
                    onSetMuted = { _, _ -> },
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }
    }

    private fun drawerRow() =
        DrawerRow(
            networkId = 1,
            name = "network",
            role = NetworkRole.DIRECT,
            depth = 0,
            state = IrcClientState.Ready("me", emptySet(), emptyMap()),
            nick = "me",
            unread = 0,
            mentions = 0,
        )

    private fun row() =
        ChatListRow(
            bufferId = 1,
            networkId = 1,
            networkName = "network",
            displayName = "alice",
            type = BufferType.QUERY,
            pinned = false,
            muted = false,
            lastMessageText = "hello",
            lastMessageSender = "alice",
            lastMessageTime = 1,
            unreadCount = 0,
            mentionCount = 0,
        )

    private fun network() =
        NetworkEntity(
            id = 1,
            name = "network",
            role = NetworkRole.DIRECT,
            host = "irc.example.test",
            port = 6697,
            nick = "me",
            username = "me",
            realname = "Me",
        )
}
