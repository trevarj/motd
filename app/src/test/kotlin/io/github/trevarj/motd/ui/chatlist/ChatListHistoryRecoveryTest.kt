package io.github.trevarj.motd.ui.chatlist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.HistorySyncMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.GapFillProgress
import io.github.trevarj.motd.data.sync.HistoryGapFillCoordinator
import io.github.trevarj.motd.data.sync.HistoryGapFiller
import io.github.trevarj.motd.data.sync.HistoryPageLoader
import io.github.trevarj.motd.data.sync.HistoryPruner
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.service.HistoryResyncController
import io.github.trevarj.motd.service.HistoryResyncCoordinator
import io.github.trevarj.motd.service.HistoryResyncState
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatListHistoryRecoveryTest {
    private val fixtures = mutableListOf<Fixture>()

    @After fun tearDown() {
        fixtures.forEach { it.db.close() }
    }

    private suspend fun fixture(
        scope: CoroutineScope,
        seedHistory: Boolean = true,
    ) = Fixture(scope).also {
        fixtures += it
        it.open(seedHistory)
    }

    @Test
    fun newestReconciliationThenGapRecoveryLoadsCanonicalSelectionsWithoutMarkingRead() =
        runTest {
            val fixture = fixture(backgroundScope)
            val alias = fixture.db.bufferDao().insert(fixture.rooms[0].copy(id = 0, name = "#old", redirectToRoomId = fixture.rooms[0].id))
            assertTrue(fixture.rows().all { chatListBadgeState(it).advertisedActivity })

            fixture.recover(listOf(alias, fixture.rooms[0].id, fixture.rooms[1].id))

            assertEquals(
                fixture.rooms.flatMap { listOf("newest:${it.id}", "gaps:${it.id}") },
                fixture.actions,
            )
            fixture.rooms.forEach { room ->
                assertEquals(
                    listOf("LATEST", "BEFORE", "BEFORE", "BEFORE", "BEFORE"),
                    fixture.requests.filter { it.target == room.ircTarget }.map { it.subcommand.name },
                )
                assertEquals(10, fixture.db.messageDao().countForBuffer(room.id))
                assertTrue(
                    fixture.db
                        .historyGapDao()
                        .forRoom(room.id)
                        .isEmpty(),
                )
                val stored = fixture.db.bufferDao().rawById(room.id)!!
                assertEquals(room.readMarkerTime, stored.readMarkerTime)
                assertEquals(room.localReadAnchorTime, stored.localReadAnchorTime)
                assertEquals(room.localReadAnchorEventId, stored.localReadAnchorEventId)
                assertEquals(room.localUnreadFloorTime, stored.localUnreadFloorTime)
                assertEquals(900_000L, stored.advertisedLatestTime)
            }
            fixture.rows().forEach { row ->
                assertFalse(chatListBadgeState(row).advertisedActivity)
                assertFalse(row.unreadCountIncomplete)
                assertEquals(9, row.unreadCount)
            }
            assertEquals(emptyList<Long>(), fixture.markedRead)
        }

    @Test
    fun duplicateOnlyNewestRecoveryClearsUnreachableActivityWithoutMarkingRead() =
        runTest {
            val fixture = fixture(backgroundScope)
            val room = fixture.rooms[0]
            fixture.recover(listOf(room.id))
            val newest = requireNotNull(fixture.db.messageDao().byMsgid(room.id, "${room.ircTarget}:900000"))
            fixture.db.bufferDao().advanceLocalReadAnchor(room.id, newest.serverTime, newest.id)
            val baseline = fixture.db.bufferDao().rawById(room.id)!!
            val recoveredRow = fixture.rows().single { it.bufferId == room.id }
            assertEquals(0, recoveredRow.unreadCount)
            assertFalse(chatListBadgeState(recoveredRow).advertisedActivity)
            assertFalse(recoveredRow.unreadCountIncomplete)
            val newestTime = newest.serverTime
            fixture.db.bufferDao().advanceAdvertisedLatest(room.id, newestTime + 5_000)
            assertTrue(chatListBadgeState(fixture.rows().single { it.bufferId == room.id }).advertisedActivity)
            fixture.requests.clear()

            fixture.recover(listOf(room.id))

            assertEquals(listOf(ChatHistoryRequest.Subcommand.LATEST), fixture.requests.map { it.subcommand })
            assertEquals(HistoryResyncState.UpToDate, fixture.newestResult)
            assertEquals(10, fixture.db.messageDao().countForBuffer(room.id))
            val row = fixture.rows().single { it.bufferId == room.id }
            assertFalse(chatListBadgeState(row).advertisedActivity)
            assertFalse(row.unreadCountIncomplete)
            assertEquals(recoveredRow.unreadCount, row.unreadCount)
            val stored = fixture.db.bufferDao().rawById(room.id)!!
            assertEquals(newestTime, stored.advertisedLatestTime)
            assertEquals(baseline.readMarkerTime, stored.readMarkerTime)
            assertEquals(baseline.localReadAnchorTime, stored.localReadAnchorTime)
            assertEquals(baseline.localReadAnchorEventId, stored.localReadAnchorEventId)
            assertEquals(baseline.localUnreadFloorTime, stored.localUnreadFloorTime)
            assertEquals(emptyList<Long>(), fixture.markedRead)
        }

    @Test
    fun activeRecoveryFollowsRoomRedirectUntilItsOwnBatchCompletes() =
        runTest {
            val fixture = fixture(backgroundScope)
            val store = BufferStore(fixture.db)
            val networkId = fixture.rooms[0].networkId
            val winner = store.getOrCreate(networkId, "alice", "alice", BufferType.QUERY)
            val loser = store.getOrCreate(networkId, "ally", "ally", BufferType.QUERY)
            val recovery = fixture.activityRecovery()
            val releaseWinner = CompletableDeferred<Unit>()
            val releaseLoser = CompletableDeferred<Unit>()
            val losingBatch = requireNotNull(recovery.launch(backgroundScope, listOf(loser.id)) { releaseLoser.await() })
            val winningBatch = requireNotNull(recovery.launch(backgroundScope, listOf(winner.id)) { releaseWinner.await() })
            // Initial activity is synchronous, before either Room observation runs.
            assertEquals(setOf(loser.id, winner.id), recovery.activeIds.value)
            runCurrent()

            store.mergeRooms(winner.id, loser.id)
            runCurrent()

            assertEquals(setOf(winner.id), recovery.activeIds.value)
            assertFalse(losingBatch.isCompleted)
            assertNull(recovery.launch(backgroundScope, listOf(winner.id)) { error("Duplicate recovery") })
            assertNull(recovery.launch(backgroundScope, listOf(loser.id)) { error("Duplicate recovery through a stale id") })

            releaseWinner.complete(Unit)
            winningBatch.join()
            assertEquals(setOf(winner.id), recovery.activeIds.value)

            releaseLoser.complete(Unit)
            losingBatch.join()
            assertEquals(emptySet<Long>(), recovery.activeIds.value)
        }

    @Test
    fun emptyRoomRecoveryKeepsFetchedMessagesUnreadWithoutChangingAutomaticImports() =
        runTest {
            val fixture = fixture(backgroundScope, seedHistory = false)
            val explicitRoom = fixture.rooms[0]
            assertTrue(chatListBadgeState(fixture.rows().single { it.bufferId == explicitRoom.id }).advertisedActivity)

            fixture.recover(listOf(explicitRoom.id))

            val stored = fixture.db.bufferDao().rawById(explicitRoom.id)!!
            assertNull(stored.readMarkerTime)
            assertNull(stored.localReadAnchorTime)
            assertNull(stored.localReadAnchorEventId)
            assertNull(stored.localUnreadFloorTime)
            assertEquals(2, fixture.rows().single { it.bufferId == explicitRoom.id }.unreadCount)
            assertEquals(emptyList<Long>(), fixture.markedRead)

            val automaticRoom = fixture.rooms[1]
            fixture.reconcileAutomatically(automaticRoom)
            assertEquals(
                900_000L,
                fixture.db
                    .bufferDao()
                    .rawById(automaticRoom.id)!!
                    .localUnreadFloorTime,
            )
            assertEquals(0, fixture.rows().single { it.bufferId == automaticRoom.id }.unreadCount)
        }

    @Test
    fun explicitNewestRecoveryDoesNotJoinAnAutomaticPersistenceFlight() =
        runTest {
            val fixture = fixture(backgroundScope, seedHistory = false)
            val room = fixture.rooms[0]
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.onRequest = {
                if (fixture.requests.size == 1) {
                    entered.complete(Unit)
                    release.await()
                    throw IOException("automatic import failed")
                }
            }
            val automatic = async { fixture.reconcileAutomatically(room) }
            entered.await()
            val explicit = async { fixture.recover(listOf(room.id)) }
            runCurrent()
            release.complete(Unit)

            assertTrue(automatic.await() is HistoryResyncState.Failed)
            explicit.await()

            assertEquals(listOf(ChatHistoryRequest.Subcommand.LATEST, ChatHistoryRequest.Subcommand.LATEST), fixture.requests.map { it.subcommand })
            assertEquals(2, fixture.rows().single { it.bufferId == room.id }.unreadCount)
            val stored = fixture.db.bufferDao().rawById(room.id)!!
            assertNull(stored.readMarkerTime)
            assertNull(stored.localReadAnchorTime)
            assertNull(stored.localReadAnchorEventId)
            assertNull(stored.localUnreadFloorTime)
        }

    @Test
    fun timestampOnlyIncompleteNewestPageStillDrainsItsRecoverableGap() =
        runTest {
            val fixture = fixture(backgroundScope)
            val room = fixture.rooms[0]
            fixture.omitMsgids = true
            fixture.availability = HistoryAvailability.Ready(setOf(HistoryReferenceType.TIMESTAMP), 2)

            fixture.recover(listOf(room.id))

            val newest = fixture.newestResult
            assertTrue(newest is HistoryResyncState.Incomplete && newest.inserted == 2)
            assertEquals(
                listOf("LATEST", "BEFORE", "BEFORE", "BEFORE", "BEFORE"),
                fixture.requests.map { it.subcommand.name },
            )
            assertTrue(fixture.requests.drop(1).all { it.bound1?.startsWith("timestamp=") == true })
            assertTrue(
                fixture.db
                    .historyGapDao()
                    .forRoom(room.id)
                    .isEmpty(),
            )
            assertEquals(9, fixture.rows().single { it.bufferId == room.id }.unreadCount)
            val stored = fixture.db.bufferDao().rawById(room.id)!!
            assertEquals(room.localReadAnchorEventId, stored.localReadAnchorEventId)
            assertEquals(room.localReadAnchorTime, stored.localReadAnchorTime)
            assertEquals(room.readMarkerTime, stored.readMarkerTime)
            assertEquals(room.localUnreadFloorTime, stored.localUnreadFloorTime)
        }

    @Test
    fun replacementClientCannotInheritAnOldClientsRecoveryOrRemainingSelections() =
        runTest {
            val fixture = fixture(backgroundScope)
            fixture.afterNewest = { fixture.currentClient = fixture.newClient() }

            fixture.recover(fixture.rooms.map { it.id })

            assertFalse(requireNotNull(fixture.newestIsCurrent).invoke())
            assertEquals(listOf("newest:${fixture.rooms[0].id}"), fixture.actions)
            assertEquals(listOf(ChatHistoryRequest.Subcommand.LATEST), fixture.requests.map { it.subcommand })
            assertTrue(
                fixture.db
                    .historyGapDao()
                    .forRoom(fixture.rooms[0].id)
                    .single()
                    .recoverable,
            )
            assertTrue(chatListBadgeState(fixture.rows().single { it.bufferId == fixture.rooms[1].id }).advertisedActivity)
            assertEquals(emptyList<Long>(), fixture.markedRead)
        }

    @Test
    fun offlineUnsupportedAndFailedNewestRecoveryPreserveActivityAndReadState() =
        runTest {
            for (mode in listOf("offline", "unsupported", "failed")) {
                val fixture = fixture(backgroundScope)
                val room = fixture.rooms[0]
                val gapId =
                    fixture.db.historyGapDao().insert(
                        HistoryGapEntity(
                            roomId = room.id,
                            olderMsgid = "#one:100000",
                            olderServerTime = 100_000,
                            newerMsgid = "#one:800000",
                            newerServerTime = 800_000,
                        ),
                    )
                when (mode) {
                    "offline" -> fixture.currentClient = null
                    "unsupported" -> fixture.availability = HistoryAvailability.Unsupported
                    "failed" -> fixture.failNewest = true
                }

                fixture.recover(listOf(room.id))

                assertTrue(mode, chatListBadgeState(fixture.rows().single { it.bufferId == room.id }).advertisedActivity)
                val stored = fixture.db.bufferDao().rawById(room.id)!!
                assertEquals(room.readMarkerTime, stored.readMarkerTime)
                assertEquals(room.localReadAnchorTime, stored.localReadAnchorTime)
                assertEquals(room.localReadAnchorEventId, stored.localReadAnchorEventId)
                assertEquals(room.localUnreadFloorTime, stored.localUnreadFloorTime)
                assertEquals(room.advertisedLatestTime, stored.advertisedLatestTime)
                assertEquals(1, fixture.db.messageDao().countForBuffer(room.id))
                val remaining =
                    fixture.db
                        .historyGapDao()
                        .forRoom(room.id)
                        .single()
                assertEquals(gapId, remaining.id)
                assertTrue(remaining.recoverable)
                assertTrue(fixture.actions.none { it.startsWith("gaps:") })
                assertEquals(emptyList<Long>(), fixture.markedRead)
            }
        }

    private class Fixture(
        private val scope: CoroutineScope,
    ) {
        val db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MotdDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        private val settings =
            object : SettingsRepository by DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>()) {
                override val settings = MutableStateFlow(Settings(historySyncMode = HistorySyncMode.LAZY))
            }
        private val processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
        private val loader = HistoryPageLoader(processor)
        private var networkId = 0L
        val rooms = mutableListOf<BufferEntity>()
        val markedRead = mutableListOf<Long>()
        val actions = mutableListOf<String>()
        val requests = mutableListOf<ChatHistoryRequest>()
        private val originalClient = newClient()
        var currentClient: IrcClient? = originalClient
        var afterNewest: (() -> Unit)? = null
        var newestIsCurrent: (() -> Boolean)? = null
        var newestResult: HistoryResyncState? = null
        var onRequest: (suspend (ChatHistoryRequest) -> Unit)? = null
        var omitMsgids = false
        var failNewest = false
        var availability: HistoryAvailability = HistoryAvailability.Ready(setOf(HistoryReferenceType.MSGID, HistoryReferenceType.TIMESTAMP), 2)
        private val connections =
            object : NoopConnectionManager() {
                override fun clientFor(networkId: Long): IrcClient? = currentClient.takeIf { networkId == this@Fixture.networkId }

                override suspend fun markRead(
                    bufferId: Long,
                    anchor: TimelineAnchor,
                ) {
                    markedRead += bufferId
                }
            }
        private val buffers =
            object : BufferRepository {
                override suspend fun canonicalBufferId(id: Long) = db.bufferDao().canonicalId(id)

                override fun observeBuffer(id: Long) = db.bufferDao().observe(id)

                override fun observeChatList() = db.bufferDao().observeChatList()

                override fun observeMembers(bufferId: Long) = db.memberDao().observe(bufferId)

                override suspend fun setPinned(
                    id: Long,
                    pinned: Boolean,
                ): Unit = error("Unexpected pin mutation")

                override suspend fun setMuted(
                    id: Long,
                    muted: Boolean,
                ): Nothing = error("Unexpected mute mutation")

                override suspend fun setLayoutDensityOverride(
                    id: Long,
                    layout: LayoutDensity?,
                ): Boolean = error("Unexpected layout mutation")

                override suspend fun setPresenceModeOverride(
                    id: Long,
                    mode: PresenceMode?,
                ): Boolean = error("Unexpected presence mutation")

                override suspend fun setHistorySyncModeOverride(
                    id: Long,
                    mode: HistorySyncMode?,
                ): Boolean = error("Explicit recovery must not change policy")

                override suspend fun deleteBuffer(id: Long): Unit = error("Unexpected deletion")
            }
        private val source =
            object : HistoryResyncCoordinator.HistorySource {
                override suspend fun availability() = this@Fixture.availability

                override fun flightIdentity(): Any = originalClient

                override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                    requests += req
                    onRequest?.invoke(req)
                    val times =
                        when (req.subcommand) {
                            ChatHistoryRequest.Subcommand.LATEST -> {
                                if (failNewest) throw IOException("newest unavailable")
                                listOf(800_000L, 900_000L)
                            }

                            ChatHistoryRequest.Subcommand.BEFORE -> {
                                val bound = requireNotNull(req.bound1)
                                val time = if (bound.startsWith("timestamp=")) Instant.parse(bound.substringAfter('=')).toEpochMilli() else bound.substringAfterLast(':').toLong()
                                when (time) {
                                    800_000L -> listOf(600_000L, 700_000L)
                                    600_000L -> listOf(400_000L, 500_000L)
                                    400_000L -> listOf(200_000L, 300_000L)
                                    200_000L -> listOf(100_000L, 150_000L)
                                    else -> error("Repeated or ungrounded gap boundary: ${req.bound1}")
                                }
                            }

                            else -> {
                                error("Recovery must not perform network-wide discovery")
                            }
                        }
                    return ChatHistoryResponse.Messages(
                        events = times.map { message(req.target, it, if (omitMsgids) null else "${req.target}:$it") },
                        oldest = ChatHistoryReference(if (omitMsgids) null else "${req.target}:${times.first()}", times.first()),
                        newest = ChatHistoryReference(if (omitMsgids) null else "${req.target}:${times.last()}", times.last()),
                        endOfHistory = false,
                        primaryMessageCount = times.size,
                    )
                }
            }
        private val coordinator = HistoryResyncCoordinator(db, processor, scope = scope, loader = loader, settingsRepository = settings)
        private val resync =
            object : HistoryResyncController by coordinator {
                override suspend fun reconcileBuffer(
                    buffer: BufferEntity,
                    client: IrcClient,
                    preserveUnread: Boolean,
                    isCurrent: () -> Boolean,
                ): HistoryResyncState {
                    assertSame(originalClient, client)
                    assertTrue(isCurrent())
                    newestIsCurrent = isCurrent
                    actions += "newest:${buffer.id}"
                    val result =
                        coordinator.reconcileBuffer(
                            buffer.networkId,
                            buffer.id,
                            buffer.ircTarget,
                            source,
                            preserveUnread = preserveUnread,
                            advertisedLatestTime = buffer.advertisedLatestTime,
                            isCurrent = isCurrent,
                        )
                    newestResult = result
                    afterNewest?.invoke()
                    return result
                }
            }
        private val gapCoordinator =
            HistoryGapFillCoordinator(
                connections,
                db.bufferDao(),
                db.messageDao(),
                db.historyCursorDao(),
                db.historyGapDao(),
                loader,
                DiagnosticLogger.Noop,
                settings,
                HistoryPruner.Noop,
            )
        private val gaps =
            object : HistoryGapFiller {
                override val fillsInFlight = gapCoordinator.fillsInFlight

                override suspend fun fillGap(
                    roomId: Long,
                    gapId: Long,
                    automatic: Boolean,
                ): GapFillProgress = error("Explicit recovery must drain the room")

                override suspend fun drainGaps(
                    roomId: Long,
                    client: IrcClient,
                    isCurrent: () -> Boolean,
                ): GapFillProgress {
                    assertSame(originalClient, client)
                    assertTrue(isCurrent())
                    assertEquals("newest:$roomId", actions.last())
                    actions += "gaps:$roomId"
                    return gapCoordinator.drainGaps(roomId, source, isCurrent)
                }
            }

        fun newClient() =
            IrcClient(
                config = IrcClientConfig("irc.example", 6697, true, "me", "me", "Me"),
                factory = TransportFactory { _, _, _, _, _ -> error("Scripted history owns the wire") },
                scope = scope,
            )

        suspend fun open(seedHistory: Boolean) {
            networkId = db.networkDao().insert(NetworkEntity(name = "network", role = NetworkRole.DIRECT, host = "h", port = 6697, nick = "me", username = "me", realname = "Me"))
            processor.onRegistered(networkId, "me", emptyMap())
            for (target in listOf("#one", "#two")) {
                val room =
                    BufferEntity(
                        networkId = networkId,
                        name = target,
                        displayName = target,
                        type = BufferType.CHANNEL,
                        readMarkerTime = 100_000L.takeIf { seedHistory },
                    )
                val id = db.bufferDao().insert(room)
                if (seedHistory) {
                    processor.process(networkId, message(target, 100_000))
                    val event = requireNotNull(db.messageDao().byMsgid(id, "$target:100000"))
                    db.bufferDao().advanceLocalReadAnchor(id, 100_000, event.id)
                }
                db.bufferDao().advanceAdvertisedLatest(id, 900_000)
                rooms += db.bufferDao().rawById(id)!!
            }
        }

        suspend fun rows() = db.bufferDao().observeChatList().first()

        fun activityRecovery() = ChatListActivityRecovery(buffers)

        suspend fun recover(ids: Collection<Long>) = recoverChatListActivity(ids, buffers, connections, resync, gaps)

        suspend fun reconcileAutomatically(room: BufferEntity) = coordinator.reconcileBuffer(room.networkId, room.id, room.ircTarget, source)

        private fun message(
            target: String,
            time: Long,
            msgid: String? = "$target:$time",
        ) = IrcEvent.ChatMessage(
            ctx = MessageContext(msgid, time, null, "batch", null),
            kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"),
            target = target,
            text = "message $time",
            isSelf = false,
            replyToMsgid = null,
        )
    }
}
