package io.github.trevarj.motd.data.repo

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.EventRedirectEntity
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkIdentityEntity
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.visibility.GlobalFeedKey
import io.github.trevarj.motd.data.visibility.GlobalFeedMode
import io.github.trevarj.motd.data.visibility.GlobalFeedSeek
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.globalFeedPagingQuery
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean

/** The global feed against a real database: ordering, fool scoping, keyset paging, and the plan. */
@RunWith(RobolectricTestRunner::class)
class GlobalFeedPagingDbTest {
    private lateinit var db: MotdDatabase
    private var networkA = 0L
    private var networkB = 0L
    private var bufferA = 0L
    private var bufferB = 0L

    @Before
    fun setUp() =
        runTest {
            // Direct commit: an insert's invalidation must reach a live source inside the test body.
            db = inMemoryDb(directCommit = true)
            networkA = db.networkDao().insert(network(name = "libera"))
            networkB = db.networkDao().insert(network(name = "oftc"))
            bufferA = db.bufferDao().insert(buffer(networkA, "#a"))
            bufferB = db.bufferDao().insert(buffer(networkB, "#b"))
        }

    @After
    fun tearDown() = db.close()

    @Test
    fun interleavesConversationRowsAcrossNetworksAndScopesFoolsToTheirOwnCasemap() =
        runTest {
            // The configured fool "Ann[ie]" folds to "ann{ie}" under RFC1459 (brackets fold) and
            // stays "ann[ie]" under ASCII. Both troll rows carry the stored actor "ann{ie}".
            db.networkIdentityDao().upsert(NetworkIdentityEntity(networkA, caseMapping = "rfc1459"))
            db.networkIdentityDao().upsert(NetworkIdentityEntity(networkB, caseMapping = "ascii"))
            db.messageDao().insertAll(
                listOf(
                    message(bufferA, "a-oldest", sender = "alice", serverTime = 100, dedupKey = "a1"),
                    message(bufferB, "b-mid", sender = "bob", serverTime = 200, dedupKey = "b1"),
                    message(
                        bufferA,
                        "a-join",
                        sender = "carol",
                        serverTime = 250,
                        dedupKey = "a-join",
                        kind = MessageKind.JOIN,
                    ),
                    message(bufferA, "troll-A", sender = "ann{ie}", serverTime = 300, dedupKey = "a-troll"),
                    message(bufferB, "troll-B", sender = "ann{ie}", serverTime = 350, dedupKey = "b-troll"),
                    message(bufferB, "b-newest", sender = "bob", serverTime = 400, dedupKey = "b2"),
                ),
            )
            // COLLAPSE, not HIDE: the global feed mutes fools in either mode.
            val spec = MessageVisibilitySpec(fools = setOf("Ann[ie]"), foolsMode = FoolsMode.COLLAPSE)

            val rows = db.messageDao().globalFeedRows(globalFeedPagingQuery(spec))

            assertEquals(
                listOf("b-newest", "troll-B", "b-mid", "a-oldest"),
                rows.map { it.message.text },
            )
            val newest = rows.first()
            assertEquals("#b", newest.bufferDisplayName)
            assertEquals("oftc", newest.networkName)
            assertEquals("ascii", newest.caseMapping)
            // Non-null on SearchHit: a raw query missing these columns would map them to null.
            assertEquals(BufferType.CHANNEL, newest.bufferType)
            assertEquals(networkB, newest.networkId)
        }

