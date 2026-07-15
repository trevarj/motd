package io.github.trevarj.motd.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TypedDaoProjectionTest {
    private lateinit var db: MotdDatabase
    private var networkId = 0L

    @Before
    fun setUp() = runTest {
        db = inMemoryDb()
        networkId = db.networkDao().insert(network())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `history and target projections preserve fixed-query semantics`() = runTest {
        val channel = db.bufferDao().insert(buffer(networkId, "#motd"))
        val server = db.bufferDao().insert(buffer(networkId, "*", BufferType.SERVER))
        db.messageDao().insertAll(
            listOf(
                message(channel, "first", serverTime = 10, dedupKey = "first", msgid = "m1"),
                message(channel, "joined", serverTime = 20, dedupKey = "join", kind = MessageKind.JOIN),
            ),
        )

        assertEquals(MessageBoundaryRow(null, 20), db.messageDao().latestBoundary(channel))
        assertEquals(2, db.messageDao().countForBuffer(channel))
        assertTrue(db.messageDao().hasStoredChat(channel))
        assertFalse(db.messageDao().hasStoredChat(server))
        assertEquals(channel, db.bufferDao().idForTarget(networkId, "#MOTD"))
        assertEquals(listOf(BufferTargetRow(channel, "#motd")), db.bufferDao().openTargets(networkId))
    }

    @Test
    fun `bouncer transcript projection retains newest hundred in display order input`() = runTest {
        val transcript = db.bufferDao().insert(buffer(networkId, "BouncerServ", BufferType.SERVER))
        db.messageDao().insertAll(
            listOf(
                message(transcript, "older", serverTime = 10, dedupKey = "old", sender = "BouncerServ"),
                message(transcript, "newer", serverTime = 20, dedupKey = "new", sender = "me", isSelf = true),
            ),
        )

        assertEquals(
            listOf(
                BouncerTranscriptRow("me", "newer", 20, true),
                BouncerTranscriptRow("BouncerServ", "older", 10, false),
            ),
            db.messageDao().observeBouncerTranscript(networkId).first(),
        )
    }
}
