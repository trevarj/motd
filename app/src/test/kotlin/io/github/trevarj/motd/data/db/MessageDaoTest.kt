package io.github.trevarj.motd.data.db

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageDaoTest {
    private lateinit var db: MotdDatabase
    private var bufferId: Long = 0

    @Before
    fun setUp() = runTest {
        db = inMemoryDb()
        val nid = db.networkDao().insert(network())
        bufferId = db.bufferDao().insert(buffer(nid, "#chan"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun dedupKeyIsDiagnostic_directDaoInsertsDoNotClaimCanonicalIdentity() = runTest {
        val dao = db.messageDao()
        val a = message(bufferId, "hello", serverTime = 1000, dedupKey = "msg-1", msgid = "msg-1")
        val b = message(bufferId, "hello again", serverTime = 2000, dedupKey = "msg-1", msgid = "msg-1")

        val firstIds = dao.insertAll(listOf(a))
        val secondIds = dao.insertAll(listOf(b))

        assertEquals(1, firstIds.size)
        assertEquals(true, secondIds.single() > 0)
        assertEquals(2000L, dao.newestTime(bufferId))
        assertEquals(2, dao.pagingList(bufferId).size)
    }

    @Test
    fun msgidUniquenessLivesInNetworkScopedAliasStore_notDirectDao() = runTest {
        val dao = db.messageDao()
        val first = message(bufferId, "hi", serverTime = 1000, dedupKey = "srv-9", msgid = "srv-9", isSelf = true)
        // A second row: same msgid, DIFFERENT dedupKey and time (a distinct local key).
        val second = message(bufferId, "hi", serverTime = 2000, dedupKey = "local-sha1", msgid = "srv-9", isSelf = true)

        assertEquals(true, dao.insertAll(listOf(first)).single() > 0)
        assertEquals(true, dao.insertAll(listOf(second)).single() > 0)
        assertEquals(2000L, dao.newestTime(bufferId))
        assertEquals(2, dao.pagingList(bufferId).size)
    }

    @Test
    fun msgidUniqueness_nullMsgidsCoexist() = runTest {
        // NULLs are distinct in a UNIQUE index: many still-pending / msgid-less self rows must coexist.
        val dao = db.messageDao()
        dao.insertAll(listOf(
            message(bufferId, "a", serverTime = 1, dedupKey = "pending:l1", msgid = null, isSelf = true, pendingLabel = "l1"),
            message(bufferId, "b", serverTime = 2, dedupKey = "pending:l2", msgid = null, isSelf = true, pendingLabel = "l2"),
        ))
        assertEquals(2, dao.pagingList(bufferId).size)
    }

    @Test
    fun observeByMsgid_emitsWhenReplyTargetArrivesAfterSubscription() = runTest {
        val dao = db.messageDao()
        assertNull(dao.observeByMsgid(bufferId, "late-parent").first())

        val target = async {
            dao.observeByMsgid(bufferId, "late-parent").first { it != null }
        }
        dao.insertAll(
            listOf(
                message(
                    bufferId,
                    "parent body",
                    serverTime = 1000,
                    dedupKey = "late-parent",
                    msgid = "late-parent",
                    isSelf = true,
                ),
            ),
        )

        assertEquals("parent body", target.await()?.text)
    }

    @Test
    fun observeCanonicalMsgid_followsEventRedirectAfterCoalescence() = runTest {
        val dao = db.messageDao()
        val winnerId = dao.insertAll(
            listOf(
                message(
                    bufferId,
                    "authoritative echo",
                    serverTime = 1_000,
                    dedupKey = "server-id",
                    msgid = "server-id",
                    isSelf = true,
                ),
            ),
        ).single()
        val loserId = dao.insertAll(
            listOf(
                message(
                    bufferId,
                    "optimistic send",
                    serverTime = 900,
                    dedupKey = "pending:label",
                    msgid = null,
                    isSelf = true,
                    pendingLabel = "label",
                ),
            ),
        ).single()

        val promoted = async {
            dao.observeCanonicalMsgid(loserId).first { it != null }
        }
        yield()
        db.canonicalTimelineDao().upsertEventRedirect(EventRedirectEntity(loserId, winnerId))
        dao.deleteById(loserId)

        assertEquals("server-id", promoted.await())
    }

    @Test
    fun findSelfMsgidlessCandidate_matchesMsgidlessSelfRow_ignoresMsgidBearing() = runTest {
        val dao = db.messageDao()
        dao.insertAll(listOf(
            // A msgid-bearing self row of the same text must NOT be returned (already has identity).
            message(bufferId, "dup", serverTime = 1000, dedupKey = "m1", msgid = "m1", isSelf = true),
            // The msgid-less self row awaiting its durable identity: the one to reconcile onto.
            message(bufferId, "dup", serverTime = 2000, dedupKey = "pending:l", msgid = null, isSelf = true, pendingLabel = "l"),
        ))
        val hit = dao.findSelfMsgidlessCandidate(bufferId, "dup")
        assertNotNull(hit)
        assertNull(hit!!.msgid)
        assertEquals("l", hit.pendingLabel)
    }

    @Test
    fun directDaoDoesNotDeduplicateHistoryOverlapOutsideCanonicalStore() = runTest {
        val dao = db.messageDao()
        val label = "lbl-1"

        // 1. pending send row.
        val pending = message(
            bufferId, "hi there",
            sender = "me", serverTime = 500,
            dedupKey = "pending:$label",
            isSelf = true, pendingLabel = label,
        )
        val pendingId = dao.insertAll(listOf(pending)).single()
        assertEquals(true, pendingId > 0)

        // 2. echo arrives: look up by pending label, update in place with real msgid.
        val found = dao.byPendingLabel(bufferId, label)
        assertNotNull(found)
        val confirmed = found!!.copy(
            msgid = "real-msgid",
            serverTime = 600,
            dedupKey = "real-msgid",
            pendingLabel = null,
        )
        dao.update(confirmed)
        assertNull(dao.byPendingLabel(bufferId, label))

        // 3. Direct DAO writes deliberately bypass the alias store and therefore do not dedup.
        val history = message(
            bufferId, "hi there",
            sender = "me", serverTime = 600,
            dedupKey = "real-msgid", msgid = "real-msgid", isSelf = true,
        )
        val historyIds = dao.insertAll(listOf(history))
        assertEquals(true, historyIds.single() > 0)

        // EventProcessor/CanonicalTimelineStore is the only IRC-derived writer in production.
        val all = dao.pagingList(bufferId)
        assertEquals(2, all.size)
        assertEquals(setOf("real-msgid"), all.mapNotNull { it.msgid }.toSet())
        assertEquals(2, all.count { it.pendingLabel == null })
    }

    @Test
    fun findSelfEchoCandidate_pendingRow_matchesEvenOutsideTimeWindow() = runTest {
        val dao = db.messageDao()
        // A pending self-send stamped with the DEVICE clock (5_000_000), far from the server echo
        // time — the exact client/server clock-skew case that used to duplicate the message.
        dao.insertAll(listOf(
            message(bufferId, "skew msg", sender = "me", serverTime = 5_000_000L,
                dedupKey = "pending:x", isSelf = true, pendingLabel = "x"),
        ))
        // Window around a server echo time of 1000; the pending row's stamp is nowhere near it.
        val hit = dao.findSelfEchoCandidate(bufferId, "skew msg", 1000L - 30_000L, 1000L + 30_000L)
        assertNotNull(hit)                 // still matched, because it is pending
        assertEquals("x", hit!!.pendingLabel)
    }

    @Test
    fun findSelfEchoCandidate_confirmedRow_outsideWindow_doesNotMatch() = runTest {
        val dao = db.messageDao()
        // A confirmed self row (no pendingLabel) far outside the window must NOT be matched, so an
        // old identical self message is never collapsed into by a much later echo.
        dao.insertAll(listOf(
            message(bufferId, "old msg", sender = "me", serverTime = 5_000_000L,
                dedupKey = "k", msgid = "m", isSelf = true),
        ))
        val hit = dao.findSelfEchoCandidate(bufferId, "old msg", 1000L - 30_000L, 1000L + 30_000L)
        assertNull(hit)
    }

    @Test
    fun ftsRoundTrip_matchesTextAndSender_viaSanitizedQuery() = runTest {
        val dao = db.messageDao()
        dao.insertAll(
            listOf(
                message(bufferId, "the quick brown fox", sender = "alice", serverTime = 1, dedupKey = "d1"),
                message(bufferId, "lazy dog sleeps", sender = "bob", serverTime = 2, dedupKey = "d2"),
                // System event must be excluded from search (kind filter).
                message(bufferId, "quick join event", sender = "carol", serverTime = 3, dedupKey = "d3", kind = MessageKind.JOIN),
            )
        )

        // Prefix-token sanitizer output: "qui" -> "qui*"
        val hits = dao.search("qui*", null).first()
        assertEquals(1, hits.size)
        assertEquals("the quick brown fox", hits.single().message.text)
        assertEquals("#chan", hits.single().bufferDisplayName)
        assertEquals("libera", hits.single().networkName)

        // sender column is indexed too.
        val bySender = dao.search("bob*", null).first()
        assertEquals(1, bySender.size)
        assertEquals("lazy dog sleeps", bySender.single().message.text)
    }

    @Test
    fun firstUnreadOtherTime_anchorsOnOther_notOwnMessages() = runTest {
        val dao = db.messageDao()
        dao.insertAll(
            listOf(
                message(bufferId, "already read", serverTime = 900, dedupKey = "d0", msgid = "d0"),
                // Own message past the marker must be ignored (you have read what you sent).
                message(bufferId, "my line", serverTime = 1500, dedupKey = "d1", msgid = "d1", isSelf = true),
                // First real incoming message past the marker: the anchor.
                message(bufferId, "someone else", serverTime = 2000, dedupKey = "d2", msgid = "d2"),
                message(bufferId, "my reply", serverTime = 2500, dedupKey = "d3", msgid = "d3", isSelf = true),
            )
        )
        assertEquals(2000L, dao.firstUnreadOtherTime(bufferId, after = 1000L))
    }

    @Test
    fun firstUnreadOtherTime_onlyOwnMessagesPastMarker_returnsNull() = runTest {
        val dao = db.messageDao()
        dao.insertAll(
            listOf(
                message(bufferId, "my line", serverTime = 1500, dedupKey = "d1", msgid = "d1", isSelf = true),
                message(bufferId, "my other line", serverTime = 2000, dedupKey = "d2", msgid = "d2", isSelf = true),
            )
        )
        // The reported bug: with only your own messages past the marker, there is no unread anchor.
        assertNull(dao.firstUnreadOtherTime(bufferId, after = 1000L))
    }
}

// Test-only helper to drain the PagingSource into a list without Paging machinery.
private suspend fun MessageDao.pagingList(bufferId: Long): List<MessageEntity> {
    val source = pagingSource(bufferId)
    val result = source.load(
        androidx.paging.PagingSource.LoadParams.Refresh(
            key = null,
            loadSize = 100,
            placeholdersEnabled = false,
        )
    )
    return (result as androidx.paging.PagingSource.LoadResult.Page).data
}
