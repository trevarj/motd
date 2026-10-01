package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.EventRedirectEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ComposerDraftStoreTest {
    private lateinit var db: MotdDatabase
    private lateinit var store: ComposerDraftStore
    private var roomId = 0L

    @Before
    fun setUp() =
        runTest {
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
    fun `every push announces its buffer so an already-open chat can drain it`() =
        runTest {
            val seen = mutableListOf<Long>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                store.prefillPushes.collect { seen += it }
            }
            runCurrent()

            store.push(roomId, "alice: ")
            store.push(roomId + 1, "bob: ")
            runCurrent()

            assertEquals(listOf(roomId, roomId + 1), seen)
        }

    @Test
    fun `a push nobody was listening for still leaves the prefill queued`() =
        runTest {
            store.push(roomId, "alice: ")

            val seen = mutableListOf<Long>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                store.prefillPushes.collect { seen += it }
            }
            runCurrent()

            // No replay: the announcement is gone, but the text it announced is not.
            assertTrue(seen.isEmpty())
            assertEquals("alice: ", store.consume(roomId))
        }

    @Test
    fun `draft text and reply survive store recreation`() =
        runTest {
            store.saveDraft(roomId, "hello", replyToEventId = 77L)

            val restored = ComposerDraftStore(db).loadDraft(roomId)

            assertEquals("hello", restored?.text)
            assertEquals(77L, restored?.replyToEventId)
        }

    @Test
    fun `accepted version clears only while unchanged`() =
        runTest {
            val submitted = store.saveDraft(roomId, "first", replyToEventId = 7L)!!
            store.saveDraft(roomId, "new text", replyToEventId = 7L)

            assertFalse(store.clearIfUnchanged(submitted))
            assertEquals("new text", store.loadDraft(roomId)?.text)

            val latest = store.loadDraft(roomId)!!
            assertTrue(store.clearIfUnchanged(latest))
            assertNull(store.loadDraft(roomId))
        }

    @Test
    fun `reply-only draft is durable and blank without reply removes row`() =
        runTest {
            store.saveDraft(roomId, "", replyToEventId = 9L)
            assertEquals(9L, store.loadDraft(roomId)?.replyToEventId)

            store.saveDraft(roomId, "", replyToEventId = null)
            assertNull(store.loadDraft(roomId))
        }

    @Test
    fun `reply deletion does not delete draft`() =
        runTest {
            val eventId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(roomId, "reply", serverTime = 100, dedupKey = "reply-delete")),
                    ).single()
            store.saveDraft(roomId, "keep me", eventId)

            db.messageDao().deleteWithAnchorFallback(eventId)

            assertEquals("keep me", store.loadDraft(roomId)?.text)
            assertEquals(eventId, store.loadDraft(roomId)?.replyToEventId)
        }

    @Test
    fun `unchanged submitted draft clears after reply coalesces`() =
        runTest {
            val loserId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(roomId, "reply", serverTime = 100, dedupKey = "reply-loser")),
                    ).single()
            val winnerId =
                db
                    .messageDao()
                    .insertAll(
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
    fun `notification reply append retains text and canonical reply identity`() =
        runTest {
            val (loserId, winnerId) =
                db.messageDao().insertAll(
                    listOf(
                        message(roomId, "reply", serverTime = 100, dedupKey = "notification-reply-loser"),
                        message(roomId, "reply", serverTime = 200, dedupKey = "notification-reply-winner"),
                    ),
                )
            store.saveDraft(roomId, "typing", replyToEventId = loserId)
            db.canonicalTimelineDao().upsertEventRedirect(EventRedirectEntity(loserId, winnerId))

            val appended = store.appendNotificationReply(roomId, "voice reply")!!

            assertEquals("typing\nvoice reply", appended.text)
            assertEquals(winnerId, appended.replyToEventId)
            assertEquals(appended, ComposerDraftStore(db).loadDraft(roomId))
        }

    @Test
    fun `notification reply replaces blank draft without dropping its reply target`() =
        runTest {
            assertEquals("first", store.appendNotificationReply(roomId, "first")?.text)
            store.saveDraft(roomId, "   ", replyToEventId = 9L)

            val appended = store.appendNotificationReply(roomId, "second")!!

            assertEquals("second", appended.text)
            assertEquals(9L, appended.replyToEventId)
        }

    @Test
    fun `notification retry removes only exact or trailing text and retains reply-only drafts`() =
        runTest {
            store.appendNotificationReply(roomId, "reply")
            assertTrue(store.removeNotificationReply(roomId, "reply"))
            assertNull(store.loadDraft(roomId))
            assertFalse(store.removeNotificationReply(roomId, "reply"))

            store.saveDraft(roomId, "typing", replyToEventId = 9L)
            store.appendNotificationReply(roomId, "reply")
            assertTrue(store.removeNotificationReply(roomId, "reply"))
            assertEquals("typing", store.loadDraft(roomId)?.text)
            assertEquals(9L, store.loadDraft(roomId)?.replyToEventId)

            store.saveDraft(roomId, "reply", replyToEventId = 9L)
            assertTrue(store.removeNotificationReply(roomId, "reply"))
            assertEquals("", store.loadDraft(roomId)?.text)
            assertEquals(9L, store.loadDraft(roomId)?.replyToEventId)
        }

    @Test
    fun `notification retry leaves edited or nontrailing copies untouched`() =
        runTest {
            for (text in listOf("typing\nreply and more", "reply\ntyping", "replacement")) {
                val edited = store.saveDraft(roomId, text, replyToEventId = 9L)

                assertFalse(store.removeNotificationReply(roomId, "reply"))
                assertEquals(edited, store.loadDraft(roomId))
            }
        }

    @Test
    fun `notification reply mutations resolve redirected room and preserve winner reply`() =
        runTest {
            val networkId = db.bufferDao().rawById(roomId)!!.networkId
            val winner = db.bufferDao().insert(buffer(networkId, "#winner"))
            store.saveDraft(winner, "winner draft", replyToEventId = 9L)
            db.roomAliasDao().markRedirect(roomId, winner)

            assertEquals(winner, store.appendNotificationReply(roomId, "reply")?.roomId)
            assertEquals("winner draft\nreply", store.loadDraft(winner)?.text)
            assertTrue(store.removeNotificationReply(roomId, "reply"))
            assertEquals("winner draft", store.loadDraft(winner)?.text)
            assertEquals(9L, store.loadDraft(winner)?.replyToEventId)
            assertNull(db.composerDraftDao().byRoom(roomId))
        }

    @Test
    fun `notification replies cannot recreate missing or dismissed room drafts`() =
        runTest {
            val networkId = db.bufferDao().rawById(roomId)!!.networkId
            val query = db.bufferDao().insert(buffer(networkId, "alice", BufferType.QUERY))
            store.appendNotificationReply(query, "reply")
            db.bufferDao().deleteBuffer(query)
            db.bufferDao().deleteBuffer(roomId)

            for (id in listOf(roomId, query, 4_242L)) {
                assertNull(store.appendNotificationReply(id, "reply"))
                assertFalse(store.removeNotificationReply(id, "reply"))
                assertNull(store.loadDraft(id))
            }
            assertTrue(db.bufferDao().rawById(query)!!.dismissed)
        }

    @Test
    fun `overlapping notification appends retain both replies and composer identity`() =
        runTest {
            store.saveDraft(roomId, "typing", replyToEventId = 9L)
            val start = CompletableDeferred<Unit>()
            val first =
                async(Dispatchers.Default) {
                    start.await()
                    store.appendNotificationReply(roomId, "first")
                }
            val second =
                async(Dispatchers.Default) {
                    start.await()
                    ComposerDraftStore(db).appendNotificationReply(roomId, "second")
                }
            start.complete(Unit)
            first.await()
            second.await()

            val final = store.loadDraft(roomId)!!
            assertTrue(final.text in setOf("typing\nfirst\nsecond", "typing\nsecond\nfirst"))
            assertEquals(9L, final.replyToEventId)
        }

    @Test
    fun `overlapping append and retry removal never lose new reply or composer text`() =
        runTest {
            store.saveDraft(roomId, "typing\nretried", replyToEventId = 9L)
            val start = CompletableDeferred<Unit>()
            val appended =
                async(Dispatchers.Default) {
                    start.await()
                    store.appendNotificationReply(roomId, "new reply")
                }
            val removed =
                async(Dispatchers.Default) {
                    start.await()
                    ComposerDraftStore(db).removeNotificationReply(roomId, "retried")
                }
            start.complete(Unit)
            appended.await()
            val didRemove = removed.await()

            assertEquals(
                if (didRemove) "typing\nnew reply" else "typing\nretried\nnew reply",
                store.loadDraft(roomId)?.text,
            )
            assertEquals(9L, store.loadDraft(roomId)?.replyToEventId)
        }

    @Test
    fun `stale save cannot recreate draft in dismissed query`() =
        runTest {
            val networkId = db.bufferDao().rawById(roomId)!!.networkId
            val queryId = db.bufferDao().insert(buffer(networkId, "alice", BufferType.QUERY))
            store.saveDraft(queryId, "before delete", replyToEventId = null)
            db.bufferDao().deleteBuffer(queryId)

            assertNull(store.saveDraft(queryId, "stale edit", replyToEventId = null))
            assertNull(store.loadDraft(queryId))
        }
}
