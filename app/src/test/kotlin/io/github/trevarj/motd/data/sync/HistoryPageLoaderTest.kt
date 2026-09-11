package io.github.trevarj.motd.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.avatar.AvatarController
import io.github.trevarj.motd.avatar.NoopAvatarController
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.dickord.DICKORD_AVATAR_TAG
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fetch-primitive coverage for [HistoryPageLoader], driving a single directional page from a
 * caller-supplied boundary against scripted responses. These mirror the boundary-handling cases the
 * [ChatHistoryRemoteMediator] previously owned inline: the msgid→timestamp fallback, the
 * non-advancing/saturated loop guards, and advertised-boundary trimming.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryPageLoaderTest {
    private lateinit var db: MotdDatabase
    private lateinit var processor: EventProcessor
    private lateinit var loader: HistoryPageLoader
    private var networkId = 0L
    private var bufferId = 0L

    @Before fun setUp() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java).allowMainThreadQueries().build()
            processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
            loader = HistoryPageLoader(processor)
            networkId =
                db.networkDao().insert(
                    NetworkEntity(name = "libera", role = NetworkRole.DIRECT, host = "h", port = 6697, nick = "me", username = "me", realname = "Me"),
                )
            processor.onRegistered(networkId, "me", emptyMap())
            db.bufferDao().insert(BufferEntity(networkId = networkId, name = "#chan", displayName = "#chan", type = BufferType.CHANNEL))
            bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
        }

    @After fun tearDown() {
        db.close()
    }

    private fun chatMsg(
        msgid: String,
        time: Long,
    ) = IrcEvent.ChatMessage(
        ctx = MessageContext(msgid, time, null, "b", null),
        kind = IrcEvent.ChatKind.PRIVMSG,
        source = Prefix("alice"),
        target = "#chan",
        text = msgid,
        isSelf = false,
        replyToMsgid = null,
    )

    private fun messages(
        events: List<IrcEvent>,
        endOfHistory: Boolean = false,
    ): ChatHistoryResponse.Messages {
        val references =
            events.mapNotNull { event ->
                val ctx =
                    when (event) {
                        is IrcEvent.ChatMessage -> event.ctx
                        is IrcEvent.TagMessage -> event.ctx
                        else -> null
                    } ?: return@mapNotNull null
                ChatHistoryReference(ctx.msgid, ctx.serverTime)
            }
        return ChatHistoryResponse.Messages(
            events,
            oldest = references.firstOrNull(),
            newest = references.lastOrNull(),
            endOfHistory = endOfHistory,
            primaryMessageCount = references.size,
        )
    }

    /** Scripts BEFORE responses and records the requests issued, mirroring the mediator's fake. */
    private inner class FakeHistory(
        val before: ArrayDeque<List<IrcEvent>> = ArrayDeque(),
        val failureFor: ((ChatHistoryRequest) -> Throwable?)? = null,
        val responseFor: ((ChatHistoryRequest) -> ChatHistoryResponse.Messages?)? = null,
        val referenceTypes: Set<HistoryReferenceType> =
            setOf(
                HistoryReferenceType.TIMESTAMP,
                HistoryReferenceType.MSGID,
            ),
    ) : HistoryPageLoader.HistorySource {
        val requests = mutableListOf<ChatHistoryRequest>()

        override suspend fun availability(): HistoryAvailability = HistoryAvailability.Ready(referenceTypes, 100)

        override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
            requests += req
            failureFor?.invoke(req)?.let { throw it }
            responseFor?.invoke(req)?.let { return it }
            return when (req.subcommand) {
                ChatHistoryRequest.Subcommand.BEFORE -> messages(before.removeFirstOrNull() ?: emptyList())
                else -> messages(emptyList())
            }
        }
    }

    /** Canonical rows newest-first, exactly as the timeline pages them. */
    private suspend fun rows() =
        db
            .messageDao()
            .pagingSource(bufferId)
            .load(
                androidx.paging.PagingSource.LoadParams
                    .Refresh(null, 200, false),
            ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data }

    private suspend fun rowCount(): Int = rows().size

    private suspend fun loadOlder(
        history: HistoryPageLoader.HistorySource,
        boundary: ChatHistoryReference,
        pageSize: Int = 50,
        gapId: Long? = null,
    ) = loader.loadPage(
        networkId,
        bufferId,
        "#chan",
        HistoryPageLoader.Direction.OLDER,
        history,
        pageSize,
        gapId = gapId,
        boundary = boundary,
    )

    @Test
    fun correlatedHistoryScansAvatarTagsBeforePersistingThePage() =
        runTest {
            val target = "#discord.guild.general"
            val discordBufferId =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = target, displayName = target, type = BufferType.CHANNEL),
                )
            val tagged =
                chatMsg("discord-avatar", 100).copy(
                    ctx =
                        MessageContext(
                            msgid = "discord-avatar",
                            serverTime = 100,
                            account = null,
                            batchId = "batch",
                            label = null,
                            clientTags =
                                mapOf(
                                    DICKORD_AVATAR_TAG to
                                        "https://cdn.discordapp.com/avatars/1/hash.png?size=256",
                                ),
                        ),
                    source = Prefix("Alice/discord"),
                    target = target,
                )
            var scannedNetworkId: Long? = null
            var rowsAtScan: Int? = null
            var scannedEvents = emptyList<IrcEvent>()
            val avatars =
                object : AvatarController by NoopAvatarController {
                    override suspend fun ingestDickordAvatars(
                        networkId: Long,
                        events: List<IrcEvent>,
                    ) {
                        scannedNetworkId = networkId
                        scannedEvents = events
                        rowsAtScan = db.messageDao().countForBuffer(discordBufferId)
                    }
                }
            val hookedLoader = HistoryPageLoader(processor, avatarController = avatars)
            val history =
                FakeHistory(
                    responseFor = { messages(listOf(tagged), endOfHistory = true) },
                )

            val result =
                hookedLoader.loadPage(
                    networkId,
                    discordBufferId,
                    target,
                    HistoryPageLoader.Direction.LATEST,
                    history,
                )

            assertTrue(result is HistoryPageLoader.PageResult.Loaded)
            assertEquals(networkId, scannedNetworkId)
            assertEquals(listOf(tagged), scannedEvents)
            assertEquals(0, rowsAtScan)
            assertEquals(1, db.messageDao().countForBuffer(discordBufferId))
        }

    @Test
    fun msgidRejectionFallsBackToAdvertisedTimestampAndPersistsFallbackRequest() =
        runTest {
            processor.process(networkId, chatMsg("OpaqueCase", 500))
            val history =
                FakeHistory(
                    before = ArrayDeque(listOf(listOf(chatMsg("older", 100)))),
                    failureFor = { request ->
                        if (request.bound1 == "msgid=OpaqueCase") {
                            IrcCommandException("CHATHISTORY", "INVALID_MSGREFTYPE", "try timestamp")
                        } else {
                            null
                        }
                    },
                )

            val result = loadOlder(history, ChatHistoryReference("OpaqueCase", 500))

            assertTrue(result is HistoryPageLoader.PageResult.Loaded)
            assertFalse((result as HistoryPageLoader.PageResult.Loaded).endOfDirection)
            assertEquals(
                listOf("msgid=OpaqueCase", "timestamp=1970-01-01T00:00:00.500Z"),
                history.requests.map { it.bound1 },
            )
            assertNull(db.historyCursorDao().byRoom(bufferId)?.oldestMsgid)
            assertEquals(100L, db.historyCursorDao().byRoom(bufferId)?.oldestServerTime)
        }

    @Test
    fun unchangedBeforeBoundaryStopsWithoutClaimingCompletion() =
        runTest {
            processor.process(networkId, chatMsg("seed", 500))
            val history =
                FakeHistory(
                    responseFor = { request ->
                        messages(listOf(chatMsg("seed", 500)))
                            .takeIf { request.subcommand == ChatHistoryRequest.Subcommand.BEFORE }
                    },
                )

            val result = loadOlder(history, ChatHistoryReference("seed", 500))

            // A non-advancing cursor would refetch forever: stop this direction without marking the
            // buffer's history complete.
            assertTrue((result as HistoryPageLoader.PageResult.Loaded).endOfDirection)
            assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
        }

    @Test
    fun saturatedTimestampOnlyBeforeStopsInsteadOfSkippingBoundaryPeers() =
        runTest {
            processor.process(networkId, chatMsg("seed", 500))
            val history =
                FakeHistory(
                    before = ArrayDeque(listOf(listOf(chatMsg("a", 100), chatMsg("b", 100)))),
                    referenceTypes = setOf(HistoryReferenceType.TIMESTAMP),
                )

            val result = loadOlder(history, ChatHistoryReference("seed", 500), pageSize = 2)

            assertTrue((result as HistoryPageLoader.PageResult.Loaded).endOfDirection)
            assertEquals("timestamp=1970-01-01T00:00:00.500Z", history.requests.single().bound1)
            assertEquals(3, rowCount())
            assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
            assertFalse(db.historyCursorDao().byRoom(bufferId)!!.historyComplete)
        }

    @Test
    fun requestTimeoutSurfacesAsRetryableFailureNotCancellation() =
        runTest {
            processor.process(networkId, chatMsg("seed", 500))
            // A hung request must become a retryable transport failure. If the timeout escaped as a
            // CancellationException, the mediator would rethrow it to Paging and freeze the direction's
            // LoadState at Loading behind a stale pending request.
            val history =
                object : HistoryPageLoader.HistorySource {
                    override suspend fun availability(): HistoryAvailability =
                        HistoryAvailability.Ready(
                            setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID),
                            100,
                        )

                    override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse = awaitCancellation()
                }

            var observed: Throwable? = null
            try {
                loader.loadPage(
                    networkId,
                    bufferId,
                    "#chan",
                    HistoryPageLoader.Direction.OLDER,
                    history,
                    boundary = ChatHistoryReference("seed", 500),
                )
            } catch (error: Throwable) {
                observed = error
            }

            assertTrue(observed is IrcDisconnectedException)
            assertFalse(observed is CancellationException)
            assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
        }

    @Test
    fun leaderCancelledMidFlightDoesNotPoisonAnActiveFollower() =
        runTest {
            processor.process(networkId, chatMsg("seed", 500))
            val firstFetchStarted = CompletableDeferred<Unit>()
            val requests = mutableListOf<ChatHistoryRequest>()
            val history =
                object : HistoryPageLoader.HistorySource {
                    override suspend fun availability(): HistoryAvailability =
                        HistoryAvailability.Ready(
                            setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID),
                            100,
                        )

                    override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                        requests += req
                        if (requests.size == 1) {
                            // The leader's fetch hangs until its caller (a replaced Pager generation) is
                            // cancelled mid-flight.
                            firstFetchStarted.complete(Unit)
                            awaitCancellation()
                        }
                        return messages(listOf(chatMsg("older", 100)))
                    }
                }
            val leader =
                launch {
                    loadOlder(history, ChatHistoryReference("seed", 500))
                }
            firstFetchStarted.await()
            // A live generation's mediator joins the same (network, room, OLDER) flight as follower.
            val follower =
                async {
                    loadOlder(history, ChatHistoryReference("seed", 500))
                }
            runCurrent()
            leader.cancelAndJoin()

            // The follower must not adopt the leader's cancellation (that would freeze the live
            // generation's append LoadState at Loading); it retries and completes the page itself.
            val result = follower.await()
            assertTrue((result as HistoryPageLoader.PageResult.Loaded).primaryCount == 1)
            assertEquals(2, requests.size)
            assertEquals(2, rowCount())
        }

    @Test
    fun followerCancellationDoesNotCancelTheLeaderFlight() =
        runTest {
            processor.process(networkId, chatMsg("seed", 500))
            val fetchStarted = CompletableDeferred<Unit>()
            val releaseFetch = CompletableDeferred<Unit>()
            val requests = mutableListOf<ChatHistoryRequest>()
            val history =
                object : HistoryPageLoader.HistorySource {
                    override suspend fun availability(): HistoryAvailability =
                        HistoryAvailability.Ready(
                            setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID),
                            100,
                        )

                    override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                        requests += req
                        fetchStarted.complete(Unit)
                        releaseFetch.await()
                        return messages(listOf(chatMsg("older", 100)))
                    }
                }
            val leader =
                async {
                    loadOlder(history, ChatHistoryReference("seed", 500))
                }
            fetchStarted.await()
            val follower =
                launch {
                    loadOlder(history, ChatHistoryReference("seed", 500))
                }
            runCurrent()
            follower.cancelAndJoin()
            releaseFetch.complete(Unit)

            // Awaiting a shared flight must not propagate a follower's cancellation into the leader.
            val result = leader.await()
            assertTrue((result as HistoryPageLoader.PageResult.Loaded).primaryCount == 1)
            assertEquals(1, requests.size)
            assertEquals(2, rowCount())
        }

    @Test
    fun timestampOnlyAdvertisementTrimsMsgidFromPersistedBoundary() =
        runTest {
            processor.process(networkId, chatMsg("seed", 500))
            // The server returns a page carrying a msgid, but the network never advertised MSGID: the
            // stored cursor must be trimmed to the timestamp so a later BEFORE never sends that msgid.
            val history =
                FakeHistory(
                    before = ArrayDeque(listOf(listOf(chatMsg("older", 100)))),
                    referenceTypes = setOf(HistoryReferenceType.TIMESTAMP),
                )

            val result = loadOlder(history, ChatHistoryReference("seed", 500))

            assertTrue(result is HistoryPageLoader.PageResult.Loaded)
            assertEquals(
                listOf("timestamp=1970-01-01T00:00:00.500Z"),
                history.requests.map { it.bound1 },
            )
            assertNull(db.historyCursorDao().byRoom(bufferId)?.oldestMsgid)
            assertEquals(100L, db.historyCursorDao().byRoom(bufferId)?.oldestServerTime)
        }

    @Test
    fun msgidRejectionWithoutTimestampFallbackSurfacesTheServersOriginalError() =
        runTest {
            processor.process(networkId, chatMsg("OpaqueOnly", 500))
            val rejection = IrcCommandException("CHATHISTORY", "INVALID_MSGREFTYPE", "try timestamp")
            // MSGID-only advertisement: a runtime rejection leaves no timestamp fallback selector.
            val history =
                FakeHistory(
                    failureFor = { request -> rejection.takeIf { request.bound1 == "msgid=OpaqueOnly" } },
                    referenceTypes = setOf(HistoryReferenceType.MSGID),
                )

            val observed =
                runCatching {
                    loadOlder(history, ChatHistoryReference("OpaqueOnly", null))
                }.exceptionOrNull()

            // The loader must rethrow the server's own diagnostics (the exact exception instance, so
            // MediatorResult.Error carries them) instead of a misleading "no advertised selector"
            // failure fabricated after the pre-checks already proved a selector existed.
            assertSame(rejection, observed)
            assertEquals(1, history.requests.size)
        }

    @Test
    fun timeoutWhileQueuedBehindTheWireLockSurfacesAsRetryableFailure() =
        runTest {
            processor.process(networkId, chatMsg("seed", 500))
            val requests = mutableListOf<ChatHistoryRequest>()
            val history =
                object : HistoryPageLoader.HistorySource {
                    override suspend fun availability(): HistoryAvailability =
                        HistoryAvailability.Ready(
                            setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID),
                            100,
                        )

                    override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                        requests += req
                        return awaitCancellation()
                    }
                }
            // A hung LATEST occupies the wire lock for its whole timeout window.
            val hung =
                async {
                    runCatching {
                        loader.loadPage(networkId, bufferId, "#chan", HistoryPageLoader.Direction.LATEST, history)
                    }
                }
            runCurrent()

            // The caller's budget bounds the WHOLE operation, lock wait included: the queued OLDER
            // fetch must fail with the same retryable transport failure at its deadline instead of
            // stretching its budget behind the busy wire (it never reaches the socket).
            val queued =
                runCatching {
                    loadOlder(history, ChatHistoryReference("seed", 500))
                }
            assertTrue(queued.exceptionOrNull() is IrcDisconnectedException)
            assertTrue(hung.await().exceptionOrNull() is IrcDisconnectedException)
            assertEquals(1, requests.size)
        }

    @Test
    fun concurrentIdenticalOlderLoadsCoalesceOntoOneWireRequest() =
        runTest {
            processor.process(networkId, chatMsg("seed", 500))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val history =
                object : HistoryPageLoader.HistorySource {
                    override suspend fun availability(): HistoryAvailability =
                        HistoryAvailability.Ready(
                            setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID),
                            100,
                        )

                    override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                        calls++
                        entered.complete(Unit)
                        release.await()
                        return messages(listOf(chatMsg("older", 100)))
                    }
                }

            // Two live Pager generations issue the identical (network, room, OLDER) page. Only the leader
            // hits the wire; the follower joins its in-flight fetch.
            val leader = async { loadOlder(history, ChatHistoryReference("seed", 500)) }
            entered.await()
            val follower = async { loadOlder(history, ChatHistoryReference("seed", 500)) }
            runCurrent()
            release.complete(Unit)

            assertTrue((leader.await() as HistoryPageLoader.PageResult.Loaded).primaryCount == 1)
            assertTrue((follower.await() as HistoryPageLoader.PageResult.Loaded).primaryCount == 1)
            assertEquals(1, calls)
            assertEquals(2, rowCount())
        }

    @Test
    fun aGapDirectedOlderLoadNeverJoinsTheBottomOfTimelineLadder() =
        runTest {
            // The two demand sources on an unbounded timeline are asking different questions: `null` is
            // the bottom-of-timeline ladder, which pages strictly below every open gap, and a gap id is a
            // fill of one specific interior interval. Coalescing across that split hands the follower a
            // page for an interval it never requested and credits it with rows it did not fetch — its own
            // boundary never moves, so it reads its zero inserts as "this interval is exhausted", which
            // is exactly how a bounded gap fill ends after one page having achieved nothing.
            processor.process(networkId, chatMsg("seed", 500))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val bounds = mutableListOf<String?>()
            val history =
                object : HistoryPageLoader.HistorySource {
                    override suspend fun availability(): HistoryAvailability =
                        HistoryAvailability.Ready(
                            setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID),
                            100,
                        )

                    override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                        bounds += req.bound1
                        if (bounds.size == 1) {
                            entered.complete(Unit)
                            release.await()
                        }
                        return messages(listOf(chatMsg("older-${bounds.size}", 100L * bounds.size)))
                    }
                }

            val ladder = async { loadOlder(history, ChatHistoryReference("seed", 500)) }
            entered.await()
            val fill = async { loadOlder(history, ChatHistoryReference("gap-edge", 900), gapId = 7) }
            runCurrent()
            release.complete(Unit)
            ladder.await()
            fill.await()

            assertEquals(listOf("msgid=seed", "msgid=gap-edge"), bounds)
        }

    @Test
    fun twoGenerationsOfTheSameGapFillStillCoalesce() =
        runTest {
            // The original rationale is untouched: two live generations of the SAME question re-read the
            // store after each page and issue their next load from their own boundary, so joining
            // whichever page is in flight is still safe and still stops a generation swap double-fetching.
            processor.process(networkId, chatMsg("seed", 500))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val history =
                object : HistoryPageLoader.HistorySource {
                    override suspend fun availability(): HistoryAvailability =
                        HistoryAvailability.Ready(
                            setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID),
                            100,
                        )

                    override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                        calls++
                        entered.complete(Unit)
                        release.await()
                        return messages(listOf(chatMsg("older", 100)))
                    }
                }

            val leader = async { loadOlder(history, ChatHistoryReference("seed", 500), gapId = 7) }
            entered.await()
            val follower = async { loadOlder(history, ChatHistoryReference("seed", 500), gapId = 7) }
            runCurrent()
            release.complete(Unit)
            leader.await()
            follower.await()

            assertEquals(1, calls)
        }

    @Test
    fun distinctRoomsOnTheSameNetworkSerializeOnTheWire() =
        runTest {
            db.bufferDao().insert(
                BufferEntity(networkId = networkId, name = "#other", displayName = "#other", type = BufferType.CHANNEL),
            )
            val otherId = db.bufferDao().byName(networkId, "#other")!!.id
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val history =
                object : HistoryPageLoader.HistorySource {
                    override suspend fun availability(): HistoryAvailability =
                        HistoryAvailability.Ready(
                            setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID),
                            100,
                        )

                    override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                        order += "start:${req.target}"
                        if (req.target == "#chan") {
                            firstEntered.complete(Unit)
                            releaseFirst.await()
                        }
                        return messages(listOf(chatMsg("older", 100)))
                    }
                }

            val first =
                launch {
                    loader.loadPage(
                        networkId,
                        bufferId,
                        "#chan",
                        HistoryPageLoader.Direction.OLDER,
                        history,
                        boundary = ChatHistoryReference("seedChan", 500),
                    )
                }
            firstEntered.await()
            val second =
                async {
                    loader.loadPage(
                        networkId,
                        otherId,
                        "#other",
                        HistoryPageLoader.Direction.OLDER,
                        history,
                        boundary = ChatHistoryReference("seedOther", 400),
                    )
                }
            runCurrent()

            // The second room's fetch is blocked on the shared per-network wire lock: it has not started.
            assertEquals(listOf("start:#chan"), order)
            releaseFirst.complete(Unit)
            second.await()
            first.join()
            assertEquals(listOf("start:#chan", "start:#other"), order)
        }

    @Test
    fun gapDirectedOlderFillResolvesTheTargetedGap() =
        runTest {
            processor.process(networkId, chatMsg("seed", 500))
            val gapId =
                db.historyGapDao().insert(
                    HistoryGapEntity(
                        roomId = bufferId,
                        olderMsgid = "mid",
                        olderServerTime = 300,
                        newerMsgid = "seed",
                        newerServerTime = 500,
                    ),
                )
            val history =
                FakeHistory(
                    responseFor = { request ->
                        messages(listOf(chatMsg("old", 100)), endOfHistory = true)
                            .takeIf { request.subcommand == ChatHistoryRequest.Subcommand.BEFORE }
                    },
                )

            val result =
                loader.loadPage(
                    networkId,
                    bufferId,
                    "#chan",
                    HistoryPageLoader.Direction.OLDER,
                    history,
                    gapId = gapId,
                    boundary = ChatHistoryReference("seed", 500),
                )

            // A terminal older page routed into the focused gap reaches its older boundary and closes it.
            assertTrue((result as HistoryPageLoader.PageResult.Loaded).endOfDirection)
            assertTrue(db.historyGapDao().forRoom(bufferId).isEmpty())
            assertTrue(db.messageDao().byMsgid(bufferId, "old") != null)
        }

    private val bothRefs = setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID)

    private fun latestRequest(target: String) = ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, target, limit = 50)

    @Test
    fun concurrentFetchesShareTheWireWhenAllowed() =
        runTest {
            var entered = 0
            val release = CompletableDeferred<Unit>()
            val history =
                object : HistoryPageLoader.HistorySource {
                    override suspend fun availability(): HistoryAvailability = HistoryAvailability.Ready(bothRefs, 100, supportsConcurrentRequests = true)

                    override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                        entered++
                        release.await()
                        return messages(emptyList(), endOfHistory = true)
                    }
                }

            val first =
                async {
                    loader.fetchMessages(
                        networkId,
                        history,
                        latestRequest("#chan"),
                        bothRefs,
                        msgidAllowed = true,
                        timeoutMs = 5_000,
                        allowConcurrent = true,
                    )
                }
            val second =
                async {
                    loader.fetchMessages(
                        networkId,
                        history,
                        latestRequest("#other"),
                        bothRefs,
                        msgidAllowed = true,
                        timeoutMs = 5_000,
                        allowConcurrent = true,
                    )
                }
            runCurrent()

            // Both requests are on the wire before either completes: the gate admits more than one.
            assertEquals(2, entered)
            release.complete(Unit)
            first.await()
            second.await()
        }

    @Test
    fun wireWidthCollapsesToOneAcrossACapabilityChange() =
        runTest {
            var entered = 0
            var blocking = false
            val release = CompletableDeferred<Unit>()
            val history =
                object : HistoryPageLoader.HistorySource {
                    override suspend fun availability(): HistoryAvailability = HistoryAvailability.Ready(bothRefs, 100)

                    override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                        entered++
                        if (blocking) release.await()
                        return messages(emptyList(), endOfHistory = true)
                    }
                }

            // A labeled-response connection widened this network's gate...
            loader.fetchMessages(
                networkId,
                history,
                latestRequest("#chan"),
                bothRefs,
                msgidAllowed = true,
                timeoutMs = 5_000,
                allowConcurrent = true,
            )
            assertEquals(1, entered)

            // ...then a reconnect without the cap must fall back to strict serialization.
            blocking = true
            val first =
                async {
                    loader.fetchMessages(
                        networkId,
                        history,
                        latestRequest("#chan"),
                        bothRefs,
                        msgidAllowed = true,
                        timeoutMs = 5_000,
                    )
                }
            val second =
                async {
                    loader.fetchMessages(
                        networkId,
                        history,
                        latestRequest("#other"),
                        bothRefs,
                        msgidAllowed = true,
                        timeoutMs = 5_000,
                    )
                }
            runCurrent()
            assertEquals(2, entered)
            release.complete(Unit)
            first.await()
            second.await()
            assertEquals(3, entered)
        }

    @Test
    fun releasingARetiredNetworkLetsItsSuccessorPastAParkedRequest() =
        runTest {
            // networkGates is keyed by network and lives for the process, so a request left parked on a
            // socket that is already gone would otherwise gate the connection that replaces it — and a
            // deleted network's semaphore would never be reclaimed at all. Retirement is the one moment
            // anything drops a gate.
            var entered = 0
            val parked = CompletableDeferred<Unit>()
            val history =
                object : HistoryPageLoader.HistorySource {
                    override suspend fun availability(): HistoryAvailability = HistoryAvailability.Ready(bothRefs, 100)

                    override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                        entered++
                        if (entered == 1) parked.await()
                        return messages(emptyList(), endOfHistory = true)
                    }
                }

            val predecessor =
                async {
                    loader.fetchMessages(
                        networkId,
                        history,
                        latestRequest("#chan"),
                        bothRefs,
                        msgidAllowed = true,
                        timeoutMs = 30_000,
                    )
                }
            runCurrent()
            assertEquals(1, entered)

            // Same connection, same gate: strict serialization is exactly what the gate is for.
            val sameConnection =
                async {
                    loader.fetchMessages(
                        networkId,
                        history,
                        latestRequest("#other"),
                        bothRefs,
                        msgidAllowed = true,
                        timeoutMs = 30_000,
                    )
                }
            runCurrent()
            assertEquals(1, entered)

            // The connection is retired. Its replacement is a different wire, so it must not queue
            // behind a page that is never coming.
            loader.releaseNetwork(networkId)
            val successor =
                async {
                    loader.fetchMessages(
                        networkId,
                        history,
                        latestRequest("#new"),
                        bothRefs,
                        msgidAllowed = true,
                        timeoutMs = 30_000,
                    )
                }
            runCurrent()
            assertEquals(2, entered)
            successor.await()

            parked.complete(Unit)
            predecessor.await()
            sameConnection.await()
            assertEquals(3, entered)
        }

    @Test
    fun timeoutWhileQueuedBehindAFullGateSurfacesAsRetryableFailure() =
        runTest {
            val requests = mutableListOf<String>()
            val history =
                object : HistoryPageLoader.HistorySource {
                    override suspend fun availability(): HistoryAvailability = HistoryAvailability.Ready(bothRefs, 100, supportsConcurrentRequests = true)

                    override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                        requests += req.target
                        return awaitCancellation()
                    }
                }

            val width = HistoryPageLoader.MAX_CONCURRENT_WIRE_REQUESTS
            val hung =
                (1..width).map { slot ->
                    async {
                        runCatching {
                            loader.fetchMessages(
                                networkId,
                                history,
                                latestRequest("#hung$slot"),
                                bothRefs,
                                msgidAllowed = true,
                                timeoutMs = 60_000,
                                allowConcurrent = true,
                            )
                        }
                    }
                }
            runCurrent()
            assertEquals(width, requests.size)

            // Every permit is held: the queued fetch's budget still bounds permit wait, surfacing as a
            // retryable transport failure instead of stretching behind the busy wire.
            val queued =
                runCatching {
                    loader.fetchMessages(
                        networkId,
                        history,
                        latestRequest("#queued"),
                        bothRefs,
                        msgidAllowed = true,
                        timeoutMs = 1_000,
                        retryableTimeout = true,
                        allowConcurrent = true,
                    )
                }
            assertTrue(queued.exceptionOrNull() is IrcDisconnectedException)
            assertEquals(width, requests.size)
            hung.forEach { it.cancelAndJoin() }
        }

    @Test
    fun aTimedOutLatestFlightReportsOneTypedOutcomeToEveryJoiner() =
        runTest {
            // The newest-page flight is shared by Paging's empty-store seed and the catch-up
            // coordinator's per-target seed, and they need different things from a timeout: a retryable
            // transport failure and a per-target timeout it can adapt its fan-out to. So the flight
            // itself completes with ONE typed outcome and each caller classifies it — whichever of them
            // happens to lead. The leader classifying for itself is how a Paging-led timeout used to
            // reach the coordinator as a transport failure and abort a whole catch-up pass.
            val requests = mutableListOf<String>()
            val history =
                object : HistoryPageLoader.HistorySource {
                    override suspend fun availability(): HistoryAvailability = HistoryAvailability.Ready(bothRefs, 100, supportsConcurrentRequests = true)

                    override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                        requests += req.target
                        return awaitCancellation()
                    }
                }
            loader.requestTimeoutMs = 5_000

            // Paging leads.
            val paging =
                async {
                    runCatching {
                        loader.loadPage(networkId, bufferId, "#chan", HistoryPageLoader.Direction.LATEST, history)
                    }
                }
            runCurrent()
            // The coordinator's seed joins the flight already on the wire.
            val coordinator =
                async {
                    runCatching {
                        loader.fetchLatest(
                            networkId,
                            bufferId,
                            "#chan",
                            history,
                            requestLimit = 50,
                            referenceTypes = bothRefs,
                            timeoutMs = 60_000,
                            allowConcurrent = true,
                        )
                    }
                }
            runCurrent()
            assertEquals(listOf("#chan"), requests)

            testScheduler.advanceTimeBy(10_000)

            // Paging's classification: a retryable transport failure, never a cancellation.
            val pagingError = paging.await().exceptionOrNull()
            assertTrue(pagingError is IrcDisconnectedException)
            assertFalse(pagingError is CancellationException)
            // The coordinator's: the typed marker its per-target timeout branch reads.
            val joined = coordinator.await().exceptionOrNull()
            assertTrue(joined is HistoryPageLoader.LatestFlightTimeoutException)
            assertFalse(joined is CancellationException)
            // A marker completion is not abandonment, so no follower re-leads the flight.
            assertEquals(listOf("#chan"), requests)
        }

    // --- AROUND (deep link / reply quote), routed through the loader ---------------------------

    /** Scripts one AROUND page centred on [msgid]; anything else answers empty. */
    private fun aroundSource(
        referenceTypes: Set<HistoryReferenceType> = bothRefs,
        availability: HistoryAvailability? = null,
        onRequest: suspend (ChatHistoryRequest) -> Unit = {},
        respond: (ChatHistoryRequest) -> ChatHistoryResponse = { messages(emptyList()) },
    ): Pair<HistoryPageLoader.HistorySource, MutableList<ChatHistoryRequest>> {
        val seen = java.util.Collections.synchronizedList(mutableListOf<ChatHistoryRequest>())
        val source =
            object : HistoryPageLoader.HistorySource {
                override suspend fun availability(): HistoryAvailability = availability ?: HistoryAvailability.Ready(referenceTypes, 100)

                override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                    seen += req
                    onRequest(req)
                    return respond(req)
                }
            }
        return source to seen
    }

    private suspend fun loadAround(
        source: HistoryPageLoader.HistorySource,
        msgid: String = "target",
        timeMs: Long = 500,
        limit: Int = 100,
    ) = loader.loadAround(networkId, bufferId, "#chan", msgid, timeMs, limit, source)

    @Test
    fun aroundPersistsItsPageThroughTheLoader() =
        runTest {
            val (source, requests) =
                aroundSource(
                    respond = { messages(listOf(chatMsg("older", 400), chatMsg("target", 500), chatMsg("newer", 600))) },
                )

            val result = loadAround(source)

            assertTrue(result is HistoryPageLoader.PageResult.Loaded)
            assertEquals(3, (result as HistoryPageLoader.PageResult.Loaded).insertedCount)
            assertEquals(3, rowCount())
            assertEquals(ChatHistoryRequest.Subcommand.AROUND, requests.single().subcommand)
            // The opaque msgid selector is preferred whenever the server advertises it.
            assertEquals("msgid=target", requests.single().bound1)
        }

    @Test
    fun aroundGatesOnAvailabilityInsteadOfIssuingARequest() =
        runTest {
            val (unsupported, unsupportedRequests) =
                aroundSource(availability = HistoryAvailability.Unsupported)
            assertEquals(HistoryPageLoader.PageResult.Unsupported, loadAround(unsupported))
            assertTrue(unsupportedRequests.isEmpty())

            val (offline, offlineRequests) =
                aroundSource(availability = HistoryAvailability.NegotiatingOrOffline)
            val result = loadAround(offline)
            assertTrue(result is HistoryPageLoader.PageResult.Unavailable)
            assertTrue(offlineRequests.isEmpty())
        }

    @Test
    fun aroundWithoutAnAdvertisedAnchorFailsWithoutARequest() =
        runTest {
            // Neither selector is advertised, so there is no anchor this server would accept.
            val (source, requests) = aroundSource(referenceTypes = emptySet())

            assertTrue(loadAround(source) is HistoryPageLoader.PageResult.Failed)
            assertTrue(requests.isEmpty())
        }

    @Test
    fun aroundFallsBackToTheTimestampSelectorOnRuntimeMsgidRejection() =
        runTest {
            val (source, requests) =
                aroundSource(
                    onRequest = { req ->
                        if (req.bound1.orEmpty().startsWith("msgid=")) {
                            throw IrcCommandException("CHATHISTORY", "INVALID_MSGREFTYPE", "no msgid selectors")
                        }
                    },
                    respond = { messages(listOf(chatMsg("target", 500))) },
                )

            assertTrue(loadAround(source) is HistoryPageLoader.PageResult.Loaded)
            assertEquals(
                listOf("msgid=target", "timestamp=1970-01-01T00:00:00.500Z"),
                requests.map { it.bound1 },
            )
            // The page persists against the request that actually produced it, not the rejected one.
            assertEquals(1, rowCount())
        }

    @Test
    fun concurrentJumpsToTheSameMessageCoalesceButDifferentAnchorsDoNot() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val (source, requests) =
                aroundSource(
                    onRequest = { release.await() },
                    respond = { req ->
                        messages(listOf(chatMsg(req.bound1.orEmpty().removePrefix("msgid="), 500)))
                    },
                )

            val first = async { loadAround(source, msgid = "target") }
            val joined = async { loadAround(source, msgid = "target") }
            val other = async { loadAround(source, msgid = "elsewhere") }
            runCurrent()
            // Same anchor joins the in-flight fetch; a different anchor is a different interval and
            // waits for its own turn on the wire rather than being credited with this page.
            assertEquals(listOf("msgid=target"), requests.map { it.bound1 })

            release.complete(Unit)
            assertTrue(first.await() is HistoryPageLoader.PageResult.Loaded)
            assertTrue(joined.await() is HistoryPageLoader.PageResult.Loaded)
            assertTrue(other.await() is HistoryPageLoader.PageResult.Loaded)
            assertEquals(listOf("msgid=target", "msgid=elsewhere"), requests.map { it.bound1 })
        }

    @Test
    fun aroundMakesExactlyOneAttemptAndReportsATimeoutAsUnavailable() =
        runTest {
            val (source, requests) = aroundSource(onRequest = { awaitCancellation() })
            loader.requestTimeoutMs = 1_000

            val result = loadAround(source)

            // A jump either lands or reports not-found: a timeout must neither retry nor escape as a
            // cancellation that would kill the caller's positioning job.
            assertTrue(result is HistoryPageLoader.PageResult.Unavailable)
            assertEquals(1, requests.size)
        }

    @Test
    fun aroundPreservesTheOpaqueMsgidAndClampsToTheServerPageLimit() =
        runTest {
            val (source, requests) =
                aroundSource(
                    availability = HistoryAvailability.Ready(bothRefs, pageLimit = 50),
                )

            loadAround(source, msgid = "MiXeD/opaque=Value", timeMs = 200, limit = 100)

            // IRCv3 message references are opaque and case-sensitive; the request must not normalize it.
            assertEquals("msgid=MiXeD/opaque=Value", requests.single().bound1)
            assertEquals(50, requests.single().limit)
        }

    @Test
    fun aroundBoundsServerOverdeliveryAroundTheRequestedMessageBeforePersistence() =
        runTest {
            val overDelivered = messages((1..100).map { chatMsg("m$it", it.toLong()) }, endOfHistory = true)
            val (source, _) = aroundSource(respond = { overDelivered })

            val result = loadAround(source, msgid = "m50", timeMs = 50, limit = 2)

            assertEquals(2, (result as HistoryPageLoader.PageResult.Loaded).primaryCount)
            assertEquals(listOf("m50", "m49"), rows().map { it.msgid })
            // The retained window is a durable route back to the discarded interval, never terminal.
            assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
        }

    @Test
    fun aroundTimestampFallbackStillCentersOverdeliveryOnTheRequestedMsgid() =
        runTest {
            // Every row shares one timestamp, so only the preferred msgid can centre the window.
            val overDelivered = messages((1..100).map { chatMsg("m$it", 50) }, endOfHistory = true)
            val (source, requests) =
                aroundSource(
                    onRequest = { req ->
                        if (req.bound1.orEmpty().startsWith("msgid=")) {
                            throw IrcCommandException("CHATHISTORY", "INVALID_MSGREFTYPE", "no msgid")
                        }
                    },
                    respond = { overDelivered },
                )

            loadAround(source, msgid = "m90", timeMs = 50, limit = 2)

            assertEquals(2, requests.size)
            assertEquals(setOf("m89", "m90"), rows().mapNotNull { it.msgid }.toSet())
        }

    @Test
    fun aroundDoesNotRetryFailuresThatAreNotAMsgidReferenceRejection() =
        runTest {
            listOf(
                IrcDisconnectedException("CHATHISTORY", "lost connection"),
                java.io.IOException("read failed"),
                IrcCommandException("CHATHISTORY", "MESSAGE_ERROR", "request rejected"),
            ).forEach { expected ->
                val (source, requests) = aroundSource(onRequest = { throw expected })

                val failure =
                    runCatching { loadAround(source, msgid = "ExactCase", timeMs = 200) }
                        .exceptionOrNull()

                assertSame(expected, failure)
                assertEquals(listOf("msgid=ExactCase"), requests.map { it.bound1 })
                assertEquals(0, rowCount())
            }
        }
}
