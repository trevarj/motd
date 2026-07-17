package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PendingChannelCloseCoordinatorTest {
    private var db: MotdDatabase? = null

    @After
    fun tearDown() {
        db?.close()
    }

    @Test
    fun directClose_waitsForReady_thenKeepsHistoryUntilServerAcknowledgesPart() = runTest {
        val database = inMemoryDb().also { db = it }
        val networkId = database.networkDao().insert(directNetwork())
        val bufferId = database.bufferDao().insert(channel(networkId))
        val connections = FakeConnections()
        val coordinator = coordinator(database, connections, backgroundScope)

        coordinator.requestClose(bufferId)
        assertNotNull(database.bufferDao().observeById(bufferId)?.pendingCloseAt)
        assertTrue(connections.parts.isEmpty())
        assertNotNull(database.bufferDao().observeById(bufferId))

        connections.states.value = mapOf(networkId to ready())
        coordinator.retryPendingCloses()

        assertEquals(listOf(bufferId), connections.parts)
        assertNotNull(database.bufferDao().observeById(bufferId)?.pendingCloseAt)
    }

    @Test
    fun directClose_sendFailure_keepsPendingForLaterRetry() = runTest {
        val database = inMemoryDb().also { db = it }
        val networkId = database.networkDao().insert(directNetwork())
        val bufferId = database.bufferDao().insert(channel(networkId))
        val connections = FakeConnections().apply { failPart = true }
        connections.states.value = mapOf(networkId to ready())
        val releaseRetry = CompletableDeferred<Unit>()
        val retryScheduled = CompletableDeferred<Unit>()
        val coordinator = coordinator(
            database = database,
            connections = connections,
            scope = backgroundScope,
            retryWait = { attempt ->
                if (attempt == 1) {
                    retryScheduled.complete(Unit)
                    releaseRetry.await()
                } else {
                    awaitCancellation()
                }
            },
        )

        coordinator.requestClose(bufferId)
        retryScheduled.await()

        assertNotNull(database.bufferDao().observeById(bufferId)?.pendingCloseAt)
        assertNotNull(database.bufferDao().observeById(bufferId))
        assertEquals(listOf(bufferId), connections.parts)

        connections.failPart = false
        releaseRetry.complete(Unit)
        connections.secondPartAttempted.await()

        assertEquals(listOf(bufferId, bufferId), connections.parts)
        assertNotNull(database.bufferDao().observeById(bufferId))
    }

    @Test
    fun directClose_transportDisappearsAtSendBoundary_keepsPending() = runTest {
        val database = inMemoryDb().also { db = it }
        val networkId = database.networkDao().insert(directNetwork())
        val bufferId = database.bufferDao().insert(channel(networkId))
        val connections = FakeConnections().apply { reportPartSuccess = false }
        connections.states.value = mapOf(networkId to ready())
        val coordinator = coordinator(database, connections, backgroundScope)

        coordinator.requestClose(bufferId)

        assertEquals(listOf(bufferId), connections.parts)
        assertNotNull(database.bufferDao().observeById(bufferId))
        assertNotNull(database.bufferDao().observeById(bufferId)?.pendingCloseAt)
    }

    @Test
    fun sojuClose_waitsForBoundChild_thenUsesNormalPart() = runTest {
        val database = inMemoryDb().also { db = it }
        val rootId = database.networkDao().insert(
            directNetwork(name = "soju", role = NetworkRole.BOUNCER_ROOT),
        )
        val childId = database.networkDao().insert(
            directNetwork(
                name = "libera",
                role = NetworkRole.BOUNCER_CHILD,
                parentId = rootId,
                bouncerNetId = "net-1",
            ),
        )
        val bufferId = database.bufferDao().insert(channel(childId, "#motd"))
        val connections = FakeConnections().apply {
            states.value = mapOf(rootId to ready())
        }
        val coordinator = coordinator(database, connections, backgroundScope)

        coordinator.requestClose(bufferId)
        assertNotNull(database.bufferDao().observeById(bufferId)?.pendingCloseAt)
        assertTrue(connections.parts.isEmpty())

        connections.states.value = mapOf(rootId to ready(), childId to ready())
        coordinator.retryPendingCloses()

        assertEquals(listOf(bufferId), connections.parts)
        assertNotNull(database.bufferDao().observeById(bufferId)?.pendingCloseAt)
    }

    @Test
    fun start_retriesPersistedCloseWhenReadyArrives() = runTest {
        val database = inMemoryDb().also { db = it }
        val networkId = database.networkDao().insert(directNetwork())
        val bufferId = database.bufferDao().insert(channel(networkId))
        database.bufferDao().markPendingClose(bufferId, 55)
        val connections = FakeConnections()
        // Simulate process recreation after the socket is already Ready: subscribing to the
        // StateFlow must observe its current value, not only future transitions.
        connections.states.value = mapOf(networkId to ready())
        val job = SupervisorJob()
        val coordinator = coordinator(
            database,
            connections,
            CoroutineScope(job + UnconfinedTestDispatcher(testScheduler)),
        )

        try {
            coordinator.start()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    connections.partAttempted.await()
                }
            }
            assertEquals(listOf(bufferId), connections.parts)
            assertNotNull(database.bufferDao().observeById(bufferId)?.pendingCloseAt)
        } finally {
            job.cancel()
        }
    }

    private fun coordinator(
        database: MotdDatabase,
        connections: FakeConnections,
        scope: CoroutineScope,
        retryWait: suspend (attempt: Int) -> Unit = { attempt ->
            kotlinx.coroutines.delay(channelCloseRetryDelayMillis(attempt))
        },
    ) = PendingChannelCloseCoordinator.forTest(
        db = database,
        connections = connections,
        clock = AppClock { 1234L },
        scope = scope,
        retryWait = retryWait,
    )

    private class FakeConnections : ConnectionManager {
        val states = MutableStateFlow<Map<Long, IrcClientState>>(emptyMap())
        val parts = mutableListOf<Long>()
        val partAttempted = CompletableDeferred<Unit>()
        val secondPartAttempted = CompletableDeferred<Unit>()
        var failPart = false
        var reportPartSuccess = true
        override val connectionStates: StateFlow<Map<Long, IrcClientState>> = states
        override fun clientFor(networkId: Long): IrcClient? = null
        override suspend fun startAll() = Unit
        override suspend fun stopAll() = Unit
        override suspend fun connect(networkId: Long) = Unit
        override suspend fun disconnect(networkId: Long) = Unit
        override suspend fun reconnectStale() = Unit
        override suspend fun sendMessage(bufferId: Long, text: String, replyToMsgid: String?) = Unit
        override suspend fun sendTyping(bufferId: Long, state: String) = Unit
        override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) = Unit
        override suspend fun joinChannel(networkId: Long, channel: String) = Unit
        override suspend fun partChannel(bufferId: Long, reason: String?) {
            parts += bufferId
            if (failPart) error("send failed")
        }
        override suspend fun partChannelForClose(bufferId: Long, reason: String?): Boolean {
            parts += bufferId
            partAttempted.complete(Unit)
            if (parts.size >= 2) secondPartAttempted.complete(Unit)
            if (failPart) error("send failed")
            return reportPartSuccess
        }
        override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long = 0
        override suspend fun ensureServerBuffer(networkId: Long): Long = 0
        override suspend fun markRead(bufferId: Long, upToTime: Long) = Unit
        override suspend fun evaluatePushMode() = Unit
        override val certPrompts = MutableStateFlow<List<CertPrompt>>(emptyList())
        override suspend fun trustCert(prompt: CertPrompt) = Unit
        override fun dismissCertPrompt(prompt: CertPrompt) = Unit
    }

    private fun directNetwork(
        name: String = "libera",
        role: NetworkRole = NetworkRole.DIRECT,
        parentId: Long? = null,
        bouncerNetId: String? = null,
    ) = NetworkEntity(
        name = name,
        role = role,
        parentId = parentId,
        bouncerNetId = bouncerNetId,
        host = "irc.example.test",
        port = 6697,
        nick = "me",
        username = "me",
        realname = "Me",
    )

    private fun channel(networkId: Long, name: String = "#channel") = BufferEntity(
        networkId = networkId,
        name = name,
        displayName = name,
        type = BufferType.CHANNEL,
    )

    private fun ready() = IrcClientState.Ready("me", emptySet(), emptyMap())
}