    /**
     * Same-second ties are routine across buffers. `timelineOrder` cannot break them: the settled
     * playback row carries a dense index (0) while the live row carries its rowid, so ordering on
     * it would sink every settled history row below every live one. The id does break them.
     */
    @Test
    fun sameSecondTiesAcrossBuffersOrderOnIdRatherThanOnPerBufferTimelineOrder() =
        runTest {
            val ids =
                db.messageDao().insertAll(
                    listOf(
                        message(bufferA, "settled-history", serverTime = 500, dedupKey = "t1"),
                        message(bufferB, "live", serverTime = 500, dedupKey = "t2"),
                    ),
                )
            // What CanonicalTimelineStore's playback settle writes: a dense per-buffer index.
            db.canonicalTimelineDao().updateTimelineOrder(ids[0], timelineOrder = 0, confirmed = true)

            val rows = db.messageDao().globalFeedRows(globalFeedPagingQuery(MessageVisibilitySpec()))

            assertEquals(listOf("live", "settled-history"), rows.map { it.message.text })
        }

    /** All three foldings plus the two default paths — unknown CASEMAPPING and no identity row at all. */
    @Test
    fun everyCasemapGetsItsOwnFoldingInOneQuery() =
        runTest {
            val cases =
                listOf(
                    "rfc1459" to "rfc1459",
                    "ascii" to "ascii",
                    "strict" to "rfc1459-strict",
                    "unknown" to "totally-made-up",
                    "default" to null,
                )
            cases.forEach { (name, casemap) ->
                val networkId = db.networkDao().insert(network(name = name))
                casemap?.let {
                    db.networkIdentityDao().upsert(NetworkIdentityEntity(networkId, caseMapping = it))
                }
                val room = db.bufferDao().insert(buffer(networkId, "#$name"))
                db.messageDao().insertAll(
                    listOf(
                        // "Ann[ie]" folds to this only where brackets fold: rfc1459 and strict.
                        message(room, "$name-brackets", sender = "ann{ie}", serverTime = 100, dedupKey = "$name-b"),
                        // "Bo~b" folds to this only under rfc1459, the one mapping that folds tilde.
                        message(room, "$name-tilde", sender = "bo^b", serverTime = 200, dedupKey = "$name-t"),
                    ),
                )
            }
            val spec = MessageVisibilitySpec(fools = setOf("Ann[ie]", "Bo~b"))

            val rows = db.messageDao().globalFeedRows(globalFeedPagingQuery(spec))

            assertEquals(
                setOf(
                    // rfc1459 mutes both; the row with no identity row is held to the same default.
                    "ascii-brackets",
                    "ascii-tilde",
                    "strict-tilde",
                    "unknown-brackets",
                    "unknown-tilde",
                ),
                rows.mapTo(mutableSetOf()) { it.message.text },
            )
        }

    /** Seek paging, not offset paging: each continuation starts from the previous page's last key. */
    @Test
    fun theFirstPageAndItsContinuationsDrainTheStreamNewestFirstWithoutRepeats() =
        runTest {
            db.messageDao().insertAll(
                (1..5).map { message(bufferA, "line-$it", serverTime = it * 100L, dedupKey = "p$it") },
            )
            val source = GlobalFeedPagingSource(db, MessageVisibilitySpec())

            val first = source.refresh(key = null, loadSize = 2)
            assertEquals(listOf("line-5", "line-4"), first.data.map { it.message.text })

            val second = source.append(first.nextKey!!, loadSize = 2)
            assertEquals(listOf("line-3", "line-2"), second.data.map { it.message.text })

            val third = source.append(second.nextKey!!, loadSize = 2)
            assertEquals(listOf("line-1"), third.data.map { it.message.text })
            // A short page is the end of the stream; the next seek finds nothing and closes it.
            assertNull(source.append(third.nextKey!!, loadSize = 2).nextKey)
        }

    /** Prepend seeks the other way: rows newer than the key, handed back newest-first. */
    @Test
    fun prependingFromAKeyReturnsTheNewerRowsInStreamOrder() =
        runTest {
            db.messageDao().insertAll(
                (1..4).map { message(bufferA, "line-$it", serverTime = it * 100L, dedupKey = "q$it") },
            )
            val source = GlobalFeedPagingSource(db, MessageVisibilitySpec())
            val bottom = source.refresh(key = null, loadSize = 10).data.last()

            val newer =
                source.load(
                    PagingSource.LoadParams.Prepend(
                        key = GlobalFeedKey(bottom.message.serverTime, bottom.message.id),
                        loadSize = 10,
                        placeholdersEnabled = false,
                    ),
                ) as PagingSource.LoadResult.Page

            assertEquals(listOf("line-4", "line-3", "line-2"), newer.data.map { it.message.text })
        }

