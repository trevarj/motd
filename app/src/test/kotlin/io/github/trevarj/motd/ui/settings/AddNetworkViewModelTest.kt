package io.github.trevarj.motd.ui.settings

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.AuthMode
import io.github.trevarj.motd.ui.onboarding.ConnectionChoice
import io.github.trevarj.motd.ui.onboarding.ServerForm
import io.github.trevarj.motd.ui.settings.addnetwork.AddNetworkPhase
import io.github.trevarj.motd.ui.settings.addnetwork.AddNetworkViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class AddNetworkViewModelTest {

    /** In-memory NetworkRepository tracking inserts/deletes. */
    private class FakeNetworkRepository : NetworkRepository {
        val networks = mutableMapOf<Long, NetworkEntity>()
        private val ids = AtomicLong(0)
        override fun observeNetworks() = throw UnsupportedOperationException()
        override suspend fun addNetwork(n: NetworkEntity): Long {
            val id = ids.incrementAndGet()
            networks[id] = n.copy(id = id)
            return id
        }
        override suspend fun updateNetwork(n: NetworkEntity) { networks[n.id] = n }
        override suspend fun deleteNetwork(id: Long) { networks.remove(id) }
        override suspend fun networkById(id: Long) = networks[id]
        override suspend fun childrenOf(rootId: Long) = networks.values.filter { it.parentId == rootId }
    }

    /** ConnectionManager fake: drives connectionStates for a single network. */
    private class FakeConnectionManager : ConnectionManager {
        override val connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(emptyMap())
        val connected = mutableListOf<Long>()
        fun emit(id: Long, state: IrcClientState) {
            connectionStates.value = connectionStates.value + (id to state)
        }
        override fun clientFor(networkId: Long): IrcClient? = null
        override suspend fun startAll() = Unit
        override suspend fun stopAll() = Unit
        override suspend fun connect(networkId: Long) { connected += networkId }
        override suspend fun disconnect(networkId: Long) = Unit
        override suspend fun reconnectStale() = Unit
        override suspend fun sendMessage(bufferId: Long, text: String, replyToMsgid: String?) = Unit
        override suspend fun sendTyping(bufferId: Long, state: String) = Unit
        override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) = Unit
        override suspend fun joinChannel(networkId: Long, channel: String) = Unit
        override suspend fun partChannel(bufferId: Long, reason: String?) = Unit
        override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long = 0
        override suspend fun ensureServerBuffer(networkId: Long): Long = 0
        override suspend fun markRead(bufferId: Long, upToTime: Long) = Unit
        override suspend fun evaluatePushMode() = Unit
        override val certPrompts = MutableStateFlow<List<CertPrompt>>(emptyList())
        override suspend fun trustCert(prompt: CertPrompt) = Unit
        override fun dismissCertPrompt(prompt: CertPrompt) = Unit
    }

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(repo: NetworkRepository, cm: ConnectionManager) = AddNetworkViewModel(repo, cm)

    private fun AddNetworkViewModel.fillValidDirect() {
        editServer(ServerForm(host = "irc.libera.chat", port = "6697", nick = "me"))
        editAuth(AuthForm(mode = AuthMode.NONE))
    }

    @Test
    fun soju_kind_pins_sasl_plain() = runTest {
        val vm = vm(FakeNetworkRepository(), FakeConnectionManager())
        vm.setKind(ConnectionChoice.SOJU)
        assertEquals(AuthMode.PLAIN, vm.state.value.auth.mode)
        // Editing auth to NONE is re-pinned back to PLAIN for soju.
        vm.editAuth(AuthForm(mode = AuthMode.NONE))
        assertEquals(AuthMode.PLAIN, vm.state.value.auth.mode)
    }

    @Test
    fun valid_soju_config_is_submittable() = runTest {
        val vm = vm(FakeNetworkRepository(), FakeConnectionManager())
        vm.setKind(ConnectionChoice.SOJU)
        // Fill the collapsed soju form: host/port/TLS + nick, then username/password (SASL PLAIN).
        vm.editServer(ServerForm(host = "soju.example", port = "6697", tls = true, nick = "trev"))
        vm.editAuth(vm.state.value.auth.copy(saslUser = "motd/libera", saslPassword = "pw"))
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun soju_missing_password_or_nick_is_not_submittable() = runTest {
        val vm = vm(FakeNetworkRepository(), FakeConnectionManager())
        vm.setKind(ConnectionChoice.SOJU)
        // Missing password.
        vm.editServer(ServerForm(host = "soju.example", port = "6697", nick = "trev"))
        vm.editAuth(vm.state.value.auth.copy(saslUser = "motd/libera", saslPassword = ""))
        assertFalse(vm.state.value.canSubmit)
        // Password present but nick missing.
        vm.editServer(ServerForm(host = "soju.example", port = "6697", nick = ""))
        vm.editAuth(vm.state.value.auth.copy(saslUser = "motd/libera", saslPassword = "pw"))
        assertFalse(vm.state.value.canSubmit)
    }

    @Test
    fun ready_direct_pops_and_keeps_row() = runTest {
        val repo = FakeNetworkRepository()
        val cm = FakeConnectionManager()
        val vm = vm(repo, cm)
        var done = false
        vm.fillValidDirect()
        vm.submit(onOpenBouncerNetworks = {}, onDone = { done = true })
        runCurrent()
        assertEquals(AddNetworkPhase.TESTING, vm.state.value.phase)
        val id = vm.state.value.networkId!!
        cm.emit(id, IrcClientState.Ready("me", emptySet(), emptyMap()))
        runCurrent()
        assertTrue(done)
        assertTrue(repo.networks.containsKey(id))   // row kept on success
    }

    @Test
    fun ready_soju_routes_to_bouncer_manager() = runTest {
        val repo = FakeNetworkRepository()
        val cm = FakeConnectionManager()
        val vm = vm(repo, cm)
        var routedTo: Long? = null
        vm.setKind(ConnectionChoice.SOJU)
        vm.editServer(ServerForm(host = "soju.example", port = "6697", nick = "me"))
        vm.editAuth(AuthForm(mode = AuthMode.PLAIN, saslUser = "me", saslPassword = "pw"))
        vm.submit(onOpenBouncerNetworks = { routedTo = it }, onDone = {})
        runCurrent()
        val id = vm.state.value.networkId!!
        cm.emit(id, IrcClientState.Ready("me", emptySet(), emptyMap()))
        runCurrent()
        assertEquals(id, routedTo)
        assertEquals(NetworkRole.BOUNCER_ROOT, repo.networks[id]?.role)
    }

    @Test
    fun failed_moves_to_failed_phase() = runTest {
        val repo = FakeNetworkRepository()
        val cm = FakeConnectionManager()
        val vm = vm(repo, cm)
        vm.fillValidDirect()
        vm.submit(onOpenBouncerNetworks = {}, onDone = {})
        runCurrent()
        val id = vm.state.value.networkId!!
        cm.emit(id, IrcClientState.Failed("SASL failed", fatal = true))
        runCurrent()
        assertEquals(AddNetworkPhase.FAILED, vm.state.value.phase)
        assertEquals("SASL failed", vm.state.value.error)
        assertTrue(repo.networks.containsKey(id))   // kept so "Save anyway" is possible
    }

    @Test
    fun retry_deletes_the_failed_row() = runTest {
        val repo = FakeNetworkRepository()
        val cm = FakeConnectionManager()
        val vm = vm(repo, cm)
        vm.fillValidDirect()
        vm.submit(onOpenBouncerNetworks = {}, onDone = {})
        runCurrent()
        val firstId = vm.state.value.networkId!!
        cm.emit(firstId, IrcClientState.Failed("nope", fatal = true))
        runCurrent()
        vm.retry(onOpenBouncerNetworks = {}, onDone = {})
        runCurrent()
        assertFalse(repo.networks.containsKey(firstId))   // old row removed
        assertEquals(AddNetworkPhase.TESTING, vm.state.value.phase)
    }

    @Test
    fun abandon_deletes_the_row() = runTest {
        val repo = FakeNetworkRepository()
        val cm = FakeConnectionManager()
        val vm = vm(repo, cm)
        var back = false
        vm.fillValidDirect()
        vm.submit(onOpenBouncerNetworks = {}, onDone = {})
        runCurrent()
        val id = vm.state.value.networkId!!
        vm.abandon { back = true }
        runCurrent()
        assertTrue(back)
        assertFalse(repo.networks.containsKey(id))
    }

    @Test
    fun save_anyway_keeps_row() = runTest {
        val repo = FakeNetworkRepository()
        val cm = FakeConnectionManager()
        val vm = vm(repo, cm)
        var back = false
        vm.fillValidDirect()
        vm.submit(onOpenBouncerNetworks = {}, onDone = {})
        runCurrent()
        val id = vm.state.value.networkId!!
        cm.emit(id, IrcClientState.Failed("nope", fatal = true))
        runCurrent()
        vm.saveAnyway { back = true }
        runCurrent()
        assertTrue(back)
        assertTrue(repo.networks.containsKey(id))
    }
}
