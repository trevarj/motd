package io.github.trevarj.motd.data.visibility

import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.EventRedirectEntity
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.FoolsMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
        assertEquals(
            100L,
            reader.firstVisibleUnreadAnchor(
                bufferId,
                io.github.trevarj.motd.data.db.TimelineAnchor(50, 0),
                spec,
            )?.serverTime,
        )
        assertEquals(
            io.github.trevarj.motd.data.db.TimelineAnchor(300L, ids[2]),
            reader.latestRawAnchor(bufferId),
        )
    }

    @Test
    fun rawTailObserverFollowsRedirectChangedWithoutMessageInvalidation() = runTest {
        val networkId = db.bufferDao().observeById(bufferId)!!.networkId
        val winnerId = db.bufferDao().insert(buffer(networkId, "#winner"))
        val eventId = db.messageDao().insertAll(
            listOf(message(winnerId, "winner tail", serverTime = 700, dedupKey = "winner-tail")),
        ).single()
        val redirected = async(start = CoroutineStart.UNDISPATCHED) {
            reader.observeLatestRawAnchor(bufferId).first { it?.eventId == eventId }
        }

        db.roomAliasDao().markRedirect(bufferId, winnerId)

        assertEquals(
            io.github.trevarj.motd.data.db.TimelineAnchor(700, eventId),
            withTimeout(5_000) { redirected.await() },
        )
    }

    @Test
    fun msgidlessSavedAnchorFollowsRedirectAndCorrectedTimestamp() = runTest {
        val winnerId = db.messageDao().insertAll(
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
        val loserId = db.messageDao().insertAll(
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

        val anchor = reader.resolveSavedAnchor(
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
    fun foolOnlyBufferHasNoAnchorOrVisibleUnread() = runTest {
        val id = db.messageDao().insertAll(
            listOf(message(bufferId, "fool", sender = "alice", serverTime = 100, dedupKey = "fool")),
        ).single()
        val spec = spec(FoolsMode.COLLAPSE)
        val resolved = reader.resolveChatList(db.bufferDao().observeChatList().first(), spec).single()

        assertNull(reader.resolveSavedAnchor(bufferId, null, 100, id, spec))
        assertNull(
            reader.firstVisibleUnreadAnchor(
                bufferId,
                io.github.trevarj.motd.data.db.TimelineAnchor(50, 0),
                spec,
            ),
        )
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
