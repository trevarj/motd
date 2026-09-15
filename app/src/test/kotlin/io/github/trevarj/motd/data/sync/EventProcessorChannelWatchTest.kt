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
import io.github.trevarj.motd.service.NotificationMode
import io.github.trevarj.motd.service.NotificationSettings
import io.github.trevarj.motd.service.NotificationSettingsImpl
import io.github.trevarj.motd.service.NotificationSettingsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
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

    private suspend fun TestScope.watching(vararg ids: Long): NotificationSettings =
        NotificationSettingsImpl(
            scope = backgroundScope,
            clock = AppClock { testScheduler.currentTime },
            onExpired = {},
        ).apply {
            for (id in ids) startWatch(id, null)
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

    private fun processor(settings: NotificationSettings = NotificationSettings.Noop) = EventProcessor(db, TypingTrackerImpl(), notifier, notificationSettings = settings)

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

            val watched = processor(watching(bufferId))
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
                NotificationSettingsImpl(
                    scope = backgroundScope,
                    clock = AppClock { 0 },
                    onExpired = {},
                    resolveBufferId = buffers::canonicalId,
                    observeBufferId = { id -> buffers.observe(id).map { it?.id } },
                )
            val watched = processor(watch)
            watched.onRegistered(networkId, "me", emptyMap())
            watch.startWatch(sourceId, null)

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
            watch.state.first { it is NotificationSettingsState.Ready && bufferId in it.config.watches }

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
            val watched = processor(watching(bufferId))
            watched.onRegistered(networkId, "me", emptyMap())
            watched.process(networkId, chat(IrcEvent.ChatKind.ACTION, "waves", "a1"))
            assertEquals(1, notifier.incoming.size)
        }

    @Test
    fun `watched channel notice does not notify without a mention`() =
        runTest {
            val watched = processor(watching(bufferId))
            watched.onRegistered(networkId, "me", emptyMap())
            watched.process(networkId, chat(IrcEvent.ChatKind.NOTICE, "server-ish", "n1"))
            assertEquals(0, notifier.incoming.size)
        }

    @Test
    fun `push playback of a watched channel does not notify without a mention`() =
        runTest {
            val watched = processor(watching(bufferId))
            watched.onRegistered(networkId, "me", emptyMap())
            watched.processPush(networkId, chat(IrcEvent.ChatKind.PRIVMSG, "replayed", "p1"))
            assertEquals(0, notifier.incoming.size)
        }

    @Test
    fun `two watched channels notify independently and stopping one keeps the other`() =
        runTest {
            val secondId =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#other", displayName = "#other", type = BufferType.CHANNEL),
                )
            val settings = watching(bufferId, secondId)
            val processor = processor(settings)
            processor.onRegistered(networkId, "me", emptyMap())

            processor.process(networkId, chat(IrcEvent.ChatKind.PRIVMSG, "first", "two-1"))
            processor.process(networkId, chat(IrcEvent.ChatKind.PRIVMSG, "second", "two-2").copy(target = "#other"))
            settings.stopWatch(bufferId)
            processor.process(networkId, chat(IrcEvent.ChatKind.PRIVMSG, "stopped", "two-3"))
            processor.process(networkId, chat(IrcEvent.ChatKind.ACTION, "still watched", "two-4").copy(target = "#other"))

            assertEquals(listOf("first", "second", "still watched"), notifier.incoming.map { it.text })
            assertEquals(listOf(true, true, true), notifier.watchedFlags)
        }

    @Test
    fun `global server and channel policies control live chat without granting mute bypass`() =
        runTest {
            db.bufferDao().insert(
                BufferEntity(networkId = networkId, name = "#other", displayName = "#other", type = BufferType.CHANNEL),
            )
            val settings = watching()
            settings.setGlobal(NotificationMode.OFF)
            settings.setServer(networkId, NotificationMode.ALL)
            settings.setChannel(bufferId, NotificationMode.MENTIONS)
            val processor = processor(settings)
            processor.onRegistered(networkId, "me", emptyMap())

            processor.process(networkId, chat(IrcEvent.ChatKind.PRIVMSG, "channel ordinary", "scope-1"))
            processor.process(networkId, chat(IrcEvent.ChatKind.PRIVMSG, "me: channel mention", "scope-2"))
            processor.process(networkId, chat(IrcEvent.ChatKind.PRIVMSG, "server all", "scope-3").copy(target = "#other"))
            processor.process(networkId, chat(IrcEvent.ChatKind.NOTICE, "ordinary notice", "scope-4").copy(target = "#other"))
            processor.process(networkId, chat(IrcEvent.ChatKind.PRIVMSG, "direct all", "scope-5").copy(target = "me"))
            settings.setChannel(bufferId, NotificationMode.OFF)
            processor.process(networkId, chat(IrcEvent.ChatKind.PRIVMSG, "me: channel off", "scope-6"))
            settings.setGlobal(NotificationMode.ALL)
            settings.setServer(networkId, NotificationMode.OFF)
            processor.process(networkId, chat(IrcEvent.ChatKind.PRIVMSG, "server off", "scope-7").copy(target = "#other"))
            processor.process(networkId, chat(IrcEvent.ChatKind.PRIVMSG, "direct off", "scope-8").copy(target = "me"))
            settings.setChannel(bufferId, NotificationMode.ALL)
            processor.process(networkId, chat(IrcEvent.ChatKind.ACTION, "channel all", "scope-9"))
            settings.setServer(networkId, null)
            processor.process(networkId, chat(IrcEvent.ChatKind.PRIVMSG, "global all", "scope-10").copy(target = "#other"))

            assertEquals(
                listOf("me: channel mention", "server all", "direct all", "channel all", "global all"),
                notifier.incoming.map { it.text },
            )
            assertEquals(List(5) { false }, notifier.watchedFlags)
        }

    @Test
    fun `off push stays suppressed after enabling policy and watch before live duplicate`() =
        runTest {
            val settings = watching()
            settings.setGlobal(NotificationMode.OFF)
            val processor = processor(settings)
            processor.onRegistered(networkId, "me", emptyMap())
            val message = chat(IrcEvent.ChatKind.PRIVMSG, "me: frozen off", "off-push")
            processor.processPush(networkId, message)

            settings.setGlobal(NotificationMode.ALL)
            settings.startWatch(bufferId, null)
            processor.process(networkId, message)

            val stored = requireNotNull(db.messageDao().byMsgid(bufferId, "off-push"))
            assertEquals(false, stored.notificationEligible)
            assertEquals(true, stored.notificationEligibilityResolved)
            assertEquals(false, stored.notificationWatched)
            assertEquals(emptyList<IrcEvent.ChatMessage>(), notifier.incoming)
            assertEquals(emptyList<Long>(), pendingIds())
        }

    @Test
    fun `off live cannot become recoverable through later enabled history`() =
        runTest {
            val settings = watching()
            settings.setGlobal(NotificationMode.OFF)
            val processor = processor(settings)
            processor.onRegistered(networkId, "me", emptyMap())
            val message = chat(IrcEvent.ChatKind.PRIVMSG, "me: live off", "off-live")
            processor.process(networkId, message)

            settings.setGlobal(NotificationMode.MENTIONS)
            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(message)))

            val stored = requireNotNull(db.messageDao().byMsgid(bufferId, "off-live"))
            assertEquals(false, stored.notificationEligible)
            assertEquals(true, stored.notificationEligibilityResolved)
            assertEquals(emptyList<Long>(), pendingIds())
            assertEquals(emptyList<IrcEvent.ChatMessage>(), notifier.incoming)
        }

    @Test
    fun `history first captures the policy of the first later live or push observation`() =
        runTest {
            val settings = watching()
            val processor = processor(settings)
            processor.onRegistered(networkId, "me", emptyMap())
            val suppressed = chat(IrcEvent.ChatKind.PRIVMSG, "me: history enabled", "history-enabled")
            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(suppressed)))
            assertEquals(true, db.messageDao().byMsgid(bufferId, "history-enabled")?.notificationEligible)
            assertEquals(false, db.messageDao().byMsgid(bufferId, "history-enabled")?.notificationEligibilityResolved)
            assertEquals(emptyList<Long>(), pendingIds())

            settings.setGlobal(NotificationMode.OFF)
            processor.processPush(networkId, suppressed)
            val admitted = chat(IrcEvent.ChatKind.PRIVMSG, "history disabled", "history-disabled")
            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(admitted)))
            assertEquals(false, db.messageDao().byMsgid(bufferId, "history-disabled")?.notificationEligible)
            assertEquals(false, db.messageDao().byMsgid(bufferId, "history-disabled")?.notificationEligibilityResolved)

            settings.setGlobal(NotificationMode.ALL)
            processor.process(networkId, suppressed)
            processor.process(networkId, admitted)
            settings.setGlobal(NotificationMode.OFF)
            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(admitted)))

            assertEquals(listOf("history disabled"), notifier.incoming.map { it.text })
            assertEquals(false, db.messageDao().byMsgid(bufferId, "history-enabled")?.notificationEligible)
            assertEquals(true, db.messageDao().byMsgid(bufferId, "history-enabled")?.notificationEligibilityResolved)
            assertEquals(true, db.messageDao().byMsgid(bufferId, "history-disabled")?.notificationEligible)
            assertEquals(true, db.messageDao().byMsgid(bufferId, "history-disabled")?.notificationEligibilityResolved)
        }

    @Test
    fun `all policy ordinary push waits for live and uses its stored eligibility`() =
        runTest {
            val settings = watching()
            settings.setGlobal(NotificationMode.ALL)
            val processor = processor(settings)
            processor.onRegistered(networkId, "me", emptyMap())
            val message = chat(IrcEvent.ChatKind.PRIVMSG, "ordinary pushed", "all-push")
            processor.processPush(networkId, message)
            assertEquals(emptyList<IrcEvent.ChatMessage>(), notifier.incoming)
            assertEquals(emptyList<Long>(), pendingIds())

            settings.setGlobal(NotificationMode.OFF)
            processor.process(networkId, message)

            assertEquals(listOf("ordinary pushed"), notifier.incoming.map { it.text })
            assertEquals(listOf(false), notifier.watchedFlags)
        }

    private suspend fun pendingIds(): List<Long> = db.canonicalTimelineDao().pendingNotifications(10, window = Long.MAX_VALUE / 2, maxRows = Int.MAX_VALUE).map { it.id }

    @Test
    fun `unavailable settings persist incoming messages without alerts`() =
        runTest {
            val settings =
                NotificationSettingsImpl(
                    scope = backgroundScope,
                    clock = AppClock { testScheduler.currentTime },
                    onExpired = {},
                    load = { throw IOException("private preference data") },
                )
            val processor = processor(settings)
            processor.onRegistered(networkId, "me", emptyMap())

            processor.process(networkId, chat(IrcEvent.ChatKind.PRIVMSG, "me: silent", "unavailable"))

            assertEquals(1, db.messageDao().countForBuffer(bufferId))
            assertEquals(emptyList<IrcEvent.ChatMessage>(), notifier.incoming)
        }
}
