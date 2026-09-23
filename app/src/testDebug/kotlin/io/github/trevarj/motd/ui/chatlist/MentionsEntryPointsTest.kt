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
import io.github.trevarj.motd.data.prefs.MentionsPlacement
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MentionsEntryPointsTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun explicitDisableHidesEveryShortcut() {
        assertEquals(true, ChatListState().mentionsEnabled)
        setChatList(enabled = false, placement = MentionsPlacement.FOLDER_TAB)
        assertEquals(0, compose.onAllNodesWithTag("chatlist_folder_tab_mentions").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("chatlist_pinned_mentions").fetchSemanticsNodes().size)
    }

    @Test
    fun drawerPlacementShowsOnlyDrawerShortcut() {
        var opened = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ServerDrawerContent(
                    drawerRows = listOf(drawerRow()),
                    selectedNetworkId = null,
                    allUnread = 0,
                    allMentions = 3,
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
                    onOpenMentions = { opened++ },
                    onMarkAllRead = {},
                )
            }
        }
        compose.onNodeWithTag("drawer_open_mentions").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun pinnedPlacementOpensFeedAboveChatsWithoutAddingATab() {
        var opened = 0
        setChatList(enabled = true, placement = MentionsPlacement.CHAT_LIST) { opened++ }
        compose.onNodeWithTag("chatlist_pinned_mentions").assertIsDisplayed().performClick()
        assertEquals(0, compose.onAllNodesWithTag("chatlist_folder_tab_mentions").fetchSemanticsNodes().size)
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun folderPlacementAddsAShortcutTabEvenWithoutUserFolders() {
        var opened = 0
        setChatList(enabled = true, placement = MentionsPlacement.FOLDER_TAB) { opened++ }
        compose.onNodeWithTag("chatlist_folder_tab_all").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_tab_mentions").assertIsDisplayed().performClick()
        assertEquals(0, compose.onAllNodesWithTag("chatlist_pinned_mentions").fetchSemanticsNodes().size)
        compose.runOnIdle { assertEquals(1, opened) }
    }

    private fun setChatList(
        enabled: Boolean,
        placement: MentionsPlacement,
        onOpen: () -> Unit = {},
    ) {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state =
                        ChatListState(
                            rows = listOf(row()),
                            networks = listOf(network()),
                            loading = false,
                            mentionsEnabled = enabled,
                            mentionsPlacement = placement,
                            allMentions = 2,
                        ),
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onOpenMentions = onOpen,
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
            mentionCount = 2,
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