    /** An arriving message must retire the live source; the screen reloads from the refresh key. */
    @Test
    fun anInsertInvalidatesTheLiveSource() =
        runTest {
            db.messageDao().insertAll(
                listOf(message(bufferA, "hello", serverTime = 100, dedupKey = "i1")),
            )
            val source = GlobalFeedPagingSource(db, MessageVisibilitySpec())
            assertEquals(listOf("hello"), source.refresh(key = null, loadSize = 10).data.map { it.message.text })
            assertFalse(source.invalid)

            db.messageDao().insertAll(
                listOf(message(bufferA, "arrived", serverTime = 200, dedupKey = "i2")),
            )

            assertTrue(source.invalid)
        }

    /** The identity table is observed too: a late CASEMAPPING changes which rows a page contains. */
    @Test
    fun anIdentityRowWrittenLaterInvalidatesTheLiveStream() =
        runTest {
            val spec = MessageVisibilitySpec(fools = setOf("Ann[ie]"))
            db.messageDao().insertAll(
                listOf(
                    message(bufferA, "hello", sender = "bob", serverTime = 100, dedupKey = "n1"),
                    message(bufferA, "troll", sender = "ann{ie}", serverTime = 200, dedupKey = "n2"),
                ),
            )
            val live = GlobalFeedPagingSource(db, spec)

            // No identity row yet: CASEMAPPING was never advertised, so RFC1459 applies and the
            // configured fool folds onto the stored actor.
            assertEquals(listOf("hello"), live.refresh(key = null, loadSize = 10).data.map { it.message.text })
            assertFalse(live.invalid)

            db.networkIdentityDao().upsert(NetworkIdentityEntity(networkA, caseMapping = "ascii"))

            assertTrue(live.invalid)
            val reloaded = GlobalFeedPagingSource(db, spec)
            assertEquals(
                listOf("troll", "hello"),
                reloaded.refresh(key = null, loadSize = 10).data.map { it.message.text },
            )
        }

    /** Refresh re-seeks from the anchor row's own key, so the viewport keeps the line it was on. */
    @Test
    fun refreshResumesFromTheAnchorRowAndStillReachesTheRowsAboveIt() =
        runTest {
            db.messageDao().insertAll(
                (1..6).map { message(bufferA, "line-$it", serverTime = it * 100L, dedupKey = "r$it") },
            )
            val source = GlobalFeedPagingSource(db, MessageVisibilitySpec())
            val loaded = source.refresh(key = null, loadSize = 6)
            // Anchored three rows down: "line-3".
            val anchorKey =
                source.getRefreshKey(
                    PagingState(
                        pages = listOf(loaded),
                        anchorPosition = 3,
                        config = GLOBAL_FEED_PAGING_CONFIG,
                        leadingPlaceholderCount = 0,
                    ),
                )
            assertNotNull(anchorKey)

            val refreshed = GlobalFeedPagingSource(db, MessageVisibilitySpec()).refresh(anchorKey, loadSize = 2)

            // The anchor row itself heads the page, so the viewport survives.
            assertEquals(listOf("line-3", "line-2"), refreshed.data.map { it.message.text })
            // And the rows above it stay reachable through prepend.
            assertEquals(GlobalFeedKey(300, loaded.data[3].message.id), refreshed.prevKey)
        }

