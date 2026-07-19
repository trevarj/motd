package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.EventRedirectEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
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
class ComposerDraftStoreTest {
    private lateinit var db: MotdDatabase
    private lateinit var store: ComposerDraftStore
    private var roomId = 0L

    @Before
    fun setUp() = runTest {
        db = inMemoryDb()
        val networkId = db.networkDao().insert(network())
        roomId = db.bufferDao().insert(buffer(networkId, "#room", BufferType.CHANNEL))
        store = ComposerDraftStore(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `prefills remain consume once`() {
        store.push(roomId, "alice: ")
        store.push(roomId, "bob: ")
        assertEquals("alice: bob: ", store.consume(roomId))
        assertNull(store.consume(roomId))
    }

    @Test
    fun `draft text and reply survive store recreation`() = runTest {
        store.saveDraft(roomId, "hello", replyToEventId = 77L)

        val restored = ComposerDraftStore(db).loadDraft(roomId)

        assertEquals("hello", restored?.text)
        assertEquals(77L, restored?.replyToEventId)
    }

    @Test
    fun `accepted version clears only while unchanged`() = runTest {
        val submitted = store.saveDraft(roomId, "first", replyToEventId = 7L)!!
        store.saveDraft(roomId, "new text", replyToEventId = 7L)

        assertFalse(store.clearIfUnchanged(submitted))
        assertEquals("new text", store.loadDraft(roomId)?.text)

        val latest = store.loadDraft(roomId)!!
        assertTrue(store.clearIfUnchanged(latest))
        assertNull(store.loadDraft(roomId))
    }

    @Test
    fun `reply-only draft is durable and blank without reply removes row`() = runTest {
        store.saveDraft(roomId, "", replyToEventId = 9L)
        assertEquals(9L, store.loadDraft(roomId)?.replyToEventId)

        store.saveDraft(roomId, "", replyToEventId = null)
        assertNull(store.loadDraft(roomId))
    }

    @Test
    fun `reply deletion does not delete draft`() = runTest {
        val eventId = db.messageDao().insertAll(
            listOf(message(roomId, "reply", serverTime = 100, dedupKey = "reply-delete")),
        ).single()
        store.saveDraft(roomId, "keep me", eventId)

        db.messageDao().deleteWithAnchorFallback(eventId)

        assertEquals("keep me", store.loadDraft(roomId)?.text)
        assertEquals(eventId, store.loadDraft(roomId)?.replyToEventId)
    }

    @Test
    fun `unchanged submitted draft clears after reply coalesces`() = runTest {
        val loserId = db.messageDao().insertAll(
            listOf(message(roomId, "reply", serverTime = 100, dedupKey = "reply-loser")),
        ).single()
        val winnerId = db.messageDao().insertAll(
            listOf(message(roomId, "reply", serverTime = 200, dedupKey = "reply-winner")),
        ).single()
        val submitted = store.saveDraft(roomId, "answer", loserId)!!
        db.canonicalTimelineDao().upsertEventRedirect(EventRedirectEntity(loserId, winnerId))
        db.composerDraftDao().repointReplies(loserId, winnerId)
        db.messageDao().deleteById(loserId)

        assertTrue(store.clearIfUnchanged(submitted))
        assertNull(store.loadDraft(roomId))
    }

    @Test
    fun `stale save cannot recreate draft in dismissed query`() = runTest {
        val networkId = db.bufferDao().rawById(roomId)!!.networkId
        val queryId = db.bufferDao().insert(buffer(networkId, "alice", BufferType.QUERY))
        store.saveDraft(queryId, "before delete", replyToEventId = null)
        db.bufferDao().deleteBuffer(queryId)

        assertNull(store.saveDraft(queryId, "stale edit", replyToEventId = null))
        assertNull(store.loadDraft(queryId))
    }
}
