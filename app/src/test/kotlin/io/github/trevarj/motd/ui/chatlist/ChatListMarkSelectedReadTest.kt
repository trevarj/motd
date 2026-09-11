package io.github.trevarj.motd.ui.chatlist

import androidx.lifecycle.SavedStateHandle
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
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
import io.github.trevarj.motd.service.AppVisibility
import io.github.trevarj.motd.service.BufferReadMarker
import io.github.trevarj.motd.service.ChannelCloseCoordinator
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.HistoryResyncController
import io.github.trevarj.motd.service.HistoryResyncState
import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.service.ReadMarkerSnapshotter
import io.github.trevarj.motd.service.unreadBufferIds
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
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
 * markSelectedRead is the explicit per-selection counterpart to markCurrentScopeRead: unlike the
 * mark-all sweep, it must reach a muted buffer, since the user hand-picked it on purpose.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListMarkSelectedReadTest {
    private class FakeBufferRepository(
        private val rows: Flow<List<ChatListRow>>,
    ) : BufferRepository {
        override fun observeChatList(): Flow<List<ChatListRow>> = rows

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

    private class FakeNetworkRepository : NetworkRepository {
        override fun observeNetworks(): Flow<List<NetworkEntity>> = flowOf(emptyList())

        override suspend fun addNetwork(n: NetworkEntity): Long = 0

        override suspend fun updateNetwork(n: NetworkEntity) = Unit

        override suspend fun deleteNetwork(id: Long) = Unit

        override suspend fun reorderNetworks(orderedIds: List<Long>) = Unit

        override suspend fun networkById(id: Long): NetworkEntity? = null

        override suspend fun childrenOf(rootId: Long): List<NetworkEntity> = emptyList()
    }

    private class RecordingConnectionManager : NoopConnectionManager() {
        override val connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(emptyMap())
        val markedRead = mutableListOf<Long>()

        override suspend fun markRead(
            bufferId: Long,
            anchor: TimelineAnchor,
        ) {
            markedRead.add(bufferId)
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

    private class FakeAppVisibility : AppVisibility {
        override val onScreen: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    }

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun readMarker(bufferId: Long) = BufferReadMarker(bufferId, target = "#muted-channel", timestamp = 200, eventId = 9)

    private fun mutedRow(bufferId: Long) =
        ChatListRow(
            bufferId = bufferId,
            networkId = 1,
            networkName = "libera",
            displayName = "#muted-channel",
            type = BufferType.CHANNEL,
            pinned = false,
            muted = true,
            lastMessageText = "quiet",
            lastMessageSender = "bot",
            lastMessageTime = 100,
            unreadCount = 5,
            mentionCount = 0,
        )

    @Test
    fun `markSelectedRead reaches a muted buffer that mark-all would have skipped`() =
        runTest {
            val bufferId = 7L
            val rows = MutableStateFlow(listOf(mutedRow(bufferId)))
            val connectionManager = RecordingConnectionManager()
            val viewModel =
                ChatListViewModel(
                    bufferRepository = FakeBufferRepository(rows),
                    networkRepository = FakeNetworkRepository(),
                    connectionManager = connectionManager,
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
                            override suspend fun latestIncoming(bufferIds: Collection<Long>): List<BufferReadMarker> = bufferIds.map(::readMarker)
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
                    appVisibility = FakeAppVisibility(),
                )

            // Sanity check: mark-all's own selector skips this row because it's muted.
            assertEquals(emptyList<Long>(), unreadBufferIds(rows.value))

            viewModel.markSelectedRead(listOf(bufferId))
            runCurrent()

            assertEquals(listOf(bufferId), connectionManager.markedRead)
        }

    @Test
    fun `markSelectedRead with no ids does nothing`() =
        runTest {
            val connectionManager = RecordingConnectionManager()
            val viewModel =
                ChatListViewModel(
                    bufferRepository = FakeBufferRepository(MutableStateFlow(emptyList())),
                    networkRepository = FakeNetworkRepository(),
                    connectionManager = connectionManager,
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
                    appVisibility = FakeAppVisibility(),
                )

            viewModel.markSelectedRead(emptyList())
            runCurrent()

            assertEquals(emptyList<Long>(), connectionManager.markedRead)
        }
}