    @Test
    fun mentionsRefreshStartsAtNewestOnlyWhileTheMentionsViewportIsAtNewest() =
        runTest {
            val ids =
                db.messageDao().insertAll(
                    (1..8).map {
                        message(
                            bufferA,
                            "mention-$it",
                            serverTime = it * 100L,
                            dedupKey = "mention-$it",
                            hasMention = true,
                        )
                    },
                )
            val atNewest = AtomicBoolean(false)
            val source =
                GlobalFeedPagingSource(
                    db = db,
                    spec = MessageVisibilitySpec(),
                    mode = GlobalFeedMode.MENTIONS,
                    isAtNewest = atNewest::get,
                )
            val loaded = source.refresh(key = null, loadSize = 8)
            val anchoredState =
                PagingState(
                    pages = listOf(loaded),
                    anchorPosition = 6,
                    config = GLOBAL_FEED_PAGING_CONFIG,
                    leadingPlaceholderCount = 0,
                )

            val anchoredKey = GlobalFeedKey(200, ids[1])
            assertEquals(anchoredKey, source.getRefreshKey(anchoredState))

            db.messageDao().insertAll(
                listOf(
                    message(
                        bufferA,
                        "new mention",
                        serverTime = 900,
                        dedupKey = "mention-new",
                        hasMention = true,
                    ),
                ),
            )
            val anchoredSource =
                GlobalFeedPagingSource(
                    db = db,
                    spec = MessageVisibilitySpec(),
                    mode = GlobalFeedMode.MENTIONS,
                    isAtNewest = atNewest::get,
                )
            val anchoredRefresh = anchoredSource.refresh(key = anchoredKey, loadSize = 2)
            assertEquals(listOf("mention-2", "mention-1"), anchoredRefresh.data.map { it.message.text })

            atNewest.set(true)

            assertNull(source.getRefreshKey(anchoredState))
            val newestSource =
                GlobalFeedPagingSource(
                    db = db,
                    spec = MessageVisibilitySpec(),
                    mode = GlobalFeedMode.MENTIONS,
                    isAtNewest = atNewest::get,
                )
            val newestRefresh = newestSource.refresh(key = null, loadSize = 2)
            assertEquals(
                "new mention",
                newestRefresh.data
                    .first()
                    .message
                    .text,
            )

            val allSource =
                GlobalFeedPagingSource(
                    db = db,
                    spec = MessageVisibilitySpec(),
                    mode = GlobalFeedMode.ALL,
                    isAtNewest = atNewest::get,
                )
            assertEquals(anchoredKey, allSource.getRefreshKey(anchoredState))
        }

    /** The plan must stay an ordered index walk with a real seek, not a per-page sort. */
    @Test
    fun theKeysetSeekWalksTheTimelineIndexInsteadOfSortingEveryPage() =
        runTest {
            val query =
                globalFeedPagingQuery(
                    MessageVisibilitySpec(),
                    key = GlobalFeedKey(serverTime = 400, id = 9),
                    seek = GlobalFeedSeek.OLDER,
                    limit = 50,
                )

            val plan = db.explainQueryPlan(query.sql)

            assertFalse(plan.toString(), plan.any { "USE TEMP B-TREE FOR ORDER BY" in it })
            assertTrue(plan.toString(), plan.any { "index_messages_serverTime_id" in it })
        }

    @Test
    fun theUnkeyedFirstPageWalksTheSameIndex() =
        runTest {
            val plan = db.explainQueryPlan(globalFeedPagingQuery(MessageVisibilitySpec()).sql)

            assertFalse(plan.toString(), plan.any { "USE TEMP B-TREE FOR ORDER BY" in it })
            assertTrue(plan.toString(), plan.any { "index_messages_serverTime_id" in it })
        }

