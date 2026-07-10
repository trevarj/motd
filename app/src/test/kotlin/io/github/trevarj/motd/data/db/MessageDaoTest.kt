package io.github.trevarj.motd.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
    fun dedupUniqueness_sameDedupKeyInsertedTwice_yieldsOneRow() = runTest {
        val dao = db.messageDao()
        val a = message(bufferId, "hello", serverTime = 1000, dedupKey = "msg-1", msgid = "msg-1")
        val b = message(bufferId, "hello again", serverTime = 2000, dedupKey = "msg-1", msgid = "msg-1")

        val firstIds = dao.insertAll(listOf(a))
        val secondIds = dao.insertAll(listOf(b))

        assertEquals(1, firstIds.size)
        // IGNORE conflict → rowid -1 for the skipped duplicate.
        assertEquals(-1L, secondIds.single())
        assertEquals(1000L, dao.newestTime(bufferId))
    }

    @Test
    fun msgidUniqueness_sameMsgidDifferentDedupKey_yieldsOneRow() = runTest {
        // The gap the old UNIQUE(bufferId, dedupKey) index alone missed: a self row confirmed by a
        // BARE echo keeps a LOCAL dedupKey (sha1/pending) while its msgid stays null; a CHATHISTORY
        // replay then arrives with the real msgid AS its dedupKey — a different dedupKey, so the old
        // index would NOT reject it. The new UNIQUE(bufferId, msgid) index rejects it by msgid.
        val dao = db.messageDao()
        val first = message(bufferId, "hi", serverTime = 1000, dedupKey = "srv-9", msgid = "srv-9", isSelf = true)
        // A second row: same msgid, DIFFERENT dedupKey and time (a distinct local key).
        val second = message(bufferId, "hi", serverTime = 2000, dedupKey = "local-sha1", msgid = "srv-9", isSelf = true)

        assertEquals(true, dao.insertAll(listOf(first)).single() > 0)
        assertEquals(-1L, dao.insertAll(listOf(second)).single()) // IGNORE on the msgid index
        assertEquals(1000L, dao.newestTime(bufferId)) // only the first row survives
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
    fun echoFlow_pendingInsertThenUpdateInPlace_thenHistoryOverlapIgnored_oneRow() = runTest {
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

        // 3. same msgid arrives later via CHATHISTORY → INSERT IGNORE no-ops.
        val history = message(
            bufferId, "hi there",
            sender = "me", serverTime = 600,
            dedupKey = "real-msgid", msgid = "real-msgid", isSelf = true,
        )
        val historyIds = dao.insertAll(listOf(history))
        assertEquals(-1L, historyIds.single())

        // Exactly one row survives.
        val all = dao.pagingList(bufferId)
        assertEquals(1, all.size)
        assertEquals("real-msgid", all.single().msgid)
        assertNull(all.single().pendingLabel)
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
