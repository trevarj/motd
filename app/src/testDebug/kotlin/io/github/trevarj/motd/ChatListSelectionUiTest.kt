package io.github.trevarj.motd

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.ui.chatlist.ArchiveAccessibilityAnnouncement
import io.github.trevarj.motd.ui.chatlist.ChatListContent
import io.github.trevarj.motd.ui.chatlist.ChatListDefaultTitle
import io.github.trevarj.motd.ui.chatlist.ChatListInvitation
import io.github.trevarj.motd.ui.chatlist.ChatListRowItem
import io.github.trevarj.motd.ui.chatlist.ChatListState
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
class ChatListSelectionUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test fun selected_row_exposes_selected_semantics_on_the_full_row() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListRowItem(row(), false, {}, {}, selected = true)
            }
        }

        compose
            .onNodeWithTag("chatlist_row_1")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    @Test fun invitation_folder_hides_after_all_are_handled_until_another_arrives() {
        val first = invitation(11, "#one")
        val second = invitation(12, "#two")
        val state =
            mutableStateOf(
                ChatListState(
                    rows = listOf(row()),
                    invitations = listOf(first, second),
                    networks = listOf(network()),
                    loading = false,
                ),
            )
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = state.value,
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { _, _ -> },
                    onSetMuted = { _, _ -> },
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                    onAcceptInvitation = { id ->
                        state.value =
                            state.value.copy(
                                invitations =
                                    state.value.invitations.map {
                                        if (it.messageId == id) it.copy(state = InviteState.JOINED) else it
                                    },
                            )
                    },
                    onIgnoreInvitation = { id ->
                        state.value =
                            state.value.copy(
                                invitations =
                                    state.value.invitations.map {
                                        if (it.messageId == id) it.copy(state = InviteState.DISMISSED) else it
                                    },
                            )
                    },
                )
            }
        }

        compose.onNodeWithTag("chatlist_invitations_folder").assertIsDisplayed().performClick()
        compose.onNodeWithText("Invitations").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_invitation_join_11").performClick()
        compose.onNodeWithTag("chatlist_invitation_ignore_12").performClick()

        compose
            .onNodeWithTag("chatlist_invitation_11")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Joined"))
        compose
            .onNodeWithTag("chatlist_invitation_12")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Ignored"))
        assertEquals(0, compose.onAllNodesWithTag("chatlist_invitation_join_11").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("chatlist_invitation_ignore_12").fetchSemanticsNodes().size)

        compose.onNodeWithTag("chatlist_selection_close").performClick()
        assertEquals(0, compose.onAllNodesWithTag("chatlist_invitations_folder").fetchSemanticsNodes().size)

        compose.runOnIdle {
            state.value =
                state.value.copy(
                    invitations = state.value.invitations + invitation(13, "#three"),
                )
        }
        compose.onNodeWithTag("chatlist_invitations_folder").assertIsDisplayed()
    }

    @Test fun collapsing_fools_clears_their_selection_and_contextual_actions() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = ChatListState(rows = listOf(row().copy(displayName = "fool")), fools = setOf("fool"), loading = false),
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

        compose.onNodeWithText("FOOLS (1)").performClick()
        assertEquals(1, compose.onAllNodesWithTag("chatlist_row_surface_1").fetchSemanticsNodes().size)
        compose.onNodeWithTag("chatlist_row_1").performTouchInput { longClick() }
        assertEquals(1, compose.onAllNodesWithTag("chatlist_selection_top_app_bar").fetchSemanticsNodes().size)

        compose.onNodeWithText("FOOLS (1)").performClick()
        assertEquals(0, compose.onAllNodesWithTag("chatlist_row_1").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("chatlist_selection_top_app_bar").fetchSemanticsNodes().size)
    }

    @Test fun unscoped_title_uses_lowercase_text() {
        compose.setContent {
            MotdTheme(dynamicColor = false) { ChatListDefaultTitle(titleConnecting = false) }
        }

        val title =
            compose
                .onNodeWithTag("chatlist_title", useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .single()
                .text
        assertTrue(title.startsWith("motd"))
        assertEquals(0, compose.onAllNodesWithText("/motd").fetchSemanticsNodes().size)
    }

    @Test fun empty_archive_uses_archive_specific_copy_without_connection_prompt() {
        val state =
            mutableStateOf(
                ChatListState(
                    archivedRows = listOf(row().copy(archived = true)),
                    networks = listOf(network()),
                    loading = false,
                ),
            )
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = state.value,
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

        compose.onNodeWithText("Archived Chats (1)").performClick()
        compose.runOnIdle { state.value = state.value.copy(archivedRows = emptyList()) }

        compose.onNodeWithText("No archived chats yet").assertIsDisplayed()
        assertEquals(
            0,
            compose
                .onAllNodesWithText("Connect to a network to start chatting.")
                .fetchSemanticsNodes()
                .size,
        )
    }

    @Test fun end_to_start_swipe_archives_once_and_undo_reverses_once() {
        val archiveCalls = mutableListOf<Pair<List<Long>, Boolean>>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = ChatListState(rows = listOf(row()), loading = false),
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { _, _ -> },
                    onSetMuted = { _, _ -> },
                    onSetArchived = { ids, archived -> archiveCalls += ids.toList() to archived },
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("chatlist_row_surface_1").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Chat archived").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(listOf(1L) to true), archiveCalls) }

        compose.onNodeWithText("Undo").performClick()

        compose.runOnIdle {
            assertEquals(listOf(listOf(1L) to true, listOf(1L) to false), archiveCalls)
        }
    }

    @Test fun dismissing_swipe_undo_leaves_archive_unchanged() {
        val archiveCalls = mutableListOf<Pair<List<Long>, Boolean>>()
        val snackbarHostState = SnackbarHostState()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = ChatListState(rows = listOf(row()), loading = false),
                    snackbarHostState = snackbarHostState,
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { _, _ -> },
                    onSetMuted = { _, _ -> },
                    onSetArchived = { ids, archived -> archiveCalls += ids.toList() to archived },
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("chatlist_row_surface_1").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Chat archived").assertIsDisplayed()
        compose.runOnIdle { snackbarHostState.currentSnackbarData?.dismiss() }

        compose.runOnIdle { assertEquals(listOf(listOf(1L) to true), archiveCalls) }
    }

    @Test fun archiving_query_round_trip_moves_the_same_row_once_each_way() {
        val active = row()
        val remaining = row().copy(bufferId = 2, displayName = "bob")
        val archived = active.copy(archived = true)
        val state = mutableStateOf(ChatListState(rows = listOf(active, remaining), networks = listOf(network()), loading = false))
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = state.value,
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { _, _ -> },
                    onSetMuted = { _, _ -> },
                    onSetArchived = { ids, archivedFlag ->
                        if (ids == listOf(active.bufferId)) {
                            state.value =
                                if (archivedFlag) {
                                    state.value.copy(rows = listOf(remaining), archivedRows = listOf(archived))
                                } else {
                                    state.value.copy(rows = listOf(active, remaining), archivedRows = emptyList())
                                }
                        }
                    },
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("chatlist_row_surface_1").performTouchInput { swipeLeft() }

        compose.onNodeWithTag("chatlist_archived_folder").assertIsDisplayed()
        compose.onNodeWithText("Archived Chats (1)").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_archived_folder").performTouchInput { click() }
        compose.onNodeWithText("Archived Chats").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()

        compose.onNodeWithTag("chatlist_row_surface_1").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Archived Chats").assertIsDisplayed()
        compose.onNodeWithText("No archived chats yet").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_selection_close").performClick()

        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
    }

    @Test fun unarchiving_query_stays_in_archive_until_back_then_shows_restored_row() {
        val active = row().copy(bufferId = 2, displayName = "bob")
        val archived = row().copy(archived = true)
        val restored = archived.copy(archived = false)
        val state = mutableStateOf(ChatListState(rows = listOf(active), archivedRows = listOf(archived), networks = listOf(network()), loading = false))
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = state.value,
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { _, _ -> },
                    onSetMuted = { _, _ -> },
                    onSetArchived = { ids, archivedFlag ->
                        if (ids == listOf(archived.bufferId)) {
                            state.value =
                                if (archivedFlag) {
                                    state.value.copy(rows = listOf(active), archivedRows = listOf(archived))
                                } else {
                                    state.value.copy(rows = listOf(restored, active), archivedRows = emptyList())
                                }
                        }
                    },
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        val revealAction =
            compose
                .onNodeWithTag("chatlist_archive_pull_target")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == "Reveal archived chats" }
        compose.runOnIdle { assertEquals(true, revealAction.action()) }
        compose.onNodeWithText("Archived Chats (1)").performTouchInput { click() }
        compose.onNodeWithTag("chatlist_row_surface_1").performTouchInput { swipeLeft() }

        compose.onNodeWithText("Archived Chats").assertIsDisplayed()
        compose.onNodeWithText("No archived chats yet").assertIsDisplayed()
        compose.onNodeWithText("Chat unarchived").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithTag("chatlist_row_1").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_selection_close").performClick()

        compose.onNodeWithTag("chatlist_row_2").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("chatlist_row_1").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("No archived chats yet").fetchSemanticsNodes().size)
    }

    @Test fun disabled_swipe_directions_do_not_archive() {
        val archiveCalls = mutableListOf<Pair<List<Long>, Boolean>>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = ChatListState(rows = listOf(row()), loading = false),
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { _, _ -> },
                    onSetMuted = { _, _ -> },
                    onSetArchived = { ids, archived -> archiveCalls += ids.toList() to archived },
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("chatlist_row_surface_1").performTouchInput { swipeRight() }
        compose.onNodeWithTag("chatlist_row_1").performTouchInput { longClick() }
        compose.onNodeWithTag("chatlist_row_surface_1").performTouchInput { swipeLeft() }

        compose.runOnIdle { assertEquals(emptyList<Pair<List<Long>, Boolean>>(), archiveCalls) }
        assertEquals(0, compose.onAllNodesWithText("Chat archived").fetchSemanticsNodes().size)
    }

    @Test fun selection_menu_archive_does_not_offer_swipe_undo() {
        val archiveCalls = mutableListOf<Pair<List<Long>, Boolean>>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = ChatListState(rows = listOf(row()), loading = false),
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { _, _ -> },
                    onSetMuted = { _, _ -> },
                    onSetArchived = { ids, archived -> archiveCalls += ids.toList() to archived },
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("chatlist_row_1").performTouchInput { longClick() }
        compose.onNodeWithTag("chatlist_selection_more").performClick()
        compose.onNodeWithTag("chatlist_selection_archive").performClick()

        compose.runOnIdle { assertEquals(listOf(listOf(1L) to true), archiveCalls) }
        assertEquals(0, compose.onAllNodesWithText("Chat archived").fetchSemanticsNodes().size)
    }

    @Test fun archive_announcement_uses_a_polite_live_region() {
        val announcement = mutableStateOf<String?>(null)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ArchiveAccessibilityAnnouncement(announcement.value)
            }
        }

        compose.runOnIdle { announcement.value = "Archived chats revealed" }
        compose
            .onNodeWithTag("chatlist_archive_announcement")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assertTextEquals("Archived chats revealed")

        compose.runOnIdle { announcement.value = "Archived chats hidden" }
        compose
            .onNodeWithTag("chatlist_archive_announcement")
            .assertTextEquals("Archived chats hidden")
    }

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

    private fun invitation(
        id: Long,
        channel: String,
    ) = ChatListInvitation(
        messageId = id,
        bufferId = id + 100,
        networkId = 1,
        networkName = "network",
        inviter = "alice",
        channel = channel,
        text = "alice invited you to $channel",
        state = InviteState.PENDING,
        serverTime = id,
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
