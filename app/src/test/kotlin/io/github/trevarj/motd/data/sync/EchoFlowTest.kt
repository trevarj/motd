package io.github.trevarj.motd.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EchoFlowTest {
    private lateinit var db: MotdDatabase
    private lateinit var processor: EventProcessor
    private var networkId = 0L
    private var bufferId = 0L

    @Before fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java).allowMainThreadQueries().build()
        processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
        networkId = db.networkDao().insert(
            NetworkEntity(name = "libera", role = NetworkRole.DIRECT, host = "h", port = 6697, nick = "me", username = "me", realname = "Me"),
        )
        processor.onRegistered(networkId, "me", emptyMap())
        bufferId = db.bufferDao().insert(BufferEntity(networkId = networkId, name = "#chan", displayName = "#chan", type = BufferType.CHANNEL))
    }

    @After fun tearDown() { db.close() }

    private suspend fun rows() =
        db.messageDao().pagingSource(bufferId).load(
            androidx.paging.PagingSource.LoadParams.Refresh(null, 100, false),
        ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data }

    @Test
    fun send_thenEcho_thenHistoryOverlap_yieldsExactlyOneRow() = runTest {
        val label = "lbl-9"
        // 1. pending send (ConnectionManagerImpl delegates to this insert path).
        val id = processor.insertPending(bufferId, label, sender = "me", text = "hi there", replyToMsgid = null, kind = MessageKind.PRIVMSG)
        assert(id > 0)
        assertEquals(1, rows().size)
        assertEquals(label, rows().single().pendingLabel)

        // 2. labeled echo arrives → update in place.
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = MessageContext(msgid = "real-1", serverTime = 600, account = null, batchId = null, label = label),
            kind = IrcEvent.ChatKind.PRIVMSG, source = Prefix("me"), target = "#chan",
            text = "hi there", isSelf = true, replyToMsgid = null,
        ))
        assertNull(db.messageDao().byPendingLabel(bufferId, label))
        assertEquals(1, rows().size)
        assertEquals("real-1", rows().single().msgid)
        assertNull(rows().single().pendingLabel)

        // 3. same msgid via CHATHISTORY later → INSERT IGNORE no-op.
        processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(
            IrcEvent.ChatMessage(
                ctx = MessageContext(msgid = "real-1", serverTime = 600, account = null, batchId = "b", label = null),
                kind = IrcEvent.ChatKind.PRIVMSG, source = Prefix("me"), target = "#chan",
                text = "hi there", isSelf = true, replyToMsgid = null,
            ),
        )))
        assertEquals(1, rows().size)
    }

    @Test
    fun liveWithoutMsgid_thenHistoryWithMsgid_promotesBoundaryRow() = runTest {
        val live = IrcEvent.ChatMessage(
            ctx = MessageContext(msgid = null, serverTime = 600, account = null, batchId = null, label = null),
            kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"),
            target = "#chan",
            text = "A",
            isSelf = false,
            replyToMsgid = null,
        )
        processor.process(networkId, live)

        processor.process(
            networkId,
            IrcEvent.HistoryBatch(
                "#chan",
                listOf(
                    live.copy(
                        ctx = live.ctx.copy(msgid = "history-a", batchId = "history"),
                    ),
                ),
            ),
        )

        assertEquals(1, rows().size)
        assertEquals("history-a", rows().single().msgid)
    }

    @Test
    fun historyMsgidPromotion_doesNotMergeSameTextAtDifferentTimes() = runTest {
        processor.process(
            networkId,
            IrcEvent.ChatMessage(
                ctx = MessageContext(null, 600, null, null, null),
                kind = IrcEvent.ChatKind.PRIVMSG,
                source = Prefix("alice"),
                target = "#chan",
                text = "same",
                isSelf = false,
                replyToMsgid = null,
            ),
        )
        processor.process(
            networkId,
            IrcEvent.HistoryBatch(
                "#chan",
                listOf(
                    IrcEvent.ChatMessage(
                        ctx = MessageContext("history-later", 601, null, "history", null),
                        kind = IrcEvent.ChatKind.PRIVMSG,
                        source = Prefix("alice"),
                        target = "#chan",
                        text = "same",
                        isSelf = false,
                        replyToMsgid = null,
                    ),
                ),
            ),
        )

        assertEquals(2, rows().size)
    }

    @Test
    fun echoTimeout_marksPendingRowFailed() = runTest {
        val label = "lbl-timeout"
        processor.insertPending(bufferId, label, "me", "no echo", null, MessageKind.PRIVMSG)
        processor.failIfStillPending(bufferId, label)
        val row = rows().single()
        assertEquals(true, row.failed)
        assertEquals(label, row.pendingLabel)
    }

    @Test
    fun echoConfirmation_convergesAfterTimeoutInEitherOrder() = runTest {
        suspend fun echo(label: String, msgid: String, text: String) {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = MessageContext(msgid, 700, null, null, label),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#chan",
                    text = text,
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )
        }

        processor.insertPending(bufferId, "timeout-first", "me", "one", null, MessageKind.PRIVMSG)
        processor.failIfStillPending(bufferId, "timeout-first")
        echo("timeout-first", "confirmed-one", "one")

        processor.insertPending(bufferId, "echo-first", "me", "two", null, MessageKind.PRIVMSG)
        echo("echo-first", "confirmed-two", "two")
        processor.failIfStillPending(bufferId, "echo-first")

        val confirmed = rows().filter { it.msgid in setOf("confirmed-one", "confirmed-two") }
        assertEquals(2, confirmed.size)
        assertEquals(setOf("one", "two"), confirmed.map { it.text }.toSet())
        confirmed.forEach {
            assertNull(it.pendingLabel)
            assertEquals(false, it.failed)
        }
    }
}
