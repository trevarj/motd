package io.github.trevarj.motd.ui.invite

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.data.prefs.NoopBouncerKindPrefs
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CreateContactInviteViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Default)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `preferred Ready network uses authoritative connected nick and can be changed`() =
        runTest {
            val networks = FakeNetworks(listOf(network(1, "One"), network(2, "Two")))
            val connections =
                NoopConnectionManager(
                    mapOf(
                        1L to IrcClientState.Ready("current-one", emptySet(), emptyMap()),
                        2L to IrcClientState.Ready("current-two", emptySet(), emptyMap()),
                    ),
                )
            val viewModel =
                CreateContactInviteViewModel(
                    networks,
                    connections,
                    InviteEndpointResolver(connections, NoopBouncerKindPrefs),
                    FakeCerts(),
                )

            viewModel.init(preferredNetworkId = 2)
            val preferred =
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { viewModel.state.first { it.qrText != null && it.selectedNetworkId == 2L } }
                }
            assertEquals("current-two", preferred.invite?.contactNick)
            assertEquals("Two", preferred.invite?.networkName)

            viewModel.selectNetwork(1)
            val changed =
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { viewModel.state.first { it.qrText != null && it.selectedNetworkId == 1L } }
                }
            assertEquals("current-one", changed.invite?.contactNick)
            assertTrue(changed.qrText.orEmpty().startsWith("https://github.com/trevarj/motd/releases/latest#motd-invite="))
        }

    @Test
    fun `configured networks without Ready state are unavailable`() =
        runTest {
            val networks = FakeNetworks(listOf(network(1, "Offline")))
            val connections = NoopConnectionManager()
            val viewModel =
                CreateContactInviteViewModel(
                    networks,
                    connections,
                    InviteEndpointResolver(connections, NoopBouncerKindPrefs),
                    FakeCerts(),
                )

            viewModel.init(preferredNetworkId = 1)
            val state =
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { viewModel.state.first { !it.loading } }
                }
            assertTrue(state.networks.isEmpty())
            assertEquals(null, state.invite)
        }

    private class FakeNetworks(
        initial: List<NetworkEntity>,
    ) : NetworkRepository {
        private val rows = MutableStateFlow(initial)

        override fun observeNetworks(): Flow<List<NetworkEntity>> = rows

        override suspend fun addNetwork(
            n: NetworkEntity,
            requireNew: Boolean,
        ): Long = error("not used")

        override suspend fun updateNetwork(n: NetworkEntity) = Unit

        override suspend fun deleteNetwork(id: Long) = Unit

        override suspend fun reorderNetworks(orderedIds: List<Long>) = Unit

        override suspend fun networkById(id: Long): NetworkEntity? = rows.value.firstOrNull { it.id == id }

        override suspend fun childrenOf(rootId: Long): List<NetworkEntity> = emptyList()
    }

    private class FakeCerts : CertTrustStore {
        override suspend fun pinnedFor(
            host: String,
            port: Int,
        ): String? = null

        override suspend fun isPinned(
            host: String,
            port: Int,
            sha256: String,
        ): Boolean = false

        override suspend fun pin(
            host: String,
            port: Int,
            sha256: String,
        ) = Unit

        override suspend fun unpin(
            host: String,
            port: Int,
        ) = Unit
    }

    private fun network(
        id: Long,
        name: String,
    ) = NetworkEntity(
        id = id,
        name = name,
        role = NetworkRole.DIRECT,
        host = "irc.$id.example",
        port = 6697,
        nick = "stale-$id",
        username = "stale-$id",
        realname = "Stale $id",
    )
}
