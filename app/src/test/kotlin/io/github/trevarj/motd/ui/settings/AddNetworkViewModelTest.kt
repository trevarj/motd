package io.github.trevarj.motd.ui.settings

import io.github.trevarj.motd.bouncer.BouncerKind
import io.github.trevarj.motd.bouncer.SojuLoginForm
import io.github.trevarj.motd.bouncer.ZncLoginForm
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.BouncerKindPrefs
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.testing.NoopConnectionManager
import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.AuthMode
import io.github.trevarj.motd.ui.onboarding.ConnectionChoice
import io.github.trevarj.motd.ui.onboarding.ServerForm
import io.github.trevarj.motd.ui.settings.addnetwork.AddNetworkPhase
import io.github.trevarj.motd.ui.settings.addnetwork.AddNetworkViewModel
import io.github.trevarj.motd.ui.settings.addnetwork.NetworkPresetId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
        var existingResult: Long? = null

        override fun observeNetworks() = flowOf(networks.values.toList())

        override suspend fun addNetwork(n: NetworkEntity): Long {
            existingResult?.let { return it }
            val id = ids.incrementAndGet()
            networks[id] = n.copy(id = id)
            return id
        }

        override suspend fun updateNetwork(n: NetworkEntity) {
            networks[n.id] = n
        }

        override suspend fun deleteNetwork(id: Long) {
            networks.remove(id)
        }

        override suspend fun reorderNetworks(orderedIds: List<Long>) = Unit

        override suspend fun networkById(id: Long) = networks[id]

        override suspend fun childrenOf(rootId: Long) = networks.values.filter { it.parentId == rootId }
    }

    private class FakeBouncerKindPrefs : BouncerKindPrefs {
        val ids = MutableStateFlow<Set<Long>>(emptySet())
        override val zncNetworkIds: Flow<Set<Long>> = ids

        override suspend fun markZnc(networkId: Long) {
            ids.value += networkId
        }

        override suspend fun clear(networkId: Long) {
            ids.value -= networkId
        }
    }

    /** ConnectionManager fake: drives connectionStates for a single network. */
    private class FakeConnectionManager : NoopConnectionManager() {
        override val connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(emptyMap())
        val connected = mutableListOf<Long>()

        fun emit(
            id: Long,
            state: IrcClientState,
        ) {
            connectionStates.value = connectionStates.value + (id to state)
        }

        override suspend fun connect(networkId: Long) {
            connected += networkId
        }

        override suspend fun ensureQueryBuffer(
            networkId: Long,
            nick: String,
        ): Long = 0

        override suspend fun ensureServerBuffer(networkId: Long): Long = 0
    }

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(
        repo: NetworkRepository,
        cm: ConnectionManager,
        bouncerPrefs: BouncerKindPrefs = FakeBouncerKindPrefs(),
    ) = AddNetworkViewModel(repo, cm, bouncerPrefs)

    private fun AddNetworkViewModel.fillValidDirect() {
        editServer(ServerForm(host = "irc.libera.chat", port = "6697", nick = "me"))
        editAuth(AuthForm(mode = AuthMode.NONE))
    }

    @Test
    fun soju_kind_uses_separate_plain_login() =
        runTest {
            val vm = vm(FakeNetworkRepository(), FakeConnectionManager())
            vm.setKind(ConnectionChoice.BOUNCER)
            assertEquals(AuthMode.PLAIN, vm.state.value.activeAuth.mode)
            vm.editAuth(AuthForm(mode = AuthMode.NONE))
            assertEquals(AuthMode.NONE, vm.state.value.auth.mode)
        }

    @Test
    fun valid_soju_config_is_submittable() =
        runTest {
            val vm = vm(FakeNetworkRepository(), FakeConnectionManager())
            vm.setKind(ConnectionChoice.BOUNCER)
            // Fill the collapsed soju form: host/port/TLS + nick, then username/password (SASL PLAIN).
            vm.editServer(ServerForm(host = "soju.example", port = "6697", tls = true, nick = "trev"))
            vm.editSojuLogin(SojuLoginForm("motd", "pw"))
            assertTrue(vm.state.value.canSubmit)
        }

    @Test
    fun ready_ZNC_is_direct_uses_combined_authcid_and_is_remembered() =
        runTest {
            val repo = FakeNetworkRepository()
            val cm = FakeConnectionManager()
            val bouncerPrefs = FakeBouncerKindPrefs()
            val vm = vm(repo, cm, bouncerPrefs = bouncerPrefs)
            var done = false
            vm.setKind(ConnectionChoice.BOUNCER)
            vm.setBouncerKind(BouncerKind.ZNC)
            vm.editServer(ServerForm(host = "znc.example", port = "6697", nick = "motd"))
            vm.editZncLogin(ZncLoginForm("account", "libera", "pw"))

            vm.submit(onOpenBouncerNetworks = { error("ZNC must not open soju manager") }, onDone = { done = true })
            runCurrent()
            val id = vm.state.value.networkId!!
            val row = repo.networks.getValue(id)
            assertEquals(NetworkRole.DIRECT, row.role)
            assertEquals("account/libera", row.saslUser)
            assertEquals("account", row.username)

            cm.emit(id, IrcClientState.Ready("motd", emptySet(), emptyMap()))
            runCurrent()
            assertTrue(done)
            assertTrue(id in bouncerPrefs.ids.value)
        }

    @Test
    fun direct_network_uses_entered_display_name_over_host() =
        runTest {
            val repo = FakeNetworkRepository()
            val vm = vm(repo, FakeConnectionManager())
            vm.fillValidDirect()
            vm.editDisplayName("  My Home Network  ")

            vm.submit(onOpenBouncerNetworks = { error("not a bouncer") }, onDone = {})
            runCurrent()

            val id = vm.state.value.networkId!!
            assertEquals("My Home Network", repo.networks.getValue(id).name)
        }

    @Test
    fun direct_network_falls_back_to_host_when_display_name_is_blank() =
        runTest {
            val repo = FakeNetworkRepository()
            val vm = vm(repo, FakeConnectionManager())
            vm.fillValidDirect()

            vm.submit(onOpenBouncerNetworks = { error("not a bouncer") }, onDone = {})
            runCurrent()

            val id = vm.state.value.networkId!!
            assertEquals("irc.libera.chat", repo.networks.getValue(id).name)
        }

    @Test
    fun duplicate_connection_stays_on_form_without_reconnecting() =
        runTest {
            val repo = FakeNetworkRepository()
            repo.networks[42] =
                NetworkEntity(
                    id = 42,
                    name = "Existing",
                    role = NetworkRole.DIRECT,
                    host = "irc.libera.chat",
                    port = 6697,
                    nick = "me",
                    username = "me",
                    realname = "me",
                )
            repo.existingResult = 42
            val connections = FakeConnectionManager()
            val vm = vm(repo, connections)
            vm.fillValidDirect()
            vm.editDisplayName("Different label")
            var done = false

            vm.submit(onOpenBouncerNetworks = { error("not a bouncer") }, onDone = { done = true })
            runCurrent()

            assertTrue(vm.state.value.duplicateConnection)
            assertEquals(AddNetworkPhase.FORM, vm.state.value.phase)
            assertEquals(null, vm.state.value.networkId)
            assertTrue(connections.connected.isEmpty())
            assertFalse(done)

            vm.editAuth(
                vm.state.value.auth
                    .copy(serverPassword = "other:secret"),
            )
            assertFalse(vm.state.value.duplicateConnection)
        }

    @Test
    fun soju_missing_password_or_nick_is_not_submittable() =
        runTest {
            val vm = vm(FakeNetworkRepository(), FakeConnectionManager())
            vm.setKind(ConnectionChoice.BOUNCER)
            // Missing password.
            vm.editServer(ServerForm(host = "soju.example", port = "6697", nick = "trev"))
            vm.editSojuLogin(SojuLoginForm("motd", ""))
            assertFalse(vm.state.value.canSubmit)
            // Password present but nick missing.
            vm.editServer(ServerForm(host = "soju.example", port = "6697", nick = ""))
            vm.editSojuLogin(SojuLoginForm("motd", "pw"))
            assertFalse(vm.state.value.canSubmit)
        }

    @Test
    fun ready_direct_pops_and_keeps_row() =
        runTest {
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
            assertTrue(repo.networks.containsKey(id)) // row kept on success
        }

    @Test
    fun ready_soju_routes_to_bouncer_manager() =
        runTest {
            val repo = FakeNetworkRepository()
            val cm = FakeConnectionManager()
            val vm = vm(repo, cm)
            var routedTo: Long? = null
            vm.setKind(ConnectionChoice.BOUNCER)
            vm.editServer(ServerForm(host = "soju.example", port = "6697", nick = "me"))
            vm.editSojuLogin(SojuLoginForm("me", "pw"))
            vm.submit(onOpenBouncerNetworks = { routedTo = it }, onDone = {})
            runCurrent()
            val id = vm.state.value.networkId!!
            cm.emit(id, IrcClientState.Ready("me", emptySet(), emptyMap()))
            runCurrent()
            assertEquals(id, routedTo)
            assertEquals(NetworkRole.BOUNCER_ROOT, repo.networks[id]?.role)
        }

    @Test
    fun failed_moves_to_failed_phase() =
        runTest {
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
            assertTrue(repo.networks.containsKey(id)) // kept so "Save anyway" is possible
        }

    @Test
    fun retry_deletes_the_failed_row() =
        runTest {
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
            assertFalse(repo.networks.containsKey(firstId)) // old row removed
            assertEquals(AddNetworkPhase.TESTING, vm.state.value.phase)
        }

    @Test
    fun abandon_deletes_the_row() =
        runTest {
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
    fun save_anyway_keeps_row() =
        runTest {
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

    @Test
    fun preset_selection_clears_auth_and_endpoint_edits_become_custom() =
        runTest {
            val vm = vm(FakeNetworkRepository(), FakeConnectionManager())
            vm.editServer(ServerForm(nick = "trev", username = "ident", realname = "Trev"))
            vm.editAuth(AuthForm(AuthMode.PLAIN, "old-account", "old-password", "old-cert"))

            vm.selectPreset(NetworkPresetId.LIBERA)

            assertEquals(NetworkPresetId.LIBERA, vm.state.value.presetId)
            assertEquals("irc.libera.chat", vm.state.value.server.host)
            assertEquals("trev", vm.state.value.server.nick)
            assertEquals("ident", vm.state.value.server.username)
            assertEquals("Trev", vm.state.value.server.realname)
            assertEquals(AuthForm(), vm.state.value.auth)

            vm.editServer(
                vm.state.value.server
                    .copy(nick = "newnick"),
            )
            assertEquals(NetworkPresetId.LIBERA, vm.state.value.presetId)
            vm.editServer(
                vm.state.value.server
                    .copy(port = "7000"),
            )
            assertEquals(NetworkPresetId.CUSTOM, vm.state.value.presetId)
        }

    @Test
    fun plaintext_direct_network_requires_explicit_confirmation() =
        runTest {
            val repo = FakeNetworkRepository()
            val cm = FakeConnectionManager()
            val vm = vm(repo, cm)
            vm.selectPreset(NetworkPresetId.QUAKENET)
            vm.editServer(
                vm.state.value.server
                    .copy(nick = "trev"),
            )

            vm.submit(onOpenBouncerNetworks = {}, onDone = {})
            runCurrent()
            assertTrue(vm.state.value.showPlaintextWarning)
            assertTrue(repo.networks.isEmpty())

            vm.confirmPlaintext(onOpenBouncerNetworks = {}, onDone = {})
            runCurrent()
            assertEquals(AddNetworkPhase.TESTING, vm.state.value.phase)
            assertEquals(
                "QuakeNet",
                repo.networks.values
                    .single()
                    .name,
            )
            assertFalse(
                repo.networks.values
                    .single()
                    .tls,
            )
        }

    @Test
    fun abandoning_a_provisional_libera_preset_deletes_the_row() =
        runTest {
            val repo = FakeNetworkRepository()
            val vm = vm(repo, FakeConnectionManager())
            vm.selectPreset(NetworkPresetId.LIBERA)
            vm.editServer(
                vm.state.value.server
                    .copy(nick = "trev"),
            )
            vm.submit({}, {})
            runCurrent()
            val id = vm.state.value.networkId!!

            vm.abandon {}
            runCurrent()

            assertFalse(repo.networks.containsKey(id))
        }
}
