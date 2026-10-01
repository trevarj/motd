package io.github.trevarj.motd.service

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import dagger.Lazy
import io.github.trevarj.motd.avatar.AvatarCoordinator
import io.github.trevarj.motd.avatar.AvatarDatabase
import io.github.trevarj.motd.avatar.AvatarPrefsImpl
import io.github.trevarj.motd.avatar.AvatarStoreImpl
import io.github.trevarj.motd.avatar.LocalAvatarStore
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.InviteEnrollmentStore
import io.github.trevarj.motd.data.prefs.PresetEnrollmentPrefsImpl
import io.github.trevarj.motd.data.prefs.ReplyPrefsImpl
import io.github.trevarj.motd.data.repo.NetworkRepositoryImpl
import io.github.trevarj.motd.data.sync.ChatSoundPlayer
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.HistoryGapFillCoordinator
import io.github.trevarj.motd.data.sync.HistoryPageLoader
import io.github.trevarj.motd.data.sync.HistoryPruner
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.OkioLineTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.push.NoopPushHealthStore
import io.github.trevarj.motd.push.WebPushRegistrar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NotificationReplyReadinessTest {
    @Test
    fun alreadyReadyJoinedChannelSubmitsOnceWithoutReplacingIt() =
        runTest {
            val transport = RecordingTransport()
            val client = newClient(transport)
            ready(client, transport)
            transport.feed(":me!user@host JOIN #ROOM")
            runCurrent()
            val buffer =
                BufferEntity(
                    networkId = 1,
                    name = "#internal-alias",
                    displayName = "#room",
                    type = BufferType.CHANNEL,
                    joined = true,
                )
            val snapshots = MutableStateFlow(snapshot(client))
            var starts = 0
            val result =
                sendNotificationReplyWhenReady(
                    1,
                    buffer.type,
                    buffer.ircTarget,
                    snapshots,
                    start = { starts++ },
                    reconnect = { error("a Ready channel must await its normal JOIN, not reconnect") },
                    submit = { prepared ->
                        assertSame(client, prepared)
                        send(prepared, buffer.ircTarget, "public voice reply")
                    },
                )

            assertEquals(SendAcceptance.Accepted(emptyList()), result)
            assertEquals(1, starts)
            assertEquals(listOf("PRIVMSG #room :public voice reply"), transport.messages())
        }

    @Test
    fun delayedSelfJoinWaitsWithoutSubmittingOrReconnectingEarly() =
        runTest {
            val transport = RecordingTransport()
            val client = newClient(transport)
            ready(client, transport)
            val snapshots = MutableStateFlow(snapshot(client))
            val reply =
                async {
                    sendNotificationReplyWhenReady(
                        1,
                        BufferType.CHANNEL,
                        "#room",
                        snapshots,
                        start = {},
                        reconnect = { error("the current client is Ready") },
                        submit = { send(it, "#room", "after join") },
                    )
                }
            transport.feed(":peer!user@host JOIN #room")
            runCurrent()
            advanceTimeBy(4_000)
            runCurrent()
            assertFalse(reply.isCompleted)
            assertEquals(emptyList<String>(), transport.messages())

            transport.feed(":me!user@host JOIN #room")
            runCurrent()

            assertEquals(SendAcceptance.Accepted(emptyList()), reply.await())
            assertEquals(listOf("PRIVMSG #room :after join"), transport.messages())
        }

    @Test
    fun rememberedRoomMembershipDoesNotQualifyTheCurrentSocket() =
        runTest {
            val room = BufferEntity(networkId = 1, name = "#room", displayName = "#room", type = BufferType.CHANNEL, joined = true)
            val transport = RecordingTransport()
            val client = newClient(transport)
            ready(client, transport)
            val reply =
                async {
                    sendNotificationReplyWhenReady(
                        1,
                        room.type,
                        room.ircTarget,
                        MutableStateFlow(snapshot(client)),
                        start = {},
                        reconnect = { error("Ready is not a reconnect trigger") },
                        submit = { send(it, room.ircTarget, "must not be queued") },
                    )
                }
            runCurrent()
            advanceTimeBy(4_999)
            runCurrent()
            assertFalse(reply.isCompleted)
            advanceTimeBy(1)
            runCurrent()

            assertEquals(SendAcceptance.Rejected(SendRejectionReason.CONNECTION_UNAVAILABLE), reply.await())
            transport.feed(":me!user@host JOIN #room")
            runCurrent()
            assertEquals(emptyList<String>(), transport.messages())
        }

    @Test
    fun replacingAnEqualReadyStateSwitchesMembershipObservationToTheExactClient() =
        runTest {
            val oldTransport = RecordingTransport()
            val oldClient = newClient(oldTransport)
            ready(oldClient, oldTransport)
            val newTransport = RecordingTransport()
            val newClient = newClient(newTransport)
            ready(newClient, newTransport)
            val snapshots = MutableStateFlow(snapshot(oldClient))
            val waiter = async { awaitNotificationReplyClient(1, BufferType.CHANNEL, "#room", snapshots) }
            runCurrent()
            // The state-only projection stays unchanged; physical connection identity does not.
            snapshots.value = snapshots.value.copy(actors = snapshot(newClient, generation = 2).actors)
            runCurrent()
            oldTransport.feed(":me!user@host JOIN #room")
            runCurrent()
            assertFalse(waiter.isCompleted)

            newTransport.feed(":me!user@host JOIN #ROOM")
            runCurrent()

            assertSame(newClient, waiter.await())
        }

    @Test
    fun disconnectedReplyRequestsOneReconnectButNeverSubmitsAfterItsDeadline() =
        runTest {
            val transport = RecordingTransport()
            val client = newClient(transport)
            val snapshots = MutableStateFlow(snapshot(client))
            val reconnects = mutableListOf<Long>()
            val reply =
                async {
                    sendNotificationReplyWhenReady(
                        1,
                        BufferType.CHANNEL,
                        "#room",
                        snapshots,
                        start = {},
                        reconnect = { reconnects += it },
                        submit = { send(it, "#room", "expired voice reply") },
                    )
                }
            runCurrent()
            assertEquals(listOf(1L), reconnects)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(SendAcceptance.Rejected(SendRejectionReason.CONNECTION_UNAVAILABLE), reply.await())

            ready(client, transport)
            transport.feed(":me!user@host JOIN #room")
            runCurrent()
            assertEquals(emptyList<String>(), transport.messages())
            assertEquals(listOf(1L), reconnects)
        }

    @Test
    fun registryStartupAndReconnectPrecedeAQuerySendWithoutRequiringChannelMembership() =
        runTest {
            val transport = RecordingTransport()
            val client = newClient(transport)
            val snapshots = MutableStateFlow(ConnectionRegistrySnapshot())
            val reconnects = mutableListOf<Long>()
            val reply =
                async {
                    sendNotificationReplyWhenReady(
                        1,
                        BufferType.QUERY,
                        "alice",
                        snapshots,
                        start = { snapshots.value = snapshot(client) },
                        reconnect = { reconnects += it },
                        submit = { send(it, "alice", "private voice reply") },
                    )
                }
            runCurrent()
            assertEquals(listOf(1L), reconnects)
            assertFalse(reply.isCompleted)
            ready(client, transport)
            runCurrent()

            assertEquals(SendAcceptance.Accepted(emptyList()), reply.await())
            assertTrue(client.joinedChannels.value.isEmpty())
            assertEquals(listOf("PRIVMSG alice :private voice reply"), transport.messages())
        }

    @Test
    fun submissionIsNotCancelledByTheReadinessDeadline() =
        runTest {
            val transport = RecordingTransport()
            val client = newClient(transport)
            ready(client, transport)
            val reply =
                async {
                    sendNotificationReplyWhenReady(
                        1,
                        BufferType.QUERY,
                        "alice",
                        MutableStateFlow(snapshot(client)),
                        start = {},
                        reconnect = { error("already Ready") },
                        submit = {
                            delay(6_000)
                            send(it, "alice", "slow write")
                        },
                    )
                }
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            assertFalse(reply.isCompleted)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(SendAcceptance.Accepted(emptyList()), reply.await())
            assertEquals(listOf("PRIVMSG alice :slow write"), transport.messages())
        }

    @Test
    fun startupAndReconnectExceptionsRejectWithoutSubmitting() =
        runTest {
            val snapshots = MutableStateFlow(ConnectionRegistrySnapshot())
            assertEquals(
                SendAcceptance.Rejected(SendRejectionReason.CONNECTION_UNAVAILABLE),
                sendNotificationReplyWhenReady(
                    1,
                    BufferType.QUERY,
                    "alice",
                    snapshots,
                    start = { error("startup failed") },
                    reconnect = { error("must not reconnect after failed startup") },
                    submit = { error("must not persist") },
                ),
            )
            assertEquals(
                SendAcceptance.Rejected(SendRejectionReason.CONNECTION_UNAVAILABLE),
                sendNotificationReplyWhenReady(
                    1,
                    BufferType.QUERY,
                    "alice",
                    snapshots,
                    start = {},
                    reconnect = { error("reconnect failed") },
                    submit = { error("must not persist") },
                ),
            )
        }

    @Test
    fun startupConsumesTheSameFiveSecondReadinessWindow() =
        runTest {
            var reconnects = 0
            val reply =
                async {
                    sendNotificationReplyWhenReady(
                        1,
                        BufferType.QUERY,
                        "alice",
                        MutableStateFlow(ConnectionRegistrySnapshot()),
                        start = { delay(6_000) },
                        reconnect = { reconnects++ },
                        submit = { error("startup timed out") },
                    )
                }
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(SendAcceptance.Rejected(SendRejectionReason.CONNECTION_UNAVAILABLE), reply.await())
            assertEquals(0, reconnects)
        }

    @Test
    fun externalCancellationPropagatesAndLeavesNoLateSubmission() =
        runTest {
            val snapshots = MutableStateFlow(ConnectionRegistrySnapshot())
            var propagated = false
            var returned = false
            val reply =
                launch {
                    try {
                        sendNotificationReplyWhenReady(
                            1,
                            BufferType.QUERY,
                            "alice",
                            snapshots,
                            start = {},
                            reconnect = {},
                            submit = { error("cancelled operation must not submit") },
                        )
                        returned = true
                    } catch (cancelled: CancellationException) {
                        propagated = true
                        throw cancelled
                    }
                }
            runCurrent()
            reply.cancelAndJoin()
            assertTrue(propagated)
            assertFalse(returned)

            val transport = RecordingTransport()
            val client = newClient(transport)
            ready(client, transport)
            snapshots.value = snapshot(client)
            runCurrent()
            assertEquals(emptyList<String>(), transport.messages())
        }

    @Test
    fun overlappingRepliesProtectOnlyTheirOwnNetworkUntilTheFinalRelease() {
        val inFlight = ConcurrentHashMap<Long, Int>()
        val suspended = setOf(1L, 2L)
        retainNotificationReplyNetwork(inFlight, 1)
        retainNotificationReplyNetwork(inFlight, 1)
        assertEquals(setOf(2L), notificationReplySuspensions(suspended, inFlight))

        releaseNotificationReplyNetwork(inFlight, 1)
        assertEquals(setOf(2L), notificationReplySuspensions(suspended, inFlight))
        releaseNotificationReplyNetwork(inFlight, 1)
        assertEquals(suspended, notificationReplySuspensions(suspended, inFlight))
        assertFalse(inFlight.containsKey(1L))
    }

    @Test
    fun managerPreflightRejectsInvalidAndClosingRoomsBeforePreparingOrPersisting() =
        runBlocking {
            val fixture = managerFixture()
            try {
                val channel = fixture.room(BufferType.CHANNEL)
                val server = fixture.room(BufferType.SERVER, "server")
                assertEquals(
                    SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND),
                    fixture.manager.sendNotificationReply(Long.MAX_VALUE, "voice reply"),
                )
                assertEquals(
                    SendAcceptance.Rejected(SendRejectionReason.INVALID_CONTENT),
                    fixture.manager.sendNotificationReply(channel.id, " \n "),
                )
                assertEquals(
                    SendAcceptance.Rejected(SendRejectionReason.UNSUPPORTED_BUFFER),
                    fixture.manager.sendNotificationReply(server.id, "voice reply"),
                )
                fixture.db.bufferDao().setJoined(channel.id, false)
                assertEquals(
                    SendAcceptance.Rejected(SendRejectionReason.NOT_IN_CHANNEL),
                    fixture.manager.sendNotificationReply(channel.id, "voice reply"),
                )
                fixture.db.bufferDao().update(channel.copy(pendingCloseAt = 1))
                assertEquals(
                    SendAcceptance.Rejected(SendRejectionReason.NOT_IN_CHANNEL),
                    fixture.manager.sendNotificationReply(channel.id, "voice reply"),
                )
                assertTrue(fixture.peer.connections.isEmpty())
                assertFalse(fixture.db.messageDao().hasStoredChat(channel.id))
                assertFalse(fixture.db.messageDao().hasStoredChat(server.id))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun channelClosingWhileWaitingForItsJoinIsRejectedByTheManagerBeforePersistence() =
        runBlocking {
            val fixture = managerFixture()
            try {
                val room = fixture.room(BufferType.CHANNEL)
                val (client, connection) = startNetwork(fixture)
                val reply =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        fixture.manager.sendNotificationReply(room.id, "voice reply while closing")
                    }
                assertTrue(client.joinedChannels.value.isEmpty())
                assertFalse(reply.isCompleted)
                fixture.db.bufferDao().markPendingClose(room.id, 1)
                connection.feed(":me!user@host JOIN #room")

                assertEquals(SendAcceptance.Rejected(SendRejectionReason.NOT_IN_CHANNEL), reply.await())
                assertFalse(fixture.db.messageDao().hasStoredChat(room.id))
                connection.barrier()
                assertEquals(emptyList<String>(), connection.messages())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun managerReturnsDurableNotificationIdsWhenCancelledBehindAnotherSendsWireLock() =
        runBlocking {
            val fixture = managerFixture()
            var wireLock: Mutex? = null
            try {
                val room = fixture.room(BufferType.QUERY, "alice")
                val (_, connection) = startNetwork(fixture)
                wireLock = fixture.holdWireLock()
                val first =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        fixture.manager.sendMessage(room.id, "first composer send")
                    }
                awaitStoredText(fixture, room.id, "first composer send")
                val notificationAcceptance = CompletableDeferred<SendAcceptance>()
                val notification =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        notificationAcceptance.complete(fixture.manager.sendNotificationReply(room.id, "voice reply"))
                    }
                awaitStoredText(fixture, room.id, "voice reply")
                assertEquals(2, fixture.db.messageDao().countForBuffer(room.id))
                assertEquals(emptyList<String>(), connection.messages())
                notification.cancel()
                assertFalse(notificationAcceptance.isCompleted)

                wireLock.unlock()
                wireLock = null
                first.await()
                notification.join()
                val accepted = notificationAcceptance.await() as SendAcceptance.Accepted
                val durable =
                    fixture.db
                        .messageDao()
                        .byIds(accepted.eventIds)
                        .single()
                assertEquals(room.id, durable.bufferId)
                assertEquals("voice reply", durable.text)
                assertEquals(ImmediateWireAcceptance.ACCEPTED, accepted.immediateWireAcceptance)
                connection.awaitLine { it == "PRIVMSG alice :voice reply" }
                assertEquals(
                    listOf("PRIVMSG alice :first composer send", "PRIVMSG alice :voice reply"),
                    connection.messages(),
                )
            } finally {
                wireLock?.unlock()
                fixture.close()
            }
        }

    @Test
    fun startedManagerStillWaitsForTheReplacementSocketToBecomeReadyBeforePersistence() =
        runBlocking {
            val fixture = managerFixture()
            try {
                val room = fixture.room(BufferType.QUERY, "alice")
                fixture.manager.startAll()
                fixture.peer.accept()
                withTimeout(10_000) {
                    fixture.manager.connectionStates.first { it[fixture.networkId] is IrcClientState.Registering }
                }
                val reply =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        fixture.manager.sendNotificationReply(room.id, "wait for registration")
                    }
                val replacement = fixture.peer.accept()
                val client = checkNotNull(fixture.manager.clientFor(fixture.networkId))
                assertFalse(reply.isCompleted)
                assertFalse(fixture.db.messageDao().hasStoredChat(room.id))
                assertEquals(emptyList<String>(), replacement.messages())

                ready(client, replacement)
                val accepted = reply.await() as SendAcceptance.Accepted
                assertEquals(
                    "wait for registration",
                    fixture.db
                        .messageDao()
                        .byIds(accepted.eventIds)
                        .single()
                        .text,
                )
                replacement.awaitLine { it == "PRIVMSG alice :wait for registration" }
                assertEquals(listOf("PRIVMSG alice :wait for registration"), replacement.messages())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun closingWhileNotificationPersistenceWaitsForTheRealNetworkSequencerRejectsBeforeInsertion() =
        runBlocking {
            val incomingHeld = CompletableDeferred<Unit>()
            val releaseIncoming = CompletableDeferred<Unit>()
            val persistenceRequested = CompletableDeferred<Unit>()
            val armed = AtomicBoolean(false)
            var roomId = 0L
            val notifier =
                object : MessageNotifier {
                    override suspend fun onIncoming(
                        networkId: Long,
                        bufferId: Long,
                        type: BufferType,
                        hasMention: Boolean,
                        message: IrcEvent.ChatMessage,
                    ) {
                        incomingHeld.complete(Unit)
                        releaseIncoming.await()
                    }
                }
            val fixture =
                managerFixture(notifier) { sql, args ->
                    if (armed.get() && sql == "SELECT * FROM buffers WHERE id = ?" && args.singleOrNull() == roomId) {
                        persistenceRequested.complete(Unit)
                    }
                }
            try {
                val room = fixture.room(BufferType.CHANNEL)
                roomId = room.id
                val (client, connection) = startNetwork(fixture)
                connection.feed(":me!user@host JOIN #room")
                withTimeout(10_000) { client.joinedChannels.first { it.contains("#room") } }
                connection.feed(":alice!user@host PRIVMSG #room :me: holding the network sequencer")
                withTimeout(10_000) { incomingHeld.await() }
                armed.set(true)
                val reply =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        fixture.manager.sendNotificationReply(room.id, "must not send after close")
                    }
                withTimeout(10_000) { persistenceRequested.await() }
                assertFalse(reply.isCompleted)
                fixture.db.bufferDao().markPendingClose(room.id, 1)
                releaseIncoming.complete(Unit)

                assertEquals(SendAcceptance.Rejected(SendRejectionReason.NOT_IN_CHANNEL), reply.await())
                assertEquals(
                    0,
                    fixture.db.messageDao().rawCount(
                        SimpleSQLiteQuery(
                            "SELECT COUNT(*) FROM messages WHERE bufferId = ? AND isSelf = 1 AND kind IN ('PRIVMSG', 'NOTICE', 'ACTION')",
                            arrayOf(room.id),
                        ),
                    ),
                )
                connection.barrier()
                assertEquals(emptyList<String>(), connection.messages())
            } finally {
                releaseIncoming.complete(Unit)
                fixture.close()
            }
        }

    private fun snapshot(
        client: IrcClient,
        generation: Long = 1,
    ) = ConnectionRegistrySnapshot(
        started = true,
        actors = mapOf(1L to ConnectionActorSnapshot(IrcClientConnection(client), true, "fixture", generation)),
        states = mapOf(1L to client.state.value),
    )

    private fun TestScope.newClient(transport: RecordingTransport) =
        IrcClient(
            IrcClientConfig("irc.example", 6697, true, "me", "me", "Me"),
            TransportFactory { _, _, _, _, _ -> transport },
            backgroundScope,
        )

    private suspend fun TestScope.ready(
        client: IrcClient,
        transport: RecordingTransport,
    ) {
        if (client.state.value == IrcClientState.Disconnected) client.start()
        runCurrent()
        val caps = "batch message-tags server-time"
        transport.feed(":srv CAP * LS :$caps")
        runCurrent()
        transport.feed(":srv CAP me ACK :$caps")
        transport.feed(":srv 005 me CHANTYPES=# CASEMAPPING=rfc1459 :supported")
        transport.feed(":srv 001 me :Welcome")
        runCurrent()
        check(client.state.value is IrcClientState.Ready)
    }

    private suspend fun send(
        client: IrcClient,
        target: String,
        text: String,
    ): SendAcceptance {
        assertTrue(client.sendMessage(target, text, replyToMsgid = null, label = "notification-readiness"))
        return SendAcceptance.Accepted(emptyList())
    }

    private suspend fun startNetwork(fixture: ManagerFixture): Pair<IrcClient, RecordingPeerConnection> =
        withTimeout(10_000) {
            fixture.manager.startAll()
            val connection = fixture.peer.accept()
            fixture.manager.connectionStates.first { it[fixture.networkId] is IrcClientState.Registering }
            val client = checkNotNull(fixture.manager.clientFor(fixture.networkId))
            ready(client, connection)
            fixture.manager.connectionStates.first { it[fixture.networkId] is IrcClientState.Ready }
            client to connection
        }

    private suspend fun ready(
        client: IrcClient,
        connection: RecordingPeerConnection,
    ) {
        val caps = "batch message-tags server-time"
        connection.feed(":srv CAP * LS :$caps")
        connection.awaitLine { it.startsWith("CAP REQ ") }
        connection.feed(":srv CAP me ACK :$caps")
        connection.feed(":srv 005 me CHANTYPES=# CASEMAPPING=rfc1459 :supported")
        connection.feed(":srv 001 me :Welcome")
        withTimeout(10_000) { client.state.first { it is IrcClientState.Ready } }
    }

    private suspend fun awaitStoredText(
        fixture: ManagerFixture,
        roomId: Long,
        text: String,
    ): MessageEntity =
        withTimeout(10_000) {
            fixture.db
                .messageDao()
                .observeRawMessage(
                    SimpleSQLiteQuery("SELECT * FROM messages WHERE bufferId = ? AND text = ? LIMIT 1", arrayOf<Any>(roomId, text)),
                ).first { it != null }!!
        }

    private suspend fun managerFixture(
        notifier: MessageNotifier = MessageNotifier.Noop,
        onQuery: (String, List<Any?>) -> Unit = { _, _ -> },
    ): ManagerFixture {
        val context: Context = ApplicationProvider.getApplicationContext()
        // Native manager tests use real I/O time; only the isolated readiness helpers use virtual time.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val peer = LocalIrcPeer()
        val db =
            Room
                .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .setQueryCallback(onQuery, Executor(Runnable::run))
                .build()
        val avatars =
            Room
                .inMemoryDatabaseBuilder(context, AvatarDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        val networkId =
            db.networkDao().insert(
                NetworkEntity(
                    name = "reply fixture",
                    role = NetworkRole.DIRECT,
                    host = "127.0.0.1",
                    port = peer.port,
                    tls = false,
                    nick = "me",
                    username = "me",
                    realname = "Me",
                ),
            )
        val settings = DataStoreSettingsRepository(context)
        settings.setDeliveryMode(DeliveryMode.PERSISTENT_SOCKET)
        val processor = EventProcessor(db, TypingTrackerImpl(scope, AppClock(System::currentTimeMillis)), notifier)
        val loader = HistoryPageLoader(processor)
        lateinit var manager: ConnectionManagerImpl
        val avatarCoordinator =
            AvatarCoordinator(
                AvatarPrefsImpl(context),
                AvatarStoreImpl(avatars.avatarDao()),
                db.userDao(),
                db.bufferDao(),
                LocalAvatarStore(context),
                Lazy { manager },
                scope,
            )
        manager =
            ConnectionManagerImpl(
                appContext = context,
                db = db,
                eventProcessor = processor,
                settings = settings,
                pushPrefs = settings,
                replyPrefs = ReplyPrefsImpl(context),
                certStore = settings,
                inviteEnrollmentStore = InviteEnrollmentStore(context),
                baseTransportFactory = TransportFactory { host, port, tls, _, proxy -> OkioLineTransport(host, port, tls, proxy = proxy) },
                localSocksProvider = LocalSocksProvider(context, DiagnosticLogger.Noop),
                historyResyncCoordinator = HistoryResyncCoordinator(db, processor, scope = scope, settingsRepository = settings),
                readMarkerRepository = ReadMarkerRepository(db),
                messageNotifier = notifier,
                chatSoundPlayer = ChatSoundPlayer.Noop,
                presetEnrollmentCoordinator = PresetEnrollmentCoordinator(PresetEnrollmentPrefsImpl(context), NetworkRepositoryImpl(db.networkDao())),
                avatarCoordinator = avatarCoordinator,
                pushHealthStore = NoopPushHealthStore,
                diagnostics = DiagnosticLogger.Noop,
                scope = scope,
                webPushRegistrar = Lazy { WebPushRegistrar(settings, manager, NoopPushHealthStore, db.networkDao()) },
                historyGapFillCoordinator =
                    Lazy {
                        HistoryGapFillCoordinator(
                            manager,
                            db.bufferDao(),
                            db.messageDao(),
                            db.historyCursorDao(),
                            db.historyGapDao(),
                            loader,
                            DiagnosticLogger.Noop,
                            settings,
                            HistoryPruner.Noop,
                        )
                    },
            )
        return ManagerFixture(db, avatars, manager, networkId, peer, scope)
    }

    private class ManagerFixture(
        val db: MotdDatabase,
        private val avatars: AvatarDatabase,
        val manager: ConnectionManagerImpl,
        val networkId: Long,
        val peer: LocalIrcPeer,
        private val scope: CoroutineScope,
    ) {
        suspend fun room(
            type: BufferType,
            target: String = "#room",
        ): BufferEntity {
            val room = BufferEntity(networkId = networkId, name = target, displayName = target, type = type, joined = true)
            return room.copy(id = db.bufferDao().insert(room))
        }

        @Suppress("UNCHECKED_CAST")
        suspend fun holdWireLock(): Mutex {
            // Hold the actual production lock without adding a test-only manager API.
            val field = ConnectionManagerImpl::class.java.getDeclaredField("sendLocks").apply { isAccessible = true }
            val locks = field.get(manager) as ConcurrentHashMap<Long, Mutex>
            return locks.computeIfAbsent(networkId) { Mutex() }.also { it.lock() }
        }

        suspend fun close() =
            withContext(NonCancellable) {
                // Close the peer first so native socket reads cannot outlive actor shutdown.
                peer.close()
                try {
                    manager.stopAll()
                } finally {
                    scope.coroutineContext.job.cancelAndJoin()
                    db.close()
                    avatars.close()
                }
            }
    }

    private class RecordingTransport : IrcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        private val sent = mutableListOf<String>()

        override suspend fun connect() = Unit

        override val incoming = inbound.consumeAsFlow()

        override suspend fun send(line: String) {
            sent += line
        }

        override suspend fun close() {
            inbound.close()
        }

        suspend fun feed(line: String) {
            inbound.send(line)
        }

        fun messages(): List<String> = sent.filter { it.startsWith("PRIVMSG ") }
    }

    private class LocalIrcPeer : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val accepted = Channel<RecordingPeerConnection>(Channel.UNLIMITED)
        val connections = ConcurrentLinkedQueue<RecordingPeerConnection>()
        val port: Int get() = listener.localPort
        private val accepting =
            Thread {
                while (true) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    val connection = RecordingPeerConnection(socket)
                    connections.add(connection)
                    accepted.trySend(connection)
                }
            }.apply {
                isDaemon = true
                start()
            }

        suspend fun accept(): RecordingPeerConnection = withTimeout(10_000) { accepted.receive() }

        override fun close() {
            listener.close()
            accepting.join(1_000)
            connections.forEach(RecordingPeerConnection::close)
            accepted.close()
        }
    }

    private class RecordingPeerConnection(
        private val socket: Socket,
    ) : AutoCloseable {
        private val output = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
        private val lines = Channel<String>(Channel.UNLIMITED)
        private val recorded = ConcurrentLinkedQueue<String>()
        private val reading =
            Thread {
                val input = runCatching { socket.getInputStream().bufferedReader(Charsets.UTF_8) }.getOrNull()
                if (input == null) {
                    lines.close()
                    return@Thread
                }
                while (true) {
                    val line = runCatching { input.readLine() }.getOrNull() ?: break
                    recorded.add(line)
                    lines.trySend(line)
                }
                lines.close()
            }.apply {
                isDaemon = true
                start()
            }

        fun feed(line: String) {
            synchronized(output) {
                output.write(line)
                output.write("\r\n")
                output.flush()
            }
        }

        suspend fun awaitLine(matches: (String) -> Boolean): String =
            withTimeout(10_000) {
                var line: String
                do {
                    line = lines.receive()
                } while (!matches(line))
                line
            }

        suspend fun barrier() {
            feed(":srv PING :notification-test-barrier")
            awaitLine {
                val message = IrcMessage.parse(it)
                message.command == "PONG" && message.params.lastOrNull() == "notification-test-barrier"
            }
        }

        fun messages(): List<String> = recorded.filter { it.startsWith("PRIVMSG ") }

        override fun close() {
            socket.close()
            reading.join(1_000)
        }
    }
}
