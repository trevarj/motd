package io.github.trevarj.motd.dickord

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.ui.components.LocalRemoteAvatars
import io.github.trevarj.motd.ui.components.RemoteAvatarState
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
class DickordPortalUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun serverAndDmNavigationShowsExactLabelsAndReturnsRawBufferIds() {
        val opened = mutableListOf<Long>()
        val state = render(portalState(), onOpenConversation = opened::add)

        compose.onNodeWithTag("screen_dickord_portal").assertIsDisplayed()
        compose.onNodeWithTag("dickord_server_rail").assertWidthIsEqualTo(72.dp)
        compose.onNodeWithTag("dickord_group_dms").assertIsSelected()
        compose.onNodeWithTag("dickord_server_7_100").performClick().assertIsSelected()
        compose.onNodeWithText("general").assertIsDisplayed()
        compose.onNodeWithText("release.notes_20").assertIsDisplayed()
        compose.onNodeWithText("#discord.example.general_encoded").assertDoesNotExist()
        compose.onNodeWithTag("dickord_conversation_11").performClick()

        compose.onNodeWithTag("dickord_group_dms").performClick().assertIsSelected()
        compose.onNodeWithText("Alice Smith").assertIsDisplayed()
        compose.onNodeWithText("#discord.dm.alice-smith_encoded").assertDoesNotExist()
        compose.onNodeWithText("AS", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("dickord_conversation_21").performClick()

        compose.runOnIdle {
            assertEquals(listOf(11L, 21L), opened)
            assertEquals(DICKORD_PORTAL_DMS_KEY, state.value.selectedGroupKey)
        }
    }

    @Test
    fun portalChromeSpansBothPanesAndKeepsActionsInsideSafeBounds() {
        var safeTop = 0.dp
        var safeBottom = 0.dp
        compose.setContent {
            val density = LocalDensity.current
            val safeDrawing = WindowInsets.safeDrawing
            safeTop = with(density) { safeDrawing.getTop(this).toDp() }
            safeBottom = with(density) { safeDrawing.getBottom(this).toDp() }
            MotdTheme(dynamicColor = false) {
                DickordPortalContent(state = portalState())
            }
        }

        val screen = compose.onNodeWithTag("screen_dickord_portal").getUnclippedBoundsInRoot()
        val topBar = compose.onNodeWithTag("dickord_portal_top_app_bar").getUnclippedBoundsInRoot()
        val exit = compose.onNodeWithTag("dickord_portal_exit").getUnclippedBoundsInRoot()
        val refresh = compose.onNodeWithTag("dickord_portal_refresh").getUnclippedBoundsInRoot()
        val more = compose.onNodeWithTag("dickord_portal_more").getUnclippedBoundsInRoot()
        val rail = compose.onNodeWithTag("dickord_server_rail").getUnclippedBoundsInRoot()
        val channels = compose.onNodeWithTag("dickord_channels").getUnclippedBoundsInRoot()

        assertEquals(screen.left, topBar.left)
        assertEquals(screen.right, topBar.right)
        assertTrue(exit.top >= screen.top + safeTop)
        assertTrue(exit.bottom <= topBar.bottom)
        assertTrue(refresh.top >= screen.top + safeTop && refresh.bottom <= topBar.bottom)
        assertTrue(more.top >= screen.top + safeTop && more.bottom <= topBar.bottom)
        assertTrue(rail.top >= topBar.bottom)
        assertTrue(channels.top >= topBar.bottom)
        assertTrue(rail.bottom <= screen.bottom - safeBottom)
        assertTrue(channels.bottom <= screen.bottom - safeBottom)
        compose.onAllNodesWithTag("dickord_portal_exit").assertCountEquals(1)
        compose.onAllNodesWithText("DMs").assertCountEquals(1)
    }

    @Test
    fun railControlsStayUsableWithNoAvatarsAndDisambiguateDuplicateServers() {
        val groups =
            listOf(
                dmsGroup(),
                serverGroup(networkId = 7, networkName = "Bridge A", guildId = "100", name = "Example Server", rows = listOf(channel(11, 7, "Bridge A", "general", "101"))),
                serverGroup(networkId = 8, networkName = "Bridge B", guildId = "200", name = "Example Server", rows = listOf(channel(31, 8, "Bridge B", "ops", "201"))),
            )
        val state =
            render(
                DickordPortalState(loading = false, enabled = true, groups = groups, offline = false),
                avatarStyle = AvatarStyle.NONE,
                remoteAvatars = RemoteAvatarState(enabled = true),
            )

        compose.onNodeWithTag("dickord_portal_exit").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("dickord_group_dms").assertHeightIsAtLeast(48.dp)
        compose
            .onNodeWithTag("dickord_server_7_100")
            .assertHeightIsAtLeast(48.dp)
            .assertContentDescriptionEquals("Example Server — Bridge A")
            .performClick()
            .assertIsSelected()
        compose
            .onNodeWithTag("dickord_server_8_200")
            .assertContentDescriptionEquals("Example Server — Bridge B")
            .performClick()
            .assertIsSelected()
        compose.onAllNodesWithText("E", useUnmergedTree = true).assertCountEquals(2)
        compose.runOnIdle { assertEquals("guild:8:200", state.value.selectedGroupKey) }
    }

