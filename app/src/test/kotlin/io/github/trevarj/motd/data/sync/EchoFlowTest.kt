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
    private class RecordingNotifier : MessageNotifier {
        val incoming = mutableListOf<IrcEvent.ChatMessage>()

        override suspend fun onIncoming(
            networkId: Long,
            bufferId: Long,
            type: BufferType,
            hasMention: Boolean,
            message: IrcEvent.ChatMessage,
        ) {
            incoming += message
        }
    }

    private lateinit var db: MotdDatabase
    private lateinit var processor: EventProcessor
    private lateinit var notifier: RecordingNotifier
    private var networkId = 0L
    private var bufferId = 0L

    @Before fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java).allowMainThreadQueries().build()
        notifier = RecordingNotifier()
        processor = EventProcessor(db, TypingTrackerImpl(), notifier)
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
    fun pushWithMsgid_thenLiveWithoutMsgid_reusesDurableRow() = runTest {
        val push = IrcEvent.ChatMessage(
            ctx = MessageContext(
                msgid = "push-a",
                serverTime = 600_000,
                account = null,
                batchId = null,
                label = null,
            ),
            kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"),
            target = "#chan",
            text = "notification then reconnect",
            isSelf = false,
            replyToMsgid = null,
        )
        processor.processPush(networkId, push)

        processor.process(
            networkId,
            push.copy(
                ctx = push.ctx.copy(msgid = null, serverTime = 600_750),
            ),
        )

        assertEquals(1, rows().size)
        assertEquals("push-a", rows().single().msgid)
        assertEquals(600_000, rows().single().serverTime)
    }

    @Test
    fun pushWithMsgid_thenHistoryWithoutMsgid_reusesDurableRow() = runTest {
        val push = IrcEvent.ChatMessage(
            ctx = MessageContext(
                msgid = "push-history-a",
                serverTime = 600_000,
                account = null,
                batchId = null,
                label = null,
            ),
            kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"),
            target = "#chan",
            text = "me: notification then msgidless history",
            isSelf = false,
            replyToMsgid = null,
        )
        processor.processPush(networkId, push)

        processor.process(
            networkId,
            IrcEvent.HistoryBatch(
                "#chan",
                listOf(push.copy(ctx = push.ctx.copy(msgid = null, batchId = "history"))),
            ),
        )

        assertEquals(1, rows().size)
        assertEquals("push-history-a", rows().single().msgid)
        assertEquals(1, notifier.incoming.size)
    }

    @Test
    fun historyWithMsgid_thenDelayedPushWithoutMsgid_keepsOnlyDurableRow() = runTest {
        val history = IrcEvent.ChatMessage(
            ctx = MessageContext(
                msgid = "soju-history-a",
                serverTime = 600_000,
                account = "alice",
                batchId = "history",
                label = null,
            ),
            kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"),
            target = "#chan",
            text = "me: delayed notification",
            isSelf = false,
            replyToMsgid = null,
        )
        processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(history)))
        val durableId = rows().single().id

        // Soju can deliver its stored, msgid-bearing CHATHISTORY representation before the
        // delayed WebPush payload. A msgid-less push is notification-only and must not touch Room.
        processor.processPush(
            networkId,
            history.copy(
                ctx = history.ctx.copy(msgid = null, batchId = null),
            ),
        )

        assertEquals(1, rows().size)
        assertEquals(durableId, rows().single().id)
        assertEquals("soju-history-a", rows().single().msgid)
        assertEquals(1, notifier.incoming.size)
        assertNull(notifier.incoming.single().ctx.msgid)
        assertEquals(600_000, notifier.incoming.single().ctx.serverTime)
    }

    @Test
    fun msgidlessLiveDelivery_stillPersists() = runTest {
        processor.process(
            networkId,
            IrcEvent.ChatMessage(
                ctx = MessageContext(null, 650_000, null, null, null),
                kind = IrcEvent.ChatKind.PRIVMSG,
                source = Prefix("alice"),
                target = "#chan",
                text = "ordinary live IRC line",
                isSelf = false,
                replyToMsgid = null,
            ),
        )

        val row = rows().single()
        assertNull(row.msgid)
        assertEquals("ordinary live IRC line", row.text)
    }

    @Test
    fun historyWithMsgid_thenLiveWithoutMsgid_reusesRowAcrossOptionalTagDifferences() = runTest {
        val history = IrcEvent.ChatMessage(
            ctx = MessageContext("soju-history-b", 700_000, "alice", "history", null),
            kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"),
            target = "#chan",
            text = "same transport delivery",
            isSelf = false,
            replyToMsgid = "parent-msgid",
        )
        processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(history)))

        processor.process(
            networkId,
            history.copy(
                ctx = history.ctx.copy(msgid = null, account = null, batchId = null),
                replyToMsgid = null,
            ),
        )

        val row = rows().single()
        assertEquals("soju-history-b", row.msgid)
        assertEquals("alice", row.senderAccount)
        assertEquals("parent-msgid", row.replyToMsgid)
    }

    @Test
    fun liveWithoutMsgid_doesNotMergeAmbiguousRepeatedMessages() = runTest {
        fun durable(msgid: String, serverTime: Long) = IrcEvent.ChatMessage(
            ctx = MessageContext(msgid, serverTime, null, null, null),
            kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"),
            target = "#chan",
            text = "same",
            isSelf = false,
            replyToMsgid = null,
        )
        processor.processPush(networkId, durable("push-a", 600_000))
        processor.processPush(networkId, durable("push-b", 601_000))

        processor.process(
            networkId,
            durable("unused", 600_750).copy(
                ctx = MessageContext(null, 600_750, null, null, null),
            ),
        )

        assertEquals(3, rows().size)
        assertEquals(1, rows().count { it.msgid == null })
    }

    @Test
    fun liveWithoutMsgid_doesNotMergeSameTextOutsideDeliveryWindow() = runTest {
        val push = IrcEvent.ChatMessage(
            ctx = MessageContext("push-a", 600_000, null, null, null),
            kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"),
            target = "#chan",
            text = "same",
            isSelf = false,
            replyToMsgid = null,
        )
        processor.processPush(networkId, push)
        processor.process(
            networkId,
            push.copy(ctx = MessageContext(null, 603_000, null, null, null)),
        )

        assertEquals(2, rows().size)
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
