package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// End-to-end: repository sanitizes raw input and drives the FTS DAO, honoring the kind filter
// and buffer scoping.
@RunWith(RobolectricTestRunner::class)
class SearchRepositoryTest {
    private lateinit var db: MotdDatabase
    private lateinit var repo: SearchRepositoryImpl
    private var b1: Long = 0
    private var b2: Long = 0

    @Before
    fun setUp() = runTest {
        db = inMemoryDb()
        repo = SearchRepositoryImpl(db.messageDao())
        val nid = db.networkDao().insert(network())
        b1 = db.bufferDao().insert(buffer(nid, "#one"))
        b2 = db.bufferDao().insert(buffer(nid, "#two"))
        db.messageDao().insertAll(
            listOf(
                message(b1, "hello world", serverTime = 1, dedupKey = "a"),
                message(b2, "hello there", serverTime = 2, dedupKey = "b"),
                message(b1, "hello join", serverTime = 3, dedupKey = "c", kind = MessageKind.JOIN),
            )
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun rawPrefixInput_matchesAcrossBuffers_excludesSystemKinds() = runTest {
        val hits = repo.search("hel", null).first()
        // JOIN row excluded → only the two chat messages match.
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.message.kind == MessageKind.PRIVMSG })
    }

    @Test
    fun bufferScopedSearch_restrictsResults() = runTest {
        val hits = repo.search("hello", b2).first()
        assertEquals(1, hits.size)
        assertEquals("hello there", hits.single().message.text)
    }

    @Test
    fun operatorOnlyInput_returnsEmptyWithoutTouchingDb() = runTest {
        assertEquals(0, repo.search("***", null).first().size)
    }
}
