package io.github.trevarj.motd.ui.chatlist

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.prefs.GlobalFeedPrefs
import io.github.trevarj.motd.data.prefs.OnboardingPrefs
import io.github.trevarj.motd.data.sync.NoopHistoryGapFiller
import io.github.trevarj.motd.gesture.FakeBuffers
import io.github.trevarj.motd.gesture.FakeNetworks
import io.github.trevarj.motd.gesture.FakeReadMarkers
import io.github.trevarj.motd.gesture.FakeSettings
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.service.AlwaysOnScreen
import io.github.trevarj.motd.service.ChannelCloseCoordinator
import io.github.trevarj.motd.service.HistoryResyncController
import io.github.trevarj.motd.service.HistoryResyncState
import io.github.trevarj.motd.testing.NoopConnectionManager
import io.github.trevarj.motd.testing.ReadAloudHarness
import io.github.trevarj.motd.ui.components.AudioPlaybackViewModel
import io.github.trevarj.motd.ui.nav.CHAT_LIST_INITIAL_DP
import io.github.trevarj.motd.ui.nav.ChatListRoute
import io.github.trevarj.motd.ui.nav.ChatRoute
import io.github.trevarj.motd.ui.nav.ChatWorkspace
import io.github.trevarj.motd.ui.nav.SettingsRoute
import io.github.trevarj.motd.ui.nav.openChat
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatListUnreadNavigationUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    private lateinit var nav: NavHostController
    private val buffers = FakeBuffers().apply { chats.value = listOf(row(1, 1), row(2, 1), row(3, 0)) }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun unread_survives_replaced_split_details_and_settings() {
        launchList()
        enableUnread()
        assertUnread()

        compose.onNodeWithTag("chatlist_row_1").performClick()
        compose.onNodeWithText("Detail 1").assertIsDisplayed()
        assertUnread()

        compose.onNodeWithTag("chatlist_row_2").performClick()
        compose.onNodeWithText("Detail 2").assertIsDisplayed()
        compose.runOnIdle { assertEquals(ChatListRoute, nav.previousBackStackEntry?.toRoute<ChatListRoute>()) }
        assertUnread()

        compose.runOnIdle { nav.navigate(SettingsRoute()) }
        compose.onNodeWithText("Settings overlay").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_unread_chip").assertDoesNotExist()
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("Detail 2").assertIsDisplayed()
        assertUnread()

        compose.runOnIdle { buffers.chats.value = buffers.chats.value.map { it.copy(unreadCount = 0) } }
        compose.onNodeWithTag("chatlist_unread_empty").assertIsDisplayed()
        assertUnread()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun unread_is_off_on_fresh_list_and_survives_phone_chat_back() {
        launchList()
        compose.onNodeWithTag("chatlist_row_3").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_unread_chip").assertDoesNotExist()
        enableUnread()

        compose.onNodeWithTag("chatlist_row_1").performClick()
        compose.onNodeWithText("Detail 1").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_unread_chip").assertDoesNotExist()
        compose.runOnIdle { nav.popBackStack() }
        assertUnread()
    }

    private fun enableUnread() {
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_show_unread").performClick()
    }

    private fun assertUnread() {
        compose.onNodeWithTag("chatlist_unread_chip").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_row_3").assertDoesNotExist()
    }

    private fun launchList() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                val controller = rememberNavController()
                nav = controller
                val listWidth = remember { mutableFloatStateOf(CHAT_LIST_INITIAL_DP) }
                val audio = remember { AudioPlaybackViewModel(ReadAloudHarness.AttachmentPlayback()) }
                NavHost(controller, startDestination = ChatListRoute) {
                    composable<ChatListRoute> { entry ->
                        val model = viewModel<ChatListViewModel>(viewModelStoreOwner = entry) { createModel(entry.savedStateHandle) }
                        ChatWorkspace(
                            listWidth,
                            listPane = {
                                ChatListScreen(
                                    viewModel = model,
                                    audioViewModel = audio,
                                    onOpenBuffer = { controller.openChat(ChatRoute(it), replaceCurrentChat = false) },
                                )
                            },
                        )
                    }
                    composable<ChatRoute> { entry ->
                        val route = entry.toRoute<ChatRoute>()
                        val listEntry = remember(entry) { controller.getBackStackEntry<ChatListRoute>() }
                        val model = viewModel<ChatListViewModel>(viewModelStoreOwner = listEntry) { createModel(listEntry.savedStateHandle) }
                        ChatWorkspace(
                            listWidth,
                            listPane = {
                                ChatListScreen(
                                    viewModel = model,
                                    audioViewModel = audio,
                                    selectedBufferId = route.bufferId,
                                    onOpenBuffer = { controller.openChat(ChatRoute(it), replaceCurrentChat = true) },
                                )
                            },
                            detailPane = { Text("Detail ${route.bufferId}") },
                        )
                    }
                    composable<SettingsRoute> { Text("Settings overlay") }
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("chatlist_row_1").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun createModel(savedStateHandle: SavedStateHandle) =
        ChatListViewModel(
            bufferRepository = buffers,
            networkRepository = FakeNetworks(),
            connectionManager = NoopConnectionManager(),
            historyResync =
                object : HistoryResyncController {
                    override suspend fun reconcileBuffer(
                        buffer: BufferEntity,
                        client: IrcClient,
                        statusOwnerId: Long,
                        preserveUnread: Boolean,
                        isCurrent: () -> Boolean,
                    ) = HistoryResyncState.Idle

                    override suspend fun reconcilePendingMessage(
                        buffer: BufferEntity,
                        client: IrcClient,
                        isCurrent: () -> Boolean,
                    ) = HistoryResyncState.Idle
                },
            gapFiller = NoopHistoryGapFiller,
            channelCloseCoordinator =
                object : ChannelCloseCoordinator {
                    override fun start() = Unit

                    override suspend fun requestClose(bufferId: Long) = Unit
                },
            readMarkerRepository = FakeReadMarkers(),
            settingsRepository = FakeSettings(),
            onboardingPrefs =
                object : OnboardingPrefs {
                    override val completed = flowOf(true)
                    override val communityInvitationHandled = flowOf(true)

                    override suspend fun markCompleted() = Unit

                    override suspend fun markCommunityInvitationHandled() = Unit
                },
            globalFeedPrefs =
                object : GlobalFeedPrefs {
                    override val enabled = flowOf(false)

                    override suspend fun setEnabled(enabled: Boolean) = Unit
                },
            dickordLabsPrefs = fakeDickordLabsPrefs(),
            savedStateHandle = savedStateHandle,
            appVisibility = AlwaysOnScreen,
            context = ApplicationProvider.getApplicationContext(),
        )

    private fun row(
        id: Long,
        unread: Int,
    ) = ChatListRow(
        bufferId = id,
        networkId = 1,
        networkName = "net",
        displayName = "#chat$id",
        type = BufferType.CHANNEL,
        pinned = false,
        muted = false,
        lastMessageText = "Hello",
        lastMessageSender = "alice",
        lastMessageTime = id,
        unreadCount = unread,
        mentionCount = 0,
    )
}
