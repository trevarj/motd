package io.github.trevarj.motd.ui.invite

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
import io.github.trevarj.motd.invite.JoinInviteCodec
import io.github.trevarj.motd.invite.JoinInviteV1
import io.github.trevarj.motd.invite.JoinInviteV2
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
            val event = async(start = CoroutineStart.UNDISPATCHED) { vm.events.first() }

            vm.init(JoinInviteCodec.encode(invite))
            vm.continueToIdentity()
            vm.editNick("alice")
            vm.connect()

            val opened =
                try {
                    withContext(Dispatchers.Default) { withTimeout(5_000) { event.await() } }
                } catch (error: Exception) {
                    throw AssertionError("state=${vm.state.value} rows=${networkRepo.rows.value} joins=${connections.joins}", error)
                }
            assertEquals(JoinInviteEvent.OpenBuffer(99), opened)
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
            val event = async(start = CoroutineStart.UNDISPATCHED) { vm.events.first() }
            vm.init(
                JoinInviteCodec.encode(
                    JoinInviteV2(networkName = "Ergo", host = "irc.example", port = 6697, contactNick = "inviter"),
                ),
            )
            vm.continueToIdentity()
            vm.editNick("new-user")
            vm.connect()

            assertEquals(
                JoinInviteEvent.OpenBuffer(77),
                withContext(Dispatchers.Default) { withTimeout(5_000) { event.await() } },
            )
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
            val event = async(start = CoroutineStart.UNDISPATCHED) { vm.events.first() }
            vm.init(
                JoinInviteCodec.encode(
                    JoinInviteV2(networkName = "Ergo", host = "irc.example", port = 6697, contactNick = "inviter"),
                ),
            )
            vm.continueToIdentity()
            vm.editNick("ignored")
            vm.connect()

            assertEquals(
                JoinInviteEvent.OpenBuffer(77),
                withContext(Dispatchers.Default) { withTimeout(5_000) { event.await() } },
            )
            assertEquals(listOf(7L to "inviter"), connections.queries)
            assertEquals(0, connections.joins)
            assertEquals(1, networkRepo.rows.value.size)
        }

    @Test
    fun `cancel after failed connection removes only provisional network`() =
        runTest {
            val networkRepo = FakeNetworks()
            val bufferRepo = FakeBuffers()
            val connections = FakeConnections(bufferRepo, failConnection = true)
            val enrollment = InviteEnrollmentStore(ApplicationProvider.getApplicationContext())
            enrollment.clearNetwork(42)
            val vm = JoinInviteViewModel(networkRepo, bufferRepo, connections, FakeCerts(), enrollment, FakeOnboarding())
            vm.init(JoinInviteCodec.encode(JoinInviteV1(networkName = "Ergo", host = "irc.example", port = 6697, channel = "#friends")))
            vm.continueToIdentity()
            vm.editNick("alice")
            vm.connect()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.state.first { it.phase == JoinInvitePhase.FAILED } }
            }
            val done = CompletableDeferred<Unit>()
            vm.cancel { done.complete(Unit) }
            withContext(Dispatchers.Default) { withTimeout(5_000) { done.await() } }

            assertTrue(networkRepo.rows.value.isEmpty())
            assertEquals(false, enrollment.isProvisionalNetwork(42))
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
            val event = async(start = CoroutineStart.UNDISPATCHED) { vm.events.first() }
            vm.init(JoinInviteCodec.encode(JoinInviteV1(networkName = "Ergo", host = "irc.example", port = 6697, channel = "#friends")))
            vm.continueToIdentity()
            vm.editNick("ignored")
            vm.connect()

            assertEquals(
                JoinInviteEvent.OpenBuffer(99),
                withContext(Dispatchers.Default) { withTimeout(5_000) { event.await() } },
            )
            assertEquals(0, connections.joins)
            assertEquals(1, networkRepo.rows.value.size)
        }

    private class FakeConnections(
        private val buffers: FakeBuffers,
        private val failConnection: Boolean = false,
    ) : NoopConnectionManager() {
        override val channelJoinOutcomes = MutableSharedFlow<io.github.trevarj.motd.service.ChannelJoinOutcome>()
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
    ) : NetworkRepository {
        val rows = MutableStateFlow(initial)

        override fun observeNetworks(): Flow<List<NetworkEntity>> = rows

        override suspend fun addNetwork(n: NetworkEntity): Long {
            val id = 42L
            rows.value = rows.value + n.copy(id = id)
            return id
        }

        override suspend fun updateNetwork(n: NetworkEntity) {
            rows.value = rows.value.map { if (it.id == n.id) n else it }
        }

        override suspend fun deleteNetwork(id: Long) {
            rows.value = rows.value.filterNot { it.id == id }
        }

        override suspend fun reorderNetworks(orderedIds: List<Long>) = Unit

        override suspend fun networkById(id: Long): NetworkEntity? = rows.value.firstOrNull { it.id == id }

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

        override suspend fun setMuted(
            id: Long,
            muted: Boolean,
        ): MuteBacklogSuppression? = null

        override suspend fun setLayoutDensityOverride(
            id: Long,
            layout: LayoutDensity?,
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

    private class FakeOnboarding : OnboardingPrefs {
        val completedState = MutableStateFlow(false)
        override val completed: Flow<Boolean> = completedState

        override suspend fun markCompleted() {
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