    @Test
    fun dropsRedirectedRowsAndRoomsThatLeftTheStream() =
        runTest {
            val dismissed = db.bufferDao().insert(buffer(networkA, "eve", type = BufferType.QUERY).copy(dismissed = true))
            val archived = db.bufferDao().insert(buffer(networkA, "#archived").copy(archived = true))
            val closing = db.bufferDao().insert(buffer(networkA, "#closing").copy(pendingCloseAt = 5_000))
            val redirected = db.bufferDao().insert(buffer(networkA, "#renamed").copy(redirectToRoomId = bufferA))
            val console = db.bufferDao().insert(buffer(networkB, "libera", type = BufferType.SERVER))
            // BufferStore coercion plus the v36 backfill make a QUERY-typed console impossible; the
            // stream drops it on type alone.
            val bouncerServ =
                db.bufferDao().insert(buffer(networkA, "bouncerserv", type = BufferType.SERVER))
            val ids =
                db.messageDao().insertAll(
                    listOf(
                        message(bufferA, "canonical", serverTime = 100, dedupKey = "c1"),
                        message(bufferA, "lost-the-merge", serverTime = 110, dedupKey = "c2"),
                        message(dismissed, "dismissed", serverTime = 120, dedupKey = "d1"),
                        message(archived, "archived", serverTime = 130, dedupKey = "d2"),
                        message(closing, "closing", serverTime = 140, dedupKey = "d3"),
                        message(redirected, "redirected", serverTime = 150, dedupKey = "d4"),
                        message(console, "console", serverTime = 160, dedupKey = "d5"),
                        message(bouncerServ, "bouncer help", serverTime = 170, dedupKey = "d6"),
                    ),
                )
            db.canonicalTimelineDao().upsertEventRedirect(
                EventRedirectEntity(losingEventId = ids[1], canonicalEventId = ids[0]),
            )

            val rows = db.messageDao().globalFeedRows(globalFeedPagingQuery(MessageVisibilitySpec()))

            assertEquals(listOf("canonical"), rows.map { it.message.text })
        }

    @Test
    fun globalFeedRowKeepsIsSelfAndReportsAnUnadvertisedIdentityAsNull() =
        runTest {
            db.messageDao().insertAll(
                listOf(
                    message(bufferA, "mine", sender = "me", serverTime = 100, dedupKey = "s1", isSelf = true),
                    message(bufferA, "theirs", sender = "alice", serverTime = 200, dedupKey = "s2", isSelf = false),
                ),
            )

            val rows = db.messageDao().globalFeedRows(globalFeedPagingQuery(MessageVisibilitySpec()))

            assertEquals(listOf("theirs" to false, "mine" to true), rows.map { it.message.text to it.message.isSelf })
            assertNull(rows.first().caseMapping)
            assertNull(rows.first().chanTypes)
        }

    @Test
    fun mentionsFeedIncludesMutedAndArchivedMentionsButExcludesNonCanonicalOrIneligibleRows() =
        runTest {
            val muted = db.bufferDao().insert(buffer(networkA, "#muted").copy(muted = true))
            val archived = db.bufferDao().insert(buffer(networkA, "#archived").copy(archived = true))
            val dismissed = db.bufferDao().insert(buffer(networkA, "#dismissed").copy(dismissed = true))
            val closing = db.bufferDao().insert(buffer(networkA, "#closing").copy(pendingCloseAt = 1_000L))
            val redirected = db.bufferDao().insert(buffer(networkA, "#redirected").copy(redirectToRoomId = bufferA))
            val ids =
                db.messageDao().insertAll(
                    listOf(
                        message(muted, "muted", sender = "alice", serverTime = 100, dedupKey = "m1", hasMention = true),
                        message(archived, "archived", sender = "alice", serverTime = 200, dedupKey = "m2", hasMention = true),
                        message(bufferA, "self", sender = "me", serverTime = 300, dedupKey = "m3", hasMention = true, isSelf = true),
                        message(bufferA, "ordinary", sender = "alice", serverTime = 400, dedupKey = "m4"),
                        message(dismissed, "dismissed", sender = "alice", serverTime = 500, dedupKey = "m5", hasMention = true),
                        message(closing, "closing", sender = "alice", serverTime = 600, dedupKey = "m6", hasMention = true),
                        message(redirected, "redirected", sender = "alice", serverTime = 700, dedupKey = "m7", hasMention = true),
                        message(bufferB, "fool", sender = "fool", serverTime = 800, dedupKey = "m8", hasMention = true),
                        message(bufferA, "canonical", sender = "alice", serverTime = 900, dedupKey = "m9", hasMention = true),
                        message(bufferA, "redirect-loser", sender = "alice", serverTime = 1_000, dedupKey = "m10", hasMention = true),
                    ),
                )
            db.canonicalTimelineDao().upsertEventRedirect(EventRedirectEntity(losingEventId = ids.last(), canonicalEventId = ids[8]))

            val rows =
                db.messageDao().globalFeedRows(
                    globalFeedPagingQuery(
                        spec = MessageVisibilitySpec(fools = setOf("fool")),
                        mode = GlobalFeedMode.MENTIONS,
                    ),
                )

            assertEquals(listOf("canonical", "archived", "muted"), rows.map { it.message.text })
        }

