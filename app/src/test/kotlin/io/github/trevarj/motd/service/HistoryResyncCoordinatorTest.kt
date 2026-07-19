package io.github.trevarj.motd.service

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.HistorySyncPrefs
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.CanonicalHistorySingleFlight
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.ChatHistoryTarget
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.client.IrcProtocolException
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import io.github.trevarj.motd.irc.proto.Prefix
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryResyncCoordinatorTest {
    private lateinit var db: MotdDatabase
    private lateinit var processor: EventProcessor
    private lateinit var coordinator: HistoryResyncCoordinator
    private var networkId = 0L
    private var bufferId = 0L
    private val syncPrefs = object : HistorySyncPrefs {
        private val values = mutableMapOf<Long, Long>()
        override suspend fun lastSuccessfulSync(networkId: Long): Long? = values[networkId]
        override suspend fun setLastSuccessfulSync(networkId: Long, timestamp: Long) {
            values[networkId] = timestamp
        }
        override suspend fun clear(networkId: Long) { values.remove(networkId) }
    }

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
        coordinator = HistoryResyncCoordinator(
            db,
            processor,
            syncPrefs,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        networkId = db.networkDao().insert(
            NetworkEntity(
                name = "libera",
                role = NetworkRole.DIRECT,
                host = "h",
                port = 6697,
                nick = "me",
                username = "me",
                realname = "Me",
            ),
        )
        bufferId = db.bufferDao().insert(
            BufferEntity(
                networkId = networkId,
                name = "#chan",
                displayName = "#chan",
                type = BufferType.CHANNEL,
                readMarkerTime = 75,
            ),
        )
        processor.onRegistered(networkId, "me", emptyMap())
    }

    @After
    fun tearDown() = db.close()

    private fun message(msgid: String, time: Long, target: String = "#chan") = IrcEvent.ChatMessage(
        ctx = MessageContext(msgid, time, null, "batch", null),
        kind = IrcEvent.ChatKind.PRIVMSG,
        source = Prefix("alice"),
        target = target,
        text = msgid,
        isSelf = false,
        replyToMsgid = null,
    )

    private suspend fun rows(): List<MessageEntity> {
        val loaded = db.messageDao().pagingSource(bufferId).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 500, placeholdersEnabled = false),
        ) as PagingSource.LoadResult.Page
        return loaded.data
    }

    private data class FakeResponse(
        val events: List<IrcEvent> = emptyList(),
        val targets: List<Pair<String, Long>> = emptyList(),
        val endOfHistory: Boolean = false,
        val oldest: ChatHistoryReference? = events.references().minByOrNull { it.serverTime ?: Long.MAX_VALUE },
        val newest: ChatHistoryReference? = events.references().maxByOrNull { it.serverTime ?: Long.MIN_VALUE },
        val primaryMessageCount: Int = events.size,
    )

    private companion object {
        fun List<IrcEvent>.references(): List<ChatHistoryReference> = mapNotNull { event ->
            val context = when (event) {
                is IrcEvent.ChatMessage -> event.ctx
                is IrcEvent.TagMessage -> event.ctx
                is IrcEvent.Joined -> event.ctx
                is IrcEvent.Parted -> event.ctx
                is IrcEvent.Quit -> event.ctx
                is IrcEvent.Kicked -> event.ctx
                is IrcEvent.NickChanged -> event.ctx
                is IrcEvent.TopicChanged -> event.ctx
                is IrcEvent.ModeChanged -> event.ctx
                is IrcEvent.Invited -> event.ctx
                else -> null
            }
            context?.let { ChatHistoryReference(it.msgid, it.serverTime) }
        }
    }

    private class FakeSource(
        var supported: Boolean = true,
        var msgidRefs: Boolean = true,
        var pageLimit: Int = 100,
        val responder: suspend (ChatHistoryRequest) -> FakeResponse,
    ) : HistoryResyncCoordinator.HistorySource {
        val requests = mutableListOf<ChatHistoryRequest>()
        override fun availability(): HistoryAvailability = if (supported) {
            HistoryAvailability.Ready(
                buildSet {
                    add(HistoryReferenceType.TIMESTAMP)
                    if (msgidRefs) add(HistoryReferenceType.MSGID)
                },
                pageLimit,
            )
        } else {
            HistoryAvailability.Unsupported
        }
        override suspend fun chathistory(request: ChatHistoryRequest): ChatHistoryResponse {
            requests += request
            val response = responder(request)
            return if (request.subcommand == ChatHistoryRequest.Subcommand.TARGETS) {
                ChatHistoryResponse.Targets(
                    response.targets.map { (name, time) -> ChatHistoryTarget(name, time) },
                    response.endOfHistory,
                )
            } else {
                ChatHistoryResponse.Messages(
                    events = response.events,
                    oldest = response.oldest,
                    newest = response.newest,
                    primaryMessageCount = response.primaryMessageCount,
                    endOfHistory = response.endOfHistory,
                )
            }
        }
    }

    @Test
    fun msgidAfterThenLatestOverlap_recoversMissingTail_andIsIdempotent() = runTest {
        processor.process(networkId, message("seed", 100))
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.AFTER -> FakeResponse(
                    listOf(message("tail", 200)),
                    endOfHistory = true,
                )
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    listOf(message("seed", 100), message("tail", 200)),
                    endOfHistory = true,
                )
                else -> FakeResponse(emptyList(), emptyList())
            }
        }

        val first = coordinator.resyncBuffer(networkId, bufferId, "#chan", source)
        val second = coordinator.resyncBuffer(networkId, bufferId, "#chan", source)

        assertEquals(HistoryResyncState.Updated(1), first)
        assertEquals(HistoryResyncState.UpToDate, second)
        assertEquals(2, rows().size)
        assertEquals("msgid=seed", source.requests.first().bound1)
        assertEquals(75L, db.bufferDao().byName(networkId, "#chan")?.readMarkerTime)
    }

    @Test
    fun timestampBoundaryIsUsedWhenMsgidReferencesAreUnavailable() = runTest {
        processor.process(networkId, message("seed", 1_000))
        val source = FakeSource(msgidRefs = false) { FakeResponse(emptyList(), emptyList()) }

        coordinator.resyncBuffer(networkId, bufferId, "#chan", source)

        assertEquals("timestamp=1970-01-01T00:00:01.000Z", source.requests.first().bound1)
    }

    @Test
    fun timestampBoundaryRetainsMillisecondsWithExactlyThreeFractionalDigits() = runTest {
        processor.process(networkId, message("seed", 1_234))
        val source = FakeSource(msgidRefs = false) { FakeResponse(emptyList(), emptyList()) }

        coordinator.resyncBuffer(networkId, bufferId, "#chan", source)

        assertEquals("timestamp=1970-01-01T00:00:01.234Z", source.requests.first().bound1)
    }

    @Test
    fun emptyBoundaryUsesBoundedLatest() = runTest {
        val source = FakeSource { request ->
            FakeResponse(
                events = if (request.subcommand == ChatHistoryRequest.Subcommand.LATEST) {
                    listOf(message("latest", 200))
                } else emptyList(),
                targets = emptyList(),
            )
        }

        assertEquals(
            HistoryResyncState.Updated(1),
            coordinator.resyncBuffer(networkId, bufferId, "#chan", source),
        )
        assertEquals(listOf(ChatHistoryRequest.Subcommand.LATEST), source.requests.map { it.subcommand })
    }

    @Test
    fun transientNewDmPush_isIncludedInReconnectHistoryCatchup() = runTest {
        val target = "new-dm-history-fixture"
        val push = IrcEvent.ChatMessage(
            ctx = MessageContext(null, 400, null, null, null),
            kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix(target),
            target = "me",
            text = "transient notification",
            isSelf = false,
            replyToMsgid = null,
        )
        processor.processPush(networkId, push)
        val dmBuffer = requireNotNull(db.bufferDao().byName(networkId, target))
        assertEquals(1, db.messageDao().countForBuffer(dmBuffer.id))

        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf(target to 400L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = if (request.target == target) {
                        listOf(
                            push.copy(
                                ctx = push.ctx.copy(msgid = "durable-new-dm", batchId = "history"),
                            ),
                        )
                    } else {
                        emptyList()
                    },
                    targets = emptyList(),
                )
                else -> FakeResponse(emptyList(), emptyList())
            }
        }

        val result = coordinator.resyncNetwork(
            networkId,
            db.bufferDao().openTargets(networkId).map { it.id to it.name },
            source,
        )

        // History enriches the already-persisted push row in place, so the canonical row count is
        // unchanged even though its durable msgid is attached.
        assertEquals(HistoryResyncState.UpToDate, result)
        assertEquals(1, db.messageDao().countForBuffer(dmBuffer.id))
        assertTrue(db.messageDao().byMsgid(dmBuffer.id, "durable-new-dm") != null)
        assertTrue(
            source.requests.any {
                it.subcommand == ChatHistoryRequest.Subcommand.LATEST && it.target == target
            },
        )
    }

    @Test
    fun ioFailureDuringAfterDoesNotFallBackToLatest() = runTest {
        processor.process(networkId, message("seed", 100))
        val source = FakeSource { request ->
            if (request.subcommand == ChatHistoryRequest.Subcommand.AFTER) throw IOException("bad selector")
            FakeResponse(listOf(message("recovered", 150)), endOfHistory = true)
        }

        assertTrue(coordinator.resyncBuffer(networkId, bufferId, "#chan", source) is HistoryResyncState.Failed)
        assertEquals(
            listOf(ChatHistoryRequest.Subcommand.AFTER),
            source.requests.map { it.subcommand },
        )
    }

    @Test
    fun recentLatestOverlapRepairsSilentGapBehindNewestBoundary() = runTest {
        processor.process(networkId, message("old", 100))
        processor.process(networkId, message("newest", 300))
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.AFTER -> FakeResponse(emptyList(), emptyList())
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    listOf(message("old", 100), message("gap", 200), message("newest", 300)),
                    emptyList(),
                )
                else -> FakeResponse(emptyList(), emptyList())
            }
        }

        assertEquals(
            HistoryResyncState.Updated(1),
            coordinator.resyncBuffer(networkId, bufferId, "#chan", source),
        )
        assertEquals(listOf("newest", "gap", "old"), rows().mapNotNull { it.msgid })
    }

    @Test
    fun pendingMessageReconciliationBypassesNetworkWideHistoryGate() = runTest {
        val pendingId = processor.insertPending(
            bufferId = bufferId,
            label = "local-pending",
            sender = "me",
            text = "react-now",
            replyToMsgid = null,
            kind = MessageKind.PRIVMSG,
        )
        val lockHeld = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        val holder = backgroundScope.launch {
            CanonicalHistorySingleFlight.withNetwork(networkId) {
                lockHeld.complete(Unit)
                releaseLock.await()
            }
        }
        lockHeld.await()
        try {
            val source = FakeSource { request ->
                assertEquals(ChatHistoryRequest.Subcommand.LATEST, request.subcommand)
                FakeResponse(
                    events = listOf(
                        IrcEvent.ChatMessage(
                            ctx = MessageContext(
                                msgid = "durable-react-target",
                                serverTime = System.currentTimeMillis(),
                                account = "me",
                                batchId = "history",
                                label = null,
                            ),
                            kind = IrcEvent.ChatKind.PRIVMSG,
                            source = Prefix("me"),
                            target = "#chan",
                            text = "react-now",
                            isSelf = true,
                            replyToMsgid = null,
                        ),
                    ),
                    targets = emptyList(),
                )
            }

            assertEquals(
                HistoryResyncState.UpToDate,
                coordinator.reconcilePendingMessage(networkId, bufferId, "#chan", source),
            )
            assertEquals("durable-react-target", db.messageDao().byCanonicalId(pendingId)?.msgid)
            assertEquals(
                "durable-react-target",
                db.historyCursorDao().byRoom(bufferId)?.newestMsgid,
            )
        } finally {
            releaseLock.complete(Unit)
            holder.join()
        }
    }

    @Test
    fun missingRefreshGreedilyRepairsSparseGapAcrossNewestPage() = runTest {
        processor.process(networkId, message("m1", 1))
        processor.process(networkId, message("m202", 202))
        val source = FakeSource(pageLimit = 100) { request ->
            val events = when (request.subcommand) {
                ChatHistoryRequest.Subcommand.AFTER -> emptyList()
                ChatHistoryRequest.Subcommand.LATEST -> (103L..202L).map { message("m$it", it) }
                ChatHistoryRequest.Subcommand.BETWEEN -> when (request.bound1) {
                    "msgid=m103" -> (3L..102L).map { message("m$it", it) }
                    "msgid=m3" -> (1L..2L).map { message("m$it", it) }
                    else -> emptyList()
                }
                else -> emptyList()
            }
            FakeResponse(
                events,
                endOfHistory = request.subcommand == ChatHistoryRequest.Subcommand.BETWEEN &&
                    request.bound1 == "msgid=m3",
            )
        }

        assertEquals(
            HistoryResyncState.Updated(200),
            coordinator.resyncBuffer(networkId, bufferId, "#chan", source),
        )
        val msgids = rows().mapNotNull { it.msgid }.toSet()
        assertTrue((2L..102L).all { "m$it" in msgids })
        assertEquals(2, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.BETWEEN })
    }

    @Test
    fun reopeningVisibleBufferDoesNotWalkBackwardIntoOldSparseHistory() = runTest {
        processor.process(networkId, message("m1", 1))
        (103L..202L).forEach { processor.process(networkId, message("m$it", it)) }
        val source = FakeSource(pageLimit = 100) { request ->
            val events = when (request.subcommand) {
                ChatHistoryRequest.Subcommand.AFTER -> emptyList()
                ChatHistoryRequest.Subcommand.LATEST -> (103L..202L).map { message("m$it", it) }
                ChatHistoryRequest.Subcommand.BETWEEN -> (3L..102L).map { message("m$it", it) }
                else -> emptyList()
            }
            FakeResponse(events, emptyList())
        }

        assertEquals(
            HistoryResyncState.UpToDate,
            coordinator.reconcileBuffer(networkId, bufferId, "#chan", source),
        )
        assertEquals(
            listOf(
                ChatHistoryRequest.Subcommand.AFTER,
                ChatHistoryRequest.Subcommand.LATEST,
            ),
            source.requests.map { it.subcommand },
        )
        assertTrue(rows().none { it.msgid == "m102" })
    }

    @Test
    fun automaticNetworkResyncDiscoversTargetsAndCreatesMissingBuffer() = runTest {
        processor.process(networkId, message("seed", 100))
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("#new" to 500L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    if (request.target == "#new") listOf(message("found", 500, "#new")) else emptyList(),
                    emptyList(),
                )
                else -> FakeResponse(emptyList(), emptyList())
            }
        }

        val result = coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source)

        assertEquals(HistoryResyncState.Updated(1), result)
        assertTrue(db.bufferDao().byName(networkId, "#new") != null)
        assertTrue(source.requests.any { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS })
    }

    @Test
    fun freshNetworkDiscoversRetainedTargetsFromEpochAndStoresCursor() = runTest {
        db.bufferDao().deleteBuffer(bufferId)
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("#old" to 500L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    if (request.target == "#old") listOf(message("retained", 500, "#old")) else emptyList(),
                    emptyList(),
                )
                else -> FakeResponse(emptyList(), emptyList())
            }
        }

        val result = coordinator.resyncNetwork(networkId, emptyList(), source)

        assertEquals(HistoryResyncState.Updated(1), result)
        val targets = source.requests.first { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS }
        assertEquals("timestamp=1970-01-01T00:00:00.000Z", targets.bound2)
        assertTrue(targets.bound1!!.matches(Regex("timestamp=.*\\.\\d{3}Z")))
        assertTrue(db.bufferDao().byName(networkId, "#old") != null)
        assertEquals(500L, syncPrefs.lastSuccessfulSync(networkId))
    }

    @Test
    fun freshNetworkPagesTargetsToExhaustionBeforeStoringCursor() = runTest {
        db.bufferDao().deleteBuffer(bufferId)
        val secondPageUpper = ChatHistorySelectors.timestamp(201)
        val source = FakeSource(pageLimit = 2) { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS -> FakeResponse(
                    emptyList(),
                    if (request.bound1 == secondPageUpper) {
                        listOf("#middle" to 200L, "#oldest" to 100L)
                    } else {
                        listOf("#newest" to 300L, "#middle" to 200L)
                    },
                    endOfHistory = request.bound1 == secondPageUpper,
                )
                ChatHistoryRequest.Subcommand.LATEST -> {
                    // Discovery must finish before any per-room sync can make the pass successful.
                    assertEquals(null, syncPrefs.lastSuccessfulSync(networkId))
                    val time = when (request.target) {
                        "#newest" -> 300L
                        "#middle" -> 200L
                        "#oldest" -> 100L
                        else -> error("unexpected target ${request.target}")
                    }
                    FakeResponse(
                        listOf(message("retained-${request.target}", time, request.target)),
                        emptyList(),
                    )
                }
                else -> FakeResponse(emptyList(), emptyList())
            }
        }

        val result = coordinator.resyncNetwork(networkId, emptyList(), source)

        assertEquals(HistoryResyncState.Updated(3), result)
        assertEquals(
            2,
            source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS },
        )
        assertEquals(
            setOf("#newest", "#middle", "#oldest"),
            source.requests.filter { it.subcommand == ChatHistoryRequest.Subcommand.LATEST }
                .map { it.target }
                .toSet(),
        )
        assertTrue(db.bufferDao().byName(networkId, "#newest") != null)
        assertTrue(db.bufferDao().byName(networkId, "#middle") != null)
        assertTrue(db.bufferDao().byName(networkId, "#oldest") != null)
        assertEquals(300L, syncPrefs.lastSuccessfulSync(networkId))
    }

    @Test
    fun networkSyncSeedsLatestWhenLiveJoinPrecedesRetainedHistoryEvenWithPriorCursor() = runTest {
        // A soju child sends the live self-JOIN as it binds. Without an initial LATEST overlap,
        // that new row becomes the AFTER cursor and hides every older retained channel message.
        // Keep a prior cursor to cover an upgrade from the behavior that already marked this
        // bouncer network successfully synced.
        syncPrefs.setLastSuccessfulSync(networkId, 2_000)
        processor.process(
            networkId,
            IrcEvent.Joined(
                ctx = MessageContext(null, 1_000, null, null, null),
                nick = "me",
                channel = "#chan",
                account = null,
                realname = null,
                isSelf = true,
            ),
        )
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("#chan" to 1_000L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.AFTER -> FakeResponse(emptyList(), emptyList())
                ChatHistoryRequest.Subcommand.LATEST ->
                    FakeResponse(listOf(message("retained", 500)), emptyList())
                else -> FakeResponse(emptyList(), emptyList())
            }
        }

        val result = coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source)

        assertEquals(HistoryResyncState.Updated(1), result)
        assertEquals(
            listOf(
                ChatHistoryRequest.Subcommand.TARGETS,
                ChatHistoryRequest.Subcommand.AFTER,
                ChatHistoryRequest.Subcommand.LATEST,
            ),
            source.requests.map { it.subcommand },
        )
        assertTrue(rows().any { it.msgid == "retained" })
    }

    @Test
    fun reconnectUsesLastCompletedSyncSoEarlyLiveMessageCannotHideGapOrDuplicate() = runTest {
        val base = 1_700_000_000_000L
        processor.process(networkId, message("seed", base + 100))
        syncPrefs.setLastSuccessfulSync(networkId, base + 150)

        // A live line can beat the reconnect catch-up coroutine into Room.
        processor.process(networkId, message("live", base + 300))
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("#chan" to (base + 300)), endOfHistory = true)
                ChatHistoryRequest.Subcommand.AFTER -> FakeResponse(
                    events = if (request.bound1?.startsWith("timestamp=") == true) {
                        // Soju may overlap the already-delivered live line. Room must insert only
                        // the missed line and retain one copy of the live line by msgid.
                        listOf(message("missed", base + 200), message("live", base + 300))
                    } else {
                        emptyList()
                    },
                    targets = emptyList(),
                    endOfHistory = true,
                )
                else -> FakeResponse(emptyList(), emptyList())
            }
        }

        val result = coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source)

        assertEquals(HistoryResyncState.Updated(1), result)
        assertEquals(listOf("live", "missed", "seed"), rows().mapNotNull { it.msgid })
        assertEquals(1, rows().count { it.msgid == "live" })
        assertTrue(
            source.requests.first { it.subcommand == ChatHistoryRequest.Subcommand.AFTER }
                .bound1.orEmpty().startsWith("timestamp="),
        )
    }

    @Test
    fun timeRangeRefreshUsesAfterFromTheSelectedCutoff() = runTest {
        val now = System.currentTimeMillis()
        val source = FakeSource { request ->
            assertEquals(ChatHistoryRequest.Subcommand.AFTER, request.subcommand)
            FakeResponse(listOf(message("recent", now - 1_000)), endOfHistory = true)
        }

        val result = coordinator.resyncBuffer(
            networkId,
            bufferId,
            "#chan",
            source,
            range = HistoryRefreshRange.HOURS_24,
        )

        assertEquals(HistoryResyncState.Updated(1), result)
        assertTrue(source.requests.single().bound1!!.startsWith("timestamp="))
        assertEquals("recent", rows().single().msgid)
    }

    @Test
    fun allAvailableStartsNewestThenPagesBackwardWithinTheCap() = runTest {
        var page = 0
        val source = FakeSource(pageLimit = 2) { request ->
            val response = when (page++) {
                0 -> {
                    assertEquals(ChatHistoryRequest.Subcommand.LATEST, request.subcommand)
                    listOf(message("m5", 5), message("m4", 4))
                }
                1 -> {
                    assertEquals(ChatHistoryRequest.Subcommand.BEFORE, request.subcommand)
                    assertEquals("msgid=m4", request.bound1)
                    listOf(message("m3", 3), message("m2", 2))
                }
                else -> {
                    assertEquals(ChatHistoryRequest.Subcommand.BEFORE, request.subcommand)
                    assertEquals("msgid=m2", request.bound1)
                    listOf(message("m1", 1))
                }
            }
            FakeResponse(response, endOfHistory = page >= 3)
        }

        val result = coordinator.resyncBuffer(
            networkId,
            bufferId,
            "#chan",
            source,
            range = HistoryRefreshRange.ALL_AVAILABLE,
        )

        assertEquals(HistoryResyncState.Updated(5), result)
        assertEquals(listOf("m5", "m4", "m3", "m2", "m1"), rows().mapNotNull { it.msgid })
        assertEquals(3, source.requests.size)
    }

    @Test
    fun unsupportedAndTimeoutAreExplicitRetryableResults() = runTest {
        val unsupported = FakeSource(supported = false) { FakeResponse(emptyList(), emptyList()) }
        assertEquals(
            HistoryResyncState.Unsupported,
            coordinator.resyncBuffer(networkId, bufferId, "#chan", unsupported),
        )
        assertTrue(unsupported.requests.isEmpty())

        processor.process(networkId, message("seed", 100))
        coordinator.requestTimeoutMs = 5
        val slow = FakeSource {
            delay(100)
            FakeResponse(emptyList(), emptyList())
        }
        val result = coordinator.resyncBuffer(networkId, bufferId, "#chan", slow)
        assertTrue(result is HistoryResyncState.Failed)
        assertEquals(listOf(ChatHistoryRequest.Subcommand.AFTER), slow.requests.map { it.subcommand })
        assertEquals(listOf("seed"), rows().mapNotNull { it.msgid })
    }

    @Test
    fun staleGenerationDiscardsReturnedPageBeforeRoomWrite() = runTest {
        var current = true
        val source = FakeSource {
            current = false
            FakeResponse(listOf(message("stale", 200)), emptyList())
        }

        val result = coordinator.resyncBuffer(
            networkId,
            bufferId,
            "#chan",
            source,
            isCurrent = { current },
        )

        assertTrue(result is HistoryResyncState.Failed)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun concurrentEquivalentRequestsShareOneFlight() = runTest {
        var calls = 0
        val source = FakeSource {
            calls++
            delay(20)
            FakeResponse(emptyList(), emptyList())
        }

        val results = coroutineScope {
            listOf(
                async { coordinator.resyncBuffer(networkId, bufferId, "#chan", source) },
                async { coordinator.resyncBuffer(networkId, bufferId, "#chan", source) },
            ).awaitAll()
        }

        assertEquals(listOf(HistoryResyncState.UpToDate, HistoryResyncState.UpToDate), results)
        assertEquals(1, calls)
    }

    @Test
    fun fullAfterPageContinuesUntilShortPageWithoutDuplicates() = runTest {
        processor.process(networkId, message("seed", 1))
        val full = (2L..101L).map { message("m$it", it) }
        var afterPage = 0
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.AFTER -> {
                    afterPage++
                    FakeResponse(
                        if (afterPage == 1) full else listOf(message("last", 102)),
                        endOfHistory = afterPage > 1,
                    )
                }
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(listOf(message("last", 102)), emptyList())
                else -> FakeResponse(emptyList(), emptyList())
            }
        }

        assertEquals(
            HistoryResyncState.Updated(101),
            coordinator.resyncBuffer(networkId, bufferId, "#chan", source),
        )
        assertEquals(102, rows().size)
        assertEquals(2, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.AFTER })
        assertTrue(source.requests.any { it.subcommand == ChatHistoryRequest.Subcommand.LATEST })
    }

    @Test
    fun shortNonTerminalAfterPageContinuesFromResponseNewest() = runTest {
        var afterPage = 0
        val source = FakeSource(pageLimit = 10) { request ->
            assertEquals(ChatHistoryRequest.Subcommand.AFTER, request.subcommand)
            when (afterPage++) {
                0 -> FakeResponse(listOf(message("first", 100)))
                else -> {
                    assertEquals("msgid=first", request.bound1)
                    FakeResponse(listOf(message("second", 200)), endOfHistory = true)
                }
            }
        }

        val result = coordinator.resyncBuffer(
            networkId,
            bufferId,
            "#chan",
            source,
            range = HistoryRefreshRange.HOURS_24,
        )

        assertEquals(HistoryResyncState.Updated(2), result)
        assertEquals(2, source.requests.size)
    }

    @Test
    fun liveInsertionCannotMoveAfterCursorPastResponseBoundary() = runTest {
        processor.process(networkId, message("seed", 100))
        var afterPage = 0
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.AFTER -> when (afterPage++) {
                    0 -> {
                        processor.process(networkId, message("live", 1_000))
                        FakeResponse(listOf(message("page-boundary", 200)))
                    }
                    else -> {
                        assertEquals("msgid=page-boundary", request.bound1)
                        FakeResponse(listOf(message("missed", 300)), endOfHistory = true)
                    }
                }
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(endOfHistory = true)
                else -> FakeResponse(endOfHistory = true)
            }
        }

        assertEquals(
            HistoryResyncState.Updated(3),
            coordinator.resyncBuffer(networkId, bufferId, "#chan", source),
        )
        assertEquals(
            listOf("msgid=seed", "msgid=page-boundary"),
            source.requests.filter { it.subcommand == ChatHistoryRequest.Subcommand.AFTER }
                .map { it.bound1 },
        )
    }

    @Test
    fun responseBoundaryExcludesNewerContextEvent() = runTest {
        var afterPage = 0
        val primary = message("primary", 100)
        val context = message("context", 999)
        val source = FakeSource { request ->
            when (afterPage++) {
                0 -> FakeResponse(
                    events = listOf(primary, context),
                    oldest = ChatHistoryReference("primary", 100),
                    newest = ChatHistoryReference("primary", 100),
                    primaryMessageCount = 1,
                )
                else -> {
                    assertEquals("msgid=primary", request.bound1)
                    FakeResponse(endOfHistory = true)
                }
            }
        }

        assertEquals(
            HistoryResyncState.Updated(2),
            coordinator.resyncBuffer(
                networkId,
                bufferId,
                "#chan",
                source,
                range = HistoryRefreshRange.HOURS_24,
            ),
        )
        assertEquals(2, source.requests.size)
        val cursor = requireNotNull(db.historyCursorDao().byRoom(bufferId))
        assertEquals("primary", cursor.newestMsgid)
        assertEquals(100L, cursor.newestServerTime)
    }

    @Test
    fun opaqueMsgidBoundaryAdvancesWithoutAuthoritativeTimestamp() = runTest {
        val opaque = "CaseSensitive/opaque=="
        var afterPage = 0
        val source = FakeSource { request ->
            when (afterPage++) {
                0 -> FakeResponse(
                    events = listOf(message(opaque, 100)),
                    oldest = ChatHistoryReference(opaque, null),
                    newest = ChatHistoryReference(opaque, null),
                )
                else -> {
                    assertEquals("msgid=$opaque", request.bound1)
                    FakeResponse(endOfHistory = true)
                }
            }
        }

        assertEquals(
            HistoryResyncState.Updated(1),
            coordinator.resyncBuffer(
                networkId,
                bufferId,
                "#chan",
                source,
                range = HistoryRefreshRange.HOURS_24,
            ),
        )
        assertEquals("msgid=$opaque", source.requests[1].bound1)
    }

    @Test
    fun completedContextOnlyBatchStopsWithoutBeingIncomplete() = runTest {
        val source = FakeSource {
            FakeResponse(
                events = listOf(message("context-only", 100)),
                oldest = null,
                newest = null,
                primaryMessageCount = 0,
            )
        }

        assertEquals(
            HistoryResyncState.Updated(1),
            coordinator.resyncBuffer(
                networkId,
                bufferId,
                "#chan",
                source,
                range = HistoryRefreshRange.HOURS_24,
            ),
        )
        assertEquals(1, source.requests.size)
        val cursor = requireNotNull(db.historyCursorDao().byRoom(bufferId))
        assertEquals(null, cursor.newestMsgid)
        assertEquals(null, cursor.newestServerTime)
    }

    @Test
    fun primaryCountPreventsEmptyEventsFromMasqueradingAsCompletedPage() = runTest {
        val source = FakeSource {
            FakeResponse(
                events = emptyList(),
                oldest = ChatHistoryReference("primary", 100),
                newest = null,
                primaryMessageCount = 1,
            )
        }

        val result = coordinator.resyncBuffer(
            networkId,
            bufferId,
            "#chan",
            source,
            range = HistoryRefreshRange.HOURS_24,
        )

        assertTrue(result is HistoryResyncState.Incomplete)
        assertEquals(0, (result as HistoryResyncState.Incomplete).inserted)
    }

    @Test
    fun secondPassAfterUsesPrimaryCursorInsteadOfNewerContextRow() = runTest {
        var latestPage = 0
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.LATEST -> if (latestPage++ == 0) {
                    FakeResponse(
                        events = listOf(
                            message("primary", 100),
                            message("newer-context", 10_000),
                        ),
                        oldest = ChatHistoryReference("primary", 100),
                        newest = ChatHistoryReference("primary", 100),
                        primaryMessageCount = 1,
                    )
                } else {
                    FakeResponse(primaryMessageCount = 0)
                }
                ChatHistoryRequest.Subcommand.AFTER -> {
                    assertEquals("msgid=primary", request.bound1)
                    FakeResponse(primaryMessageCount = 0)
                }
                else -> error("unexpected ${request.subcommand}")
            }
        }

        assertEquals(
            HistoryResyncState.Updated(2),
            coordinator.resyncBuffer(networkId, bufferId, "#chan", source),
        )
        assertEquals(
            HistoryResyncState.UpToDate,
            coordinator.resyncBuffer(networkId, bufferId, "#chan", source),
        )

        assertEquals(ChatHistoryRequest.Subcommand.AFTER, source.requests[1].subcommand)
        assertEquals("msgid=primary", source.requests[1].bound1)
        val cursor = requireNotNull(db.historyCursorDao().byRoom(bufferId))
        assertEquals("primary", cursor.newestMsgid)
        assertEquals(100L, cursor.newestServerTime)
    }

    @Test
    fun futurePendingRowCannotReplaceAuthoritativeRoomFallbackBoundary() = runTest {
        processor.process(networkId, message("authoritative", 100))
        processor.insertPending(
            bufferId = bufferId,
            label = "future-pending",
            sender = "me",
            text = "local clock row",
            replyToMsgid = null,
            kind = MessageKind.PRIVMSG,
        )
        assertEquals(null, db.historyCursorDao().byRoom(bufferId))
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.AFTER -> {
                    assertEquals("msgid=authoritative", request.bound1)
                    FakeResponse(primaryMessageCount = 0)
                }
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(primaryMessageCount = 0)
                else -> error("unexpected ${request.subcommand}")
            }
        }

        assertEquals(
            HistoryResyncState.UpToDate,
            coordinator.resyncBuffer(networkId, bufferId, "#chan", source),
        )
        assertEquals("msgid=authoritative", source.requests.first().bound1)
    }

    @Test
    fun missingDirectionalBoundaryReturnsIncompleteNotUpToDate() = runTest {
        val source = FakeSource {
            FakeResponse(
                events = listOf(message("partial", 100)),
                oldest = ChatHistoryReference("partial", 100),
                newest = null,
            )
        }

        val result = coordinator.resyncBuffer(
            networkId,
            bufferId,
            "#chan",
            source,
            range = HistoryRefreshRange.HOURS_24,
        )

        assertTrue(result is HistoryResyncState.Incomplete)
        assertEquals(1, (result as HistoryResyncState.Incomplete).inserted)
    }

    @Test
    fun unchangedBoundaryReturnsIncomplete() = runTest {
        processor.process(networkId, message("seed", 100))
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.AFTER -> FakeResponse(listOf(message("seed", 100)))
                else -> error("LATEST must not hide an incomplete AFTER traversal")
            }
        }

        val result = coordinator.resyncBuffer(networkId, bufferId, "#chan", source)

        assertTrue(result is HistoryResyncState.Incomplete)
        assertEquals(listOf(ChatHistoryRequest.Subcommand.AFTER), source.requests.map { it.subcommand })
    }

    @Test
    fun nonTerminalTraversalAtSafetyLimitReturnsCapped() = runTest {
        processor.process(networkId, message("seed", 100))
        coordinator.paginationRequestLimit = 2
        val source = FakeSource { request ->
            val next = when (request.bound1) {
                "msgid=seed" -> message("m2", 200)
                "msgid=m2" -> message("m3", 300)
                else -> error("unexpected boundary ${request.bound1}")
            }
            FakeResponse(listOf(next))
        }

        val result = coordinator.resyncBuffer(networkId, bufferId, "#chan", source)

        val capped = result as HistoryResyncState.Capped
        assertEquals(2, capped.inserted)
        assertEquals(2, capped.limit)
        assertEquals(2, source.requests.size)
    }

    @Test
    fun shortNonTerminalBeforePageContinuesFromResponseOldest() = runTest {
        var page = 0
        val source = FakeSource(pageLimit = 10) { request ->
            when (page++) {
                0 -> {
                    assertEquals(ChatHistoryRequest.Subcommand.LATEST, request.subcommand)
                    FakeResponse(listOf(message("m3", 300)))
                }
                1 -> {
                    assertEquals(ChatHistoryRequest.Subcommand.BEFORE, request.subcommand)
                    assertEquals("msgid=m3", request.bound1)
                    FakeResponse(listOf(message("m2", 200)))
                }
                else -> {
                    assertEquals(ChatHistoryRequest.Subcommand.BEFORE, request.subcommand)
                    assertEquals("msgid=m2", request.bound1)
                    FakeResponse(listOf(message("m1", 100)), endOfHistory = true)
                }
            }
        }

        assertEquals(
            HistoryResyncState.Updated(3),
            coordinator.resyncBuffer(
                networkId,
                bufferId,
                "#chan",
                source,
                range = HistoryRefreshRange.ALL_AVAILABLE,
            ),
        )
        assertEquals(3, source.requests.size)
    }

    @Test
    fun shortNonTerminalBetweenPageContinuesFromResponseOldest() = runTest {
        processor.process(networkId, message("known-oldest", 100))
        processor.process(networkId, message("known-newest", 1_000))
        var betweenPage = 0
        val source = FakeSource(pageLimit = 10) { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.AFTER -> FakeResponse(endOfHistory = true)
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = listOf(message("latest-overlap", 900)),
                )
                ChatHistoryRequest.Subcommand.BETWEEN -> when (betweenPage++) {
                    0 -> {
                        assertEquals("msgid=latest-overlap", request.bound1)
                        FakeResponse(listOf(message("middle", 500)))
                    }
                    else -> {
                        assertEquals("msgid=middle", request.bound1)
                        FakeResponse(listOf(message("old-gap", 200)), endOfHistory = true)
                    }
                }
                else -> error("unexpected ${request.subcommand}")
            }
        }

        assertEquals(
            HistoryResyncState.Updated(3),
            coordinator.resyncBuffer(networkId, bufferId, "#chan", source),
        )
        assertEquals(2, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.BETWEEN })
    }

    @Test
    fun invalidMsgrefTypeRetriesSameAfterBoundaryAsTimestampOnly() = runTest {
        processor.process(networkId, message("seed", 100))
        val source = FakeSource { request ->
            when {
                request.subcommand == ChatHistoryRequest.Subcommand.AFTER &&
                    request.bound1 == "msgid=seed" -> throw IrcCommandException(
                    "CHATHISTORY",
                    "INVALID_MSGREFTYPE",
                    "msgid unsupported",
                )
                request.subcommand == ChatHistoryRequest.Subcommand.AFTER ->
                    FakeResponse(listOf(message("tail", 200)), endOfHistory = true)
                request.subcommand == ChatHistoryRequest.Subcommand.LATEST ->
                    FakeResponse(endOfHistory = true)
                else -> error("unexpected ${request.subcommand}")
            }
        }

        assertEquals(
            HistoryResyncState.Updated(1),
            coordinator.resyncBuffer(networkId, bufferId, "#chan", source),
        )
        assertEquals(
            listOf("msgid=seed", "timestamp=1970-01-01T00:00:00.100Z"),
            source.requests.filter { it.subcommand == ChatHistoryRequest.Subcommand.AFTER }
                .map { it.bound1 },
        )
    }

    @Test
    fun disconnectAndProtocolFailuresDoNotFallBackToLatest() = runTest {
        processor.process(networkId, message("seed", 100))
        val failures = listOf<Exception>(
            IrcDisconnectedException("CHATHISTORY", "gone"),
            IrcProtocolException("CHATHISTORY", "bad batch"),
            IrcCommandException("CHATHISTORY", "MESSAGE_ERROR", "failed"),
        )
        failures.forEach { failure ->
            val source = FakeSource { request ->
                assertEquals(ChatHistoryRequest.Subcommand.AFTER, request.subcommand)
                throw failure
            }

            assertTrue(coordinator.resyncBuffer(networkId, bufferId, "#chan", source) is HistoryResyncState.Failed)
            assertEquals(listOf(ChatHistoryRequest.Subcommand.AFTER), source.requests.map { it.subcommand })
        }
    }

    @Test
    fun targetsShortPageContinuesAndDeduplicatesTimestampOverlap() = runTest {
        db.bufferDao().deleteBuffer(bufferId)
        var targetsPage = 0
        val source = FakeSource(pageLimit = 5) { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS -> when (targetsPage++) {
                    0 -> FakeResponse(targets = listOf("#new" to 300L))
                    else -> {
                        assertEquals(ChatHistorySelectors.timestamp(301), request.bound1)
                        FakeResponse(
                            targets = listOf("#NEW" to 300L, "#old" to 200L),
                            endOfHistory = true,
                        )
                    }
                }
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(endOfHistory = true)
                else -> FakeResponse(endOfHistory = true)
            }
        }

        assertEquals(HistoryResyncState.UpToDate, coordinator.resyncNetwork(networkId, emptyList(), source))
        assertEquals(2, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS })
        assertEquals(2, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.LATEST })
        assertEquals(300L, syncPrefs.lastSuccessfulSync(networkId))
    }

    @Test
    fun saturatedTargetsTimestampTieReturnsIncompleteWithoutWatermark() = runTest {
        db.bufferDao().deleteBuffer(bufferId)
        val source = FakeSource(pageLimit = 2) { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("#a" to 100L, "#b" to 100L))
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(endOfHistory = true)
                else -> FakeResponse(endOfHistory = true)
            }
        }

        val result = coordinator.resyncNetwork(networkId, emptyList(), source)

        assertTrue(result is HistoryResyncState.Incomplete)
        assertEquals(null, syncPrefs.lastSuccessfulSync(networkId))
        assertEquals(2, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS })
    }

    @Test
    fun completeNetworkPassPersistsNewestServerPageBoundary() = runTest {
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("#chan" to 500L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = listOf(message("server-high-water", 700)),
                    endOfHistory = true,
                )
                else -> FakeResponse(endOfHistory = true)
            }
        }

        assertEquals(
            HistoryResyncState.Updated(1),
            coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source),
        )
        assertEquals(700L, syncPrefs.lastSuccessfulSync(networkId))
    }

    @Test
    fun completeNetworkPassCannotMoveWatermarkBackward() = runTest {
        syncPrefs.setLastSuccessfulSync(networkId, 1_000)
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("#chan" to 500L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.AFTER -> FakeResponse(primaryMessageCount = 0)
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = listOf(message("older-server-boundary", 700)),
                    endOfHistory = true,
                )
                else -> FakeResponse(primaryMessageCount = 0)
            }
        }

        assertEquals(
            HistoryResyncState.Updated(1),
            coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source),
        )
        assertEquals(1_000L, syncPrefs.lastSuccessfulSync(networkId))
    }

    @Test
    fun incompleteTargetPassPreservesPreviousNetworkWatermark() = runTest {
        processor.process(networkId, message("seed", 100))
        syncPrefs.setLastSuccessfulSync(networkId, 1_000)
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("#chan" to 2_000L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.AFTER -> FakeResponse(
                    events = emptyList(),
                    oldest = ChatHistoryReference("partial", 1_500),
                    newest = null,
                    primaryMessageCount = 1,
                )
                else -> FakeResponse(endOfHistory = true)
            }
        }

        val result = coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source)

        assertTrue(result is HistoryResyncState.Incomplete)
        assertEquals(1_000L, syncPrefs.lastSuccessfulSync(networkId))
    }

    @Test
    fun transientTargetFailurePreservesWatermarkAndDoesNotRequestLatest() = runTest {
        processor.process(networkId, message("seed", 100))
        syncPrefs.setLastSuccessfulSync(networkId, 1_000)
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("#chan" to 2_000L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.AFTER -> throw IOException("temporary transport failure")
                ChatHistoryRequest.Subcommand.LATEST -> error("transient AFTER failure must not fall back")
                else -> FakeResponse(endOfHistory = true)
            }
        }

        val result = coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source)

        assertTrue(result is HistoryResyncState.Failed)
        assertEquals(1_000L, syncPrefs.lastSuccessfulSync(networkId))
        assertEquals(
            listOf(ChatHistoryRequest.Subcommand.TARGETS, ChatHistoryRequest.Subcommand.AFTER),
            source.requests.map { it.subcommand },
        )
    }

    @Test
    fun cappedTargetsPassDoesNotPersistWatermark() = runTest {
        db.bufferDao().deleteBuffer(bufferId)
        coordinator.targetsRequestLimit = 1
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS -> FakeResponse(targets = listOf("#a" to 100L))
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(endOfHistory = true)
                else -> FakeResponse(endOfHistory = true)
            }
        }

        val result = coordinator.resyncNetwork(networkId, emptyList(), source)

        assertTrue(result is HistoryResyncState.Capped)
        assertEquals(null, syncPrefs.lastSuccessfulSync(networkId))
    }

    @Test
    fun equivalentAutomaticRequestsCoalesce() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val source = FakeSource {
            calls++
            entered.complete(Unit)
            release.await()
            FakeResponse(endOfHistory = true)
        }

        val first = async { coordinator.reconcileBuffer(networkId, bufferId, "#chan", source) }
        entered.await()
        val second = async { coordinator.reconcileBuffer(networkId, bufferId, "#chan", source) }
        delay(20)
        release.complete(Unit)

        assertEquals(HistoryResyncState.UpToDate, first.await())
        assertEquals(HistoryResyncState.UpToDate, second.await())
        assertEquals(1, calls)
    }

    @Test
    fun manualRangeDoesNotJoinWeakerAutomaticFlight() = runTest {
        val automaticEntered = CompletableDeferred<Unit>()
        val releaseAutomatic = CompletableDeferred<Unit>()
        var calls = 0
        val source = FakeSource {
            calls++
            if (calls == 1) {
                automaticEntered.complete(Unit)
                releaseAutomatic.await()
            }
            FakeResponse(endOfHistory = true)
        }

        val automatic = async { coordinator.reconcileBuffer(networkId, bufferId, "#chan", source) }
        automaticEntered.await()
        val manual = async {
            coordinator.resyncBuffer(
                networkId,
                bufferId,
                "#chan",
                source,
                range = HistoryRefreshRange.DAYS_30,
            )
        }
        delay(20)
        assertEquals(1, calls)
        releaseAutomatic.complete(Unit)

        assertEquals(HistoryResyncState.UpToDate, automatic.await())
        assertEquals(HistoryResyncState.UpToDate, manual.await())
        assertEquals(2, calls)
    }

    @Test
    fun automaticRequestMayJoinActiveManualFlight() = runTest {
        val manualEntered = CompletableDeferred<Unit>()
        val releaseManual = CompletableDeferred<Unit>()
        var calls = 0
        val source = FakeSource {
            calls++
            manualEntered.complete(Unit)
            releaseManual.await()
            FakeResponse(endOfHistory = true)
        }

        val manual = async { coordinator.resyncBuffer(networkId, bufferId, "#chan", source) }
        manualEntered.await()
        val automatic = async { coordinator.reconcileBuffer(networkId, bufferId, "#chan", source) }
        delay(20)
        releaseManual.complete(Unit)

        assertEquals(HistoryResyncState.UpToDate, manual.await())
        assertEquals(HistoryResyncState.UpToDate, automatic.await())
        assertEquals(1, calls)
    }

    @Test
    fun immediateManualCancellationCannotMissQueuedRegistration() = runTest {
        val lockHeld = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        val holder = backgroundScope.launch {
            CanonicalHistorySingleFlight.withNetwork(networkId) {
                lockHeld.complete(Unit)
                releaseLock.await()
            }
        }
        lockHeld.await()
        val source = FakeSource { FakeResponse(primaryMessageCount = 0) }
        val manual = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.resyncBuffer(networkId, bufferId, "#chan", source)
        }

        coordinator.cancelBufferResync(bufferId)
        releaseLock.complete(Unit)

        assertTrue(runCatching { withTimeout(1_000) { manual.await() } }.isFailure)
        assertTrue(source.requests.isEmpty())
        holder.join()
    }

    @Test
    fun immediateCancellationDoesNotCancelNextManualGeneration() = runTest {
        coordinator = HistoryResyncCoordinator(db, processor, syncPrefs, backgroundScope)
        val firstEntered = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val holdFirst = CompletableDeferred<Unit>()
        var calls = 0
        val source = FakeSource {
            calls++
            if (calls == 1) {
                firstEntered.complete(Unit)
                try {
                    holdFirst.await()
                } finally {
                    firstCancelled.complete(Unit)
                }
            } else {
                secondEntered.complete(Unit)
            }
            FakeResponse(primaryMessageCount = 0)
        }
        val first = async { coordinator.resyncBuffer(networkId, bufferId, "#chan", source) }
        runCurrent()
        firstEntered.await()

        coordinator.cancelBufferResync(bufferId)
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.resyncBuffer(networkId, bufferId, "#chan", source)
        }
        runCurrent()
        firstCancelled.await()
        secondEntered.await()

        assertTrue(runCatching { first.await() }.isFailure)
        assertTrue(firstCancelled.isCompleted)
        assertEquals(2, source.requests.size)
        assertEquals(HistoryResyncState.UpToDate, second.await())
    }

    @Test
    fun automaticWaiterRestartsWhenJoinedManualFlightIsCancelled() = runTest {
        coordinator = HistoryResyncCoordinator(db, processor, syncPrefs, backgroundScope)
        val manualEntered = CompletableDeferred<Unit>()
        val manualCancelled = CompletableDeferred<Unit>()
        val automaticRestarted = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        var calls = 0
        val source = FakeSource {
            calls++
            if (calls == 1) {
                manualEntered.complete(Unit)
                try {
                    neverRelease.await()
                } finally {
                    manualCancelled.complete(Unit)
                }
            } else {
                automaticRestarted.complete(Unit)
            }
            FakeResponse(endOfHistory = true)
        }

        val manual = async { coordinator.resyncBuffer(networkId, bufferId, "#chan", source) }
        manualEntered.await()
        val automatic = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.reconcileBuffer(networkId, bufferId, "#chan", source)
        }
        assertEquals(1, source.requests.size)

        coordinator.cancelBufferResync(bufferId)
        runCurrent()
        manualCancelled.await()
        automaticRestarted.await()

        assertTrue(runCatching { manual.await() }.isFailure)
        assertEquals(2, calls)
        assertEquals(HistoryResyncState.UpToDate, automatic.await())
    }

    @Test
    fun uiCancellationDoesNotCancelAutomaticFlight() = runTest {
        coordinator = HistoryResyncCoordinator(db, processor, syncPrefs, backgroundScope)
        val automaticEntered = CompletableDeferred<Unit>()
        val releaseAutomatic = CompletableDeferred<Unit>()
        val source = FakeSource {
            automaticEntered.complete(Unit)
            releaseAutomatic.await()
            FakeResponse(endOfHistory = true)
        }

        val automatic = async { coordinator.reconcileBuffer(networkId, bufferId, "#chan", source) }
        automaticEntered.await()
        coordinator.cancelBufferResync(bufferId)
        runCurrent()

        assertTrue(automatic.isActive)
        assertEquals(1, source.requests.size)
        releaseAutomatic.complete(Unit)
        runCurrent()

        assertEquals(HistoryResyncState.UpToDate, automatic.await())
    }

    @Test
    fun automaticRetryBackoffRemainsBounded() {
        assertEquals(2_000L, catchUpRetryDelayMs(0))
        assertEquals(4_000L, catchUpRetryDelayMs(1))
        assertEquals(30_000L, catchUpRetryDelayMs(20))
    }
}
