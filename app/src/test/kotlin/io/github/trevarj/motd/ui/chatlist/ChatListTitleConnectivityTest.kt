package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListTitleConnectivityTest {
    private val ready = IrcClientState.Ready("me", emptySet(), emptyMap())

    // --- snapshot derivation -------------------------------------------------------------------

    @Test
    fun connecting_and_registering_are_the_only_states_that_show_the_cue() {
        assertTrue(titleConnectingSnapshot(mapOf(1L to IrcClientState.Connecting)))
        assertTrue(titleConnectingSnapshot(mapOf(1L to IrcClientState.Registering)))

        assertFalse(titleConnectingSnapshot(emptyMap()))
        assertFalse(titleConnectingSnapshot(mapOf(1L to ready)))
        assertFalse(titleConnectingSnapshot(mapOf(1L to IrcClientState.Disconnected)))
        // Terminal failures (fatal or the cert-trust park) belong in the inspector, not progress.
        assertFalse(titleConnectingSnapshot(mapOf(1L to IrcClientState.Failed("SASL", fatal = true))))
        assertFalse(titleConnectingSnapshot(mapOf(1L to IrcClientState.Failed("certificate not trusted", fatal = false))))
    }

    @Test
    fun one_reconnecting_network_shows_even_while_others_are_ready() {
        // The bouncer cold-start window: roots Ready, children still dialing. The status
        // notification deliberately hides this behind "Connected to N"; the title cue must not.
        val states =
            mapOf(
                4L to ready,
                9L to ready,
                5L to IrcClientState.Connecting,
            )

        assertTrue(titleConnectingSnapshot(states))
    }

    @Test
    fun a_scoped_cue_reports_only_the_scoped_networks_sockets() {
        val states = mapOf(1L to ready, 2L to IrcClientState.Connecting)

        // Unscoped sees every network; a scope that excludes the dialing network stays quiet.
        assertTrue(titleConnectingSnapshot(states, scopeIds = null))
        assertTrue(titleConnectingSnapshot(states, scopeIds = setOf(2L)))
        assertFalse(titleConnectingSnapshot(states, scopeIds = setOf(1L)))
    }

    @Test
    fun scope_ids_follow_the_same_rule_as_row_scoping() {
        val networks =
            listOf(
                net(1, role = NetworkRole.BOUNCER_ROOT),
                net(2, role = NetworkRole.BOUNCER_CHILD, parentId = 1),
                net(3),
            )

        assertNull(scopeNetworkIds(null, networks))
        // A root's scope covers its children, so a dialing child shows under the root's name.
        assertEquals(setOf(1L, 2L), scopeNetworkIds(1L, networks))
        assertEquals(setOf(2L), scopeNetworkIds(2L, networks))
        assertEquals(setOf(3L), scopeNetworkIds(3L, networks))
    }

    // --- presenter windows ---------------------------------------------------------------------

    @Test
    fun the_cue_stays_hidden_until_the_appearance_grace_elapses() {
        val presenter = TitleConnectingPresenter()

        assertFalse(presenter.resolve(true, nowMs = 0))
        assertEquals(SYNC_CHROME_APPEARANCE_DELAY_MS, presenter.nextDeadlineMs(nowMs = 0))
        assertFalse(presenter.resolve(true, nowMs = 499))
        assertTrue(presenter.resolve(true, nowMs = 500))
        assertNull(presenter.nextDeadlineMs(nowMs = 500))
    }

    @Test
    fun a_reconnect_that_resolves_inside_the_grace_never_shows_and_the_next_earns_a_fresh_grace() {
        val presenter = TitleConnectingPresenter()

        assertFalse(presenter.resolve(true, nowMs = 0))
        assertFalse(presenter.resolve(false, nowMs = 300))
        assertNull(presenter.nextDeadlineMs(nowMs = 300))

        assertFalse(presenter.resolve(true, nowMs = 400))
        assertEquals(900L, presenter.nextDeadlineMs(nowMs = 400))
        assertFalse(presenter.resolve(true, nowMs = 899))
        assertTrue(presenter.resolve(true, nowMs = 900))
    }

    @Test
    fun a_shown_cue_survives_its_minimum_visible_window() {
        val presenter = TitleConnectingPresenter()
        presenter.resolve(true, nowMs = 0)
        assertTrue(presenter.resolve(true, nowMs = 500))

        assertTrue(presenter.resolve(false, nowMs = 520))
        assertEquals(1_500L, presenter.nextDeadlineMs(nowMs = 520))
        assertTrue(presenter.resolve(false, nowMs = 1_499))
        assertFalse(presenter.resolve(false, nowMs = 1_500))
        assertNull(presenter.nextDeadlineMs(nowMs = 1_500))
    }

    @Test
    fun a_redial_during_the_minimum_visible_hold_continues_the_same_episode() {
        val presenter = TitleConnectingPresenter()
        presenter.resolve(true, nowMs = 0)
        assertTrue(presenter.resolve(true, nowMs = 500))
        assertTrue(presenter.resolve(false, nowMs = 600))

        // Ready flapped straight back to Connecting: the cue simply stays up, no strobe, and the
        // eventual settle still honors the original appearance moment.
        assertTrue(presenter.resolve(true, nowMs = 700))
        assertNull(presenter.nextDeadlineMs(nowMs = 700))
        assertTrue(presenter.resolve(false, nowMs = 1_499))
        assertFalse(presenter.resolve(false, nowMs = 2_600))
    }

    // --- driver --------------------------------------------------------------------------------

    @Test
    fun a_300ms_flap_produces_no_visible_emission_at_all() =
        runTest {
            val snapshots = MutableStateFlow(false)
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch {
                snapshots.presentTitleConnecting { testScheduler.currentTime }.toList(seen)
            }
            runCurrent()
            assertEquals(listOf(false), seen)

            snapshots.value = true
            advanceTimeBy(300L)
            snapshots.value = false
            advanceTimeBy(SYNC_CHROME_APPEARANCE_DELAY_MS + SYNC_CHROME_MIN_VISIBLE_MS)

            assertEquals(listOf(false), seen)
        }

    @Test
    fun the_driver_resolves_its_own_deadlines_without_further_connection_emissions() =
        runTest {
            val snapshots = MutableStateFlow(false)
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch {
                snapshots.presentTitleConnecting { testScheduler.currentTime }.toList(seen)
            }
            runCurrent()

            snapshots.value = true
            runCurrent()
            assertEquals(listOf(false), seen)

            // No new connection emission here: the appearance timer is the driver's own.
            advanceTimeBy(SYNC_CHROME_APPEARANCE_DELAY_MS + 1)
            assertEquals(listOf(false, true), seen)

            snapshots.value = false
            runCurrent()
            assertEquals(listOf(false, true), seen)

            advanceTimeBy(SYNC_CHROME_MIN_VISIBLE_MS + 1)
            assertEquals(listOf(false, true, false), seen)
        }

    private fun net(
        id: Long,
        role: NetworkRole = NetworkRole.DIRECT,
        parentId: Long? = null,
    ) = NetworkEntity(
        id = id,
        name = "net$id",
        role = role,
        parentId = parentId,
        host = "h",
        port = 6697,
        nick = "me",
        username = "me",
        realname = "Me",
    )
}
