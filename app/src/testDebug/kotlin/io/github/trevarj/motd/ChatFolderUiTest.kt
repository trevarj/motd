package io.github.trevarj.motd

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatFolderEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.prefs.FolderDisplayMode
import io.github.trevarj.motd.ui.chatlist.ChatListContent
import io.github.trevarj.motd.ui.chatlist.ChatListInvitation
import io.github.trevarj.motd.ui.chatlist.ChatListState
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
class ChatFolderUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun folder_expands_and_long_press_opens_editor() {
        val state = mutableStateOf(ChatListState(rows = listOf(row()), folders = listOf(folder()), showFolderChatsInAll = false, loading = false))
        var edited: Long? = null
        setContent(state) {
            onSetFolderExpanded = { id, expanded ->
                state.value = state.value.copy(folders = listOf(folder().copy(id = id, expanded = expanded)))
            }
            onOpenFolderEditor = { edited = it }
        }

        compose.onAllNodesWithTag("chatlist_row_1").assertCountEquals(0)
        compose.onNodeWithTag("chatlist_folder_preview_sender", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_7").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_7").performTouchInput { longClick() }
        assertEquals(7L, edited)
    }

    @Test
    fun tabs_all_is_flat_and_folder_filters_with_icon_name_and_badge() {
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(row(1, "#dev", folderId = 7, mentions = 3), row(2, "#other", folderId = null)),
                    folders = listOf(folder()),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    loading = false,
                ),
            )
        setContent(state)

        compose.onNodeWithTag("chatlist_folder_tabs").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_tab_all").assertIsSelected()
        compose.onAllNodesWithTag("chatlist_folder_7").assertCountEquals(0)
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_row_2").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_tab_7").assert(hasText("3", substring = true)).performClick()
        compose.onNodeWithTag("chatlist_folder_tab_icon_7", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
        compose.onAllNodesWithTag("chatlist_row_2").assertCountEquals(0)
    }

    @Test
    fun tabs_all_only_shows_unassigned_chats_when_folder_chats_hidden() {
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(row(), row(2, "#other", folderId = null)),
                    folders = listOf(folder()),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    showFolderChatsInAll = false,
                    loading = false,
                ),
            )
        setContent(state)

        compose.onNodeWithTag("chatlist_folder_tab_all").assertIsSelected()
        compose.onAllNodesWithTag("chatlist_row_1").assertCountEquals(0)
        compose.onNodeWithTag("chatlist_row_2").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_tab_7").performClick()
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
        compose.onAllNodesWithTag("chatlist_row_2").assertCountEquals(0)
    }

    @Test
    fun tabs_omit_empty_all_and_select_first_folder() {
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(row()),
                    folders = listOf(folder()),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    showFolderChatsInAll = false,
                    loading = false,
                ),
            )
        setContent(state)

        compose.onAllNodesWithTag("chatlist_folder_tab_all").assertCountEquals(0)
        compose.onNodeWithTag("chatlist_folder_tab_7").assertIsSelected()
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
    }

    @Test
    fun tabs_all_routes_remain_for_archives_and_invitations() {
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(row()),
                    invitations = listOf(invitation()),
                    folders = listOf(folder()),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    showFolderChatsInAll = false,
                    loading = false,
                ),
            )
        setContent(state)

        compose.onNodeWithTag("chatlist_folder_tab_all").assertIsSelected()
        compose.onAllNodesWithTag("chatlist_row_1").assertCountEquals(0)
        compose.onNodeWithTag("chatlist_invitations_folder").assertIsDisplayed()

        compose.onNodeWithTag("chatlist_folder_tab_7").performClick()
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
        compose.onAllNodesWithTag("chatlist_invitations_folder").assertCountEquals(0)
        compose.onNodeWithTag("chatlist_folder_tab_all").performClick()

        state.value =
            state.value.copy(
                archivedRows = listOf(row(3, "#archived", folderId = 7)),
                invitations = emptyList(),
            )
        compose.onNodeWithTag("chatlist_folder_tab_all").assertIsSelected()
        compose.onNodeWithTag("chatlist_archived_folder").performClick()
        compose.onNodeWithTag("chatlist_row_3").assertIsDisplayed()
    }

    @Test
    fun selected_folder_disappearance_falls_back_to_all() {
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(row(1, "#dev", folderId = 7), row(2, "#ops", folderId = 8)),
                    folders = listOf(folder(), folder(8, "Ops")),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    loading = false,
                ),
            )
        setContent(state)
        compose.onNodeWithTag("chatlist_folder_tab_7").performClick()

        state.value = state.value.copy(rows = listOf(row(2, "#ops", folderId = 8)))

        compose.onNodeWithTag("chatlist_folder_tab_all").assertIsSelected()
        compose.onNodeWithTag("chatlist_row_2").assertIsDisplayed()
    }

    @Test
    fun selected_folder_survives_transient_loading_snapshot() {
        val loaded =
            ChatListState(
                rows = listOf(row()),
                folders = listOf(folder()),
                folderDisplayMode = FolderDisplayMode.TABS,
                loading = false,
            )
        val state = mutableStateOf(loaded)
        setContent(state)
        compose.onNodeWithTag("chatlist_folder_tab_7").performClick()

        state.value = ChatListState(folderDisplayMode = FolderDisplayMode.TABS, loading = true)
        state.value = loaded

        compose.onNodeWithTag("chatlist_folder_tab_7").assertIsSelected()
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
    }

    @Test
    fun tab_switch_clears_selection_and_starts_each_list_at_top() {
        val allRows = (1L..15L).map { row(it, "#room$it", folderId = null) } + row(100, "#dev", folderId = 7)
        val state =
            mutableStateOf(
                ChatListState(
                    rows = allRows,
                    folders = listOf(folder()),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    loading = false,
                ),
            )
        setContent(state)

        compose.onNodeWithTag("chatlist_rows").performScrollToIndex(14)
        compose.onNodeWithTag("chatlist_row_15").performTouchInput { longClick() }
        compose.onNodeWithTag("chatlist_selection_top_app_bar").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_tab_7").performClick()
        compose.onAllNodesWithTag("chatlist_selection_top_app_bar").assertCountEquals(0)
        compose.onNodeWithTag("chatlist_row_100").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_folder_tab_all").performClick()
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
    }

    @Test
    fun many_tabs_scroll_horizontally() {
        val folders = (1L..12L).map { folder(it, "Folder $it") }
        val state =
            mutableStateOf(
                ChatListState(
                    rows = folders.map { row(it.id, "#room${it.id}", folderId = it.id) },
                    folders = folders,
                    folderDisplayMode = FolderDisplayMode.TABS,
                    loading = false,
                ),
            )
        setContent(state)

        repeat(5) { compose.onNodeWithTag("chatlist_folder_tabs").performTouchInput { swipeLeft() } }
        compose.onNodeWithTag("chatlist_folder_tab_12").assertIsDisplayed()
    }

    @Test
    fun stock_row_swipe_archives_in_tabs_mode() {
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(row()),
                    folders = listOf(folder()),
                    folderDisplayMode = FolderDisplayMode.TABS,
                    loading = false,
                ),
            )
        var archived: Pair<List<Long>, Boolean>? = null
        setContent(state) {
            onSetArchived = { ids, value -> archived = ids.toList() to value }
        }
        compose.onNodeWithTag("chatlist_folder_tab_7").performClick()

        compose.onNodeWithTag("chatlist_row_surface_1").performTouchInput { swipeLeft() }

        compose.runOnIdle { assertEquals(listOf(1L) to true, archived) }
    }

    @Test
    fun assignment_sheet_clears_selection_only_after_success() {
        val succeeds = mutableStateOf(false)
        val state = mutableStateOf(ChatListState(rows = listOf(row().copy(folderId = null)), loading = false))
        setContent(state) {
            onAssignFolder = { _, _, done -> done(succeeds.value) }
        }

        compose.onNodeWithTag("chatlist_row_1").performTouchInput { longClick() }
        compose.onNodeWithTag("chatlist_selection_more").performClick()
        compose.onNodeWithTag("chatlist_selection_add_folder").performClick()
        compose.onNodeWithTag("folder_destination_none").performClick()
        compose.onNodeWithTag("chatlist_selection_top_app_bar").assertIsDisplayed()

        succeeds.value = true
        compose.onNodeWithTag("folder_destination_none").performClick()
        compose.onAllNodesWithTag("chatlist_selection_top_app_bar").assertCountEquals(0)
    }

    private fun setContent(
        state: androidx.compose.runtime.State<ChatListState>,
        callbacks: Callbacks.() -> Unit = {},
    ) {
        val configured = Callbacks().apply(callbacks)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = state.value,
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { _, _ -> },
                    onSetMuted = { _, _ -> },
                    onSetArchived = configured.onSetArchived,
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                    onAssignFolder = configured.onAssignFolder,
                    onSetFolderExpanded = configured.onSetFolderExpanded,
                    onOpenFolderEditor = configured.onOpenFolderEditor,
                )
            }
        }
    }

    private class Callbacks {
        var onSetArchived: (Collection<Long>, Boolean) -> Unit = { _, _ -> }
        var onAssignFolder: (Collection<Long>, Long?, (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) }
        var onSetFolderExpanded: (Long, Boolean) -> Unit = { _, _ -> }
        var onOpenFolderEditor: (Long) -> Unit = {}
    }

    private fun invitation() =
        ChatListInvitation(
            messageId = 11,
            bufferId = 1,
            networkId = 1,
            networkName = "net",
            inviter = "alice",
            channel = "#dev",
            text = "alice invited you to #dev",
            state = InviteState.PENDING,
            serverTime = 1,
        )

    private fun folder(
        id: Long = 7,
        name: String = "Dev",
    ) = ChatFolderEntity(id = id, displayName = name, normalizedName = name.lowercase(), ordering = id.toInt(), expanded = false)

    private fun row(
        id: Long = 1,
        name: String = "#dev",
        folderId: Long? = 7,
        mentions: Int = 0,
    ) = ChatListRow(
        bufferId = id,
        networkId = 1,
        networkName = "net",
        displayName = name,
        type = BufferType.CHANNEL,
        pinned = false,
        muted = false,
        folderId = folderId,
        lastMessageText = "hello",
        lastMessageSender = "alice",
        lastMessageTime = id,
        unreadCount = 0,
        mentionCount = mentions,
    )
}
