package io.github.trevarj.motd.ui.chatlist

import androidx.lifecycle.SavedStateHandle
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Manual drawer ordering as the user experiences it: when a move is written, what the drawer shows
 * between the write and Room catching up, and what a drag leaves behind when it ends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListReorderTest {
    /** Records every reorder write and only publishes it when the test says Room caught up. */
    private class FakeNetworkRepository(
        initial: List<NetworkEntity>,
    ) : NetworkRepository {
        val networks = MutableStateFlow(initial)
        val writes = mutableListOf<List<Long>>()
        var failWrites = false

        override fun observeNetworks(): Flow<List<NetworkEntity>> = networks

        override suspend fun addNetwork(n: NetworkEntity): Long = 0

        override suspend fun updateNetwork(n: NetworkEntity) = Unit

        override suspend fun deleteNetwork(id: Long) = Unit

        override suspend fun reorderNetworks(orderedIds: List<Long>) {
            if (failWrites) throw IllegalStateException("disk full")
            writes += orderedIds
        }

        override suspend fun networkById(id: Long): NetworkEntity? = null

        override suspend fun childrenOf(rootId: Long): List<NetworkEntity> = emptyList()

        /** Publish the last written order the way Room's invalidation eventually would. */
        fun publishLastWrite() {
            val order = writes.last()
            networks.value = networks.value.sortedBy { order.indexOf(it.id) }
        }
    }

    private class FakeBufferRepository : BufferRepository {
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

        override suspend fun deleteBuffer(id: Long) = Unit
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

    private fun net(
        id: Long,
        name: String,
        role: NetworkRole = NetworkRole.DIRECT,
        parentId: Long? = null,
    ) = NetworkEntity(
        id = id,
        name = name,
        role = role,
        parentId = parentId,
        host = "$name.example",
        port = 6697,
        nick = "me",
        username = "me",
        realname = "Me",
    )

    /** libera, soju(oftc, ergo), hackint — the shape a soju user actually has. */
    private val networks =
        listOf(
            net(1, "libera"),
            net(2, "soju", NetworkRole.BOUNCER_ROOT),
            net(3, "oftc", NetworkRole.BOUNCER_CHILD, parentId = 2),
            net(4, "ergo", NetworkRole.BOUNCER_CHILD, parentId = 2),
            net(5, "hackint"),
        )

    private fun vm(repository: NetworkRepository) =
        ChatListViewModel(
            bufferRepository = FakeBufferRepository(),
            networkRepository = repository,
            connectionManager = FakeConnectionManager(),
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
            dickordLabsPrefs = fakeDickordLabsPrefs(),
            savedStateHandle = SavedStateHandle(),
            appVisibility = AlwaysOnScreen,
        )

    private fun TestScope.collecting(viewModel: ChatListViewModel): Job = launch { viewModel.state.collect {} }.also { runCurrent() }

    private fun order(viewModel: ChatListViewModel) =
        viewModel.state.value.drawerRows
            .map(DrawerRow::networkId)

    @Test
    fun `a move action is persisted at once and shown before Room agrees`() =
        runTest {
            val repository = FakeNetworkRepository(networks)
            val viewModel = vm(repository)
            val collection = collecting(viewModel)

            viewModel.moveNetwork(networkId = 5, delta = -1)
            runCurrent()

            // One finished intent, one write — no debounce to lose if the process dies here.
            assertEquals(listOf(listOf(1L, 5L, 2L, 3L, 4L)), repository.writes)
            // The drawer moves immediately rather than waiting for the round trip through Room.
            assertEquals(listOf(1L, 5L, 2L, 3L, 4L), order(viewModel))

            repository.publishLastWrite()
            runCurrent()

            assertEquals(listOf(1L, 5L, 2L, 3L, 4L), order(viewModel))
            collection.cancel()
        }

    @Test
    fun `a move that cannot happen writes nothing`() =
        runTest {
            val repository = FakeNetworkRepository(networks)
            val viewModel = vm(repository)
            val collection = collecting(viewModel)

            viewModel.moveNetwork(networkId = 1, delta = -1) // already first
            viewModel.moveNetwork(networkId = 5, delta = 1) // already last
            viewModel.moveNetwork(networkId = 3, delta = -1) // first child of its root
            viewModel.moveNetwork(networkId = 77, delta = 1) // not in the drawer
            runCurrent()

            assertEquals(emptyList<List<Long>>(), repository.writes)
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), order(viewModel))
            collection.cancel()
        }

    @Test
    fun `a finished drag writes its arrangement once and shows it before Room agrees`() =
        runTest {
            val repository = FakeNetworkRepository(networks)
            val viewModel = vm(repository)
            val collection = collecting(viewModel)

            // The drag lived in the composable; the ViewModel sees only the arrangement it ended on.
            viewModel.commitNetworkOrder(listOf(5L, 1L, 2L, 3L, 4L))
            runCurrent()

            assertEquals(listOf(listOf(5L, 1L, 2L, 3L, 4L)), repository.writes)
            assertEquals(listOf(5L, 1L, 2L, 3L, 4L), order(viewModel))

            repository.publishLastWrite()
            runCurrent()

            assertEquals(listOf(5L, 1L, 2L, 3L, 4L), order(viewModel))
            collection.cancel()
        }

    @Test
    fun `a drag that ends where it started commits nothing`() =
        runTest {
            val repository = FakeNetworkRepository(networks)
            val viewModel = vm(repository)
            val collection = collecting(viewModel)

            // Picked the row up, wobbled, and dropped it back into place: not an intent to reorder.
            viewModel.commitNetworkOrder(listOf(1L, 2L, 3L, 4L, 5L))
            runCurrent()

            assertEquals(emptyList<List<Long>>(), repository.writes)
            collection.cancel()
        }

    @Test
    fun `the pending order clears even when Room publishes rows that differ from the prediction`() =
        runTest {
            val repository = FakeNetworkRepository(networks)
            val viewModel = vm(repository)
            val collection = collecting(viewModel)

            viewModel.commitNetworkOrder(listOf(5L, 1L, 2L, 3L, 4L))
            runCurrent()

            // hackint is deleted before Room can publish the reorder: the published rows will never
            // equal the id list the write predicted.
            repository.networks.value = repository.networks.value.filterNot { it.id == 5L }
            runCurrent()
            assertEquals(listOf(1L, 2L, 3L, 4L), order(viewModel))

            // The overlay must have settled: a later stored-order change (say, another device) shows
            // through untouched instead of being re-ranked by a stale pending order forever.
            val byId = repository.networks.value.associateBy { it.id }
            repository.networks.value = listOf(byId[2L]!!, byId[3L]!!, byId[4L]!!, byId[1L]!!)
            runCurrent()

            assertEquals(listOf(2L, 3L, 4L, 1L), order(viewModel))
            collection.cancel()
        }

    @Test
    fun `a failed write drops the optimistic order instead of pinning it`() =
        runTest {
            val repository = FakeNetworkRepository(networks)
            repository.failWrites = true
            val viewModel = vm(repository)
            val collection = collecting(viewModel)

            viewModel.commitNetworkOrder(listOf(5L, 1L, 2L, 3L, 4L))
            runCurrent()

            // Nothing was persisted, so the stored order is the truth again.
            assertEquals(emptyList<List<Long>>(), repository.writes)
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), order(viewModel))
            collection.cancel()
        }

    @Test
    fun `a bouncer child reorder keeps the group intact`() =
        runTest {
            val repository = FakeNetworkRepository(networks)
            val viewModel = vm(repository)
            val collection = collecting(viewModel)

            viewModel.moveNetwork(networkId = 4, delta = -1)
            runCurrent()

            assertEquals(listOf(listOf(1L, 2L, 4L, 3L, 5L)), repository.writes)
            assertEquals(listOf(1L, 2L, 4L, 3L, 5L), order(viewModel))
            collection.cancel()
        }

    @Test
    fun `a network added mid-drag joins the committed order at the end`() =
        runTest {
            val repository = FakeNetworkRepository(networks)
            val viewModel = vm(repository)
            val collection = collecting(viewModel)

            // The drag started before "ergo2" existed, so the arrangement it ends on omits id 6. The
            // commit layers that arrangement onto the live rows: the newcomer keeps its place at the
            // end rather than being dropped from the write or stranded away from its siblings.
            repository.networks.value = repository.networks.value + net(6, "ergo2")
            runCurrent()
            viewModel.commitNetworkOrder(listOf(1L, 5L, 2L, 3L, 4L))
            runCurrent()

            assertEquals(listOf(listOf(1L, 5L, 2L, 3L, 4L, 6L)), repository.writes)
            assertEquals(listOf(1L, 5L, 2L, 3L, 4L, 6L), order(viewModel))
            collection.cancel()
        }
}
