package io.github.trevarj.motd.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import io.github.trevarj.motd.irc.proto.IrcMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventProcessorTest {
    private lateinit var db: MotdDatabase
    private lateinit var processor: EventProcessor
    private var networkId: Long = 0

    private fun ctx(msgid: String? = null, time: Long = 1000, label: String? = null) =
        MessageContext(msgid = msgid, serverTime = time, account = null, batchId = null, label = label)

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
            IrcMessage.parse("@+draft/unreact=👍;+reply=m1 :bob!u@h TAGMSG #chan"),
        )

        processor.process(networkId, unreact)
        processor.process(networkId, unreact)

        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        assertTrue(db.reactionDao().observeFor(buffer.id, listOf("m1")).first().isEmpty())
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
    fun ownReactionEcho_reconcilesOntoOptimisticRow_withoutDuplicating() = runTest {
        // Auto-create the buffer and seed the optimistic own-reaction row the way sendReact does
        // (upsert keyed by bufferId+targetMsgid+sender) before the server echo arrives.
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = "m1"), kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"), target = "#chan", text = "hi",
            isSelf = false, replyToMsgid = null,
        ))
        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        db.reactionDao().upsert(
            io.github.trevarj.motd.data.db.ReactionEntity(
                bufferId = buffer.id, targetMsgid = "m1", sender = "me", emoji = "👍", serverTime = 5,
            ),
        )
        // Server echoes our own react back as a TAGMSG; onTag upserts by the same unique key, so the
        // optimistic row is reconciled rather than duplicated.
        processor.process(networkId, IrcEvent.TagMessage(
            ctx = ctx(time = 9), source = Prefix("me"), target = "#chan",
            typing = null, reactEmoji = "👍", reactTargetMsgid = "m1",
        ))

        val reactions = db.reactionDao().observeFor(buffer.id, listOf("m1")).first()
        assertEquals(1, reactions.size)
        assertEquals("me", reactions.single().sender)
        assertEquals(9, reactions.single().serverTime)
    }

    @Test
    fun readMarker_advancesMaxOnly() = runTest {
        db.bufferDao().insert(
            io.github.trevarj.motd.data.db.BufferEntity(
                networkId = networkId, name = "#chan", displayName = "#chan", type = BufferType.CHANNEL,
            ),
        )
        processor.process(networkId, IrcEvent.ReadMarker("#chan", 5000))
        processor.process(networkId, IrcEvent.ReadMarker("#chan", 3000)) // lower → ignored
        val buffer = db.bufferDao().byName(networkId, "#chan")!!
        assertEquals(5000L, buffer.readMarkerTime)
    }

    @Test
    fun readMarker_notifiesOnlyForKnownTimestampedTarget() = runTest {
        val observed = mutableListOf<Pair<Long, Long>>()
        val notifying = EventProcessor(db, TypingTrackerImpl(), object : MessageNotifier {
            override suspend fun onIncoming(
                networkId: Long,
                bufferId: Long,
                type: BufferType,
                hasMention: Boolean,
                message: IrcEvent.ChatMessage,
            ) = Unit

            override suspend fun onRead(bufferId: Long, upToTime: Long) {
                observed += bufferId to upToTime
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

        assertEquals(listOf(bufferId to 5000L), observed)
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
        assertEquals(2_000L, db.bufferDao().observeById(buffer.id)!!.readMarkerTime)
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
    fun ownMessage_bareEchoThenLaterHistoryWithMsgid_collapsesByMsgid() = runTest {
        // The remaining double-send: a self message confirmed by a BARE echo (no msgid) keeps a
        // msgid-less local row. A reconnect CHATHISTORY replay later delivers the SAME message with
        // a real msgid, OUTSIDE the 30s echo window. Before the fix that replay fell through to a
        // fresh INSERT (no msgid on the row, window missed) and the message showed twice.
        val bufferId = selfBuffer("#chan")
        // 1. optimistic pending send.
        processor.insertPending(bufferId, "lblz", "me", "double me", null, MessageKind.PRIVMSG)
        // 2. bare echo (echo-message on, but this echo carries no draft/msgid) confirms in place.
        processor.process(networkId, IrcEvent.ChatMessage(
            ctx = ctx(msgid = null, time = System.currentTimeMillis(), label = "lblz"),
            kind = IrcEvent.ChatKind.PRIVMSG, source = Prefix("me"), target = "#chan",
            text = "double me", isSelf = true, replyToMsgid = null,
        ))
        assertEquals(1, pagingList(bufferId).count { it.text == "double me" })
        // 3. much-later CHATHISTORY replay carries the real msgid, well outside the echo window.
        processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(
            IrcEvent.ChatMessage(
                ctx = ctx(msgid = "hist-msgid", time = 10_000_000L, label = null),
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
}
