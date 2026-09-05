package io.github.trevarj.motd.data.visibility

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.EventRedirectEntity
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkIdentityEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.PresenceMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
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
import java.util.Collections
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
class MessageVisibilityReaderTest {
    private lateinit var db: MotdDatabase
    private lateinit var reader: MessageVisibilityReader
    private val observedQueries = Collections.synchronizedList(mutableListOf<String>())

    private fun querySnapshot(): List<String> = synchronized(observedQueries) { observedQueries.toList() }

    private var networkId = 0L
    private var bufferId = 0L

    @Before
    fun setUp() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db =
                Room
                    .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                    .allowMainThreadQueries()
                    .setQueryCallback(
                        { sql, _ -> observedQueries += sql },
                        Executor(Runnable::run),
                    ).build()
            reader = MessageVisibilityReader(db)
            networkId = db.networkDao().insert(network())
            bufferId = db.bufferDao().insert(buffer(networkId, "#test", readMarkerTime = 50))
        }

    @After
    fun tearDown() = db.close()

    @Test
    fun chatListFallsBackPastFoolAndExcludesFoolUnreadAndMention() =
        runTest {
            db.messageDao().insertAll(
                listOf(
                    message(bufferId, "meaningful", sender = "bob", serverTime = 100, dedupKey = "good"),
                    message(
                        bufferId,
                        "ignored fool",
                        sender = "alice",
                        serverTime = 200,
                        dedupKey = "fool",
                        hasMention = true,
                    ),
                ),
            )
            val raw = db.bufferDao().observeChatList().first()
            val resolved = reader.resolveChatList(raw, spec(FoolsMode.COLLAPSE)).single()

            assertEquals("meaningful", resolved.lastMessageText)
            assertEquals("bob", resolved.lastMessageSender)
            assertEquals(100L, resolved.lastMessageTime)
            assertEquals(1, resolved.unreadCount)
            assertEquals(0, resolved.mentionCount)
        }

    @Test
    fun chatListFoolResolutionUsesOneQueryPerIdentityRuleSet() =
        runTest {
            repeat(99) { index ->
                db.bufferDao().insert(buffer(networkId, "#room-$index", readMarkerTime = 50))
            }
            val raw = db.bufferDao().observeChatList().first()
            observedQueries.clear()

            assertEquals(100, reader.resolveChatList(raw, spec(FoolsMode.COLLAPSE)).size)

            assertEquals(
                1,
                querySnapshot().count { it.contains("WITH selected AS", ignoreCase = true) },
            )
        }

    @Test
    fun chatListReevaluatesCanonicalIdentityAfterMessageEnrichment() =
        runTest {
            val eventId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(
                            message(
                                bufferId,
                                "identity arrives later",
                                sender = "renamed-user",
                                serverTime = 200,
                                dedupKey = "identity-enrichment",
                            ),
                        ),
                    ).single()
            val spec = MessageVisibilitySpec(fools = setOf("stable-account"))
            val raw = db.bufferDao().observeChatList().first()
            assertEquals("identity arrives later", reader.resolveChatList(raw, spec).single().lastMessageText)

            val initial = db.messageDao().byId(eventId)!!
            db.messageDao().update(initial.copy(senderAccount = "stable-account"))

            val enrichedRaw = db.bufferDao().observeChatList().first()
            val enriched = reader.resolveChatList(enrichedRaw, spec).single()
            assertNull(enriched.lastMessageText)
            assertEquals(0, enriched.unreadCount)
        }

    @Test
    fun hiddenRedactionTombstoneIsExcludedFromSqlTimelineAndPreview() =
        runTest {
            db.messageDao().insertAll(
                listOf(
                    message(
                        bufferId,
                        "Message deleted by oper",
                        sender = "alice",
                        serverTime = 200,
                        dedupKey = "redacted",
                        kind = MessageKind.REDACTED,
                    ),
                ),
            )
            val hidden = MessageVisibilitySpec(showRedactedMessages = false)

            assertEquals(1, reader.countTimelineNewer(bufferId, 0, 0, MessageVisibilitySpec()))
            assertEquals(0, reader.countTimelineNewer(bufferId, 0, 0, hidden))
            assertNull(reader.resolveChatList(db.bufferDao().observeChatList().first(), hidden).single().lastMessageText)
        }

    @Test
    fun timelineIndexIncludesCollapsedFoolButNotHiddenFoolOrHiddenJoin() =
        runTest {
            val ids =
                db.messageDao().insertAll(
                    listOf(
                        message(bufferId, "target", sender = "bob", serverTime = 100, dedupKey = "target"),
                        message(bufferId, "fool", sender = "alice", serverTime = 200, dedupKey = "fool"),
                        message(
                            bufferId,
                            "join",
                            sender = "carol",
                            serverTime = 300,
                            dedupKey = "join",
                            kind = MessageKind.JOIN,
                        ),
                    ),
                )
            assertEquals(
                1,
                reader.countTimelineNewer(
                    bufferId,
                    100,
                    ids[0],
                    spec(FoolsMode.COLLAPSE, presenceMode = PresenceMode.HIDDEN),
                ),
            )
            assertEquals(
                0,
                reader.countTimelineNewer(
                    bufferId,
                    100,
                    ids[0],
                    spec(FoolsMode.HIDE, presenceMode = PresenceMode.HIDDEN),
                ),
            )
        }

    @Test
    fun viewportUnreadCountUsesPolicyTimelinePositionsWithoutPagingSnapshots() =
        runTest {
            db.messageDao().insertAll(
                listOf(
                    message(bufferId, "older", sender = "bob", serverTime = 100, dedupKey = "older"),
                    message(bufferId, "fool", sender = "alice", serverTime = 200, dedupKey = "fool"),
                    message(bufferId, "own", sender = "me", serverTime = 300, dedupKey = "own").copy(isSelf = true),
                    message(bufferId, "newer", sender = "bob", serverTime = 400, dedupKey = "newer"),
                    message(
                        bufferId,
                        "join",
                        sender = "carol",
                        serverTime = 500,
                        dedupKey = "join",
                        kind = MessageKind.JOIN,
                    ),
                ),
            )
            val marker =
                io.github.trevarj.motd.data.db
                    .TimelineAnchor(50, 0)

            assertEquals(
                1,
                reader.countVisibleUnreadInTimelinePrefix(
                    bufferId,
                    beforeIndex = 4,
                    after = marker,
                    maxCount = 100,
                    spec = spec(FoolsMode.COLLAPSE),
                ),
            )
            assertEquals(
                2,
                reader.countVisibleUnreadInTimelinePrefix(
                    bufferId,
                    beforeIndex = 4,
                    after = marker,
                    maxCount = 100,
                    spec = spec(FoolsMode.HIDE),
                ),
            )
        }

    @Test
    fun deepViewportUnreadCountCapsWithoutFalseZeroAfterPageDrops() =
        runTest {
            db.messageDao().insertAll(
                List(600) { index ->
                    message(
                        bufferId,
                        "message-$index",
                        sender = "bob",
                        serverTime = index.toLong() + 1,
                        dedupKey = "deep-$index",
                    )
                },
            )

            assertEquals(
                100,
                reader.countVisibleUnreadInTimelinePrefix(
                    bufferId,
                    beforeIndex = 550,
                    after =
                        io.github.trevarj.motd.data.db
                            .TimelineAnchor(0, 0),
                    maxCount = 100,
                    spec = MessageVisibilitySpec(),
                ),
            )
        }

    @Test
    fun readerUsesPersistedNetworkCasemapForFoolPredicates() =
        runTest {
            db.networkIdentityDao().upsert(
                NetworkIdentityEntity(networkId, caseMapping = "ascii"),
            )
            val ids =
                db.messageDao().insertAll(
                    listOf(
                        message(
                            bufferId,
                            "ascii fool",
                            sender = "[Alice",
                            serverTime = 100,
                            dedupKey = "ascii-fool",
                        ).copy(normalizedActor = "[alice"),
                        message(bufferId, "target", sender = "bob", serverTime = 50, dedupKey = "target"),
                    ),
                )
            val spec =
                MessageVisibilitySpec(
                    fools = setOf("[alice"),
                    foolsMode = FoolsMode.HIDE,
                )

            assertEquals(0, reader.countTimelineNewer(bufferId, 50, ids[1], spec))
            assertEquals(ids[1], reader.latestEffectiveAnchor(bufferId, spec)?.id)
        }

    @Test
    fun savedAnchorAndUnreadSkipFoolRowsAndRawTailStillAdvances() =
        runTest {
            val ids =
                db.messageDao().insertAll(
                    listOf(
                        message(bufferId, "older", sender = "bob", serverTime = 100, dedupKey = "older"),
                        message(bufferId, "fool", sender = "alice", serverTime = 200, dedupKey = "fool"),
                        message(
                            bufferId,
                            "part",
                            sender = "carol",
                            serverTime = 300,
                            dedupKey = "part",
                            kind = MessageKind.PART,
                        ),
                    ),
                )
            val spec = spec(FoolsMode.COLLAPSE, presenceMode = PresenceMode.HIDDEN)
            val anchor = reader.resolveSavedAnchor(bufferId, null, 200, ids[1], spec)

            assertEquals(ids[0], anchor?.id)
            assertEquals(
                100L,
                reader
                    .firstVisibleUnreadAnchor(
                        bufferId,
                        io.github.trevarj.motd.data.db
                            .TimelineAnchor(50, 0),
                        spec,
                    )?.serverTime,
            )
            assertEquals(
                io.github.trevarj.motd.data.db
                    .TimelineAnchor(300L, ids[2]),
                reader.latestRawAnchor(bufferId),
            )
        }

    @Test
    fun rawTailObserverFollowsRedirectChangedWithoutMessageInvalidation() =
        runTest {
            val networkId = db.bufferDao().observeById(bufferId)!!.networkId
            val winnerId = db.bufferDao().insert(buffer(networkId, "#winner"))
            val eventId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(winnerId, "winner tail", serverTime = 700, dedupKey = "winner-tail")),
                    ).single()
            val observedInitialState = CompletableDeferred<Unit>()
            val redirected =
                async {
                    reader
                        .observeLatestRawAnchor(bufferId)
                        .onEach { if (it == null) observedInitialState.complete(Unit) }
                        .first { it?.eventId == eventId }
                }

            observedInitialState.await()
            db.roomAliasDao().markRedirect(bufferId, winnerId)

            assertEquals(
                io.github.trevarj.motd.data.db
                    .TimelineAnchor(700, eventId),
                redirected.await(),
            )
        }

    @Test
    fun msgidlessSavedAnchorFollowsRedirectAndCorrectedTimestamp() =
        runTest {
            val winnerId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(
                            message(
                                bufferId,
                                "history",
                                sender = "bob",
                                serverTime = 500,
                                dedupKey = "winner",
                            ),
                        ),
                    ).single()
            val loserId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(
                            message(
                                bufferId,
                                "live",
                                sender = "bob",
                                serverTime = 200,
                                dedupKey = "loser",
                            ),
                        ),
                    ).single()
            db.canonicalTimelineDao().upsertEventRedirect(EventRedirectEntity(loserId, winnerId))
            db.messageDao().deleteById(loserId)

            val anchor =
                reader.resolveSavedAnchor(
                    bufferId = bufferId,
                    msgid = null,
                    serverTime = 200,
                    id = loserId,
                    spec = spec(FoolsMode.COLLAPSE),
                )

            assertEquals(winnerId, anchor?.id)
            assertEquals(500L, anchor?.serverTime)
        }

    @Test
    fun foolOnlyBufferHasNoAnchorOrVisibleUnread() =
        runTest {
            val id =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(bufferId, "fool", sender = "alice", serverTime = 100, dedupKey = "fool")),
                    ).single()
            val spec = spec(FoolsMode.COLLAPSE)
            val resolved = reader.resolveChatList(db.bufferDao().observeChatList().first(), spec).single()

            assertNull(reader.resolveSavedAnchor(bufferId, null, 100, id, spec))
            assertNull(
                reader.firstVisibleUnreadAnchor(
                    bufferId,
                    io.github.trevarj.motd.data.db
                        .TimelineAnchor(50, 0),
                    spec,
                ),
            )
            assertNull(resolved.lastMessageText)
            assertNull(resolved.lastMessageTime)
            assertEquals(0, resolved.unreadCount)
            assertEquals(0, resolved.mentionCount)
        }

    @Test
    fun longFoolTailUsesTargetedQueriesForPreviewEffectiveBottomAndSavedAnchor() =
        runTest {
            val meaningfulId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(bufferId, "meaningful", sender = "bob", serverTime = 1, dedupKey = "good")),
                    ).single()
            var newestFoolId = 0L
            repeat(5) { chunk ->
                newestFoolId =
                    db
                        .messageDao()
                        .insertAll(
                            List(1_000) { offset ->
                                val index = chunk * 1_000 + offset
                                message(
                                    bufferId,
                                    "fool-$index",
                                    sender = "alice",
                                    serverTime = 2L + index,
                                    dedupKey = "fool-$index",
                                )
                            },
                        ).last()
            }

            val raw = db.bufferDao().observeChatList().first()
            observedQueries.clear()
            val resolved = reader.resolveChatList(raw, spec(FoolsMode.HIDE)).single()
            assertEquals("meaningful", resolved.lastMessageText)
            assertEquals(meaningfulId, reader.latestEffectiveAnchor(bufferId, spec(FoolsMode.HIDE))?.id)
            assertEquals(
                meaningfulId,
                reader
                    .resolveSavedAnchor(
                        bufferId = bufferId,
                        msgid = null,
                        serverTime = 5_001,
                        id = newestFoolId,
                        spec = spec(FoolsMode.HIDE),
                    )?.id,
            )
            assertEquals(0, reader.countTimelineNewer(bufferId, 1, meaningfulId, spec(FoolsMode.HIDE)))
            assertFalse(querySnapshot().any { it.contains(" OFFSET ", ignoreCase = true) })
        }

    @Test
    fun nearestUnreadMentionBelowViewportWalksNewestToOldestAndExcludesFoolsAndRead() =
        runTest {
            val ids =
                db.messageDao().insertAll(
                    listOf(
                        message(bufferId, "old", sender = "bob", serverTime = 100, dedupKey = "old"),
                        message(bufferId, "fool", sender = "alice", serverTime = 200, dedupKey = "fool", hasMention = true),
                        message(bufferId, "mention-a", sender = "bob", serverTime = 300, dedupKey = "m-a", hasMention = true),
                        message(bufferId, "mention-b", sender = "bob", serverTime = 400, dedupKey = "m-b", hasMention = true),
                        message(bufferId, "plain", sender = "bob", serverTime = 500, dedupKey = "plain"),
                    ),
                )
            val marker =
                io.github.trevarj.motd.data.db
                    .TimelineAnchor(50, 0)
            val spec = spec(FoolsMode.COLLAPSE)
            // Reversed indices: 0=plain(500), 1=mention-b(400), 2=mention-a(300), 3=fool(200), 4=old(100).

            // firstVisible=3 => below viewport = {0,1,2}; nearest unread mention = mention-a (index 2).
            assertEquals(
                2,
                reader.nearestUnreadMentionBelowIndex(bufferId, beforeIndex = 3, after = marker, spec = spec),
            )
            // firstVisible=2 => below = {0,1}; nearest = mention-b (index 1). Repeated taps walk downward.
            assertEquals(
                1,
                reader.nearestUnreadMentionBelowIndex(bufferId, beforeIndex = 2, after = marker, spec = spec),
            )
            // firstVisible=1 => below = {0}; plain(500) has no mention => null (fall through to bottom).
            assertNull(reader.nearestUnreadMentionBelowIndex(bufferId, beforeIndex = 1, after = marker, spec = spec))
            // firstVisible=4 => below = {0,1,2,3}; fool(200) is timeline-visible but visibleUnread excludes
            // it, so the nearest mention is still mention-a (index 2), never the fool.
            assertEquals(
                2,
                reader.nearestUnreadMentionBelowIndex(bufferId, beforeIndex = 4, after = marker, spec = spec),
            )
            // Read marker advanced past mention-b(400): neither mention is unread-after-marker, so null.
            val pastMarker =
                io.github.trevarj.motd.data.db
                    .TimelineAnchor(450, ids[3])
            assertNull(
                reader.nearestUnreadMentionBelowIndex(bufferId, beforeIndex = 3, after = pastMarker, spec = spec),
            )
            // No viewport prefix to search => null without querying.
            assertNull(reader.nearestUnreadMentionBelowIndex(bufferId, beforeIndex = 0, after = marker, spec = spec))
        }

    @Test
    fun catchUpUsesFullTupleBoundaryAndReturnsChronologicalRows() =
        runTest {
            insertContextMessage("older-time", 99)
            insertContextMessage("same-order-older-id", 100, timelineOrder = 7)
            val marker = insertContextMessage("marker", 100, timelineOrder = 7)
            val sameOrderNewerId = insertContextMessage("same-order-newer-id", 100, timelineOrder = 7)
            val higherOrder = insertContextMessage("higher-order", 100, timelineOrder = 8)
            val laterTime = insertContextMessage("later-time", 101, timelineOrder = 1)

            val result =
                reader.catchUpContext(
                    bufferId,
                    TimelineAnchor(100, marker, 7),
                    MessageVisibilitySpec(),
                ) as MessageContextResult.Available

            assertEquals(listOf(sameOrderNewerId, higherOrder, laterTime), result.rows.map { it.eventId })
            assertEquals(MessageContextCoverage.COMPLETE, result.coverage)
            assertTrue(result.omissions.isEmpty())
        }

    @Test
    fun catchUpCanonicalizesRoomKeepsNewestHundredAndReportsTruncation() =
        runTest {
            val canonicalBufferId = db.bufferDao().insert(buffer(networkId, "#canonical"))
            db.roomAliasDao().markRedirect(bufferId, canonicalBufferId)
            val ids =
                db.messageDao().insertAll(
                    List(105) { index ->
                        message(
                            canonicalBufferId,
                            "message-$index",
                            sender = "bob",
                            serverTime = index.toLong() + 1,
                            dedupKey = "catch-up-$index",
                        )
                    },
                )
            observedQueries.clear()

            val result =
                reader.catchUpContext(
                    bufferId,
                    TimelineAnchor(0, 0),
                    MessageVisibilitySpec(),
                ) as MessageContextResult.Available

            assertEquals(ids.drop(5), result.rows.map { it.eventId })
            assertEquals(MessageContextCoverage.TRUNCATED, result.coverage)
            assertEquals(setOf(MessageContextOmission.ROW_LIMIT), result.omissions)
            assertTrue(
                querySnapshot().any {
                    it.contains("m.kind IN ('PRIVMSG','NOTICE','ACTION')") &&
                        it.contains("LIMIT ?")
                },
            )
            assertFalse(querySnapshot().any { it.contains(" OFFSET ", ignoreCase = true) })
        }

    @Test
    fun catchUpUsesPreviewVisibilityInBothFoolModesAndRetainsSelf() =
        runTest {
            val normal = insertContextMessage("normal", 100)
            insertContextMessage("fool", 200, sender = "alice")
            val self = insertContextMessage("self", 300, sender = "alice", isSelf = true)
            insertContextMessage("redacted", 400, kind = MessageKind.REDACTED)
            insertContextMessage("presence", 500, kind = MessageKind.JOIN)
            insertContextMessage("system", 600, kind = MessageKind.SERVER_INFO)
            val notice = insertContextMessage("notice", 700, kind = MessageKind.NOTICE)
            val action = insertContextMessage("action", 800, kind = MessageKind.ACTION)
            val expected = listOf(normal, self, notice, action)

            listOf(FoolsMode.COLLAPSE, FoolsMode.HIDE).forEach { mode ->
                val result =
                    reader.catchUpContext(
                        bufferId,
                        TimelineAnchor(0, 0),
                        MessageVisibilitySpec(
                            presenceMode = PresenceMode.ALL,
                            showRedactedMessages = false,
                            fools = setOf("alice"),
                            foolsMode = mode,
                        ),
                    ) as MessageContextResult.Available

                assertEquals(expected, result.rows.map { it.eventId })
                assertEquals(listOf(false, true, false, false), result.rows.map { it.isSelf })
            }
        }

    @Test
    fun catchUpPreservesKnownIncompleteWithoutLiveGap() =
        runTest {
            insertContextMessage("newer", 300)

            val result =
                reader.catchUpContext(
                    bufferId,
                    TimelineAnchor(150, 0),
                    MessageVisibilitySpec(),
                    knownIncomplete = true,
                ) as MessageContextResult.Available

            assertEquals(MessageContextCoverage.PARTIAL, result.coverage)
            assertEquals(setOf(MessageContextOmission.HISTORY_GAP), result.omissions)
        }

    @Test
    fun catchUpReportsOnlyHistoryGapsIntersectingRequestedInterval() =
        runTest {
            insertContextMessage("older", 100)
            insertContextMessage("newer", 300)
            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = bufferId,
                    olderMsgid = null,
                    olderServerTime = 50,
                    newerMsgid = null,
                    newerServerTime = 100,
                ),
            )

            val complete =
                reader.catchUpContext(
                    bufferId,
                    TimelineAnchor(150, 0),
                    MessageVisibilitySpec(),
                ) as MessageContextResult.Available
            assertEquals(MessageContextCoverage.COMPLETE, complete.coverage)

            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = bufferId,
                    olderMsgid = null,
                    olderServerTime = 200,
                    newerMsgid = null,
                    newerServerTime = 300,
                ),
            )
            val partial =
                reader.catchUpContext(
                    bufferId,
                    TimelineAnchor(150, 0),
                    MessageVisibilitySpec(),
                ) as MessageContextResult.Available

            assertEquals(MessageContextCoverage.PARTIAL, partial.coverage)
            assertEquals(setOf(MessageContextOmission.HISTORY_GAP), partial.omissions)
        }

    @Test
    fun threadCanonicalizesSelectedEventAndCollectsRootSiblingsAndDescendants() =
        runTest {
            val root = insertContextMessage("root", 100)
            val sibling = insertContextMessage("sibling", 200, replyToEventId = root)
            val hiddenFool = insertContextMessage("hidden-fool", 250, sender = "alice", replyToEventId = root)
            val visibleGrandchild = insertContextMessage("visible-grandchild", 300, replyToEventId = hiddenFool)
            val selected = insertContextMessage("selected", 400, replyToEventId = root)
            val selectedChild = insertContextMessage("selected-child", 500, replyToEventId = selected)
            val losingId = insertContextMessage("coalesced-copy", 400, replyToEventId = root)
            db.canonicalTimelineDao().upsertEventRedirect(EventRedirectEntity(losingId, selected))
            db.messageDao().deleteById(losingId)
            observedQueries.clear()

            val result =
                reader.threadContext(
                    bufferId,
                    losingId,
                    spec(FoolsMode.COLLAPSE),
                ) as MessageContextResult.Available

            assertEquals(
                listOf(root, sibling, visibleGrandchild, selected, selectedChild),
                result.rows.map { it.eventId },
            )
            assertEquals(root, result.rootEventId)
            assertEquals(root, result.rows.first().eventId)
            assertEquals(hiddenFool, result.rows[2].replyToEventId)
            assertEquals(MessageContextCoverage.COMPLETE, result.coverage)
            val queries = querySnapshot()
            assertEquals(1, queries.count { it.contains("WITH RECURSIVE thread") })
            assertFalse(queries.any { it.contains(" OFFSET ", ignoreCase = true) })
        }

    @Test
    fun threadUsesShallowestVisibleRootThroughHiddenAncestors() =
        runTest {
            val hiddenRoot = insertContextMessage("hidden-root", 100, sender = "alice")
            val hiddenBranch =
                insertContextMessage("hidden-branch", 150, sender = "alice", replyToEventId = hiddenRoot)
            val deepVisible = insertContextMessage("deep-visible", 200, replyToEventId = hiddenBranch)
            val shallowVisible = insertContextMessage("shallow-visible", 300, replyToEventId = hiddenRoot)

            val result =
                reader.threadContext(
                    bufferId,
                    deepVisible,
                    spec(FoolsMode.COLLAPSE),
                ) as MessageContextResult.Available

            assertEquals(listOf(deepVisible, shallowVisible), result.rows.map { it.eventId })
            assertEquals(shallowVisible, result.rootEventId)
        }

    @Test
    fun oneVisibleThreadRowIsAvailableAndMissingOrCrossRoomParentIsPartial() =
        runTest {
            val selected = insertContextMessage("selected", 100)
            val oneRow =
                reader.threadContext(
                    bufferId,
                    selected,
                    MessageVisibilitySpec(),
                ) as MessageContextResult.Available
            assertEquals(listOf(selected), oneRow.rows.map { it.eventId })
            assertEquals(selected, oneRow.rootEventId)
            assertEquals(MessageContextCoverage.COMPLETE, oneRow.coverage)

            db.messageDao().update(db.messageDao().byId(selected)!!.copy(replyToMsgid = "missing-parent"))
            val unresolvedMsgid =
                reader.threadContext(
                    bufferId,
                    selected,
                    MessageVisibilitySpec(),
                ) as MessageContextResult.Available
            assertEquals(listOf(selected), unresolvedMsgid.rows.map { it.eventId })
            assertEquals(selected, unresolvedMsgid.rootEventId)
            assertEquals(MessageContextCoverage.PARTIAL, unresolvedMsgid.coverage)
            assertEquals(setOf(MessageContextOmission.UNRESOLVED_PARENT), unresolvedMsgid.omissions)

            db.messageDao().update(
                db.messageDao().byId(selected)!!.copy(replyToMsgid = null, replyToEventId = 999_999),
            )
            val missing =
                reader.threadContext(
                    bufferId,
                    selected,
                    MessageVisibilitySpec(),
                ) as MessageContextResult.Available
            assertEquals(listOf(selected), missing.rows.map { it.eventId })
            assertEquals(selected, missing.rootEventId)
            assertEquals(MessageContextCoverage.PARTIAL, missing.coverage)
            assertEquals(setOf(MessageContextOmission.UNRESOLVED_PARENT), missing.omissions)

            val otherRoom = db.bufferDao().insert(buffer(networkId, "#other"))
            val foreignParent = insertContextMessage("foreign-parent", 50, roomId = otherRoom)
            db.messageDao().update(db.messageDao().byId(selected)!!.copy(replyToEventId = foreignParent))
            val crossRoom =
                reader.threadContext(
                    bufferId,
                    selected,
                    MessageVisibilitySpec(),
                ) as MessageContextResult.Available
            assertEquals(listOf(selected), crossRoom.rows.map { it.eventId })
            assertEquals(selected, crossRoom.rootEventId)
            assertEquals(MessageContextCoverage.PARTIAL, crossRoom.coverage)
            assertEquals(setOf(MessageContextOmission.UNRESOLVED_PARENT), crossRoom.omissions)
        }

    @Test
    fun threadCycleReturnsEachVisibleRowOnceAndReportsPartialCoverage() =
        runTest {
            val first = insertContextMessage("first", 100)
            val second = insertContextMessage("second", 200)
            db.messageDao().update(db.messageDao().byId(first)!!.copy(replyToEventId = second))
            db.messageDao().update(db.messageDao().byId(second)!!.copy(replyToEventId = first))

            val result =
                reader.threadContext(
                    bufferId,
                    first,
                    MessageVisibilitySpec(),
                ) as MessageContextResult.Available

            assertEquals(listOf(first, second), result.rows.map { it.eventId })
            assertEquals(MessageContextCoverage.PARTIAL, result.coverage)
            assertTrue(MessageContextOmission.CYCLE in result.omissions)
        }

    @Test
    fun threadDepthCapAppliesToParentWalkAndDescendantTraversal() =
        runTest {
            val root = insertContextMessage("root", 100)
            val first = insertContextMessage("first", 200, replyToEventId = root)
            val second = insertContextMessage("second", 300, replyToEventId = first)
            val third = insertContextMessage("third", 400, replyToEventId = second)

            val parentCapped =
                reader.threadContext(
                    bufferId,
                    third,
                    MessageVisibilitySpec(),
                    maxDepth = 2,
                ) as MessageContextResult.Available
            assertEquals(listOf(first, second, third), parentCapped.rows.map { it.eventId })
            assertTrue(MessageContextOmission.DEPTH_LIMIT in parentCapped.omissions)

            val descendantCapped =
                reader.threadContext(
                    bufferId,
                    root,
                    MessageVisibilitySpec(),
                    maxDepth = 2,
                ) as MessageContextResult.Available
            assertEquals(listOf(root, first, second), descendantCapped.rows.map { it.eventId })
            assertTrue(MessageContextOmission.DEPTH_LIMIT in descendantCapped.omissions)
        }

    @Test
    fun threadRowCapKeepsRootAndNewestRowsThenRestoresChronology() =
        runTest {
            val root = insertContextMessage("root", 100)
            val children =
                (2L..5L).map { index ->
                    insertContextMessage(
                        "child-$index",
                        index * 100,
                        replyToEventId = root,
                    )
                }

            val result =
                reader.threadContext(
                    bufferId,
                    children.first(),
                    MessageVisibilitySpec(),
                    limit = 3,
                ) as MessageContextResult.Available

            assertEquals(listOf(root, children[2], children[3]), result.rows.map { it.eventId })
            assertEquals(MessageContextCoverage.TRUNCATED, result.coverage)
            assertTrue(MessageContextOmission.ROW_LIMIT in result.omissions)
        }

    @Test
    fun threadReportsHistoryGapBetweenRootAndNewestLocalRow() =
        runTest {
            val root = insertContextMessage("root", 100)
            val child = insertContextMessage("child", 300, replyToEventId = root)
            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = bufferId,
                    olderMsgid = null,
                    olderServerTime = 150,
                    newerMsgid = null,
                    newerServerTime = 250,
                ),
            )

            val result =
                reader.threadContext(
                    bufferId,
                    child,
                    MessageVisibilitySpec(),
                ) as MessageContextResult.Available

            assertEquals(listOf(root, child), result.rows.map { it.eventId })
            assertEquals(MessageContextCoverage.PARTIAL, result.coverage)
            assertEquals(setOf(MessageContextOmission.HISTORY_GAP), result.omissions)
        }

    private suspend fun insertContextMessage(
        text: String,
        serverTime: Long,
        sender: String = "bob",
        kind: MessageKind = MessageKind.PRIVMSG,
        isSelf: Boolean = false,
        replyToEventId: Long? = null,
        roomId: Long = bufferId,
        timelineOrder: Long = 0,
    ): Long =
        db
            .messageDao()
            .insertAll(
                listOf(
                    message(
                        roomId,
                        text,
                        sender = sender,
                        serverTime = serverTime,
                        dedupKey = "$roomId:$text:$serverTime",
                        kind = kind,
                        isSelf = isSelf,
                    ).copy(
                        replyToEventId = replyToEventId,
                        timelineOrder = timelineOrder,
                    ),
                ),
            ).single()

    private fun spec(
        mode: FoolsMode,
        presenceMode: PresenceMode = PresenceMode.ALL,
    ) = MessageVisibilitySpec(
        presenceMode = presenceMode,
        fools = setOf("alice"),
        foolsMode = mode,
    )
}
