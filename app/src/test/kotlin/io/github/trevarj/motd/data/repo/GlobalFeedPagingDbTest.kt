package io.github.trevarj.motd.data.repo

import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.EventRedirectEntity
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkIdentityEntity
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.FoolPreferences
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.KeywordMatch
import io.github.trevarj.motd.data.visibility.ActivityFilter
import io.github.trevarj.motd.data.visibility.GlobalFeedKey
import io.github.trevarj.motd.data.visibility.GlobalFeedKind
import io.github.trevarj.motd.data.visibility.GlobalFeedSeek
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.globalFeedPagingQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
    fun mixedFoolsRemainExcludedFromAllButOnlyPermittedVisibleMentionAppears() =
        runTest {
            db.messageDao().insertAll(
                listOf("a", "b", "c").mapIndexed { index, sender ->
                    message(bufferA, sender, sender = sender, serverTime = 100L * (index + 1), dedupKey = sender, hasMention = true)
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
            for (reveal in listOf(false, true)) {
                val current = spec.copy(revealHiddenFools = reveal)
                assertTrue(db.messageDao().globalFeedRows(globalFeedPagingQuery(current)).isEmpty())
                for (filter in listOf(ActivityFilter.ALL, ActivityFilter.MENTIONS)) {
                    val rows = db.messageDao().globalFeedRows(globalFeedPagingQuery(current, activityFilter = filter))
                    assertEquals(listOf("b"), rows.map { it.message.sender })
                }
            }
            val disabled = spec.copy(foolPreferences = spec.foolPreferences + ("b" to FoolPreferences(FoolsMode.COLLAPSE)))
            assertTrue(db.messageDao().globalFeedRows(globalFeedPagingQuery(disabled, activityFilter = ActivityFilter.MENTIONS)).isEmpty())
        }

    @Test
    fun mentionFeedUsesEachRowsCasemapCategoryAndConservativeAccountActorMergePastBindLimit() =
        runTest {
            val quoted = "fool-' or 1=1 -- λ"
            val configured =
                buildList {
                    repeat(1_501) { add("fool-$it") }
                    addAll(listOf("nick[", "account", quoted, "dm"))
                }
            val preferences =
                mapOf(
                    "nick[" to FoolPreferences(FoolsMode.COLLAPSE, notifyMentions = true),
                    "account" to FoolPreferences(FoolsMode.HIDE, notifyDirectMessages = true),
                    quoted to FoolPreferences(FoolsMode.DIMMED, notifyMentions = true),
                    "dm" to FoolPreferences(notifyDirectMessages = true),
                )
            val expected = mutableSetOf<String>()
            for (mapping in listOf("rfc1459", "rfc1459-strict", "ascii")) {
                val network = db.networkDao().insert(network(name = mapping))
                db.networkIdentityDao().upsert(NetworkIdentityEntity(network, caseMapping = mapping))
                val channel = db.bufferDao().insert(buffer(network, "#$mapping"))
                val query = db.bufferDao().insert(buffer(network, "dm", type = BufferType.QUERY))
                db.messageDao().insertAll(
                    listOf(
                        message(channel, "$mapping-nick", sender = "nick{", serverTime = 100, dedupKey = "nick", hasMention = true),
                        message(channel, "$mapping-collision", sender = "nick{", serverTime = 200, dedupKey = "collision", hasMention = true).copy(senderAccount = "account"),
                        message(channel, "$mapping-quoted", sender = quoted, serverTime = 300, dedupKey = "quoted", hasMention = true),
                        message(channel, "$mapping-channel-dm-only", sender = "dm", serverTime = 400, dedupKey = "channel-dm", hasMention = true),
                        message(query, "$mapping-dm", sender = "dm", serverTime = 500, dedupKey = "dm", hasMention = true),
                        message(query, "$mapping-dm-mention-only", sender = "nick{", serverTime = 600, dedupKey = "dm-no", hasMention = true),
                    ),
                )
                expected += "$mapping-nick"
                expected += "$mapping-quoted"
                expected += "$mapping-dm"
                // ASCII nick{ is not a fool and therefore is not restricted by nick['s DM switch.
                if (mapping == "ascii") expected += "$mapping-dm-mention-only"
            }
            for (reverse in listOf(false, true)) {
                val spec =
                    MessageVisibilitySpec(
                        fools = (if (reverse) configured.reversed() else configured).toSet(),
                        foolPreferences = preferences,
                    )
                val rows = db.messageDao().globalFeedRows(globalFeedPagingQuery(spec, activityFilter = ActivityFilter.MENTIONS))
                assertEquals(expected, rows.mapTo(mutableSetOf()) { it.message.text })
            }
        }

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

    @Test
    fun cancelingAnActivityPagerInvalidatesItsLoadedSourceWithoutADatabaseWrite() =
        runTest {
            val source = GlobalFeedPagingSource(db, MessageVisibilitySpec(), ActivityFilter.ALL)
            val presented = CompletableDeferred<Unit>()
            val presenter =
                object : PagingDataPresenter<SearchHit>(mainContext = UnconfinedTestDispatcher(testScheduler)) {
                    override suspend fun presentPagingDataEvent(event: PagingDataEvent<SearchHit>) {
                        presented.complete(Unit)
                    }
                }
            val collection =
                backgroundScope.launch {
                    globalFeedPager { source }.collectLatest(presenter::collectFrom)
                }

            presented.await()
            assertFalse(source.invalid)
            collection.cancelAndJoin()
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
                    activityFilter = ActivityFilter.MENTIONS,
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
                    activityFilter = ActivityFilter.MENTIONS,
                    isAtNewest = atNewest::get,
                )
            val anchoredRefresh = anchoredSource.refresh(key = anchoredKey, loadSize = 2)
            assertEquals(listOf("mention-3", "mention-2"), anchoredRefresh.data.map { it.message.text })

            atNewest.set(true)

            assertNull(source.getRefreshKey(anchoredState))
            val newestSource =
                GlobalFeedPagingSource(
                    db = db,
                    spec = MessageVisibilitySpec(),
                    activityFilter = ActivityFilter.MENTIONS,
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
                    isAtNewest = atNewest::get,
                )
            assertEquals(anchoredKey, allSource.getRefreshKey(anchoredState))
        }

    @Test
    fun mentionsRefreshPublishesBothSidesOfTheReadingAnchorTogetherWithinTheLoadBound() =
        runTest {
            val ids =
                db.messageDao().insertAll(
                    (1..200).map {
                        message(
                            if (it % 2 == 0) bufferA else bufferB,
                            "mention-$it",
                            serverTime = it * 100L,
                            dedupKey = "centered-$it",
                            hasMention = true,
                        )
                    },
                )
            val source = GlobalFeedPagingSource(db, MessageVisibilitySpec(), ActivityFilter.MENTIONS)
            val first = source.refresh(key = null, loadSize = GLOBAL_FEED_PAGING_CONFIG.initialLoadSize)
            assertEquals((200 downTo 51).map { "mention-$it" }, first.data.map { it.message.text })
            assertNull(first.prevKey)
            assertNotNull(first.nextKey)

            val anchor = GlobalFeedKey(10_000, ids[99])
            val refreshed = source.refresh(key = anchor, loadSize = 50)
            assertEquals((125 downTo 76).map { "mention-$it" }, refreshed.data.map { it.message.text })
            assertEquals(25, refreshed.data.indexOfFirst { it.message.id == anchor.id })
            assertEquals(GlobalFeedKey(12_500, ids[124]), refreshed.prevKey)
            assertEquals(GlobalFeedKey(7_600, ids[75]), refreshed.nextKey)

            val nearNewest = source.refresh(key = GlobalFeedKey(19_800, ids[197]), loadSize = 50)
            assertEquals((200 downTo 151).map { "mention-$it" }, nearNewest.data.map { it.message.text })
            assertNull(nearNewest.prevKey)

            val nearOldest = source.refresh(key = GlobalFeedKey(200, ids[1]), loadSize = 50)
            assertEquals((27 downTo 1).map { "mention-$it" }, nearOldest.data.map { it.message.text })
            assertNull(nearOldest.nextKey)
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
                        activityFilter = ActivityFilter.MENTIONS,
                    ),
                )

            assertEquals(listOf("canonical", "archived", "muted"), rows.map { it.message.text })
        }

    @Test
    fun activityFiltersPageStoredMentionAndKeywordProvenanceWithoutRepeatingOverlap() =
        runTest {
            val archived = db.bufferDao().insert(buffer(networkA, "#archived").copy(archived = true))
            db.messageDao().insertAll(
                listOf(
                    message(bufferA, "mention", serverTime = 100, dedupKey = "mention", hasMention = true),
                    message(archived, "silent", serverTime = 200, dedupKey = "silent", hasMention = true)
                        .copy(directMention = false, keywordMatch = KeywordMatch.SILENT),
                    message(bufferB, "alert", serverTime = 200, dedupKey = "alert", hasMention = true)
                        .copy(directMention = false, keywordMatch = KeywordMatch.ALERT),
                    message(bufferA, "both", serverTime = 300, dedupKey = "both", hasMention = true)
                        .copy(keywordMatch = KeywordMatch.ALERT),
                    message(bufferA, "unclassified", serverTime = 400, dedupKey = "unclassified"),
                    message(bufferB, "nonmatch", serverTime = 500, dedupKey = "nonmatch")
                        .copy(keywordMatch = KeywordMatch.NONE),
                    message(bufferA, "fool", sender = "fool", serverTime = 600, dedupKey = "fool", hasMention = true)
                        .copy(directMention = false, keywordMatch = KeywordMatch.SILENT),
                    message(bufferA, "self", serverTime = 700, dedupKey = "self", hasMention = true, isSelf = true)
                        .copy(keywordMatch = KeywordMatch.ALERT),
                    message(bufferA, "redacted", serverTime = 800, dedupKey = "redacted", hasMention = true, kind = MessageKind.REDACTED)
                        .copy(keywordMatch = KeywordMatch.ALERT),
                ),
            )
            val spec = MessageVisibilitySpec(fools = setOf("fool"))
            val expected =
                mapOf(
                    ActivityFilter.ALL to listOf("both", "alert", "silent", "mention"),
                    ActivityFilter.MENTIONS to listOf("both", "mention"),
                    ActivityFilter.KEYWORDS to listOf("both", "alert", "silent"),
                )
            for ((filter, texts) in expected) {
                val source = GlobalFeedPagingSource(db, spec, filter)
                val first = source.refresh(key = null, loadSize = 2)
                val second = source.append(checkNotNull(first.nextKey), loadSize = 2)
                assertEquals(texts, (first.data + second.data).map { it.message.text })
            }

            val optedIn = spec.copy(foolPreferences = mapOf("fool" to FoolPreferences(notifyMentions = true)))
            val keywords = GlobalFeedPagingSource(db, optedIn, ActivityFilter.KEYWORDS).refresh(key = null, loadSize = 4)
            assertEquals(listOf("fool", "both", "alert", "silent"), keywords.data.map { it.message.text })
        }

    @Test
    fun mixedActivitySeeksAcrossEqualTimesAndRowIdsInBothDirectionsAndRefreshesFromAReaction() =
        runTest {
            val ids =
                db.messageDao().insertAll(
                    listOf(
                        message(bufferA, "own parent", serverTime = 25, dedupKey = "parent", isSelf = true, msgid = "parent"),
                        message(bufferA, "oldest", serverTime = 100, dedupKey = "oldest", hasMention = true),
                        message(bufferB, "tied", serverTime = 300, dedupKey = "tied", hasMention = true),
                        message(bufferB, "newest", serverTime = 500, dedupKey = "newest", hasMention = true),
                    ),
                )
            val (parent, oldest, tied, newest) = ids
            val atTie = activityReaction(parent, at = 300, actor = "alice").copy(id = tied)
            val higherId =
                activityReaction(parent, at = 300, actor = "bob")
                    .copy(id = tied + 1, activitySender = "Bob", activityAccount = "bob-account")
            val older = activityReaction(parent, at = 200, actor = "carol").copy(id = tied + 2)
            for (reaction in listOf(atTie, higherId, older)) db.reactionDao().upsert(reaction)

            val source = GlobalFeedPagingSource(db, MessageVisibilitySpec(), ActivityFilter.ALL)
            val page1 = source.refresh(key = null, loadSize = 2)
            val page2 = source.append(checkNotNull(page1.nextKey), loadSize = 2)
            val page3 = source.append(checkNotNull(page2.nextKey), loadSize = 2)
            assertEquals(
                listOf(newest to null, parent to higherId.id, parent to atTie.id, tied to null, parent to older.id, oldest to null),
                (page1.data + page2.data + page3.data).map { it.message.id to it.activityReaction?.id },
            )

            val reactionKey =
                source.getRefreshKey(
                    PagingState(listOf(page2), anchorPosition = 0, config = GLOBAL_FEED_PAGING_CONFIG, leadingPlaceholderCount = 0),
                )
            assertEquals(GlobalFeedKey(300, tied, GlobalFeedKind.REACTION), reactionKey)
            val fromReaction = source.prepend(checkNotNull(reactionKey), loadSize = 2)
            assertEquals(page1.data, fromReaction.data)
            val fromMessage = source.prepend(checkNotNull(page2.nextKey), loadSize = 2)
            assertEquals(listOf(higherId.id, atTie.id), fromMessage.data.map { it.activityReaction?.id })
            val refreshed = source.refresh(key = reactionKey, loadSize = 4)
            assertEquals(page1.data + page2.data, refreshed.data)

            val reactions = GlobalFeedPagingSource(db, MessageVisibilitySpec(), ActivityFilter.REACTIONS)
            val result = reactions.refresh(key = null, loadSize = 3)
            assertEquals(listOf(higherId.id, atTie.id, older.id), result.data.map { it.activityReaction?.id })
            assertEquals(listOf("Bob", "alice", "carol"), result.data.map { it.activityReaction?.sender })
            val firstReaction = result.data.first()
            assertEquals("bob-account", firstReaction.activityReaction?.account)
            assertTrue(result.data.all { it.message.id == parent && it.message.serverTime == 25L && it.activityReaction?.emoji == "👍" })
        }

    @Test
    fun reactionEligibilityChecksTheReactorAndCanonicalParentBeforeThePageLimit() =
        runTest {
            db.networkIdentityDao().upsert(NetworkIdentityEntity(networkB, caseMapping = "ascii"))
            val query = db.bufferDao().insert(buffer(networkA, "alice", type = BufferType.QUERY))
            val archived = db.bufferDao().insert(buffer(networkB, "#archived").copy(archived = true))
            val dismissed = db.bufferDao().insert(buffer(networkA, "#dismissed").copy(dismissed = true))
            val closing = db.bufferDao().insert(buffer(networkA, "#closing").copy(pendingCloseAt = 1_000))
            val redirected = db.bufferDao().insert(buffer(networkA, "#redirected").copy(redirectToRoomId = bufferA))
            val server = db.bufferDao().insert(buffer(networkA, "server", type = BufferType.SERVER))
            val rooms = listOf(bufferA, archived, query, dismissed, closing, redirected, server, bufferA, bufferA, bufferA)
            val parents =
                db.messageDao().insertAll(
                    rooms.mapIndexed { index, room ->
                        message(
                            room,
                            "parent-$index",
                            sender = "hidden-account",
                            serverTime = 1,
                            dedupKey = "$index",
                            kind = if (index == 8) MessageKind.REDACTED else MessageKind.PRIVMSG,
                            isSelf = index != 9,
                            msgid = "parent-$index",
                        )
                    },
                )
            db.canonicalTimelineDao().upsertEventRedirect(EventRedirectEntity(losingEventId = parents[7], canonicalEventId = parents[0]))

            suspend fun react(
                index: Int,
                time: Long,
                actor: String,
                account: String? = null,
            ) {
                db.reactionDao().upsert(
                    activityReaction(parents[index], at = time, actor = actor, room = rooms[index], target = "parent-$index")
                        .copy(activityAccount = account),
                )
            }
            react(0, 100, "approved")
            react(1, 200, "nick{") // ASCII does not fold nick[ to nick{, even in this archived room.
            react(2, 300, "dm-only")
            react(0, 7_000, "nick{") // Default RFC1459 does fold those names.
            react(0, 7_100, "not-a-fool", account = "hidden-account")
            react(0, 7_200, "dm-only")
            react(2, 7_300, "approved") // Channel permission is not DM permission.
            for (index in 3..9) react(index, 8_000, "alice")
            val orphan = activityReaction(parents[0], at = 9_000, actor = "orphan").copy(targetEventId = null)
            val mismatchedRoom = activityReaction(parents[0], at = 9_100, actor = "wrong-room", room = bufferB)
            val historical = activityReaction(parents[0], at = 9_200, actor = "historic").copy(activityAt = null)
            val incomplete = activityReaction(parents[0], at = 9_300, actor = "incomplete").copy(activitySender = null)
            for (reaction in listOf(orphan, mismatchedRoom, historical, incomplete)) db.reactionDao().upsert(reaction)

            val spec =
                MessageVisibilitySpec(
                    fools = setOf("nick[", "hidden-account", "approved", "dm-only"),
                    foolPreferences =
                        mapOf(
                            "nick[" to FoolPreferences(FoolsMode.HIDE, notifyMentions = true),
                            "hidden-account" to FoolPreferences(FoolsMode.HIDE, notifyMentions = true),
                            "approved" to FoolPreferences(FoolsMode.COLLAPSE, notifyMentions = true),
                            "dm-only" to FoolPreferences(FoolsMode.BLUR, notifyDirectMessages = true),
                        ),
                    revealHiddenFools = true,
                )
            for (filter in listOf(ActivityFilter.ALL, ActivityFilter.REACTIONS)) {
                val page = GlobalFeedPagingSource(db, spec, filter).refresh(key = null, loadSize = 3)
                assertEquals(listOf("dm-only", "nick{", "approved"), page.data.map { it.activityReaction?.sender })
                assertEquals(listOf(parents[2], parents[1], parents[0]), page.data.map { it.message.id })
            }
        }

    @Test
    fun reactionVisibilityReclassifiesCapturedSpellingWhenCaseMappingChanges() =
        runTest {
            db.networkIdentityDao().upsert(NetworkIdentityEntity(networkA, caseMapping = "ascii"))
            val ownMessage = message(bufferA, "own parent", serverTime = 25, dedupKey = "parent", isSelf = true, msgid = "parent")
            val inserted = db.messageDao().insertAll(listOf(ownMessage))
            val parent = inserted.single()
            db.reactionDao().upsert(activityReaction(parent, at = 200, actor = "iλ[").copy(activitySender = "Iλ["))
            db.reactionDao().upsert(activityReaction(parent, at = 100, actor = "t~").copy(activitySender = "T~"))
            val spec =
                MessageVisibilitySpec(
                    fools = setOf("iλ{", "t^"),
                    foolPreferences =
                        mapOf(
                            "iλ{" to FoolPreferences(FoolsMode.HIDE, notifyMentions = true),
                            "t^" to FoolPreferences(FoolsMode.HIDE),
                        ),
                )
            val ascii = GlobalFeedPagingSource(db, spec, ActivityFilter.REACTIONS)
            assertEquals(listOf("Iλ[", "T~"), ascii.refresh(key = null, loadSize = 2).data.map { it.activityReaction?.sender })

            // RFC1459-strict folds brackets, but not ~. Case of the advertisement is immaterial.
            db.networkIdentityDao().upsert(NetworkIdentityEntity(networkA, caseMapping = "RFC1459-STRICT"))
            assertTrue(ascii.invalid)
            val strict = GlobalFeedPagingSource(db, spec, ActivityFilter.REACTIONS)
            val strictRow = strict.refresh(key = null, loadSize = 1).data.single()
            assertEquals("T~", strictRow.activityReaction?.sender)

            // Removing the advertisement restores full RFC1459, so ~ also folds to ^.
            db.networkIdentityDao().upsert(NetworkIdentityEntity(networkA, caseMapping = null))
            assertTrue(strict.invalid)
            val fallback = GlobalFeedPagingSource(db, spec, ActivityFilter.REACTIONS)
            assertTrue(fallback.refresh(key = null, loadSize = 2).data.isEmpty())
        }

    @Test
    fun onlyReactionScopesInvalidateOnReactionAddAndRemove() =
        runTest {
            val ownMessage = message(bufferA, "own parent", serverTime = 25, dedupKey = "parent", isSelf = true, msgid = "parent")
            val inserted = db.messageDao().insertAll(listOf(ownMessage))
            val parent = inserted.single()
            val scopes = listOf(null) + ActivityFilter.entries
            val sources = scopes.associateWith { GlobalFeedPagingSource(db, MessageVisibilitySpec(), it) }
            for ((filter, source) in sources) {
                val loaded = source.refresh(key = null, loadSize = 10)
                assertEquals(if (filter == null) 1 else 0, loaded.data.size)
            }

            val reaction = activityReaction(parent, at = 100, actor = "alice")
            db.reactionDao().upsert(reaction)
            assertEquals(setOf(ActivityFilter.ALL, ActivityFilter.REACTIONS), sources.filterValues { it.invalid }.keys)

            val afterAdd = GlobalFeedPagingSource(db, MessageVisibilitySpec(), ActivityFilter.REACTIONS)
            val addedRow = afterAdd.refresh(key = null, loadSize = 10).data.single()
            assertEquals("alice", addedRow.activityReaction?.sender)
            db.reactionDao().deleteForTarget(reaction.bufferId, reaction.targetMsgid)
            assertTrue(afterAdd.invalid)
            assertTrue(GlobalFeedPagingSource(db, MessageVisibilitySpec(), ActivityFilter.ALL).refresh(key = null, loadSize = 10).data.isEmpty())
        }

    private fun activityReaction(
        parent: Long,
        at: Long,
        actor: String,
        room: Long = bufferA,
        target: String = "parent",
    ): ReactionEntity =
        ReactionEntity(
            bufferId = room,
            targetMsgid = target,
            actorKey = actor,
            sender = actor,
            emoji = "👍",
            serverTime = 10,
            targetEventId = parent,
            activityAt = at,
            activitySender = actor,
        )

    @Test
    fun messageActivityFiltersUseTheCrossBufferMentionIndex() =
        runTest {
            for (filter in listOf(ActivityFilter.MENTIONS, ActivityFilter.KEYWORDS)) {
                val plan =
                    db.explainQueryPlan(
                        globalFeedPagingQuery(
                            spec = MessageVisibilitySpec(),
                            key = GlobalFeedKey(serverTime = 400, id = 9),
                            seek = GlobalFeedSeek.OLDER,
                            limit = 50,
                            activityFilter = filter,
                        ).sql,
                    )

                assertFalse(plan.toString(), plan.any { "USE TEMP B-TREE FOR ORDER BY" in it })
                assertTrue(plan.toString(), plan.any { "index_messages_hasMention_serverTime_id" in it })
            }
        }

    @Test
    fun reactionAnchorsSeekBothTheEqualTimeIdRangeAndDifferentTimestamps() =
        runTest {
            for (seek in GlobalFeedSeek.entries) {
                val range = if (seek == GlobalFeedSeek.NEWER) ">" else "<"
                val indexSeek = "SEARCH r USING INDEX index_reactions_activityAt"
                val tieSeek = "$indexSeek (activityAt=? AND rowid$range?)"
                val timeSeek = "$indexSeek (activityAt$range?)"
                val query =
                    globalFeedPagingQuery(
                        spec = MessageVisibilitySpec(),
                        key = GlobalFeedKey(400, 9, GlobalFeedKind.REACTION),
                        seek = seek,
                        limit = 50,
                        activityFilter = ActivityFilter.ALL,
                    )
                val plan = db.explainQueryPlan(query.sql)
                assertTrue(plan.toString(), plan.any { "SEARCH m USING INDEX index_messages_hasMention_serverTime_id" in it })
                assertTrue(plan.toString(), plan.any { tieSeek in it })
                assertTrue(plan.toString(), plan.any { timeSeek in it })

                val reactionOnly =
                    globalFeedPagingQuery(
                        spec = MessageVisibilitySpec(),
                        key = GlobalFeedKey(400, 9, GlobalFeedKind.REACTION),
                        seek = seek,
                        limit = 50,
                        activityFilter = ActivityFilter.REACTIONS,
                    )
                val reactionPlan = db.explainQueryPlan(reactionOnly.sql)
                assertTrue(reactionPlan.toString(), reactionPlan.any { tieSeek in it })
                assertTrue(reactionPlan.toString(), reactionPlan.any { timeSeek in it })
            }

            val firstPage = globalFeedPagingQuery(MessageVisibilitySpec(), limit = 50, activityFilter = ActivityFilter.REACTIONS)
            val firstPlan = db.explainQueryPlan(firstPage.sql)
            assertFalse(firstPlan.toString(), firstPlan.any { "USE TEMP B-TREE FOR ORDER BY" in it })
        }

    @Test
    fun allFeedScopesKeepTheSharedLifecycleFoolAndOrderingClauses() {
        for (filter in listOf(null) + ActivityFilter.entries) {
            val sql =
                globalFeedPagingQuery(
                    spec = MessageVisibilitySpec(fools = setOf("fool")),
                    activityFilter = filter,
                ).sql

            assertTrue(sql, "b.dismissed = 0" in sql)
            assertTrue(sql, "b.pendingCloseAt IS NULL" in sql)
            assertTrue(sql, "b.redirectToRoomId IS NULL" in sql)
            assertTrue(sql, "redirect.losingEventId IS NULL" in sql)
            assertTrue(sql, "m.kind IN" in sql)
            if (filter != ActivityFilter.REACTIONS) {
                assertTrue(sql, "m.normalizedActor" in sql)
                assertTrue(sql, "ORDER BY m.serverTime DESC, m.id DESC" in sql)
            }
            if (filter == ActivityFilter.ALL || filter == ActivityFilter.REACTIONS) {
                assertTrue(sql, "ORDER BY r.activityAt DESC, r.id DESC" in sql)
                assertTrue(sql, "lower(replace(" in sql)
                assertTrue(sql, "COALESCE(ni.caseMapping,'rfc1459') COLLATE NOCASE" in sql)
            }
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

private suspend fun GlobalFeedPagingSource.prepend(
    key: GlobalFeedKey,
    loadSize: Int,
): PagingSource.LoadResult.Page<GlobalFeedKey, SearchHit> =
    load(
        PagingSource.LoadParams.Prepend(key = key, loadSize = loadSize, placeholdersEnabled = false),
    ) as PagingSource.LoadResult.Page
