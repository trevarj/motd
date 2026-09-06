package io.github.trevarj.motd.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.TimelineEventId
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import io.github.trevarj.motd.service.ChannelWatch
import io.github.trevarj.motd.service.ChannelWatchImpl
import io.github.trevarj.motd.service.ChannelWatchState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventProcessorChannelWatchTest {
    private class RecordingNotifier : MessageNotifier {
        val incoming = mutableListOf<IrcEvent.ChatMessage>()
        val watchedFlags = mutableListOf<Boolean>()

        override suspend fun onIncoming(
            networkId: Long,
            bufferId: Long,
            type: BufferType,
            hasMention: Boolean,
            message: IrcEvent.ChatMessage,
        ) {
            incoming += message
        }

        override suspend fun onCanonicalIncoming(
            networkId: Long,
            bufferId: Long,
            type: BufferType,
            hasMention: Boolean,
            eventId: TimelineEventId,
            message: IrcEvent.ChatMessage,
            watched: Boolean,
        ) {
            watchedFlags += watched
            onIncoming(networkId, bufferId, type, hasMention, message)
        }
    }

    private class StickyWatch(
        private val watched: Long,
    ) : ChannelWatch {
        override val state: StateFlow<ChannelWatchState?> =
            MutableStateFlow(ChannelWatchState(watched, Long.MAX_VALUE))

        override suspend fun isActive(bufferId: Long): Boolean = bufferId == watched

        override suspend fun start(
            bufferId: Long,
            durationMs: Long?,
        ) = Unit

        override suspend fun stop() = Unit
    }

    private lateinit var db: MotdDatabase
    private lateinit var notifier: RecordingNotifier
    private var networkId = 0L
    private var bufferId = 0L

    @Before
    fun setUp() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java).allowMainThreadQueries().build()
            notifier = RecordingNotifier()
            networkId =
                db.networkDao().insert(
                    NetworkEntity(
                        name = "libera",
                        role = NetworkRole.DIRECT,
                        host = "h",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                )
            bufferId =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#chan", displayName = "#chan", type = BufferType.CHANNEL),
                )
        }

    @After
    fun tearDown() {
        db.close()
    }

    private fun processor(watch: ChannelWatch = ChannelWatch.Noop) = EventProcessor(db, TypingTrackerImpl(), notifier, channelWatch = watch)

    private fun chat(
        kind: IrcEvent.ChatKind,
        text: String,
        msgid: String,
    ) = IrcEvent.ChatMessage(
        ctx = MessageContext(msgid = msgid, serverTime = 1000, account = null, batchId = null, label = null),
        kind = kind,
        source = Prefix("alice"),
        target = "#chan",
        text = text,
        isSelf = false,
        replyToMsgid = null,
    )

    @Test
    fun `live channel privmsg without mention notifies only while watched`() =
        runTest {
            val idle = processor()
            idle.onRegistered(networkId, "me", emptyMap())
            idle.process(networkId, chat(IrcEvent.ChatKind.PRIVMSG, "hello", "m1"))
            assertEquals(0, notifier.incoming.size)

            val watched = processor(StickyWatch(bufferId))
            watched.onRegistered(networkId, "me", emptyMap())
            watched.process(networkId, chat(IrcEvent.ChatKind.PRIVMSG, "still hello", "m2"))
            assertEquals(1, notifier.incoming.size)
            assertEquals(listOf(true), notifier.watchedFlags)
        }

    @Test
    fun `watch follows a renamed source into the canonical channel`() =
        runTest {
            val buffers = db.bufferDao()
            val sourceId =
                buffers.insert(
                    BufferEntity(networkId = networkId, name = "#old", displayName = "#old", type = BufferType.CHANNEL),
                )
            val watch =
                ChannelWatchImpl(
                    scope = backgroundScope,
                    clock = AppClock { 0 },
                    onExpired = {},
                    resolveBufferId = buffers::canonicalId,
                    observeBufferId = { id -> buffers.observe(id).map { it?.id } },
                )
            val watched = processor(watch)
            watched.onRegistered(networkId, "me", emptyMap())
            watch.start(sourceId, null)

            watched.process(
                networkId,
                IrcEvent.ChannelRenamed(
                    ctx = MessageContext(msgid = "rename-1", serverTime = 999, account = null, batchId = null, label = null),
                    actor = "oper",
                    oldName = "#old",
                    newName = "#chan",
                    reason = "moving",
                ),
            )
            assertEquals(bufferId, buffers.canonicalId(sourceId))

            watched.process(networkId, chat(IrcEvent.ChatKind.PRIVMSG, "hello", "first-after-rename"))

            assertEquals(listOf(true), notifier.watchedFlags)
            assertEquals(
                "first-after-rename",
                notifier.incoming
                    .single()
                    .ctx.msgid,
            )
        }

    @Test
    fun `mention on an unwatched channel notifies without the watched flag`() =
        runTest {
            val idle = processor()
            idle.onRegistered(networkId, "me", emptyMap())
            idle.process(networkId, chat(IrcEvent.ChatKind.PRIVMSG, "me: ping", "m3"))
            assertEquals(1, notifier.incoming.size)
            assertEquals(listOf(false), notifier.watchedFlags)
        }

    @Test
    fun `watched channel action notifies without a mention`() =
        runTest {
            val watched = processor(StickyWatch(bufferId))
            watched.onRegistered(networkId, "me", emptyMap())
            watched.process(networkId, chat(IrcEvent.ChatKind.ACTION, "waves", "a1"))
            assertEquals(1, notifier.incoming.size)
        }

    @Test
    fun `watched channel notice does not notify without a mention`() =
        runTest {
            val watched = processor(StickyWatch(bufferId))
            watched.onRegistered(networkId, "me", emptyMap())
            watched.process(networkId, chat(IrcEvent.ChatKind.NOTICE, "server-ish", "n1"))
            assertEquals(0, notifier.incoming.size)
        }

    @Test
    fun `push playback of a watched channel does not notify without a mention`() =
        runTest {
            val watched = processor(StickyWatch(bufferId))
            watched.onRegistered(networkId, "me", emptyMap())
            watched.processPush(networkId, chat(IrcEvent.ChatKind.PRIVMSG, "replayed", "p1"))
            assertEquals(0, notifier.incoming.size)
        }
}
