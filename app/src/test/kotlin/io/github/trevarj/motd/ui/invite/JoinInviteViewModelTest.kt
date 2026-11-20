package io.github.trevarj.motd.ui.invite

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.data.prefs.HistorySyncMode
import io.github.trevarj.motd.data.prefs.InviteEnrollmentStore
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.OnboardingPrefs
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.data.repo.networkIdentityKey
import io.github.trevarj.motd.invite.JoinInviteCodec
import io.github.trevarj.motd.invite.JoinInviteV1
import io.github.trevarj.motd.invite.JoinInviteV2
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ChannelJoinOutcome
import io.github.trevarj.motd.service.ChannelJoinRejectionKind
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class JoinInviteViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Default)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `guest flow creates network persists key and opens authoritative joined buffer`() =
        runTest {
            val networkRepo = FakeNetworks()
            val bufferRepo = FakeBuffers()
            val connections = FakeConnections(bufferRepo)
            val enrollment = InviteEnrollmentStore(ApplicationProvider.getApplicationContext())
            enrollment.clearNetwork(42)
            val onboarding = FakeOnboarding()
            val vm = JoinInviteViewModel(networkRepo, bufferRepo, connections, FakeCerts(), enrollment, onboarding)
            val invite = JoinInviteV1(networkName = "Ergo", host = "irc.example", port = 6697, channel = "#friends", channelKey = "secret")

            vm.init(JoinInviteCodec.encode(invite))
            vm.continueToIdentity()
            vm.editNick("alice")
            vm.connect()

            val opened =
                try {
                    vm.awaitPhase(JoinInvitePhase.READY).pendingBufferId
                } catch (error: Exception) {
                    throw AssertionError("state=${vm.state.value} rows=${networkRepo.rows.value} joins=${connections.joins}", error)
                }
            assertEquals(99L, opened)
            assertEquals(99L, vm.state.first { it.pendingBufferId != null }.pendingBufferId)
            vm.onBufferOpened(99)
            assertEquals(null, vm.state.value.pendingBufferId)
            assertEquals(
                "alice",
                networkRepo.rows.value
                    .single()
                    .nick,
            )
            assertEquals("secret", connections.lastKey)
            assertTrue(onboarding.completedState.value)
        }

    @Test
    fun `v2 creates network and opens contact query without joining a channel`() =
        runTest {
            val networkRepo = FakeNetworks()
            val bufferRepo = FakeBuffers()
            val connections = FakeConnections(bufferRepo)
            val onboarding = FakeOnboarding()
            val vm =
                JoinInviteViewModel(
                    networkRepo,
                    bufferRepo,
                    connections,
                    FakeCerts(),
                    InviteEnrollmentStore(ApplicationProvider.getApplicationContext()),
                    onboarding,
                )
            vm.init(
                JoinInviteCodec.encode(
                    JoinInviteV2(networkName = "Ergo", host = "irc.example", port = 6697, contactNick = "inviter"),
                ),
            )
            vm.continueToIdentity()
            vm.editNick("new-user")
            vm.connect()

            assertEquals(77L, vm.awaitPhase(JoinInvitePhase.READY).pendingBufferId)
            assertEquals(listOf(42L to "inviter"), connections.queries)
            assertEquals(0, connections.joins)
            assertEquals(1, networkRepo.rows.value.size)
            assertTrue(onboarding.completedState.value)
        }

    @Test
    fun `v2 reuses compatible network and opens contact query without channel lookup`() =
        runTest {
            val networkRepo = FakeNetworks(listOf(direct(id = 7)))
            val bufferRepo = FakeBuffers(joinedId = 99, joinedNames = setOf("#friends"))
            val connections = FakeConnections(bufferRepo)
            val vm =
                JoinInviteViewModel(
                    networkRepo,
                    bufferRepo,
                    connections,
                    FakeCerts(),
                    InviteEnrollmentStore(ApplicationProvider.getApplicationContext()),
                    FakeOnboarding(),
                )
            vm.init(
                JoinInviteCodec.encode(
                    JoinInviteV2(networkName = "Ergo", host = "irc.example", port = 6697, contactNick = "inviter"),
                ),
            )
            vm.continueToIdentity()
            vm.editNick("ignored")
            vm.connect()

            assertEquals(77L, vm.awaitPhase(JoinInvitePhase.READY).pendingBufferId)
            assertEquals(listOf(7L to "inviter"), connections.queries)
            assertEquals(0, connections.joins)
            assertEquals(1, networkRepo.rows.value.size)
        }

    @Test
    fun `identity back preserves nick and review exit cleans up failed invite after retry`() =
        runTest {
            val existing = direct(id = 7).copy(host = "unrelated.example")
            val networkRepo = FakeNetworks(listOf(existing))
            val bufferRepo = FakeBuffers()
            val connections = FakeConnections(bufferRepo, failConnection = true)
            val enrollment = InviteEnrollmentStore(ApplicationProvider.getApplicationContext())
            enrollment.clearNetwork(42)
            val certs = FakeCerts()
            val vm = JoinInviteViewModel(networkRepo, bufferRepo, connections, certs, enrollment, FakeOnboarding())
            val invite =
                JoinInviteV1(
                    networkName = "Ergo",
                    host = "irc.example",
                    port = 6697,
                    channel = "#friends",
                    certSha256 = "ab".repeat(32),
                )
            vm.init(JoinInviteCodec.encode(invite))
            vm.continueToIdentity()
            vm.editNick("alice")
            vm.connect()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.state.first { it.phase == JoinInvitePhase.FAILED } }
            }
            assertTrue(enrollment.isProvisionalNetwork(42))
            assertTrue(enrollment.hasImportedCertPin(42))
            assertEquals(invite.certSha256, certs.pinnedFor(invite.host, invite.port))

            vm.retry()
            vm.onBack()
            assertEquals(JoinInvitePhase.REVIEW, vm.state.value.phase)
            vm.continueToIdentity()
            assertEquals("alice", vm.state.value.nick)
            vm.onBack()
            assertEquals(false, vm.state.value.exitRequested)
            assertTrue(enrollment.isProvisionalNetwork(42))

            vm.onBack()
            vm.awaitExit()
            assertEquals(listOf(existing), networkRepo.rows.value)
            assertEquals(false, enrollment.isProvisionalNetwork(42))
            assertEquals(false, enrollment.hasImportedCertPin(42))
            assertEquals(null, certs.pinnedFor(invite.host, invite.port))
        }

    @Test
    fun `failed join back restores the channel key on a reused network before exit`() =
        runTest {
            val existing = direct(id = 7)
            val networkRepo = FakeNetworks(listOf(existing))
            val enrollment = InviteEnrollmentStore(ApplicationProvider.getApplicationContext())
            enrollment.clearNetwork(existing.id)
            enrollment.putChannelKey(existing.id, "#friends", "old-key")
            val ready = IrcClientState.Ready("alice", emptySet(), emptyMap())
            val connections =
                object : NoopConnectionManager(initialStates = mapOf(existing.id to ready)) {
                    override val channelJoinOutcomes = MutableSharedFlow<ChannelJoinOutcome>(replay = 1)

                    override suspend fun joinChannel(
                        networkId: Long,
                        channel: String,
                        key: String?,
                        rememberKey: Boolean,
                    ): Boolean {
                        if (rememberKey && !key.isNullOrBlank()) enrollment.putChannelKey(networkId, channel, key)
                        channelJoinOutcomes.emit(
                            ChannelJoinOutcome.Rejected(
                                networkId,
                                channel,
                                "Join requires an account",
                                "477",
                                ChannelJoinRejectionKind.ACCOUNT_REQUIRED,
                            ),
                        )
                        return true
                    }
                }
            val vm = JoinInviteViewModel(networkRepo, FakeBuffers(), connections, FakeCerts(), enrollment, FakeOnboarding())
            val invite = JoinInviteV1(networkName = "Ergo", host = "irc.example", port = 6697, channel = "#friends", channelKey = "new-key")
            vm.init(JoinInviteCodec.encode(invite))
            vm.continueToIdentity()
            vm.editNick("alice")
            vm.connect()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.state.first { it.phase == JoinInvitePhase.FAILED } }
            }
            assertTrue(vm.state.value.accountSetupAvailable)
            assertEquals("new-key", enrollment.channelKey(existing.id, "#friends"))

            vm.onBack()
            vm.awaitExit()
            assertEquals("old-key", enrollment.channelKey(existing.id, "#friends"))
            assertEquals(listOf(existing), networkRepo.rows.value)
        }

    @Test
    fun `connecting back waits for cleanup and retains exit until acknowledged`() =
        runTest {
            val inserted = CompletableDeferred<Unit>()
            val finishInsert = CompletableDeferred<Unit>()
            val networkRepo =
                FakeNetworks(
                    afterInsert = {
                        inserted.complete(Unit)
                        finishInsert.await()
                    },
                )
            val bufferRepo = FakeBuffers()
            val enrollment = InviteEnrollmentStore(ApplicationProvider.getApplicationContext())
            enrollment.clearNetwork(42)
            val vm = JoinInviteViewModel(networkRepo, bufferRepo, FakeConnections(bufferRepo), FakeCerts(), enrollment, FakeOnboarding())
            vm.init(JoinInviteCodec.encode(JoinInviteV1(networkName = "Ergo", host = "irc.example", port = 6697, channel = "#friends")))
            vm.continueToIdentity()
            vm.editNick("alice")
            vm.connect()
            try {
                withContext(Dispatchers.Default) { withTimeout(5_000) { inserted.await() } }
                assertEquals(JoinInvitePhase.CONNECTING, vm.state.value.phase)
                vm.onBack()
                vm.onBack()
                assertEquals(false, vm.state.value.exitRequested)
                finishInsert.complete(Unit)
                vm.awaitExit()
                assertTrue(networkRepo.rows.value.isEmpty())
                assertTrue(vm.state.first().exitRequested)
                vm.onExitHandled()
                vm.onBack()
                assertEquals(false, vm.state.value.exitRequested)
            } finally {
                finishInsert.complete(Unit)
            }
        }

    @Test
    fun `duplicate invite reuses joined direct network without another join`() =
        runTest {
            val existing = direct(id = 7)
            val networkRepo = FakeNetworks(listOf(existing))
            val bufferRepo = FakeBuffers(joinedId = 99, joinedNames = setOf("#friends"))
            val connections = FakeConnections(bufferRepo)
            connections.states.value = mapOf(7L to IrcClientState.Ready("existing", emptySet(), emptyMap()))
            val vm =
                JoinInviteViewModel(
                    networkRepo,
                    bufferRepo,
                    connections,
                    FakeCerts(),
                    InviteEnrollmentStore(ApplicationProvider.getApplicationContext()),
                    FakeOnboarding(),
                )
            vm.init(JoinInviteCodec.encode(JoinInviteV1(networkName = "Ergo", host = "irc.example", port = 6697, channel = "#friends")))
            vm.continueToIdentity()
            vm.editNick("ignored")
            vm.connect()

            assertEquals(99L, vm.awaitPhase(JoinInvitePhase.READY).pendingBufferId)
            assertEquals(0, connections.joins)
            assertEquals(1, networkRepo.rows.value.size)
        }

    @Test
    fun `invite cannot claim a deduplicated incompatible network`() =
        runTest {
            val existing = direct(id = 7).copy(tls = false, nick = "alice", username = "alice")
            val networkRepo = FakeNetworks(listOf(existing))
            val buffers = FakeBuffers()
            val connections = FakeConnections(buffers)
            val enrollment = InviteEnrollmentStore.fromDataStore(FakeEnrollmentDataStore())
            val vm = JoinInviteViewModel(networkRepo, buffers, connections, FakeCerts(), enrollment, FakeOnboarding())
            vm.init(JoinInviteCodec.encode(JoinInviteV1(networkName = "Ergo", host = "irc.example", port = 6697, channel = "#friends")))
            vm.continueToIdentity()
            vm.editNick("alice")
            vm.connect()

            assertEquals(null, vm.awaitPhase(JoinInvitePhase.FAILED).networkId)
            vm.onBack()
            vm.awaitExit()
            assertEquals(listOf(existing), networkRepo.rows.value)
            assertTrue(connections.states.value.isEmpty())
            assertEquals(false, enrollment.isProvisionalNetwork(existing.id))
        }

    @Test
    fun `back during completion lookup still rolls back the invite`() =
        runTest {
            val lookupStarted = CompletableDeferred<Unit>()
            val networkRepo =
                FakeNetworks(
                    beforeLookup = {
                        lookupStarted.complete(Unit)
                        awaitCancellation()
                    },
                )
            val buffers = FakeBuffers(joinedId = 99, joinedNames = setOf("#friends"))
            val enrollment = InviteEnrollmentStore.fromDataStore(FakeEnrollmentDataStore())
            val certs = FakeCerts()
            val onboarding = FakeOnboarding()
            val vm = JoinInviteViewModel(networkRepo, buffers, FakeConnections(buffers), certs, enrollment, onboarding)
            val invite =
                JoinInviteV1(
                    networkName = "Ergo",
                    host = "irc.example",
                    port = 6697,
                    channel = "#friends",
                    certSha256 = "ab".repeat(32),
                )
            vm.init(JoinInviteCodec.encode(invite))
            vm.continueToIdentity()
            vm.editNick("alice")
            vm.connect()
            withContext(Dispatchers.Default) { withTimeout(5_000) { lookupStarted.await() } }
            assertTrue(enrollment.isProvisionalNetwork(42))
            assertTrue(enrollment.hasImportedCertPin(42))

            vm.onBack()
            vm.awaitExit()
            assertTrue(networkRepo.rows.value.isEmpty())
            assertEquals(null, certs.pinnedFor(invite.host, invite.port))
            assertEquals(null, vm.state.value.pendingBufferId)
            assertEquals(false, onboarding.completedState.value)
        }

    @Test
    fun `back during a resumed pin marker write clears it without deleting the reused network`() =
        runTest {
            val markerStarted = CompletableDeferred<Unit>()
            val existing = direct(id = 7)
            val networkRepo = FakeNetworks(listOf(existing))
            val dataStore = FakeEnrollmentDataStore()
            val enrollment = InviteEnrollmentStore.fromDataStore(dataStore)
            val certs = FakeCerts()
            val pin = "ab".repeat(32)
            certs.pin(existing.host, existing.port, pin)
            enrollment.setImportedCertPin(existing.id, true)
            dataStore.beforeNextUpdate = {
                markerStarted.complete(Unit)
                awaitCancellation()
            }
            val buffers = FakeBuffers()
            val connections = FakeConnections(buffers)
            val vm = JoinInviteViewModel(networkRepo, buffers, connections, certs, enrollment, FakeOnboarding())
            vm.init(JoinInviteCodec.encode(JoinInviteV1(networkName = "Ergo", host = existing.host, port = existing.port, channel = "#friends")))
            vm.continueToIdentity()
            vm.editNick("alice")
            vm.connect()
            withContext(Dispatchers.Default) { withTimeout(5_000) { markerStarted.await() } }
            assertEquals(existing.id, vm.state.value.networkId)

            vm.onBack()
            vm.awaitExit()
            assertEquals(listOf(existing), networkRepo.rows.value)
            assertEquals(false, enrollment.hasImportedCertPin(existing.id))
            assertEquals(null, certs.pinnedFor(existing.host, existing.port))
            assertTrue(connections.states.value.isEmpty())
        }

    @Test
    fun `failed enrollment commit preserves rollback and a failed exit can be retried`() =
        runTest {
            val dataStore = FakeEnrollmentDataStore()
            val enrollment = InviteEnrollmentStore.fromDataStore(dataStore)
            enrollment.putChannelKey(42, "#friends", "old-key")
            val networkRepo =
                FakeNetworks(
                    beforeLookup = {
                        enrollment.putChannelKey(42, "#friends", "new-key")
                        dataStore.beforeNextUpdate = { error("Enrollment write failed") }
                    },
                )
            val buffers = FakeBuffers()
            val certs = FakeCerts()
            val onboarding = FakeOnboarding()
            val vm = JoinInviteViewModel(networkRepo, buffers, FakeConnections(buffers), certs, enrollment, onboarding)
            val invite =
                JoinInviteV1(
                    networkName = "Ergo",
                    host = "irc.example",
                    port = 6697,
                    channel = "#friends",
                    channelKey = "new-key",
                    certSha256 = "ab".repeat(32),
                )
            vm.init(JoinInviteCodec.encode(invite))
            vm.continueToIdentity()
            vm.editNick("alice")
            vm.connect()

            assertEquals("Enrollment write failed", vm.awaitPhase(JoinInvitePhase.FAILED).error)
            assertEquals(false, onboarding.completedState.value)
            assertTrue(enrollment.isProvisionalNetwork(42))
            assertTrue(enrollment.hasImportedCertPin(42))
            assertEquals("new-key", enrollment.channelKey(42, "#friends"))

            dataStore.beforeNextUpdate = { error("Cleanup write failed") }
            vm.onBack()
            withContext(Dispatchers.Default) { withTimeout(5_000) { vm.state.first { it.error == "Cleanup write failed" } } }
            assertEquals(false, vm.state.value.exitRequested)
            vm.retry()
            vm.awaitExit()
            assertTrue(networkRepo.rows.value.isEmpty())
            assertEquals(null, certs.pinnedFor(invite.host, invite.port))
            assertEquals("old-key", enrollment.channelKey(42, "#friends"))
        }

    @Test
    fun `onboarding failure retries finalization without reconnecting or committing twice`() =
        runTest {
            val dataStore = FakeEnrollmentDataStore()
            val enrollment = InviteEnrollmentStore.fromDataStore(dataStore)
            val networkRepo = FakeNetworks()
            val buffers = FakeBuffers()
            val connections = FakeConnections(buffers)
            var attempts = 0
            val onboarding = FakeOnboarding { if (++attempts == 1) error("Onboarding write failed") }
            val vm = JoinInviteViewModel(networkRepo, buffers, connections, FakeCerts(), enrollment, onboarding)
            vm.init(JoinInviteCodec.encode(JoinInviteV1(networkName = "Ergo", host = "irc.example", port = 6697, channel = "#friends")))
            vm.continueToIdentity()
            vm.editNick("alice")
            vm.connect()

            assertEquals("Onboarding write failed", vm.awaitPhase(JoinInvitePhase.FAILED).error)
            assertEquals(false, enrollment.isProvisionalNetwork(42))
            assertEquals(null, vm.state.value.pendingBufferId)

            dataStore.beforeNextUpdate = { error("Enrollment must not be committed twice") }
            vm.retry()
            assertEquals(99L, vm.awaitPhase(JoinInvitePhase.READY).pendingBufferId)
            assertEquals(1, connections.joins)
            assertEquals(
                42L,
                networkRepo.rows.value
                    .single()
                    .id,
            )
            assertTrue(onboarding.completedState.value)
        }

    private suspend fun JoinInviteViewModel.awaitExit() {
        withContext(Dispatchers.Default) { withTimeout(5_000) { state.first { it.exitRequested } } }
    }

    private suspend fun JoinInviteViewModel.awaitPhase(phase: JoinInvitePhase): JoinInviteUiState = withContext(Dispatchers.Default) { withTimeout(5_000) { state.first { it.phase == phase } } }

    private class FakeEnrollmentDataStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        var beforeNextUpdate: (suspend () -> Unit)? = null

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val beforeUpdate = beforeNextUpdate
            beforeNextUpdate = null
            beforeUpdate?.invoke()
            return transform(data.value).also { data.value = it }
        }
    }

    private class FakeConnections(
        private val buffers: FakeBuffers,
        private val failConnection: Boolean = false,
    ) : NoopConnectionManager() {
        override val channelJoinOutcomes = MutableSharedFlow<ChannelJoinOutcome>()
        var joins = 0
        var lastKey: String? = null
        val queries = mutableListOf<Pair<Long, String>>()

        override suspend fun connect(networkId: Long) {
            states.value =
                states.value +
                (
                    networkId to
                        if (failConnection) {
                            IrcClientState.Failed("offline", false)
                        } else {
                            IrcClientState.Ready("alice", emptySet(), emptyMap())
                        }
                )
        }

        override suspend fun ensureQueryBuffer(
            networkId: Long,
            nick: String,
        ): Long {
            queries += networkId to nick
            return 77
        }

        override suspend fun joinChannel(
            networkId: Long,
            channel: String,
            key: String?,
            rememberKey: Boolean,
        ): Boolean {
            joins++
            lastKey = key
            buffers.joinedId = 99
            buffers.joinedNames.value = setOf(channel)
            return true
        }
    }

    private class FakeNetworks(
        initial: List<NetworkEntity> = emptyList(),
        private val afterInsert: suspend () -> Unit = {},
        private val beforeLookup: suspend () -> Unit = {},
    ) : NetworkRepository {
        val rows = MutableStateFlow(initial)

        override fun observeNetworks(): Flow<List<NetworkEntity>> = rows

        override suspend fun addNetwork(
            n: NetworkEntity,
            requireNew: Boolean,
        ): Long {
            val key = networkIdentityKey(n)
            rows.value.firstOrNull { networkIdentityKey(it) == key }?.let {
                check(!requireNew)
                return it.id
            }
            val id = 42L
            rows.value = rows.value + n.copy(id = id)
            afterInsert()
            return id
        }

        override suspend fun updateNetwork(n: NetworkEntity) {
            rows.value = rows.value.map { if (it.id == n.id) n else it }
        }

        override suspend fun deleteNetwork(id: Long) {
            rows.value = rows.value.filterNot { it.id == id }
        }

        override suspend fun reorderNetworks(orderedIds: List<Long>) = Unit

        override suspend fun networkById(id: Long): NetworkEntity? {
            beforeLookup()
            return rows.value.firstOrNull { it.id == id }
        }

        override suspend fun childrenOf(rootId: Long): List<NetworkEntity> = emptyList()
    }

    private class FakeBuffers(
        var joinedId: Long? = null,
        joinedNames: Set<String> = emptySet(),
    ) : BufferRepository {
        val joinedNames = MutableStateFlow(joinedNames)

        override fun observeChatList(): Flow<List<ChatListRow>> = flowOf(emptyList())

        override fun observeJoinedChannelNames(networkId: Long): Flow<Set<String>> = joinedNames

        override suspend fun joinedBufferId(
            networkId: Long,
            normalizedChannel: String,
        ): Long? = joinedId

        override fun observeBuffer(id: Long): Flow<BufferEntity?> = flowOf(null)

        override fun observeMembers(bufferId: Long): Flow<List<MemberEntity>> = flowOf(emptyList())

        override suspend fun setPinned(
            id: Long,
            pinned: Boolean,
        ) = Unit

        override suspend fun setAnchored(
            id: Long,
            anchored: Boolean,
        ) = Unit

        override suspend fun reorderAnchored(orderedIds: List<Long>) = Unit

        override suspend fun setMuted(
            id: Long,
            muted: Boolean,
        ): MuteBacklogSuppression? = null

        override suspend fun setLayoutDensityOverride(
            id: Long,
            layout: LayoutDensity?,
        ): Boolean = true

        override suspend fun setCompactNickBracketsOverride(
            id: Long,
            brackets: io.github.trevarj.motd.data.prefs.CompactNickBrackets?,
        ): Boolean = true

        override suspend fun setMessageBackgroundOpacityOverride(
            id: Long,
            percent: Int?,
        ): Boolean = true

        override suspend fun setMessageBackgroundBlurOverride(
            id: Long,
            intensity: Int?,
        ): Boolean = true

        override suspend fun setPresenceModeOverride(
            id: Long,
            mode: PresenceMode?,
        ): Boolean = true

        override suspend fun setHistorySyncModeOverride(
            id: Long,
            mode: HistorySyncMode?,
        ): Boolean = error("Unexpected history sync mode override write")

        override suspend fun deleteBuffer(id: Long) = Unit
    }

    private class FakeCerts : CertTrustStore {
        private val pins = mutableMapOf<Pair<String, Int>, String>()

        override suspend fun pinnedFor(
            host: String,
            port: Int,
        ): String? = pins[host to port]

        override suspend fun isPinned(
            host: String,
            port: Int,
            sha256: String,
        ): Boolean = pinnedFor(host, port) == sha256

        override suspend fun pin(
            host: String,
            port: Int,
            sha256: String,
        ) {
            pins[host to port] = sha256
        }

        override suspend fun unpin(
            host: String,
            port: Int,
        ) {
            pins.remove(host to port)
        }
    }

    private class FakeOnboarding(
        private val beforeComplete: () -> Unit = {},
    ) : OnboardingPrefs {
        val completedState = MutableStateFlow(false)
        override val completed: Flow<Boolean> = completedState
        override val communityInvitationHandled = MutableStateFlow(false)

        override suspend fun markCommunityInvitationHandled() {
            communityInvitationHandled.value = true
        }

        override suspend fun markCompleted() {
            beforeComplete()
            completedState.value = true
        }
    }

    private fun direct(id: Long) =
        NetworkEntity(
            id = id,
            name = "Ergo",
            role = NetworkRole.DIRECT,
            host = "irc.example",
            port = 6697,
            nick = "existing",
            username = "existing",
            realname = "Existing",
        )
}
