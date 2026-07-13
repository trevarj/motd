package io.github.trevarj.motd.data.visibility

import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.FoolsMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageVisibilityReaderTest {
    private lateinit var db: MotdDatabase
    private lateinit var reader: MessageVisibilityReader
    private var bufferId = 0L

    @Before
    fun setUp() = runTest {
        db = inMemoryDb()
        reader = MessageVisibilityReader(db)
        val networkId = db.networkDao().insert(network())
        bufferId = db.bufferDao().insert(buffer(networkId, "#test", readMarkerTime = 50))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun chatListFallsBackPastFoolAndExcludesFoolUnreadAndMention() = runTest {
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
    fun timelineIndexIncludesCollapsedFoolButNotHiddenFoolOrHiddenJoin() = runTest {
        val ids = db.messageDao().insertAll(
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
                spec(FoolsMode.COLLAPSE, showJoinPartQuit = false),
            ),
        )
        assertEquals(
            0,
            reader.countTimelineNewer(
                bufferId,
                100,
                ids[0],
                spec(FoolsMode.HIDE, showJoinPartQuit = false),
            ),
        )
    }

    @Test
    fun savedAnchorAndUnreadSkipFoolRowsAndRawTailStillAdvances() = runTest {
        val ids = db.messageDao().insertAll(
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
        val spec = spec(FoolsMode.COLLAPSE, showJoinPartQuit = false)
        val anchor = reader.resolveSavedAnchor(bufferId, null, 200, ids[1], spec)

        assertEquals(ids[0], anchor?.id)
        assertEquals(100L, reader.firstVisibleUnreadTime(bufferId, 50, spec))
        assertEquals(300L, reader.latestRawTime(bufferId))
    }

    @Test
    fun foolOnlyBufferHasNoAnchorOrVisibleUnread() = runTest {
        val id = db.messageDao().insertAll(
            listOf(message(bufferId, "fool", sender = "alice", serverTime = 100, dedupKey = "fool")),
        ).single()
        val spec = spec(FoolsMode.COLLAPSE)
        val resolved = reader.resolveChatList(db.bufferDao().observeChatList().first(), spec).single()

        assertNull(reader.resolveSavedAnchor(bufferId, null, 100, id, spec))
        assertNull(reader.firstVisibleUnreadTime(bufferId, 50, spec))
        assertNull(resolved.lastMessageText)
        assertNull(resolved.lastMessageTime)
        assertEquals(0, resolved.unreadCount)
        assertEquals(0, resolved.mentionCount)
    }

    @Test
    fun previewFallbackTraversesBoundedPagesWithoutEmbeddingFoolSetInSql() = runTest {
        db.messageDao().insertAll(
            buildList {
                add(message(bufferId, "meaningful", sender = "bob", serverTime = 1, dedupKey = "good"))
                repeat(300) { index ->
                    add(
                        message(
                            bufferId,
                            "fool-$index",
                            sender = "alice",
                            serverTime = 2L + index,
                            dedupKey = "fool-$index",
                        ),
                    )
                }
            },
        )

        val raw = db.bufferDao().observeChatList().first()
        val resolved = reader.resolveChatList(raw, spec(FoolsMode.HIDE)).single()
        assertEquals("meaningful", resolved.lastMessageText)
    }

    private fun spec(mode: FoolsMode, showJoinPartQuit: Boolean = true) = MessageVisibilitySpec(
        showJoinPartQuit = showJoinPartQuit,
        fools = setOf("alice"),
        foolsMode = mode,
    )
}
