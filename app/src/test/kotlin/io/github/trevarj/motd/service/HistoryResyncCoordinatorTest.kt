package io.github.trevarj.motd.service

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.HistoryCursorEntity
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.prefs.HistorySyncPrefs
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.HistoryPageLoader
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.ChatHistoryTarget
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.client.IrcProtocolException
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import io.github.trevarj.motd.irc.proto.Prefix
import io.github.trevarj.motd.ui.chat.entryHistoryReady
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryResyncCoordinatorTest {
    private lateinit var db: MotdDatabase
    private lateinit var processor: EventProcessor
    private lateinit var coordinator: HistoryResyncCoordinator
    private var networkId = 0L
    private var bufferId = 0L
    private val syncPrefs =
        object : HistorySyncPrefs {
            private val values = mutableMapOf<Long, Long>()

            override suspend fun lastSuccessfulSync(networkId: Long): Long? = values[networkId]

            override suspend fun setLastSuccessfulSync(
                networkId: Long,
                timestamp: Long,
            ) {
                values[networkId] = timestamp
            }

            override suspend fun clear(networkId: Long) {
                values.remove(networkId)
            }
        }

    private fun openTargets(vararg targets: Pair<Long, String>): List<OpenBufferTarget> = openTargets(targets.toList())

    private fun openTargets(targets: List<Pair<Long, String>>): List<OpenBufferTarget> = targets.map { (id, name) -> OpenBufferTarget(id, name) }

    @Before
    fun setUp() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db =
                Room
                    .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
            coordinator =
                HistoryResyncCoordinator(
                    db,
                    processor,
                    syncPrefs,
                    CoroutineScope(SupervisorJob() + Dispatchers.Default),
                )
            networkId =
                db.networkDao().insert(
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
            bufferId =
                db.bufferDao().insert(
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

    private fun message(
        msgid: String,
        time: Long,
        target: String = "#chan",
    ) = IrcEvent.ChatMessage(
        ctx = MessageContext(msgid, time, null, "batch", null),
        kind = IrcEvent.ChatKind.PRIVMSG,
        source = Prefix("alice"),
        target = target,
        text = msgid,
        isSelf = false,
        replyToMsgid = null,
    )

    private fun directMessage(
        msgid: String,
        time: Long,
        peer: String = "bob",
    ) = IrcEvent.ChatMessage(
        ctx = MessageContext(msgid, time, null, "batch", null),
        kind = IrcEvent.ChatKind.PRIVMSG,
        source = Prefix(peer),
        target = "me",
        text = msgid,
        isSelf = false,
        replyToMsgid = null,
    )

    private suspend fun rows(
        id: Long = bufferId,
        loadSize: Int = 500,
    ): List<MessageEntity> {
        val loaded =
            db.messageDao().pagingSource(id).load(
                PagingSource.LoadParams.Refresh(key = null, loadSize = loadSize, placeholdersEnabled = false),
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
        fun List<IrcEvent>.references(): List<ChatHistoryReference> =
            mapNotNull { event ->
                val context =
                    when (event) {
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
        var timestampRefs: Boolean = true,
        var pageLimit: Int = 100,
        var targetClassificationReady: Boolean = true,
        // False keeps every existing test on the strictly-sequential driver.
        var supportsConcurrent: Boolean = false,
        val channelClassifier: (String) -> Boolean = { target ->
            target.startsWith('#') || target.startsWith('&')
        },
        val responder: suspend (ChatHistoryRequest) -> FakeResponse,
    ) : HistoryResyncCoordinator.HistorySource {
        // Synchronized: a labeled-response pass records requests from concurrent fetches.
        val requests: MutableList<ChatHistoryRequest> =
            java.util.Collections.synchronizedList(mutableListOf())

        /**
         * Sampling seam: the coordinator probes availability while preparing a target, which is the
         * only point a test can observe a buffer between registration (Queued) and its wire fetch.
         */
        var onAvailability: (suspend () -> Unit)? = null

        override suspend fun availability(): HistoryAvailability {
            onAvailability?.invoke()
            return if (supported) {
                HistoryAvailability.Ready(
                    buildSet {
                        if (timestampRefs) add(HistoryReferenceType.TIMESTAMP)
                        if (msgidRefs) add(HistoryReferenceType.MSGID)
                    },
                    pageLimit,
                    supportsConcurrentRequests = supportsConcurrent,
                )
            } else {
                HistoryAvailability.Unsupported
            }
        }

        override fun canClassifyTargets(): Boolean = targetClassificationReady

        override fun isChannelTarget(target: String): Boolean = channelClassifier(target)

        override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
            requests += req
            val response = responder(req)
            return if (req.subcommand == ChatHistoryRequest.Subcommand.TARGETS) {
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
    fun transientNewDmPush_isIncludedInReconnectHistoryCatchup() =
        runTest {
            val target = "new-dm-history-fixture"
            val push =
                IrcEvent.ChatMessage(
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

            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf(target to 400L), endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events =
                                    if (request.target == target) {
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
                        }

                        else -> {
                            FakeResponse(emptyList(), emptyList())
                        }
                    }
                }

            val result =
                coordinator.resyncNetwork(
                    networkId,
                    db.bufferDao().openTargets(networkId).map { OpenBufferTarget(it.id, it.name, it.pinned) },
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
    fun pendingMessagePromotionInterleavesBetweenResyncPages() =
        runTest {
            // Phase 3 replaced the bespoke bypass of the network-wide gate with per-request wire
            // serialization in the loader: an urgent pending promotion must be serviced BETWEEN two
            // pages of an in-flight network resync — never queued behind the entire pass.
            val loader = HistoryPageLoader(processor)
            coordinator = HistoryResyncCoordinator(db, processor, syncPrefs, backgroundScope, loader = loader)
            val otherId =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#other", displayName = "#other", type = BufferType.CHANNEL),
                )
            val pendingId =
                processor.insertPending(
                    bufferId = bufferId,
                    label = "local-pending",
                    sender = "me",
                    text = "react-now",
                    replyToMsgid = null,
                    kind = MessageKind.PRIVMSG,
                )
            val page1Entered = CompletableDeferred<Unit>()
            val releasePage1 = CompletableDeferred<Unit>()
            // The pass's per-buffer pages request limit RECENT_PAGE_SIZE (50); the pending promotion
            // requests the full page limit (100), which distinguishes it in the recorded request order.
            val source =
                FakeSource { request ->
                    when {
                        request.subcommand == ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(endOfHistory = true)
                        }

                        request.target == "#chan" && request.limit == 50 -> {
                            page1Entered.complete(Unit)
                            releasePage1.await()
                            FakeResponse(listOf(message("m1", 100)), endOfHistory = true)
                        }

                        request.target == "#chan" -> {
                            FakeResponse(
                                events =
                                    listOf(
                                        IrcEvent.ChatMessage(
                                            ctx =
                                                MessageContext(
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
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            FakeResponse(listOf(message("o1", 300, target = "#other")), endOfHistory = true)
                        }
                    }
                }

            val pass =
                async {
                    coordinator.resyncNetwork(
                        networkId,
                        openTargets(bufferId to "#chan", otherId to "#other"),
                        source,
                    )
                }
            page1Entered.await()
            // The pass holds the loader's wire lock for its first page. The urgent promotion queues on
            // that per-request lock, taking the very next slot ahead of the pass's remaining pages.
            val pending = async { coordinator.reconcilePendingMessage(networkId, bufferId, "#chan", source) }
            runCurrent()
            releasePage1.complete(Unit)

            assertEquals(HistoryResyncState.UpToDate, pending.await())
            assertEquals(HistoryResyncState.Updated(2), pass.await())
            assertEquals("durable-react-target", db.messageDao().byCanonicalId(pendingId)?.msgid)
            assertEquals("durable-react-target", db.historyCursorDao().byRoom(bufferId)?.newestMsgid)
            // Request order on the wire is the proof: the pending LATEST (limit 100) was serviced
            // strictly between the pass's two per-buffer pages (limit 50) — i.e. before the pass had
            // even sent its final page, never queued behind the whole pass.
            assertEquals(
                listOf(
                    "TARGETS:*:100",
                    "LATEST:#chan:50",
                    "LATEST:#chan:100",
                    "LATEST:#other:50",
                ),
                source.requests.map { "${it.subcommand}:${it.target}:${it.limit}" },
            )
        }

    @Test
    fun reopeningVisibleBufferDoesNotWalkBackwardIntoOldSparseHistory() =
        runTest {
            processor.process(networkId, message("m1", 1))
            (103L..202L).forEach { processor.process(networkId, message("m$it", it)) }
            val source =
                FakeSource(pageLimit = 100) { request ->
                    val events =
                        when (request.subcommand) {
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
                listOf(ChatHistoryRequest.Subcommand.LATEST),
                source.requests.map { it.subcommand },
            )
            assertEquals(0, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.BEFORE })
            assertTrue(rows().none { it.msgid == "m102" })
        }

    @Test
    fun automaticNetworkResyncDiscoversQueriesButNotDepartedChannels() =
        runTest {
            processor.process(networkId, message("seed", 100))
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                targets = listOf("Alice" to 500L, "#departed" to 400L),
                                endOfHistory = true,
                            )
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                if (request.target == "Alice") listOf(message("found", 500, "me")) else emptyList(),
                                emptyList(),
                            )
                        }

                        else -> {
                            FakeResponse(emptyList(), emptyList())
                        }
                    }
                }

            val result = coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)

            assertEquals(HistoryResyncState.Updated(1), result)
            val query = db.bufferDao().byName(networkId, "alice")
            assertEquals(BufferType.QUERY, query?.type)
            assertEquals("found", db.messageDao().newestMessage(query!!.id)?.msgid)
            assertEquals(null, db.bufferDao().byName(networkId, "#departed"))
            assertTrue(source.requests.any { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS })
        }

    @Test
    fun automaticNetworkResyncBoundsThousandMessageGapToOneRecentPage() =
        runTest {
            processor.process(networkId, message("seed", 100))
            val source =
                FakeSource(pageLimit = 100) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                targets = listOf("#chan" to 1_214L),
                                endOfHistory = true,
                            )
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events = (1_165..1_214).map { message("m$it", it.toLong()) },
                            )
                        }

                        ChatHistoryRequest.Subcommand.BEFORE -> {
                            val newest = request.bound1!!.removePrefix("msgid=m").toInt() - 1
                            FakeResponse((newest - 49..newest).map { message("m$it", it.toLong()) })
                        }

                        else -> {
                            error("unexpected ${request.subcommand}")
                        }
                    }
                }

            val result =
                coordinator.resyncNetwork(
                    networkId,
                    openTargets(bufferId to "#chan"),
                    source,
                )

            assertEquals(HistoryResyncState.Updated(50), result)
            val msgids = rows(loadSize = 2_000).mapNotNull { it.msgid }
            assertEquals(51, msgids.size)
            assertEquals(51, msgids.toSet().size)
            assertEquals("m1214", msgids.first())
            assertEquals("seed", msgids.last())
            assertEquals(0, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.BEFORE })
            assertEquals(HistorySyncStatus.Idle, coordinator.syncStatus(bufferId).first())
            assertEquals(1_214L, syncPrefs.lastSuccessfulSync(networkId))
        }

    @Test
    fun automaticRecentWindowTrimsAnOversizedServerResponseBeforePersistence() =
        runTest {
            processor.process(networkId, message("seed", 100))
            val source =
                FakeSource(pageLimit = 50) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                targets = listOf("#chan" to 1_000L),
                                endOfHistory = true,
                            )
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events = (1..1_000).map { message("m$it", it.toLong()) },
                            )
                        }

                        ChatHistoryRequest.Subcommand.BEFORE -> {
                            FakeResponse(endOfHistory = true)
                        }

                        else -> {
                            error("unexpected ${request.subcommand}")
                        }
                    }
                }

            val result = coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)

            assertEquals(HistoryResyncState.Updated(50), result)
            val msgids = rows(loadSize = 2_000).mapNotNull { it.msgid }
            assertEquals(51, msgids.size)
            assertTrue((951..1_000).all { "m$it" in msgids })
            assertTrue("m1" !in msgids)
            assertEquals(0, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.BEFORE })
        }

    @Test
    fun automaticNetworkResyncUsesOneNewestPageWhenRoomAlreadyHasTheLatestRow() =
        runTest {
            processor.process(networkId, message("m2000", 2_000))
            syncPrefs.setLastSuccessfulSync(networkId, 0)
            val source =
                FakeSource(pageLimit = 100) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                targets = listOf("#chan" to 2_000L),
                                endOfHistory = true,
                            )
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events = (1_951..2_000).map { message("m$it", it.toLong()) },
                            )
                        }

                        ChatHistoryRequest.Subcommand.BEFORE -> {
                            val newest = request.bound1!!.removePrefix("msgid=m").toInt() - 1
                            FakeResponse((newest - 49..newest).map { message("m$it", it.toLong()) })
                        }

                        else -> {
                            error("unexpected ${request.subcommand}")
                        }
                    }
                }

            val result =
                coordinator.resyncNetwork(
                    networkId,
                    openTargets(bufferId to "#chan"),
                    source,
                )

            assertEquals(HistoryResyncState.Updated(49), result)
            val msgids = rows(loadSize = 2_500).mapNotNull { it.msgid }
            assertEquals(50, msgids.size)
            assertTrue((1_951..2_000).all { "m$it" in msgids })
            assertEquals(0, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.BEFORE })
            assertEquals("m2000", db.historyCursorDao().byRoom(bufferId)?.newestMsgid)
            assertEquals(2_000L, syncPrefs.lastSuccessfulSync(networkId))
        }

    @Test
    fun automaticNetworkResyncDoesNotRetryAfterReachingAdvertisedLatest() =
        runTest {
            processor.process(networkId, message("seed", 100))
            val source =
                FakeSource(pageLimit = 1) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                targets = listOf("#chan" to 101L),
                                endOfHistory = true,
                            )
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events = listOf(message("m101", 101)),
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            error("unexpected ${request.subcommand}")
                        }
                    }
                }

            val result =
                coordinator.resyncNetwork(
                    networkId,
                    openTargets(bufferId to "#chan"),
                    source,
                )

            assertEquals(HistoryResyncState.Updated(1), result)
            assertEquals(1, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.LATEST })
            assertEquals(HistorySyncStatus.Idle, coordinator.syncStatus(bufferId).first())
            assertEquals(101L, syncPrefs.lastSuccessfulSync(networkId))
            assertEquals(listOf("m101", "seed"), rows().mapNotNull { it.msgid })
        }

    @Test
    fun automaticNetworkResyncRecommendsRetryWhileAdvertisedLatestIsMissing() =
        runTest {
            processor.process(networkId, message("seed", 100_000))
            val source =
                FakeSource(pageLimit = 1) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                targets = listOf("#chan" to 102_000L),
                                endOfHistory = true,
                            )
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events = listOf(message("m101", 101_000)),
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            error("unexpected ${request.subcommand}")
                        }
                    }
                }

            val result =
                coordinator.resyncNetwork(
                    networkId,
                    openTargets(bufferId to "#chan"),
                    source,
                )

            assertTrue(result is HistoryResyncState.Incomplete)
            val incomplete = result as HistoryResyncState.Incomplete
            assertEquals(true, incomplete.retryRecommended)
            assertTrue(shouldRetryIncompleteCatchUp(incomplete))
            // The buffer's own fetch succeeded end-to-end, so no error badge: the shortfall drives
            // only the automatic pass retry, never a user-facing false negative.
            assertEquals(HistorySyncStatus.Idle, coordinator.syncStatus(bufferId).first())
            // Discovery itself completed, so the watermark advances despite the target shortfall.
            assertEquals(102_000L, syncPrefs.lastSuccessfulSync(networkId))
            assertEquals(listOf("m101", "seed"), rows().mapNotNull { it.msgid })
        }

    @Test
    fun unreachableAdvertisedLatestSettlesIdleAfterAnEmptyTerminalPage() =
        runTest {
            // TARGETS advertises a timestamp CHATHISTORY never replays (soju can index an event that
            // replay never returns). A terminal LATEST that inserts nothing is the server's proof that
            // nothing newer will ever arrive, so the target must settle instead of wearing a permanent
            // Partial badge with no affordance to clear it.
            processor.process(networkId, message("m400", 400_000))
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                targets = listOf("#chan" to 500_000L),
                                endOfHistory = true,
                            )
                        }

                        // No endOfHistory: soju 0.10.x omits draft/chathistory-end on message batches, so
                        // convergence must not depend on a terminal marker that never arrives.
                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events = listOf(message("m400", 400_000)),
                            )
                        }

                        else -> {
                            error("unexpected ${request.subcommand}")
                        }
                    }
                }

            val result = coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)

            assertEquals(HistoryResyncState.UpToDate, result)
            assertEquals(HistorySyncStatus.Idle, coordinator.syncStatus(bufferId).first())
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
            // The pass completed, so the watermark advances past the unreachable advertised time and
            // the next reconnect does not rediscover the same dead end.
            assertEquals(500_000L, syncPrefs.lastSuccessfulSync(networkId))
        }

    @Test
    fun advertisedLatestMissConvergesOnTheRetryPassOnceNothingNewIsReplayed() =
        runTest {
            // First pass: LATEST genuinely inserts a new message but still falls short of the
            // advertised time, so a retry is recommended. Retry pass: the same terminal page now
            // deduplicates to zero inserts, which settles the target instead of looping forever.
            processor.process(networkId, message("seed", 100_000))
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                targets = listOf("#chan" to 102_000L),
                                endOfHistory = true,
                            )
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events = listOf(message("m101", 101_000)),
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            error("unexpected ${request.subcommand}")
                        }
                    }
                }

            val first = coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)
            assertTrue(first is HistoryResyncState.Incomplete)
            assertTrue(shouldRetryIncompleteCatchUp(first as HistoryResyncState.Failed))

            val second = coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)
            assertEquals(HistoryResyncState.UpToDate, second)
            assertEquals(HistorySyncStatus.Idle, coordinator.syncStatus(bufferId).first())
            assertEquals(102_000L, syncPrefs.lastSuccessfulSync(networkId))
        }

    @Test
    fun staleConnectionLeavesTheTimelineWaitingForTheNextConnection() =
        runTest {
            processor.process(networkId, message("seed", 100))
            val requestStarted = CompletableDeferred<Unit>()
            val releaseRequest = CompletableDeferred<Unit>()
            var current = true
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                targets = listOf("#chan" to 200L),
                                endOfHistory = true,
                            )
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            requestStarted.complete(Unit)
                            releaseRequest.await()
                            FakeResponse(listOf(message("tail", 200)), endOfHistory = true)
                        }

                        else -> {
                            error("unexpected ${request.subcommand}")
                        }
                    }
                }
            val resync =
                async {
                    coordinator.resyncNetwork(
                        networkId,
                        openTargets(bufferId to "#chan"),
                        source,
                        isCurrent = { current },
                    )
                }
            requestStarted.await()

            assertEquals(HistorySyncStatus.Syncing, coordinator.syncStatus(bufferId).first())
            current = false
            releaseRequest.complete(Unit)

            assertTrue(resync.await() is HistoryResyncState.Failed)
            // The pass died with its connection, so the buffer keeps an optimistic waiting state
            // instead of silently going Idle: it is still queued for whatever connects next.
            assertEquals(
                HistorySyncStatus.AwaitingConnection,
                coordinator.syncStatus(bufferId).first(),
            )
        }

    @Test
    fun liveChantypesClassificationCanDiscoverHashPrefixedQuery() =
        runTest {
            db.bufferDao().deleteBuffer(bufferId)
            val source =
                FakeSource(channelClassifier = { false }) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#peer" to 500L), endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events =
                                    listOf(
                                        IrcEvent.ChatMessage(
                                            MessageContext("custom-query-in", 500, null, "batch", null),
                                            IrcEvent.ChatKind.PRIVMSG,
                                            Prefix("#peer"),
                                            "me",
                                            "hello",
                                            false,
                                            null,
                                        ),
                                        IrcEvent.ChatMessage(
                                            MessageContext("custom-query-out", 501, null, "batch", null),
                                            IrcEvent.ChatKind.PRIVMSG,
                                            Prefix("me"),
                                            "#peer",
                                            "reply",
                                            true,
                                            null,
                                        ),
                                    ),
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            assertEquals(HistoryResyncState.Updated(2), coordinator.resyncNetwork(networkId, emptyList(), source))
            val query = db.bufferDao().byName(networkId, "#peer")
            assertEquals(BufferType.QUERY, query?.type)
            assertEquals(2, db.messageDao().countForBuffer(query!!.id))
        }

    @Test
    fun networkWatermarkWaitsForTargetClassification() =
        runTest {
            val source =
                FakeSource(targetClassificationReady = false) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> error("TARGETS must wait for CHANTYPES")
                        ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(endOfHistory = true)
                        else -> FakeResponse(endOfHistory = true)
                    }
                }

            val result = coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)

            assertTrue(result is HistoryResyncState.Incomplete)
            assertTrue((result as HistoryResyncState.Incomplete).awaitsTargetClassification)
            assertTrue(source.requests.none { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS })
            assertEquals(null, syncPrefs.lastSuccessfulSync(networkId))
        }

    @Test
    fun freshNetworkDiscoversRetainedQueriesFromEpochAndStoresCursor() =
        runTest {
            db.bufferDao().deleteBuffer(bufferId)
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("old-friend" to 500L), endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                if (request.target == "old-friend") listOf(message("retained", 500, "me")) else emptyList(),
                                emptyList(),
                            )
                        }

                        else -> {
                            FakeResponse(emptyList(), emptyList())
                        }
                    }
                }

            // Everything-depth: this test pins the epoch-window request shape.
            val result = coordinator.resyncNetwork(networkId, emptyList(), source, initialLookbackMs = null)

            assertEquals(HistoryResyncState.Updated(1), result)
            val targets = source.requests.first { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS }
            assertEquals("timestamp=1970-01-01T00:00:00.000Z", targets.bound2)
            assertTrue(targets.bound1!!.matches(Regex("timestamp=.*\\.\\d{3}Z")))
            assertTrue(db.bufferDao().byName(networkId, "old-friend") != null)
            assertEquals(500L, syncPrefs.lastSuccessfulSync(networkId))
        }

    @Test
    fun freshNetworkPagesTargetsToExhaustionBeforeStoringCursor() =
        runTest {
            db.bufferDao().deleteBuffer(bufferId)
            val secondPageUpper = ChatHistorySelectors.timestamp(201)
            val source =
                FakeSource(pageLimit = 2) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                emptyList(),
                                if (request.bound1 == secondPageUpper) {
                                    listOf("middle" to 200L, "oldest" to 100L)
                                } else {
                                    listOf("newest" to 300L, "middle" to 200L)
                                },
                                endOfHistory = request.bound1 == secondPageUpper,
                            )
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            // Discovery must finish before any per-room sync can make the pass successful.
                            assertEquals(null, syncPrefs.lastSuccessfulSync(networkId))
                            val time =
                                when (request.target) {
                                    "newest" -> 300L
                                    "middle" -> 200L
                                    "oldest" -> 100L
                                    else -> error("unexpected target ${request.target}")
                                }
                            FakeResponse(
                                listOf(message("retained-${request.target}", time, "me")),
                                emptyList(),
                            )
                        }

                        else -> {
                            FakeResponse(emptyList(), emptyList())
                        }
                    }
                }

            // Everything-depth: exhaustion paging below the fixtures' small timestamps needs epoch.
            val result = coordinator.resyncNetwork(networkId, emptyList(), source, initialLookbackMs = null)

            assertEquals(HistoryResyncState.Updated(3), result)
            assertEquals(
                2,
                source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS },
            )
            assertEquals(
                setOf("newest", "middle", "oldest"),
                source.requests
                    .filter { it.subcommand == ChatHistoryRequest.Subcommand.LATEST }
                    .map { it.target }
                    .toSet(),
            )
            assertTrue(db.bufferDao().byName(networkId, "newest") != null)
            assertTrue(db.bufferDao().byName(networkId, "middle") != null)
            assertTrue(db.bufferDao().byName(networkId, "oldest") != null)
            assertEquals(300L, syncPrefs.lastSuccessfulSync(networkId))
        }

    @Test
    fun dismissedQueryIgnoresUnchangedHistoryThenRevivesForNewDm() =
        runTest {
            processor.process(networkId, directMessage("dm-old", 100_000))
            val query = db.bufferDao().byName(networkId, "bob")!!
            db.bufferDao().deleteBuffer(query.id)
            syncPrefs.setLastSuccessfulSync(networkId, 150_000)

            val unchanged =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("bob" to 100_000L), endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.AFTER -> {
                            FakeResponse(endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events = if (request.target == "bob") listOf(directMessage("dm-old", 100_000)) else emptyList(),
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            assertEquals(
                HistoryResyncState.UpToDate,
                coordinator.resyncNetwork(
                    networkId,
                    db.bufferDao().openTargets(networkId).map { OpenBufferTarget(it.id, it.name, it.pinned) },
                    unchanged,
                ),
            )
            assertTrue(db.bufferDao().rawById(query.id)!!.dismissed)
            assertEquals(0, db.messageDao().countForBuffer(query.id))

            val updated =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("bob" to 200_000L), endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.AFTER -> {
                            FakeResponse(
                                events = if (request.target == "bob") listOf(directMessage("dm-new", 200_000)) else emptyList(),
                                endOfHistory = true,
                            )
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events =
                                    if (request.target == "bob") {
                                        listOf(directMessage("dm-old", 100_000), directMessage("dm-new", 200_000))
                                    } else {
                                        emptyList()
                                    },
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            assertEquals(
                HistoryResyncState.Updated(1),
                coordinator.resyncNetwork(
                    networkId,
                    db.bufferDao().openTargets(networkId).map { OpenBufferTarget(it.id, it.name, it.pinned) },
                    updated,
                ),
            )
            assertTrue(!db.bufferDao().rawById(query.id)!!.dismissed)
            assertEquals(listOf("dm-new"), rows(query.id).mapNotNull { it.msgid })
        }

    @Test
    fun dismissedMsgidlessQueryIgnoresTargetAtExactDiscardTime() =
        runTest {
            val old =
                directMessage("ignored", 100).copy(
                    ctx = MessageContext(null, 100, null, "batch", null),
                    text = "msgidless-old",
                )
            processor.process(networkId, old)
            val query = db.bufferDao().byName(networkId, "bob")!!
            db.bufferDao().deleteBuffer(query.id)
            syncPrefs.setLastSuccessfulSync(networkId, 150)

            val shell = db.bufferDao().rawById(query.id)!!
            assertTrue(shell.dismissed)
            assertEquals(null, shell.historyDiscardedThroughMsgid)
            assertEquals(100L, shell.historyDiscardedThroughTime)

            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("bob" to 100L), endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.AFTER -> {
                            FakeResponse(endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events = if (request.target == "bob") listOf(old) else emptyList(),
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            assertEquals(
                HistoryResyncState.UpToDate,
                coordinator.resyncNetwork(
                    networkId,
                    db.bufferDao().openTargets(networkId).map { OpenBufferTarget(it.id, it.name, it.pinned) },
                    source,
                ),
            )
            assertTrue(db.bufferDao().rawById(query.id)!!.dismissed)
            assertEquals(0, db.messageDao().countForBuffer(query.id))
            assertTrue(source.requests.none { it.target == "bob" })
        }

    @Test
    fun repeatedForgetDoesNotRestorePreviousMsgidlessMessageOnOpen() =
        runTest {
            fun dm(
                text: String,
                time: Long,
            ) = directMessage("ignored", time).copy(
                ctx = MessageContext(null, time, null, null, null),
                text = text,
            )

            val retained = mutableListOf<IrcEvent.ChatMessage>()

            suspend fun receive(message: IrcEvent.ChatMessage) {
                retained += message
                processor.process(networkId, message)
            }
            val source =
                FakeSource(msgidRefs = false) { request ->
                    val lowerBound =
                        when (request.bound1) {
                            null -> null
                            ChatHistorySelectors.timestamp(100) -> 100L
                            ChatHistorySelectors.timestamp(200) -> 200L
                            ChatHistorySelectors.timestamp(300) -> 300L
                            else -> error("unexpected history bound ${request.bound1}")
                        }
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.AFTER,
                        ChatHistoryRequest.Subcommand.LATEST,
                        -> {
                            FakeResponse(
                                events =
                                    retained
                                        .filter { lowerBound == null || it.ctx.serverTime > lowerBound }
                                        .map { it.copy(ctx = it.ctx.copy(batchId = "history")) },
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            receive(dm("1", 100))
            val query = db.bufferDao().byName(networkId, "bob")!!
            db.bufferDao().deleteBuffer(query.id)

            receive(dm("2", 200))
            coordinator.reconcileBuffer(networkId, query.id, "bob", source)

            assertEquals(listOf("2"), rows(query.id).map { it.text })
            assertEquals(
                ChatHistorySelectors.timestamp(100),
                source.requests.last { it.subcommand == ChatHistoryRequest.Subcommand.LATEST }.bound1,
            )

            db.bufferDao().deleteBuffer(query.id)
            receive(dm("3", 300))
            coordinator.reconcileBuffer(networkId, query.id, "bob", source)

            assertEquals(listOf("3"), rows(query.id).map { it.text })
            assertEquals(
                ChatHistorySelectors.timestamp(200),
                source.requests.last { it.subcommand == ChatHistoryRequest.Subcommand.LATEST }.bound1,
            )
        }

    @Test
    fun dismissedQueryUsesLatestWhenDiscardBoundarySelectorIsUnsupported() =
        runTest {
            processor.process(networkId, directMessage("dm-old", 100))
            val query = db.bufferDao().byName(networkId, "bob")!!
            db.bufferDao().deleteBuffer(query.id)
            val shell = db.bufferDao().rawById(query.id)!!
            db.bufferDao().update(shell.copy(historyDiscardedThroughTime = null))
            db.historyCursorDao().upsert(
                HistoryCursorEntity(roomId = query.id, newestMsgid = "dm-old"),
            )
            assertTrue(db.bufferDao().isDiscardedMessageId(query.id, "dm-old"))
            val source =
                FakeSource(msgidRefs = false) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("bob" to 200L), endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events =
                                    if (request.target == "bob") {
                                        listOf(directMessage("dm-old", 100), directMessage("dm-new", 200))
                                    } else {
                                        emptyList()
                                    },
                                endOfHistory = true,
                            )
                        }

                        ChatHistoryRequest.Subcommand.AFTER -> {
                            error("unsupported discard cursor must be skipped")
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            val result =
                coordinator.resyncNetwork(
                    networkId,
                    db.bufferDao().openTargets(networkId).map { OpenBufferTarget(it.id, it.name, it.pinned) },
                    source,
                )
            assertEquals(listOf("dm-new"), rows(query.id).mapNotNull { it.msgid })
            assertEquals(HistoryResyncState.Updated(1), result)
            assertTrue(source.requests.none { it.subcommand == ChatHistoryRequest.Subcommand.AFTER })

            db.historyCursorDao().upsert(
                HistoryCursorEntity(roomId = query.id, newestMsgid = "dm-new"),
            )
            val visibleSource =
                FakeSource(msgidRefs = false) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events = listOf(directMessage("dm-newer", 300)),
                                endOfHistory = true,
                            )
                        }

                        ChatHistoryRequest.Subcommand.AFTER -> {
                            error("visible unsupported cursor must be skipped")
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            assertEquals(
                HistoryResyncState.Updated(1),
                coordinator.reconcileBuffer(networkId, query.id, "bob", visibleSource),
            )
            assertTrue(visibleSource.requests.none { it.subcommand == ChatHistoryRequest.Subcommand.AFTER })
            assertEquals(listOf("dm-newer", "dm-new"), rows(query.id).mapNotNull { it.msgid })
        }

    @Test
    fun runtimeMsgidRejectionStillChecksDismissedQueryLatest() =
        runTest {
            processor.process(networkId, directMessage("dm-old", 100))
            val query = db.bufferDao().byName(networkId, "bob")!!
            db.bufferDao().deleteBuffer(query.id)
            val shell = db.bufferDao().rawById(query.id)!!
            db.bufferDao().update(shell.copy(historyDiscardedThroughTime = null))
            db.historyCursorDao().upsert(
                HistoryCursorEntity(roomId = query.id, newestMsgid = "dm-old"),
            )
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("bob" to 200L), endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.AFTER -> {
                            throw IrcCommandException(
                                "CHATHISTORY",
                                "INVALID_MSGREFTYPE",
                                "msgid unsupported",
                            )
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events = if (request.target == "bob") listOf(directMessage("dm-new", 200)) else emptyList(),
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            val result =
                coordinator.resyncNetwork(
                    networkId,
                    db.bufferDao().openTargets(networkId).map { OpenBufferTarget(it.id, it.name, it.pinned) },
                    source,
                )

            assertEquals(HistoryResyncState.Updated(1), result)
            assertEquals(listOf("dm-new"), rows(query.id).mapNotNull { it.msgid })
            assertTrue(source.requests.any { it.subcommand == ChatHistoryRequest.Subcommand.LATEST })
        }

    @Test
    fun accountRerouteDoesNotReviveDismissedQueryForRejectedHistory() =
        runTest {
            fun accountMessage(
                msgid: String,
                time: Long,
                peer: String,
            ) = IrcEvent.ChatMessage(
                ctx = MessageContext(msgid, time, "shared-account", "batch", null),
                kind = IrcEvent.ChatKind.PRIVMSG,
                source = Prefix(peer),
                target = "me",
                text = msgid,
                isSelf = false,
                replyToMsgid = null,
            )
            processor.process(networkId, accountMessage("account-old", 100, "alice"))
            val query = db.bufferDao().byName(networkId, "alice")!!
            db.bufferDao().deleteBuffer(query.id)

            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("newnick" to 90L), endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events =
                                    listOf(
                                        accountMessage("account-older", 90, "newnick"),
                                        IrcEvent.NickChanged(
                                            MessageContext("old-nick", 90, null, "batch", null),
                                            from = "newnick",
                                            to = "oldernick",
                                            isSelf = false,
                                        ),
                                    ),
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            assertEquals(
                HistoryResyncState.UpToDate,
                coordinator.resyncNetwork(networkId, emptyList(), source),
            )
            assertTrue(db.bufferDao().observeById(query.id)!!.dismissed)
            assertEquals(0, db.messageDao().countForBuffer(query.id))
            assertTrue(
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .none { it.bufferId == query.id },
            )
        }

    @Test
    fun accountRerouteFiltersContextAgainstSelectedRoomWithoutMerge() =
        runTest {
            fun accountMessage(
                msgid: String,
                time: Long,
                peer: String,
                account: String,
            ) = IrcEvent.ChatMessage(
                ctx = MessageContext(msgid, time, account, "batch", null),
                kind = IrcEvent.ChatKind.PRIVMSG,
                source = Prefix(peer),
                target = "me",
                text = msgid,
                isSelf = false,
                replyToMsgid = null,
            )
            processor.process(networkId, accountMessage("account-old", 100, "alice", "account-a"))
            val accountA = db.bufferDao().byName(networkId, "alice")!!
            db.bufferDao().deleteBuffer(accountA.id)
            processor.process(networkId, accountMessage("account-b", 80, "newnick", "account-b"))
            val accountB = db.bufferDao().byName(networkId, "newnick")!!
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("newnick" to 90L), endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events =
                                    listOf(
                                        IrcEvent.ChatMessage(
                                            MessageContext("account-a-outgoing", 90, null, "batch", null),
                                            IrcEvent.ChatKind.PRIVMSG,
                                            Prefix("me"),
                                            "newnick",
                                            "old outgoing",
                                            true,
                                            null,
                                        ),
                                        accountMessage("account-a-older", 90, "newnick", "account-a"),
                                        IrcEvent.NickChanged(
                                            MessageContext("old-context", 90, null, "batch", null),
                                            from = "newnick",
                                            to = "oldernick",
                                            isSelf = false,
                                        ),
                                    ),
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            assertEquals(
                HistoryResyncState.UpToDate,
                coordinator.resyncNetwork(networkId, emptyList(), source),
            )
            assertTrue(db.bufferDao().rawById(accountA.id)!!.dismissed)
            assertEquals(listOf("account-b"), rows(accountB.id).mapNotNull { it.msgid })
            assertEquals(0, db.messageDao().countForBuffer(accountA.id))
        }

    @Test
    fun accountRerouteCountsAcceptedHistoryInCanonicalRoom() =
        runTest {
            fun accountMessage(
                msgid: String,
                time: Long,
                peer: String,
            ) = IrcEvent.ChatMessage(
                ctx = MessageContext(msgid, time, "shared-account", "batch", null),
                kind = IrcEvent.ChatKind.PRIVMSG,
                source = Prefix(peer),
                target = "me",
                text = msgid,
                isSelf = false,
                replyToMsgid = null,
            )
            processor.process(networkId, accountMessage("account-old", 100, "alice"))
            val query = db.bufferDao().byName(networkId, "alice")!!
            db.bufferDao().deleteBuffer(query.id)
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("newnick" to 200L), endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events = listOf(accountMessage("account-new", 200, "newnick")),
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            assertEquals(
                HistoryResyncState.Updated(1),
                coordinator.resyncNetwork(networkId, emptyList(), source),
            )
            assertEquals("account-new", db.messageDao().newestMessage(query.id)?.msgid)
        }

    @Test
    fun networkSyncSeedsLatestWhenLiveJoinPrecedesRetainedHistoryEvenWithPriorCursor() =
        runTest {
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
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 1_000L), endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(listOf(message("retained", 500)), emptyList())
                        }

                        else -> {
                            FakeResponse(emptyList(), emptyList())
                        }
                    }
                }

            val result = coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)

            assertEquals(HistoryResyncState.Updated(1), result)
            assertEquals(
                listOf(
                    ChatHistoryRequest.Subcommand.TARGETS,
                    ChatHistoryRequest.Subcommand.LATEST,
                ),
                source.requests.map { it.subcommand },
            )
            assertEquals(0, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.BEFORE })
            assertTrue(rows().any { it.msgid == "retained" })
        }

    @Test
    fun lateLiveJoinCanSeedHistoryAfterTargetsSkippedUnknownChannel() =
        runTest {
            db.bufferDao().deleteBuffer(bufferId)
            val discovery =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#late" to 500L), endOfHistory = true)
                        }

                        else -> {
                            error("departed channel must not be synchronized")
                        }
                    }
                }

            assertEquals(HistoryResyncState.UpToDate, coordinator.resyncNetwork(networkId, emptyList(), discovery))
            assertEquals(null, db.bufferDao().byName(networkId, "#late"))

            processor.process(
                networkId,
                IrcEvent.Joined(
                    MessageContext("join-late", 600, null, null, null),
                    "me",
                    "#late",
                    null,
                    null,
                    true,
                ),
            )
            val joined = db.bufferDao().byName(networkId, "#late")!!
            val latest =
                FakeSource { request ->
                    assertEquals(ChatHistoryRequest.Subcommand.LATEST, request.subcommand)
                    FakeResponse(events = listOf(message("late-history", 500, "#late")), endOfHistory = true)
                }

            assertEquals(
                HistoryResyncState.Updated(1),
                coordinator.reconcileBuffer(networkId, joined.id, "#late", latest),
            )
            assertEquals("late-history", rows(joined.id).first { it.kind == MessageKind.PRIVMSG }.msgid)
        }

    @Test
    fun channelDeletedWhileHistoryIsInFlightIsNotRecreated() =
        runTest {
            val requestStarted = CompletableDeferred<Unit>()
            val releaseResponse = CompletableDeferred<Unit>()
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 500L), endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            requestStarted.complete(Unit)
                            releaseResponse.await()
                            FakeResponse(events = listOf(message("too-late", 500)), endOfHistory = true)
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }
            val result =
                async {
                    coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)
                }
            requestStarted.await()

            db.bufferDao().deleteBuffer(bufferId)
            releaseResponse.complete(Unit)

            assertTrue(result.await() is HistoryResyncState.Failed)
            assertEquals(null, db.bufferDao().byName(networkId, "#chan"))
        }

    @Test
    fun reconnectUsesLastCompletedSyncSoEarlyLiveMessageCannotHideGapOrDuplicate() =
        runTest {
            val base = 1_700_000_000_000L
            processor.process(networkId, message("seed", base + 100))
            syncPrefs.setLastSuccessfulSync(networkId, base + 150)

            // A live line can beat the reconnect catch-up coroutine into Room.
            processor.process(networkId, message("live", base + 300))
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to (base + 300)), endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events = listOf(message("missed", base + 200), message("live", base + 300)),
                                targets = emptyList(),
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            FakeResponse(emptyList(), emptyList())
                        }
                    }
                }

            val result = coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)

            assertEquals(HistoryResyncState.Updated(1), result)
            assertEquals(listOf("live", "missed", "seed"), rows().mapNotNull { it.msgid })
            assertEquals(1, rows().count { it.msgid == "live" })
            assertTrue(
                source.requests.any { it.subcommand == ChatHistoryRequest.Subcommand.LATEST },
            )
        }

    @Test
    fun targetsShortPageContinuesAndDeduplicatesTimestampOverlap() =
        runTest {
            db.bufferDao().deleteBuffer(bufferId)
            var targetsPage = 0
            val source =
                FakeSource(pageLimit = 5) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            when (targetsPage++) {
                                0 -> {
                                    FakeResponse(targets = listOf("New" to 300L))
                                }

                                else -> {
                                    assertEquals(ChatHistorySelectors.timestamp(301), request.bound1)
                                    FakeResponse(
                                        targets = listOf("NEW" to 300L, "old" to 200L),
                                        endOfHistory = true,
                                    )
                                }
                            }
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events =
                                    listOf(
                                        directMessage(
                                            msgid = "latest-${request.target}",
                                            time = if (request.target.equals("New", ignoreCase = true)) 300L else 200L,
                                            peer = request.target,
                                        ),
                                    ),
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            assertEquals(
                HistoryResyncState.Updated(2),
                // Everything-depth: overlap stepping through the fixtures' timestamps needs epoch.
                coordinator.resyncNetwork(networkId, emptyList(), source, initialLookbackMs = null),
            )
            assertEquals(2, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS })
            assertEquals(2, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.LATEST })
            assertEquals(300L, syncPrefs.lastSuccessfulSync(networkId))
        }

    @Test
    fun sojuTargetsWithoutEndMarkerAdvancePastFinalOverlapToEmptyPage() =
        runTest {
            db.bufferDao().deleteBuffer(bufferId)
            val source =
                FakeSource(pageLimit = 3) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                targets =
                                    when (request.bound1) {
                                        ChatHistorySelectors.timestamp(201) -> {
                                            listOf("middle" to 200L, "oldest" to 100L)
                                        }

                                        ChatHistorySelectors.timestamp(101) -> {
                                            listOf("oldest" to 100L)
                                        }

                                        ChatHistorySelectors.timestamp(100) -> {
                                            emptyList()
                                        }

                                        else -> {
                                            listOf("newest" to 300L, "middle" to 200L)
                                        }
                                    },
                            )
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(endOfHistory = true)
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            assertTrue(
                // Everything-depth: the 0.10 short-tie walk below the fixture timestamps needs epoch.
                coordinator.resyncNetwork(networkId, emptyList(), source, initialLookbackMs = null)
                    is HistoryResyncState.Incomplete,
            )
            assertEquals(4, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS })
            assertEquals(3, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.LATEST })
            // The tie is only unprovable, not unfinished: enumeration reached the empty page, so the
            // watermark still advances and the next reconnect does not re-walk the whole window.
            assertEquals(300L, syncPrefs.lastSuccessfulSync(networkId))
        }

    @Test
    fun saturatedTargetsTimestampTieReturnsIncompleteWithoutWatermark() =
        runTest {
            db.bufferDao().deleteBuffer(bufferId)
            val source =
                FakeSource(pageLimit = 2) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#a" to 100L, "#b" to 100L))
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(endOfHistory = true)
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            // Everything-depth: saturating the tie takes a second page, which needs the epoch window.
            val result = coordinator.resyncNetwork(networkId, emptyList(), source, initialLookbackMs = null)

            assertTrue(result is HistoryResyncState.Incomplete)
            assertEquals(null, syncPrefs.lastSuccessfulSync(networkId))
            assertEquals(2, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS })
        }

    @Test
    fun completeNetworkPassPersistsNewestServerPageBoundary() =
        runTest {
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 500L), endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events = listOf(message("server-high-water", 700)),
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            assertEquals(
                HistoryResyncState.Updated(1),
                coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source),
            )
            assertEquals(700L, syncPrefs.lastSuccessfulSync(networkId))
        }

    @Test
    fun thousandMessageAbsencePublishesOneRecentPageAndPersistsOlderGap() =
        runTest {
            processor.process(networkId, message("m1000", 1_000))
            syncPrefs.setLastSuccessfulSync(networkId, 1_001)

            fun page(newest: Int): List<IrcEvent> =
                List(50) { offset ->
                    val ordinal = newest - offset
                    message("m$ordinal", ordinal.toLong())
                }
            val source =
                FakeSource(pageLimit = 100) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 2_000L), endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            assertEquals(50, request.limit)
                            FakeResponse(page(2_000))
                        }

                        else -> {
                            error("automatic recent sync must not issue ${request.subcommand}")
                        }
                    }
                }

            assertEquals(
                HistoryResyncState.Updated(50),
                coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source),
            )
            assertEquals(51, rows(loadSize = 1_200).size)
            assertEquals("m2000", rows(loadSize = 1_200).first().msgid)
            assertEquals(
                listOf(
                    ChatHistoryRequest.Subcommand.TARGETS,
                    ChatHistoryRequest.Subcommand.LATEST,
                ),
                source.requests.map { it.subcommand },
            )
            val gap = db.historyGapDao().forRoom(bufferId).single()
            assertEquals(1_000L, gap.olderServerTime)
            assertEquals(1_951L, gap.newerServerTime)
        }

    @Test
    fun completeNetworkPassCannotMoveWatermarkBackward() =
        runTest {
            syncPrefs.setLastSuccessfulSync(networkId, 1_000)
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 500L), endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.AFTER -> {
                            FakeResponse(primaryMessageCount = 0)
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events = listOf(message("older-server-boundary", 700)),
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            FakeResponse(primaryMessageCount = 0)
                        }
                    }
                }

            assertEquals(
                HistoryResyncState.Updated(1),
                coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source),
            )
            assertEquals(1_000L, syncPrefs.lastSuccessfulSync(networkId))
        }

    @Test
    fun incompleteTargetPassStillAdvancesTheDiscoveryWatermark() =
        runTest {
            // The watermark bounds TARGETS discovery only; a per-target message-level incompleteness
            // must not starve it (an active account always has some target mid-catch-up). Hard
            // discovery failures preserving the watermark are pinned by
            // [saturatedTargetsTimestampTieReturnsIncompleteWithoutWatermark].
            processor.process(networkId, message("seed", 100))
            syncPrefs.setLastSuccessfulSync(networkId, 1_000)
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 2_000L), endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.AFTER -> {
                            FakeResponse(
                                events = emptyList(),
                                oldest = ChatHistoryReference("partial", 1_500),
                                newest = null,
                                primaryMessageCount = 1,
                            )
                        }

                        // Non-terminal page with no usable boundary: a genuinely incomplete target pass.
                        // (An EMPTY terminal page short of the advertised time is no longer incomplete —
                        // it settles as the unreachable-advertised-latest convergence.)
                        else -> {
                            FakeResponse(
                                events = listOf(message("m1500", 1_500)),
                                oldest = null,
                                newest = null,
                            )
                        }
                    }
                }

            val result = coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)

            assertTrue(result is HistoryResyncState.Incomplete)
            assertEquals(2_000L, syncPrefs.lastSuccessfulSync(networkId))
        }

    @Test
    fun transientLatestFailurePreservesWatermark() =
        runTest {
            processor.process(networkId, message("seed", 100))
            syncPrefs.setLastSuccessfulSync(networkId, 1_000)
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 2_000L), endOfHistory = true)
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            throw IOException("temporary transport failure")
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            val result = coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)

            assertTrue(result is HistoryResyncState.Failed)
            assertEquals(1_000L, syncPrefs.lastSuccessfulSync(networkId))
            assertEquals(
                listOf(ChatHistoryRequest.Subcommand.TARGETS, ChatHistoryRequest.Subcommand.LATEST),
                source.requests.map { it.subcommand },
            )
        }

    @Test
    fun serviceTargetRefusingHistoryDoesNotFailTheNetworkPassOrStrandARetryBanner() =
        runTest {
            // A service nick answers FAIL CHATHISTORY INVALID_TARGET permanently. That refusal is
            // scoped to this one target: the rest of the network must still sync, the refused buffer
            // must not offer an unrecoverable retry, and the watermark must advance so reconnect
            // catch-up stops reissuing the same doomed request every 30s.
            val serviceBufferId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "ChanServ",
                        displayName = "ChanServ",
                        type = BufferType.QUERY,
                    ),
                )
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                targets = listOf("ChanServ" to 2_000L, "#chan" to 2_000L),
                                endOfHistory = true,
                            )
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            if (request.target == "ChanServ") {
                                throw IrcCommandException(
                                    "CHATHISTORY",
                                    HistoryPageLoader.INVALID_TARGET,
                                    "Messages could not be retrieved",
                                )
                            } else {
                                FakeResponse(
                                    events = listOf(message("chan-latest", 2_000L)),
                                    endOfHistory = true,
                                )
                            }
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            val result =
                coordinator.resyncNetwork(
                    networkId,
                    openTargets(serviceBufferId to "ChanServ", bufferId to "#chan"),
                    source,
                )

            // The refused target must not poison the whole-network verdict.
            assertEquals(HistoryResyncState.Updated(1), result)
            // The healthy target still syncs even though the refused one came first.
            assertTrue(
                source.requests.any {
                    it.subcommand == ChatHistoryRequest.Subcommand.LATEST && it.target == "#chan"
                },
            )
            assertEquals(listOf("chan-latest"), rows().map { it.msgid })
            // The refused buffer reports Unavailable — a permanent server refusal, not a retryable
            // failure — while the healthy one settles clean and leaves the map entirely.
            assertEquals(
                mapOf(serviceBufferId to HistorySyncStatus.Unavailable),
                coordinator.syncStatuses.value,
            )
            assertEquals(HistorySyncStatus.Unavailable, coordinator.syncStatus(serviceBufferId).first())
            assertEquals(HistorySyncStatus.Idle, coordinator.syncStatus(bufferId).first())
            // Watermark advanced, so the catch-up loop terminates instead of retrying forever.
            assertEquals(2_000L, syncPrefs.lastSuccessfulSync(networkId))
        }

    @Test
    fun automaticNetworkResyncContinuesPastTargetsRequestCap() =
        runTest {
            db.bufferDao().deleteBuffer(bufferId)
            coordinator.targetsRequestLimit = 1
            var targetRequests = 0
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            if (targetRequests++ == 0) {
                                FakeResponse(targets = listOf("alice" to 200L))
                            } else {
                                FakeResponse(targets = listOf("bob" to 100L), endOfHistory = true)
                            }
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(
                                events =
                                    listOf(
                                        directMessage(
                                            msgid = "latest-${request.target}",
                                            time = if (request.target == "alice") 200L else 100L,
                                            peer = request.target,
                                        ),
                                    ),
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            // Everything-depth: chunk-cap continuation below the fixture timestamps needs epoch.
            val result = coordinator.resyncNetwork(networkId, emptyList(), source, initialLookbackMs = null)

            assertEquals(HistoryResyncState.Updated(2), result)
            assertEquals(2, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS })
            val latestTargets =
                source.requests
                    .filter { it.subcommand == ChatHistoryRequest.Subcommand.LATEST }
                    .map { it.target }
                    .toSet()
            assertEquals(setOf("alice", "bob"), latestTargets)
            assertEquals(200L, syncPrefs.lastSuccessfulSync(networkId))
        }

    @Test
    fun equivalentAutomaticRequestsCoalesce() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val source =
                FakeSource {
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
    fun staleInitialSyncStatusCannotReplaceNewerTerminalState() {
        val terminal =
            mapOf<Long, HistorySyncStatus>(
                bufferId to HistorySyncStatus.Partial("newer generation"),
            )

        assertEquals(
            terminal,
            initialSyncStatusIfCurrent(
                current = terminal,
                bufferId = bufferId,
                generation = 1,
                currentGeneration = 2,
                status = HistorySyncStatus.Syncing,
            ),
        )
    }

    @Test
    fun reconcileSessionSupersedesAStaleNetworkPassStatus() =
        runTest {
            processor.process(networkId, message("seed", 100_000))
            val stalled =
                FakeSource(pageLimit = 1) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                targets = listOf("#chan" to 102_000L),
                                endOfHistory = true,
                            )
                        }

                        // Non-terminal with no usable boundary: a genuinely incomplete fetch, so the pass
                        // paints Partial (a terminal shortfall would settle Idle instead).
                        else -> {
                            FakeResponse(
                                events = listOf(message("m101", 101_000)),
                                oldest = null,
                                newest = null,
                            )
                        }
                    }
                }
            coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), stalled)
            assertTrue(coordinator.syncStatuses.value.getValue(bufferId) is HistorySyncStatus.Partial)

            val recovered =
                FakeSource {
                    FakeResponse(events = listOf(message("m102", 102)), endOfHistory = true)
                }
            coordinator.reconcileBuffer(networkId, bufferId, "#chan", recovered)

            // The newer single-buffer session owns the entry, so the stale pass's Partial is gone.
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
            assertEquals(HistorySyncStatus.Idle, coordinator.syncStatus(bufferId).first())
        }

    @Test
    fun networkPassSupersedesAStaleReconcileStatus() =
        runTest {
            // A saturated timestamp boundary is the reconcile path's Incomplete verdict.
            val saturated =
                FakeSource(pageLimit = 1) {
                    FakeResponse(
                        events = listOf(message("m1", 100)),
                        oldest = ChatHistoryReference(null, 100),
                        newest = ChatHistoryReference(null, 100),
                        primaryMessageCount = 1,
                    )
                }
            coordinator.reconcileBuffer(networkId, bufferId, "#chan", saturated)
            assertTrue(coordinator.syncStatuses.value.getValue(bufferId) is HistorySyncStatus.Partial)

            val healthy =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                targets = listOf("#chan" to 200L),
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            FakeResponse(events = listOf(message("m2", 200)), endOfHistory = true)
                        }
                    }
                }
            coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), healthy)

            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
        }

    @Test
    fun skippedUpToDateTargetSettlesBeforeThePassEnds() =
        runTest {
            // #chan's advertised newest is already stored, so the pass skips it without a request. That
            // skip must clear its status immediately instead of parking a spinner until the pass ends.
            db.historyCursorDao().upsert(
                HistoryCursorEntity(roomId = bufferId, newestMsgid = "stored", newestServerTime = 400),
            )
            var duringLaterFetch: Map<Long, HistorySyncStatus>? = null
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                targets = listOf("#chan" to 400L, "bob" to 300L),
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            duringLaterFetch = coordinator.syncStatuses.value
                            FakeResponse(listOf(directMessage("dm", 300)), endOfHistory = true)
                        }
                    }
                }

            coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)

            val bob = requireNotNull(db.bufferDao().byName(networkId, "bob"))
            assertTrue(source.requests.none { it.target == "#chan" })
            // The skipped buffer is already absent while a later target is still on the wire.
            assertEquals(mapOf(bob.id to HistorySyncStatus.Syncing), duringLaterFetch)
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
        }

    @Test
    fun targetDiscoveredMidPassReachesQueuedThenSyncingThenSettles() =
        runTest {
            db.bufferDao().deleteBuffer(bufferId)
            var whileQueued: Map<Long, HistorySyncStatus>? = null
            var whileSyncing: Map<Long, HistorySyncStatus>? = null
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("bob" to 500L), endOfHistory = true)
                        }

                        else -> {
                            whileSyncing = coordinator.syncStatuses.value
                            FakeResponse(listOf(directMessage("dm", 500)), endOfHistory = true)
                        }
                    }
                }
            source.onAvailability = {
                val current = coordinator.syncStatuses.value
                if (current.values.any { it == HistorySyncStatus.Queued }) whileQueued = current
            }

            // No open buffers: this target is registered only because discovery found it mid-pass.
            coordinator.resyncNetwork(networkId, emptyList(), source)

            val bob = requireNotNull(db.bufferDao().byName(networkId, "bob"))
            assertEquals(mapOf(bob.id to HistorySyncStatus.Queued), whileQueued)
            assertEquals(mapOf(bob.id to HistorySyncStatus.Syncing), whileSyncing)
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
        }

    @Test
    fun passFailureMarksOnlyTheInFlightBufferAndDropsStillQueuedOnes() =
        runTest {
            val otherId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "#other",
                        displayName = "#other",
                        type = BufferType.CHANNEL,
                    ),
                )
            var duringFailedFetch: Map<Long, HistorySyncStatus>? = null
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(endOfHistory = true)
                        }

                        else -> {
                            duringFailedFetch = coordinator.syncStatuses.value
                            throw IOException("transport died mid-pass")
                        }
                    }
                }

            val result =
                coordinator.resyncNetwork(
                    networkId,
                    openTargets(bufferId to "#chan", otherId to "#other"),
                    source,
                )

            assertTrue(result is HistoryResyncState.Failed)
            assertEquals(
                mapOf(bufferId to HistorySyncStatus.Syncing, otherId to HistorySyncStatus.Queued),
                duringFailedFetch,
            )
            // A transport failure is retryable, and the catch-up loop will re-run the whole pass: the
            // statuses stay exactly as the attempt painted them so nothing blinks across the backoff.
            assertEquals(
                mapOf(bufferId to HistorySyncStatus.Syncing, otherId to HistorySyncStatus.Queued),
                coordinator.syncStatuses.value,
            )

            // Only when the loop gives up does the pass's verdict land — and still only on the buffer
            // that actually had a request on the wire.
            coordinator.settleNetworkPass(networkId, result, source)
            val settled = coordinator.syncStatuses.value
            assertEquals(setOf(bufferId), settled.keys)
            assertTrue(settled.getValue(bufferId) is HistorySyncStatus.Failed)
            assertEquals(HistorySyncStatus.Idle, coordinator.syncStatus(otherId).first())
        }

    @Test
    fun reconcileBufferPublishesQueuedThenSyncingThenSettles() =
        runTest {
            var whileQueued: Map<Long, HistorySyncStatus>? = null
            var whileSyncing: Map<Long, HistorySyncStatus>? = null
            val source =
                FakeSource {
                    whileSyncing = coordinator.syncStatuses.value
                    FakeResponse(listOf(message("m1", 100)), endOfHistory = true)
                }
            source.onAvailability = {
                val current = coordinator.syncStatuses.value
                if (current.values.any { it == HistorySyncStatus.Queued }) whileQueued = current
            }

            assertEquals(
                HistoryResyncState.Updated(1),
                coordinator.reconcileBuffer(networkId, bufferId, "#chan", source),
            )
            assertEquals(mapOf(bufferId to HistorySyncStatus.Queued), whileQueued)
            assertEquals(mapOf(bufferId to HistorySyncStatus.Syncing), whileSyncing)
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
        }

    @Test
    fun unprovenTargetsTieStillAdvancesTheWatermark() =
        runTest {
            // Soju 0.10.x omits draft/chathistory-end, so a repeated short tie page can never PROVE
            // discovery complete. Enumeration still finished; refusing to advance the watermark on
            // that proof gap re-ran a full-window discovery on every reconnect forever.
            processor.process(networkId, message("seed", 4_000_000))
            var targetsCalls = 0
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            when (++targetsCalls) {
                                // The same short page twice: the unprovable timestamp tie.
                                1, 2 -> FakeResponse(targets = listOf("#chan" to 5_000_000L))

                                else -> FakeResponse(endOfHistory = true)
                            }
                        }

                        else -> {
                            FakeResponse(listOf(message("m5", 5_000_000)), endOfHistory = true)
                        }
                    }
                }

            val result =
                coordinator.resyncNetwork(
                    networkId,
                    openTargets(bufferId to "#chan"),
                    source,
                    initialLookbackMs = null,
                )

            assertTrue(result is HistoryResyncState.Incomplete)
            assertEquals(false, (result as HistoryResyncState.Incomplete).retryRecommended)
            assertEquals(HistorySyncStatus.Idle, coordinator.syncStatus(bufferId).first())
            assertEquals(5_000_000L, syncPrefs.lastSuccessfulSync(networkId))
        }

    @Test
    fun saturatedMsgidlessPageWithZeroInsertsConverges() =
        runTest {
            // A busy msgid-less channel returns a FULL timestamp-only LATEST page every pass. When
            // that page deduplicates entirely into the store, the saturation ambiguity cannot hide
            // anything new at the head, so the target must converge instead of re-failing forever.
            val events =
                (0 until 50).map { i ->
                    message("x", 1_000_000L + i * 1_000).copy(
                        ctx = MessageContext(null, 1_000_000L + i * 1_000, null, "batch", null),
                        text = "msgidless-$i",
                    )
                }
            events.forEach { processor.process(networkId, it) }
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                targets = listOf("#chan" to 90_000_000L),
                                endOfHistory = true,
                            )
                        }

                        ChatHistoryRequest.Subcommand.LATEST -> {
                            FakeResponse(events = events)
                        }

                        else -> {
                            error("unexpected ${request.subcommand}")
                        }
                    }
                }

            val result = coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)

            assertEquals(HistoryResyncState.UpToDate, result)
            assertEquals(HistorySyncStatus.Idle, coordinator.syncStatus(bufferId).first())
            assertEquals(90_000_000L, syncPrefs.lastSuccessfulSync(networkId))
        }

    @Test
    fun sameSecondAdvertisedLatestSkipsWithoutARequest() =
        runTest {
            // Stored server-time tags can carry second precision while TARGETS advertises
            // milliseconds; a stored newest in the same second must not refetch on every pass.
            db.historyCursorDao().upsert(
                HistoryCursorEntity(roomId = bufferId, newestMsgid = "stored", newestServerTime = 400_400),
            )
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 400_900L), endOfHistory = true)
                        }

                        else -> {
                            error("no message fetch expected for a same-second advertisement")
                        }
                    }
                }

            val result = coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)

            assertEquals(HistoryResyncState.UpToDate, result)
            assertTrue(source.requests.none { it.subcommand == ChatHistoryRequest.Subcommand.LATEST })
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
        }

    @Test
    fun dismissSyncStatusClearsASettledPartialBadge() =
        runTest {
            // A non-terminal page with no usable boundary is a genuinely incomplete target fetch,
            // which is what still paints a Partial badge.
            processor.process(networkId, message("seed", 100_000))
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 102_000L), endOfHistory = true)
                        }

                        else -> {
                            FakeResponse(
                                events = listOf(message("m101", 101_000)),
                                oldest = null,
                                newest = null,
                            )
                        }
                    }
                }
            coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)
            assertTrue(coordinator.syncStatus(bufferId).first() is HistorySyncStatus.Partial)

            coordinator.dismissSyncStatus(bufferId)

            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
            assertEquals(HistorySyncStatus.Idle, coordinator.syncStatus(bufferId).first())
        }

    @Test
    fun dismissSyncStatusWinsARaceWithAnInFlightPass() =
        runTest {
            val fetchEntered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 102L), endOfHistory = true)
                        }

                        else -> {
                            fetchEntered.complete(Unit)
                            release.await()
                            FakeResponse(listOf(message("m101", 101)), endOfHistory = true)
                        }
                    }
                }

            val pass = async { coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source) }
            fetchEntered.await()
            // Dismissed while the buffer's request is on the wire: the pass's later settle for this
            // buffer carries a stale generation, so the dismissal must not be overwritten.
            coordinator.dismissSyncStatus(bufferId)
            release.complete(Unit)
            pass.await()

            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
        }

    private suspend fun insertChannels(names: List<String>): List<Long> =
        names.map { name ->
            db.bufferDao().insert(
                BufferEntity(networkId = networkId, name = name, displayName = name, type = BufferType.CHANNEL),
            )
        }

    @Test
    fun labeledResponsePassRunsTargetsInParallelBoundedByWireWidth() =
        runTest {
            val width = AdaptiveFanOut.INITIAL_WIDTH
            val names = (1..width + 2).map { "#chan$it" }
            val ids = insertChannels(names)
            val entered = AtomicInteger()
            val wireWidthReached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val source =
                FakeSource(supportsConcurrent = true) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(endOfHistory = true)
                        }

                        else -> {
                            if (entered.incrementAndGet() == width) wireWidthReached.complete(Unit)
                            release.await()
                            FakeResponse(
                                listOf(message("m-${request.target}", 100, target = request.target)),
                                endOfHistory = true,
                            )
                        }
                    }
                }

            val pass = async { coordinator.resyncNetwork(networkId, openTargets(ids.zip(names)), source) }
            wireWidthReached.await()

            // Exactly the starting fan-out width is on the wire at once; the rest wait for a slot.
            assertEquals(
                width,
                source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.LATEST },
            )
            release.complete(Unit)
            assertEquals(HistoryResyncState.Updated(names.size), pass.await())
            assertEquals(
                names.toSet(),
                source.requests
                    .filter { it.subcommand == ChatHistoryRequest.Subcommand.LATEST }
                    .map { it.target }
                    .toSet(),
            )
        }

    @Test
    fun passWithoutLabeledResponseStaysStrictlySequential() =
        runTest {
            val names = listOf("#a", "#b", "#c")
            val ids = insertChannels(names)
            val active = AtomicInteger()
            val maxActive = AtomicInteger()
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(endOfHistory = true)
                        }

                        else -> {
                            maxActive.updateAndGet { maxOf(it, active.incrementAndGet()) }
                            // Give any (incorrect) fan-out sibling the chance to overlap before finishing.
                            yield()
                            active.decrementAndGet()
                            FakeResponse(
                                listOf(message("m-${request.target}", 100, target = request.target)),
                                endOfHistory = true,
                            )
                        }
                    }
                }

            assertEquals(
                HistoryResyncState.Updated(3),
                coordinator.resyncNetwork(networkId, openTargets(ids.zip(names)), source),
            )
            assertEquals(1, maxActive.get())
            // Sequential order is the open-buffer (newest-first merge) order, one at a time.
            assertEquals(listOf("*", "#a", "#b", "#c"), source.requests.map { it.target })
        }

    @Test
    fun parallelPassFoldsAccumulatorsAcrossTargets() =
        runTest {
            val (incompleteId, cleanId) = insertChannels(listOf("#inc", "#ok"))
            val source =
                FakeSource(supportsConcurrent = true) { request ->
                    when {
                        request.subcommand == ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(endOfHistory = true)
                        }

                        // A non-terminal page with no usable oldest boundary: inserted but Incomplete.
                        request.target == "#inc" -> {
                            FakeResponse(
                                events = listOf(message("inc1", 100, target = "#inc")),
                                oldest = null,
                                newest = null,
                            )
                        }

                        else -> {
                            FakeResponse(listOf(message("ok1", 200, target = "#ok")), endOfHistory = true)
                        }
                    }
                }

            val result =
                coordinator.resyncNetwork(
                    networkId,
                    openTargets(incompleteId to "#inc", cleanId to "#ok"),
                    source,
                )

            // Inserted counts fold across both targets; the incomplete target's reason wins the pass.
            assertEquals(
                HistoryResyncState.Incomplete(2, "CHATHISTORY LATEST returned no usable oldest boundary"),
                result,
            )
            // Discovery completed, so the watermark still advances to the clean target's high water.
            assertEquals(200L, syncPrefs.lastSuccessfulSync(networkId))
        }

    @Test
    fun cleanParallelPassAdvancesTheWatermarkToTheMaxHighWater() =
        runTest {
            val (aId, bId) = insertChannels(listOf("#a", "#b"))
            val source =
                FakeSource(supportsConcurrent = true) { request ->
                    when {
                        request.subcommand == ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(endOfHistory = true)
                        }

                        request.target == "#a" -> {
                            FakeResponse(listOf(message("a1", 150, target = "#a")), endOfHistory = true)
                        }

                        else -> {
                            FakeResponse(listOf(message("b1", 250, target = "#b")), endOfHistory = true)
                        }
                    }
                }

            assertEquals(
                HistoryResyncState.Updated(2),
                coordinator.resyncNetwork(networkId, openTargets(aId to "#a", bId to "#b"), source),
            )
            assertEquals(250L, syncPrefs.lastSuccessfulSync(networkId))
        }

    @Test
    fun parallelPassPublishesConcurrentSyncingStatusesAndSettlesIndependently() =
        runTest {
            val names = listOf("#a", "#b", "#c")
            val ids = insertChannels(names)
            val idByName = names.zip(ids).toMap()
            val releases = names.associateWith { CompletableDeferred<Unit>() }
            val syncing = AtomicInteger()
            val allSyncing = CompletableDeferred<Unit>()
            val source =
                FakeSource(supportsConcurrent = true) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(endOfHistory = true)
                        }

                        else -> {
                            if (syncing.incrementAndGet() == 3) allSyncing.complete(Unit)
                            releases.getValue(request.target).await()
                            FakeResponse(
                                listOf(message("m-${request.target}", 100, target = request.target)),
                                endOfHistory = true,
                            )
                        }
                    }
                }

            val pass = async { coordinator.resyncNetwork(networkId, openTargets(ids.zip(names)), source) }
            allSyncing.await()
            assertEquals(
                ids.associateWith { HistorySyncStatus.Syncing as HistorySyncStatus },
                coordinator.syncStatuses.value,
            )

            releases.getValue("#a").complete(Unit)
            // #a settles and clears on its own while its siblings are still on the wire.
            coordinator.syncStatuses.first { idByName.getValue("#a") !in it }
            assertEquals(
                setOf(idByName.getValue("#b"), idByName.getValue("#c")),
                coordinator.syncStatuses.value.keys,
            )

            releases.getValue("#b").complete(Unit)
            releases.getValue("#c").complete(Unit)
            assertEquals(HistoryResyncState.Updated(3), pass.await())
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
        }

    @Test
    fun failedTargetCancelsInFlightSiblingsAndPaintsThemWithThePassVerdict() =
        runTest {
            // Exactly the fan-out width: all three targets go on the wire together, so no queued
            // sibling can take a permit a cancellation frees mid-teardown. Permit order on a
            // multithreaded dispatcher is arbitrary, so roles are assigned by arrival: the first two
            // entrants block, the third fails the pass.
            val names = listOf("#a", "#b", "#c")
            val ids = insertChannels(names)
            val idByName = names.zip(ids).toMap()
            val enteredTargets = ConcurrentHashMap.newKeySet<String>()
            val arrival = AtomicInteger()
            val twoBlocked = CompletableDeferred<Unit>()
            val cancelledTargets = ConcurrentHashMap.newKeySet<String>()
            val source =
                FakeSource(supportsConcurrent = true) { request ->
                    when {
                        request.subcommand == ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(endOfHistory = true)
                        }

                        else -> {
                            enteredTargets += request.target
                            val n = arrival.incrementAndGet()
                            if (n <= 2) {
                                if (n == 2) twoBlocked.complete(Unit)
                                try {
                                    awaitCancellation()
                                } catch (cancelled: CancellationException) {
                                    cancelledTargets += request.target
                                    throw cancelled
                                }
                            } else {
                                twoBlocked.await()
                                throw IOException("transport died mid-pass")
                            }
                        }
                    }
                }

            val result = coordinator.resyncNetwork(networkId, openTargets(ids.zip(names)), source)

            assertTrue(result is HistoryResyncState.Failed)
            // The failing target aborted the pass and both in-flight siblings were cancelled with it.
            assertEquals(3, enteredTargets.size)
            assertEquals(2, cancelledTargets.size)
            assertTrue(enteredTargets.containsAll(cancelledTargets))
            // Every buffer that had a request on the wire wears the pass verdict once the catch-up loop
            // gives up.
            coordinator.settleNetworkPass(networkId, result, source)
            val settled = coordinator.syncStatuses.value
            assertEquals(enteredTargets.map { idByName.getValue(it) }.toSet(), settled.keys)
            assertTrue(settled.values.all { it is HistorySyncStatus.Failed })
        }

    @Test
    fun aTargetThatNeverReachedTheWireIsDroppedRatherThanPaintedWithThePassFailure() =
        runTest {
            // Sequential pass: the first target fails, so the second never starts. Serialization makes
            // "never reached the wire" exact, which the concurrent path cannot promise — a cancelled
            // sibling frees its permit, so a queued target can still slip onto the wire mid-teardown.
            val names = listOf("#a", "#b")
            val ids = insertChannels(names)
            val enteredTargets = ConcurrentHashMap.newKeySet<String>()
            val source =
                FakeSource(supportsConcurrent = false) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(endOfHistory = true)
                        }

                        else -> {
                            enteredTargets += request.target
                            throw IOException("transport died mid-pass")
                        }
                    }
                }

            val result = coordinator.resyncNetwork(networkId, openTargets(ids.zip(names)), source)

            assertTrue(result is HistoryResyncState.Failed)
            assertEquals(setOf("#a"), enteredTargets)
            coordinator.settleNetworkPass(networkId, result, source)
            val settled = coordinator.syncStatuses.value
            assertEquals(setOf(ids.first()), settled.keys)
            assertTrue(settled.values.all { it is HistorySyncStatus.Failed })
        }

    @Test
    fun staleConnectionDuringAParallelPassLeavesEveryBufferAwaitingConnection() =
        runTest {
            val names = listOf("#a", "#b")
            val ids = insertChannels(names)
            val current = AtomicBoolean(true)
            val entered = AtomicInteger()
            val bothIn = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val source =
                FakeSource(supportsConcurrent = true) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(endOfHistory = true)
                        }

                        else -> {
                            if (entered.incrementAndGet() == 2) bothIn.complete(Unit)
                            release.await()
                            FakeResponse(
                                listOf(message("m-${request.target}", 100, target = request.target)),
                                endOfHistory = true,
                            )
                        }
                    }
                }

            val pass =
                async {
                    coordinator.resyncNetwork(networkId, openTargets(ids.zip(names)), source, isCurrent = { current.get() })
                }
            bothIn.await()
            current.set(false)
            release.complete(Unit)

            assertEquals(HistoryResyncState.Failed("Connection changed; try again"), pass.await())
            assertEquals(
                ids.associateWith { HistorySyncStatus.AwaitingConnection as HistorySyncStatus },
                coordinator.syncStatuses.value,
            )
            // The aggregate progress entry retires with the pass; only per-buffer waiting survives.
            assertEquals(emptyMap<Long, SyncPassProgress>(), coordinator.passProgress.value)
        }

    @Test
    fun backfillPublishesNoPerBufferSyncStatus() =
        runTest {
            db.historyBackfillCursorDao().seed(
                io.github.trevarj.motd.data.db
                    .HistoryBackfillCursorEntity(networkId, upperBound = 1_000),
            )
            val observed = mutableListOf<Map<Long, HistorySyncStatus>>()
            val source =
                FakeSource { request ->
                    observed += coordinator.syncStatuses.value
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("old-friend" to 500L), endOfHistory = true)
                        }

                        else -> {
                            FakeResponse(
                                events = listOf(directMessage("old1", 500, peer = "old-friend")),
                                endOfHistory = true,
                            )
                        }
                    }
                }
            source.onAvailability = { observed += coordinator.syncStatuses.value }

            coordinator.backfillTargets(networkId, source) { true }

            // The paced background seed does the same per-target work as the reconnect pass; its
            // invisibility is the whole point, so every observation must be empty.
            assertTrue(db.bufferDao().byName(networkId, "old-friend") != null)
            assertTrue(observed.isNotEmpty())
            assertTrue(observed.all { it.isEmpty() })
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
        }

    @Test
    fun automaticRetryBackoffRemainsBounded() {
        assertEquals(2_000L, catchUpRetryDelayMs(0))
        assertEquals(4_000L, catchUpRetryDelayMs(1))
        assertEquals(30_000L, catchUpRetryDelayMs(20))
        val settledPartial =
            HistoryResyncState.Incomplete(
                inserted = 1,
                reason = "latest already local",
            )
        assertFalse(shouldRetryIncompleteCatchUp(settledPartial))
        assertTrue(terminalCatchUpCanVouchForConnection(settledPartial))
        assertFalse(terminalCatchUpCanVouchForConnection(settledPartial.copy(retryRecommended = true)))
    }

    private fun String.timestampBoundMillis(): Long =
        java.time.Instant
            .parse(removePrefix("timestamp="))
            .toEpochMilli()

    @Test
    fun firstSyncBoundsDiscoveryWindowAndSeedsBackfillCursor() =
        runTest {
            val source = FakeSource { FakeResponse(endOfHistory = true) }
            coordinator.resyncNetwork(networkId, emptyList(), source)

            val targetsRequest =
                source.requests
                    .single { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS }
            val lower = requireNotNull(targetsRequest.bound2).timestampBoundMillis()
            val expected = System.currentTimeMillis() - HistoryResyncCoordinator.INITIAL_SYNC_LOOKBACK_MS
            // The first pass enumerates a bounded recent window, never epoch.
            assertTrue(lower > 0)
            assertTrue(kotlin.math.abs(lower - expected) < 60_000)

            val cursor = requireNotNull(db.historyBackfillCursorDao().byNetwork(networkId))
            assertFalse(cursor.complete)
            assertEquals(lower + 1, cursor.upperBound)
        }

    @Test
    fun everythingDepthEnumeratesFromEpochWithoutBackfillCursor() =
        runTest {
            val source = FakeSource { FakeResponse(endOfHistory = true) }
            coordinator.resyncNetwork(networkId, emptyList(), source, initialLookbackMs = null)

            val targetsRequest =
                source.requests
                    .single { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS }
            assertEquals(0L, requireNotNull(targetsRequest.bound2).timestampBoundMillis())
            // The unbounded pass already reached epoch, so nothing older remains to backfill.
            assertNull(db.historyBackfillCursorDao().byNetwork(networkId))
        }

    @Test
    fun manualPassPublishesProgressWhileItsWorkRunsAndReleasesItAfter() =
        runTest {
            val seen = mutableListOf<SyncPassProgress?>()
            coordinator.manualPass(networkId, listOf(bufferId)) { id ->
                assertEquals(bufferId, id)
                seen += coordinator.passProgress.value[networkId]
            }
            // The chat-list bar reads pass progress; per-buffer transient statuses stay withheld
            // under the engine's anti-flash policy and are not what a manual fetch relies on.
            assertEquals(listOf(SyncPassProgress(total = 1, settled = 0, backfill = true)), seen)
            assertNull(coordinator.passProgress.value[networkId])
            assertNull(coordinator.syncStatuses.value[bufferId])
        }

    @Test
    fun aCancelledManualPassLeavesNoProgressAndNoErrorBadge() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val pass =
                async {
                    coordinator.manualPass(networkId, listOf(bufferId)) {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
            started.await()
            assertEquals(SyncPassProgress(total = 1, settled = 0, backfill = true), coordinator.passProgress.value[networkId])

            pass.cancel()
            pass.join()

            assertNull(coordinator.passProgress.value[networkId])
            assertNull(coordinator.syncStatuses.value[bufferId])
        }

    @Test
    fun manualResyncDiscoversFromTheRequestedBoundNotTheWatermark() =
        runTest {
            val source = FakeSource { FakeResponse(endOfHistory = true) }
            val watermark = System.currentTimeMillis() - 60_000
            syncPrefs.setLastSuccessfulSync(networkId, watermark)

            fun targetsLower(): Long =
                requireNotNull(source.requests.single { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS }.bound2)
                    .timestampBoundMillis()

            coordinator.resyncNetwork(networkId, emptyList(), source)
            assertTrue(kotlin.math.abs(targetsLower() - watermark) < 60_000)
            source.requests.clear()

            val requested = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1_000
            coordinator.resyncNetwork(networkId, emptyList(), source, discoveryLowerMs = requested)
            assertTrue(kotlin.math.abs(targetsLower() - requested) < 60_000)
        }

    @Test
    fun resyncSkipsTargetWhoseAdvertisedLatestIsAlreadyStored() =
        runTest {
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 400L), endOfHistory = true)
                        }

                        else -> {
                            FakeResponse(events = listOf(message("m1", 400)), endOfHistory = true)
                        }
                    }
                }
            coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)
            assertTrue(source.requests.any { it.subcommand == ChatHistoryRequest.Subcommand.LATEST })

            // Clear the watermark: the stored room cursor alone must suppress the refetch, exactly the
            // first-run retry and backfill situation.
            syncPrefs.clear(networkId)
            source.requests.clear()
            coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)
            assertTrue(source.requests.none { it.subcommand == ChatHistoryRequest.Subcommand.LATEST })
        }

    @Test
    fun firstHistorySeedStartsRead() =
        runTest {
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("bob" to 400L), endOfHistory = true)
                        }

                        else -> {
                            FakeResponse(
                                events = listOf(directMessage("d1", 300), directMessage("d2", 400)),
                                endOfHistory = true,
                            )
                        }
                    }
                }
            coordinator.resyncNetwork(networkId, emptyList(), source)

            // Imported backlog predating the app must not badge...
            val row =
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .single { it.displayName == "bob" }
            assertEquals(0, row.unreadCount)

            // ...while genuinely new live activity still does.
            processor.process(networkId, directMessage("live-1", 500))
            val after =
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .single { it.displayName == "bob" }
            assertEquals(1, after.unreadCount)
        }

    @Test
    fun backfillSeedsDiscoveredTargetsAndCompletes() =
        runTest {
            db.historyBackfillCursorDao().seed(
                io.github.trevarj.motd.data.db
                    .HistoryBackfillCursorEntity(networkId, upperBound = 1_000),
            )
            val source =
                FakeSource { request ->
                    when {
                        request.subcommand == ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("old-friend" to 500L), endOfHistory = true)
                        }

                        request.target == "old-friend" -> {
                            FakeResponse(
                                events = listOf(directMessage("old1", 500, peer = "old-friend")),
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }
            coordinator.backfillTargets(networkId, source) { true }

            val targetsRequest =
                source.requests
                    .first { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS }
            assertEquals(1_000L, requireNotNull(targetsRequest.bound1).timestampBoundMillis())
            val buffer = requireNotNull(db.bufferDao().byName(networkId, "old-friend"))
            assertEquals(1, db.messageDao().countForBuffer(buffer.id))
            assertTrue(requireNotNull(db.historyBackfillCursorDao().byNetwork(networkId)).complete)
            // Backfill never advances the reconnect watermark.
            assertEquals(null, syncPrefs.lastSuccessfulSync(networkId))
        }

    @Test
    fun backfillPersistsProgressAndResumesAfterTransportFailure() =
        runTest {
            db.historyBackfillCursorDao().seed(
                io.github.trevarj.motd.data.db
                    .HistoryBackfillCursorEntity(networkId, upperBound = 1_000),
            )
            var cutOnce = true
            val source =
                FakeSource(pageLimit = 1) { request ->
                    when {
                        request.subcommand == ChatHistoryRequest.Subcommand.TARGETS -> {
                            val upper = requireNotNull(request.bound1).timestampBoundMillis()
                            when {
                                upper >= 1_000 -> {
                                    FakeResponse(targets = listOf("dm-a" to 900L))
                                }

                                cutOnce -> {
                                    cutOnce = false
                                    throw IOException("connection cut")
                                }

                                else -> {
                                    FakeResponse(targets = listOf("dm-b" to 800L), endOfHistory = true)
                                }
                            }
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            // First run enumerates one page, seeds it, persists the boundary, then dies on the wire.
            coordinator.backfillTargets(networkId, source) { true }
            val interrupted = requireNotNull(db.historyBackfillCursorDao().byNetwork(networkId))
            assertFalse(interrupted.complete)
            assertEquals(901, interrupted.upperBound)
            assertTrue(db.bufferDao().byName(networkId, "dm-a") != null)

            // The next session resumes below the persisted boundary instead of restarting.
            coordinator.backfillTargets(networkId, source) { true }
            assertTrue(requireNotNull(db.historyBackfillCursorDao().byNetwork(networkId)).complete)
            assertTrue(db.bufferDao().byName(networkId, "dm-b") != null)
        }

    // -- optimistic waiting state, adoption, priority, and aggregate progress -------------------

    private fun refusingSource(advertised: Long) =
        FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS -> {
                    FakeResponse(targets = listOf("#chan" to advertised), endOfHistory = true)
                }

                else -> {
                    throw IrcCommandException(
                        "CHATHISTORY",
                        HistoryPageLoader.INVALID_TARGET,
                        "Messages could not be retrieved",
                    )
                }
            }
        }

    private class FakeForegroundBuffer(
        bufferId: Long?,
    ) : ForegroundBufferTracker {
        private val state = MutableStateFlow(bufferId)
        override val foregroundBufferId: StateFlow<Long?> = state

        override fun set(bufferId: Long?) {
            state.value = bufferId
        }
    }

    @Test
    fun markAwaitingConnectionPublishesWaitingForEveryOpenBuffer() =
        runTest {
            val otherId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "#other",
                        displayName = "#other",
                        type = BufferType.CHANNEL,
                    ),
                )

            coordinator.markAwaitingConnection(networkId, listOf(bufferId, otherId))

            // Foregrounding with no usable connection is instant feedback, not an error state.
            assertEquals(
                mapOf(
                    bufferId to HistorySyncStatus.AwaitingConnection,
                    otherId to HistorySyncStatus.AwaitingConnection,
                ),
                coordinator.syncStatuses.value,
            )
        }

    @Test
    fun clearAwaitingConnectionDropsWaitingEntriesOnADeliberateDisconnect() =
        runTest {
            coordinator.markAwaitingConnection(networkId, listOf(bufferId))

            coordinator.clearAwaitingConnection(networkId)

            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
        }

    @Test
    fun markAwaitingConnectionLeavesAPermanentlyRefusedTargetAlone() =
        runTest {
            coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), refusingSource(2_000L))
            assertEquals(
                mapOf(bufferId to HistorySyncStatus.Unavailable),
                coordinator.syncStatuses.value,
            )

            coordinator.markAwaitingConnection(networkId, listOf(bufferId))

            // The server will never serve this target; it is not waiting for a connection.
            assertEquals(
                mapOf(bufferId to HistorySyncStatus.Unavailable),
                coordinator.syncStatuses.value,
            )
        }

    @Test
    fun aRealPassAdoptsTheWaitingStateWithoutAnIdleGap() =
        runTest {
            coordinator.markAwaitingConnection(networkId, listOf(bufferId))
            val seen = mutableListOf<HistorySyncStatus>()
            var whileSyncing: HistorySyncStatus? = null
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 200L), endOfHistory = true)
                        }

                        else -> {
                            whileSyncing = coordinator.syncStatuses.value[bufferId]
                            FakeResponse(listOf(message("m1", 200)), endOfHistory = true)
                        }
                    }
                }
            source.onAvailability = {
                seen += coordinator.syncStatuses.value[bufferId] ?: HistorySyncStatus.Idle
            }

            coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)

            // Waiting until the pass registers the buffer, then Queued — never an Idle flash between
            // the optimistic mark and the pass that adopts it.
            assertEquals(HistorySyncStatus.AwaitingConnection, seen.first())
            assertTrue(HistorySyncStatus.Queued in seen)
            assertTrue(seen.none { it == HistorySyncStatus.Idle })
            assertEquals(HistorySyncStatus.Syncing, whileSyncing)
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
        }

    @Test
    fun adoptionClearsWaitingBuffersThatClosedBeforeThePassRan() =
        runTest {
            val closedId = bufferId + 10_000
            coordinator.markAwaitingConnection(networkId, listOf(bufferId, closedId))
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> FakeResponse(endOfHistory = true)
                        else -> FakeResponse(listOf(message("m1", 200)), endOfHistory = true)
                    }
                }

            coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)

            // The buffer the pass never queued was closed between foreground and reconnect; leaving it
            // marked would strand a waiting badge on a row that no longer syncs.
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
        }

    @Test
    fun aRefusedTargetStaysUnavailableThroughARequeueAndClearsOnRecovery() =
        runTest {
            coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), refusingSource(2_000L))
            assertEquals(
                mapOf(bufferId to HistorySyncStatus.Unavailable),
                coordinator.syncStatuses.value,
            )

            val requeued = refusingSource(2_000L)
            val duringRequeue = mutableListOf<HistorySyncStatus?>()
            requeued.onAvailability = { duringRequeue += coordinator.syncStatuses.value[bufferId] }
            coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), requeued)

            // Registered silently: the pass owns the buffer (so it can still settle it) but publishes
            // neither Queued nor Syncing, so re-refusing the same target every pass never churns.
            assertTrue(duringRequeue.isNotEmpty())
            assertTrue(duringRequeue.all { it == HistorySyncStatus.Unavailable })
            assertEquals(
                mapOf(bufferId to HistorySyncStatus.Unavailable),
                coordinator.syncStatuses.value,
            )

            val recovered =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 3_000L), endOfHistory = true)
                        }

                        else -> {
                            FakeResponse(listOf(message("m1", 3_000)), endOfHistory = true)
                        }
                    }
                }
            coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), recovered)

            // The server serves the target again, so the terminal badge clears on its own.
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
        }

    @Test
    fun passProgressCountsSettledBuffersAndRetiresWithThePass() =
        runTest {
            val names = listOf("#a", "#b")
            val ids = insertChannels(names)
            val releases = names.associateWith { CompletableDeferred<Unit>() }
            val syncing = AtomicInteger()
            val bothSyncing = CompletableDeferred<Unit>()
            val source =
                FakeSource(supportsConcurrent = true) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(endOfHistory = true)
                        }

                        else -> {
                            if (syncing.incrementAndGet() == 2) bothSyncing.complete(Unit)
                            releases.getValue(request.target).await()
                            FakeResponse(
                                listOf(message("m-${request.target}", 100, target = request.target)),
                                endOfHistory = true,
                            )
                        }
                    }
                }

            val pass = async { coordinator.resyncNetwork(networkId, openTargets(ids.zip(names)), source) }
            bothSyncing.await()
            // The denominator is engine-owned: settled buffers leave the status map entirely, so the
            // UI could never reconstruct it.
            assertEquals(
                mapOf(networkId to SyncPassProgress(total = 2, settled = 0)),
                coordinator.passProgress.value,
            )

            releases.getValue("#a").complete(Unit)
            coordinator.passProgress.first { it[networkId]?.settled == 1 }

            releases.getValue("#b").complete(Unit)
            assertEquals(HistoryResyncState.Updated(2), pass.await())
            assertEquals(emptyMap<Long, SyncPassProgress>(), coordinator.passProgress.value)
        }

    @Test
    fun passProgressSurvivesARetryableFailureAndRetiresWhenTheLoopGivesUp() =
        runTest {
            val otherId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "#other",
                        displayName = "#other",
                        type = BufferType.CHANNEL,
                    ),
                )
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> FakeResponse(endOfHistory = true)
                        else -> throw IOException("transport died mid-pass")
                    }
                }

            val result =
                coordinator.resyncNetwork(
                    networkId,
                    openTargets(bufferId to "#chan", otherId to "#other"),
                    source,
                )

            assertTrue(result is HistoryResyncState.Failed)
            // Frozen but truthful through the backoff; resetting to 0/N every attempt would flash.
            assertEquals(
                mapOf(networkId to SyncPassProgress(total = 2, settled = 0)),
                coordinator.passProgress.value,
            )

            coordinator.settleNetworkPass(networkId, result, source)
            assertEquals(emptyMap<Long, SyncPassProgress>(), coordinator.passProgress.value)
        }

    @Test
    fun retiringANetworkMidPassLeavesNothingPaintedWhenTheLatePassTerminates() =
        runTest {
            val names = listOf("#a", "#b")
            val ids = insertChannels(names)
            val onTheWire = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val current = AtomicBoolean(true)
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(endOfHistory = true)
                        }

                        else -> {
                            if (!onTheWire.isCompleted) onTheWire.complete(Unit)
                            release.await()
                            FakeResponse(
                                listOf(message("m-${request.target}", 100, target = request.target)),
                                endOfHistory = true,
                            )
                        }
                    }
                }
            coordinator.markAwaitingConnection(networkId, ids)

            val pass =
                async {
                    coordinator.resyncNetwork(
                        networkId,
                        openTargets(ids.zip(names)),
                        source,
                        isCurrent = { current.get() },
                    )
                }
            onTheWire.await()
            // The user disconnects the network mid-pass. The pass runs in the coordinator's own scope,
            // so it outlives that call and still has its terminal to run.
            coordinator.retireNetwork(networkId, source)
            current.set(false)
            release.complete(Unit)
            pass.await()

            // Nothing is going to reconnect this network: a late abandon must not repaint every
            // unsettled buffer as waiting, nor leave a frozen header count behind.
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
            assertEquals(emptyMap<Long, SyncPassProgress>(), coordinator.passProgress.value)
        }

    @Test
    fun retiringWithoutAConnectionSilencesASupersededClientsLatePass() =
        runTest {
            val otherId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "#other",
                        displayName = "#other",
                        type = BufferType.CHANNEL,
                    ),
                )
            val onTheWire = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val current = AtomicBoolean(true)
            // The superseded client's pass: still on the wire for #chan when everything else happens.
            // Both connections advertise labeled responses so the successor's own request is not queued
            // behind the parked one on the per-network wire gate.
            val stale =
                FakeSource(supportsConcurrent = true) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(endOfHistory = true)
                        }

                        else -> {
                            onTheWire.complete(Unit)
                            release.await()
                            FakeResponse(listOf(message("m1", 100)), endOfHistory = true)
                        }
                    }
                }
            val successor =
                FakeSource(supportsConcurrent = true) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(endOfHistory = true)
                        }

                        else -> {
                            FakeResponse(
                                listOf(message("m-other", 100, target = "#other")),
                                endOfHistory = true,
                            )
                        }
                    }
                }

            val stalePass =
                async {
                    coordinator.resyncNetwork(
                        networkId,
                        openTargets(bufferId to "#chan"),
                        stale,
                        isCurrent = { current.get() },
                    )
                }
            onTheWire.await()
            // A stacked reconnect: the successor's pass takes the network's session slot, so the stale
            // pass is no longer the one a retirement can reach through that slot.
            coordinator.resyncNetwork(networkId, openTargets(otherId to "#other"), successor)
            // The user disconnects, and the actor is already gone — the caller has no client to name.
            coordinator.retireNetwork(networkId, null)
            current.set(false)
            release.complete(Unit)
            stalePass.await()

            // Nothing is going to reconnect this network: the stale pass's abandon must not repaint its
            // survivors as waiting after the disconnect cleared them.
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
            assertEquals(emptyMap<Long, SyncPassProgress>(), coordinator.passProgress.value)
        }

    @Test
    fun retiringANetworkDropsTheLoadersWireGateForIt() =
        runTest {
            // The loader's per-network gates are process-lifetime, and retirement is the only thing that
            // reclaims one. Until it did, a request parked on the socket that just went away kept its
            // permit, and the connection replacing it queued behind a page that was never coming.
            val loader = HistoryPageLoader(processor)
            coordinator = HistoryResyncCoordinator(db, processor, syncPrefs, backgroundScope, loader = loader)
            var entered = 0
            val parked = CompletableDeferred<Unit>()
            val refs = setOf(HistoryReferenceType.TIMESTAMP)
            val source =
                object : HistoryPageLoader.HistorySource {
                    override suspend fun availability(): HistoryAvailability = HistoryAvailability.Ready(refs, 100)

                    override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                        entered++
                        if (entered == 1) parked.await()
                        return ChatHistoryResponse.Messages(
                            events = emptyList(),
                            oldest = null,
                            newest = null,
                            endOfHistory = true,
                        )
                    }
                }

            fun latest(target: String) = ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, target, limit = 50)

            val stranded =
                async {
                    loader.fetchMessages(networkId, source, latest("#chan"), refs, msgidAllowed = false, timeoutMs = 30_000)
                }
            runCurrent()
            assertEquals(1, entered)

            coordinator.retireNetwork(networkId, null)

            val successor =
                async {
                    loader.fetchMessages(networkId, source, latest("#chan"), refs, msgidAllowed = false, timeoutMs = 30_000)
                }
            runCurrent()
            // The replacement connection is on the wire while the retired one's request is still parked.
            assertEquals(2, entered)
            successor.await()

            parked.complete(Unit)
            stranded.await()
        }

    @Test
    fun retiringWithoutAConnectionLeavesTheNextReconnectsPassPublishing() =
        runTest {
            // A retirement that could not name its connection covers the passes that already exist, not
            // the network: a later reconnect has to sync and paint statuses normally.
            coordinator.markAwaitingConnection(networkId, listOf(bufferId))
            coordinator.retireNetwork(networkId, null)
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)

            var whileSyncing: HistorySyncStatus? = null
            var progressWhileSyncing: Map<Long, SyncPassProgress>? = null
            val reconnected =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(endOfHistory = true)
                        }

                        else -> {
                            whileSyncing = coordinator.syncStatuses.value[bufferId]
                            progressWhileSyncing = coordinator.passProgress.value
                            FakeResponse(listOf(message("m1", 100)), endOfHistory = true)
                        }
                    }
                }

            assertEquals(
                HistoryResyncState.Updated(1),
                coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), reconnected),
            )

            assertEquals(HistorySyncStatus.Syncing, whileSyncing)
            assertEquals(
                mapOf(networkId to SyncPassProgress(total = 1, settled = 0)),
                progressWhileSyncing,
            )
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
            assertEquals(emptyMap<Long, SyncPassProgress>(), coordinator.passProgress.value)
        }

    @Test
    fun retiringANetworkSettlesAPassSuspendedForRetry() =
        runTest {
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> FakeResponse(endOfHistory = true)
                        else -> throw IOException("transport died mid-pass")
                    }
                }
            assertTrue(
                coordinator.resyncNetwork(
                    networkId,
                    openTargets(bufferId to "#chan"),
                    source,
                ) is HistoryResyncState.Failed,
            )
            assertEquals(mapOf(bufferId to HistorySyncStatus.Syncing), coordinator.syncStatuses.value)

            coordinator.retireNetwork(networkId, source)

            // The catch-up loop that would have retried died with the connection, so the frozen
            // statuses and progress entry have to be released here or they last the process lifetime.
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
            assertEquals(emptyMap<Long, SyncPassProgress>(), coordinator.passProgress.value)
        }

    @Test
    fun historyTurningUnsupportedBetweenAttemptsSettlesTheSuspendedPass() =
        runTest {
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> FakeResponse(endOfHistory = true)
                        else -> throw IOException("transport died mid-pass")
                    }
                }
            assertTrue(
                coordinator.resyncNetwork(
                    networkId,
                    openTargets(bufferId to "#chan"),
                    source,
                ) is HistoryResyncState.Failed,
            )
            assertEquals(mapOf(bufferId to HistorySyncStatus.Syncing), coordinator.syncStatuses.value)
            assertEquals(
                mapOf(networkId to SyncPassProgress(total = 1, settled = 0)),
                coordinator.passProgress.value,
            )

            // The server CAP-DELs chathistory during the backoff, so the retry takes the Unsupported
            // early return and never reaches a session terminal of its own.
            source.supported = false
            assertEquals(
                HistoryResyncState.Unsupported,
                coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source),
            )

            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
            assertEquals(emptyMap<Long, SyncPassProgress>(), coordinator.passProgress.value)
        }

    @Test
    fun givingUpOnASupersededConnectionDoesNotSettleTheSuccessorsPass() =
        runTest {
            val stale = FakeSource { FakeResponse(endOfHistory = true) }
            val onTheWire = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val successor =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(endOfHistory = true)
                        }

                        else -> {
                            onTheWire.complete(Unit)
                            release.await()
                            FakeResponse(listOf(message("m1", 100)), endOfHistory = true)
                        }
                    }
                }

            val pass =
                async {
                    coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), successor)
                }
            onTheWire.await()
            // The predecessor's catch-up loop gives up just after the successor registered its pass:
            // its verdict must not land on buffers the new connection is actively syncing.
            coordinator.settleNetworkPass(networkId, HistoryResyncState.Failed("gave up"), stale)

            assertEquals(mapOf(bufferId to HistorySyncStatus.Syncing), coordinator.syncStatuses.value)
            release.complete(Unit)
            pass.await()
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
        }

    @Test
    fun abandoningASupersededPassKeepsTheSuccessorsProgressHeader() =
        runTest {
            val stalePrepared = CompletableDeferred<Unit>()
            val releaseStale = CompletableDeferred<Unit>()
            var staleIsCurrent = true
            val stale = FakeSource { FakeResponse(endOfHistory = true) }
            var probes = 0
            stale.onAvailability = {
                // The per-target probe (the pass-open probe is the first): the session is registered
                // and its progress published, and no wire permit is held yet, so the successor below
                // can run its own pass to a terminal while this one is parked.
                if (++probes == 2) {
                    stalePrepared.complete(Unit)
                    releaseStale.await()
                }
            }
            val successor =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> FakeResponse(endOfHistory = true)
                        else -> throw IOException("transport died mid-pass")
                    }
                }

            val stalePass =
                async {
                    coordinator.resyncNetwork(
                        networkId,
                        openTargets(bufferId to "#chan"),
                        stale,
                        isCurrent = { staleIsCurrent },
                    )
                }
            stalePrepared.await()
            // The successor connection's pass takes the session slot and fails retryably, so its
            // statuses and its frozen progress header are deliberately left painted for the retry.
            val successorResult =
                coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), successor)
            assertTrue(successorResult is HistoryResyncState.Failed)
            val frozen = coordinator.passProgress.value
            assertEquals(mapOf(networkId to SyncPassProgress(total = 1, settled = 0)), frozen)

            // Only now does the predecessor learn its connection was replaced, so its pass abandons.
            // It owns nothing any more: deleting the aggregate progress entry here blanked the live
            // successor's sync header, and re-registering its survivors stranded ids the successor's
            // own adoption had already cleared.
            staleIsCurrent = false
            releaseStale.complete(Unit)
            stalePass.await()

            assertEquals(frozen, coordinator.passProgress.value)
            assertEquals(mapOf(bufferId to HistorySyncStatus.Syncing), coordinator.syncStatuses.value)

            // The retry loop's give-up verdict still settles the successor's own pass normally.
            coordinator.settleNetworkPass(networkId, successorResult, successor)
            assertEquals(emptyMap<Long, SyncPassProgress>(), coordinator.passProgress.value)
        }

    @Test
    fun abandoningASupersededPassStrandsNoWaitingBadge() =
        runTest {
            // The buffer the predecessor also held, and which closed before the successor's pass, so
            // the successor never re-queues it and its own adoptAwaiting cannot clear it either.
            val closedBufferId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "#closed",
                        displayName = "#closed",
                        type = BufferType.CHANNEL,
                    ),
                )
            val stalePrepared = CompletableDeferred<Unit>()
            val releaseStale = CompletableDeferred<Unit>()
            var staleIsCurrent = true
            val stale = FakeSource { FakeResponse(endOfHistory = true) }
            var probes = 0
            stale.onAvailability = {
                // Parked after registration, before any wire permit is held (see the sibling test).
                if (++probes == 2) {
                    stalePrepared.complete(Unit)
                    releaseStale.await()
                }
            }
            val successor =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> FakeResponse(endOfHistory = true)
                        else -> throw IOException("transport died mid-pass")
                    }
                }

            val stalePass =
                async {
                    coordinator.resyncNetwork(
                        networkId,
                        openTargets(bufferId to "#chan", closedBufferId to "#closed"),
                        stale,
                        isCurrent = { staleIsCurrent },
                    )
                }
            stalePrepared.await()
            // The successor's pass takes the slot with only the still-open buffer.
            coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), successor)

            staleIsCurrent = false
            releaseStale.complete(Unit)
            stalePass.await()

            // Painting AwaitingConnection without registering it for adoption stranded the badge for
            // the process lifetime: adoptAwaiting, clearAwaitingConnection and retireNetwork all clear
            // by walking awaitingByNetwork, and no later pass re-queues a buffer that has closed.
            assertNull(coordinator.syncStatuses.value[closedBufferId])
            // A live successor owns the slot, so the predecessor may not repaint the open buffer either.
            assertEquals(HistorySyncStatus.Syncing, coordinator.syncStatuses.value[bufferId])
        }

    /** Bounded spin for a condition another thread produces; the test dispatcher cannot drive it. */
    private fun awaitOnAnotherThread(
        what: String,
        timeoutMs: Long = 5_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for $what")
            Thread.sleep(2)
        }
    }

    @Test
    fun aPassProgressPublicationCannotInterleaveWithANetworkSlotHandover() =
        runBlocking {
            // Real threads on purpose. The defect is a check-then-act: the pass decided it still owned
            // its network's progress entry, and only then wrote it. Between those two steps the slot
            // can change hands — a predecessor's endSession leaves it empty and the successor's
            // beginNetworkSession takes it — so the write landed on a header the pass no longer owned,
            // overwriting the successor's count or (from the release side) deleting it outright. A
            // single-threaded test dispatcher can never interleave inside a non-suspending function.
            //
            // Holding the coordinator's own ownership guard here IS that handover: beginNetworkSession
            // takes the same guard, so a real successor could not be driven concurrently and observed
            // at the same time. What the assertion proves is that the publication does not slip past a
            // slot transition in progress.
            val onTheWire = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(endOfHistory = true)
                        }

                        else -> {
                            onTheWire.complete(Unit)
                            release.await()
                            FakeResponse(listOf(message("m1", 100)), endOfHistory = true)
                        }
                    }
                }

            val pass =
                launch(Dispatchers.IO) {
                    coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)
                }
            withTimeout(10_000) { onTheWire.await() }
            assertEquals(
                mapOf(networkId to SyncPassProgress(total = 1, settled = 0)),
                coordinator.passProgress.value,
            )

            synchronized(coordinator.retireGuard) {
                release.complete(Unit)
                // The per-buffer terminal is published from inside the session monitor, immediately
                // before the aggregate publication, so observing it puts the pass at the guard.
                awaitOnAnotherThread("the buffer to settle") {
                    coordinator.syncStatuses.value[bufferId] != HistorySyncStatus.Syncing
                }
                Thread.sleep(200)
                assertEquals(
                    mapOf(networkId to SyncPassProgress(total = 1, settled = 0)),
                    coordinator.passProgress.value,
                )
            }

            withTimeout(10_000) { pass.join() }
            assertEquals(emptyMap<Long, SyncPassProgress>(), coordinator.passProgress.value)
        }

    @Test
    fun reconcileBufferPublishesNoAggregateProgress() =
        runTest {
            var duringFetch: Map<Long, SyncPassProgress>? = null
            val source =
                FakeSource {
                    duringFetch = coordinator.passProgress.value
                    FakeResponse(listOf(message("m1", 100)), endOfHistory = true)
                }

            coordinator.reconcileBuffer(networkId, bufferId, "#chan", source)

            // A single-buffer reconcile is not a network pass; counting it would corrupt the header.
            assertEquals(emptyMap<Long, SyncPassProgress>(), duringFetch)
            assertEquals(emptyMap<Long, SyncPassProgress>(), coordinator.passProgress.value)
        }

    @Test
    fun passOrdersTheForegroundChatThenPinnedThenNewestAdvertised() =
        runTest {
            val (aId, bId) = insertChannels(listOf("#a", "#b"))
            val pinnedId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "#pin",
                        displayName = "#pin",
                        type = BufferType.CHANNEL,
                        pinned = true,
                    ),
                )
            val foregroundId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "#fg",
                        displayName = "#fg",
                        type = BufferType.CHANNEL,
                    ),
                )
            val prioritized =
                HistoryResyncCoordinator(
                    db,
                    processor,
                    syncPrefs,
                    CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    foregroundBuffers = FakeForegroundBuffer(foregroundId),
                )
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                // The foreground chat and the pinned room are the two LEAST recently active.
                                targets = listOf("#a" to 500L, "#b" to 900L, "#pin" to 100L, "#fg" to 50L),
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            FakeResponse(
                                listOf(message("m-${request.target}", 1_000, target = request.target)),
                                endOfHistory = true,
                            )
                        }
                    }
                }

            prioritized.resyncNetwork(
                networkId,
                listOf(
                    OpenBufferTarget(aId, "#a"),
                    OpenBufferTarget(bId, "#b"),
                    OpenBufferTarget(pinnedId, "#pin", pinned = true),
                    OpenBufferTarget(foregroundId, "#fg"),
                ),
                source,
            )

            // The fair semaphore admits in launch order, so this order decides the first wire wave:
            // the open chat, then pinned, then everything else newest-advertised-first.
            assertEquals(
                listOf("#fg", "#pin", "#b", "#a"),
                source.requests
                    .filter { it.subcommand == ChatHistoryRequest.Subcommand.LATEST }
                    .map { it.target },
            )
        }

    @Test
    fun stopAllRetiresNetworksItOnlyKnowsThroughATrackedCatchUp() {
        assertEquals(setOf(1L, 2L), networksToRetire(setOf(1L, 2L), emptySet()))
        assertEquals(setOf(1L, 2L), networksToRetire(setOf(1L, 2L), setOf(2L)))
        // The network row was deleted while its verification pass was still tracked. Cancelling that
        // job is not enough: without a retirement its waiting badges and progress entry survive.
        assertEquals(setOf(1L, 7L), networksToRetire(setOf(1L), setOf(7L)))
        assertEquals(setOf(7L), networksToRetire(emptySet(), setOf(7L)))
        assertEquals(emptySet<Long>(), networksToRetire(emptySet(), emptySet()))
    }

    @Test
    fun catchUpOwnershipFollowsTheConnectionNotJustTheNetwork() {
        val clientA = Any()
        val clientB = Any()
        val alive = Job()
        val candidate = CatchUpJob(clientB, Job())

        // The reproduction of the user-visible bug: a pass pinned to a client the actor already
        // replaced must not outrank — and cancel — the live connection's verification. Keyed on the
        // network alone this returned `existing`, silently disabling every later foreground
        // verification for that network until the process was killed.
        assertSame(candidate, chooseCatchUpOwner(CatchUpJob(clientA, alive), candidate))
        // Same connection: that is exactly what the single flight exists for.
        val sameClientCandidate = CatchUpJob(clientA, Job())
        assertSame(
            alive,
            chooseCatchUpOwner(CatchUpJob(clientA, alive), sameClientCandidate).job,
        )
        // Nothing tracked, or a finished pass: the candidate always wins.
        assertSame(candidate, chooseCatchUpOwner(null, candidate))
        val completed = Job().apply { complete() }
        assertSame(candidate, chooseCatchUpOwner(CatchUpJob(clientB, completed), candidate))
        alive.cancel()
    }

    @Test
    fun targetClassificationWaitGivesUpInsteadOfParkingOnADeadSocket() =
        runTest {
            val ready = MutableStateFlow(false)
            val outcome = async { awaitTargetClassification(ready) }
            advanceTimeBy(HistoryResyncCoordinator.TARGET_CLASSIFICATION_WAIT_TIMEOUT_MS - 1)
            runCurrent()
            assertTrue(outcome.isActive)

            // A client that dies before CHANTYPES/376 writes `false` over `false`, which a StateFlow
            // conflates, and a StateFlow has no completion signal — so the unbounded collector that
            // used to sit here could never be woken again.
            advanceTimeBy(2)
            runCurrent()
            assertFalse(outcome.await())
        }

    @Test
    fun targetClassificationWaitReturnsAsSoonAsTheBurstSettles() =
        runTest {
            assertTrue(awaitTargetClassification(MutableStateFlow(true)))

            val ready = MutableStateFlow(false)
            val outcome = async { awaitTargetClassification(ready) }
            runCurrent()
            ready.value = true
            runCurrent()
            assertTrue(outcome.await())
        }

    private data class ForegroundVerificationCase(
        val description: String,
        val recorded: CompletedCatchUp?,
        val client: Any,
        val expected: Boolean,
    )

    @Test
    fun foregroundVerificationIsSkippedForAnySocketThatConvergedAndNeverDied() {
        val socket = Any()
        val replacement = Any()
        val converged = CompletedCatchUp(socket)
        val cases =
            listOf(
                ForegroundVerificationCase("no pass has ever converged", null, socket, true),
                ForegroundVerificationCase("same socket, converged moments ago", converged, socket, false),
                // The whole point of the rework: elapsed time is not evidence. A socket that stayed
                // Ready received everything live, so the pass is pure waste however long ago it ran.
                ForegroundVerificationCase(
                    "same socket, converged long ago",
                    converged,
                    socket,
                    false,
                ),
                // A reconnect can never inherit the old socket's proof, however recent it was.
                ForegroundVerificationCase("reconnected socket", converged, replacement, true),
            )

        cases.forEach { case ->
            assertEquals(
                case.description,
                case.expected,
                shouldRunForegroundVerification(case.recorded, case.client),
            )
        }
    }

    // --- discovery-first badging, wave planning, adaptive fan-out, latent chrome ---------------

    /** Give [bufferId] a stored cursor, so the pass has something to compare an advertisement to. */
    private suspend fun seedCursor(
        roomId: Long = bufferId,
        newestServerTime: Long,
    ) {
        db.historyCursorDao().upsert(
            HistoryCursorEntity(
                roomId = roomId,
                newestMsgid = "seeded",
                newestServerTime = newestServerTime,
                oldestMsgid = "seeded",
                oldestServerTime = newestServerTime,
            ),
        )
    }

    @Test
    fun discoveryBadgesTheChatListBeforeAnyPageIsFetched() =
        runTest {
            seedCursor(newestServerTime = 100_000)
            var advertisedAtFetch: Long? = null
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 900_000L), endOfHistory = true)
                        }

                        else -> {
                            // The list already knows this room moved; the rows are only now being asked for.
                            advertisedAtFetch = db.bufferDao().rawById(bufferId)?.advertisedLatestTime
                            FakeResponse(listOf(message("tail", 900_000)), endOfHistory = true)
                        }
                    }
                }

            coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)

            assertEquals(900_000L, advertisedAtFetch)
            assertEquals(900_000L, db.bufferDao().rawById(bufferId)?.advertisedLatestTime)
        }

    @Test
    fun advertisedActivityNeverMovesBackwards() =
        runTest {
            db.bufferDao().advanceAdvertisedLatest(bufferId, 900_000)
            seedCursor(newestServerTime = 100_000)
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        // An older report — a second pass, or the paced sweep — must not walk the sort key
                        // or the pending-unread cue back.
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 400_000L), endOfHistory = true)
                        }

                        else -> {
                            FakeResponse(listOf(message("tail", 400_000)), endOfHistory = true)
                        }
                    }
                }

            coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)

            assertEquals(900_000L, db.bufferDao().rawById(bufferId)?.advertisedLatestTime)
        }

    @Test
    fun anUnchangedRoomCostsNoWireRequestAndSettlesImmediately() =
        runTest {
            // Discovery advertises exactly what this room's cursor already holds: there is nothing to
            // fetch, and asking anyway is the redundant traffic the wave plan exists to remove.
            seedCursor(newestServerTime = 500_000)
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 500_000L), endOfHistory = true)
                        }

                        else -> {
                            error("an unchanged room must not be fetched")
                        }
                    }
                }

            val result = coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)

            assertEquals(HistoryResyncState.UpToDate, result)
            assertEquals(
                listOf(ChatHistoryRequest.Subcommand.TARGETS),
                source.requests.map { it.subcommand },
            )
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
            assertEquals(emptyMap<Long, SyncPassProgress>(), coordinator.passProgress.value)
        }

    @Test
    fun aPassWithNothingToFetchShowsNoChromeAtAll() =
        runTest {
            seedCursor(newestServerTime = 500_000)
            val chrome = mutableListOf<Map<Long, SyncPassProgress>>()
            val statuses = mutableListOf<Map<Long, HistorySyncStatus>>()
            val source =
                FakeSource { request ->
                    chrome += coordinator.passProgress.value
                    statuses += coordinator.syncStatuses.value
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 500_000L), endOfHistory = true)
                        }

                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)

            // Not "settled quickly" — never painted. A reconnect the user did not notice must not
            // announce itself, and the entry gate the chat screen waits on is a different mechanism
            // entirely (the registry's historyCatchUpPending), so nothing about positioning changes.
            assertTrue(chrome.all { it.isEmpty() })
            assertTrue(statuses.all { it.isEmpty() })
        }

    @Test
    fun aReVerificationOfAnAlreadyConvergedSocketStaysSilentEvenWithWorkToDo() =
        runTest {
            val observed = mutableListOf<Map<Long, HistorySyncStatus>>()
            val source =
                FakeSource { request ->
                    observed += coordinator.syncStatuses.value
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 900L), endOfHistory = true)
                        }

                        else -> {
                            FakeResponse(listOf(message("tail", 900)), endOfHistory = true)
                        }
                    }
                }

            val result =
                coordinator.resyncNetwork(
                    networkId,
                    openTargets(bufferId to "#chan"),
                    source,
                    chromeEligible = false,
                )

            // The work happened — the row landed — but a socket that already converged has nothing
            // worth interrupting the list for, so no Queued and no Syncing was ever published.
            assertEquals(HistoryResyncState.Updated(1), result)
            assertTrue(db.messageDao().byMsgid(bufferId, "tail") != null)
            assertTrue(observed.all { it.isEmpty() })
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
        }

    @Test
    fun chromeIsWithheldUntilDiscoveryProvesThereIsWorkThenReplaysCurrentStatus() =
        runTest {
            var duringDiscovery: Map<Long, HistorySyncStatus>? = null
            var whileSyncing: Map<Long, HistorySyncStatus>? = null
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            // The pass has already registered its open buffer, but nothing is published yet:
                            // it has not been shown that anything changed.
                            duringDiscovery = coordinator.syncStatuses.value
                            FakeResponse(targets = listOf("#chan" to 900_000L), endOfHistory = true)
                        }

                        else -> {
                            whileSyncing = coordinator.syncStatuses.value
                            FakeResponse(listOf(message("tail", 900_000)), endOfHistory = true)
                        }
                    }
                }

            coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)

            assertEquals(emptyMap<Long, HistorySyncStatus>(), duringDiscovery)
            // Revealed once discovery reported a changed room, and the replay is the buffer's CURRENT
            // status rather than a queue of the states it already left.
            assertEquals(mapOf(bufferId to HistorySyncStatus.Syncing), whileSyncing)
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
        }

    @Test
    fun aTerminalFailurePublishesEvenWhileThePassIsStillLatent() =
        runTest {
            // A latent pass is quiet, not silent: the affordance the retry pill is built on has to
            // survive, or a permanently refused target would never be reported at all.
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> FakeResponse(endOfHistory = true)
                        else -> throw IrcCommandException("CHATHISTORY", "INVALID_TARGET", "no history")
                    }
                }

            coordinator.resyncNetwork(
                networkId,
                openTargets(bufferId to "#chan"),
                source,
                chromeEligible = false,
            )

            assertEquals(HistorySyncStatus.Unavailable, coordinator.syncStatus(bufferId).first())
        }

    @Test
    fun waveTwoSweepsTheOverflowSequentiallyAfterTheVisibleWave() =
        runTest {
            val names = (1..WAVE_ONE_LIMIT + 3).map { "#chan$it" }
            val ids = insertChannels(names)
            val source =
                FakeSource(supportsConcurrent = true) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                // Newest first by name index, so the wave-one cut is predictable.
                                targets = names.mapIndexed { index, name -> name to (900_000L - index * 1_000) },
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            FakeResponse(
                                listOf(message("m-${request.target}", 900_000, target = request.target)),
                                endOfHistory = true,
                            )
                        }
                    }
                }

            val result =
                async {
                    coordinator.resyncNetwork(networkId, openTargets(ids.zip(names)), source)
                }
            // Wave two is paced like the background backfill, so it only runs as virtual time advances.
            advanceTimeBy(HistoryResyncCoordinator.BACKFILL_SEED_PACE_MS * (names.size + 1))
            assertEquals(HistoryResyncState.Updated(names.size), result.await())

            val fetched =
                source.requests
                    .filter { it.subcommand == ChatHistoryRequest.Subcommand.LATEST }
                    .map { it.target }
            assertEquals(names.size, fetched.size)
            // The visible wave is bounded; the rest is swept in the same priority order behind it.
            assertEquals(names.take(WAVE_ONE_LIMIT).toSet(), fetched.take(WAVE_ONE_LIMIT).toSet())
            assertEquals(names.drop(WAVE_ONE_LIMIT), fetched.drop(WAVE_ONE_LIMIT))
            // The sweep is silent: it publishes no per-buffer status and no aggregate progress.
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
            assertEquals(emptyMap<Long, SyncPassProgress>(), coordinator.passProgress.value)
        }

    @Test
    fun theWatermarkAdvancesBeforeThePacedSweepRuns() =
        runTest {
            val names = (1..WAVE_ONE_LIMIT + 2).map { "#chan$it" }
            val ids = insertChannels(names)
            // Room persistence resumes on its own executor, so the pass's requests can be observed from
            // more than one thread; the counter and the sample have to survive that.
            val fetches = AtomicInteger()
            val watermarkAtSweep = CompletableDeferred<Long?>()
            val source =
                FakeSource(supportsConcurrent = true) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                targets = names.mapIndexed { index, name -> name to (900_000L - index * 1_000) },
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            if (fetches.incrementAndGet() > WAVE_ONE_LIMIT) {
                                watermarkAtSweep.complete(syncPrefs.lastSuccessfulSync(networkId))
                            }
                            FakeResponse(
                                listOf(message("m-${request.target}", 900_000, target = request.target)),
                                endOfHistory = true,
                            )
                        }
                    }
                }

            val pass = async { coordinator.resyncNetwork(networkId, openTargets(ids.zip(names)), source) }
            advanceTimeBy(HistoryResyncCoordinator.BACKFILL_SEED_PACE_MS * (names.size + 1))
            pass.await()

            // The next reconnect's discovery window must not be held open behind a sweep that can run
            // for as long as the account has rooms.
            assertEquals(names.size, fetches.get())
            assertEquals(900_000L, watermarkAtSweep.await())
        }

    @Test
    fun aRetryableVisibleWaveSkipsThePacedSweep() =
        runTest {
            val names = (1..WAVE_ONE_LIMIT + 2).map { "#chan$it" }
            val ids = insertChannels(names)
            val source =
                FakeSource(supportsConcurrent = true) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                targets = names.mapIndexed { index, name -> name to (900_000L - index * 1_000) },
                                endOfHistory = true,
                            )
                        }

                        // The visible wave's first room inserts a row but still lands below what discovery
                        // advertised, which is the pass asking to be retried rather than a dead advertisement.
                        else -> {
                            FakeResponse(
                                listOf(
                                    message(
                                        "m-${request.target}",
                                        if (request.target == names.first()) 800_000 else 900_000,
                                        target = request.target,
                                    ),
                                ),
                                endOfHistory = true,
                            )
                        }
                    }
                }

            val pass = async { coordinator.resyncNetwork(networkId, openTargets(ids.zip(names)), source) }
            // Enough virtual time for the sweep to have finished, had it been started at all.
            advanceTimeBy(HistoryResyncCoordinator.BACKFILL_SEED_PACE_MS * (names.size + 1))
            val result = pass.await()

            assertEquals(true, (result as? HistoryResyncState.Incomplete)?.retryRecommended)
            val fetched =
                source.requests
                    .filter { it.subcommand == ChatHistoryRequest.Subcommand.LATEST }
                    .map { it.target }
            // The retry is about to re-run this whole pass; pacing the overflow in front of it would
            // delay the one timeline the reader is looking at by the length of the sweep.
            assertEquals(WAVE_ONE_LIMIT, fetched.size)
            assertEquals(emptyList<String>(), fetched.filter { it in names.drop(WAVE_ONE_LIMIT) })
        }

    @Test
    fun aTargetTimeoutNarrowsTheFanOutInsteadOfFailingThePass() =
        runTest {
            val names = listOf("#slow", "#fast")
            val ids = insertChannels(names)
            val source =
                FakeSource(supportsConcurrent = true) { request ->
                    when {
                        request.subcommand == ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = names.map { it to 900L }, endOfHistory = true)
                        }

                        request.target == "#slow" -> {
                            awaitCancellation()
                        }

                        else -> {
                            FakeResponse(
                                listOf(message("m-fast", 900, target = "#fast")),
                                endOfHistory = true,
                            )
                        }
                    }
                }
            coordinator.requestTimeoutMs = 5_000

            val pass = async { coordinator.resyncNetwork(networkId, openTargets(ids.zip(names)), source) }
            advanceTimeBy(10_000)
            val result = pass.await()

            // One target running out of budget used to abort the whole pass, skipping every remaining
            // target and marking every open buffer failed. Now it is that target's own incompleteness,
            // and the pass keeps its retry recommendation so the catch-up loop tries again.
            assertTrue(result is HistoryResyncState.Incomplete)
            assertTrue((result as HistoryResyncState.Incomplete).retryRecommended)
            assertTrue(db.messageDao().byMsgid(ids[1], "m-fast") != null)
        }

    @Test
    fun aStaleConnectionStillAbortsTheWholePass() =
        runTest {
            // The timeout carve-out must not swallow the one failure that means "stop": a superseded
            // connection has nothing left to fetch for.
            val names = listOf("#a", "#b")
            val ids = insertChannels(names)
            var current = true
            val source =
                FakeSource(supportsConcurrent = true) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = names.map { it to 900L }, endOfHistory = true)
                        }

                        else -> {
                            current = false
                            FakeResponse(
                                listOf(message("m-${request.target}", 900, target = request.target)),
                                endOfHistory = true,
                            )
                        }
                    }
                }

            val result =
                coordinator.resyncNetwork(
                    networkId,
                    openTargets(ids.zip(names)),
                    source,
                    isCurrent = { current },
                )

            assertTrue(result is HistoryResyncState.Failed)
        }

    @Test
    fun aChatOpeningMidPassJoinsTheSameNewestPageFetch() =
        runTest {
            // Two askers, one question: the pass seeding this room and a chat screen opening onto it
            // want the same newest page, and the second one used to put an identical request on the
            // wire behind the first, guaranteed to insert nothing.
            val loader = HistoryPageLoader(processor)
            coordinator = HistoryResyncCoordinator(db, processor, syncPrefs, backgroundScope, loader = loader)
            val fetchStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 900L), endOfHistory = true)
                        }

                        else -> {
                            fetchStarted.complete(Unit)
                            release.await()
                            FakeResponse(listOf(message("tail", 900)), endOfHistory = true)
                        }
                    }
                }

            val pass = async { coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source) }
            fetchStarted.await()
            val chatOpen =
                async {
                    loader.loadPage(
                        networkId,
                        bufferId,
                        "#chan",
                        HistoryPageLoader.Direction.LATEST,
                        source,
                    )
                }
            runCurrent()
            release.complete(Unit)

            assertEquals(HistoryResyncState.Updated(1), pass.await())
            assertTrue(chatOpen.await() is HistoryPageLoader.PageResult.Loaded)
            assertEquals(
                listOf("TARGETS", "LATEST"),
                source.requests.map { it.subcommand.name },
            )
        }

    @Test
    fun aSharedNewestPageTimeoutIsOneTargetsTimeoutAndPagingsRetryableFailure() =
        runTest {
            // The coalescing from the test above, with the outcome it has to survive: the shared flight
            // times out with a chat screen joined to it. Whichever caller LEADS must not decide what the
            // other sees — a leader's timeout classified for Paging reached the pass as a transport
            // failure, which is neither a target-scoped refusal nor a TimeoutCancellationException, so
            // it escaped syncOneTarget, cancelled every sibling, and failed the whole pass; and a
            // leader's timeout classified as cancellation let the Paging follower read the flight as
            // abandoned and silently re-lead it onto a wire that just proved it is too slow.
            val loader = HistoryPageLoader(processor)
            coordinator = HistoryResyncCoordinator(db, processor, syncPrefs, backgroundScope, loader = loader)
            coordinator.requestTimeoutMs = 5_000
            val names = listOf("#slow", "#fast")
            val ids = insertChannels(names)
            val leaderOnTheWire = CompletableDeferred<Unit>()
            val source =
                FakeSource(supportsConcurrent = true) { request ->
                    when {
                        request.subcommand == ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = names.map { it to 900L }, endOfHistory = true)
                        }

                        request.target == "#slow" -> {
                            leaderOnTheWire.complete(Unit)
                            awaitCancellation()
                        }

                        else -> {
                            FakeResponse(
                                listOf(message("m-fast", 900, target = "#fast")),
                                endOfHistory = true,
                            )
                        }
                    }
                }

            val pass = async { coordinator.resyncNetwork(networkId, openTargets(ids.zip(names)), source) }
            // The flight is registered before its request goes out, so a chat opened now joins it.
            leaderOnTheWire.await()
            val chatOpen =
                async {
                    runCatching {
                        loader.loadPage(networkId, ids[0], "#slow", HistoryPageLoader.Direction.LATEST, source)
                    }
                }
            runCurrent()
            advanceTimeBy(10_000)
            val result = pass.await()

            // The pass reports the timeout as this target's incompleteness and keeps its retry
            // recommendation; the sibling was never cancelled and its row landed.
            assertTrue(result is HistoryResyncState.Incomplete)
            assertTrue((result as HistoryResyncState.Incomplete).retryRecommended)
            assertTrue(db.messageDao().byMsgid(ids[1], "m-fast") != null)
            // The joiner gets Paging's own classification of the same fact: a retryable transport
            // failure, never a cancellation, or the mediator would freeze this direction's LoadState.
            val paging = chatOpen.await().exceptionOrNull()
            assertTrue(paging is IrcDisconnectedException)
            assertFalse(paging is CancellationException)
            // And exactly one request: a timed-out flight must not be re-led by its followers.
            assertEquals(1, source.requests.count { it.target == "#slow" })
        }

    @Test
    fun anUnreachableAdvertisementIsRetiredInsteadOfHauntingTheChatList() =
        runTest {
            // soju can index an event its replay never returns. Discovery badges the room, the LATEST
            // fetch adds nothing, and the pass settles it Idle — after which nothing else will ever
            // touch the advertisement: the room is converged, so no later wave fetches it, and mark-read
            // anchors on the newest LOCAL row, which is below the advertised instant. Left standing it
            // is a permanent unread dot.
            processor.process(networkId, message("tail", 500_000))
            seedCursor(newestServerTime = 100_000)
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 900_000L), endOfHistory = true)
                        }

                        // LATEST is by definition the newest page and it returns nothing new.
                        else -> {
                            FakeResponse(endOfHistory = true)
                        }
                    }
                }

            val result = coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)

            // Settled, not retried: chasing a timestamp the server will not serve never converges.
            assertEquals(HistoryResyncState.UpToDate, result)
            val row =
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .single { it.bufferId == bufferId }
            assertFalse(row.advertisedUnread)
            // Clamped onto what the room can actually show.
            assertEquals(500_000L, db.bufferDao().rawById(bufferId)?.advertisedLatestTime)
            assertEquals(500_000L, row.lastMessageTime)
        }

    @Test
    fun aConvergedRoomKeepsNoCueForAnEventItCanNeverShow() =
        runTest {
            // The other half of the same invariant, on the path with no fetch at all: the cursor already
            // reached the advertisement, so the pass skips the room — and the advertised instant is a
            // JOIN, which the chat list never previews. Without the retirement the cue would be lit
            // forever on a room that is fully caught up.
            processor.process(networkId, message("tail", 500_000))
            seedCursor(newestServerTime = 900_000)
            db.bufferDao().advanceAdvertisedLatest(bufferId, 900_000)
            assertTrue(
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .single()
                    .advertisedUnread,
            )
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 900_000L), endOfHistory = true)
                        }

                        else -> {
                            error("a converged room must not be fetched")
                        }
                    }
                }

            coordinator.resyncNetwork(networkId, openTargets(bufferId to "#chan"), source)

            assertFalse(
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .single()
                    .advertisedUnread,
            )
        }

    @Test
    fun theEntryGateIsHeldThroughAPassThatPaintsNothingAndHandedBackWhenItConverges() =
        runTest {
            // Chrome and entry positioning are different mechanisms and must stay that way: this pass
            // publishes no status and no progress at all, and the chat screen's entry gate — the
            // registry's historyCatchUpPending, which the Ready session hands back through
            // onCatchUpConverged — has to stay held for its whole visible wave regardless.
            var pending = setOf(networkId)

            fun activity() =
                ConnectionActivitySnapshot(
                    states = mapOf(networkId to IrcClientState.Ready("me", emptySet(), emptyMap())),
                    initializationComplete = true,
                    historyCatchUpPending = pending,
                )
            val entryReadyDuringPass = mutableListOf<Boolean>()
            val paintedDuringPass = mutableListOf<Boolean>()
            val source =
                FakeSource { request ->
                    entryReadyDuringPass += entryHistoryReady(activity(), networkId)
                    paintedDuringPass += coordinator.syncStatuses.value.isNotEmpty() ||
                        coordinator.passProgress.value.isNotEmpty()
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 900_000L), endOfHistory = true)
                        }

                        else -> {
                            FakeResponse(listOf(message("tail", 900_000)), endOfHistory = true)
                        }
                    }
                }

            coordinator.resyncNetwork(
                networkId,
                openTargets(bufferId to "#chan"),
                source,
                chromeEligible = false,
                onCatchUpConverged = { pending = pending - networkId },
            )

            assertTrue(paintedDuringPass.none { it })
            // Held across discovery AND the fetch: a room positioned mid-pass would otherwise enter
            // against a store the pass is still writing.
            assertEquals(listOf(false, false), entryReadyDuringPass)
            assertTrue(entryHistoryReady(activity(), networkId))
            assertEquals(emptyMap<Long, HistorySyncStatus>(), coordinator.syncStatuses.value)
        }

    @Test
    fun theEntryGateIsHandedBackBeforeThePacedSweepRunsNotAfterIt() =
        runTest {
            // The gate is network-scoped, so holding it across the overflow sweep makes a chat that
            // settled in wave one wait on background work it has no stake in — up to the entry timeout
            // on a large account. It is handed back on the same boundary as the watermark.
            val names = (1..WAVE_ONE_LIMIT + 2).map { "#chan$it" }
            val ids = insertChannels(names)
            val fetches = AtomicInteger()
            val gateAtSweep = CompletableDeferred<Boolean>()
            var pending = true
            val source =
                FakeSource(supportsConcurrent = true) { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(
                                targets = names.mapIndexed { index, name -> name to (900_000L - index * 1_000) },
                                endOfHistory = true,
                            )
                        }

                        else -> {
                            if (fetches.incrementAndGet() > WAVE_ONE_LIMIT) gateAtSweep.complete(pending)
                            FakeResponse(
                                listOf(message("m-${request.target}", 900_000, target = request.target)),
                                endOfHistory = true,
                            )
                        }
                    }
                }

            val pass =
                async {
                    coordinator.resyncNetwork(
                        networkId,
                        openTargets(ids.zip(names)),
                        source,
                        onCatchUpConverged = { pending = false },
                    )
                }
            advanceTimeBy(HistoryResyncCoordinator.BACKFILL_SEED_PACE_MS * (names.size + 1))
            pass.await()

            assertEquals(names.size, fetches.get())
            assertFalse(gateAtSweep.await())
        }

    @Test
    fun aPassTheCatchUpLoopWillRetryKeepsItsEntryGate() =
        runTest {
            // The converged signal is not "the pass returned": a pass that recommends a retry is one
            // the catch-up loop runs again, and entry positioning must keep waiting through it.
            var handedBack = false
            val source =
                FakeSource { request ->
                    when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.TARGETS -> {
                            FakeResponse(targets = listOf("#chan" to 900_000L), endOfHistory = true)
                        }

                        // The advertised message never arrives and rows DID land, so this is a genuine lag
                        // the pass should chase rather than a timestamp replay refuses to serve.
                        else -> {
                            FakeResponse(listOf(message("older", 500_000)), endOfHistory = true)
                        }
                    }
                }

            val result =
                coordinator.resyncNetwork(
                    networkId,
                    openTargets(bufferId to "#chan"),
                    source,
                    onCatchUpConverged = { handedBack = true },
                )

            assertTrue(result is HistoryResyncState.Incomplete)
            assertTrue((result as HistoryResyncState.Incomplete).retryRecommended)
            assertFalse(handedBack)
        }

    @Test
    fun aClearedConnectionReferenceVerifiesRatherThanSkipping() {
        // The record is weak so a retired client is not pinned for the network's lifetime; losing
        // the referent must fail toward running the pass, never toward trusting a socket that is
        // demonstrably gone.
        val collected = CompletedCatchUp(Any())
        System.gc()

        assertTrue(shouldRunForegroundVerification(collected, Any()))
    }
}
