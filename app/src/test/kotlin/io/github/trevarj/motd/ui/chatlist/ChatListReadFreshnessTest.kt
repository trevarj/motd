package io.github.trevarj.motd.ui.chatlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.InvitationEventRow
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.GlobalFeedPrefs
import io.github.trevarj.motd.data.prefs.HistorySyncMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.OnboardingPrefs
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.data.sync.InvitePayloadV1
import io.github.trevarj.motd.data.sync.NoopHistoryGapFiller
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.service.AppVisibility
import io.github.trevarj.motd.service.BufferReadMarker
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ChannelCloseCoordinator
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.HistoryResyncController
import io.github.trevarj.motd.service.HistoryResyncState
import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.service.ReadMarkerSnapshotter
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the chat list is holding at the instant navigation composes it again.
 *
 * Opening a chat disposes this pane on a phone, so the tests below model a visit as "cancel the
 * pane's collection, let the sharing timeout expire, change the data, compose again" — and assert
 * against `state.value`, because that single read IS the pane's first frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatListReadFreshnessTest {
    private class FakeBufferRepository(
        private val rows: Flow<List<ChatListRow>>,
        private val invitations: Flow<List<InvitationEventRow>> = flowOf(emptyList()),
    ) : BufferRepository {
        override fun observeChatList(): Flow<List<ChatListRow>> = rows

        override fun observeInvitations(): Flow<List<InvitationEventRow>> = invitations

        override fun observeBuffer(id: Long): Flow<BufferEntity?> = flowOf(null)

        override fun observeMembers(bufferId: Long): Flow<List<MemberEntity>> = flowOf(emptyList())

        override suspend fun setPinned(
            id: Long,
            pinned: Boolean,
        ) = Unit

        override suspend fun setMuted(
            id: Long,
            muted: Boolean,
        ): MuteBacklogSuppression? = null

        override suspend fun setLayoutDensityOverride(
            id: Long,
            layout: LayoutDensity?,
        ): Boolean = true

        override suspend fun setPresenceModeOverride(
            id: Long,
            mode: PresenceMode?,
        ): Boolean = true

        override suspend fun setHistorySyncModeOverride(
            id: Long,
            mode: HistorySyncMode?,
        ): Boolean = error("Unexpected history sync mode override write")

        override suspend fun deleteBuffer(id: Long) = Unit
    }

    private class FakeNetworkRepository : NetworkRepository {
        override fun observeNetworks(): Flow<List<NetworkEntity>> = flowOf(emptyList())

        override suspend fun addNetwork(n: NetworkEntity): Long = 0

        override suspend fun updateNetwork(n: NetworkEntity) = Unit

        override suspend fun deleteNetwork(id: Long) = Unit

        override suspend fun reorderNetworks(orderedIds: List<Long>) = Unit

        override suspend fun networkById(id: Long): NetworkEntity? = null

        override suspend fun childrenOf(rootId: Long): List<NetworkEntity> = emptyList()
    }

    private class FakeConnectionManager : NoopConnectionManager() {
        override val connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(emptyMap())

        override suspend fun ensureQueryBuffer(
            networkId: Long,
            nick: String,
        ): Long = 0

        override suspend fun ensureServerBuffer(networkId: Long): Long = 0

        override suspend fun markRead(
            bufferId: Long,
            anchor: io.github.trevarj.motd.data.db.TimelineAnchor,
        ) = Unit
    }

    private class FakeSettingsRepository : SettingsRepository {
        override val settings =
            MutableStateFlow(
                Settings(ThemeMode.SYSTEM, true, DeliveryMode.PERSISTENT_SOCKET),
            )

        override suspend fun setThemeMode(m: ThemeMode) = Unit

        override suspend fun setDynamicColor(enabled: Boolean) = Unit

        override suspend fun setDeliveryMode(m: DeliveryMode) = Unit

        override suspend fun setLayoutDensity(d: LayoutDensity) = Unit

        override suspend fun setNickColorsEnabled(enabled: Boolean) = Unit

        override suspend fun setNickColorPalette(p: NickColorPalette) = Unit

        override suspend fun setNickColorOverride(
            nick: String,
            hue: Int?,
        ) = Unit

        override suspend fun setFriend(
            nick: String,
            isFriend: Boolean,
        ) = Unit

        override suspend fun setFool(
            nick: String,
            isFool: Boolean,
        ) = Unit

        override suspend fun setFoolsMode(m: FoolsMode) = Unit

        override suspend fun setPresenceMode(m: PresenceMode) = Unit

        override suspend fun setAvatarStyle(style: AvatarStyle) = Unit

        override suspend fun setChatWallpaper(w: io.github.trevarj.motd.data.prefs.ChatWallpaper) = Unit

        override suspend fun setShowComposerEmoji(show: Boolean) = Unit

        override suspend fun setShowComposerFormattingTools(show: Boolean) = Unit

        override suspend fun setChatSoundsEnabled(enabled: Boolean) = Unit

        override suspend fun setHistorySyncDepth(d: io.github.trevarj.motd.data.prefs.HistorySyncDepth) = Unit

        override suspend fun setHistorySyncMode(mode: HistorySyncMode) {
            settings.value = settings.value.copy(historySyncMode = mode)
        }

        override suspend fun setAutoAwayEnabled(enabled: Boolean) = Unit

        override suspend fun setAutoAwayMinutes(minutes: Int) = Unit

        override suspend fun setAutoAwayMessage(message: String) = Unit
    }

    /** The process lifecycle the test drives by hand. */
    private class FakeAppVisibility(
        onScreen: Boolean,
    ) : AppVisibility {
        private val _onScreen = MutableStateFlow(onScreen)
        override val onScreen: StateFlow<Boolean> = _onScreen.asStateFlow()

        fun set(value: Boolean) {
            _onScreen.value = value
        }
    }

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(
        rows: Flow<List<ChatListRow>>,
        visibility: AppVisibility,
        dickordEnabled: Flow<Boolean> = flowOf(false),
        invitations: Flow<List<InvitationEventRow>> = flowOf(emptyList()),
        connections: ConnectionManager = FakeConnectionManager(),
        networks: NetworkRepository = FakeNetworkRepository(),
        buffers: BufferRepository = FakeBufferRepository(rows, invitations),
        resync: HistoryResyncController? = null,
    ) = ChatListViewModel(
        bufferRepository = buffers,
        networkRepository = networks,
        connectionManager = connections,
        gapFiller = NoopHistoryGapFiller,
        historyResync =
            resync ?: object : HistoryResyncController {
                override fun syncStatus(bufferId: Long) = flowOf<HistorySyncStatus>(HistorySyncStatus.Idle)

                override suspend fun reconcileBuffer(
                    buffer: BufferEntity,
                    client: IrcClient,
                    preserveUnread: Boolean,
                    isCurrent: () -> Boolean,
                ) = HistoryResyncState.Idle

                override suspend fun reconcilePendingMessage(
                    buffer: BufferEntity,
                    client: IrcClient,
                    isCurrent: () -> Boolean,
                ) = HistoryResyncState.Idle
            },
        channelCloseCoordinator =
            object : ChannelCloseCoordinator {
                override fun start() = Unit

                override suspend fun requestClose(bufferId: Long) = Unit
            },
        readMarkerRepository =
            object : ReadMarkerSnapshotter {
                override suspend fun latestIncoming(bufferIds: Collection<Long>): List<BufferReadMarker> = emptyList()
            },
        settingsRepository = FakeSettingsRepository(),
        onboardingPrefs =
            object : OnboardingPrefs {
                override val completed = flowOf(true)

                override suspend fun markCompleted() = Unit
            },
        globalFeedPrefs =
            object : GlobalFeedPrefs {
                override val enabled = flowOf(false)

                override suspend fun setEnabled(enabled: Boolean) = Unit
            },
        dickordLabsPrefs = fakeDickordLabsPrefs(dickordEnabled),
        savedStateHandle = SavedStateHandle(),
        appVisibility = visibility,
    )

    private fun unreadRow(count: Int) =
        ChatListRow(
            bufferId = 7,
            networkId = 1,
            networkName = "libera",
            displayName = "#kotlin",
            type = BufferType.CHANNEL,
            pinned = false,
            muted = false,
            lastMessageText = "hey",
            lastMessageSender = "alice",
            lastMessageTime = 100,
            unreadCount = count,
            mentionCount = 0,
        )

    /** The chat-list pane, composed. Cancelling it is navigation disposing the destination. */
    private fun TestScope.composePane(viewModel: ChatListViewModel): Job = launch { viewModel.state.collect {} }.also { runCurrent() }

    private fun unreadCount(viewModel: ChatListViewModel) =
        viewModel.state.value.rows
            .single()
            .unreadCount

    @Test
    fun `a chat read while the pane was away is already read on the frame it comes back`() =
        runTest {
            val rows = MutableStateFlow(listOf(unreadRow(3)))
            val visibility = FakeAppVisibility(onScreen = true)
            val viewModel = vm(rows, visibility)

            val pane = composePane(viewModel)
            assertEquals(3, unreadCount(viewModel))

            // Open the chat: navigation disposes this pane, and the visit outlasts the sharing timeout.
            pane.cancel()
            advanceTimeBy(30_000)
            runCurrent()

            // The reader clears the room; EventProcessor advances the marker and Room republishes.
            rows.value = listOf(unreadRow(0))
            runCurrent()

            // Back. This read is the first frame, before any restarted flow could emit into it.
            assertEquals(0, unreadCount(viewModel))
            composePane(viewModel).cancel()
        }

    @Test
    fun `the chat list stops observing while the app is off screen`() =
        runTest {
            val rows = MutableStateFlow(listOf(unreadRow(3)))
            val visibility = FakeAppVisibility(onScreen = true)
            val viewModel = vm(rows, visibility)

            val pane = composePane(viewModel)
            assertEquals(3, unreadCount(viewModel))

            // Home button: the pane goes with the app, and nothing may keep querying behind it.
            pane.cancel()
            visibility.set(false)
            advanceTimeBy(30_000)
            runCurrent()

            rows.value = listOf(unreadRow(9))
            runCurrent()
            assertEquals(3, unreadCount(viewModel))

            // Foregrounding re-arms the observation without waiting for a pane to compose.
            visibility.set(true)
            runCurrent()
            assertEquals(9, unreadCount(viewModel))
        }

    @Test
    fun `Dickord flag partitions every ordinary list without mutating rows`() =
        runTest {
            val ordinary = unreadRow(2).copy(bufferId = 1)
            val portal =
                unreadRow(4).copy(
                    bufferId = 2,
                    displayName = "#DiScOrD.guild.general",
                    pinned = true,
                    folderId = 9,
                )
            val archivedPortal =
                unreadRow(8).copy(
                    bufferId = 3,
                    displayName = "#discord.guild.old",
                    archived = true,
                    folderId = 10,
                )
            val control = unreadRow(1).copy(bufferId = 4, displayName = "#discord.control")
            val invitationEvents =
                flowOf(
                    listOf(
                        InvitationEventRow(
                            messageId = 11,
                            bufferId = portal.bufferId,
                            networkId = portal.networkId,
                            networkName = portal.networkName,
                            text = "alice invited you",
                            eventPayload = InvitePayloadV1("alice", "me", portal.displayName).encode(),
                            inviteState = InviteState.PENDING,
                            serverTime = 10,
                        ),
                    ),
                )
            val rows = MutableStateFlow(listOf(ordinary, portal, archivedPortal, control))
            val enabled = MutableStateFlow(false)
            val viewModel = vm(rows, FakeAppVisibility(onScreen = true), enabled, invitationEvents)
            val pane = composePane(viewModel)

            assertEquals(
                listOf(1L, 2L, 4L),
                viewModel.state.value.rows
                    .map(ChatListRow::bufferId),
            )
            assertEquals(
                listOf(3L),
                viewModel.state.value.archivedRows
                    .map(ChatListRow::bufferId),
            )
            assertEquals(null, viewModel.state.value.dickordUnreadSummary)

            enabled.value = true
            runCurrent()

            val active = viewModel.state.value
            assertEquals(listOf(1L, 4L), active.rows.map(ChatListRow::bufferId))
            assertEquals(emptyList<Long>(), active.archivedRows.map(ChatListRow::bufferId))
            assertEquals(listOf(portal.bufferId), active.invitations.map(ChatListInvitation::bufferId))
            assertEquals(1, active.dickordUnreadSummary?.visibleCount)
            assertEquals(4, active.dickordUnreadSummary?.unreadCount)
            assertEquals(3, active.scopedUnreadCount)
            assertEquals(true, portal.pinned)
            assertEquals(9L, portal.folderId)
            assertEquals("#DiScOrD.guild.general", portal.displayName)

            enabled.value = false
            runCurrent()

            assertEquals(
                listOf(1L, 2L, 4L),
                viewModel.state.value.rows
                    .map(ChatListRow::bufferId),
            )
            assertEquals(
                listOf(3L),
                viewModel.state.value.archivedRows
                    .map(ChatListRow::bufferId),
            )
            assertEquals(
                9L,
                viewModel.state.value.rows
                    .first { it.bufferId == 2L }
                    .folderId,
            )
            pane.cancel()
        }

    @Test
    fun networkActivityCapturesWithoutScreenSubscribersAndAcknowledgementKeepsRawReason() =
        runTest {
            val network = NetworkEntity(id = 1, name = "Libera", role = NetworkRole.DIRECT, host = "irc.test", port = 6697, nick = "me", username = "me", realname = "Me")
            val networks =
                object : NetworkRepository by FakeNetworkRepository() {
                    override fun observeNetworks() = flowOf(listOf(network))

                    override suspend fun networkById(id: Long) = network.takeIf { it.id == id }
                }
            val connections = FakeConnectionManager()
            val statuses = MutableStateFlow<Map<Long, HistorySyncStatus>>(emptyMap())
            val room = BufferEntity(id = 7, networkId = 1, name = "#kotlin", displayName = "#kotlin", type = BufferType.CHANNEL)
            val buffers =
                object : BufferRepository by FakeBufferRepository(flowOf(emptyList())) {
                    override fun observeBuffer(id: Long) = flowOf(room.takeIf { it.id == id })
                }
            val resync =
                object : HistoryResyncController {
                    override val syncStatuses = statuses

                    override suspend fun reconcileBuffer(
                        buffer: BufferEntity,
                        client: IrcClient,
                        preserveUnread: Boolean,
                        isCurrent: () -> Boolean,
                    ) = HistoryResyncState.Idle

                    override suspend fun reconcilePendingMessage(
                        buffer: BufferEntity,
                        client: IrcClient,
                        isCurrent: () -> Boolean,
                    ) = HistoryResyncState.Idle
                }
            val model = vm(flowOf(emptyList()), FakeAppVisibility(false), connections = connections, networks = networks, buffers = buffers, resync = resync)
            try {
                runCurrent()
                // No state, chrome or ledger collectors: the retained entry owns capture, not its UI.
                assertEquals(false, model.hasUnseenNetworkActivity.value)
                connections.connectionStates.value = mapOf(1L to IrcClientState.Failed("full connection reason", true))
                statuses.value = mapOf(7L to HistorySyncStatus.Failed("full history reason"))
                runCurrent()
                assertEquals(2, model.networkActivity.value.unacknowledgedCount)
                assertEquals(true, model.hasUnseenNetworkActivity.value)
                model.markNetworkActivitySeen()
                runCurrent()
                assertEquals(false, model.hasUnseenNetworkActivity.value)
                assertEquals(2, model.networkActivity.value.unacknowledgedCount)
                backgroundScope.launch { model.syncIndicators.collect {} }
                runCurrent()
                assertEquals(ChatListSyncIndicator.ERROR, model.syncIndicators.value[7])
                val history =
                    model.networkActivity.value.active
                        .single { it.bufferId == 7L }
                model.networkActivityAction(history, NetworkActivityAction.ACKNOWLEDGE, {}, {})
                runCurrent()
                assertEquals(HistorySyncStatus.Failed("full history reason"), statuses.value[7])
                assertEquals(
                    "full history reason",
                    model.networkActivity.value.active
                        .single { it.bufferId == 7L }
                        .reason,
                )
                assertEquals(1, model.networkActivity.value.unacknowledgedCount)
                assertEquals(null, model.syncIndicators.value[7])
                assertEquals(false, model.hasUnseenNetworkActivity.value)
                statuses.value = mapOf(7L to HistorySyncStatus.Syncing)
                runCurrent()
                assertEquals(ChatListSyncIndicator.SYNCING, model.syncIndicators.value[7])
                statuses.value = mapOf(7L to HistorySyncStatus.Failed("full history reason"))
                runCurrent()
                assertEquals(2, model.networkActivity.value.unacknowledgedCount)
                assertEquals(ChatListSyncIndicator.ERROR, model.syncIndicators.value[7])
                assertEquals(false, model.hasUnseenNetworkActivity.value)
                model.networkActivityAction(history, NetworkActivityAction.ACKNOWLEDGE, {}, {})
                runCurrent()
                assertEquals(2, model.networkActivity.value.unacknowledgedCount)
                statuses.value = mapOf(7L to HistorySyncStatus.Partial("full history reason"))
                runCurrent()
                assertEquals(false, model.hasUnseenNetworkActivity.value)
                statuses.value = mapOf(7L to HistorySyncStatus.Failed("full history reason"))
                runCurrent()
                assertEquals(true, model.hasUnseenNetworkActivity.value)
                model.markNetworkActivitySeen()
                runCurrent()
                assertEquals(false, model.hasUnseenNetworkActivity.value)
                statuses.value = mapOf(7L to HistorySyncStatus.Failed("new exact cause"))
                runCurrent()
                assertEquals(true, model.hasUnseenNetworkActivity.value)
                statuses.value = emptyMap()
                runCurrent()
                // The new history cause cleared, but the older connection cause still holds attention.
                assertEquals(1, model.networkActivity.value.active.size)
                assertEquals(true, model.hasUnseenNetworkActivity.value)
                connections.connectionStates.value = mapOf(1L to IrcClientState.Ready("me", emptySet(), emptyMap()))
                statuses.value = emptyMap()
                runCurrent()
                assertEquals(false, model.hasUnseenNetworkActivity.value)
                assertEquals(
                    setOf(NetworkActivityDisposition.CONNECTED, NetworkActivityDisposition.NO_LONGER_REPORTED),
                    model.networkActivity.value.recent
                        .map { it.disposition }
                        .toSet(),
                )
                assertEquals(false, model.hasUnseenNetworkActivity.value)
                connections.connectionStates.value = mapOf(1L to IrcClientState.Failed("later failure", true))
                runCurrent()
                assertEquals(true, model.hasUnseenNetworkActivity.value)
                model.markNetworkActivitySeen()
                runCurrent()
                assertEquals(false, model.hasUnseenNetworkActivity.value)
                val fresh = vm(flowOf(emptyList()), FakeAppVisibility(false), connections = connections, networks = networks, buffers = buffers, resync = resync)
                try {
                    runCurrent()
                    assertEquals(emptyList<NetworkActivityIssue>(), fresh.networkActivity.value.recent)
                    assertEquals(1L, fresh.networkActivity.value.latestAttentionSequence)
                    assertEquals(true, fresh.hasUnseenNetworkActivity.value)
                    fresh.markNetworkActivitySeen()
                    runCurrent()
                    assertEquals(false, fresh.hasUnseenNetworkActivity.value)
                } finally {
                    fresh.viewModelScope.cancel()
                }
            } finally {
                model.viewModelScope.cancel()
            }
        }

    @Test
    fun networkActivityOpenChatUsesCanonicalBufferAndRejectsStaleOrDeletedTargets() =
        runTest {
            val network = NetworkEntity(id = 1, name = "Libera", role = NetworkRole.DIRECT, host = "irc.test", port = 6697, nick = "me", username = "me", realname = "Me")
            var saved: NetworkEntity? = network
            val networks =
                object : NetworkRepository by FakeNetworkRepository() {
                    override fun observeNetworks() = flowOf(listOf(network))

                    override suspend fun networkById(id: Long) = saved?.takeIf { it.id == id }
                }
            val statuses = MutableStateFlow<Map<Long, HistorySyncStatus>>(mapOf(7L to HistorySyncStatus.Failed("original")))
            val projectedRoom = MutableStateFlow(BufferEntity(id = 8, networkId = 1, name = "#kotlin", displayName = "#kotlin", type = BufferType.CHANNEL))
            val buffers =
                object : BufferRepository by FakeBufferRepository(flowOf(emptyList())) {
                    override suspend fun canonicalBufferId(id: Long) = if (id == 7L) 8L else id

                    override fun observeBuffer(id: Long) = projectedRoom
                }
            val resync =
                object : HistoryResyncController {
                    override val syncStatuses = statuses

                    override suspend fun reconcileBuffer(
                        buffer: BufferEntity,
                        client: IrcClient,
                        preserveUnread: Boolean,
                        isCurrent: () -> Boolean,
                    ) = HistoryResyncState.Idle

                    override suspend fun reconcilePendingMessage(
                        buffer: BufferEntity,
                        client: IrcClient,
                        isCurrent: () -> Boolean,
                    ) = HistoryResyncState.Idle
                }
            val model = vm(flowOf(emptyList()), FakeAppVisibility(false), networks = networks, buffers = buffers, resync = resync)
            runCurrent()
            val original =
                model.networkActivity.value.active
                    .single()
            assertEquals(7L, original.bufferId)
            projectedRoom.value = projectedRoom.value.copy(displayName = "#merged")
            runCurrent()
            assertEquals(
                original.episodeId,
                model.networkActivity.value.active
                    .single()
                    .episodeId,
            )
            assertEquals(
                1,
                model.networkActivity.value.active
                    .single()
                    .occurrences,
            )
            assertEquals(emptyList<NetworkActivityIssue>(), model.networkActivity.value.recent)
            model.networkActivityAction(original, NetworkActivityAction.ACKNOWLEDGE, {}, {})
            runCurrent()
            val acknowledged =
                model.networkActivity.value.active
                    .single()
            assertEquals(true, acknowledged.acknowledged)
            assertEquals(HistorySyncStatus.Failed("original"), statuses.value[7L])
            val opened = mutableListOf<Long>()
            model.networkActivityAction(acknowledged, NetworkActivityAction.OPEN_CHAT, opened::add, {})
            runCurrent()
            assertEquals(listOf(8L), opened)
            statuses.value = mapOf(7L to HistorySyncStatus.Partial("different cause"))
            runCurrent()
            model.networkActivityAction(acknowledged, NetworkActivityAction.OPEN_CHAT, opened::add, {})
            runCurrent()
            assertEquals(listOf(8L), opened)
            saved = null
            model.networkActivityAction(
                model.networkActivity.value.active
                    .last(),
                NetworkActivityAction.OPEN_CHAT,
                opened::add,
                {},
            )
            runCurrent()
            assertEquals(listOf(8L), opened)
        }

    @Test
    fun networkActivityRetryReusesCanonicalCurrentClientAndDoesNotInventResolutionFromReturn() =
        runTest {
            val network = NetworkEntity(id = 1, name = "Libera", role = NetworkRole.DIRECT, host = "irc.test", port = 6697, nick = "me", username = "me", realname = "Me")
            val networks =
                object : NetworkRepository by FakeNetworkRepository() {
                    override fun observeNetworks() = flowOf(listOf(network))

                    override suspend fun networkById(id: Long) = network.takeIf { it.id == id }
                }
            val originalClient = IrcClient(IrcClientConfig("irc.test", 6697, true, "me", "me", "Me"), TransportFactory { _, _, _, _, _ -> error("Controller owns reconciliation") }, backgroundScope)
            var currentClient: IrcClient? = originalClient
            val connections =
                object : NoopConnectionManager() {
                    override val connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(mapOf(1L to IrcClientState.Ready("me", emptySet(), emptyMap())))

                    override fun clientFor(networkId: Long) = currentClient.takeIf { networkId == 1L }
                }
            val statuses = MutableStateFlow<Map<Long, HistorySyncStatus>>(mapOf(7L to HistorySyncStatus.Partial("full partial reason")))
            val buffers =
                object : BufferRepository by FakeBufferRepository(flowOf(emptyList())) {
                    override suspend fun canonicalBufferId(id: Long) = if (id == 7L) 8L else id

                    override fun observeBuffer(id: Long) = flowOf(BufferEntity(id = if (id == 7L) 8L else id, networkId = 1, name = "#kotlin", displayName = "#kotlin", type = BufferType.CHANNEL))
                }
            var calls = 0
            val resync =
                object : HistoryResyncController {
                    override val syncStatuses = statuses

                    override suspend fun reconcileBuffer(
                        buffer: BufferEntity,
                        client: IrcClient,
                        preserveUnread: Boolean,
                        isCurrent: () -> Boolean,
                    ): HistoryResyncState {
                        calls++
                        assertEquals(8L, buffer.id)
                        assertEquals(originalClient, client)
                        assertEquals(true, isCurrent())
                        currentClient = null
                        assertEquals(false, isCurrent())
                        return HistoryResyncState.UpToDate
                    }

                    override suspend fun reconcilePendingMessage(
                        buffer: BufferEntity,
                        client: IrcClient,
                        isCurrent: () -> Boolean,
                    ) = HistoryResyncState.Idle
                }
            val model = vm(flowOf(emptyList()), FakeAppVisibility(false), connections = connections, networks = networks, buffers = buffers, resync = resync)
            runCurrent()
            val issue =
                model.networkActivity.value.active
                    .single()
            assertEquals(7L, issue.bufferId)
            model.networkActivityAction(issue, NetworkActivityAction.RETRY_HISTORY, {}, {})
            runCurrent()
            assertEquals(1, calls)
            assertEquals(
                "full partial reason",
                model.networkActivity.value.active
                    .single()
                    .reason,
            )
            assertEquals(emptyList<NetworkActivityIssue>(), model.networkActivity.value.recent)
            model.networkActivityAction(issue, NetworkActivityAction.RETRY_HISTORY, {}, {})
            runCurrent()
            assertEquals(1, calls)
        }

    @Test
    fun networkActivityConnectNeverBypassesPendingCertificateAndGoOfflineRetiresExplicitly() =
        runTest {
            val network = NetworkEntity(id = 1, name = "Libera", role = NetworkRole.DIRECT, host = "irc.test", port = 6697, nick = "me", username = "me", realname = "Me")
            val networks =
                object : NetworkRepository by FakeNetworkRepository() {
                    override fun observeNetworks() = flowOf(listOf(network))

                    override suspend fun networkById(id: Long) = network.takeIf { it.id == id }
                }
            var connects = 0
            var disconnects = 0
            val connections =
                object : NoopConnectionManager() {
                    override val connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(mapOf(1L to IrcClientState.Failed("certificate reason", true)))
                    override val certPrompts = MutableStateFlow(listOf(CertPrompt(1, "irc.test", 6697, "fingerprint", "subject", "issuer", 0, 1, false)))

                    override suspend fun connect(networkId: Long) {
                        connects++
                    }

                    override suspend fun disconnect(networkId: Long) {
                        disconnects++
                    }
                }
            val model = vm(flowOf(emptyList()), FakeAppVisibility(false), connections = connections, networks = networks)
            val pane = composePane(model)
            runCurrent()
            model.networkActivityAction(
                model.networkActivity.value.active
                    .single(),
                NetworkActivityAction.CONNECT,
                {},
                {},
            )
            model.networkActivityNetworkAction(1, NetworkActivityAction.CONNECT, {}, {})
            runCurrent()
            assertEquals(0, connects)
            assertEquals(1, connections.certPrompts.value.size)
            model.goOffline()
            runCurrent()
            assertEquals(1, disconnects)
            assertEquals(emptyList<NetworkActivityIssue>(), model.networkActivity.value.active)
            assertEquals(
                NetworkActivityDisposition.STOPPED,
                model.networkActivity.value.recent
                    .single()
                    .disposition,
            )
            pane.cancel()
        }

    @Test
    fun delayedNetworkActivityConnectDoesNotRestartReadyOrProgressingSocket() =
        runTest {
            val network = NetworkEntity(id = 1, name = "Libera", role = NetworkRole.DIRECT, host = "irc.test", port = 6697, nick = "me", username = "me", realname = "Me")
            var lookupGate = CompletableDeferred<Unit>()
            val networks =
                object : NetworkRepository by FakeNetworkRepository() {
                    override fun observeNetworks() = flowOf(listOf(network))

                    override suspend fun networkById(id: Long): NetworkEntity? {
                        lookupGate.await()
                        return network.takeIf { it.id == id }
                    }
                }
            var connects = 0
            val connections =
                object : NoopConnectionManager() {
                    override val connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(mapOf(1L to IrcClientState.Failed("timeout", false)))

                    override suspend fun connect(networkId: Long) {
                        connects++
                    }
                }
            val model = vm(flowOf(emptyList()), FakeAppVisibility(false), networks = networks, connections = connections)
            runCurrent()
            for (live in listOf(IrcClientState.Connecting, IrcClientState.Registering, IrcClientState.Ready("me", emptySet(), emptyMap()))) {
                connections.connectionStates.value = mapOf(1L to IrcClientState.Failed("timeout", false))
                lookupGate = CompletableDeferred()
                model.networkActivityNetworkAction(1, NetworkActivityAction.CONNECT, {}, {})
                runCurrent()
                connections.connectionStates.value = mapOf(1L to live)
                lookupGate.complete(Unit)
                runCurrent()
                assertEquals(0, connects)
            }
            connections.connectionStates.value = mapOf(1L to IrcClientState.Disconnected)
            model.networkActivityNetworkAction(1, NetworkActivityAction.CONNECT, {}, {})
            runCurrent()
            assertEquals(1, connects)
        }

    @Test
    fun goOfflineUsesDrawerNetworksEvenWhileActivityBufferMetadataHasNotEmitted() =
        runTest {
            val network = NetworkEntity(id = 1, name = "Libera", role = NetworkRole.DIRECT, host = "irc.test", port = 6697, nick = "me", username = "me", realname = "Me")
            val networks =
                object : NetworkRepository by FakeNetworkRepository() {
                    override fun observeNetworks() = flowOf(listOf(network))
                }
            val disconnected = mutableListOf<Long>()
            val connections =
                object : NoopConnectionManager() {
                    override val connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(mapOf(1L to IrcClientState.Ready("me", emptySet(), emptyMap())))

                    override suspend fun disconnect(networkId: Long) {
                        disconnected += networkId
                    }
                }
            val metadata = MutableSharedFlow<BufferEntity?>()
            val buffers =
                object : BufferRepository by FakeBufferRepository(flowOf(emptyList())) {
                    override fun observeBuffer(id: Long): Flow<BufferEntity?> = metadata
                }
            val resync =
                object : HistoryResyncController {
                    override val syncStatuses = MutableStateFlow<Map<Long, HistorySyncStatus>>(mapOf(7L to HistorySyncStatus.Failed("awaiting metadata")))

                    override suspend fun reconcileBuffer(
                        buffer: BufferEntity,
                        client: IrcClient,
                        preserveUnread: Boolean,
                        isCurrent: () -> Boolean,
                    ) = HistoryResyncState.Idle

                    override suspend fun reconcilePendingMessage(
                        buffer: BufferEntity,
                        client: IrcClient,
                        isCurrent: () -> Boolean,
                    ) = HistoryResyncState.Idle
                }
            val model = vm(flowOf(emptyList()), FakeAppVisibility(false), networks = networks, connections = connections, buffers = buffers, resync = resync)
            val pane = composePane(model)
            assertEquals(
                listOf(1L),
                model.state.value.networks
                    .map { it.id },
            )
            assertEquals(emptyList<NetworkActivityNetwork>(), model.networkActivity.value.networks)
            model.goOffline()
            runCurrent()
            assertEquals(listOf(1L), disconnected)
            pane.cancel()
        }
}
