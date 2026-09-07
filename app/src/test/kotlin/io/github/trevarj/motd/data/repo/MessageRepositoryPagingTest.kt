package io.github.trevarj.motd.data.repo

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import io.github.trevarj.motd.data.db.EventRedirectEntity
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.history.seamAbove
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilityReader
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.messagePagingQuery
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageRepositoryPagingTest {
    private lateinit var db: MotdDatabase
    private lateinit var reader: MessageVisibilityReader
    private var bufferId = 0L

    @Before
    fun setUp() =
        runTest {
            db = inMemoryDb()
            reader = MessageVisibilityReader(db)
            val networkId = db.networkDao().insert(network())
            bufferId = db.bufferDao().insert(buffer(networkId, "#paging"))
        }

    @After
    fun tearDown() = db.close()

    @OptIn(ExperimentalPagingApi::class)
    private fun repository() =
        MessageRepositoryImpl(
            db.bufferDao(),
            db.networkIdentityDao(),
            db.messageDao(),
            db.reactionDao(),
            ChatHistoryMediatorFactory { _, _, _ -> error("paging is driven by the source directly here") },
            db.historyGapDao(),
        )

    @Test
    fun observeReplyTarget_keepsLocalParentThroughPromotionAndRedaction() =
        runTest {
            val dao = db.messageDao()
            val parentId =
                dao
                    .insertAll(
                        listOf(message(bufferId, "local parent", serverTime = 100, dedupKey = "pending", isSelf = true)),
                    ).single()
            val updates = Channel<MessageEntity?>(Channel.UNLIMITED)
            backgroundScope.launch {
                repository().observeReplyTarget(bufferId, parentId, null).collect { updates.send(it) }
            }
            val parent = checkNotNull(updates.receive())
            assertEquals(parentId, parent.id)
            assertEquals("local parent", parent.text)
            assertNull(parent.msgid)

            dao.update(parent.copy(msgid = "echo-id"))
            val promoted = checkNotNull(updates.receive())
            assertEquals(parentId, promoted.id)
            assertEquals("echo-id", promoted.msgid)

            dao.update(promoted.copy(kind = MessageKind.REDACTED, text = ""))
            val redacted = checkNotNull(updates.receive())
            assertEquals(parentId, redacted.id)
            assertEquals(MessageKind.REDACTED, redacted.kind)
            assertEquals("", redacted.text)

            dao.deleteById(parentId)
            assertNull(updates.receive())
        }

    @Test
    fun observeReplyTarget_prefersLocalParentAndScopesMissingReferenceFallback() =
        runTest {
            val dao = db.messageDao()
            val repository = repository()
            val missingId = 100L
            val updates = Channel<MessageEntity?>(Channel.UNLIMITED)
            backgroundScope.launch {
                repository.observeReplyTarget(bufferId, missingId, "late-parent").collect { updates.send(it) }
            }
            assertNull(updates.receive())
            val fallbackId =
                dao
                    .insertAll(
                        listOf(message(bufferId, "history parent", serverTime = 100, dedupKey = "late", msgid = "late-parent")),
                    ).single()
            assertEquals(fallbackId, updates.receive()?.id)
            assertEquals(fallbackId, repository.observeReplyTarget(bufferId, null, "late-parent").first()?.id)

            dao.insertAll(
                listOf(message(bufferId, "local parent", serverTime = 200, dedupKey = "local").copy(id = missingId)),
            )
            assertEquals(missingId, updates.receive()?.id)

            val otherRoom = db.bufferDao().insert(buffer(db.bufferDao().observeById(bufferId)!!.networkId, "#other"))
            val foreignId =
                dao
                    .insertAll(
                        listOf(message(otherRoom, "foreign parent", serverTime = 300, dedupKey = "foreign", msgid = "foreign-id")),
                    ).single()
            assertEquals(fallbackId, repository.observeReplyTarget(bufferId, foreignId, "late-parent").first()?.id)
            assertNull(repository.observeReplyTarget(bufferId, foreignId, "foreign-id").first())
            assertNull(repository.observeReplyTarget(bufferId, foreignId, null).first())
            assertNull(repository.observeReplyTarget(bufferId, null, null).first())
        }

    @Test
    fun observeReplyTarget_followsEventAndRoomRedirects() =
        runTest {
            val dao = db.messageDao()
            val repository = repository()
            val ids =
                dao.insertAll(
                    listOf(
                        message(bufferId, "optimistic parent", serverTime = 100, dedupKey = "pending"),
                        message(bufferId, "canonical parent", serverTime = 200, dedupKey = "canonical", msgid = "parent-id"),
                    ),
                )
            val winnerRoom = db.bufferDao().insert(buffer(db.bufferDao().observeById(bufferId)!!.networkId, "#winner"))
            val winnerId =
                dao
                    .insertAll(
                        listOf(message(winnerRoom, "merged parent", serverTime = 300, dedupKey = "merged", msgid = "parent-id")),
                    ).single()
            val updates = Channel<MessageEntity?>(Channel.UNLIMITED)
            backgroundScope.launch {
                repository.observeReplyTarget(bufferId, ids[0], "parent-id").collect { updates.send(it) }
            }
            assertEquals(ids[0], updates.receive()?.id)

            // Only the redirects table changes: the old row must not mask its canonical parent.
            db.canonicalTimelineDao().upsertEventRedirect(EventRedirectEntity(ids[0], ids[1]))
            assertEquals(ids[1], updates.receive()?.id)
            assertEquals(ids[1], repository.observeReplyTarget(bufferId, ids[0], null).first()?.id)

            // Only the room changes: the old canonical event is now outside the requested room.
            db.roomAliasDao().markRedirect(bufferId, winnerRoom)
            assertEquals(winnerId, updates.receive()?.id)
            assertNull(repository.observeReplyTarget(bufferId, ids[0], null).first())

            db.canonicalTimelineDao().upsertEventRedirect(EventRedirectEntity(ids[0], winnerId))
            assertEquals(winnerId, repository.observeReplyTarget(bufferId, ids[0], null).first()?.id)
        }

    @Test
    fun pagingConfigIsPlaceholderAwareAndBounded() {
        assertEquals(50, MESSAGE_PAGING_CONFIG.pageSize)
        assertEquals(25, MESSAGE_PAGING_CONFIG.prefetchDistance)
        assertTrue(MESSAGE_PAGING_CONFIG.enablePlaceholders)
        assertEquals(500, MESSAGE_PAGING_CONFIG.maxSize)
        assertEquals(250, MESSAGE_PAGING_CONFIG.jumpThreshold)
    }

    @OptIn(ExperimentalPagingApi::class)
    @Test
    fun pagingAttachesMediatorWithCanonicalRoomAndVisibilityContext() =
        runTest {
            // Mediator and PagingSource must use one coordinate space or hidden raw pages can be
            // mistaken for visible paging progress.
            var mediatorRoomId: Long? = null
            var mediatorVisibility: MessageVisibilitySpec? = null
            var mediatorIdentityRules: IrcIdentityRules? = null
            val repository =
                MessageRepositoryImpl(
                    db.bufferDao(),
                    db.networkIdentityDao(),
                    db.messageDao(),
                    db.reactionDao(),
                    ChatHistoryMediatorFactory { roomId, visibility, identityRules ->
                        mediatorRoomId = roomId
                        mediatorVisibility = visibility
                        mediatorIdentityRules = identityRules
                        object : RemoteMediator<Int, MessageEntity>() {
                            override suspend fun load(
                                loadType: LoadType,
                                state: PagingState<Int, MessageEntity>,
                            ) = MediatorResult.Success(endOfPaginationReached = true)
                        }
                    },
                    db.historyGapDao(),
                )

            val visibility = MessageVisibilitySpec(presenceMode = PresenceMode.HIDDEN)
            repository.messages(bufferId, visibility).first()

            assertEquals(bufferId, mediatorRoomId)
            assertEquals(visibility, mediatorVisibility)
            assertEquals(IrcIdentityRules(), mediatorIdentityRules)
        }

    @Test
    fun sameTimestampGapPresentsBothIslandsWithASeamBetweenThem() =
        runTest {
            // The sharpest shape of the retired clamp. Two rows share serverTime 100 and are told apart
            // only by their exact `(timelineOrder, id)` tuples, and a gap sits between them. The lower
            // boundary this used to derive was inclusive at the newer row, so the older row — durable,
            // intact, and the user's own history — was excluded from the presented window with nothing
            // on screen to say so.
            //
            // Nothing derives a boundary from a gap now, so BOTH rows are presented and the break between
            // them is rendered instead of applied: exactly one seam, in the newer row's slot.
            val olderId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(bufferId, "older", "alice", 100, "older", msgid = "older")),
                    ).single()
            val newerId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(bufferId, "newer", "alice", 100, "newer", msgid = "newer")),
                    ).single()
            val older = checkNotNull(db.messageDao().byCanonicalId(olderId))
            val newer = checkNotNull(db.messageDao().byCanonicalId(newerId))
            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = bufferId,
                    olderMsgid = "older",
                    olderServerTime = 100,
                    olderEventId = olderId,
                    olderTimelineOrder = older.timelineOrder,
                    newerMsgid = "newer",
                    newerServerTime = 100,
                    newerEventId = newerId,
                    newerTimelineOrder = newer.timelineOrder,
                ),
            )
            val repository = repository()
            // Exactly the query the shipped Pager builds: no boundary is derived from the gap at all,
            // which is what keeps the older equal-timestamp row on screen.
            val page =
                db
                    .messageDao()
                    .pagingSource(
                        messagePagingQuery(bufferId, MessageVisibilitySpec()),
                    ).load(
                        PagingSource.LoadParams.Refresh(null, 50, false),
                    ).requirePage()

            assertEquals(listOf(newerId, olderId), page.data.map { it.id })

            // The seam lands where the clamp used to cut, and only there: it takes the same projection
            // the old lower boundary took, so the cut is drawn in the position it was previously applied.
            val seams = repository.observeTimelineSeams(bufferId, MessageVisibilitySpec()).first()
            assertEquals(TimelineAnchor(100, newer.id, newer.timelineOrder), seams.single().position)
            val placements =
                page.data.mapIndexedNotNull { index, row ->
                    seamAbove(row, page.data.getOrNull(index + 1), seams)?.let { row.text to it.gapId }
                }
            assertEquals(listOf("newer" to seams.single().gapId), placements)
        }

    @Test
    fun fiftyThousandRowsKeepDeepRefreshCountAndSavedAnchorExact() =
        runTest {
            val totalRows = 50_000
            val targetOrdinal = 12_347
            for (start in 1..totalRows step 500) {
                db.messageDao().insertAll(
                    (start until minOf(start + 500, totalRows + 1)).map { ordinal ->
                        message(
                            bufferId = bufferId,
                            text = "row-$ordinal",
                            sender = if (ordinal % 17 == 0) "fool" else "person",
                            serverTime = ordinal.toLong(),
                            dedupKey = "row-$ordinal",
                            msgid = "row-$ordinal",
                            kind = if (ordinal % 19 == 0) MessageKind.JOIN else MessageKind.PRIVMSG,
                        )
                    },
                )
            }
            val spec =
                MessageVisibilitySpec(
                    presenceMode = PresenceMode.HIDDEN,
                    fools = setOf("fool"),
                    foolsMode = FoolsMode.HIDE,
                )
            val target = checkNotNull(db.messageDao().byMsgid(bufferId, "row-$targetOrdinal"))
            val targetIndex = reader.countTimelineNewer(bufferId, target.serverTime, target.id, spec)
            val source = db.messageDao().pagingSource(messagePagingQuery(bufferId, spec))
            val result =
                source
                    .load(
                        PagingSource.LoadParams.Refresh(
                            key = targetIndex,
                            loadSize = MESSAGE_PAGING_CONFIG.pageSize,
                            placeholdersEnabled = true,
                        ),
                    ).requirePage()

            val localTargetIndex = targetIndex - result.itemsBefore
            assertTrue(localTargetIndex in result.data.indices)
            assertEquals(target.id, result.data[localTargetIndex].id)
            assertEquals(targetIndex, reader.countTimelineNewer(bufferId, target.serverTime, target.id, spec))
            assertEquals(
                target.id,
                reader
                    .resolveSavedAnchor(
                        bufferId,
                        target.msgid,
                        target.serverTime,
                        target.id,
                        spec,
                    )?.id,
            )
            assertTrue(result.data.size <= MESSAGE_PAGING_CONFIG.pageSize)
            val expectedVisible = (1..totalRows).count { it % 17 != 0 && it % 19 != 0 }
            assertEquals(expectedVisible, result.itemsBefore + result.data.size + result.itemsAfter)
        }

    @Test
    fun entryIndexIsOneDomainAcrossTheRepositoryAndTheVisibilityReader() =
        runTest {
            // The normal-entry rule (preferredEntryTarget) compares three indices that arrive by two
            // different code paths: the unread anchor comes from MessageRepositoryImpl.countNewerThan,
            // the saved viewport and the furthest-displayed watermark from
            // MessageVisibilityReader.countTimelineNewer. They agree today because both funnel into
            // countTimelineNewerQuery with the same room resolution, identity rules, spec, and
            // (serverTime, timelineOrder, id) tie-break — and nothing but this test enforces that.
            //
            // If either grows a predicate the other lacks, the entry rule silently compares two
            // coordinate systems: the wrong anchor wins near ties, and entryPagingKey keys the Pager at
            // a position the entry target does not scroll to. No other test would fail, because every
            // other test exercises one path or the other, never both against each other.
            //
            // The list-position oracle is the third leg: it pins both paths to the ORDER the
            // PagingSource actually presents, so a predicate added to both at once still fails here.
            val repository = repository()
            (1..40).forEach { ordinal ->
                db.messageDao().insertAll(
                    listOf(
                        message(
                            bufferId = bufferId,
                            text = "row-$ordinal",
                            // Deliberate ties: consecutive rows share a millisecond in pairs, so the
                            // (timelineOrder, id) tie-break decides their order and any drift in it
                            // shows up as an off-by-one right where entry decisions are closest.
                            sender =
                                when {
                                    ordinal % 7 == 0 -> "fool"
                                    ordinal % 11 == 0 -> "me"
                                    else -> "person"
                                },
                            serverTime = (ordinal / 2).toLong() + 1,
                            dedupKey = "row-$ordinal",
                            msgid = "row-$ordinal",
                            kind = if (ordinal % 5 == 0) MessageKind.JOIN else MessageKind.PRIVMSG,
                            isSelf = ordinal % 11 == 0,
                        ),
                    ),
                )
            }
            val specs =
                listOf(
                    MessageVisibilitySpec(),
                    MessageVisibilitySpec(presenceMode = PresenceMode.HIDDEN),
                    MessageVisibilitySpec(fools = setOf("fool"), foolsMode = FoolsMode.HIDE),
                    MessageVisibilitySpec(
                        presenceMode = PresenceMode.HIDDEN,
                        fools = setOf("fool"),
                        foolsMode = FoolsMode.COLLAPSE,
                    ),
                )

            for (spec in specs) {
                val presented =
                    db
                        .messageDao()
                        .pagingSource(messagePagingQuery(bufferId, spec))
                        .load(PagingSource.LoadParams.Refresh(null, 100, false))
                        .requirePage()
                        .data
                for (ordinal in 1..40) {
                    val row = checkNotNull(db.messageDao().byMsgid(bufferId, "row-$ordinal"))
                    val fromRepository = repository.countNewerThan(bufferId, row.serverTime, row.id, spec)
                    val fromReader = reader.countTimelineNewer(bufferId, row.serverTime, row.id, spec)
                    assertEquals(
                        "row-$ordinal under $spec: entry indices must come from one domain",
                        fromReader,
                        fromRepository,
                    )
                    // Rows the spec hides have no position of their own; the shared count still has to
                    // name the slot they would occupy, which is where the presented list splits.
                    val presentedIndex = presented.indexOfFirst { it.id == row.id }
                    if (presentedIndex >= 0) {
                        assertEquals(
                            "row-$ordinal under $spec: the index must be its list position",
                            presentedIndex,
                            fromRepository,
                        )
                    }
                }
            }
        }

    @Test
    fun chatListUnreadCueAndEntryUnreadAnchorCountTheSameRows() =
        runTest {
            // The chat-list badge (observeChatList's unreadCount SQL in Daos.kt) and the entry divider
            // (firstVisibleUnreadQuery) are two statements of one promise: "N new messages" means entry
            // can land on the first of them. If either counts a row class the other lacks, the badge
            // shows N > 0 while entry resolves nothing and silently parks at the bottom — payload-bearing
            // DCC offers were exactly such a class. Enumerating the entry anchor row by row must
            // reproduce the cue count: for a read room from its marker, and for a never-read room from
            // the epoch anchor entry resolves with (matching the cue's COALESCE of the missing anchor
            // to time zero).
            val networkId = db.networkDao().insert(network("parity"))
            val readId = db.bufferDao().insert(buffer(networkId, "#read", readMarkerTime = 10))
            val neverReadId = db.bufferDao().insert(buffer(networkId, "#never-read"))
            val spec = MessageVisibilitySpec()

            // One of every row class either side counts, per buffer. Expected cue membership:
            // conversation kinds from someone else and payload-bearing incoming DCC offers; never self
            // rows, presence rows, or payload-less DCC records.
            suspend fun seed(roomId: Long): List<Long> {
                val counted =
                    db.messageDao().insertAll(
                        listOf(
                            message(roomId, "old chat", serverTime = 5, dedupKey = "old"),
                            message(roomId, "chat", serverTime = 11, dedupKey = "chat"),
                            message(roomId, "notice", serverTime = 12, dedupKey = "notice", kind = MessageKind.NOTICE),
                            message(roomId, "action", serverTime = 13, dedupKey = "action", kind = MessageKind.ACTION),
                            message(roomId, "offer", serverTime = 16, dedupKey = "offer", kind = MessageKind.DCC_TRANSFER)
                                .copy(eventPayload = "payload-v1"),
                        ),
                    )
                db.messageDao().insertAll(
                    listOf(
                        message(roomId, "own", serverTime = 14, dedupKey = "own", isSelf = true),
                        message(roomId, "join", serverTime = 15, dedupKey = "join", kind = MessageKind.JOIN),
                        message(roomId, "offer record", serverTime = 17, dedupKey = "record", kind = MessageKind.DCC_TRANSFER),
                        message(roomId, "own offer", serverTime = 18, dedupKey = "own-offer", kind = MessageKind.DCC_TRANSFER, isSelf = true)
                            .copy(eventPayload = "payload-v1"),
                    ),
                )
                return counted
            }

            suspend fun unreadAnchorWalk(
                roomId: Long,
                from: TimelineAnchor,
            ): List<Long> {
                val walked = mutableListOf<Long>()
                var anchor = from
                while (true) {
                    anchor = reader.firstVisibleUnreadAnchor(roomId, anchor, spec) ?: break
                    walked += anchor.eventId
                }
                return walked
            }
            val readCounted = seed(readId)
            val neverReadCounted = seed(neverReadId)
            val cue =
                db
                    .bufferDao()
                    .observeChatList()
                    .first()
                    .associateBy { it.bufferId }

            // Read room: the marker retires the serverTime-5 row on both sides.
            val readMarker =
                checkNotNull(
                    reader.effectiveLocalReadAnchor(buffer(networkId, "#read", readMarkerTime = 10).copy(id = readId)),
                )
            val readWalk = unreadAnchorWalk(readId, readMarker)
            assertEquals(readCounted.drop(1), readWalk)
            assertEquals(readWalk.size, cue.getValue(readId).unreadCount)

            // Never-read room: the cue counts every visible row, and entry's epoch anchor
            // (ChatViewModel.resolveEntryAnchors) enumerates exactly the same set.
            val neverReadWalk = unreadAnchorWalk(neverReadId, TimelineAnchor(0, 0, Long.MIN_VALUE))
            assertEquals(neverReadCounted, neverReadWalk)
            assertEquals(neverReadWalk.size, cue.getValue(neverReadId).unreadCount)
        }

    @Test
    fun importingOlderHistoryAfterRecentPageKeepsNewestWindowInFront() =
        runTest {
            val recentIds =
                db.messageDao().insertAll(
                    (1..25).map { ordinal ->
                        message(
                            bufferId = bufferId,
                            text = "recent-$ordinal",
                            serverTime = 1_000L + ordinal,
                            dedupKey = "recent-$ordinal",
                            msgid = "recent-$ordinal",
                        )
                    },
                )
            val initialSource =
                db.messageDao().pagingSource(
                    messagePagingQuery(bufferId, MessageVisibilitySpec()),
                )
            val initialPage =
                initialSource
                    .load(
                        PagingSource.LoadParams.Refresh(null, 50, true),
                    ).requirePage()
            val initialNewestIds = initialPage.data.map { it.id }

            db.messageDao().insertAll(
                (1..513).map { ordinal ->
                    message(
                        bufferId = bufferId,
                        text = "history-$ordinal",
                        serverTime = ordinal.toLong(),
                        dedupKey = "history-$ordinal",
                    )
                },
            )
            initialSource.invalidate()
            assertTrue(initialSource.invalid)

            val reloadedPage =
                db
                    .messageDao()
                    .pagingSource(
                        messagePagingQuery(bufferId, MessageVisibilitySpec()),
                    ).load(
                        PagingSource.LoadParams.Refresh(null, 50, true),
                    ).requirePage()

            assertEquals(recentIds.reversed(), initialNewestIds)
            assertEquals(initialNewestIds, reloadedPage.data.take(initialNewestIds.size).map { it.id })
            assertEquals("history-513", reloadedPage.data[initialNewestIds.size].text)
            assertEquals(25 + 513, reloadedPage.itemsBefore + reloadedPage.data.size + reloadedPage.itemsAfter)
        }

    @Test
    fun foolSetPastBindLimitIsCompleteAndEmptySetProducesValidSql() =
        runTest {
            db.messageDao().insertAll(
                listOf(
                    message(bufferId, "good", sender = "person", serverTime = 1, dedupKey = "good"),
                    message(bufferId, "hidden", sender = "fool-1499", serverTime = 2, dedupKey = "hidden"),
                    message(
                        bufferId,
                        "own",
                        sender = "fool-1499",
                        serverTime = 3,
                        dedupKey = "own",
                        isSelf = true,
                    ),
                    message(
                        bufferId,
                        "quoted fool",
                        sender = "fool-' OR 1=1 --",
                        serverTime = 4,
                        dedupKey = "quoted-fool",
                    ).copy(normalizedActor = "fool-' or 1=1 --"),
                ),
            )
            val largeSpec =
                MessageVisibilitySpec(
                    fools =
                        buildSet {
                            repeat(1_500) { add("fool-$it") }
                            add("fool-' OR 1=1 --")
                        },
                    foolsMode = FoolsMode.HIDE,
                )
            val query = messagePagingQuery(bufferId, largeSpec)
            assertEquals(1, query.argCount)
            assertFalse(query.sql.contains("OFFSET", ignoreCase = true))
            assertFalse(query.sql.contains("OR 1=1"))
            val largePage =
                db
                    .messageDao()
                    .pagingSource(query)
                    .load(
                        PagingSource.LoadParams.Refresh(null, 50, true),
                    ).requirePage()
            assertEquals(listOf("own", "good"), largePage.data.map { it.text })

            val emptyQuery =
                messagePagingQuery(
                    bufferId,
                    MessageVisibilitySpec(fools = emptySet(), foolsMode = FoolsMode.HIDE),
                )
            assertFalse(emptyQuery.sql.contains("IN ()"))
            val emptyPage =
                db
                    .messageDao()
                    .pagingSource(emptyQuery)
                    .load(
                        PagingSource.LoadParams.Refresh(null, 50, true),
                    ).requirePage()
            assertEquals(4, emptyPage.data.size)
        }

    @Test
    fun pagingSqlMatchesPolicyForNickAccountOwnSystemAndNullAccountRows() =
        runTest {
            val rows =
                listOf(
                    message(bufferId, "ordinary", "person", 1, "ordinary"),
                    message(bufferId, "nick fool", "[Alice", 2, "nick")
                        .copy(normalizedActor = "{alice"),
                    message(bufferId, "account fool", "new-nick", 3, "account")
                        .copy(senderAccount = "stable-account"),
                    message(bufferId, "own", "[Alice", 4, "own", isSelf = true)
                        .copy(normalizedActor = "{alice"),
                    message(bufferId, "join", "person", 5, "join", kind = MessageKind.JOIN),
                    message(bufferId, "system", "[Alice", 6, "system", kind = MessageKind.TOPIC)
                        .copy(normalizedActor = "{alice"),
                )
            db.messageDao().insertAll(rows)
            val spec =
                MessageVisibilitySpec(
                    presenceMode = PresenceMode.HIDDEN,
                    fools = setOf("[alice", "stable-account"),
                    foolsMode = FoolsMode.HIDE,
                )
            val page =
                db
                    .messageDao()
                    .pagingSource(
                        messagePagingQuery(bufferId, spec),
                    ).load(PagingSource.LoadParams.Refresh(null, 50, true))
                    .requirePage()

            val expected =
                rows
                    .asSequence()
                    .filter(MessageVisibilityPolicy(spec)::timeline)
                    .sortedByDescending { it.serverTime }
                    .map { it.text }
                    .toList()
            assertEquals(listOf("system", "own", "ordinary"), expected)
            assertEquals(expected, page.data.map { it.text })
        }

    private fun PagingSource.LoadResult<Int, MessageEntity>.requirePage() =
        when (this) {
            is PagingSource.LoadResult.Page -> {
                this
            }

            is PagingSource.LoadResult.Error -> {
                throw AssertionError("Paging load failed: ${throwable.message}", throwable)
            }

            is PagingSource.LoadResult.Invalid -> {
                throw AssertionError("Paging source invalidated before load")
            }
        }
}
