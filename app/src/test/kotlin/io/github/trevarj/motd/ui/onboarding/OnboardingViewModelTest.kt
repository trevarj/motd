package io.github.trevarj.motd.ui.onboarding

import io.github.trevarj.motd.bouncer.SojuLoginForm
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.ChatWallpaper
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.HistorySyncDepth
import io.github.trevarj.motd.data.prefs.HistorySyncMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.OnboardingPrefs
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.PresetEnrollmentPrefs
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.client.BouncerNetwork
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeNetworkRepository : NetworkRepository {
        private val ids = AtomicLong()
        val rows = mutableMapOf<Long, NetworkEntity>()

        override fun observeNetworks() = flowOf(rows.values.toList())

        override suspend fun addNetwork(n: NetworkEntity): Long = ids.incrementAndGet().also { rows[it] = n.copy(id = it) }

        override suspend fun updateNetwork(n: NetworkEntity) {
            rows[n.id] = n
        }

        override suspend fun deleteNetwork(id: Long) {
            rows.remove(id)
        }

        override suspend fun reorderNetworks(orderedIds: List<Long>) = Unit

        override suspend fun networkById(id: Long) = rows[id]

        override suspend fun childrenOf(rootId: Long) = rows.values.filter { it.parentId == rootId }
    }

    private class FakeConnectionManager(
        private val log: MutableList<String>? = null,
    ) : NoopConnectionManager() {
        override val connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(emptyMap())

        fun connecting(id: Long) {
            connectionStates.value += id to IrcClientState.Connecting
        }

        fun ready(id: Long) {
            connectionStates.value += id to IrcClientState.Ready("motd", emptySet(), emptyMap())
        }

        override suspend fun connect(networkId: Long) {
            log?.add("connect:$networkId")
        }

        override suspend fun sendMessage(
            bufferId: Long,
            text: String,
            replyToEventId: Long?,
            channelContext: String?,
        ) = SendAcceptance.Accepted(emptyList())

        override suspend fun ensureQueryBuffer(
            networkId: Long,
            nick: String,
        ) = 0L

        override suspend fun ensureServerBuffer(networkId: Long) = 0L
    }

    private class FakeBouncerOperations : OnboardingBouncerOperations {
        val snapshots = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
        val snapshotsByRoot = mutableMapOf<Long, MutableStateFlow<Map<String, Map<String, String>>>>()
        var listResponse = CompletableDeferred<List<BouncerNetwork>>()
        var addResponse = CompletableDeferred<String>()
        val listResponses = mutableMapOf<Long, CompletableDeferred<List<BouncerNetwork>>>()
        val addResponses = mutableMapOf<Long, CompletableDeferred<String>>()
        val ignoreListCancellation = mutableSetOf<Long>()
        val ignoreAddCancellation = mutableSetOf<Long>()
        var listCalls = 0
        var addCalls = 0

        override fun snapshots(rootNetworkId: Long) = snapshotsByRoot[rootNetworkId] ?: snapshots

        override suspend fun list(rootNetworkId: Long): List<BouncerNetwork> {
            listCalls += 1
            val response = listResponses[rootNetworkId] ?: listResponse
            return if (rootNetworkId in ignoreListCancellation) {
                kotlinx.coroutines.withContext(NonCancellable) { response.await() }
            } else {
                response.await()
            }
        }

        override suspend fun add(
            rootNetworkId: Long,
            name: String,
            host: String,
        ): String {
            addCalls += 1
            val response = addResponses[rootNetworkId] ?: addResponse
            return if (rootNetworkId in ignoreAddCancellation) {
                kotlinx.coroutines.withContext(NonCancellable) { response.await() }
            } else {
                response.await()
            }
        }
    }

    private object FakePresetPrefs : PresetEnrollmentPrefs {
        override suspend fun markLiberaEligible(networkId: Long) = Unit

        override suspend fun claimLiberaMotdJoin(networkId: Long) = false

        override suspend fun revokeLiberaEligibility(networkId: Long) = Unit
    }

    private object FakeOnboardingPrefs : OnboardingPrefs {
        override val completed: Flow<Boolean> = flowOf(false)

        override suspend fun markCompleted() = Unit
    }

    /** Records the persisted depth and the ordering against child connects. */
    private class FakeSettingsRepository(
        private val log: MutableList<String>? = null,
    ) : SettingsRepository {
        var historySyncDepth: HistorySyncDepth? = null
        override val settings = MutableStateFlow(Settings())

        override suspend fun setThemeMode(m: ThemeMode) = Unit

        override suspend fun setDynamicColor(enabled: Boolean) = Unit

        override suspend fun setDeliveryMode(m: DeliveryMode) = Unit

        override suspend fun setLayoutDensity(d: LayoutDensity) = Unit

        override suspend fun setNickColorsEnabled(enabled: Boolean) = Unit

        override suspend fun setNickColorPalette(p: NickColorPalette) = Unit

        override suspend fun setNickColorOverride(
            nick: String,
            hue: Int?,
        ) = Unit

        override suspend fun setFriend(
            nick: String,
            isFriend: Boolean,
        ) = Unit

        override suspend fun setFool(
            nick: String,
            isFool: Boolean,
        ) = Unit

        override suspend fun setFoolsMode(m: FoolsMode) = Unit

        override suspend fun setPresenceMode(m: PresenceMode) = Unit

        override suspend fun setAvatarStyle(style: AvatarStyle) = Unit

        override suspend fun setChatWallpaper(w: ChatWallpaper) = Unit

        override suspend fun setShowComposerEmoji(show: Boolean) = Unit

        override suspend fun setShowComposerFormattingTools(show: Boolean) = Unit

        override suspend fun setChatSoundsEnabled(enabled: Boolean) = Unit

        override suspend fun setHistorySyncDepth(d: HistorySyncDepth) {
            historySyncDepth = d
            log?.add("depth:${d.name}")
        }

        override suspend fun setHistorySyncMode(mode: HistorySyncMode) {
            settings.value = settings.value.copy(historySyncMode = mode)
        }

        override suspend fun setAutoAwayEnabled(enabled: Boolean) = Unit

        override suspend fun setAutoAwayMinutes(minutes: Int) = Unit

        override suspend fun setAutoAwayMessage(message: String) = Unit
    }

    private suspend fun TestScope.readyBouncer(
        operations: FakeBouncerOperations,
        repository: FakeNetworkRepository = FakeNetworkRepository(),
        connections: FakeConnectionManager = FakeConnectionManager(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
    ): OnboardingViewModel {
        val vm =
            OnboardingViewModel(
                repository,
                connections,
                FakePresetPrefs,
                FakeOnboardingPrefs,
                operations,
                settings,
            )
        vm.next()
        vm.chooseConnection(ConnectionChoice.BOUNCER)
        vm.next()
        vm.editServer(ServerForm(host = "soju.example", nick = "motd"))
        vm.next()
        vm.editSojuLogin(SojuLoginForm("motd", "password"))
        vm.next()
        runCurrent()
        connections.ready(vm.state.value.networkId!!)
        runCurrent()
        return vm
    }

    @Test
    fun `list failure is visible and retry loads an empty snapshot`() =
        runTest {
            val operations = FakeBouncerOperations()
            val vm = readyBouncer(operations)
            operations.listResponse.completeExceptionally(IrcDisconnectedException("BOUNCER", null))
            runCurrent()
            assertTrue(vm.state.value.bouncerDiscovery is BouncerDiscoveryState.Failed)

            operations.listResponse = CompletableDeferred<List<BouncerNetwork>>().also { it.complete(emptyList()) }
            vm.retryBouncerDiscovery()
            runCurrent()
            assertEquals(emptyList<BouncerNetworkRow>(), vm.state.value.bouncerNetworks)
            assertTrue(vm.state.value.bouncerDiscovery is BouncerDiscoveryState.Loaded)
            assertEquals(2, operations.listCalls)
        }

    @Test
    fun `server rejected list remains failed when a late passive snapshot arrives`() =
        runTest {
            val operations = FakeBouncerOperations()
            val vm = readyBouncer(operations)
            operations.listResponse.completeExceptionally(IrcCommandException("BOUNCER", "DENIED", "not allowed"))
            runCurrent()
            operations.snapshots.value = mapOf("libera" to mapOf("name" to "Libera"))
            runCurrent()

            val failed = vm.state.value.bouncerDiscovery as BouncerDiscoveryState.Failed
            assertTrue(failed.error is BouncerOperationError.ServerRejected)
            assertEquals(
                "libera",
                vm.state.value.bouncerNetworks
                    .single()
                    .netId,
            )
        }

    @Test
    fun `add failure retains draft duplicate tap is ignored and success clears once`() =
        runTest {
            val operations = FakeBouncerOperations()
            operations.listResponse.complete(emptyList())
            val vm = readyBouncer(operations)
            vm.editBouncerAddDraft(BouncerAddDraft("New", "irc.new.example"))
            vm.addBouncerNetwork()
            vm.addBouncerNetwork()
            assertTrue(vm.state.value.bouncerAdd is BouncerAddState.Submitting)
            runCurrent()
            assertEquals(1, operations.addCalls)

            operations.addResponse.completeExceptionally(IrcCommandException("BOUNCER", "DENIED", "not allowed"))
            runCurrent()
            assertEquals(BouncerAddDraft("New", "irc.new.example"), vm.state.value.bouncerAddDraft)
            assertTrue(vm.state.value.bouncerAdd is BouncerAddState.Failed)

            operations.addResponse = CompletableDeferred<String>().also { it.complete("9") }
            vm.addBouncerNetwork()
            runCurrent()
            assertEquals(BouncerAddDraft(), vm.state.value.bouncerAddDraft)
            assertTrue(vm.state.value.bouncerAdd is BouncerAddState.Success)
            assertEquals(2, operations.addCalls)
        }

    @Test
    fun `add connection loss retains draft`() =
        runTest {
            val operations = FakeBouncerOperations()
            operations.listResponse.complete(emptyList())
            val vm = readyBouncer(operations)
            vm.editBouncerAddDraft(BouncerAddDraft("New", "irc.new.example"))
            vm.addBouncerNetwork()
            operations.addResponse.completeExceptionally(IrcDisconnectedException("BOUNCER", null))
            runCurrent()

            assertTrue(vm.state.value.bouncerAdd is BouncerAddState.Failed)
            assertEquals(BouncerAddDraft("New", "irc.new.example"), vm.state.value.bouncerAddDraft)
        }

    @Test
    fun `late list and add completions from replaced root are ignored`() =
        runTest {
            val operations = FakeBouncerOperations()
            operations.ignoreListCancellation += 1L
            val connections = FakeConnectionManager()
            val vm = readyBouncer(operations, connections = connections)
            val oldRoot = vm.state.value.networkId!!
            vm.editBouncerAddDraft(BouncerAddDraft("Old", "irc.old.example"))
            vm.addBouncerNetwork()
            operations.ignoreAddCancellation += oldRoot

            vm.retryConnect()
            runCurrent()
            val newRoot = vm.state.value.networkId!!
            operations.listResponses[newRoot] = CompletableDeferred<List<BouncerNetwork>>().also { it.complete(emptyList()) }
            connections.ready(newRoot)
            runCurrent()

            operations.listResponse.complete(listOf(BouncerNetwork("old", mapOf("name" to "Old"))))
            operations.addResponse.complete("old")
            runCurrent()

            assertEquals(newRoot, vm.state.value.networkId)
            assertTrue(
                vm.state.value.bouncerNetworks
                    .isEmpty(),
            )
            assertTrue(vm.state.value.bouncerAdd is BouncerAddState.Idle)
        }

    @Test
    fun `same root reconnect rebinds discovery to the replacement client`() =
        runTest {
            val operations = FakeBouncerOperations()
            operations.snapshots.value = mapOf("old" to mapOf("name" to "Old"))
            operations.listResponse.complete(listOf(BouncerNetwork("old", mapOf("name" to "Old"))))
            val connections = FakeConnectionManager()
            val vm = readyBouncer(operations, connections = connections)
            val root = vm.state.value.networkId!!
            runCurrent()
            assertEquals(
                "old",
                vm.state.value.bouncerNetworks
                    .single()
                    .netId,
            )

            val replacementSnapshots =
                MutableStateFlow(
                    mapOf("new" to mapOf("name" to "New")),
                )
            operations.snapshotsByRoot[root] = replacementSnapshots
            operations.listResponses[root] =
                CompletableDeferred<List<BouncerNetwork>>().also {
                    it.complete(listOf(BouncerNetwork("new", mapOf("name" to "New"))))
                }
            connections.connecting(root)
            runCurrent()
            connections.ready(root)
            runCurrent()

            assertEquals(2, operations.listCalls)
            assertEquals(
                "new",
                vm.state.value.bouncerNetworks
                    .single()
                    .netId,
            )
            operations.snapshots.value = mapOf("stale" to mapOf("name" to "Stale"))
            runCurrent()
            assertEquals(
                "new",
                vm.state.value.bouncerNetworks
                    .single()
                    .netId,
            )
        }

    @Test
    fun `finish persists the chosen history depth before connecting the imported children`() =
        runTest {
            val log = mutableListOf<String>()
            val operations = FakeBouncerOperations()
            operations.snapshots.value = mapOf("libera" to mapOf("name" to "Libera"))
            operations.listResponse.complete(listOf(BouncerNetwork("libera", mapOf("name" to "Libera"))))
            val settings = FakeSettingsRepository(log)
            val vm =
                readyBouncer(
                    operations,
                    connections = FakeConnectionManager(log),
                    settings = settings,
                )
            runCurrent()
            vm.selectHistorySyncDepth(HistorySyncDepth.EVERYTHING)
            vm.next()

            vm.finish {}
            runCurrent()

            assertEquals(HistorySyncDepth.EVERYTHING, settings.historySyncDepth)
            // The root's own connect happened during the connect test; the depth must land before the
            // child connect that triggers its first catch-up.
            val depthIndex = log.indexOf("depth:EVERYTHING")
            assertTrue(log.toString(), depthIndex >= 0)
            assertTrue(log.toString(), log.drop(depthIndex).any { it.startsWith("connect:") })
        }

    @Test
    fun `await current onboarding resource retries until lookup succeeds`() =
        runTest {
            val resource = Any()
            var attempts = 0

            val result =
                awaitCurrentOnboardingResource(
                    expectedNetworkId = 7L,
                    currentNetworkId = { 7L },
                    lookup = {
                        attempts += 1
                        if (attempts == 3) resource else null
                    },
                    maxAttempts = 5,
                    delayMs = 1L,
                )

            assertSame(resource, result)
            assertEquals(3, attempts)
        }

    @Test
    fun `await current onboarding resource stops when network changes`() =
        runTest {
            var attempts = 0

            val result =
                awaitCurrentOnboardingResource<Any>(
                    expectedNetworkId = 7L,
                    currentNetworkId = { 8L },
                    lookup = {
                        attempts += 1
                        Any()
                    },
                    maxAttempts = 5,
                    delayMs = 1L,
                )

            assertNull(result)
            assertEquals(0, attempts)
        }
}
