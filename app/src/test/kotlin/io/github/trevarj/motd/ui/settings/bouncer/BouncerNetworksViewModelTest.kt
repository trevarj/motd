package io.github.trevarj.motd.ui.settings.bouncer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.bouncer.BouncerServCapabilities
import io.github.trevarj.motd.bouncer.BouncerServClient
import io.github.trevarj.motd.bouncer.BouncerServCommand
import io.github.trevarj.motd.bouncer.BouncerServResult
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BouncerNetworksViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: MotdDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MotdDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `late root lookup cannot overwrite a ready connection state`() = runTest(dispatcher) {
        val root = rootNetwork()
        val lookupStarted = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        val repository = object : NetworkRepository {
            override fun observeNetworks() = flowOf(listOf(root))
            override suspend fun addNetwork(n: NetworkEntity) = error("unused")
            override suspend fun updateNetwork(n: NetworkEntity) = error("unused")
            override suspend fun deleteNetwork(id: Long) = error("unused")
            override suspend fun networkById(id: Long): NetworkEntity {
                lookupStarted.complete(Unit)
                releaseLookup.await()
                return root
            }
            override suspend fun childrenOf(rootId: Long) = emptyList<NetworkEntity>()
        }
        val ready = IrcClientState.Ready("motd", emptySet(), emptyMap())
        val connections = FakeConnectionManager(mapOf(root.id to ready))
        val viewModel = BouncerNetworksViewModel(
            networkRepository = repository,
            connectionManager = connections,
            bouncerServ = FakeBouncerServClient,
            messageDao = database.messageDao(),
        )

        viewModel.init(root.id)
        runCurrent()
        assertTrue(lookupStarted.isCompleted)
        assertTrue(viewModel.state.value.rootState is IrcClientState.Ready)

        releaseLookup.complete(Unit)
        runCurrent()

        assertEquals(root, viewModel.state.value.root)
        assertTrue(viewModel.state.value.rootState is IrcClientState.Ready)
    }

    private class FakeConnectionManager(initial: Map<Long, IrcClientState>) : ConnectionManager {
        override val connectionStates = MutableStateFlow(initial)
        override val certPrompts = MutableStateFlow<List<CertPrompt>>(emptyList())
        override fun clientFor(networkId: Long): IrcClient? = null
        override suspend fun startAll() = Unit
        override suspend fun stopAll() = Unit
        override suspend fun connect(networkId: Long) = Unit
        override suspend fun disconnect(networkId: Long) = Unit
        override suspend fun reconnectStale() = Unit
        override suspend fun sendMessage(bufferId: Long, text: String, replyToEventId: Long?) =
            io.github.trevarj.motd.service.SendAcceptance.Accepted(emptyList())
        override suspend fun sendTyping(bufferId: Long, state: String) = Unit
        override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) = Unit
        override suspend fun joinChannel(networkId: Long, channel: String) = Unit
        override suspend fun partChannel(bufferId: Long, reason: String?) = Unit
        override suspend fun ensureQueryBuffer(networkId: Long, nick: String) = 0L
        override suspend fun ensureServerBuffer(networkId: Long) = 0L
        override suspend fun markRead(bufferId: Long, anchor: io.github.trevarj.motd.data.db.TimelineAnchor) = Unit
        override suspend fun evaluatePushMode() = Unit
        override suspend fun trustCert(prompt: CertPrompt) = Unit
        override fun dismissCertPrompt(prompt: CertPrompt) = Unit
    }

    private object FakeBouncerServClient : BouncerServClient {
        override suspend fun execute(rootNetworkId: Long, command: BouncerServCommand) =
            BouncerServResult.Success(command.display, emptyList())

        override suspend fun probe(rootNetworkId: Long) = BouncerServCapabilities(
            commandPaths = setOf("network create", "server status"),
            verified = true,
        )
    }

    private fun rootNetwork() = NetworkEntity(
        id = 1L,
        name = "Local soju",
        role = NetworkRole.BOUNCER_ROOT,
        host = "127.0.0.1",
        port = 6697,
        tls = true,
        nick = "motd",
        username = "motd",
        realname = "MOTD",
    )
}