    @Test
    fun mentionsPagingUsesTheCrossBufferMentionIndex() =
        runTest {
            val plan =
                db.explainQueryPlan(
                    globalFeedPagingQuery(
                        spec = MessageVisibilitySpec(),
                        key = GlobalFeedKey(serverTime = 400, id = 9),
                        seek = GlobalFeedSeek.OLDER,
                        limit = 50,
                        mode = GlobalFeedMode.MENTIONS,
                    ).sql,
                )

            assertFalse(plan.toString(), plan.any { "USE TEMP B-TREE FOR ORDER BY" in it })
            assertTrue(plan.toString(), plan.any { "index_messages_hasMention_serverTime_id" in it })
        }

    @Test
    fun bothFeedModesKeepTheSharedLifecycleFoolAndOrderingClauses() {
        GlobalFeedMode.entries.forEach { mode ->
            val sql =
                globalFeedPagingQuery(
                    spec = MessageVisibilitySpec(fools = setOf("fool")),
                    mode = mode,
                ).sql

            assertTrue(sql, "b.dismissed = 0" in sql)
            assertTrue(sql, "b.pendingCloseAt IS NULL" in sql)
            assertTrue(sql, "b.redirectToRoomId IS NULL" in sql)
            assertTrue(sql, "redirect.losingEventId IS NULL" in sql)
            assertTrue(sql, "m.kind IN" in sql)
            assertTrue(sql, "normalizedActor" in sql)
            assertTrue(sql, "ORDER BY m.serverTime DESC, m.id DESC" in sql)
        }
    }
}

private fun MotdDatabase.explainQueryPlan(sql: String): List<String> =
    openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
        buildList {
            // The plan's detail is the last column in every SQLite version that reports one.
            while (cursor.moveToNext()) add(cursor.getString(cursor.columnCount - 1))
        }
    }

// Whole-stream read for the query-shape tests: no paging machinery, one page big enough for all.
private suspend fun MessageDao.globalFeedRows(query: SupportSQLiteQuery): List<SearchHit> = globalFeedPage(query)

private suspend fun GlobalFeedPagingSource.refresh(
    key: GlobalFeedKey?,
    loadSize: Int,
): PagingSource.LoadResult.Page<GlobalFeedKey, SearchHit> =
    load(
        PagingSource.LoadParams.Refresh(key = key, loadSize = loadSize, placeholdersEnabled = false),
    ) as PagingSource.LoadResult.Page

private suspend fun GlobalFeedPagingSource.append(
    key: GlobalFeedKey,
    loadSize: Int,
): PagingSource.LoadResult.Page<GlobalFeedKey, SearchHit> =
    load(
        PagingSource.LoadParams.Append(key = key, loadSize = loadSize, placeholdersEnabled = false),
    ) as PagingSource.LoadResult.Page
