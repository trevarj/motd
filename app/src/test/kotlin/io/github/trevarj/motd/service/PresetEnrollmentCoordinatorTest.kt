package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.PresetEnrollmentPrefs
import io.github.trevarj.motd.data.repo.NetworkRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PresetEnrollmentCoordinatorTest {
    private class FakePrefs : PresetEnrollmentPrefs {
        val eligible = mutableSetOf<Long>()
        val attempted = mutableSetOf<Long>()
        override suspend fun markLiberaEligible(networkId: Long) { eligible += networkId }
        override suspend fun claimLiberaMotdJoin(networkId: Long): Boolean {
            val claimed = eligible.remove(networkId) && attempted.add(networkId)
            return claimed
        }
        override suspend fun revokeLiberaEligibility(networkId: Long) { eligible -= networkId }
    }

    private class FakeNetworks(var network: NetworkEntity?) : NetworkRepository {
        override fun observeNetworks() = flowOf(listOfNotNull(network))
        override suspend fun addNetwork(n: NetworkEntity) = error("unused")
        override suspend fun updateNetwork(n: NetworkEntity) = error("unused")
        override suspend fun deleteNetwork(id: Long) = error("unused")
        override suspend fun networkById(id: Long) = network?.takeIf { it.id == id }
        override suspend fun childrenOf(rootId: Long) = emptyList<NetworkEntity>()
    }

    private fun libera(role: NetworkRole = NetworkRole.DIRECT) = NetworkEntity(
        id = 7,
        name = "Libera.Chat",
        role = role,
        host = "irc.libera.chat",
        port = 6697,
        tls = true,
        nick = "trev",
        username = "trev",
        realname = "Trev",
    )

    @Test
    fun eligible_first_ready_sends_once_and_reconnect_does_not_repeat() = runTest {
        val prefs = FakePrefs().apply { markLiberaEligible(7) }
        val coordinator = PresetEnrollmentCoordinator(prefs, FakeNetworks(libera()))
        val joins = mutableListOf<String>()

        assertEquals(EnrollmentJoinResult.SENT, coordinator.onReady(7, { true }, joins::add))
        assertEquals(EnrollmentJoinResult.NOT_ELIGIBLE, coordinator.onReady(7, { true }, joins::add))
        assertEquals(listOf(MOTD_CHANNEL), joins)
    }

    @Test
    fun failed_write_is_attempted_and_never_retried() = runTest {
        val prefs = FakePrefs().apply { markLiberaEligible(7) }
        val coordinator = PresetEnrollmentCoordinator(prefs, FakeNetworks(libera()))
        var attempts = 0

        assertEquals(EnrollmentJoinResult.FAILED, coordinator.onReady(7, { true }) {
            attempts++
            error("socket closed")
        })
        assertEquals(EnrollmentJoinResult.NOT_ELIGIBLE, coordinator.onReady(7, { true }) { attempts++ })
        assertEquals(1, attempts)
    }

    @Test
    fun current_row_must_still_be_a_direct_tls_libera_endpoint() = runTest {
        val invalidRows = listOf(
            libera(NetworkRole.BOUNCER_ROOT),
            libera(NetworkRole.BOUNCER_CHILD),
            libera().copy(host = "irc.example.org"),
            libera().copy(port = 6667),
            libera().copy(tls = false),
        )

        invalidRows.forEach { row ->
            val prefs = FakePrefs().apply { markLiberaEligible(7) }
            val joins = mutableListOf<String>()
            val coordinator = PresetEnrollmentCoordinator(prefs, FakeNetworks(row))
            assertEquals(EnrollmentJoinResult.SKIPPED, coordinator.onReady(7, { true }, joins::add))
            assertEquals(emptyList<String>(), joins)
        }
    }

    @Test
    fun an_unmarked_existing_or_imported_row_is_not_inferred_from_its_hostname() = runTest {
        val joins = mutableListOf<String>()
        val coordinator = PresetEnrollmentCoordinator(FakePrefs(), FakeNetworks(libera()))

        assertEquals(EnrollmentJoinResult.NOT_ELIGIBLE, coordinator.onReady(7, { true }, joins::add))
        assertEquals(emptyList<String>(), joins)
    }

    @Test
    fun an_obsolete_ready_generation_does_not_consume_eligibility() = runTest {
        val prefs = FakePrefs().apply { markLiberaEligible(7) }
        val coordinator = PresetEnrollmentCoordinator(prefs, FakeNetworks(libera()))
        val joins = mutableListOf<String>()

        assertEquals(EnrollmentJoinResult.NOT_ELIGIBLE, coordinator.onReady(7, { false }, joins::add))
        assertEquals(EnrollmentJoinResult.SENT, coordinator.onReady(7, { true }, joins::add))
        assertEquals(listOf(MOTD_CHANNEL), joins)
    }
}
