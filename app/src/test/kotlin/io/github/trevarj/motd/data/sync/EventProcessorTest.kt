package io.github.trevarj.motd.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
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
            override fun onIncoming(networkId: Long, bufferId: Long, type: BufferType, hasMention: Boolean, message: IrcEvent.ChatMessage) {
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
}
