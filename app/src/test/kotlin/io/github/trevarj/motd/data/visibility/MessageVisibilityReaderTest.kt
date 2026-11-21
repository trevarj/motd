package io.github.trevarj.motd.data.visibility

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.EventRedirectEntity
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkIdentityEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.FoolPreferences
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
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
    fun chatListVisibilityResortPreservesAnchorRanksAheadOfPinsAndActivity() =
        runTest {
            val second = db.bufferDao().insert(buffer(networkId, "#second").copy(pinned = true))
            val ordinary = db.bufferDao().insert(buffer(networkId, "#ordinary").copy(pinned = true))
            db.bufferDao().setAnchored(bufferId, true)
            db.bufferDao().setAnchored(second, true)
            db.messageDao().insertAll(
                listOf(
                    message(second, "second", sender = "bob", serverTime = 200, dedupKey = "second"),
                    message(ordinary, "ordinary", sender = "bob", serverTime = 300, dedupKey = "ordinary"),
                    message(bufferId, "hidden", sender = "alice", serverTime = 400, dedupKey = "hidden"),
                ),
            )
            val rows = db.bufferDao().observeChatList().first()
            assertEquals(listOf(bufferId, second, ordinary), rows.map { it.bufferId })
            val resolved = reader.resolveChatList(rows, MessageVisibilitySpec(fools = setOf("alice")))
            assertEquals(listOf(bufferId, second, ordinary), resolved.map { it.bufferId })
            assertEquals(listOf(0, 1, null), resolved.map { it.anchorOrder })
        }

    @Test
    fun messagesOnlyKeepsFoolAndRedactionFilteringWithoutChangingEntryOrSort() =
        runTest {
            val otherRoom = db.bufferDao().insert(buffer(networkId, "#other"))
            db.messageDao().insertAll(
                listOf(
                    message(bufferId, "latest conversation", "bob", 100, "real"),
                    message(bufferId, "fool message", "alice", 300, "fool", hasMention = true),
                    message(bufferId, "mode change", "op", 400, "mode", kind = MessageKind.MODE),
                    message(bufferId, "deleted", "bob", 500, "deleted", kind = MessageKind.REDACTED),
                    message(bufferId, "nick change", "bob", 600, "nick", kind = MessageKind.NICK),
                    message(otherRoom, "newer conversation", "bob", 200, "other"),
                ),
            )
            val legacyRaw = db.bufferDao().observeChatList().first()
            val raw = db.bufferDao().observeChatList(messagesOnly = true).first()
            assertEquals("fool message", raw.first().lastMessageText)
            for (showRedacted in listOf(false, true)) {
                val spec = MessageVisibilitySpec(fools = setOf("alice"), foolsMode = FoolsMode.HIDE, showRedactedMessages = showRedacted)
                val legacy = reader.resolveChatList(legacyRaw, spec)
                val messages = reader.resolveChatList(raw, spec, messagesOnly = true)

                assertEquals(listOf(bufferId, otherRoom), messages.map { it.bufferId })
                assertEquals(legacy, listOf(checkNotNull(reader.chatListRow(bufferId, spec)), messages.last()))
                val preview = messages.first()
                val activity = legacy.first()
                assertEquals(if (showRedacted) "deleted" else "mode change", activity.lastMessageText)
                assertEquals(if (showRedacted) 500L else 400L, preview.lastMessageTime)
                assertEquals("latest conversation", preview.lastMessageText)
                assertEquals("bob", preview.lastMessageSender)
                assertEquals(100L, preview.previewTime)
                assertEquals(1, preview.unreadCount)
                assertEquals(0, preview.mentionCount)
                assertEquals(
                    activity,
                    preview.copy(
                        lastMessageText = activity.lastMessageText,
                        lastMessageSender = activity.lastMessageSender,
                        previewTime = activity.previewTime,
                    ),
                )
            }
        }

    @Test
    fun roomDeletedBetweenOverviewReadsDoesNotFallBackToUnfilteredPreview() =
        runTest {
            val buffers = db.bufferDao()
            val survivingRoom = buffers.insert(buffer(networkId, "#surviving"))
            db.messageDao().insertAll(
                listOf(
                    message(bufferId, "hidden text", "alice", 200, "hidden"),
                    message(bufferId, "mode change", "op", 300, "mode", kind = MessageKind.MODE),
                    message(survivingRoom, "hello", "bob", 100, "hello"),
                ),
            )
            val raw = buffers.observeChatList(messagesOnly = true).first()
            assertEquals("hidden text", raw.single { it.bufferId == bufferId }.lastMessageText)
            buffers.deleteBuffer(bufferId)

            val spec = MessageVisibilitySpec(fools = setOf("alice"), foolsMode = FoolsMode.HIDE)
            assertEquals(
                listOf(raw.single { it.bufferId == survivingRoom }),
                reader.resolveChatList(raw, spec, messagesOnly = true),
            )
        }

    @Test
    fun mixedFoolsKeepSqlUnreadAttentionAndAnchorsAlignedDuringHiddenReveal() =
        runTest {
            val ids =
                db.messageDao().insertAll(
                    listOf("a", "b", "c").mapIndexed { index, sender ->
                        message(bufferId, sender, sender = sender, serverTime = 100L * (index + 1), dedupKey = sender, hasMention = true)
                    },
                )
            val spec =
                MessageVisibilitySpec(
                    fools = setOf("a", "b", "c"),
                    foolPreferences =
                        mapOf(
                            "b" to FoolPreferences(FoolsMode.COLLAPSE, notifyMentions = true),
                            "c" to FoolPreferences(FoolsMode.HIDE, notifyMentions = true),
                        ),
                )
            val marker = TimelineAnchor(50, 0)
            for (reveal in listOf(false, true)) {
                val current = spec.copy(revealHiddenFools = reveal)
                val raw = db.bufferDao().observeChatList().first()
                val resolved = reader.resolveChatList(raw, current).single()
                assertEquals(resolved, reader.chatListRow(bufferId, current))
                assertEquals(2, resolved.unreadCount)
                assertEquals(1, resolved.mentionCount)
                assertNull(resolved.lastMessageText)
                assertEquals(if (reveal) 3 else 2, reader.countTimelineNewer(bufferId, 50, 0, current))
                assertEquals(2, reader.countVisibleUnreadInTimelinePrefix(bufferId, 3, marker, 100, current))
                assertEquals(ids[0], reader.firstVisibleUnreadAnchor(bufferId, marker, current)?.eventId)
                assertEquals(ids[1], reader.firstVisibleUnreadAnchor(bufferId, TimelineAnchor(100, ids[0]), current)?.eventId)
                assertEquals(ids[0], reader.resolveSavedAnchor(bufferId, null, 100, ids[0], current)?.id)
                assertEquals(ids[1], reader.resolveSavedAnchor(bufferId, null, 200, ids[1], current)?.id)
                assertEquals(ids[1], reader.resolveSavedAnchor(bufferId, null, 300, ids[2], current)?.id)
                assertEquals(ids[1], reader.latestEffectiveAnchor(bufferId, current)?.id)
                assertEquals(if (reveal) 1 else 0, reader.nearestUnreadMentionBelowIndex(bufferId, 3, marker, current))
                val rows = db.messageDao().rawMessages(messagePagingQuery(bufferId, current))
                val policy = MessageVisibilityPolicy(current)
                assertEquals(2, rows.count(policy::visibleUnread))
                assertEquals(1, rows.count { policy.mentionAttention(it, false) })
            }
            assertEquals(50L, db.bufferDao().observeById(bufferId)?.localReadAnchorTime)
        }

    @Test
    fun directMentionAttentionUsesQueryPermissionNotChannelMentionPermission() =
        runTest {
            val queryId = db.bufferDao().insert(buffer(networkId, "alice", type = BufferType.QUERY, readMarkerTime = 50))
            for (room in listOf(bufferId, queryId)) {
                db.messageDao().insertAll(listOf(message(room, "mention", serverTime = 100, dedupKey = "mention-$room", hasMention = true)))
            }
            for (dmEnabled in listOf(false, true)) {
                val current =
                    MessageVisibilitySpec(
                        fools = setOf("alice"),
                        foolPreferences = mapOf("alice" to FoolPreferences(notifyMentions = !dmEnabled, notifyDirectMessages = dmEnabled)),
                    )
                val resolved = reader.resolveChatList(db.bufferDao().observeChatList().first(), current).associateBy { it.bufferId }
                assertEquals(resolved.getValue(bufferId), reader.chatListRow(bufferId, current))
                assertEquals(resolved.getValue(queryId), reader.chatListRow(queryId, current))
                assertEquals(if (dmEnabled) 1 else 0, resolved.getValue(queryId).mentionCount)
                assertEquals(if (dmEnabled) 0 else 1, resolved.getValue(bufferId).mentionCount)
                assertEquals(1, resolved.getValue(queryId).unreadCount)
                assertEquals(1, resolved.getValue(bufferId).unreadCount)
                assertEquals(if (dmEnabled) 0 else null, reader.nearestUnreadMentionBelowIndex(queryId, 1, TimelineAnchor(50, 0), current))
                assertEquals(if (dmEnabled) null else 0, reader.nearestUnreadMentionBelowIndex(bufferId, 1, TimelineAnchor(50, 0), current))
            }
        }

    @Test
    fun chatListFallsBackPastFoolAndKeepsOrdinaryUnreadSeparateFromMention() =
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
            assertEquals(2, resolved.unreadCount)
            assertEquals(0, resolved.mentionCount)
        }

    @Test
    fun roomChatListRowMatchesWholeListPolicyWithoutMixingRooms() =
        runTest {
            val otherNetwork = db.networkDao().insert(network(name = "other"))
            val otherRoom = db.bufferDao().insert(buffer(otherNetwork, "#test", readMarkerTime = 50).copy(localUnreadFloorTime = 120))
            db.networkIdentityDao().upsert(NetworkIdentityEntity(networkId, caseMapping = "ascii"))
            db.networkIdentityDao().upsert(NetworkIdentityEntity(otherNetwork, caseMapping = "rfc1459"))
            for (room in listOf(bufferId, otherRoom)) {
                val rules = IrcIdentityRules.from(if (room == bufferId) "ascii" else "rfc1459", null)
                db.messageDao().insertAll(
                    listOf(
                        message(room, "ordinary-$room", sender = "bob", serverTime = 100, dedupKey = "ordinary", hasMention = true),
                        message(room, "fool-$room", sender = "[Alice", serverTime = 150, dedupKey = "fool", hasMention = true)
                            // Raw DAO inserts must carry the network-normalized identity, as EventProcessor writes it.
                            .copy(normalizedActor = rules.normalize("[Alice")),
                        message(room, "self-$room", serverTime = 160, dedupKey = "self", isSelf = true),
                        message(room, "join-$room", serverTime = 170, dedupKey = "join", kind = MessageKind.JOIN),
                        message(room, "redacted-$room", serverTime = 180, dedupKey = "redacted", kind = MessageKind.REDACTED),
                    ),
                )
            }
            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = bufferId,
                    olderMsgid = null,
                    olderServerTime = 60,
                    newerMsgid = null,
                    newerServerTime = 90,
                ),
            )
            val raw = db.bufferDao().observeChatList().first()
            assertEquals(setOf(bufferId, otherRoom), raw.map { it.bufferId }.toSet())
            for (mode in FoolsMode.entries) {
                for (showRedacted in listOf(false, true)) {
                    val current = MessageVisibilitySpec(fools = setOf("{alice"), foolsMode = mode, showRedactedMessages = showRedacted)
                    val expected = reader.resolveChatList(raw, current).associateBy { it.bufferId }
                    for (room in listOf(bufferId, otherRoom)) {
                        assertEquals(
                            raw.single { it.bufferId == room },
                            db
                                .bufferDao()
                                .observeChatList(room)
                                .first()
                                .single(),
                        )
                        assertEquals(expected.getValue(room), reader.chatListRow(room, current))
                    }
                    val selected = checkNotNull(reader.chatListRow(bufferId, current))
                    val other = checkNotNull(reader.chatListRow(otherRoom, current))
                    assertEquals(2, selected.unreadCount)
                    assertEquals(if (mode == FoolsMode.HIDE) 0 else 1, other.unreadCount)
                    assertEquals(if (showRedacted) "redacted-$bufferId" else "self-$bufferId", selected.lastMessageText)
                    assertTrue(selected.unreadCountIncomplete)
                    assertFalse(other.unreadCountIncomplete)
                }
            }
        }

    @Test
    fun roomChatListRowDoesNotSubstituteAnotherRoomForAnExcludedOrRedirectedRoom() =
        runTest {
            val excluded =
                listOf(
                    buffer(networkId, "server", type = BufferType.SERVER),
                    buffer(networkId, "#dismissed").copy(dismissed = true),
                    buffer(networkId, "#closing").copy(pendingCloseAt = 1),
                    buffer(networkId, "#alias").copy(redirectToRoomId = bufferId),
                ).map { db.bufferDao().insert(it) }
            val current = MessageVisibilitySpec()
            val canonical = checkNotNull(reader.chatListRow(bufferId, current))
            assertEquals(bufferId, canonical.bufferId)
            for (room in excluded + Long.MAX_VALUE) {
                assertTrue(
                    db
                        .bufferDao()
                        .observeChatList(room)
                        .first()
                        .isEmpty(),
                )
                assertNull(reader.chatListRow(room, current))
            }
            assertEquals(listOf(canonical), db.bufferDao().observeChatList().first())
        }

    @Test
    fun roomChatListRowKeepsCappedCountsWithAndWithoutVisibilityReplacement() =
        runTest {
            db.messageDao().insertAll(
                List(1_005) { index ->
                    message(bufferId, "unread-$index", sender = "bob", serverTime = 100L + index, dedupKey = "$index", hasMention = true)
                },
            )
            for (current in listOf(MessageVisibilitySpec(), spec(FoolsMode.HIDE))) {
                val scoped = checkNotNull(reader.chatListRow(bufferId, current))
                assertEquals(1_000, scoped.unreadCount)
                assertEquals(1_000, scoped.mentionCount)
                assertEquals(reader.resolveChatList(db.bufferDao().observeChatList().first(), current).single(), scoped)
            }
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
            assertEquals(1, enriched.unreadCount)
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
                2,
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
    fun savedAnchorAdmitsCollapsedFoolAndRawTailStillAdvances() =
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

            assertEquals(ids[1], anchor?.id)
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
    fun collapsedFoolOnlyBufferHasUnreadAnchorButNoPreviewOrAttention() =
        runTest {
            val id =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(bufferId, "fool", sender = "alice", serverTime = 100, dedupKey = "fool")),
                    ).single()
            val spec = spec(FoolsMode.COLLAPSE)
            val resolved = reader.resolveChatList(db.bufferDao().observeChatList().first(), spec).single()

            assertEquals(id, reader.resolveSavedAnchor(bufferId, null, 100, id, spec)?.id)
            assertEquals(id, reader.firstVisibleUnreadAnchor(bufferId, TimelineAnchor(50, 0), spec)?.eventId)
            assertNull(resolved.lastMessageText)
            assertNull(resolved.lastMessageTime)
            assertEquals(1, resolved.unreadCount)
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
            // The default fool is ordinary unread, but disabled mention attention still excludes it.
            // The nearest mention is therefore mention-a (index 2), never the fool.
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

    private fun spec(
        mode: FoolsMode,
        presenceMode: PresenceMode = PresenceMode.ALL,
    ) = MessageVisibilitySpec(
        presenceMode = presenceMode,
        fools = setOf("alice"),
        foolsMode = mode,
    )
}
