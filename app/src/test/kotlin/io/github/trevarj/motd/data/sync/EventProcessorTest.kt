package io.github.trevarj.motd.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.HistoryCursorEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.RoomAliasNamespace
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.push.PushEventHandler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
class EventProcessorTest {
    private class RecordingDiagnostics : DiagnosticLogger {
        override val enabled: StateFlow<Boolean> = MutableStateFlow(true)
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun setEnabled(enabled: Boolean) = Unit
        override fun record(component: String, event: String, fields: () -> Map<String, Any?>) {
            events += event to fields()
        }
        override fun fingerprint(value: String?): String? = value
        override suspend fun exportTo(output: OutputStream) = Unit
    }

    private enum class InviteDeliveryOrder {
        HISTORY_LIVE,
        LIVE_HISTORY,
        HISTORY_PUSH,
        PUSH_HISTORY,
    }

    private lateinit var db: MotdDatabase
    private lateinit var processor: EventProcessor
    private var networkId: Long = 0

    private fun ctx(
        msgid: String? = null,
        time: Long = 1000,
        label: String? = null,
        account: String? = null,
    ) = MessageContext(msgid = msgid, serverTime = time, account = account, batchId = null, label = label)

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .allowMainThreadQueries().build()
        processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
        networkId = db.networkDao().insert(
            NetworkEntity(
                name = "libera", role = NetworkRole.DIRECT,
                host = "irc.libera.chat", port = 6697,
                nick = "me", username = "me", realname = "Me",
            ),
        )
        processor.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))
    }

    @After fun tearDown() { db.close() }

    private suspend fun pagingList(bufferId: Long) =
        db.messageDao().pagingSource(bufferId).load(
            androidx.paging.PagingSource.LoadParams.Refresh(null, 100, false),
        ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data }

    @Test
    fun chatMessage_channel_insertsRow_andAutoCreatesBuffer() = runTest {
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "m1"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "hello world",
            isSelf = false, replyToMsgid = null,
        ))
        val buffer = db.bufferDao().byName(networkId, "#chan")
        assertNotNull(buffer)
        assertEquals(BufferType.CHANNEL, buffer!!.type)
        val rows = pagingList(buffer.id)
        assertEquals(1, rows.size)
        assertEquals("hello world", rows.single().text)
        assertFalse(rows.single().hasMention)
    }

    @Test
    fun advertisedCustomChantypesAreAuthoritativeForRoomRouting() = runTest {
        processor.onRegistered(
            networkId,
            "me",
            mapOf("CASEMAPPING" to "rfc1459", "CHANTYPES" to "+!"),
        )
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "custom-channel"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "+room", text = "channel",
            isSelf = false, replyToMsgid = null,
        ))
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "hash-query"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("bob"), target = "#room", text = "query",
            isSelf = false, replyToMsgid = null,
        ))

        assertEquals(BufferType.CHANNEL, db.bufferDao().byName(networkId, "+room")?.type)
        assertEquals(BufferType.QUERY, db.bufferDao().byName(networkId, "bob")?.type)
        assertNull(db.bufferDao().byName(networkId, "#room"))
    }

    @Test
    fun explicitlyEmptyChantypesRoutesHashTargetAsQueryWithoutSplittingOldRooms() = runTest {
        val legacyId = db.bufferDao().insert(
            BufferEntity(
                networkId = networkId,
                name = "#legacy",
                displayName = "#legacy",
                type = BufferType.CHANNEL,
            ),
        )
        processor.onRegistered(
            networkId,
            "me",
            mapOf("CASEMAPPING" to "rfc1459", "CHANTYPES" to ""),
        )
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "empty-chantypes"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#legacy", text = "query now",
            isSelf = false, replyToMsgid = null,
        ))

        assertEquals(BufferType.CHANNEL, db.bufferDao().observeById(legacyId)?.type)
        assertEquals(BufferType.QUERY, db.bufferDao().byName(networkId, "alice")?.type)
    }

    @Test
    fun customChantypesRestoreAfterProcessDeathForPushRouting() = runTest {
        processor.onRegistered(
            networkId,
            "me",
            mapOf("CASEMAPPING" to "rfc1459", "CHANTYPES" to "+!"),
        )
        val persisted = db.networkIdentityDao().byNetwork(networkId)!!
        assertEquals("rfc1459", persisted.caseMapping)
        assertEquals("+!", persisted.chanTypes)
        assertEquals("me", persisted.selfNick)

        val restarted = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
        restarted.processPush(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "custom-after-restart"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "+room", text = "channel after restart",
            isSelf = false, replyToMsgid = null,
        ))

        assertEquals(BufferType.CHANNEL, db.bufferDao().byName(networkId, "+room")?.type)
    }

    @Test
    fun mapperLimitedPushedOutgoingPmUsesPersistedCurrentSelfNick() = runTest {
        processor.onRegistered(networkId, "OldSelf", mapOf("CASEMAPPING" to "rfc1459"))
        assertEquals("me", db.networkDao().byId(networkId)?.nick)
        assertEquals("OldSelf", db.networkIdentityDao().byNetwork(networkId)?.selfNick)
        processor.process(
            networkId,
            IrcEvent.NickChanged(
                ctx = ctx(msgid = "self-nick"),
                from = "OldSelf",
                to = "Me[",
                isSelf = false,
            ),
        )
        assertEquals("me", db.networkDao().byId(networkId)?.nick)
        assertEquals("Me[", db.networkIdentityDao().byNetwork(networkId)?.selfNick)

        var notifications = 0
        val restarted = EventProcessor(
            db,
            TypingTrackerImpl(),
            object : MessageNotifier {
                override suspend fun onIncoming(
                    networkId: Long,
                    bufferId: Long,
                    type: BufferType,
                    hasMention: Boolean,
                    message: IrcEvent.ChatMessage,
                ) {
                    notifications++
                }
            },
        )
        val pushed = PushEventHandler.mapToEvent(
            IrcMessage.parse(":ME{!user@host PRIVMSG Bob :pushed outgoing"),
        ) as IrcEvent.ChatMessage
        assertFalse(pushed.isSelf)

        restarted.processPush(networkId, pushed)

        val peer = db.bufferDao().byName(networkId, "bob")!!
        val row = pagingList(peer.id).single()
        assertTrue(row.isSelf)
        assertEquals("pushed outgoing", row.text)
        assertNull(db.bufferDao().byName(networkId, "me{"))
        assertEquals(0, notifications)
    }

    @Test
    fun missingPersistedSelfNickFallsBackToConfiguredNickForPush() = runTest {
        db.networkIdentityDao().upsert(
            io.github.trevarj.motd.data.db.NetworkIdentityEntity(
                networkId = networkId,
                caseMapping = "rfc1459",
                chanTypes = "",
                selfNick = null,
            ),
        )
        val restarted = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
        val pushed = PushEventHandler.mapToEvent(
            IrcMessage.parse(":ME!user@host PRIVMSG Bob :configured fallback"),
        ) as IrcEvent.ChatMessage

        restarted.processPush(networkId, pushed)

        val peer = db.roomAliasDao().byValue(
            networkId,
            RoomAliasNamespace.PROVISIONAL_NICK,
            "bob",
        )!!
        assertTrue(pagingList(peer.roomId).single().isSelf)
        assertEquals("me", db.networkDao().byId(networkId)?.nick)
        assertNull(db.networkIdentityDao().byNetwork(networkId)?.selfNick)
    }

    @Test
    fun emptyChantypesRestoreForPushAndPreserveLegacyChannelRoom() = runTest {
        val legacyId = db.bufferDao().insert(
            BufferEntity(
                networkId = networkId,
                name = "#peer",
                displayName = "#peer",
                type = BufferType.CHANNEL,
            ),
        )
        processor.onRegistered(
            networkId,
            "me",
            mapOf("CASEMAPPING" to "rfc1459", "CHANTYPES" to ""),
        )

        val restarted = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
        restarted.processPush(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "empty-after-restart"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("me"), target = "#peer", text = "query after restart",
            isSelf = true, replyToMsgid = null,
        ))

        assertEquals("", db.networkIdentityDao().byNetwork(networkId)?.chanTypes)
        assertEquals(BufferType.CHANNEL, db.bufferDao().rawById(legacyId)?.type)
        val queryAlias = db.roomAliasDao().byValue(
            networkId,
            RoomAliasNamespace.PROVISIONAL_NICK,
            "#peer",
        )!!
        val query = db.bufferDao().observeById(queryAlias.roomId)!!
        assertEquals(BufferType.QUERY, query.type)
        assertNotEquals(legacyId, query.id)
        assertEquals("empty-after-restart", pagingList(query.id).single().msgid)
    }

    @Test
    fun restoredUnknownCasemappingUsesAsciiAndEmitsDiagnostic() = runTest {
        processor.onRegistered(
            networkId,
            "me",
            mapOf("CASEMAPPING" to "vendor-unicode", "CHANTYPES" to "+"),
        )
        val diagnostics = RecordingDiagnostics()
        val restarted = EventProcessor(
            db = db,
            typing = TypingTrackerImpl(),
            notifier = MessageNotifier.Noop,
            diagnostics = diagnostics,
        )
        listOf("+[room]", "+{room}").forEachIndexed { index, target ->
            restarted.processPush(networkId, IrcEvent.ChatMessage(
                ctx = ctx(msgid = "unknown-$index", time = 1000L + index),
                kind = IrcEvent.ChatKind.PRIVMSG,
                source = Prefix("alice"), target = target, text = target,
                isSelf = false, replyToMsgid = null,
            ))
        }

        val persisted = db.networkIdentityDao().byNetwork(networkId)!!
        assertEquals("vendor-unicode", persisted.caseMapping)
        assertNotEquals(
            db.bufferDao().byName(networkId, "+[room]")?.id,
            db.bufferDao().byName(networkId, "+{room}")?.id,
        )
        assertTrue(diagnostics.events.any { (event, _) -> event == "unsupported_casemapping" })
    }

    @Test
    fun strictRfc1459KeepsTildeAndCaretRoomsDistinctForFutureEvents() = runTest {
        processor.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459-strict"))
        listOf("#room~", "#room^").forEachIndexed { index, target ->
            processor.process(networkId, IrcEvent.ChatMessage(
                ctx = ctx(msgid = "strict-room-$index", time = 1000L + index),
                kind = IrcEvent.ChatKind.PRIVMSG,
                source = Prefix("alice"),
                target = target,
                text = target,
                isSelf = false,
                replyToMsgid = null,
            ))
        }

        val tilde = db.bufferDao().byName(networkId, "#room~")
        val caret = db.bufferDao().byName(networkId, "#room^")
        assertNotNull(tilde)
        assertNotNull(caret)
        assertNotEquals(tilde?.id, caret?.id)
    }

    @Test
    fun strictReactionActorsRemainDistinctAfterRestart() = runTest {
        processor.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459-strict"))
        val restarted = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
        restarted.processPush(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "Opaque-Target"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "parent",
            isSelf = false, replyToMsgid = null,
        ))
        listOf("bot~", "bot^").forEachIndexed { index, actor ->
            restarted.processPush(networkId, IrcEvent.TagMessage(
                ctx = ctx(time = 2000L + index), source = Prefix(actor), target = "#chan",
                typing = null, reactEmoji = "+1", reactTargetMsgid = "Opaque-Target",
            ))
        }
        restarted.processPush(networkId, IrcEvent.TagMessage(
            ctx = ctx(time = 2002), source = Prefix("BOT~"), target = "#chan",
            typing = null, reactEmoji = "+1", reactTargetMsgid = "Opaque-Target",
        ))

        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        val reactions = db.reactionDao().observeFor(buffer.id, listOf("Opaque-Target")).first()
        assertEquals(setOf("nick:bot~", "nick:bot^"), reactions.map { it.actorKey }.toSet())
        assertTrue(reactions.all { it.targetMsgid == "Opaque-Target" })
        assertEquals("rfc1459-strict", db.networkIdentityDao().byNetwork(networkId)?.identityRules?.caseMapping?.rawName)
    }

    @Test
    fun nickChangeRekeysCachedAccountForOwnReactionIdentity() = runTest {
        processor.process(networkId, IrcEvent.AccountChanged("me", "stable-self-account"))
        processor.process(
            networkId,
            IrcEvent.NickChanged(ctx(msgid = "nick-rekey"), "me", "NewMe", isSelf = true),
        )
        processor.process(
            networkId,
            IrcEvent.ChatMessage(
                ctx = ctx(msgid = "reaction-parent", time = 2_000),
                kind = IrcEvent.ChatKind.PRIVMSG,
                source = Prefix("alice"),
                target = "#chan",
                text = "parent",
                isSelf = false,
                replyToMsgid = null,
            ),
        )
        processor.process(
            networkId,
            IrcEvent.TagMessage(
                ctx = ctx(time = 2_001),
                source = Prefix("NewMe"),
                target = "#chan",
                typing = null,
                reactEmoji = "+1",
                reactTargetMsgid = "reaction-parent",
            ),
        )

        assertNull(db.userDao().byNick(networkId, "me"))
        assertEquals("stable-self-account", db.userDao().byNick(networkId, "newme")?.account)
        val room = db.bufferDao().byName(networkId, "#chan")!!
        assertEquals(
            setOf("account:stable-self-account"),
            db.reactionDao().observeFor(room.id, listOf("reaction-parent")).first()
                .mapTo(mutableSetOf()) { it.actorKey },
        )
    }

    @Test
    fun topicSnapshot_updatesChannelTopic_withoutAddingTimelineEntry() = runTest {
        processor.process(networkId, IrcEvent.TopicSnapshot("#Room", "Welcome to the room"))

        val buffer = db.bufferDao().byName(networkId, "#room")!!
        assertEquals("Welcome to the room", buffer.topic)
        assertNull(buffer.topicSetBy)
        assertTrue(pagingList(buffer.id).isEmpty())

        processor.process(networkId, IrcEvent.TopicSnapshot("#Room", ""))

        assertEquals("", db.bufferDao().byName(networkId, "#room")!!.topic)
        assertTrue(pagingList(buffer.id).isEmpty())
    }

    @Test
    fun chatMessage_dm_autoCreatesQueryBuffer_keyedBySender() = runTest {
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "m2"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("bob"), target = "me", text = "hi",
            isSelf = false, replyToMsgid = null,
        ))
        val buffer = db.bufferDao().byName(networkId, "bob")
        assertNotNull(buffer)
        assertEquals(BufferType.QUERY, buffer!!.type)
    }

    @Test
    fun dismissedQueryDropsOldHistoryAndRevivesForNewDm() = runTest {
        processor.process(
            networkId,
            IrcEvent.ChatMessage(
                ctx("m-old", 100),
                IrcEvent.ChatKind.PRIVMSG,
                Prefix("bob"),
                "me",
                "discard me",
                false,
                null,
            ),
        )
        val query = db.bufferDao().byName(networkId, "bob")!!
        db.bufferDao().deleteBuffer(query.id)

        processor.process(
            networkId,
            IrcEvent.HistoryBatch(
                "bob",
                listOf(
                    IrcEvent.ChatMessage(
                        ctx("m-older", 90).copy(batchId = "history"),
                        IrcEvent.ChatKind.PRIVMSG,
                        Prefix("bob"),
                        "me",
                        "older",
                        false,
                        null,
                    ),
                    IrcEvent.ChatMessage(
                        ctx("m-old", 100).copy(batchId = "history"),
                        IrcEvent.ChatKind.PRIVMSG,
                        Prefix("bob"),
                        "me",
                        "discard me",
                        false,
                        null,
                    ),
                    IrcEvent.ChatMessage(
                        ctx("m-new", 101).copy(batchId = "history"),
                        IrcEvent.ChatKind.PRIVMSG,
                        Prefix("bob"),
                        "me",
                        "new dm",
                        false,
                        null,
                    ),
                ),
            ),
        )

        assertFalse(db.bufferDao().rawById(query.id)!!.dismissed)
        assertEquals(listOf("m-new"), pagingList(query.id).mapNotNull { it.msgid })
    }

    @Test
    fun dismissedQueryConservativelyRevivesForEqualTimeDifferentMsgid() = runTest {
        processor.process(
            networkId,
            IrcEvent.ChatMessage(
                ctx("m-old", 100),
                IrcEvent.ChatKind.PRIVMSG,
                Prefix("bob"),
                "me",
                "old",
                false,
                null,
            ),
        )
        val query = db.bufferDao().byName(networkId, "bob")!!
        db.bufferDao().deleteBuffer(query.id)

        processor.process(
            networkId,
            IrcEvent.HistoryBatch(
                "bob",
                listOf(
                    IrcEvent.ChatMessage(
                        ctx("m-same-time", 100).copy(batchId = "history"),
                        IrcEvent.ChatKind.PRIVMSG,
                        Prefix("bob"),
                        "me",
                        "possibly new",
                        false,
                        null,
                    ),
                ),
            ),
        )

        assertFalse(db.bufferDao().rawById(query.id)!!.dismissed)
        assertEquals("m-same-time", pagingList(query.id).single().msgid)
    }

    @Test
    fun dismissedQueryRejectsEveryExactMsgidAtTimestampTie() = runTest {
        listOf("m-tie-a", "m-tie-b").forEach { msgid ->
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx(msgid, 100),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("bob"),
                    "me",
                    msgid,
                    false,
                    null,
                ),
            )
        }
        val query = db.bufferDao().byName(networkId, "bob")!!
        db.bufferDao().deleteBuffer(query.id)

        processor.process(
            networkId,
            IrcEvent.HistoryBatch(
                "bob",
                listOf("m-tie-a", "m-tie-b").map { msgid ->
                    IrcEvent.ChatMessage(
                        ctx(msgid, 100).copy(batchId = "history"),
                        IrcEvent.ChatKind.PRIVMSG,
                        Prefix("bob"),
                        "me",
                        msgid,
                        false,
                        null,
                    )
                },
            ),
        )

        assertTrue(db.bufferDao().rawById(query.id)!!.dismissed)
        assertTrue(pagingList(query.id).isEmpty())
    }

    @Test
    fun dismissedQueryAlwaysRevivesForPushDespiteServerClockSkew() = runTest {
        processor.process(
            networkId,
            IrcEvent.ChatMessage(
                ctx("m-old", 100),
                IrcEvent.ChatKind.PRIVMSG,
                Prefix("bob"),
                "me",
                "old",
                false,
                null,
            ),
        )
        val query = db.bufferDao().byName(networkId, "bob")!!
        db.bufferDao().deleteBuffer(query.id)

        processor.processPush(
            networkId,
            IrcEvent.ChatMessage(
                ctx("m-push", 50),
                IrcEvent.ChatKind.PRIVMSG,
                Prefix("bob"),
                "me",
                "clock-skewed new dm",
                false,
                null,
            ),
        )

        assertFalse(db.bufferDao().rawById(query.id)!!.dismissed)
        assertEquals("m-push", pagingList(query.id).single().msgid)
        assertEquals(1, db.bufferDao().observeChatList().first().single {
            it.bufferId == query.id
        }.unreadCount)
    }

    @Test
    fun dismissedQueryIgnoresExactDelayedPushDuplicate() = runTest {
        val message = IrcEvent.ChatMessage(
            ctx("m-old", 100),
            IrcEvent.ChatKind.PRIVMSG,
            Prefix("bob"),
            "me",
            "old",
            false,
            null,
        )
        processor.process(networkId, message)
        val query = db.bufferDao().byName(networkId, "bob")!!
        db.bufferDao().deleteBuffer(query.id)

        processor.processPush(networkId, message.copy(ctx = ctx("m-old", 150)))

        assertTrue(db.bufferDao().rawById(query.id)!!.dismissed)
        assertTrue(pagingList(query.id).isEmpty())
    }

    @Test
    fun outgoingMessageRevivesDismissedQueryBeforePersisting() = runTest {
        processor.process(
            networkId,
            IrcEvent.ChatMessage(
                ctx("old", 100),
                IrcEvent.ChatKind.PRIVMSG,
                Prefix("bob"),
                "me",
                "old",
                false,
                null,
            ),
        )
        val query = db.bufferDao().byName(networkId, "bob")!!
        db.bufferDao().deleteBuffer(query.id)

        processor.insertPending(
            query.id,
            "send-after-delete",
            "me",
            "new outgoing",
            replyToMsgid = null,
            kind = MessageKind.PRIVMSG,
        )

        assertFalse(db.bufferDao().rawById(query.id)!!.dismissed)
        assertEquals("new outgoing", pagingList(query.id).single().text)
    }

    @Test
    fun dismissedAccountQueryAppliesFloorAfterHistoricalNickRerouting() = runTest {
        processor.process(
            networkId,
            IrcEvent.ChatMessage(
                ctx("account-old", 1_000, account = "acct-a"),
                IrcEvent.ChatKind.PRIVMSG,
                Prefix("alice"),
                "me",
                "old",
                false,
                null,
            ),
        )
        val query = db.bufferDao().byName(networkId, "alice")!!
        db.bufferDao().deleteBuffer(query.id)
        fun history(msgid: String, time: Long) = IrcEvent.ChatMessage(
            ctx(msgid, time, account = "acct-a").copy(batchId = "history"),
            IrcEvent.ChatKind.PRIVMSG,
            Prefix("bob"),
            "me",
            msgid,
            false,
            null,
        )

        processor.process(networkId, IrcEvent.HistoryBatch("bob", listOf(history("older", 900))))

        assertTrue(db.bufferDao().rawById(query.id)!!.dismissed)
        assertTrue(pagingList(query.id).isEmpty())
        assertNull(db.bufferDao().byName(networkId, "bob"))

        processor.process(networkId, IrcEvent.HistoryBatch("bob", listOf(history("newer", 1_100))))

        assertFalse(db.bufferDao().rawById(query.id)!!.dismissed)
        assertEquals(listOf("newer"), pagingList(query.id).mapNotNull { it.msgid })
    }

    @Test
    fun liveTypingTracksPeersButIgnoresSelfUsingServerCaseMapping() = runTest {
        val typing = TypingTrackerImpl()
        val recording = EventProcessor(db, typing, MessageNotifier.Noop)
        recording.onRegistered(networkId, "Me[", mapOf("CASEMAPPING" to "rfc1459"))

        recording.process(
            networkId,
            IrcEvent.TagMessage(ctx(), Prefix("Alice"), "#chan", "active", null, null),
        )
        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        assertEquals(listOf("Alice"), typing.typingNicks(buffer.id).value)

        recording.process(
            networkId,
            IrcEvent.TagMessage(ctx(), Prefix("ME{"), "#chan", "active", null, null),
        )

        assertEquals(listOf("Alice"), typing.typingNicks(buffer.id).value)
    }

    @Test
    fun selfPmEchoAccountTagDoesNotIdentifyDifferentRecipientsAsOneRoom() = runTest {
        suspend fun echo(target: String, msgid: String) {
            processor.process(
                networkId,
                IrcEvent.ChatMessage(
                    ctx(msgid = msgid).copy(account = "my-account"),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("me"),
                    target,
                    "hello $target",
                    true,
                    null,
                ),
            )
        }

        echo("Alice", "self-alice")
        echo("Bob", "self-bob")

        val alice = db.bufferDao().byName(networkId, "alice")!!
        val bob = db.bufferDao().byName(networkId, "bob")!!
        assertNotEquals(alice.id, bob.id)
        assertEquals("my-account", pagingList(alice.id).single().senderAccount)
        assertEquals("my-account", pagingList(bob.id).single().senderAccount)
    }

    @Test
    fun historicalOutgoingPmFromOldSelfNickUsesBatchPeerRoom() = runTest {
        processor.onRegistered(networkId, "newMe", mapOf("CASEMAPPING" to "rfc1459"))
        val historical = IrcEvent.ChatMessage(
            ctx(msgid = "old-self-history", time = 4_000).copy(
                account = "my-account",
                batchId = "bob-history",
            ),
            IrcEvent.ChatKind.PRIVMSG,
            Prefix("oldMe"),
            "Bob",
            "sent before nick change",
            false,
            null,
        )

        processor.process(networkId, IrcEvent.HistoryBatch("Bob", listOf(historical)))

        val bob = db.bufferDao().byName(networkId, "bob")!!
        val row = pagingList(bob.id).single()
        assertTrue(row.isSelf)
        assertEquals("my-account", row.senderAccount)
        assertNull(db.bufferDao().byName(networkId, "oldme"))
    }

    @Test
    fun historicalIncomingPmFromPeersOldNickRemainsIncomingInBatchPeerRoom() = runTest {
        processor.onRegistered(networkId, "newMe", mapOf("CASEMAPPING" to "rfc1459"))
        val historical = IrcEvent.ChatMessage(
            ctx(msgid = "old-peer-history", time = 4_100).copy(
                account = "bob-account",
                batchId = "bob-history",
            ),
            IrcEvent.ChatKind.PRIVMSG,
            Prefix("OldBob"),
            "oldMe",
            "sent to my previous nick",
            false,
            null,
        )

        processor.process(networkId, IrcEvent.HistoryBatch("Bob", listOf(historical)))

        val bob = db.bufferDao().byName(networkId, "bob")!!
        val row = pagingList(bob.id).single()
        assertFalse(row.isSelf)
        assertEquals("bob-account", row.senderAccount)
        assertEquals(
            bob.id,
            BufferStore(db).resolveQueryRoom(networkId, "bob", "bob-account")?.id,
        )
        assertNull(db.bufferDao().byName(networkId, "oldbob"))
    }

    @Test
    fun historicalIncomingPmIgnoresCurrentNickCollisionInMapperSelfFlag() = runTest {
        processor.onRegistered(networkId, "newMe", mapOf("CASEMAPPING" to "rfc1459"))
        val historical = IrcEvent.ChatMessage(
            ctx(msgid = "peer-used-current-self-nick", time = 4_200).copy(
                account = "bob-account",
                batchId = "bob-history",
            ),
            IrcEvent.ChatKind.PRIVMSG,
            Prefix("newMe"),
            "oldMe",
            "the peer used my current nick in the past",
            true,
            null,
        )

        processor.process(networkId, IrcEvent.HistoryBatch("Bob", listOf(historical)))

        val bob = db.bufferDao().byName(networkId, "bob")!!
        val row = pagingList(bob.id).single()
        assertFalse(row.isSelf)
        assertEquals("newMe", row.sender)
        assertEquals("bob-account", row.senderAccount)
        assertNull(db.bufferDao().byName(networkId, "oldme"))
    }

    @Test
    fun historicalQueryReplayRepairsIncorrectSelfAttribution() = runTest {
        processor.onRegistered(networkId, "newMe", mapOf("CASEMAPPING" to "rfc1459"))
        val incorrectlyAttributed = IrcEvent.ChatMessage(
            ctx(msgid = "repair-query-direction", time = 4_300),
            IrcEvent.ChatKind.PRIVMSG,
            Prefix("newMe"),
            "Bob",
            "repair my direction",
            true,
            null,
        )
        processor.processPush(networkId, incorrectlyAttributed)
        val bob = db.bufferDao().byName(networkId, "bob")!!
        assertTrue(pagingList(bob.id).single().isSelf)

        processor.process(
            networkId,
            IrcEvent.HistoryBatch(
                "Bob",
                listOf(
                    incorrectlyAttributed.copy(
                        ctx = incorrectlyAttributed.ctx.copy(batchId = "bob-history"),
                        target = "oldMe",
                    ),
                ),
            ),
        )

        val repaired = pagingList(bob.id).single()
        assertFalse(repaired.isSelf)
        assertEquals("repair-query-direction", repaired.msgid)
    }

    @Test
    fun historicalReplayFromQueryPeerCannotFlipIncomingLiveMessageToSelf() = runTest {
        processor.onRegistered(networkId, "newMe", mapOf("CASEMAPPING" to "rfc1459"))
        val incoming = IrcEvent.ChatMessage(
            ctx(msgid = "incoming-then-history", time = 4_400),
            IrcEvent.ChatKind.PRIVMSG,
            Prefix("Bob"),
            "newMe",
            "correct before opening the chat",
            false,
            null,
        )
        processor.process(networkId, incoming)
        val bob = db.bufferDao().byName(networkId, "bob")!!
        assertFalse(pagingList(bob.id).single().isSelf)

        processor.process(
            networkId,
            IrcEvent.HistoryBatch(
                "Bob",
                listOf(
                    incoming.copy(
                        ctx = incoming.ctx.copy(batchId = "bob-history"),
                        // Some bouncers replay a query against its conversation target rather than
                        // retaining the self nick that appeared on the original wire message.
                        target = "Bob",
                    ),
                ),
            ),
        )

        val replayed = pagingList(bob.id).single()
        assertFalse(replayed.isSelf)
        assertEquals("incoming-then-history", replayed.msgid)
    }

    @Test
    fun accountTaggedDmMergesExistingProvisionalNickRoomIntoKnownAccount() = runTest {
        processor.process(
            networkId,
            IrcEvent.ChatMessage(
                ctx(msgid = "alice-account", time = 1_000).copy(account = "acct-a"),
                IrcEvent.ChatKind.PRIVMSG,
                Prefix("alice"),
                "me",
                "from alice",
                false,
                null,
            ),
        )
        processor.process(
            networkId,
            IrcEvent.ChatMessage(
                ctx(msgid = "bob-provisional", time = 2_000),
                IrcEvent.ChatKind.PRIVMSG,
                Prefix("bob"),
                "me",
                "before identification",
                false,
                null,
            ),
        )
        val bobBefore = db.bufferDao().byName(networkId, "bob")!!

        processor.process(
            networkId,
            IrcEvent.ChatMessage(
                ctx(msgid = "bob-account", time = 3_000).copy(account = "acct-a"),
                IrcEvent.ChatKind.PRIVMSG,
                Prefix("bob"),
                "me",
                "after identification",
                false,
                null,
            ),
        )

        val accountRoom = checkNotNull(
            BufferStore(db).resolveQueryRoom(networkId, "bob", "acct-a"),
        )
        assertEquals(accountRoom.id, db.bufferDao().observeById(bobBefore.id)?.id)
        assertEquals(3, pagingList(accountRoom.id).size)
        assertEquals(1, db.bufferDao().observeChatList().first().size)
    }

    @Test
    fun rfc1459EquivalentChannelTargets_shareOneBuffer() = runTest {
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "case-channel-1"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#Room", text = "first",
            isSelf = false, replyToMsgid = null,
        ))
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "case-channel-2", time = 1001), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#room", text = "second",
            isSelf = false, replyToMsgid = null,
        ))

        val buffers = db.bufferDao().observeChatList().first().filter { it.displayName.contains("Room", ignoreCase = true) }
        assertEquals(1, buffers.size)
        assertEquals(2, pagingList(buffers.single().bufferId).size)
    }

    @Test
    fun liveAndHistoryRepresentations_withNickCaseChange_reconcileToOneRow() = runTest {
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "durable", time = 2000), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("ALICE"), target = "#chan", text = "same line",
            isSelf = false, replyToMsgid = null,
        ))
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(time = 2001), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "same line",
            isSelf = false, replyToMsgid = null,
        ))

        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        val rows = pagingList(buffer.id)
        assertEquals(1, rows.size)
        assertEquals("durable", rows.single().msgid)
    }

    @Test
    fun msgidPromotion_withNickCaseChange_reusesTheMsgidlessRow() = runTest {
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(time = 3000), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("ALICE"), target = "#chan", text = "same line",
            isSelf = false, replyToMsgid = null,
        ))
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "promoted", time = 3000), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "same line",
            isSelf = false, replyToMsgid = null,
        ))

        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        val rows = pagingList(buffer.id)
        assertEquals(1, rows.size)
        assertEquals("promoted", rows.single().msgid)
    }

    @Test
    fun mentionFlag_setWhenOwnNickAppears_wordBoundary() = runTest {
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "m3"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "hey me, look here",
            isSelf = false, replyToMsgid = null,
        ))
        // Substring-only should NOT match (e.g. "meeting").
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "m4", time = 1001), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "meeting soon",
            isSelf = false, replyToMsgid = null,
        ))
        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        val rows = pagingList(buffer.id).sortedBy { it.serverTime }
        assertTrue(rows[0].hasMention)
        assertFalse(rows[1].hasMention)
    }

    @Test
    fun mentionRegex_rebuiltOnNickChange() = runTest {
        // Self renames me -> newnick.
        processor.process(networkId, IrcEvent.NickChanged(ctx(), from = "me", to = "newnick", isSelf = true))
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "m5"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "ping newnick please",
            isSelf = false, replyToMsgid = null,
        ))
        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        assertTrue(pagingList(buffer.id).single().hasMention)
    }

    @Test
    fun mentionMatchingUsesStrictRfc1459WithoutFoldingTilde() = runTest {
        processor.onRegistered(networkId, "me~", mapOf("CASEMAPPING" to "rfc1459-strict"))
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "strict-no"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "ping me^",
            isSelf = false, replyToMsgid = null,
        ))
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "strict-yes", time = 1001), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "ping ME~",
            isSelf = false, replyToMsgid = null,
        ))

        val rows = pagingList(db.bufferDao().byName(networkId, "#chan")!!.id)
        assertFalse(rows.first { it.msgid == "strict-no" }.hasMention)
        assertTrue(rows.first { it.msgid == "strict-yes" }.hasMention)
    }

    @Test
    fun replyToOwnKnownParent_setsMention_withoutTextMention() = runTest {
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "parent"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("me"), target = "#chan", text = "parent text",
            isSelf = true, replyToMsgid = null,
        ))
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "child", time = 1001), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "plain child",
            isSelf = false, replyToMsgid = "parent",
        ))

        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        assertTrue(pagingList(buffer.id).first { it.msgid == "child" }.hasMention)
    }

    @Test
    fun replyArrivingBeforeParentResolvesByIndexedRoomAndMsgidLookup() = runTest {
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "child"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "child",
            isSelf = false, replyToMsgid = "late-parent",
        ))
        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        assertNull(db.messageDao().byMsgid(buffer.id, "child")?.replyToEventId)

        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "late-parent", time = 1001), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("bob"), target = "#chan", text = "parent",
            isSelf = false, replyToMsgid = null,
        ))

        val parent = db.messageDao().byMsgid(buffer.id, "late-parent")!!
        assertEquals(parent.id, db.messageDao().byMsgid(buffer.id, "child")?.replyToEventId)
    }

    @Test
    fun replyToOwnKnownParent_notifiesExactlyOnce() = runTest {
        var notifications = 0
        val notifying = EventProcessor(db, TypingTrackerImpl(), object : MessageNotifier {
            override suspend fun onIncoming(
                networkId: Long,
                bufferId: Long,
                type: BufferType,
                hasMention: Boolean,
                message: IrcEvent.ChatMessage,
            ) {
                notifications++
                assertTrue(hasMention)
            }
        })
        notifying.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))
        notifying.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "parent"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("me"), target = "#chan", text = "parent",
            isSelf = true, replyToMsgid = null,
        ))
        notifying.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "child", time = 1001), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "plain child",
            isSelf = false, replyToMsgid = "parent",
        ))

        assertEquals(1, notifications)
    }

    @Test
    fun chatSoundFailure_doesNotInterruptPersistenceOrNotification() = runTest {
        var notifications = 0
        val recording = EventProcessor(
            db = db,
            typing = TypingTrackerImpl(),
            notifier = object : MessageNotifier {
                override suspend fun onIncoming(
                    networkId: Long,
                    bufferId: Long,
                    type: BufferType,
                    hasMention: Boolean,
                    message: IrcEvent.ChatMessage,
                ) {
                    notifications++
                }
            },
            chatSoundPlayer = object : ChatSoundPlayer {
                override suspend fun onIncoming(
                    bufferId: Long,
                    type: BufferType,
                    message: IrcEvent.ChatMessage,
                ) {
                    error("audio service unavailable")
                }

                override suspend fun onOutgoingAccepted(bufferId: Long) = Unit
            },
        )
        recording.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))

        recording.process(
            networkId,
            IrcEvent.ChatMessage(
                ctx = ctx(msgid = "sound-failure"),
                kind = IrcEvent.ChatKind.PRIVMSG,
                source = Prefix("alice"),
                target = "#chan",
                text = "me: still persist and notify",
                isSelf = false,
                replyToMsgid = null,
            ),
        )

        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        assertEquals(1, pagingList(buffer.id).size)
        assertEquals(1, notifications)
    }

    @Test
    fun canonicalEventDrivesOneNotificationAndAtMostOneSoundAcrossHistoryAndLive() = runTest {
        var notifications = 0
        var sounds = 0
        val recording = EventProcessor(
            db = db,
            typing = TypingTrackerImpl(),
            notifier = object : MessageNotifier {
                override suspend fun onIncoming(
                    networkId: Long,
                    bufferId: Long,
                    type: BufferType,
                    hasMention: Boolean,
                    message: IrcEvent.ChatMessage,
                ) {
                    notifications++
                }
            },
            chatSoundPlayer = object : ChatSoundPlayer {
                override suspend fun onIncoming(
                    bufferId: Long,
                    type: BufferType,
                    message: IrcEvent.ChatMessage,
                ) {
                    sounds++
                }

                override suspend fun onOutgoingAccepted(bufferId: Long) = Unit
            },
        )
        recording.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))
        val durable = IrcEvent.ChatMessage(
            ctx = ctx(msgid = "presentation-once", time = 5_000),
            kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"),
            target = "#chan",
            text = "me: one presentation decision",
            isSelf = false,
            replyToMsgid = null,
        )

        recording.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(durable)))
        recording.process(networkId, durable)
        recording.process(networkId, durable)

        assertEquals(1, notifications)
        assertEquals(1, sounds)
        val buffer = requireNotNull(db.bufferDao().byName(networkId, "#chan"))
        assertEquals(1, pagingList(buffer.id).size)
    }

    @Test
    fun pendingChannelClose_deletesOnlyAfterSelfPartAcknowledgement() = runTest {
        val bufferId = db.bufferDao().insert(
            BufferEntity(
                networkId = networkId,
                name = "#closing",
                displayName = "#closing",
                type = BufferType.CHANNEL,
                pendingCloseAt = 123,
            ),
        )

        processor.process(
            networkId,
            IrcEvent.Parted(
                ctx = ctx(),
                nick = "me",
                channel = "#closing",
                reason = null,
                isSelf = true,
            ),
        )

        assertNull(db.bufferDao().observeById(bufferId))
    }

    @Test
    fun pendingChannelClose_notOnChannelError_isTerminalAcknowledgement() = runTest {
        val bufferId = db.bufferDao().insert(
            BufferEntity(
                networkId = networkId,
                name = "#already-gone",
                displayName = "#already-gone",
                type = BufferType.CHANNEL,
                pendingCloseAt = 123,
            ),
        )

        processor.process(
            networkId,
            IrcEvent.ServerError(
                code = "442",
                params = listOf("me", "#already-gone", "You're not on that channel"),
                text = "You're not on that channel",
            ),
        )

        assertNull(db.bufferDao().observeById(bufferId))
    }

    @Test
    fun replyToOtherOrMissingParent_doesNotInventMention() = runTest {
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "other-parent"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("bob"), target = "#chan", text = "parent text",
            isSelf = false, replyToMsgid = null,
        ))
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "other-child", time = 1001), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "plain child",
            isSelf = false, replyToMsgid = "other-parent",
        ))
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "missing-child", time = 1002), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "another plain child",
            isSelf = false, replyToMsgid = "missing",
        ))

        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        val rows = pagingList(buffer.id)
        assertFalse(rows.first { it.msgid == "other-child" }.hasMention)
        assertFalse(rows.first { it.msgid == "missing-child" }.hasMention)
    }

    @Test
    fun quit_fansOutToEveryMemberBuffer() = runTest {
        // alice joins two channels.
        processor.process(networkId, IrcEvent.Joined(ctx(), "alice", "#a", null, null, false))
        processor.process(networkId, IrcEvent.Joined(ctx(), "alice", "#b", null, null, false))
        val a = db.bufferDao().byName(networkId, "#a")!!.id
        val b = db.bufferDao().byName(networkId, "#b")!!.id
        assertEquals(1, db.memberDao().observe(a).first().count { it.nick == "alice" })
        assertEquals(1, db.memberDao().observe(b).first().count { it.nick == "alice" })

        processor.process(networkId, IrcEvent.Quit(ctx(time = 2000), "alice", "bye"))

        assertTrue(db.memberDao().observe(a).first().none { it.nick == "alice" })
        assertTrue(db.memberDao().observe(b).first().none { it.nick == "alice" })
        // A QUIT system message landed in both buffers.
        assertTrue(pagingList(a).any { it.kind == MessageKind.QUIT })
        assertTrue(pagingList(b).any { it.kind == MessageKind.QUIT })
    }

    @Test
    fun join_insertsSystemMessage_andMarksSelfJoined() = runTest {
        processor.process(networkId, IrcEvent.Joined(ctx(msgid = "j1"), "me", "#chan", null, null, isSelf = true))
        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        assertTrue(buffer.joined)
        assertTrue(pagingList(buffer.id).any { it.kind == MessageKind.JOIN })
    }

    @Test
    fun reaction_upserted() = runTest {
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "m1"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "parent",
            isSelf = false, replyToMsgid = null,
        ))
        processor.process(networkId, IrcEvent.TagMessage(
            ctx = ctx(), source = Prefix("alice"), target = "#chan",
            typing = null, reactEmoji = "👍", reactTargetMsgid = "m1",
        ))
        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        val reactions = db.reactionDao().observeFor(buffer.id, listOf("m1")).first()
        assertEquals(1, reactions.size)
        assertEquals("👍", reactions.single().emoji)
    }

    @Test
    fun unreact_removesMatchingReaction_andReplayIsIdempotent() = runTest {
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "m1"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "parent",
            isSelf = false, replyToMsgid = null,
        ))
        processor.process(networkId, IrcEvent.TagMessage(
            ctx = ctx(), source = Prefix("bob"), target = "#chan",
            typing = null, reactEmoji = "👍", reactTargetMsgid = "m1",
        ))
        val unreact = IrcEvent.Raw(
            IrcMessage.parse("@+draft/unreact=👍;+reply=m1 :BOB!u@h TAGMSG #chan"),
        )

        processor.process(networkId, unreact)
        processor.process(networkId, unreact)

        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        assertTrue(db.reactionDao().observeFor(buffer.id, listOf("m1")).first().isEmpty())
    }

    @Test
    fun actorCanReactWithMultipleEmojis_andUnreactRemovesOnlySelectedEmoji() = runTest {
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "m1"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "parent",
            isSelf = false, replyToMsgid = null,
        ))
        listOf("👍", "🎉").forEachIndexed { index, emoji ->
            processor.process(networkId, IrcEvent.TagMessage(
                ctx = ctx(time = 2_000L + index), source = Prefix("bob"), target = "#chan",
                typing = null, reactEmoji = emoji, reactTargetMsgid = "m1",
            ))
        }
        processor.process(
            networkId,
            IrcEvent.Raw(
                IrcMessage.parse("@+draft/unreact=👍;+reply=m1 :BOB!u@h TAGMSG #chan"),
            ),
        )

        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        val rows = db.reactionDao().observeFor(buffer.id, listOf("m1")).first()
        assertEquals(listOf("🎉"), rows.map { it.emoji })
    }

    @Test
    fun accountReactionEchoReconcilesOptimisticNickKeyWithTargetedMutation() = runTest {
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "m1"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "parent",
            isSelf = false, replyToMsgid = null,
        ))
        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        db.reactionDao().upsert(
            io.github.trevarj.motd.data.db.ReactionEntity(
                bufferId = buffer.id,
                targetMsgid = "m1",
                actorKey = "nick:bob",
                sender = "bob",
                emoji = "👍",
                serverTime = 1,
            ),
        )

        processor.process(networkId, IrcEvent.TagMessage(
            ctx = ctx(time = 2, account = "BobAccount"), source = Prefix("BOB"), target = "#chan",
            typing = null, reactEmoji = "👍", reactTargetMsgid = "m1",
        ))

        val echoed = db.reactionDao().observeFor(buffer.id, listOf("m1")).first().single()
        assertEquals("account:BobAccount", echoed.actorKey)
        assertEquals("BOB", echoed.sender)

        processor.process(networkId, IrcEvent.AccountChanged("bob", "BobAccount"))
        processor.process(networkId, IrcEvent.TagMessage(
            ctx = ctx(time = 3), source = Prefix("bob"), target = "#chan",
            typing = null, reactEmoji = "👍", reactTargetMsgid = "m1",
        ))
        assertEquals(
            "account:BobAccount",
            db.reactionDao().observeFor(buffer.id, listOf("m1")).first().single().actorKey,
        )

        processor.process(
            networkId,
            IrcEvent.Raw(
                IrcMessage.parse(
                    "@+draft/unreact=👍;+reply=m1 :bob!u@h TAGMSG #chan",
                ),
            ),
        )
        assertTrue(db.reactionDao().observeFor(buffer.id, listOf("m1")).first().isEmpty())
    }

    @Test
    fun migratedLegacyReactionAliasesAreCleanedPerEmojiWithoutScanningOtherReactions() = runTest {
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "m1"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "parent",
            isSelf = false, replyToMsgid = null,
        ))
        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        val legacyActor = "nick:bob\u0000legacy:7"
        db.reactionDao().upsert(
            ReactionEntity(bufferId = buffer.id, targetMsgid = "m1", actorKey = legacyActor,
                sender = "BOB", emoji = "one", serverTime = 1),
        )
        db.reactionDao().upsert(
            ReactionEntity(bufferId = buffer.id, targetMsgid = "m1", actorKey = legacyActor,
                sender = "BOB", emoji = "two", serverTime = 2),
        )

        processor.process(networkId, IrcEvent.TagMessage(
            ctx = ctx(time = 3), source = Prefix("Bob"), target = "#chan",
            typing = null, reactEmoji = "one", reactTargetMsgid = "m1",
        ))

        var rows = db.reactionDao().observeFor(buffer.id, listOf("m1")).first()
        assertEquals("nick:bob", rows.single { it.emoji == "one" }.actorKey)
        assertEquals(legacyActor, rows.single { it.emoji == "two" }.actorKey)

        processor.process(
            networkId,
            IrcEvent.Raw(IrcMessage.parse("@+draft/unreact=one;+reply=m1 :BOB!u@h TAGMSG #chan")),
        )

        rows = db.reactionDao().observeFor(buffer.id, listOf("m1")).first()
        assertEquals(listOf("two"), rows.map { it.emoji })
        assertEquals(legacyActor, rows.single().actorKey)
    }

    @Test
    fun queryHistoryReactionsAndUnreactionsFollowBatchPeerAcrossNickChanges() = runTest {
        processor.process(
            networkId,
            IrcEvent.ChatMessage(
                ctx = ctx(msgid = "query-parent"),
                kind = IrcEvent.ChatKind.PRIVMSG,
                source = Prefix("Bob"),
                target = "me",
                text = "parent",
                isSelf = false,
                replyToMsgid = null,
            ),
        )
        val buffer = db.bufferDao().byName(networkId, "bob")!!

        processor.process(
            networkId,
            IrcEvent.HistoryBatch(
                "Bob",
                listOf(
                    IrcEvent.TagMessage(
                        ctx = ctx(time = 2_000),
                        source = Prefix("oldMe"),
                        target = "Bob",
                        typing = null,
                        reactEmoji = "👍",
                        reactTargetMsgid = "query-parent",
                    ),
                    IrcEvent.TagMessage(
                        ctx = ctx(time = 2_001),
                        source = Prefix("OldBob"),
                        target = "oldMe",
                        typing = null,
                        reactEmoji = "🔥",
                        reactTargetMsgid = "query-parent",
                    ),
                ),
            ),
        )

        assertEquals(
            setOf("oldMe" to "👍", "OldBob" to "🔥"),
            db.reactionDao().observeFor(buffer.id, listOf("query-parent")).first()
                .map { it.sender to it.emoji }
                .toSet(),
        )

        processor.process(
            networkId,
            IrcEvent.HistoryBatch(
                "Bob",
                listOf(
                    IrcEvent.Raw(
                        IrcMessage.parse(
                            "@+draft/unreact=👍;+reply=query-parent :oldMe!u@h TAGMSG Bob",
                        ),
                    ),
                    IrcEvent.Raw(
                        IrcMessage.parse(
                            "@+draft/unreact=🔥;+reply=query-parent :OldBob!u@h TAGMSG oldMe",
                        ),
                    ),
                ),
            ),
        )

        assertTrue(
            db.reactionDao().observeFor(buffer.id, listOf("query-parent")).first().isEmpty(),
        )
    }

    @Test
    fun reactionForMissingParent_isIgnoredWithoutCreatingBuffer() = runTest {
        processor.process(networkId, IrcEvent.TagMessage(
            ctx = ctx(), source = Prefix("alice"), target = "#missing",
            typing = null, reactEmoji = "👍", reactTargetMsgid = "absent",
        ))

        assertNull(db.bufferDao().byName(networkId, "#missing"))
    }

    @Test
    fun reactionArrivingBeforeParent_isRetainedUntilParentBecomesVisible() = runTest {
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "seed"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "existing buffer",
            isSelf = false, replyToMsgid = null,
        ))
        val buffer = db.bufferDao().byName(networkId, "#chan")!!

        processor.process(networkId, IrcEvent.TagMessage(
            ctx = ctx(time = 5), source = Prefix("bob"), target = "#chan",
            typing = null, reactEmoji = "👍", reactTargetMsgid = "late-parent",
        ))
        assertEquals(
            1,
            db.reactionDao().observeFor(buffer.id, listOf("late-parent")).first().size,
        )

        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "late-parent", time = 6), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("me"), target = "#chan", text = "parent",
            isSelf = true, replyToMsgid = null,
        ))

        val reactions = db.reactionDao().observeFor(buffer.id, listOf("late-parent")).first()
        assertEquals("👍", reactions.single().emoji)
        assertEquals("bob", reactions.single().sender)
        assertEquals(db.messageDao().byMsgid(buffer.id, "late-parent")?.id, reactions.single().targetEventId)
    }

    @Test
    fun lateQueryReactionTargetRepairsAmbiguousNickRouteAfterAccountReuse() = runTest {
        suspend fun incoming(msgid: String, account: String, text: String) {
            processor.process(networkId, IrcEvent.ChatMessage(
                ctx = ctx(msgid = msgid, account = account),
                kind = IrcEvent.ChatKind.PRIVMSG,
                source = Prefix("Bob"),
                target = "me",
                text = text,
                isSelf = false,
                replyToMsgid = null,
            ))
        }

        incoming("account-a-seed", "account-a", "first Bob")
        incoming("account-b-seed", "account-b", "reused Bob")
        val accountARoom = checkNotNull(BufferStore(db).resolveQueryRoom(networkId, "bob", "account-a"))
        val accountBRoom = checkNotNull(BufferStore(db).resolveQueryRoom(networkId, "bob", "account-b"))
        assertNotEquals(accountARoom.id, accountBRoom.id)

        processor.process(networkId, IrcEvent.TagMessage(
            ctx = ctx(time = 2_000),
            source = Prefix("Bob"),
            target = "me",
            typing = null,
            reactEmoji = "👍",
            reactTargetMsgid = "account-a-parent",
        ))
        assertEquals(
            "nick:bob",
            db.reactionDao().observeFor(accountBRoom.id, listOf("account-a-parent")).first()
                .single().actorKey,
        )

        incoming("account-a-parent", "account-a", "late parent")

        val parent = checkNotNull(db.messageDao().byMsgid(accountARoom.id, "account-a-parent"))
        val reaction = db.reactionDao().observeFor(accountARoom.id, listOf("account-a-parent"))
            .first().single()
        assertEquals(parent.id, reaction.targetEventId)
        assertTrue(
            db.reactionDao().observeFor(accountBRoom.id, listOf("account-a-parent")).first().isEmpty(),
        )
    }

    @Test
    fun ownReactionEcho_reconcilesOntoOptimisticRow_withoutDuplicating() = runTest {
        // Auto-create the buffer and seed the optimistic own-reaction row the way sendReact does
        // (upsert keyed by bufferId+targetMsgid+actorKey+emoji) before the server echo arrives.
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "m1"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "hi",
            isSelf = false, replyToMsgid = null,
        ))
        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        db.reactionDao().upsert(
            io.github.trevarj.motd.data.db.ReactionEntity(
                bufferId = buffer.id, targetMsgid = "m1", actorKey = "nick:me",
                sender = "me", emoji = "👍", serverTime = 5,
            ),
        )
        // Server echoes our own react back with different nick casing; IRC casefolding must still
        // reconcile it with the optimistic row rather than creating a duplicate.
        processor.process(networkId, IrcEvent.TagMessage(
            ctx = ctx(time = 9), source = Prefix("Me"), target = "#chan",
            typing = null, reactEmoji = "👍", reactTargetMsgid = "m1",
        ))

        val reactions = db.reactionDao().observeFor(buffer.id, listOf("m1")).first()
        assertEquals(1, reactions.size)
        assertEquals("Me", reactions.single().sender)
        assertEquals(9, reactions.single().serverTime)
    }

    @Test
    fun readMarker_advancesInclusiveLocalAnchorBeforeHistoryAndMaxOnly() = runTest {
        val bufferId = db.bufferDao().insert(
            io.github.trevarj.motd.data.db.BufferEntity(
                networkId = networkId, name = "#chan", displayName = "#chan", type = BufferType.CHANNEL,
            ),
        )
        processor.process(networkId, IrcEvent.ReadMarker("#chan", 5000))
        processor.process(networkId, IrcEvent.ReadMarker("#chan", 3000)) // lower → ignored
        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        assertEquals(5000L, buffer.readMarkerTime)
        assertEquals(5000L, buffer.localReadAnchorTime)
        assertEquals(Long.MAX_VALUE, buffer.localReadAnchorEventId)

        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "before-marker", time = 4999), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "before",
            isSelf = false, replyToMsgid = null,
        ))
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "same-ms", time = 5000), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "same millisecond",
            isSelf = false, replyToMsgid = null,
        ))

        val row = db.bufferDao().observeChatList().first().single { it.bufferId == bufferId }
        assertEquals(0, row.unreadCount)

        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "after-marker", time = 5001), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "after",
            isSelf = false, replyToMsgid = null,
        ))
        assertEquals(
            1,
            db.bufferDao().observeChatList().first().single { it.bufferId == bufferId }.unreadCount,
        )
    }

    @Test
    fun readMarker_notifiesOnlyForKnownTimestampedTarget() = runTest {
        val observed = mutableListOf<Pair<Long, io.github.trevarj.motd.data.db.TimelineAnchor>>()
        val notifying = EventProcessor(db, TypingTrackerImpl(), object : MessageNotifier {
            override suspend fun onIncoming(
                networkId: Long,
                bufferId: Long,
                type: BufferType,
                hasMention: Boolean,
                message: IrcEvent.ChatMessage,
            ) = Unit

            override suspend fun onRead(
                bufferId: Long,
                anchor: io.github.trevarj.motd.data.db.TimelineAnchor,
            ) {
                observed += bufferId to anchor
            }
        })
        notifying.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))
        val bufferId = db.bufferDao().insert(
            io.github.trevarj.motd.data.db.BufferEntity(
                networkId = networkId,
                name = "#chan",
                displayName = "#chan",
                type = BufferType.CHANNEL,
            ),
        )

        notifying.process(networkId, IrcEvent.ReadMarker("#chan", 5000))
        notifying.process(networkId, IrcEvent.ReadMarker("#missing", 6000))
        notifying.process(networkId, IrcEvent.ReadMarker("#chan", null))

        assertEquals(
            listOf(
                bufferId to io.github.trevarj.motd.data.db.TimelineAnchor(5000, Long.MAX_VALUE),
            ),
            observed,
        )
    }

    @Test
    fun bouncerNetworkState_mirrorsChildRow_thenDeletes() = runTest {
        val rootId = db.networkDao().insert(
            NetworkEntity(
                name = "soju", role = NetworkRole.BOUNCER_ROOT,
                host = "bnc.example", port = 6697, nick = "me", username = "me", realname = "Me",
            ),
        )
        processor.process(rootId, IrcEvent.BouncerNetworkState("42", mapOf("name" to "OFTC", "host" to "irc.oftc.net")))
        var children = db.networkDao().childrenOf(rootId)
        assertEquals(1, children.size)
        assertEquals("OFTC", children.single().name)
        assertEquals("42", children.single().bouncerNetId)

        processor.process(rootId, IrcEvent.BouncerNetworkState("42", emptyMap())) // "*" → delete
        children = db.networkDao().childrenOf(rootId)
        assertTrue(children.isEmpty())
    }

    @Test
    fun bouncerNetworkState_partialUpdate_preservesExistingConnectionFields() = runTest {
        val rootId = db.networkDao().insert(
            NetworkEntity(
                name = "soju", role = NetworkRole.BOUNCER_ROOT,
                host = "bnc.example", port = 6697, nick = "rootNick", username = "me", realname = "Me",
            ),
        )
        processor.process(rootId, IrcEvent.BouncerNetworkState("42", mapOf(
            "name" to "OFTC", "host" to "irc.oftc.net", "port" to "6698", "nickname" to "childNick",
        )))

        // Soju's later NETWORK notifications may omit unchanged attrs. They must not replace the
        // child endpoint/nick with root defaults, which would churn the child's fingerprint.
        processor.process(rootId, IrcEvent.BouncerNetworkState("42", mapOf("name" to "OFTC")))

        val child = db.networkDao().childrenOf(rootId).single()
        assertEquals("irc.oftc.net", child.host)
        assertEquals(6698, child.port)
        assertEquals("childNick", child.nick)
    }

    // --- Round 5: server buffer routing (plans/16 §5.6) ---

    private suspend fun serverBuffer() = db.bufferDao().byName(networkId, "*")

    @Test
    fun serverNotice_routesToServerBuffer_notQuery() = runTest {
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(), kind = IrcEvent.ChatKind.NOTICE,
            source = Prefix("irc.libera.chat"), target = "me", text = "*** Looking up your hostname",
            isSelf = false, replyToMsgid = null,
        ))
        val server = serverBuffer()
        assertNotNull(server)
        assertEquals(BufferType.SERVER, server!!.type)
        assertEquals("libera", server.displayName)
        assertEquals("*** Looking up your hostname", pagingList(server.id).single().text)
        // No junk query buffer for the host source.
        assertNull(db.bufferDao().byName(networkId, "irc.libera.chat"))
    }

    @Test
    fun repeatedHistoryServerNoticesUseServerRoutingAndPreserveMultiplicity() = runTest {
        val notice = IrcEvent.ChatMessage(
            ctx = ctx(msgid = null, time = 2_000).copy(batchId = "notice-history"),
            kind = IrcEvent.ChatKind.NOTICE,
            source = Prefix("irc.libera.chat"),
            target = "me",
            text = "same historical notice",
            isSelf = false,
            replyToMsgid = null,
        )
        val batch = IrcEvent.HistoryBatch("me", listOf(notice, notice))

        processor.process(networkId, batch)
        processor.process(networkId, batch)

        val server = checkNotNull(serverBuffer())
        assertEquals(2, pagingList(server.id).count { it.kind == MessageKind.NOTICE })
        assertNull(db.bufferDao().byName(networkId, "irc.libera.chat"))
    }

    @Test
    fun historyPreflightRoomCreationRollsBackWithFailedBatch() = runTest {
        db.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER reject_history_message BEFORE INSERT ON messages
               BEGIN SELECT RAISE(ABORT, 'forced history failure'); END""",
        )
        val message = IrcEvent.ChatMessage(
            ctx = ctx(msgid = "rollback-history", time = 2_100).copy(batchId = "rollback"),
            kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"),
            target = "#rollback",
            text = "must roll back with its room",
            isSelf = false,
            replyToMsgid = null,
        )

        val result = runCatching {
            processor.process(networkId, IrcEvent.HistoryBatch("#rollback", listOf(message)))
        }

        assertTrue(result.isFailure)
        assertNull(db.bufferDao().byName(networkId, "#rollback"))
    }

    @Test
    fun repeatedAccountBackedPmHistoryUsesCanonicalAccountRoom() = runTest {
        processor.process(
            networkId,
            IrcEvent.ChatMessage(
                ctx(msgid = "known-account", time = 1_000).copy(account = "acct-a"),
                IrcEvent.ChatKind.PRIVMSG,
                Prefix("alice"),
                "me",
                "establish account room",
                false,
                null,
            ),
        )
        val knownRoom = db.bufferDao().byName(networkId, "alice")!!
        val repeated = IrcEvent.ChatMessage(
            ctx(msgid = null, time = 2_200).copy(account = "acct-a", batchId = "account-history"),
            IrcEvent.ChatKind.PRIVMSG,
            Prefix("bob"),
            "me",
            "same account history",
            false,
            null,
        )
        val batch = IrcEvent.HistoryBatch("bob", listOf(repeated, repeated))

        processor.process(networkId, batch)
        processor.process(networkId, batch)

        assertEquals(3, pagingList(knownRoom.id).size)
        assertEquals(2, pagingList(knownRoom.id).count { it.text == "same account history" })
        assertNull(db.bufferDao().byName(networkId, "bob"))
    }

    @Test
    fun msgidlessPushServerNotice_persistsCanonicalServerEvent() = runTest {
        processor.processPush(networkId, IrcEvent.ChatMessage(
            ctx = ctx(), kind = IrcEvent.ChatKind.NOTICE,
            source = Prefix("irc.libera.chat"), target = "me", text = "transient server notice",
            isSelf = false, replyToMsgid = null,
        ))

        val server = serverBuffer()
        assertNotNull(server)
        assertEquals(BufferType.SERVER, server!!.type)
        assertEquals(1, pagingList(server.id).size)
        assertNull(db.bufferDao().byName(networkId, "irc.libera.chat"))
    }

    @Test
    fun nickServNotice_stillCreatesQueryBuffer() = runTest {
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(), kind = IrcEvent.ChatKind.NOTICE,
            source = Prefix("NickServ"), target = "me", text = "This nick is registered.",
            isSelf = false, replyToMsgid = null,
        ))
        val query = db.bufferDao().byName(networkId, "nickserv")
        assertNotNull(query)
        assertEquals(BufferType.QUERY, query!!.type)
        assertNull(serverBuffer())
    }

    @Test
    fun serverError_insertsErrorRow() = runTest {
        processor.process(networkId, IrcEvent.ServerError("482", listOf("me", "#chan"), "You're not a channel operator"))
        val server = serverBuffer()!!
        val row = pagingList(server.id).single()
        assertEquals(MessageKind.ERROR, row.kind)
        assertTrue(row.text.startsWith("482"))
    }

    @Test
    fun whitelistedNumeric_insertsServerInfo_withNickDropped() = runTest {
        // 375 RPL_MOTDSTART: params = [me, "- server Message of the Day -"].
        processor.process(networkId, IrcEvent.Raw(
            io.github.trevarj.motd.irc.proto.IrcMessage(command = "375", params = listOf("me", "- Message of the Day -")),
        ))
        val server = serverBuffer()!!
        val row = pagingList(server.id).single()
        assertEquals(MessageKind.SERVER_INFO, row.kind)
        assertEquals("- Message of the Day -", row.text) // our nick dropped
    }

    @Test
    fun nonWhitelistedRaw_isDropped() = runTest {
        // 322 (RPL_LIST) is deliberately excluded so LIST never floods the server buffer.
        processor.process(networkId, IrcEvent.Raw(
            io.github.trevarj.motd.irc.proto.IrcMessage(command = "322", params = listOf("me", "#chan", "42", "topic")),
        ))
        assertNull(serverBuffer())
    }

    @Test
    fun disconnected_insertsServerInfoMarker() = runTest {
        processor.process(networkId, IrcEvent.Disconnected("connection reset"))
        val server = serverBuffer()!!
        val row = pagingList(server.id).single()
        assertEquals(MessageKind.SERVER_INFO, row.kind)
        assertEquals("disconnected: connection reset", row.text)
    }

    @Test
    fun serverBufferMention_doesNotNotify() = runTest {
        // A recording notifier; a MOTD line containing our nick must not fire.
        var fired = false
        val recProcessor = EventProcessor(db, TypingTrackerImpl(), object : MessageNotifier {
            override suspend fun onIncoming(networkId: Long, bufferId: Long, type: BufferType, hasMention: Boolean, message: IrcEvent.ChatMessage) {
                fired = true
            }
        })
        recProcessor.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))
        recProcessor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(), kind = IrcEvent.ChatKind.NOTICE,
            source = Prefix("irc.libera.chat"), target = "me", text = "welcome me to the server",
            isSelf = false, replyToMsgid = null,
        ))
        assertFalse(fired)
        // The line still landed in the server buffer.
        assertNotNull(serverBuffer())
    }

    @Test
    fun rootBouncerServ_privmsg_isPersistedSeenWithoutNotification_butNoticeRemainsOrdinary() = runTest {
        val direct = db.networkDao().byId(networkId)!!
        db.networkDao().update(direct.copy(role = NetworkRole.BOUNCER_ROOT))
        val notifiedKinds = mutableListOf<IrcEvent.ChatKind>()
        val recording = EventProcessor(db, TypingTrackerImpl(), object : MessageNotifier {
            override suspend fun onIncoming(
                networkId: Long,
                bufferId: Long,
                type: BufferType,
                hasMention: Boolean,
                message: IrcEvent.ChatMessage,
            ) {
                notifiedKinds += message.kind
            }
        })
        recording.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))

        recording.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "service-reply", time = 2_000), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("BouncerServ"), target = "me", text = "network status for me",
            isSelf = false, replyToMsgid = null,
        ))
        val buffer = db.bufferDao().byName(networkId, "bouncerserv")!!
        assertEquals(2_000L, db.bufferDao().observeById(buffer.id)!!.localReadAnchorTime)
        assertEquals(null, db.bufferDao().observeById(buffer.id)!!.readMarkerTime)
        assertTrue(notifiedKinds.isEmpty())

        recording.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "service-notice", time = 3_000), kind = IrcEvent.ChatKind.NOTICE,
            source = Prefix("BouncerServ"), target = "me", text = "detached relay",
            isSelf = false, replyToMsgid = null,
        ))
        assertEquals(listOf(IrcEvent.ChatKind.NOTICE), notifiedKinds)
    }

    @Test
    fun bouncerServ_self_echo_is_redacted_before_room_and_dedup() = runTest {
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "secret-command"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("me"), target = "BouncerServ",
            text = "sasl set-plain -network libera alice hunter2",
            isSelf = true, replyToMsgid = null,
        ))
        val buffer = db.bufferDao().byName(networkId, "bouncerserv")!!
        val row = pagingList(buffer.id).single()
        assertFalse(row.text.contains("hunter2"))
        assertTrue(row.text.contains("<redacted>"))
    }

    // --- own-message single-row across the three echo scenarios (plans/03/04, bug 4) ---

    /** Buffer id for a self-send target, creating the query/channel buffer as onChat would. */
    private suspend fun selfBuffer(target: String): Long {
        // A channel target starts with '#'; DMs key by the other party but self-sends key by target.
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = null, time = 500), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("me"), target = target, text = "__seed__", isSelf = true, replyToMsgid = null,
        ))
        val name = if (target.startsWith("#")) target else target
        return db.bufferDao().byName(networkId, name)!!.id
    }

    @Test
    fun ownMessage_echoWithLabeledResponse_singleRow() = runTest {
        val bufferId = selfBuffer("#chan")
        // Pending insert (labeled send).
        processor.insertPending(bufferId, "lbl1", "me", "hey there", null, MessageKind.PRIVMSG)
        // Labeled echo confirms in place.
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "srv1", time = 2000, label = "lbl1"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("me"), target = "#chan", text = "hey there", isSelf = true, replyToMsgid = null,
        ))
        val rows = pagingList(bufferId).filter { it.text == "hey there" }
        assertEquals(1, rows.size)
        assertEquals("srv1", rows.single().msgid)
        assertNull(rows.single().pendingLabel)
    }

    @Test
    fun ownMessage_echoOnly_noLabel_collapsesToSingleRow() = runTest {
        val bufferId = selfBuffer("#chan")
        // (c/b) Confirmed-local insert as the no-labeled-response send path does: self ChatMessage,
        // no msgid, no label, local clock.
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = null, time = 1000, label = null), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("me"), target = "#chan", text = "echo test", isSelf = true, replyToMsgid = null,
        ))
        // Server echo-message arrives with a real msgid and a slightly different (server) time.
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "srv2", time = 1200, label = null), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("me"), target = "#chan", text = "echo test", isSelf = true, replyToMsgid = null,
        ))
        val rows = pagingList(bufferId).filter { it.text == "echo test" }
        assertEquals(1, rows.size)
        assertEquals("srv2", rows.single().msgid)
        // A later CHATHISTORY replay of the same msgid is ignored.
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "srv2", time = 1200, label = null), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("me"), target = "#chan", text = "echo test", isSelf = true, replyToMsgid = null,
        ))
        assertEquals(1, pagingList(bufferId).count { it.text == "echo test" })
    }

    @Test
    fun ownMessage_pendingThenUnlabeledEcho_collapsesAndConfirms() = runTest {
        val bufferId = selfBuffer("bob") // DM query buffer
        // Labeled send inserts a pending row (serverTime = wall-clock now, like production)...
        processor.insertPending(bufferId, "lblx", "me", "dm body", null, MessageKind.PRIVMSG)
        // ...but the echo comes back WITHOUT the label (server dropped labeled-response mid-flight).
        // Its serverTime is the server clock, ~now (within the 30s echo window of the pending row).
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "srv3", time = System.currentTimeMillis(), label = null), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("me"), target = "bob", text = "dm body", isSelf = true, replyToMsgid = null,
        ))
        val rows = pagingList(bufferId).filter { it.text == "dm body" }
        assertEquals(1, rows.size)
        assertEquals("srv3", rows.single().msgid)
        assertNull(rows.single().pendingLabel)
    }

    @Test
    fun ownMessage_localInsertOnly_neitherEchoNorLabel_singleRow() = runTest {
        val bufferId = selfBuffer("#chan")
        // (c) No echo-message, no labeled-response: only the confirmed-local insert exists.
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = null, time = 1000, label = null), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("me"), target = "#chan", text = "lonely", isSelf = true, replyToMsgid = null,
        ))
        val rows = pagingList(bufferId).filter { it.text == "lonely" }
        assertEquals(1, rows.size)
        assertNull(rows.single().pendingLabel)
    }

    @Test
    fun successfulLegacyWriteConfirmsPendingAndCannotLaterTimeoutAsFailed() = runTest {
        val bufferId = selfBuffer("#chan")
        val eventId = processor.insertPending(
            bufferId, "local-attempt", "me", "legacy delivered", null, MessageKind.PRIVMSG,
        )

        processor.confirmIfStillPending(bufferId, "local-attempt")
        processor.failIfStillPending(bufferId, "local-attempt")

        val row = db.messageDao().byId(eventId)
        assertNull(row?.pendingLabel)
        assertFalse(row?.failed ?: true)
    }

    @Test
    fun ownMessage_bareEchoThenLaterHistoryWithMsgid_collapsesByMsgid() = runTest {
        // The remaining double-send: a self message confirmed by a BARE echo (no msgid) keeps a
        // msgid-less local row. A reconnect CHATHISTORY replay later delivers the SAME message with
        // a real msgid. Its arrival is later, but tagged server-time remains the original event
        // time, allowing conservative bounded reconciliation without matching a later repeat.
        val bufferId = selfBuffer("#chan")
        // 1. optimistic pending send.
        processor.insertPending(bufferId, "lblz", "me", "double me", null, MessageKind.PRIVMSG)
        // 2. bare echo (echo-message on, but this echo carries no draft/msgid) confirms in place.
        val originalServerTime = System.currentTimeMillis()
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = null, time = originalServerTime, label = "lblz"),
            kind = IrcEvent.ChatKind.PRIVMSG, source = Prefix("me"), target = "#chan",
            text = "double me", isSelf = true, replyToMsgid = null,
        ))
        assertEquals(1, pagingList(bufferId).count { it.text == "double me" })
        // 3. A much-later CHATHISTORY replay carries the original tagged server time and msgid.
        processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(
            IrcEvent.ChatMessage(
                ctx = ctx(msgid = "hist-msgid", time = originalServerTime, label = null),
                kind = IrcEvent.ChatKind.PRIVMSG, source = Prefix("me"), target = "#chan",
                text = "double me", isSelf = true, replyToMsgid = null,
            ),
        )))
        val rows = pagingList(bufferId).filter { it.text == "double me" }
        assertEquals(1, rows.size)
        assertEquals("hist-msgid", rows.single().msgid)
    }

    @Test
    fun ownMessage_distinctSelfSendsSameText_notMerged_outsideWindow() = runTest {
        val bufferId = selfBuffer("#chan")
        // Two genuinely separate self-sends of the same text far apart in time must stay separate.
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "a1", time = 1000, label = null), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("me"), target = "#chan", text = "same", isSelf = true, replyToMsgid = null,
        ))
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "a2", time = 1000 + 60_000, label = null), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("me"), target = "#chan", text = "same", isSelf = true, replyToMsgid = null,
        ))
        assertEquals(2, pagingList(bufferId).count { it.text == "same" })
    }

    // --- self-join idempotency across replays (bug 8) ---

    @Test
    fun selfJoin_idempotent_acrossReplaysAndReconnects() = runTest {
        // Live self-join (no msgid).
        processor.process(networkId, IrcEvent.Joined(ctx(msgid = null, time = 1000), "me", "#chan", null, null, isSelf = true))
        val bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
        // Buffer reopen → CHATHISTORY event-playback replays the self-join with a msgid + new time.
        processor.process(networkId, IrcEvent.Joined(ctx(msgid = "j-play", time = 1000), "me", "#chan", null, null, isSelf = true))
        // Reconnect → another live self-join, no msgid, different time.
        processor.process(networkId, IrcEvent.Joined(ctx(msgid = null, time = 9999), "me", "#chan", null, null, isSelf = true))
        val joins = pagingList(bufferId).filter { it.kind == MessageKind.JOIN }
        assertEquals(1, joins.size)
    }

    @Test
    fun otherJoin_notCollapsedBySelfJoinKey() = runTest {
        // A self-join and someone else's join to the same buffer must both appear.
        processor.process(networkId, IrcEvent.Joined(ctx(msgid = null, time = 1000), "me", "#chan", null, null, isSelf = true))
        processor.process(networkId, IrcEvent.Joined(ctx(msgid = "oj", time = 1001), "alice", "#chan", null, null, isSelf = false))
        val bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
        assertEquals(2, pagingList(bufferId).count { it.kind == MessageKind.JOIN })
    }

    @Test
    fun selfJoinAfterExplicitPartStartsNewMembershipCycle() = runTest {
        val firstJoin = IrcEvent.Joined(
            ctx(msgid = null, time = 1_000), "me", "#chan", null, null, true,
        )
        processor.process(
            networkId,
            firstJoin,
        )
        processor.process(
            networkId,
            IrcEvent.Parted(ctx(msgid = null, time = 2_000), "me", "#chan", "leaving", true),
        )
        processor.process(
            networkId,
            IrcEvent.Joined(ctx(msgid = null, time = 3_000), "me", "#chan", null, null, true),
        )
        processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(firstJoin)))

        val bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
        assertEquals(2, pagingList(bufferId).count { it.kind == MessageKind.JOIN })
        assertEquals(1, pagingList(bufferId).count { it.kind == MessageKind.PART })
    }

    @Test
    fun selfJoinAfterKickStartsNewCycleWithoutHistoryReplayCoalescingIt() = runTest {
        val firstJoin = IrcEvent.Joined(
            ctx(msgid = null, time = 1_000), "me", "#chan", null, null, true,
        )
        processor.process(networkId, firstJoin)
        processor.process(
            networkId,
            IrcEvent.Kicked(ctx(msgid = null, time = 2_000), "me", "#chan", "op", null, true),
        )
        processor.process(
            networkId,
            IrcEvent.Joined(ctx(msgid = null, time = 3_000), "me", "#chan", null, null, true),
        )
        processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(firstJoin)))

        val bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
        assertEquals(2, pagingList(bufferId).count { it.kind == MessageKind.JOIN })
        assertEquals(1, pagingList(bufferId).count { it.kind == MessageKind.KICK })
    }

    @Test
    fun historyFirstSelfJoinConvergesWithLaterLiveReconnectJoin() = runTest {
        val historyJoin = IrcEvent.Joined(
            ctx(msgid = "history-join", time = 1_000).copy(batchId = "history"),
            "me",
            "#chan",
            null,
            null,
            true,
        )
        processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(historyJoin)))
        processor.process(
            networkId,
            IrcEvent.Joined(ctx(msgid = null, time = 9_999), "me", "#chan", null, null, true),
        )

        val bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
        val joins = pagingList(bufferId).filter { it.kind == MessageKind.JOIN }
        assertEquals(1, joins.size)
        assertEquals("history-join", joins.single().msgid)
    }

    @Test
    fun selfPartAndMembershipCycleAdvanceRollbackTogether() = runTest {
        processor.process(
            networkId,
            IrcEvent.Joined(ctx(msgid = null, time = 1_000), "me", "#chan", null, null, true),
        )
        val bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
        db.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER fail_membership_cycle BEFORE UPDATE OF membershipCycle ON buffers
               BEGIN SELECT RAISE(ABORT, 'cycle failure'); END""",
        )

        val failure = runCatching {
            processor.process(
                networkId,
                IrcEvent.Parted(ctx(msgid = null, time = 2_000), "me", "#chan", null, true),
            )
        }

        assertTrue(failure.isFailure)
        assertEquals(0L, db.bufferDao().observeById(bufferId)?.membershipCycle)
        assertEquals(true, db.bufferDao().observeById(bufferId)?.joined)
        assertEquals(0, pagingList(bufferId).count { it.kind == MessageKind.PART })
    }

    @Test
    fun namesUserhost_convergesIdentityWithoutErasingOnPlainSnapshot() = runTest {
        processor.process(
            networkId,
            IrcEvent.Names(
                "#Room",
                listOf(IrcEvent.Names.Member("Nick", "@+", "~user", "host.example")),
            ),
        )
        val buffer = db.bufferDao().byName(networkId, "#room")!!
        assertEquals("@+", db.memberDao().observe(buffer.id).first().single().prefixes)
        val enriched = db.userDao().byNick(networkId, "nick")!!
        assertEquals("~user", enriched.username)
        assertEquals("~user@host.example", enriched.hostmask)

        processor.process(
            networkId,
            IrcEvent.Names("#room", listOf(IrcEvent.Names.Member("NICK", "@", null, null))),
        )
        assertEquals("~user@host.example", db.userDao().byNick(networkId, "nick")?.hostmask)

        processor.process(networkId, IrcEvent.HostChanged("Nick", "new", "cloak.example"))
        val changed = db.userDao().byNick(networkId, "nick")!!
        assertEquals("new@cloak.example", changed.hostmask)
    }

    @Test
    fun whoxRow_enrichesUserWithoutChangingMembershipPrefixes() = runTest {
        processor.process(
            networkId,
            IrcEvent.Names("#room", listOf(IrcEvent.Names.Member("Nick", "@+", null, null))),
        )
        processor.process(
            networkId,
            IrcEvent.WhoxRow(
                token = 12,
                username = "~user",
                host = "cloak.example",
                nick = "NICK",
                account = "account",
                flags = "G@",
                realname = "Real Name",
            ),
        )

        val user = db.userDao().byNick(networkId, "nick")!!
        assertEquals("~user", user.username)
        assertEquals("~user@cloak.example", user.hostmask)
        assertEquals("account", user.account)
        assertTrue(user.away)
        assertEquals("Real Name", user.realname)
        val buffer = db.bufferDao().byName(networkId, "#room")!!
        assertEquals("@+", db.memberDao().observe(buffer.id).first().single().prefixes)
    }

    @Test
    fun partialWhoxRow_updatesSafeIdentity_withoutInventingAwayOrErasingRealname() = runTest {
        processor.process(networkId, IrcEvent.AccountChanged("Nick", "old-account"))
        processor.process(networkId, IrcEvent.AwayChanged("Nick", "gone"))
        processor.process(networkId, IrcEvent.RealnameChanged("Nick", "Known Name"))

        processor.process(
            networkId,
            IrcEvent.WhoxRow(
                token = 13,
                username = "~new",
                host = "new.example",
                nick = "NICK",
                account = null,
                flags = null,
                realname = null,
            ),
        )

        val user = db.userDao().byNick(networkId, "nick")!!
        assertEquals("~new", user.username)
        assertEquals("~new@new.example", user.hostmask)
        assertEquals(null, user.account)
        assertTrue(user.away)
        assertEquals("Known Name", user.realname)
    }

    @Test
    fun namesSnapshot_replaysJoinThatArrivesBeforeEndOfNames() = runTest {
        processor.process(
            networkId,
            IrcEvent.Joined(ctx(), "me", "#room", null, null, isSelf = true),
        )
        processor.process(networkId, IrcEvent.NamesStarted("#room"))
        processor.process(
            networkId,
            IrcEvent.Joined(ctx(time = 1001), "Alice", "#room", null, null, isSelf = false),
        )
        processor.process(
            networkId,
            IrcEvent.Names("#room", listOf(IrcEvent.Names.Member("Bob", "", null, null))),
        )

        val buffer = db.bufferDao().byName(networkId, "#room")!!
        assertEquals(setOf("Alice", "Bob"), db.memberDao().allNow(buffer.id).map { it.nick }.toSet())
    }

    @Test
    fun namesSnapshot_replaysPrefixModeThatArrivesBeforeEndOfNames() = runTest {
        processor.onRegistered(
            networkId,
            "me",
            mapOf(
                "CASEMAPPING" to "rfc1459",
                "PREFIX" to "(qaohv)~&@%+",
                "CHANMODES" to "beI,k,l,imnst",
            ),
        )
        processor.process(networkId, IrcEvent.NamesStarted("#room"))
        processor.process(
            networkId,
            IrcEvent.ModeChanged(ctx(), "#room", "+o", listOf("Nick")),
        )
        processor.process(
            networkId,
            IrcEvent.Names("#room", listOf(IrcEvent.Names.Member("Nick", "", null, null))),
        )

        val buffer = db.bufferDao().byName(networkId, "#room")!!
        assertEquals("@", db.memberDao().allNow(buffer.id).single().prefixes)
    }

    @Test
    fun namesSnapshot_replaysOrderedRemovalAndRenameRaces_withoutDuplicateTimelineRows() = runTest {
        processor.process(
            networkId,
            IrcEvent.Joined(ctx(), "me", "#room", null, null, isSelf = true),
        )
        processor.process(networkId, IrcEvent.NamesStarted("#room"))
        processor.process(networkId, IrcEvent.Kicked(ctx("kick"), "Bob", "#room", "op", null, false))
        processor.process(networkId, IrcEvent.NickChanged(ctx("nick"), "Alice", "Alicia", false))
        processor.process(networkId, IrcEvent.Quit(ctx("quit"), "Carol", "gone"))
        processor.process(networkId, IrcEvent.Parted(ctx("part"), "Dave", "#room", null, false))
        processor.process(
            networkId,
            IrcEvent.Names(
                "#room",
                listOf("Alice", "Bob", "Carol", "Dave", "Eve").map {
                    IrcEvent.Names.Member(it, "", null, null)
                },
            ),
        )

        val buffer = db.bufferDao().byName(networkId, "#room")!!
        assertEquals(setOf("Alicia", "Eve"), db.memberDao().allNow(buffer.id).map { it.nick }.toSet())
        val rows = pagingList(buffer.id)
        assertEquals(1, rows.count { it.kind == MessageKind.KICK })
        assertEquals(1, rows.count { it.kind == MessageKind.NICK })
        assertEquals(1, rows.count { it.kind == MessageKind.QUIT })
        assertEquals(1, rows.count { it.kind == MessageKind.PART })
    }

    @Test
    fun selfPart_clearsDurableRoster() = runTest {
        processor.process(
            networkId,
            IrcEvent.Names("#room", listOf(IrcEvent.Names.Member("Nick", "@", null, null))),
        )
        processor.process(
            networkId,
            IrcEvent.Parted(ctx(), "me", "#room", null, isSelf = true),
        )

        val buffer = db.bufferDao().byName(networkId, "#room")!!
        assertTrue(db.memberDao().allNow(buffer.id).isEmpty())
        assertFalse(buffer.joined)
    }

    @Test
    fun monitorOnline_enrichesIdentityWithoutTimelineActivity() = runTest {
        processor.process(
            networkId,
            IrcEvent.MonitorOnline(listOf(Prefix("Nick", "~user", "cloak.example"))),
        )

        val user = db.userDao().byNick(networkId, "nick")!!
        assertEquals("~user", user.username)
        assertEquals("~user@cloak.example", user.hostmask)
        assertNull(db.bufferDao().byName(networkId, "nick"))
    }

    @Test
    fun monitorLimitExceeded_insertsOneDiagnosticForAllRejectedTargets() = runTest {
        processor.process(
            networkId,
            IrcEvent.MonitorLimitExceeded(1, listOf("Alice", "Bob"), "list is full"),
        )

        val server = db.bufferDao().byName(networkId, "*")!!
        val rows = pagingList(server.id)
        assertEquals(1, rows.size)
        assertEquals(MessageKind.ERROR, rows.single().kind)
        assertTrue(rows.single().text.contains("Alice,Bob"))
    }

    @Test
    fun networkBatches_fanOutMembershipAndOneOrderedPillPerChannelIdempotently() = runTest {
        processor.process(
            networkId,
            IrcEvent.Names(
                "#one",
                listOf(
                    IrcEvent.Names.Member("Alice", "@", null, null),
                    IrcEvent.Names.Member("Bob", "", null, null),
                ),
            ),
        )
        processor.process(
            networkId,
            IrcEvent.Names(
                "#two",
                listOf(IrcEvent.Names.Member("Alice", "", null, null)),
            ),
        )
        val split = IrcEvent.NetworkBatch(
            IrcEvent.NetworkBatchKind.NETSPLIT,
            "a.example",
            "b.example",
            listOf(
                IrcEvent.Quit(ctx("q1", 10_000).copy(batchId = "split"), "Alice", "split"),
                IrcEvent.Quit(ctx("q2", 10_001).copy(batchId = "split"), "Bob", "split"),
            ),
        )
        processor.process(networkId, split)
        processor.process(networkId, split)

        val one = db.bufferDao().byName(networkId, "#one")!!
        val two = db.bufferDao().byName(networkId, "#two")!!
        assertTrue(db.memberDao().allNow(one.id).isEmpty())
        assertTrue(db.memberDao().allNow(two.id).isEmpty())
        val oneRows = pagingList(one.id)
        val twoRows = pagingList(two.id)
        assertEquals(1, oneRows.count { it.kind == MessageKind.NETSPLIT })
        assertEquals(1, twoRows.count { it.kind == MessageKind.NETSPLIT })
        assertEquals(0, oneRows.count { it.kind == MessageKind.QUIT })
        assertEquals(
            listOf("Alice", "Bob"),
            NetworkBatchPayloadV1.decode(oneRows.single { it.kind == MessageKind.NETSPLIT }.eventPayload)?.nicks,
        )

        val join = IrcEvent.NetworkBatch(
            IrcEvent.NetworkBatchKind.NETJOIN,
            "a.example",
            "b.example",
            listOf(
                IrcEvent.Joined(
                    ctx("j1", 20_000).copy(batchId = "join"),
                    "Alice",
                    "#one",
                    null,
                    null,
                    false,
                ),
                IrcEvent.Joined(
                    ctx("j2", 20_001).copy(batchId = "join"),
                    "Bob",
                    "#one",
                    null,
                    null,
                    false,
                ),
            ),
        )
        processor.process(networkId, join)
        assertEquals(setOf("Alice", "Bob"), db.memberDao().allNow(one.id).map { it.nick }.toSet())
        assertEquals(1, pagingList(one.id).count { it.kind == MessageKind.NETJOIN })
        assertEquals(0, pagingList(one.id).count { it.kind == MessageKind.JOIN })
    }

    @Test
    fun malformedNetworkBatch_isIgnoredWithoutPartialMembershipMutation() = runTest {
        processor.process(
            networkId,
            IrcEvent.Names("#room", listOf(IrcEvent.Names.Member("Alice", "", null, null))),
        )
        processor.process(
            networkId,
            IrcEvent.NetworkBatch(
                IrcEvent.NetworkBatchKind.NETSPLIT,
                "a",
                "b",
                listOf(
                    IrcEvent.Quit(ctx(), "Alice", "split"),
                    IrcEvent.Joined(ctx(), "Bob", "#room", null, null, false),
                ),
            ),
        )

        val room = db.bufferDao().byName(networkId, "#room")!!
        assertEquals(listOf("Alice"), db.memberDao().allNow(room.id).map { it.nick })
        assertTrue(pagingList(room.id).none { it.kind == MessageKind.NETSPLIT })
    }

    @Test
    fun networkBatch_insertFailureRollsBackEveryMembershipMutation() = runTest {
        processor.process(
            networkId,
            IrcEvent.Names("#room", listOf(IrcEvent.Names.Member("Alice", "", null, null))),
        )
        db.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER reject_network_batch BEFORE INSERT ON messages
               WHEN NEW.kind = 'NETSPLIT'
               BEGIN SELECT RAISE(ABORT, 'forced batch insert failure'); END""",
        )

        val result = runCatching {
            processor.process(
                networkId,
                IrcEvent.NetworkBatch(
                    IrcEvent.NetworkBatchKind.NETSPLIT,
                    "a",
                    "b",
                    listOf(IrcEvent.Quit(ctx("rollback"), "Alice", "split")),
                ),
            )
        }

        assertTrue(result.isFailure)
        val room = db.bufferDao().byName(networkId, "#room")!!
        assertEquals(listOf("Alice"), db.memberDao().allNow(room.id).map { it.nick })
        assertTrue(pagingList(room.id).none { it.kind == MessageKind.NETSPLIT })
    }

    @Test
    fun networkBatch_withoutMsgidsPreservesDistinctTaggedOccurrences() = runTest {
        processor.process(
            networkId,
            IrcEvent.Names("#room", listOf(IrcEvent.Names.Member("Alice", "", null, null))),
        )
        fun split(time: Long) = IrcEvent.NetworkBatch(
            IrcEvent.NetworkBatchKind.NETSPLIT,
            "a.example",
            "b.example",
            listOf(IrcEvent.Quit(ctx(msgid = null, time = time).copy(batchId = "split"), "Alice", "split")),
            target = "#room",
        )

        processor.process(networkId, split(31_000))
        processor.process(networkId, split(32_000))
        processor.process(networkId, split(61_000))

        val room = db.bufferDao().byName(networkId, "#room")!!
        assertEquals(3, pagingList(room.id).count { it.kind == MessageKind.NETSPLIT })
    }

    @Test
    fun hundredUserSplit_createsOnePillAndNoPerNickQuitRows() = runTest {
        val nicks = (1..100).map { "Nick$it" }
        processor.process(
            networkId,
            IrcEvent.Names("#large", nicks.map { IrcEvent.Names.Member(it, "", null, null) }),
        )
        processor.process(
            networkId,
            IrcEvent.NetworkBatch(
                IrcEvent.NetworkBatchKind.NETSPLIT,
                "a",
                "b",
                nicks.mapIndexed { index, nick ->
                    IrcEvent.Quit(
                        ctx("q$index", 30_000L + index).copy(batchId = "split"),
                        nick,
                        "split",
                    )
                },
            ),
        )

        val large = db.bufferDao().byName(networkId, "#large")!!
        val rows = pagingList(large.id)
        assertTrue(db.memberDao().allNow(large.id).isEmpty())
        assertEquals(1, rows.count { it.kind == MessageKind.NETSPLIT })
        assertEquals(0, rows.count { it.kind == MessageKind.QUIT })
        assertEquals(
            nicks,
            NetworkBatchPayloadV1.decode(rows.single { it.kind == MessageKind.NETSPLIT }.eventPayload)?.nicks,
        )
    }

    // --- invitations ------------------------------------------------------

    @Test
    fun selfInvite_createsUnjoinedChannelRow_andNotifiesOnce() = runTest {
        val notified = mutableListOf<Triple<Long, Long, Long>>()
        val resolved = mutableListOf<Long>()
        val recording = EventProcessor(db, TypingTrackerImpl(), object : MessageNotifier {
            override suspend fun onIncoming(
                networkId: Long,
                bufferId: Long,
                type: BufferType,
                hasMention: Boolean,
                message: IrcEvent.ChatMessage,
            ) = Unit

            override suspend fun onInvitation(networkId: Long, bufferId: Long, messageId: Long) {
                notified += Triple(networkId, bufferId, messageId)
            }

            override suspend fun onInvitationResolved(messageId: Long) {
                resolved += messageId
            }
        })
        recording.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))
        val invite = IrcEvent.Invited(ctx("invite-1"), "alice", "ME", "#Secret")

        recording.process(networkId, invite)
        recording.process(networkId, invite)

        val buffer = db.bufferDao().byName(networkId, "#secret")!!
        assertFalse(buffer.joined)
        assertEquals(BufferType.CHANNEL, buffer.type)
        val row = pagingList(buffer.id).single()
        assertEquals(MessageKind.INVITE, row.kind)
        assertEquals(InviteState.PENDING, row.inviteState)
        assertEquals(InvitePayloadV1("alice", "ME", "#Secret"), InvitePayloadV1.decode(row.eventPayload))
        assertEquals(1, notified.size)
        assertEquals(row.id, notified.single().third)

        recording.process(
            networkId,
            IrcEvent.Joined(ctx("join-echo", 2_000), "me", "#Secret", null, null, isSelf = true),
        )
        assertEquals(InviteState.JOINED, db.messageDao().byId(row.id)?.inviteState)
        assertEquals(listOf(row.id), resolved)

        // A later socket replay or push delivery must not revive the resolved card.
        recording.process(networkId, invite)
        assertEquals(InviteState.JOINED, db.messageDao().byId(row.id)?.inviteState)
        assertEquals(1, notified.size)
    }

    @Test
    fun msgidlessInvite_preservesDistinctTaggedOccurrences() = runTest {
        val first = IrcEvent.Invited(ctx(msgid = null, time = 31_000), "alice", "me", "#fallback")
        val duplicate = first.copy(ctx = first.ctx.copy(serverTime = 32_000))
        val later = first.copy(ctx = first.ctx.copy(serverTime = 61_000))

        processor.process(networkId, first)
        processor.process(networkId, duplicate)
        processor.process(networkId, later)

        val buffer = db.bufferDao().byName(networkId, "#fallback")!!
        assertEquals(3, pagingList(buffer.id).count { it.kind == MessageKind.INVITE })
    }

    @Test
    fun historyLiveAndPushInvitePermutationsConvergeToOneActionableEvent() = runTest {
        val notifications = mutableListOf<Long>()
        val recording = EventProcessor(
            db,
            TypingTrackerImpl(),
            object : MessageNotifier {
                override suspend fun onIncoming(
                    networkId: Long,
                    bufferId: Long,
                    type: BufferType,
                    hasMention: Boolean,
                    message: IrcEvent.ChatMessage,
                ) = Unit

                override suspend fun onInvitation(networkId: Long, bufferId: Long, messageId: Long) {
                    notifications += messageId
                }
            },
        )
        recording.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))

        enumValues<InviteDeliveryOrder>().forEachIndexed { index, order ->
            val channel = "#invite-order-$index"
            val msgid = "invite-order-$index"
            val live = IrcEvent.Invited(ctx(msgid, 20_000L + index), "alice", "me", channel)
            val history = live.copy(ctx = live.ctx.copy(batchId = "invite-history-$index"))
            when (order) {
                InviteDeliveryOrder.HISTORY_LIVE -> {
                    recording.process(networkId, IrcEvent.HistoryBatch(channel, listOf(history)))
                    recording.process(networkId, live)
                }
                InviteDeliveryOrder.LIVE_HISTORY -> {
                    recording.process(networkId, live)
                    recording.process(networkId, IrcEvent.HistoryBatch(channel, listOf(history)))
                }
                InviteDeliveryOrder.HISTORY_PUSH -> {
                    recording.process(networkId, IrcEvent.HistoryBatch(channel, listOf(history)))
                    recording.processPush(networkId, live)
                }
                InviteDeliveryOrder.PUSH_HISTORY -> {
                    recording.processPush(networkId, live)
                    recording.process(networkId, IrcEvent.HistoryBatch(channel, listOf(history)))
                }
            }

            val room = db.bufferDao().byName(networkId, channel)!!
            val row = pagingList(room.id).single { it.msgid == msgid }
            assertEquals(InviteState.PENDING, row.inviteState)
            assertEquals(1, notifications.count { it == row.id })
        }
    }

    @Test
    fun invalidSelfInvite_isInformationalInServerBuffer_withoutActions() = runTest {
        processor.process(networkId, IrcEvent.Invited(ctx("invalid-invite"), "alice", "me", "not a channel"))

        val server = db.bufferDao().byName(networkId, "*")!!
        val row = pagingList(server.id).single { it.kind == MessageKind.INVITE }
        assertEquals(InviteState.HISTORICAL, row.inviteState)
        assertTrue(row.text.contains("invalid invitation"))
    }

    @Test
    fun inviteContributesPreviewAndActivity_butNeverUnread() = runTest {
        processor.process(networkId, IrcEvent.Invited(ctx("preview-invite", 9_000), "alice", "me", "#preview"))

        val row = db.bufferDao().observeChatList().first().single { it.displayName == "#preview" }
        assertEquals("alice invited you to #preview", row.lastMessageText)
        assertEquals(9_000L, row.lastMessageTime)
        assertEquals(0, row.unreadCount)
        assertEquals(0, row.mentionCount)
    }

    @Test
    fun thirdPartyInvite_routesToExistingChannel_withoutNotification() = runTest {
        processor.process(networkId, IrcEvent.Joined(ctx(), "me", "#ops", null, null, isSelf = true))
        processor.process(networkId, IrcEvent.Invited(ctx("invite-ops", 2_000), "oper", "bob", "#ops"))

        val buffer = db.bufferDao().byName(networkId, "#ops")!!
        val row = pagingList(buffer.id).first { it.kind == MessageKind.INVITE }
        assertEquals(InviteState.HISTORICAL, row.inviteState)
        assertEquals("oper invited bob to #ops", row.text)
    }

    @Test
    fun historyInvite_isCompactAndNeverNotifies() = runTest {
        val batch = IrcEvent.HistoryBatch(
            "#old",
            listOf(IrcEvent.Invited(ctx("history-invite"), "alice", "me", "#old")),
        )
        processor.process(networkId, batch)

        val buffer = db.bufferDao().byName(networkId, "#old")!!
        assertEquals(InviteState.HISTORICAL, pagingList(buffer.id).single().inviteState)
    }

    @Test
    fun inviteState_claimAndDismiss_areAtomicAndJoinDoesNotReviveDismissed() = runTest {
        processor.process(networkId, IrcEvent.Invited(ctx("invite-state"), "alice", "me", "#state"))
        val buffer = db.bufferDao().byName(networkId, "#state")!!
        val row = pagingList(buffer.id).single()

        assertEquals(
            1,
            db.messageDao().compareAndSetInviteState(row.id, InviteState.PENDING, InviteState.JOINING),
        )
        assertEquals(
            0,
            db.messageDao().compareAndSetInviteState(row.id, InviteState.PENDING, InviteState.JOINING),
        )
        assertEquals(1, db.messageDao().dismissInvite(row.id))
        processor.process(
            networkId,
            IrcEvent.Joined(ctx("late-join"), "me", "#state", null, null, isSelf = true),
        )
        assertEquals(InviteState.DISMISSED, db.messageDao().byId(row.id)?.inviteState)
    }

    @Test
    fun joinRejection_marksOnlyMatchingJoiningInviteFailed_withReason() = runTest {
        processor.process(networkId, IrcEvent.Invited(ctx("reject-a"), "alice", "me", "#locked"))
        processor.process(networkId, IrcEvent.Invited(ctx("reject-b"), "bob", "me", "#other"))
        val locked = db.bufferDao().byName(networkId, "#locked")!!
        val other = db.bufferDao().byName(networkId, "#other")!!
        val lockedRow = pagingList(locked.id).single()
        val otherRow = pagingList(other.id).single()
        db.messageDao().compareAndSetInviteState(lockedRow.id, InviteState.PENDING, InviteState.JOINING)
        db.messageDao().compareAndSetInviteState(otherRow.id, InviteState.PENDING, InviteState.JOINING)

        processor.process(
            networkId,
            IrcEvent.ServerError("473", listOf("me", "#LOCKED"), "Cannot join channel (+i)"),
        )

        val failed = db.messageDao().byId(lockedRow.id)!!
        assertEquals(InviteState.FAILED, failed.inviteState)
        assertTrue(failed.text.contains("Cannot join channel (+i)"))
        assertEquals(InviteState.JOINING, db.messageDao().byId(otherRow.id)?.inviteState)
    }

    @Test
    fun disconnect_marksAllJoiningInvitesOnNetworkFailed() = runTest {
        processor.process(networkId, IrcEvent.Invited(ctx("disconnect-a"), "alice", "me", "#one"))
        processor.process(networkId, IrcEvent.Invited(ctx("disconnect-b"), "bob", "me", "#two"))
        val rows = listOf("#one", "#two").map { channel ->
            pagingList(db.bufferDao().byName(networkId, channel)!!.id).single()
        }
        rows.forEach {
            db.messageDao().compareAndSetInviteState(it.id, InviteState.PENDING, InviteState.JOINING)
        }

        processor.process(networkId, IrcEvent.Disconnected("network lost"))

        rows.forEach {
            val failed = db.messageDao().byId(it.id)!!
            assertEquals(InviteState.FAILED, failed.inviteState)
            assertTrue(failed.text.contains("network lost"))
        }
    }

    @Test
    fun history_pushedThroughInOneBatch_isIdempotent() = runTest {
        val batch = IrcEvent.HistoryBatch("#chan", listOf(
            IrcEvent.ChatMessage(ctx(msgid = "h1"), IrcEvent.ChatKind.PRIVMSG, Prefix("alice"), "#chan", "one", false, null),
            IrcEvent.ChatMessage(ctx(msgid = "h2"), IrcEvent.ChatKind.PRIVMSG, Prefix("alice"), "#chan", "two", false, null),
        ))
        processor.process(networkId, batch)
        processor.process(networkId, batch) // replay overlap → IGNORE
        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        assertEquals(2, pagingList(buffer.id).size)
    }

    @Test
    fun historyBatch_identicalMsgidlessTextAtDifferentTaggedTimes_replaysIdempotently() = runTest {
        val batch = IrcEvent.HistoryBatch(
            "#chan",
            listOf(
                IrcEvent.ChatMessage(
                    ctx(msgid = null, time = 10_000),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("alice"),
                    "#chan",
                    "same text",
                    false,
                    null,
                ),
                IrcEvent.ChatMessage(
                    ctx(msgid = null, time = 11_000),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("alice"),
                    "#chan",
                    "same text",
                    false,
                    null,
                ),
            ),
        )

        processor.process(networkId, batch)
        processor.process(networkId, batch)

        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        val rows = pagingList(buffer.id)
        assertEquals(2, rows.size)
        assertEquals(setOf(10_000L, 11_000L), rows.map { it.serverTime }.toSet())
    }

    @Test
    fun historyBatch_identicalMsgidlessSystemEventsPreserveMultiplicityAndReplay() = runTest {
        val mode = IrcEvent.ModeChanged(
            ctx(msgid = null, time = 12_000).copy(batchId = "history-mode"),
            "#chan",
            "+m",
            emptyList(),
        )
        val batch = IrcEvent.HistoryBatch("#chan", listOf(mode, mode))

        processor.process(networkId, batch)
        processor.process(networkId, batch)

        val bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
        val modes = pagingList(bufferId).filter { it.kind == MessageKind.MODE }
        assertEquals(2, modes.size)
    }

    @Test
    fun historyBatch_persistsMentionsAndDms_withoutNotifications() = runTest {
        val notifications = mutableListOf<String>()
        val recording = EventProcessor(db, TypingTrackerImpl(), object : MessageNotifier {
            override suspend fun onIncoming(
                networkId: Long,
                bufferId: Long,
                type: BufferType,
                hasMention: Boolean,
                message: IrcEvent.ChatMessage,
            ) {
                notifications += message.text
            }
        })
        recording.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))
        recording.process(
            networkId,
            IrcEvent.HistoryBatch(
                "#chan",
                listOf(
                    IrcEvent.ChatMessage(
                        ctx("history-mention", 1_000),
                        IrcEvent.ChatKind.PRIVMSG,
                        Prefix("alice"),
                        "#chan",
                        "hello me",
                        false,
                        null,
                    ),
                    IrcEvent.ChatMessage(
                        ctx("history-dm", 1_001),
                        IrcEvent.ChatKind.PRIVMSG,
                        Prefix("bob"),
                        "me",
                        "old direct message",
                        false,
                        null,
                    ),
                ),
            ),
        )

        assertTrue(pagingList(db.bufferDao().byName(networkId, "#chan")!!.id).isNotEmpty())
        assertTrue(pagingList(db.bufferDao().byName(networkId, "bob")!!.id).isNotEmpty())
        assertTrue(notifications.isEmpty())

        recording.process(
            networkId,
            IrcEvent.ChatMessage(
                ctx("live-dm", 1_002),
                IrcEvent.ChatKind.PRIVMSG,
                Prefix("bob"),
                "me",
                "live direct message",
                false,
                null,
            ),
        )
        assertEquals(listOf("live direct message"), notifications)
    }

    @Test
    fun historyBatch_persistsTimeline_withoutMutatingCurrentSessionState() = runTest {
        val notifications = mutableListOf<String>()
        val reads = mutableListOf<Pair<Long, Long>>()
        val inviteNotifications = mutableListOf<Long>()
        val resolvedInvites = mutableListOf<Long>()
        val typing = TypingTrackerImpl()
        val recording = EventProcessor(db, typing, object : MessageNotifier {
            override suspend fun onIncoming(
                networkId: Long,
                bufferId: Long,
                type: BufferType,
                hasMention: Boolean,
                message: IrcEvent.ChatMessage,
            ) {
                notifications += message.text
            }

            override suspend fun onRead(
                bufferId: Long,
                anchor: io.github.trevarj.motd.data.db.TimelineAnchor,
            ) {
                reads += bufferId to anchor.serverTime
            }

            override suspend fun onInvitation(networkId: Long, bufferId: Long, messageId: Long) {
                inviteNotifications += messageId
            }

            override suspend fun onInvitationResolved(messageId: Long) {
                resolvedInvites += messageId
            }
        })
        recording.onRegistered(
            networkId,
            "me",
            mapOf(
                "CASEMAPPING" to "rfc1459",
                "PREFIX" to "(ov)@+",
                "CHANMODES" to "beI,k,l,imnst",
            ),
        )
        recording.process(networkId, IrcEvent.Joined(ctx("self-live", 10_000), "me", "#room", null, null, true))
        recording.process(
            networkId,
            IrcEvent.Names(
                "#room",
                listOf(
                    IrcEvent.Names.Member("me", "@", "self", "current.example"),
                    IrcEvent.Names.Member("Alice", "+", "alice", "current.example"),
                    IrcEvent.Names.Member("Bob", "", "bob", "current.example"),
                ),
            ),
        )
        recording.process(networkId, IrcEvent.TopicChanged(ctx("topic-live", 10_001), "#room", "current topic", "op"))
        recording.process(networkId, IrcEvent.ReadMarker("#room", 10_002))
        recording.process(networkId, IrcEvent.Invited(ctx("invite-live", 10_003), "oper", "me", "#room"))
        recording.process(
            networkId,
            IrcEvent.ChatMessage(ctx("relation-parent", 10_004), IrcEvent.ChatKind.PRIVMSG, Prefix("me"), "#room", "parent", true, null),
        )

        val bufferBefore = db.bufferDao().byName(networkId, "#room")!!
        val membersBefore = db.memberDao().allNow(bufferBefore.id).sortedBy { it.nick }
        val aliceBefore = db.userDao().byNick(networkId, "alice")
        val inviteBefore = pagingList(bufferBefore.id).single { it.msgid == "invite-live" }
        assertEquals(
            1,
            db.messageDao().compareAndSetInviteState(inviteBefore.id, InviteState.PENDING, InviteState.JOINING),
        )
        db.networkDao().update(db.networkDao().byId(networkId)!!.copy(role = NetworkRole.BOUNCER_ROOT))
        val networksBefore = db.networkDao().allNow()
        reads.clear()
        inviteNotifications.clear()

        val history = IrcEvent.HistoryBatch(
            "#room",
            listOf(
                IrcEvent.ChatMessage(ctx("history-chat", 1_000), IrcEvent.ChatKind.PRIVMSG, Prefix("Alice"), "#room", "old me mention", false, null),
                IrcEvent.Joined(ctx("history-join", 1_001), "Eve", "#room", "old-account", "Old Eve", false),
                IrcEvent.Parted(ctx("history-part", 1_002), "Alice", "#room", "old part", false),
                IrcEvent.Kicked(ctx("history-kick", 1_003), "Bob", "#room", "old-op", "old kick", false),
                IrcEvent.Quit(ctx("history-quit", 1_004), "Alice", "old quit"),
                IrcEvent.NickChanged(ctx("history-nick", 1_005), "me", "old-me", true),
                IrcEvent.ModeChanged(ctx("history-mode", 1_006), "#room", "-o", listOf("me")),
                IrcEvent.TopicChanged(ctx("history-topic", 1_007), "#room", "old topic", "old-op"),
                IrcEvent.AwayChanged("Alice", "old away"),
                IrcEvent.AccountChanged("Alice", "old-account"),
                IrcEvent.HostChanged("Alice", "old-user", "old.example"),
                IrcEvent.RealnameChanged("Alice", "Old Alice"),
                IrcEvent.TagMessage(ctx("history-typing", 1_007), Prefix("Mallory"), "#room", "active", null, null),
                IrcEvent.TagMessage(ctx("history-react-bob", 1_007), Prefix("Bob"), "#room", null, "👍", "relation-parent"),
                IrcEvent.TagMessage(ctx("history-react-alice", 1_007), Prefix("Alice"), "#room", null, "👍", "relation-parent"),
                IrcEvent.Raw(IrcMessage.parse("@+draft/unreact=👍;+reply=relation-parent :Alice!u@h TAGMSG #room")),
                IrcEvent.NamesStarted("#room"),
                IrcEvent.Names("#room", listOf(IrcEvent.Names.Member("Mallory", "@", "mallory", "old.example"))),
                IrcEvent.ReadMarker("#room", 20_000),
                IrcEvent.BouncerNetworkState("old-net", mapOf("name" to "Old network")),
                IrcEvent.Invited(ctx("history-invite", 1_008), "old-oper", "me", "#room"),
                IrcEvent.Joined(ctx("history-self-join", 1_009), "me", "#room", null, null, true),
                IrcEvent.Disconnected("old disconnect"),
                IrcEvent.NetworkBatch(
                    IrcEvent.NetworkBatchKind.NETSPLIT,
                    "old-a.example",
                    "old-b.example",
                    listOf(IrcEvent.Quit(ctx("history-split", 1_010), "Bob", "split")),
                    target = "#room",
                ),
            ),
        )
        recording.process(networkId, history)
        recording.process(networkId, history)

        assertEquals(bufferBefore, db.bufferDao().byName(networkId, "#room"))
        assertEquals(membersBefore, db.memberDao().allNow(bufferBefore.id).sortedBy { it.nick })
        assertEquals(aliceBefore, db.userDao().byNick(networkId, "alice"))
        assertNull(db.userDao().byNick(networkId, "eve"))
        assertNull(db.userDao().byNick(networkId, "mallory"))
        assertEquals(networksBefore, db.networkDao().allNow())
        assertEquals(InviteState.JOINING, db.messageDao().byId(inviteBefore.id)?.inviteState)
        assertTrue(notifications.isEmpty())
        assertTrue(reads.isEmpty())
        assertTrue(inviteNotifications.isEmpty())
        assertTrue(resolvedInvites.isEmpty())
        assertTrue(typing.typingNicks(bufferBefore.id).value.isEmpty())
        assertEquals(
            listOf("Bob"),
            db.reactionDao().observeFor(bufferBefore.id, listOf("relation-parent")).first().map { it.sender },
        )

        val rows = pagingList(bufferBefore.id)
        listOf(
            MessageKind.PRIVMSG,
            MessageKind.JOIN,
            MessageKind.PART,
            MessageKind.KICK,
            MessageKind.QUIT,
            MessageKind.NICK,
            MessageKind.MODE,
            MessageKind.TOPIC,
            MessageKind.INVITE,
            MessageKind.NETSPLIT,
        ).forEach { kind -> assertTrue("missing historical $kind row", rows.any { it.kind == kind }) }
        assertEquals(1, rows.count { it.msgid == "history-chat" })
        assertEquals(1, rows.count { it.msgid == "history-join" })
        assertEquals(InviteState.HISTORICAL, rows.single { it.msgid == "history-invite" }.inviteState)

        recording.process(
            networkId,
            IrcEvent.ChatMessage(ctx("post-history", 30_000), IrcEvent.ChatKind.PRIVMSG, Prefix("Alice"), "#room", "hello me", false, null),
        )
        assertEquals(listOf("hello me"), notifications)
    }

    @Test
    fun protocolPageUsesPrimaryMetadataForCursorAndRestoresGenericHistoryWrites() = runTest {
        val context = IrcEvent.ChatMessage(
            ctx("context", 50), IrcEvent.ChatKind.PRIVMSG, Prefix("alice"), "#page",
            "context", false, null,
        )
        val primary = IrcEvent.ChatMessage(
            ctx("PrimaryCase", 100), IrcEvent.ChatKind.PRIVMSG, Prefix("alice"), "#page",
            "primary", false, null,
        )
        processor.persistHistoryPage(
            networkId,
            ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#page", limit = 50),
            ChatHistoryResponse.Messages(
                events = listOf(context, primary),
                oldest = ChatHistoryReference("PrimaryCase", 100),
                newest = ChatHistoryReference("PrimaryCase", 100),
                endOfHistory = false,
                primaryMessageCount = 1,
            ),
        )

        val page = db.bufferDao().byName(networkId, "#page")!!
        assertEquals(
            HistoryCursorEntity(page.id, "PrimaryCase", 100, "PrimaryCase", 100, false),
            db.historyCursorDao().byRoom(page.id),
        )

        processor.process(
            networkId,
            IrcEvent.HistoryBatch(
                "#other",
                listOf(
                    IrcEvent.ChatMessage(
                        ctx("other", 25), IrcEvent.ChatKind.PRIVMSG, Prefix("bob"), "#other",
                        "other", false, null,
                    ),
                ),
            ),
        )
        val other = db.bufferDao().byName(networkId, "#other")!!
        assertEquals("other", db.historyCursorDao().byRoom(other.id)?.oldestMsgid)
        assertEquals("PrimaryCase", db.historyCursorDao().byRoom(page.id)?.oldestMsgid)
    }

    @Test
    fun protocolPageDoesNotRecreateDeletedExpectedRoom() = runTest {
        val room = BufferStore(db).getOrCreate(networkId, "#gone", "#gone", BufferType.CHANNEL)
        db.bufferDao().deleteBuffer(room.id)

        val result = runCatching {
            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#gone", limit = 50),
                ChatHistoryResponse.Messages(
                    events = listOf(
                        IrcEvent.ChatMessage(
                            ctx("late", 100), IrcEvent.ChatKind.PRIVMSG, Prefix("alice"),
                            "#gone", "too late", false, null,
                        ),
                    ),
                    oldest = ChatHistoryReference("late", 100),
                    newest = ChatHistoryReference("late", 100),
                    endOfHistory = true,
                    primaryMessageCount = 1,
                ),
                expectedRoomId = room.id,
            )
        }

        assertTrue(result.isFailure)
        assertEquals(null, db.bufferDao().byName(networkId, "#gone"))
    }

    @Test
    fun protocolPageCompletionIsDirectional() = runTest {
        val empty = ChatHistoryResponse.Messages(
            events = emptyList(),
            oldest = null,
            newest = null,
            endOfHistory = true,
            primaryMessageCount = 0,
        )
        val incompleteRequests = listOf(
            ChatHistoryRequest(ChatHistoryRequest.Subcommand.AFTER, "#after", bound1 = "msgid=x", limit = 50),
            ChatHistoryRequest(ChatHistoryRequest.Subcommand.AROUND, "#around", bound1 = "msgid=x", limit = 50),
            ChatHistoryRequest(
                ChatHistoryRequest.Subcommand.BETWEEN,
                "#between",
                bound1 = "msgid=x",
                bound2 = "msgid=y",
                limit = 50,
            ),
            ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#bounded", bound1 = "msgid=x", limit = 50),
        )

        incompleteRequests.forEach { request ->
            val roomId = processor.persistHistoryPage(networkId, request, empty)
            assertFalse(db.bufferDao().observeById(roomId)!!.historyComplete)
            assertFalse(db.historyCursorDao().byRoom(roomId)!!.historyComplete)
        }

        listOf(
            ChatHistoryRequest(ChatHistoryRequest.Subcommand.BEFORE, "#before", bound1 = "msgid=x", limit = 50),
            ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#latest", limit = 50),
        ).forEach { request ->
            val roomId = processor.persistHistoryPage(networkId, request, empty)
            assertTrue(db.bufferDao().observeById(roomId)!!.historyComplete)
            assertTrue(db.historyCursorDao().byRoom(roomId)!!.historyComplete)
        }
    }

    @Test
    fun protocolPagePreservesPostMergeWinnerCursorExtents() = runTest {
        val store = BufferStore(db)
        val winner = store.getOrCreate(networkId, "alice", "Alice", BufferType.QUERY)
        store.bindQueryIdentity(winner.id, networkId, "alice", "Alice", "shared-account")
        val loser = store.getOrCreate(networkId, "bob", "Bob", BufferType.QUERY)
        db.historyCursorDao().upsert(
            HistoryCursorEntity(
                roomId = winner.id,
                newestMsgid = "winner-newest",
                newestServerTime = 1_000,
                oldestMsgid = "winner-oldest",
                oldestServerTime = 100,
            ),
        )
        db.historyCursorDao().upsert(
            HistoryCursorEntity(
                roomId = loser.id,
                newestMsgid = "loser-newest",
                newestServerTime = 800,
                oldestMsgid = "loser-oldest",
                oldestServerTime = 300,
            ),
        )
        val historyMessage = IrcEvent.ChatMessage(
            ctx(msgid = "page", time = 200, account = "shared-account"),
            IrcEvent.ChatKind.PRIVMSG,
            Prefix("Bob"),
            "me",
            "merged history",
            false,
            null,
        )
        val historyNick = IrcEvent.NickChanged(
            ctx(msgid = "page-context", time = 201),
            from = "Bob",
            to = "Robert",
            isSelf = false,
        )

        val roomId = processor.persistHistoryPage(
            networkId,
            ChatHistoryRequest(
                ChatHistoryRequest.Subcommand.BEFORE,
                "bob",
                bound1 = "msgid=loser-oldest",
                limit = 50,
            ),
            ChatHistoryResponse.Messages(
                events = listOf(historyMessage, historyNick),
                oldest = ChatHistoryReference("page", 200),
                newest = ChatHistoryReference("page", 200),
                endOfHistory = false,
                primaryMessageCount = 1,
            ),
        )

        assertEquals(winner.id, roomId)
        val cursor = db.historyCursorDao().byRoom(winner.id)
        assertEquals("winner-oldest", cursor?.oldestMsgid)
        assertEquals(100L, cursor?.oldestServerTime)
        assertEquals("winner-newest", cursor?.newestMsgid)
        assertEquals(1_000L, cursor?.newestServerTime)
        assertEquals(winner.id, db.messageDao().byMsgid(winner.id, "page-context")?.bufferId)
        assertEquals(null, db.messageDao().byMsgid(loser.id, "page-context"))
        assertNull(db.historyCursorDao().byRoom(loser.id))
    }

    @Test
    fun push_persistsSupportedMessage_withoutMutatingSessionState() = runTest {
        val notifications = mutableListOf<String>()
        val recording = EventProcessor(db, TypingTrackerImpl(), object : MessageNotifier {
            override suspend fun onIncoming(
                networkId: Long,
                bufferId: Long,
                type: BufferType,
                hasMention: Boolean,
                message: IrcEvent.ChatMessage,
            ) {
                notifications += message.text
            }
        })
        recording.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))
        recording.process(networkId, IrcEvent.Joined(ctx("push-seed", 5_000), "me", "#push", null, null, true))
        recording.process(networkId, IrcEvent.TopicChanged(ctx("push-topic-live", 5_001), "#push", "current", "op"))
        recording.process(networkId, IrcEvent.ReadMarker("#push", 5_002))
        val before = db.bufferDao().byName(networkId, "#push")!!
        val membersBefore = db.memberDao().allNow(before.id)

        recording.processPush(networkId, IrcEvent.Joined(ctx("push-join", 6_000), "Mallory", "#push", null, null, false))
        recording.processPush(networkId, IrcEvent.TopicChanged(ctx("push-topic", 6_001), "#push", "pushed", "mallory"))
        recording.processPush(networkId, IrcEvent.ReadMarker("#push", 6_002))
        recording.processPush(
            networkId,
            IrcEvent.ChatMessage(
                ctx("push-chat", 6_003),
                IrcEvent.ChatKind.PRIVMSG,
                Prefix("Alice"),
                "#push",
                "hello me",
                false,
                null,
            ),
        )

        assertEquals(before, db.bufferDao().byName(networkId, "#push"))
        assertEquals(membersBefore, db.memberDao().allNow(before.id))
        assertEquals(listOf("hello me"), notifications)
        assertEquals(1, pagingList(before.id).count { it.msgid == "push-chat" })
        assertTrue(pagingList(before.id).none { it.msgid == "push-join" || it.msgid == "push-topic" })
    }

    @Test
    fun concurrentLiveHistoryAndPushFirstContact_convergesOnOneLogicalMessage() = runTest {
        val start = CompletableDeferred<Unit>()
        val message = IrcEvent.ChatMessage(
            ctx("concurrent-message", 50_000),
            IrcEvent.ChatKind.PRIVMSG,
            Prefix("Alice"),
            "#concurrent",
            "one logical message",
            false,
            null,
        )
        listOf(
            async { start.await(); processor.process(networkId, message) },
            async {
                start.await()
                processor.process(networkId, IrcEvent.HistoryBatch("#concurrent", listOf(message)))
            },
            async { start.await(); processor.processPush(networkId, message) },
        ).also { jobs ->
            start.complete(Unit)
            jobs.awaitAll()
        }

        val buffer = db.bufferDao().byName(networkId, "#concurrent")!!
        assertEquals(1, pagingList(buffer.id).count { it.msgid == "concurrent-message" })
        assertEquals(1, db.bufferDao().observeChatList().first().count { it.bufferId == buffer.id })
    }

    @Test
    fun networkEvictionAndShutdown_releaseProcessorSequencers() = runTest {
        assertEquals(1, processor.sequencerSize())
        processor.evictNetwork(networkId)
        assertEquals(0, processor.sequencerSize())

        processor.process(
            networkId,
            IrcEvent.ChatMessage(ctx("after-evict"), IrcEvent.ChatKind.PRIVMSG, Prefix("Alice"), "#room", "hi", false, null),
        )
        assertEquals(1, processor.sequencerSize())
        processor.shutdown()
        assertEquals(0, processor.sequencerSize())
    }
}
