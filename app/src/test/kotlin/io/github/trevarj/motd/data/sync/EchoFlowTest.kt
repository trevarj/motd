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
import io.github.trevarj.motd.irc.event.ServerTimeSource
import io.github.trevarj.motd.irc.proto.Prefix
import io.github.trevarj.motd.service.outgoingClientTags
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Before fun setUp() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java).allowMainThreadQueries().build()
            notifier = RecordingNotifier()
            processor = EventProcessor(db, TypingTrackerImpl(), notifier)
            networkId =
                db.networkDao().insert(
                    NetworkEntity(name = "libera", role = NetworkRole.DIRECT, host = "h", port = 6697, nick = "me", username = "me", realname = "Me"),
                )
            processor.onRegistered(networkId, "me", emptyMap())
            bufferId = db.bufferDao().insert(BufferEntity(networkId = networkId, name = "#chan", displayName = "#chan", type = BufferType.CHANNEL))
        }

    @After fun tearDown() {
        db.close()
    }

    private suspend fun rows(roomId: Long = bufferId) =
        db
            .messageDao()
            .pagingSource(roomId)
            .load(
                androidx.paging.PagingSource.LoadParams
                    .Refresh(null, 100, false),
            ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data }

    @Test
    fun send_thenEcho_thenHistoryOverlap_yieldsExactlyOneRow() =
        runTest {
            val label = "lbl-9"
            // 1. pending send (ConnectionManagerImpl delegates to this insert path).
            val id = processor.insertPending(bufferId, label, sender = "me", text = "hi there", replyToMsgid = null, kind = MessageKind.PRIVMSG)
            assert(id > 0)
            assertEquals(1, rows().size)
            assertEquals(label, rows().single().pendingLabel)
            db.bufferDao().advanceLocalReadAnchor(bufferId, rows().single().serverTime, id)

            // 2. labeled echo arrives → update in place.
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = MessageContext(msgid = "real-1", serverTime = 600, account = null, batchId = null, label = label),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#chan",
                    text = "hi there",
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )
            assertNull(db.messageDao().byPendingLabel(bufferId, label))
            assertEquals(1, rows().size)
            assertEquals("real-1", rows().single().msgid)
            assertNull(rows().single().pendingLabel)
            assertEquals(600L, db.bufferDao().observeById(bufferId)?.localReadAnchorTime)
            assertEquals(id, db.bufferDao().observeById(bufferId)?.localReadAnchorEventId)

            // 3. same msgid via CHATHISTORY later → INSERT IGNORE no-op.
            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "#chan",
                    listOf(
                        IrcEvent.ChatMessage(
                            ctx = MessageContext(msgid = "real-1", serverTime = 600, account = null, batchId = "b", label = null),
                            kind = IrcEvent.ChatKind.PRIVMSG,
                            source = Prefix("me"),
                            target = "#chan",
                            text = "hi there",
                            isSelf = true,
                            replyToMsgid = null,
                        ),
                    ),
                ),
            )
            assertEquals(1, rows().size)
        }

    @Test
    fun labeledStandardFailurePreservesMessageThroughRetryAndEcho() =
        runTest {
            val label = "motd-rejected"
            val text = "original message"
            val description = "Fixture rejects first attempt"
            val eventId = processor.insertPending(bufferId, label, "me", text, null, MessageKind.PRIVMSG)
            processor.process(
                networkId,
                IrcEvent.StandardReply(
                    ctx = MessageContext(null, 600, null, null, label),
                    severity = IrcEvent.StandardReplySeverity.FAIL,
                    commandName = "PRIVMSG",
                    code = "CANNOTSENDTOCHAN",
                    context = listOf("#chan"),
                    description = description,
                ),
            )

            val failedRows = rows()
            assertEquals(2, failedRows.size)
            val failed = failedRows.single { it.id == eventId }
            assertEquals(MessageKind.PRIVMSG, failed.kind)
            assertEquals(text, failed.text)
            assertEquals(label, failed.pendingLabel)
            assertTrue(failed.failed)
            val error = failedRows.single { it.kind == MessageKind.ERROR }
            assertEquals("PRIVMSG failed (CANNOTSENDTOCHAN): $description", error.text)

            val retryLabel = "motd-retry"
            val retry = checkNotNull(processor.beginRetry(eventId, retryLabel))
            assertEquals(eventId, retry.id)
            assertEquals(text, retry.text)
            assertEquals(retryLabel, retry.pendingLabel)
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = MessageContext("accepted-msgid", 700, null, null, retryLabel),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#chan",
                    text = text,
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )

            val confirmedRows = rows()
            assertEquals(2, confirmedRows.size)
            val confirmed = confirmedRows.single { it.id == eventId }
            assertEquals(MessageKind.PRIVMSG, confirmed.kind)
            assertEquals(text, confirmed.text)
            assertEquals("accepted-msgid", confirmed.msgid)
            assertNull(confirmed.pendingLabel)
            assertEquals(false, confirmed.failed)
            assertEquals(error, confirmedRows.single { it.kind == MessageKind.ERROR })
        }

    @Test
    fun labeledCannotSendErrorFailsOnlyTheMatchingPendingMessage() =
        runTest {
            val label = "motd-refused"
            val newerLabel = "motd-newer"
            val older = processor.insertPending(bufferId, label, "me", "older message", null, MessageKind.PRIVMSG)
            val newer = processor.insertPending(bufferId, newerLabel, "me", "newer message", null, MessageKind.PRIVMSG)
            val refusal =
                IrcEvent.ServerError(
                    code = "404",
                    params = listOf("me", "#chan", "Cannot send to channel"),
                    text = "Cannot send to channel",
                    ctx = MessageContext(null, 600, null, null, label),
                )
            processor.process(networkId, refusal)

            val inlineRows = rows()
            assertEquals(3, inlineRows.size)
            val failed = inlineRows.single { it.id == older }
            assertEquals(MessageKind.PRIVMSG, failed.kind)
            assertEquals("older message", failed.text)
            assertEquals(label, failed.pendingLabel)
            assertTrue(failed.failed)
            assertEquals("404 Cannot send to channel", inlineRows.single { it.kind == MessageKind.ERROR }.text)
            assertEquals(newer, processor.pendingOutgoingByLabel(networkId, newerLabel)?.id)

            processor.process(networkId, refusal.copy(code = "403", ctx = refusal.ctx?.copy(serverTime = 700, label = "unknown")))
            assertEquals(newer, processor.pendingOutgoingByLabel(networkId, newerLabel)?.id)
        }

    @Test
    fun liveWithoutMsgid_thenHistoryWithMsgid_promotesBoundaryRow() =
        runTest {
            val live =
                IrcEvent.ChatMessage(
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
    fun locallyTimedLiveThenTaggedMsgidlessHistory_reconcilesTimestampSkew() =
        runTest {
            val live =
                IrcEvent.ChatMessage(
                    ctx =
                        MessageContext(
                            msgid = null,
                            serverTime = 1_784_228_957_447,
                            account = null,
                            batchId = null,
                            label = null,
                            serverTimeSource = ServerTimeSource.LOCAL,
                        ),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("antti"),
                    target = "#chan",
                    text = "!tell trev reproduce the duplicate",
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
                            ctx =
                                live.ctx.copy(
                                    serverTime = 1_784_228_959_000,
                                    batchId = "history",
                                    serverTimeSource = ServerTimeSource.TAG,
                                ),
                        ),
                    ),
                ),
            )

            val row = rows().single()
            assertNull(row.msgid)
            assertEquals(1_784_228_959_000, row.serverTime)
        }

    @Test
    fun existingTaggedMsgidlessHistory_collapsesLocallyTimedReplayWithoutConstraintFailure() =
        runTest {
            val history =
                IrcEvent.ChatMessage(
                    ctx =
                        MessageContext(
                            msgid = null,
                            serverTime = 800_000,
                            account = null,
                            batchId = "history-a",
                            label = null,
                            serverTimeSource = ServerTimeSource.TAG,
                        ),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "already stored",
                    isSelf = false,
                    replyToMsgid = null,
                )
            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(history)))

            processor.process(
                networkId,
                history.copy(
                    ctx =
                        history.ctx.copy(
                            serverTime = 801_000,
                            batchId = null,
                            serverTimeSource = ServerTimeSource.LOCAL,
                        ),
                ),
            )
            assertEquals(2, rows().size)

            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "#chan",
                    listOf(history.copy(ctx = history.ctx.copy(batchId = "history-b"))),
                ),
            )

            val row = rows().single()
            assertNull(row.msgid)
            assertEquals(800_000, row.serverTime)
        }

    @Test
    fun locallyTimedLiveDoesNotMergeAmbiguousRepeatedHistoryMessages() =
        runTest {
            val live =
                IrcEvent.ChatMessage(
                    ctx =
                        MessageContext(
                            msgid = null,
                            serverTime = 700_000,
                            account = null,
                            batchId = null,
                            label = null,
                            serverTimeSource = ServerTimeSource.LOCAL,
                        ),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "same",
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
                            ctx =
                                live.ctx.copy(
                                    serverTime = 701_000,
                                    batchId = "history-a",
                                    serverTimeSource = ServerTimeSource.TAG,
                                ),
                        ),
                        live.copy(
                            ctx =
                                live.ctx.copy(
                                    serverTime = 701_500,
                                    batchId = "history-b",
                                    serverTimeSource = ServerTimeSource.TAG,
                                ),
                        ),
                    ),
                ),
            )

            assertEquals(3, rows().size)
            assertEquals(setOf(700_000L, 701_000L, 701_500L), rows().map { it.serverTime }.toSet())
        }

    @Test
    fun pushWithMsgid_thenLiveWithoutMsgid_reusesDurableRow() =
        runTest {
            val push =
                IrcEvent.ChatMessage(
                    ctx =
                        MessageContext(
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
    fun pushWithMsgid_thenHistoryWithoutMsgid_reusesDurableRow() =
        runTest {
            val push =
                IrcEvent.ChatMessage(
                    ctx =
                        MessageContext(
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
    fun historyWithMsgid_thenDelayedPushWithoutMsgid_keepsOnlyDurableRow() =
        runTest {
            val history =
                IrcEvent.ChatMessage(
                    ctx =
                        MessageContext(
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
            // delayed WebPush payload. The observation attaches to the same canonical Room row.
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
            assertEquals(
                "soju-history-a",
                notifier.incoming
                    .single()
                    .ctx.msgid,
            )
            assertEquals(
                600_000,
                notifier.incoming
                    .single()
                    .ctx.serverTime,
            )
        }

    @Test
    fun msgidlessLiveDelivery_stillPersists() =
        runTest {
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
    fun historyWithMsgid_thenLiveWithoutMsgid_reusesRowAcrossOptionalTagDifferences() =
        runTest {
            val history =
                IrcEvent.ChatMessage(
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
    fun msgidlessHistoryDoesNotMergeIdenticalMessageOneSecondAfterDurableRow() =
        runTest {
            val durable =
                IrcEvent.ChatMessage(
                    ctx = MessageContext("durable-first", 700_000, null, "history", null),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "same",
                    isSelf = false,
                    replyToMsgid = null,
                )
            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(durable)))
            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "#chan",
                    listOf(durable.copy(ctx = durable.ctx.copy(msgid = null, serverTime = 701_000))),
                ),
            )

            assertEquals(2, rows().size)
            assertEquals(setOf(700_000L, 701_000L), rows().map { it.serverTime }.toSet())
        }

    @Test
    fun liveWithoutMsgid_doesNotMergeAmbiguousRepeatedMessages() =
        runTest {
            fun durable(
                msgid: String,
                serverTime: Long,
            ) = IrcEvent.ChatMessage(
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
    fun liveWithoutMsgid_doesNotMergeSameTextOutsideDeliveryWindow() =
        runTest {
            val push =
                IrcEvent.ChatMessage(
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
    fun historyMsgidPromotion_doesNotMergeSameTextAtDifferentTimes() =
        runTest {
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
    fun echoTimeout_marksPendingRowFailed() =
        runTest {
            val label = "lbl-timeout"
            processor.insertPending(bufferId, label, "me", "no echo", null, MessageKind.PRIVMSG)
            processor.failIfStillPending(bufferId, label)
            val row = rows().single()
            assertEquals(true, row.failed)
            assertEquals(label, row.pendingLabel)
        }

    @Test
    fun echoConfirmation_convergesAfterTimeoutInEitherOrder() =
        runTest {
            suspend fun echo(
                label: String,
                msgid: String,
                text: String,
            ) {
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

    @Test
    fun `labeled self echo without reply tag retains outgoing reply references`() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = MessageContext("parent-msgid", 600, null, null, null),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("alice"),
                    target = "#chan",
                    text = "parent",
                    isSelf = false,
                    replyToMsgid = null,
                ),
            )
            val parent = rows().single()
            val sent =
                processor
                    .persistOutgoingPlan(
                        bufferId = bufferId,
                        sender = "me",
                        events = listOf(OutgoingEventPlan("motd-reply", "reply", MessageKind.PRIVMSG)),
                        replyToEventId = parent.id,
                        replyToMsgid = parent.msgid,
                    ).single()

            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = MessageContext("reply-msgid", 700, null, null, "motd-reply"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#chan",
                    text = "reply",
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )

            val timeline = rows()
            val confirmed = timeline.single { it.id == sent.eventId }
            assertEquals(2, timeline.size)
            assertEquals("reply-msgid", confirmed.msgid)
            assertEquals("parent-msgid", confirmed.replyToMsgid)
            assertEquals(parent.id, confirmed.replyToEventId)
        }

    @Test
    fun `outgoing plan persists every chunk alias and observation in one call`() =
        runTest {
            val durable =
                processor.persistOutgoingPlan(
                    bufferId = bufferId,
                    sender = "me",
                    events =
                        listOf(
                            OutgoingEventPlan("motd-plan-1", "one", MessageKind.PRIVMSG),
                            OutgoingEventPlan("motd-plan-2", "two", MessageKind.PRIVMSG),
                            OutgoingEventPlan("motd-plan-3", "three", MessageKind.PRIVMSG),
                        ),
                    replyToEventId = null,
                    replyToMsgid = null,
                )

            assertEquals(3, durable.map { it.eventId }.distinct().size)
            assertEquals(setOf("one", "two", "three"), rows().map { it.text }.toSet())
            durable.forEach { event ->
                assertTrue(
                    db.canonicalTimelineDao().aliasesFor(event.eventId).any {
                        it.namespace == io.github.trevarj.motd.data.db.EventAliasNamespace.LABEL
                    },
                )
            }
            db.openHelper.readableDatabase
                .query(
                    "SELECT COUNT(*) FROM event_observations WHERE origin = 'LOCAL_SEND'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(3, cursor.getInt(0))
                }
        }

    @Test
    fun `rejected logical send is replanned into legacy chunks without a failed duplicate`() =
        runTest {
            val durable =
                processor
                    .persistOutgoingPlan(
                        bufferId = bufferId,
                        sender = "me",
                        events = listOf(OutgoingEventPlan("motd-logical", "one\n\nthree", MessageKind.PRIVMSG)),
                        replyToEventId = null,
                        replyToMsgid = null,
                    ).single()

            val replanned =
                processor.replanPendingOutgoing(
                    networkId = networkId,
                    eventId = durable.eventId,
                    oldLabel = "motd-logical",
                    events =
                        listOf(
                            OutgoingEventPlan("motd-legacy-1", "one", MessageKind.PRIVMSG),
                            OutgoingEventPlan("motd-legacy-2", "three", MessageKind.PRIVMSG),
                        ),
                )

            assertEquals(bufferId, replanned?.bufferId)
            assertEquals(durable.eventId, replanned?.events?.first()?.eventId)
            assertEquals(
                2,
                replanned
                    ?.events
                    ?.map { it.eventId }
                    ?.distinct()
                    ?.size,
            )
            assertNull(processor.pendingOutgoingByLabel(networkId, "motd-logical"))
            assertEquals("one", processor.pendingOutgoingByLabel(networkId, "motd-legacy-1")?.text)
            assertEquals("three", processor.pendingOutgoingByLabel(networkId, "motd-legacy-2")?.text)
            assertEquals(2, rows().size)
            assertTrue(rows().none { it.failed })
        }

    @Test
    fun `retry keeps event id and old label accepts a late echo`() =
        runTest {
            val eventId =
                processor.insertPending(
                    bufferId,
                    "motd-old-attempt",
                    "me",
                    "retry me",
                    null,
                    MessageKind.PRIVMSG,
                )
            processor.failPendingEvents(listOf(eventId))

            val retry = processor.beginRetry(eventId, "motd-new-attempt")

            assertEquals(eventId, retry?.id)
            assertEquals("motd-new-attempt", retry?.pendingLabel)
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx = MessageContext("late-msgid", 700, null, null, "motd-old-attempt"),
                    kind = IrcEvent.ChatKind.PRIVMSG,
                    source = Prefix("me"),
                    target = "#chan",
                    text = "retry me",
                    isSelf = true,
                    replyToMsgid = null,
                ),
            )
            val confirmed = rows().single()
            assertEquals(eventId, confirmed.id)
            assertEquals("late-msgid", confirmed.msgid)
            assertNull(confirmed.pendingLabel)
        }

    @Test
    fun retryRebuildsAudioMarkerFromCanonicalBody() =
        runTest {
            val body = "[voice 0:01 audio/ogg] https://files.example/voice.ogg"
            val plan =
                OutgoingEventPlan(
                    label = "motd-audio-old",
                    text = body,
                    kind = MessageKind.PRIVMSG,
                    ircFormattedText = body,
                )
            val durable =
                processor
                    .persistOutgoingPlan(
                        bufferId = bufferId,
                        sender = "me",
                        events = listOf(plan),
                        replyToEventId = null,
                        replyToMsgid = null,
                    ).single()
            val marker = mapOf("+trevarj.github.io/audio" to "1")

            assertEquals(marker, outgoingClientTags(plan.kind, plan.ircFormattedText ?: plan.text))
            processor.failPendingEvents(listOf(durable.eventId))
            val retry = checkNotNull(processor.beginRetry(durable.eventId, "motd-audio-new"))
            val retryBody = retry.ircFormattedText ?: retry.text

            assertEquals(durable.eventId, retry.id)
            assertEquals(body, retryBody)
            assertEquals(MessageKind.PRIVMSG, retry.kind)
            assertEquals(marker, outgoingClientTags(retry.kind, retryBody))
            assertEquals(
                emptyMap<String, String>(),
                outgoingClientTags(
                    MessageKind.PRIVMSG,
                    "[voice 0:01 audio/ogg expires=72h] https://files.example/voice.ogg",
                ),
            )
            assertEquals(emptyMap<String, String>(), outgoingClientTags(MessageKind.ACTION, body))
        }

    @Test
    fun `outgoing transitions resolve a redirect room inside the network sequence`() =
        runTest {
            val canonicalId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "canonical-room",
                        displayName = "canonical-room",
                        type = BufferType.QUERY,
                    ),
                )
            val redirectId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "old-room",
                        displayName = "old-room",
                        type = BufferType.QUERY,
                    ),
                )
            BufferStore(db).mergeRooms(canonicalId, redirectId)

            val durable =
                processor.persistOutgoingPlan(
                    bufferId = redirectId,
                    sender = "me",
                    events =
                        listOf(
                            OutgoingEventPlan("motd-redirect-fail", "fail then retry", MessageKind.PRIVMSG),
                            OutgoingEventPlan("motd-redirect-confirm", "timeout", MessageKind.PRIVMSG),
                        ),
                    replyToEventId = null,
                    replyToMsgid = null,
                )
            assertTrue(db.messageDao().byIds(durable.map { it.eventId }).all { it.bufferId == canonicalId })
            assertTrue(rows(redirectId).isEmpty())

            processor.failPendingEvents(listOf(durable.first().eventId))
            val retry = processor.beginRetry(durable.first().eventId, "motd-redirect-retry")
            processor.confirmIfStillPending(redirectId, "motd-redirect-retry")
            processor.failIfStillPending(redirectId, "motd-redirect-confirm")

            assertEquals(canonicalId, retry?.bufferId)
            val transitioned = db.messageDao().byIds(durable.map { it.eventId }).associateBy { it.id }
            assertNull(transitioned.getValue(durable.first().eventId).pendingLabel)
            assertEquals(false, transitioned.getValue(durable.first().eventId).failed)
            assertEquals(true, transitioned.getValue(durable.last().eventId).failed)
            assertEquals(canonicalId, db.bufferDao().rawById(redirectId)?.redirectToRoomId)
        }

    @Test
    fun `pending recovery can run again after the same row is retried`() =
        runTest {
            val eventId =
                processor.insertPending(
                    bufferId,
                    "motd-interrupted",
                    "me",
                    "recover me",
                    null,
                    MessageKind.PRIVMSG,
                )

            assertEquals(1, processor.recoverInterruptedPending())
            assertEquals(true, db.messageDao().byId(eventId)?.failed)
            assertEquals("motd-interrupted", db.messageDao().byId(eventId)?.pendingLabel)

            assertEquals(eventId, processor.beginRetry(eventId, "motd-interrupted-again")?.id)
            assertEquals(1, processor.recoverInterruptedPending())
            assertEquals(true, db.messageDao().byId(eventId)?.failed)
            assertEquals("motd-interrupted-again", db.messageDao().byId(eventId)?.pendingLabel)
        }
}