    @Test
    fun pendingAndCachedOfflineStatesStayReachable() {
        val opened = mutableListOf<Long>()
        var refreshes = 0
        val pending = pendingGroup(row(91, 9, "Bridge C", "#discord.pending.encoded"))
        val state =
            render(
                DickordPortalState(
                    loading = false,
                    enabled = true,
                    groups = listOf(dmsGroup(), pending),
                    selectedGroupKey = DICKORD_PORTAL_PENDING_KEY,
                    offline = true,
                ),
                onOpenConversation = opened::add,
                onRefresh = { refreshes++ },
            )

        compose.onNodeWithTag("dickord_group_pending").assertIsSelected()
        compose.onNodeWithText("Waiting for bridge details").assertIsDisplayed()
        compose.onNodeWithText("Conversation 91").assertIsDisplayed()
        compose.onNodeWithTag("dickord_conversation_91").performClick()
        compose.onNodeWithText("Refresh bridge details").performClick()
        compose.onNodeWithText("Bridge offline — showing saved chats").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(91L), opened)
            assertEquals(1, refreshes)
        }

        compose.runOnUiThread {
            state.value =
                DickordPortalState(
                    loading = false,
                    enabled = true,
                    groups = listOf(dmsGroup()),
                    offline = false,
                )
        }
        compose.onNodeWithText("No bridged conversations").assertIsDisplayed()

        compose.runOnUiThread {
            state.value = portalState().copy(selectedGroupKey = DICKORD_PORTAL_DMS_KEY)
        }
        compose.onNodeWithText("No bridged DMs").assertDoesNotExist()
        compose.runOnUiThread {
            val serversOnly = portalState().groups.filterNot { it.key == DICKORD_PORTAL_DMS_KEY }
            state.value = portalState().copy(groups = listOf(dmsGroup()) + serversOnly, selectedGroupKey = DICKORD_PORTAL_DMS_KEY)
        }
        compose.onNodeWithText("No bridged DMs").assertIsDisplayed()

        compose.runOnUiThread {
            state.value =
                DickordPortalState(
                    loading = false,
                    enabled = true,
                    groups = listOf(dmsGroup()),
                    showArchived = true,
                    offline = false,
                )
        }
        compose.onNodeWithText("No archived chats yet").assertIsDisplayed()
    }

    @Test
    fun headerAndConversationMenusForwardExactTargets() {
        val calls = mutableListOf<String>()
        var state by mutableStateOf(
            DickordPortalState(
                loading = false,
                enabled = true,
                groups =
                    listOf(
                        dmsGroup(),
                        serverGroup(
                            networkId = 7,
                            networkName = "Bridge A",
                            guildId = "100",
                            name = "Example Server",
                            rows = listOf(channel(11, 7, "Bridge A", "general", "101", pinned = true, muted = true, archived = true)),
                        ),
                    ),
                selectedGroupKey = "guild:7:100",
                showArchived = true,
                offline = false,
            ),
        )
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                DickordPortalContent(
                    state = state,
                    onSelectGroup = { state = state.copy(selectedGroupKey = it) },
                    onShowArchived = { calls += "showArchived:$it" },
                    onRefresh = { calls += "refresh" },
                    onMarkRead = { calls += "read:$it" },
                    onSetMuted = { id, value -> calls += "mute:$id:$value" },
                    onSetPinned = { id, value -> calls += "pin:$id:$value" },
                    onSetArchived = { id, value -> calls += "archive:$id:$value" },
                    onConversationInfo = { calls += "info:$it" },
                )
            }
        }

        compose.onNodeWithTag("dickord_portal_refresh").performClick()
        compose.onNodeWithTag("dickord_portal_more").performClick()
        compose.onNodeWithTag("dickord_portal_archive").performClick()
        invokeMenu("dickord_menu_mark_read")
        invokeMenu("dickord_menu_mute")
        invokeMenu("dickord_menu_pin")
        invokeMenu("dickord_menu_archive")
        invokeMenu("dickord_menu_info")

        compose.runOnIdle {
            assertEquals(
                listOf(
                    "refresh",
                    "showArchived:false",
                    "read:11",
                    "mute:11:false",
                    "pin:11:false",
                    "archive:11:false",
                    "info:11",
                ),
                calls,
            )
        }
    }

    private fun invokeMenu(actionTag: String) {
        compose.onNodeWithTag("dickord_conversation_11").performTouchInput { longClick() }
        compose.onNodeWithTag(actionTag).performClick()
    }

    private fun render(
        initial: DickordPortalState,
        avatarStyle: AvatarStyle = AvatarStyle.INITIALS,
        remoteAvatars: RemoteAvatarState = RemoteAvatarState(),
        onOpenConversation: (Long) -> Unit = {},
        onRefresh: () -> Unit = {},
    ): MutableState<DickordPortalState> {
        val state = mutableStateOf(initial)
        compose.setContent {
            CompositionLocalProvider(LocalRemoteAvatars provides remoteAvatars) {
                MotdTheme(dynamicColor = false, avatarStyle = avatarStyle) {
                    DickordPortalContent(
                        state = state.value,
                        onSelectGroup = { state.value = state.value.copy(selectedGroupKey = it) },
                        onOpenConversation = onOpenConversation,
                        onRefresh = onRefresh,
                    )
                }
            }
        }
        return state
    }

    private fun portalState(): DickordPortalState =
        DickordPortalState(
            loading = false,
            enabled = true,
            groups =
                listOf(
                    dmsGroup(dm(21, 7, "Bridge A", "Alice Smith", "301")),
                    serverGroup(
                        networkId = 7,
                        networkName = "Bridge A",
                        guildId = "100",
                        name = "Example Server",
                        rows =
                            listOf(
                                channel(11, 7, "Bridge A", "general", "101"),
                                channel(12, 7, "Bridge A", "release.notes_20", "102"),
                            ),
                    ),
                    serverGroup(networkId = 8, networkName = "Bridge B", guildId = "200", name = "Second Server", rows = listOf(channel(31, 8, "Bridge B", "ops", "201"))),
                ),
            offline = false,
        )

    private fun dmsGroup(vararg conversations: DickordPortalConversation) =
        DickordPortalGroup(
            key = DICKORD_PORTAL_DMS_KEY,
            networkId = null,
            guildId = null,
            displayName = null,
            iconUrl = null,
            conversations = conversations.toList(),
        )

    private fun pendingGroup(vararg rows: ChatListRow) =
        DickordPortalGroup(
            key = DICKORD_PORTAL_PENDING_KEY,
            networkId = null,
            guildId = null,
            displayName = null,
            iconUrl = null,
            conversations = rows.map { DickordPortalConversation(it, null) },
        )

    private fun serverGroup(
        networkId: Long,
        networkName: String,
        guildId: String,
        name: String,
        rows: List<DickordPortalConversation>,
    ) = DickordPortalGroup(
        key = "guild:$networkId:$guildId",
        networkId = networkId,
        guildId = guildId,
        displayName = name,
        iconUrl = "https://cdn.example.test/$guildId.png",
        conversations = rows.map { it.copy(row = it.row.copy(networkName = networkName)) },
    )

    private fun channel(
        id: Long,
        networkId: Long,
        networkName: String,
        name: String,
        channelId: String,
        pinned: Boolean = false,
        muted: Boolean = false,
        archived: Boolean = false,
    ) = DickordPortalConversation(
        row =
            row(
                id = id,
                networkId = networkId,
                networkName = networkName,
                rawName = "#discord.example.${name.replace('.', '_')}_encoded",
                pinned = pinned,
                muted = muted,
                archived = archived,
            ),
        descriptor =
            DickordChannelDescriptor(
                v = 1,
                guildId = "100",
                guildName = "Example Server",
                channelId = channelId,
                channelType = 0,
                parentId = null,
                channelName = name,
                guildIconUrl = null,
            ),
    )

    private fun dm(
        id: Long,
        networkId: Long,
        networkName: String,
        name: String,
        channelId: String,
    ) = DickordPortalConversation(
        row = row(id, networkId, networkName, "#discord.dm.alice-smith_encoded", lastMessageSender = "Alice/discord"),
        descriptor =
            DickordChannelDescriptor(
                v = 1,
                guildId = null,
                guildName = null,
                channelId = channelId,
                channelType = 1,
                parentId = null,
                channelName = name,
                guildIconUrl = null,
                channelIconUrl = "https://cdn.discordapp.com/avatars/301/avatar.png",
            ),
    )

    private fun row(
        id: Long,
        networkId: Long,
        networkName: String,
        rawName: String,
        pinned: Boolean = false,
        muted: Boolean = false,
        archived: Boolean = false,
        lastMessageSender: String? = null,
    ) = ChatListRow(
        bufferId = id,
        networkId = networkId,
        networkName = networkName,
        displayName = rawName,
        type = BufferType.CHANNEL,
        pinned = pinned,
        muted = muted,
        lastMessageText = "Latest bridged message",
        lastMessageSender = lastMessageSender,
        lastMessageTime = 1_700_000_000_000,
        unreadCount = 2,
        mentionCount = 1,
        archived = archived,
    )
}
