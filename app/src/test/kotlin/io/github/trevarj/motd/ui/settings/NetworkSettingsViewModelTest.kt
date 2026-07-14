package io.github.trevarj.motd.ui.settings

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.data.prefs.PresetEnrollmentPrefs
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.AuthMode
import io.github.trevarj.motd.ui.onboarding.ServerForm
import io.github.trevarj.motd.avatar.AvatarConfig
import io.github.trevarj.motd.avatar.AvatarController
import io.github.trevarj.motd.avatar.AvatarPrefs
import io.github.trevarj.motd.avatar.SelfAvatarSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkSettingsViewModelTest {

    private class FakePresetEnrollmentPrefs : PresetEnrollmentPrefs {
        val revoked = mutableSetOf<Long>()
        override suspend fun markLiberaEligible(networkId: Long) = Unit
        override suspend fun claimLiberaMotdJoin(networkId: Long) = false
        override suspend fun revokeLiberaEligibility(networkId: Long) { revoked += networkId }
    }

    private class FakeNetworkRepository(initial: List<NetworkEntity>) : NetworkRepository {
        val networks = initial.associateByTo(mutableMapOf()) { it.id }
        val operations = mutableListOf<String>()

        override fun observeNetworks() = flowOf(networks.values.toList())
        override suspend fun addNetwork(n: NetworkEntity): Long = error("unused")
        override suspend fun updateNetwork(n: NetworkEntity) {
            operations += "update:${n.id}"
            networks[n.id] = n
        }
        override suspend fun deleteNetwork(id: Long) {
            operations += "delete:$id"
            val removed = networks.remove(id)
            if (removed?.role == NetworkRole.BOUNCER_ROOT) {
                networks.values.filter { it.parentId == id }.map { it.id }.forEach(networks::remove)
            }
        }
        override suspend fun networkById(id: Long): NetworkEntity? = networks[id]
        override suspend fun childrenOf(rootId: Long): List<NetworkEntity> =
            networks.values.filter { it.parentId == rootId }
    }

    private class FakeConnectionManager(
        initial: Map<Long, IrcClientState> = emptyMap(),
    ) : ConnectionManager {
        override val connectionStates = MutableStateFlow(initial)
        val disconnected = mutableListOf<Long>()
        override fun clientFor(networkId: Long): IrcClient? = null
        override suspend fun startAll() = Unit
        override suspend fun stopAll() = Unit
        override suspend fun connect(networkId: Long) = Unit
        override suspend fun disconnect(networkId: Long) { disconnected += networkId }
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

    private class FakeAvatarPrefs : AvatarPrefs {
        override val config = flowOf(AvatarConfig())
        override fun selfSetting(networkId: Long) = flowOf<SelfAvatarSetting>(SelfAvatarSetting.Unmanaged)
        override suspend fun setShowSharedAvatars(show: Boolean) = Unit
        override suspend fun setSelfSetting(networkId: Long, setting: SelfAvatarSetting) = Unit
    }

    private object FakeAvatarController : AvatarController {
        var available = false
        val published = mutableListOf<Pair<Long, String?>>()
        val cleared = mutableListOf<Long>()
        override suspend fun setShowSharedAvatars(show: Boolean) = Unit
        override suspend fun setSelfAvatar(networkId: Long, url: String?): Boolean {
            published += networkId to url
            return available
        }
        override suspend fun stopManagingSelfAvatar(networkId: Long) = Unit
        override suspend fun clearNetworkState(networkId: Long) { cleared += networkId }
        override fun publishingAvailable(networkId: Long) = available
    }

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        FakeAvatarController.available = false
        FakeAvatarController.published.clear()
        FakeAvatarController.cleared.clear()
    }
    @After fun tearDown() = Dispatchers.resetMain()

    private fun root(
        host: String = "bouncer.example.org",
        port: Int = 6697,
        tls: Boolean = true,
        wsUrl: String? = null,
        saslMechanism: String = "PLAIN",
        saslUser: String? = "motd",
        clientCertAlias: String? = null,
    ) = NetworkEntity(
        id = 1,
        name = "My bouncer",
        role = NetworkRole.BOUNCER_ROOT,
        host = host,
        port = port,
        tls = tls,
        nick = "motd",
        username = "motd",
        realname = "motd",
        wsUrl = wsUrl,
        saslMechanism = saslMechanism,
        saslUser = saslUser,
        saslPassword = "old-password",
        clientCertAlias = clientCertAlias,
    )

    private fun child(id: Long) = root().copy(
        id = id,
        name = "Libera",
        role = NetworkRole.BOUNCER_CHILD,
        parentId = 1,
        bouncerNetId = id.toString(),
    )

    private fun TestScope.loadedVm(
        repo: FakeNetworkRepository,
        prefs: PresetEnrollmentPrefs = FakePresetEnrollmentPrefs(),
        connectionManager: FakeConnectionManager = FakeConnectionManager(),
    ): NetworkSettingsViewModel {
        return NetworkSettingsViewModel(
            repo,
            connectionManager,
            prefs,
            FakeAvatarPrefs(),
            FakeAvatarController,
        ).also {
            it.init(1)
            runCurrent()
        }
    }

    private fun NetworkSettingsViewModel.changeHost() {
        editServer(state.value.server.copy(host = "new-bouncer.example.org"))
    }

    @Test
    fun bouncerIdentityComparator_normalizesHostAndIgnoresLocalOnlyFields() {
        val original = root(host = " BOUNCER.example.org. ", wsUrl = " wss://bnc.example/ ")
        assertFalse(bouncerIdentityChanged(original, original.copy(
            host = "bouncer.example.org",
            wsUrl = "wss://bnc.example/",
            name = "Renamed",
            nick = "differentNick",
            username = "differentUser",
            saslPassword = "new-password",
            proxyHost = "127.0.0.1",
            proxyPort = 1080,
            obfsLink = "vless://private-link",
        )))
    }

    @Test
    fun bouncerIdentityComparator_detectsEveryInheritedField() {
        val original = root()
        listOf(
            original.copy(host = "other.example.org"),
            original.copy(port = 443),
            original.copy(tls = false),
            original.copy(wsUrl = "wss://bouncer.example.org/irc"),
            original.copy(saslMechanism = "EXTERNAL"),
            original.copy(saslUser = "another-account"),
            original.copy(clientCertAlias = "client-cert"),
        ).forEach { changed -> assertTrue(bouncerIdentityChanged(original, changed)) }
    }

    @Test
    fun vlessLinkValidationError_explainsInvalidLinkAndAcceptsSupportedRealityTcpLink() {
        assertEquals("Only TCP VLESS links are supported", vlessLinkValidationError(
            "vless://0702fbe2-7d6e-4e71-85e5-3689b8dffa9f@ingress.example:8443?" +
                "security=reality&sni=www.cloudflare.com&pbk=key&sid=abcd",
        ))
        assertNull(vlessLinkValidationError(
            "vless://0702fbe2-7d6e-4e71-85e5-3689b8dffa9f@ingress.example:8443?" +
                "type=tcp&security=reality&sni=www.cloudflare.com&pbk=key&sid=abcd",
        ))
    }

    @Test
    fun embeddedRealityState_surfacesLinkErrorAndDisablesSave() {
        val invalid = NetworkSettingsUiState(
            obfsMode = io.github.trevarj.motd.data.db.ObfsMode.EMBEDDED_REALITY,
            obfsLink = "not a VLESS URI",
            server = ServerForm(host = "soju", port = "6697", nick = "motd"),
            auth = AuthForm(mode = AuthMode.PLAIN, saslUser = "motd", saslPassword = "password"),
        )
        assertNotNull(invalid.vlessLinkError)
        assertFalse(invalid.canSave)
    }

    @Test
    fun saveRootIdentityChangeWithChildren_defersAllWritesUntilDecision() = runTest {
        val repo = FakeNetworkRepository(listOf(root(), child(2), child(3)))
        val vm = loadedVm(repo)
        vm.changeHost()

        vm.save { error("must wait for mirror decision") }
        runCurrent()

        assertEquals(2, vm.state.value.pendingBouncerIdentityChange?.localMirrorCount)
        assertTrue(repo.operations.isEmpty())

        vm.cancelBouncerIdentityChange()
        assertNull(vm.state.value.pendingBouncerIdentityChange)
        assertTrue(repo.operations.isEmpty())
    }

    @Test
    fun keepLocalMirrors_updatesOnlyRootThenNavigates() = runTest {
        val repo = FakeNetworkRepository(listOf(root(), child(2)))
        val vm = loadedVm(repo)
        var done = false
        vm.changeHost()
        vm.save { error("must wait for mirror decision") }
        runCurrent()

        vm.keepLocalMirrors { done = true }
        runCurrent()

        assertEquals(listOf("update:1"), repo.operations)
        assertTrue(repo.networks.containsKey(2))
        assertTrue(done)
        assertNull(vm.state.value.pendingBouncerIdentityChange)
    }

    @Test
    fun removeLocalMirrors_updatesRootBeforeDeletingOnlyLocalChildrenThenNavigates() = runTest {
        val repo = FakeNetworkRepository(listOf(root(), child(2), child(3)))
        val vm = loadedVm(repo)
        var done = false
        vm.changeHost()
        vm.save { error("must wait for mirror decision") }
        runCurrent()

        vm.removeLocalMirrors { done = true }
        runCurrent()

        assertEquals(listOf("update:1", "delete:2", "delete:3"), repo.operations)
        assertTrue(repo.networks.containsKey(1))
        assertFalse(repo.networks.containsKey(2))
        assertFalse(repo.networks.containsKey(3))
        assertTrue(done)
    }

    @Test
    fun deletingBouncerRoot_disconnectsAndForgetsEveryLocalMirror() = runTest {
        val repo = FakeNetworkRepository(listOf(root(), child(2), child(3)))
        val prefs = FakePresetEnrollmentPrefs()
        val connectionManager = FakeConnectionManager()
        val vm = loadedVm(repo, prefs, connectionManager)
        var done = false

        vm.delete { done = true }
        runCurrent()

        assertEquals(listOf(2L, 3L, 1L), connectionManager.disconnected)
        assertEquals(setOf(1L, 2L, 3L), prefs.revoked)
        assertEquals(listOf(2L, 3L, 1L), FakeAvatarController.cleared)
        assertEquals(listOf("delete:1"), repo.operations)
        assertTrue(repo.networks.isEmpty())
        assertTrue(done)
    }

    @Test
    fun passwordOnlyRootEdit_updatesWithoutMirrorWarning() = runTest {
        val repo = FakeNetworkRepository(listOf(root(), child(2)))
        val vm = loadedVm(repo)
        var done = false
        vm.editAuth(AuthForm(mode = AuthMode.PLAIN, saslUser = "motd", saslPassword = "new-password"))

        vm.save { done = true }
        runCurrent()

        assertNull(vm.state.value.pendingBouncerIdentityChange)
        assertEquals(listOf("update:1"), repo.operations)
        assertTrue(done)
    }

    @Test
    fun directNetworkIdentityChange_doesNotQueryOrWarnAboutMirrors() = runTest {
        val direct = root().copy(role = NetworkRole.DIRECT, parentId = null)
        val repo = FakeNetworkRepository(listOf(direct))
        val vm = loadedVm(repo)
        var done = false
        vm.changeHost()

        vm.save { done = true }
        runCurrent()

        assertNull(vm.state.value.pendingBouncerIdentityChange)
        assertEquals(listOf("update:1"), repo.operations)
        assertTrue(done)
    }

    @Test
    fun changing_an_eligible_direct_endpoint_revokes_enrollment_before_save() = runTest {
        val directLibera = root(host = "irc.libera.chat", port = 6697).copy(
            role = NetworkRole.DIRECT,
            parentId = null,
        )
        val repo = FakeNetworkRepository(listOf(directLibera))
        val prefs = FakePresetEnrollmentPrefs()
        val vm = loadedVm(repo, prefs)
        vm.editServer(vm.state.value.server.copy(host = "irc.example.org"))

        vm.save {}
        runCurrent()

        assertEquals(setOf(1L), prefs.revoked)
        assertEquals("irc.example.org", repo.networks.getValue(1).host)
    }

    @Test
    fun autoConnectEdit_isStagedUntilSave() = runTest {
        val repo = FakeNetworkRepository(listOf(root()))
        val vm = loadedVm(repo)

        vm.setAutoConnect(false)
        runCurrent()

        assertTrue(repo.operations.isEmpty())
        assertTrue(vm.state.value.hasUnsavedChanges)
        assertTrue(vm.state.value.canSave)

        vm.save {}
        runCurrent()

        assertEquals(listOf("update:1"), repo.operations)
        assertFalse(repo.networks.getValue(1).autoConnect)
    }

    @Test
    fun unavailableAvatarPublishing_isNotAttempted_andShowsFailure() = runTest {
        val repo = FakeNetworkRepository(listOf(root()))
        val ready = IrcClientState.Ready("motd", emptySet(), emptyMap())
        val vm = NetworkSettingsViewModel(
            repo,
            FakeConnectionManager(mapOf(1L to ready)),
            FakePresetEnrollmentPrefs(),
            FakeAvatarPrefs(),
            FakeAvatarController,
        )
        vm.init(1)
        runCurrent()
        vm.editAvatarUrl("https://example.com/avatar.png")

        vm.publishAvatar()
        runCurrent()

        assertTrue(FakeAvatarController.published.isEmpty())
        assertTrue(vm.state.value.avatarPublishError)
    }

    @Test
    fun lateNetworkLookup_preservesReadyAndAvatarCapabilityState() = runTest {
        val root = root()
        val lookupStarted = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        val repo = object : NetworkRepository {
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
        FakeAvatarController.available = true
        val vm = NetworkSettingsViewModel(
            repo,
            FakeConnectionManager(mapOf(1L to ready)),
            FakePresetEnrollmentPrefs(),
            FakeAvatarPrefs(),
            FakeAvatarController,
        )

        vm.init(1)
        runCurrent()
        assertTrue(lookupStarted.isCompleted)
        assertTrue(vm.state.value.connState is IrcClientState.Ready)
        assertTrue(vm.state.value.avatarPublishingAvailable)

        releaseLookup.complete(Unit)
        runCurrent()

        assertTrue(vm.state.value.connState is IrcClientState.Ready)
        assertTrue(vm.state.value.avatarPublishingAvailable)
        assertEquals(root, vm.state.value.entity)
    }
}
