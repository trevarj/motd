package io.github.trevarj.motd.ui.chatlist

import androidx.lifecycle.SavedStateHandle
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.GlobalFeedPrefs
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.OnboardingPrefs
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.AlwaysOnScreen
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Covers durable channel-close requests, immediate local-only deletion, and the row actions that
 * report back to the screen (archive overrides, unmute backlog dismissal).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListDeleteTest {
    /** Records the delete calls; other reads return empty streams (state is not under test here). */
    private open class FakeBufferRepository : BufferRepository {
        val deleted = mutableListOf<Long>()

        override fun observeChatList(): Flow<List<ChatListRow>> = flowOf(emptyList())

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

        override suspend fun deleteBuffer(id: Long) {
            deleted += id
        }
    }

    /** Reports a hidden backlog for every unmute and records the floors an undo puts back. */
    private class MutingBufferRepository : FakeBufferRepository() {
        val restored = mutableListOf<MuteBacklogSuppression>()

        override suspend fun setMuted(
            id: Long,
            muted: Boolean,
        ): MuteBacklogSuppression? = if (muted) null else MuteBacklogSuppression(id, previousFloorTime = id * 10)

        override suspend fun restoreMuteBacklog(suppression: MuteBacklogSuppression) {
            restored += suppression
        }
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

    /** Appends every part/delete to a shared [ops] log so ordering can be asserted. */
    private class FakeConnectionManager(
        private val ops: MutableList<String>,
    ) : NoopConnectionManager() {
        override val connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(emptyMap())

        override suspend fun partChannel(
            bufferId: Long,
            reason: String?,
        ) {
            ops += "part:$bufferId"
        }

        override suspend fun ensureQueryBuffer(
            networkId: Long,
            nick: String,
        ): Long = 0

        override suspend fun ensureServerBuffer(networkId: Long): Long = 0
    }

    private class FakeChannelCloseCoordinator(
        private val ops: MutableList<String>,
    ) : ChannelCloseCoordinator {
        var started = false

        override fun start() {
            started = true
        }

        override suspend fun requestClose(bufferId: Long) {
            ops += "pending:$bufferId"
        }
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

        override suspend fun setAutoAwayEnabled(enabled: Boolean) = Unit

        override suspend fun setAutoAwayMinutes(minutes: Int) = Unit

        override suspend fun setAutoAwayMessage(message: String) = Unit
    }

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(
        buffers: BufferRepository,
        cm: ConnectionManager,
        close: ChannelCloseCoordinator,
    ) = ChatListViewModel(
        bufferRepository = buffers,
        networkRepository = FakeNetworkRepository(),
        connectionManager = cm,
        historyResync =
            object : HistoryResyncController {
                override fun syncStatus(bufferId: Long) = flowOf<HistorySyncStatus>(HistorySyncStatus.Idle)

                override suspend fun reconcileBuffer(
                    buffer: BufferEntity,
                    client: IrcClient,
                    isCurrent: () -> Boolean,
                ) = HistoryResyncState.Idle

                override suspend fun reconcilePendingMessage(
                    buffer: BufferEntity,
                    client: IrcClient,
                    isCurrent: () -> Boolean,
                ) = HistoryResyncState.Idle
            },
        channelCloseCoordinator = close,
        readMarkerRepository =
            object : ReadMarkerSnapshotter {
                override suspend fun latestIncoming(
                    bufferIds: Collection<Long>,
                ): List<BufferReadMarker> = emptyList()
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
        dickordLabsPrefs = fakeDickordLabsPrefs(),
        savedStateHandle = SavedStateHandle(),
        appVisibility = AlwaysOnScreen,
    )

    private fun row(
        id: Long,
        type: BufferType,
        name: String,
    ) = ChatListRow(
        bufferId = id,
        networkId = 1,
        networkName = "libera",
        displayName = name,
        type = type,
        pinned = false,
        muted = false,
        lastMessageText = null,
        lastMessageSender = null,
        lastMessageTime = null,
        unreadCount = 0,
        mentionCount = 0,
    )

    @Test
    fun deleteChannel_marksPending_andLeavesHistoryUntilAccepted() =
        runTest {
            val ops = mutableListOf<String>()
            val buffers =
                object : FakeBufferRepository() {
                    override suspend fun deleteBuffer(id: Long) {
                        super.deleteBuffer(id)
                        ops += "delete:$id"
                    }
                }
            val close = FakeChannelCloseCoordinator(ops)
            val vm = vm(buffers, FakeConnectionManager(ops), close)

            vm.deleteBuffer(row(7, BufferType.CHANNEL, "#kotlin"))
            runCurrent()

            assertEquals(listOf("pending:7"), ops)
            assertEquals(emptyList<Long>(), buffers.deleted)
            assertEquals(true, close.started)
        }

    @Test
    fun deleteQuery_doesNotPart() =
        runTest {
            val ops = mutableListOf<String>()
            val buffers =
                object : FakeBufferRepository() {
                    override suspend fun deleteBuffer(id: Long) {
                        super.deleteBuffer(id)
                        ops += "delete:$id"
                    }
                }
            val vm = vm(buffers, FakeConnectionManager(ops), FakeChannelCloseCoordinator(ops))

            vm.deleteBuffer(row(9, BufferType.QUERY, "carol"))
            runCurrent()

            assertEquals(listOf("delete:9"), ops) // no part for a DM
            assertEquals(listOf(9L), buffers.deleted)
        }

    @Test
    fun deleteServer_doesNotPart() =
        runTest {
            val ops = mutableListOf<String>()
            val buffers =
                object : FakeBufferRepository() {
                    override suspend fun deleteBuffer(id: Long) {
                        super.deleteBuffer(id)
                        ops += "delete:$id"
                    }
                }
            val vm = vm(buffers, FakeConnectionManager(ops), FakeChannelCloseCoordinator(ops))

            vm.deleteBuffer(row(3, BufferType.SERVER, "*"))
            runCurrent()

            assertEquals(listOf("delete:3"), ops)
        }

    @Test
    fun batchDeletion_deduplicates_and_keeps_channel_coordinator_order() =
        runTest {
            val ops = mutableListOf<String>()
            val buffers =
                object : FakeBufferRepository() {
                    override suspend fun deleteBuffer(id: Long) {
                        super.deleteBuffer(id)
                        ops += "delete:$id"
                    }
                }
            val vm = vm(buffers, FakeConnectionManager(ops), FakeChannelCloseCoordinator(ops))

            vm.deleteBuffers(listOf(row(7, BufferType.CHANNEL, "#kotlin"), row(9, BufferType.QUERY, "carol"), row(7, BufferType.CHANNEL, "#kotlin")))
            runCurrent()

            assertEquals(listOf("pending:7", "delete:9"), ops)
        }

    @Test
    fun unmute_announcesDismissedBacklogOnce_andUndoRestoresEveryFloor() =
        runTest {
            val ops = mutableListOf<String>()
            val buffers = MutingBufferRepository()
            val vm = vm(buffers, FakeConnectionManager(ops), FakeChannelCloseCoordinator(ops))
            val announced = mutableListOf<List<MuteBacklogSuppression>>()
            val collection = launch { vm.muteBacklogSuppressions.collect { announced += it } }
            runCurrent()

            vm.setMuted(listOf(7L, 9L), false)
            runCurrent()

            val suppressions =
                listOf(
                    MuteBacklogSuppression(7L, previousFloorTime = 70),
                    MuteBacklogSuppression(9L, previousFloorTime = 90),
                )
            assertEquals(listOf(suppressions), announced)

            vm.undoMuteBacklogSuppression(suppressions)
            runCurrent()

            assertEquals(suppressions, buffers.restored)
            collection.cancel()
        }

    @Test
    fun mute_saysNothing_becauseNoBacklogIsDismissed() =
        runTest {
            val ops = mutableListOf<String>()
            val buffers = MutingBufferRepository()
            val vm = vm(buffers, FakeConnectionManager(ops), FakeChannelCloseCoordinator(ops))
            val announced = mutableListOf<List<MuteBacklogSuppression>>()
            val collection = launch { vm.muteBacklogSuppressions.collect { announced += it } }
            runCurrent()

            vm.setMuted(7L, true)
            runCurrent()

            assertEquals(emptyList<List<MuteBacklogSuppression>>(), announced)
            collection.cancel()
        }

    @Test
    fun archiveAction_movesRowsBeforeRepositoryProjectionEmits() =
        runTest {
            val ops = mutableListOf<String>()
            val active = row(7, BufferType.QUERY, "alice")
            val remaining = row(8, BufferType.QUERY, "bob")
            val rows = MutableStateFlow(listOf(active, remaining))
            val buffers =
                object : FakeBufferRepository() {
                    override fun observeChatList(): Flow<List<ChatListRow>> = rows

                    override suspend fun setArchived(
                        id: Long,
                        archived: Boolean,
                    ) {
                        ops += "archive:$id:$archived"
                    }
                }
            val vm = vm(buffers, FakeConnectionManager(ops), FakeChannelCloseCoordinator(ops))
            val collection = launch { vm.state.collect {} }
            runCurrent()

            vm.setArchived(listOf(active.bufferId), true)
            runCurrent()

            assertEquals(
                listOf(remaining.bufferId),
                vm.state.value.rows
                    .map(ChatListRow::bufferId),
            )
            assertEquals(
                listOf(active.bufferId),
                vm.state.value.archivedRows
                    .map(ChatListRow::bufferId),
            )
            assertEquals(listOf("archive:${active.bufferId}:true"), ops)

            rows.value = listOf(active.copy(archived = true), remaining)
            runCurrent()

            assertEquals(
                listOf(remaining.bufferId),
                vm.state.value.rows
                    .map(ChatListRow::bufferId),
            )
            assertEquals(
                listOf(active.bufferId),
                vm.state.value.archivedRows
                    .map(ChatListRow::bufferId),
            )
            collection.cancel()
        }

    @Test
    fun unarchiveAction_movesRowsBeforeRepositoryProjectionEmits() =
        runTest {
            val ops = mutableListOf<String>()
            val archived = row(7, BufferType.QUERY, "alice").copy(archived = true)
            val remainingArchived = row(8, BufferType.QUERY, "bob").copy(archived = true)
            val rows = MutableStateFlow(listOf(archived, remainingArchived))
            val buffers =
                object : FakeBufferRepository() {
                    override fun observeChatList(): Flow<List<ChatListRow>> = rows

                    override suspend fun setArchived(
                        id: Long,
                        archived: Boolean,
                    ) {
                        ops += "archive:$id:$archived"
                    }
                }
            val vm = vm(buffers, FakeConnectionManager(ops), FakeChannelCloseCoordinator(ops))
            val collection = launch { vm.state.collect {} }
            runCurrent()

            vm.setArchived(listOf(archived.bufferId), false)
            runCurrent()

            assertEquals(
                listOf(archived.bufferId),
                vm.state.value.rows
                    .map(ChatListRow::bufferId),
            )
            assertEquals(
                listOf(remainingArchived.bufferId),
                vm.state.value.archivedRows
                    .map(ChatListRow::bufferId),
            )
            assertEquals(listOf("archive:${archived.bufferId}:false"), ops)

            rows.value = listOf(archived.copy(archived = false), remainingArchived)
            runCurrent()

            assertEquals(
                listOf(archived.bufferId),
                vm.state.value.rows
                    .map(ChatListRow::bufferId),
            )
            assertEquals(
                listOf(remainingArchived.bufferId),
                vm.state.value.archivedRows
                    .map(ChatListRow::bufferId),
            )
            collection.cancel()
        }
}
