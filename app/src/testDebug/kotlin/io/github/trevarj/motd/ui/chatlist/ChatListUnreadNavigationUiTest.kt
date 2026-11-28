package io.github.trevarj.motd.ui.chatlist

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.compose.foundation.focusable
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
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
import io.github.trevarj.motd.data.db.ChatFolderEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.prefs.FolderDisplayMode
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
import io.github.trevarj.motd.ui.nav.ChatKeyboardAction
import io.github.trevarj.motd.ui.nav.ChatKeyboardNavigate
import io.github.trevarj.motd.ui.nav.ChatListRoute
import io.github.trevarj.motd.ui.nav.ChatRoute
import io.github.trevarj.motd.ui.nav.ChatWorkspace
import io.github.trevarj.motd.ui.nav.SettingsRoute
import io.github.trevarj.motd.ui.nav.chatKeyboardAction
import io.github.trevarj.motd.ui.nav.openChat
import io.github.trevarj.motd.ui.nav.openKeyboardChat
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.InputDeviceBuilder
import android.view.KeyEvent as AndroidKeyEvent

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatListUnreadNavigationUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    private lateinit var nav: NavHostController
    private lateinit var listModel: ChatListViewModel
    private lateinit var inputMode: InputModeManager
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

    @Test
    @Config(qualifiers = "w411dp-h891dp-keysexposed-qwerty")
    fun keyboardSwitchesPhoneDetailsWithoutStacking() = exerciseKeyboardSwitching()

    @Test
    @Config(qualifiers = "w840dp-h900dp-keysexposed-qwerty")
    fun keyboardSwitchesSplitDetailsWithoutStacking() = exerciseKeyboardSwitching()

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-nokeys")
    fun overflowHidesShortcutsForNumpadAndOnlyDividesVisibleViews() {
        attachKeypad(AndroidKeyEvent.KEYCODE_NUMPAD_1)
        launchList()
        val more = compose.onNodeWithTag("chatlist_more")
        val menu = compose.onNodeWithTag("chatlist_more_menu")
        val shortcuts = compose.onNodeWithTag("chatlist_keyboard_shortcuts")
        val unread = compose.onNodeWithTag("chatlist_show_unread")
        val divider = compose.onNodeWithTag("chatlist_more_views_divider")
        val activity = compose.onNodeWithTag("chatlist_network_activity")
        more.performClick()
        menu.assertIsDisplayed()
        shortcuts.assertDoesNotExist()
        val unreadTop = unread.assertIsDisplayed().getUnclippedBoundsInRoot().top
        val dividerTop = divider.assertIsDisplayed().getUnclippedBoundsInRoot().top
        val activityTop = activity.assertIsDisplayed().getUnclippedBoundsInRoot().top
        assertTrue(unreadTop < dividerTop && dividerTop < activityTop)

        unread.performClick()
        assertUnread()
        more.performClick()
        activity.assertIsDisplayed()
        unread.assertDoesNotExist()
        divider.assertDoesNotExist()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-nokeys")
    fun overflowShowsShortcutsForNonAlphabeticShortcutPad() {
        attachKeypad(AndroidKeyEvent.KEYCODE_CTRL_RIGHT, AndroidKeyEvent.KEYCODE_F8)
        launchList()

        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_keyboard_shortcuts").assertIsDisplayed()
    }

    private fun exerciseKeyboardSwitching() {
        buffers.chats.value =
            listOf(row(4, 1).copy(muted = true), row(2, 1), row(5, 1).copy(type = BufferType.SERVER), row(3, 1), row(1, 1).copy(pinned = true))
        launchList()
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_keyboard_shortcuts").performClick()
        shortcut("chat_keyboard_next", Key.F8)
        compose.onNodeWithTag("chat_keyboard_dialog").assertIsDisplayed()
        compose.onNodeWithTag("chat_keyboard_next").performClick()
        compose.onNodeWithText("Detail 1").assertIsDisplayed()

        shortcut("keyboard_detail", Key.F8, unread = true)
        compose.onNodeWithText("Detail 2").assertIsDisplayed()
        compose.runOnIdle { buffers.chats.value = buffers.chats.value.map { if (it.bufferId == 2L) it.copy(unreadCount = 0) else it } }
        compose.waitUntil(5_000) {
            val rows = listModel.state.value.rows
            rows.any { it.bufferId == 2L && it.unreadCount == 0 }
        }
        shortcut("keyboard_detail", Key.F8, unread = true, requestFocus = false)
        compose.onNodeWithText("Detail 3").assertIsDisplayed()
        shortcut("keyboard_detail", Key.F8, unread = true, requestFocus = false)
        compose.onNodeWithText("Detail 1").assertIsDisplayed()
        shortcut("keyboard_detail", Key.F7, unread = true, requestFocus = false)
        compose.onNodeWithText("Detail 3").assertIsDisplayed()
        compose.runOnIdle { buffers.chats.value = buffers.chats.value.map { it.copy(unreadCount = 0) } }
        compose.waitUntil(5_000) {
            val rows = listModel.state.value.rows
            rows.none { it.unreadCount > 0 }
        }
        shortcut("keyboard_detail", Key.F7, unread = true, requestFocus = false)
        compose.onNodeWithText("Detail 3").assertIsDisplayed()
        compose.runOnIdle {
            val old = requireNotNull(nav.currentBackStackEntry)
            nav.popBackStack()
            assertEquals(false, nav.openKeyboardChat(old, true, true) { error("stale detail resolved a target") })
        }
        compose.onNodeWithTag("chatlist_more").assertIsDisplayed()
        shortcut("chatlist_more", Key.F7)
        compose.onNodeWithText("Detail 3").assertIsDisplayed()
    }

    private fun shortcut(
        tag: String,
        key: Key,
        unread: Boolean = false,
        requestFocus: Boolean = true,
    ) {
        val node = compose.onNodeWithTag(tag)
        if (requestFocus) {
            compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
            node.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        }
        node.performKeyInput {
            keyDown(Key.CtrlLeft)
            if (unread) keyDown(Key.ShiftLeft)
            keyDown(key)
            keyUp(key)
            if (unread) keyUp(Key.ShiftLeft)
            keyUp(Key.CtrlLeft)
        }
    }

    @Test
    fun keyboardOrderRespectsVisibleFolderAndGroupRows() {
        val folder = ChatFolderEntity(9, "Work", "work", expanded = false)
        val state =
            ChatListState(
                loading = false,
                selectedNetworkId = 7L,
                folders = listOf(folder),
                fools = setOf("hidden"),
                rows =
                    listOf(
                        row(1, 0),
                        row(2, 1).copy(folderId = 9),
                        row(3, 1).copy(type = BufferType.QUERY, displayName = "hidden"),
                        row(4, 1).copy(pinned = true),
                        row(5, 1).copy(anchorOrder = 0),
                    ),
            )

        fun next(
            list: ChatListState,
            current: Long,
            expanded: Boolean = false,
            unread: ChatListUnreadScope? = null,
        ) = list.keyboardTarget(ChatKeyboardAction.NEXT, current, folder.id, unread, expanded)
        assertEquals(4L, next(state, 5))
        assertEquals(1L, next(state, 4))
        assertEquals(5L, next(state, 1))
        assertEquals(3L, next(state, 1, expanded = true))
        assertEquals(5L, next(state, 4, unread = ChatListUnreadScope(7, null)))
        assertEquals(1L, next(state, 4, unread = ChatListUnreadScope(8, null)))
        assertEquals(2L, next(state.copy(folders = listOf(folder.copy(expanded = true))), 4))
        val tabs = state.copy(folderDisplayMode = FolderDisplayMode.TABS)
        assertEquals(2L, next(tabs, 1))
        assertEquals(null, next(tabs, 2))
        val readInCollapsedFolder =
            state.copy(rows = state.rows + listOf(row(6, 0).copy(folderId = 9), row(7, 1).copy(folderId = 9), row(8, 1)))
        assertEquals(8L, next(readInCollapsedFolder, 6, unread = ChatListUnreadScope(7, null)))
    }

    @Test
    fun onlyExactInitialControlFunctionKeysAreCommands() {
        fun classify(
            code: Int,
            modifiers: Int,
            action: Int = AndroidKeyEvent.ACTION_DOWN,
            repeat: Int = 0,
        ) = chatKeyboardAction(KeyEvent(AndroidKeyEvent(0, 0, action, code, repeat, modifiers)))
        val ctrl = AndroidKeyEvent.META_CTRL_ON
        val shift = AndroidKeyEvent.META_SHIFT_ON
        val previous = AndroidKeyEvent.KEYCODE_F7
        val next = AndroidKeyEvent.KEYCODE_F8
        assertEquals(ChatKeyboardAction.PREVIOUS, classify(previous, ctrl))
        assertEquals(ChatKeyboardAction.NEXT, classify(next, ctrl))
        assertEquals(ChatKeyboardAction.PREVIOUS_UNREAD, classify(previous, ctrl or shift))
        assertEquals(ChatKeyboardAction.NEXT_UNREAD, classify(next, ctrl or shift))
        assertEquals(null, classify(next, 0))
        assertEquals(null, classify(previous, ctrl or AndroidKeyEvent.META_ALT_ON))
        assertEquals(null, classify(next, ctrl or AndroidKeyEvent.META_META_ON))
        assertEquals(null, classify(next, ctrl, AndroidKeyEvent.ACTION_UP))
        assertEquals(null, classify(previous, ctrl, repeat = 1))
        assertEquals(null, classify(AndroidKeyEvent.KEYCODE_TAB, ctrl))
    }

    private fun enableUnread() {
        compose.onNodeWithTag("chatlist_more").performClick()
        compose.onNodeWithTag("chatlist_show_unread").performClick()
    }

    private fun assertUnread() {
        compose.onNodeWithTag("chatlist_unread_chip").assertIsDisplayed()
        compose.onNodeWithTag("chatlist_row_3").assertDoesNotExist()
    }

    private fun attachKeypad(vararg keys: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val input = shadowOf(context.getSystemService(InputManager::class.java))
        val keypad =
            InputDeviceBuilder
                .newBuilder()
                .setId(1)
                .setName("Test keypad")
                .setSources(InputDevice.SOURCE_KEYBOARD)
                .setKeyboardType(InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC)
                .build()
        input.addInputDevice(keypad)
        input.addInputDeviceKeys(keypad.id, keys)
    }

    private fun launchList() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                inputMode = LocalInputModeManager.current
                val controller = rememberNavController()
                nav = controller
                val listWidth = remember { mutableFloatStateOf(CHAT_LIST_INITIAL_DP) }
                val audio = remember { AudioPlaybackViewModel(ReadAloudHarness.AttachmentPlayback()) }
                NavHost(controller, startDestination = ChatListRoute) {
                    composable<ChatListRoute> { entry ->
                        val model = viewModel<ChatListViewModel>(viewModelStoreOwner = entry) { createModel(entry.savedStateHandle) }
                        listModel = model
                        ChatWorkspace(
                            listWidth,
                            listPane = {
                                ChatListScreen(
                                    viewModel = model,
                                    audioViewModel = audio,
                                    onOpenBuffer = { controller.openChat(ChatRoute(it), replaceCurrentChat = false) },
                                    onKeyboardNavigate = { action, hardware ->
                                        controller.openKeyboardChat(entry, false, hardware) { model.keyboardTarget(action, null) }
                                    },
                                )
                            },
                        )
                    }
                    composable<ChatRoute> { entry ->
                        val route = entry.toRoute<ChatRoute>()
                        val listEntry = remember(entry) { controller.getBackStackEntry<ChatListRoute>() }
                        val model = viewModel<ChatListViewModel>(viewModelStoreOwner = listEntry) { createModel(listEntry.savedStateHandle) }
                        val navigate: ChatKeyboardNavigate = { action, hardware ->
                            controller.openKeyboardChat(entry, true, hardware) { model.keyboardTarget(action, route.bufferId) }
                        }
                        ChatWorkspace(
                            listWidth,
                            onKeyboardNavigate = navigate,
                            listPane = {
                                ChatListScreen(
                                    viewModel = model,
                                    audioViewModel = audio,
                                    selectedBufferId = route.bufferId,
                                    onOpenBuffer = { controller.openChat(ChatRoute(it), replaceCurrentChat = true) },
                                    onKeyboardNavigate = navigate,
                                )
                            },
                            detailPane = {
                                Text(
                                    "Detail ${route.bufferId}",
                                    Modifier
                                        .testTag("keyboard_detail")
                                        .onKeyEvent { event -> chatKeyboardAction(event)?.let { navigate(it, true) } == true }
                                        .focusable(),
                                )
                            },
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
