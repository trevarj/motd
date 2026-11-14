package io.github.trevarj.motd.ui.chatlist

import androidx.lifecycle.SavedStateHandle
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.NotificationChannelRow
import io.github.trevarj.motd.data.prefs.GlobalFeedPrefs
import io.github.trevarj.motd.data.prefs.OnboardingPrefs
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.sync.NoopHistoryGapFiller
import io.github.trevarj.motd.gesture.FakeBuffers
import io.github.trevarj.motd.gesture.FakeNetworks
import io.github.trevarj.motd.gesture.FakeSettings
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.AlwaysOnScreen
import io.github.trevarj.motd.service.ChannelCloseCoordinator
import io.github.trevarj.motd.service.ChannelJoinOutcome
import io.github.trevarj.motd.service.ChannelJoinRejectionKind
import io.github.trevarj.motd.service.HistoryResyncController
import io.github.trevarj.motd.service.HistoryResyncState
import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.gesture.FakeReadMarkers
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CommunityTest {
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class Prefs : OnboardingPrefs {
        override val completed = MutableStateFlow(true)
        override val communityInvitationHandled = MutableStateFlow(false)
        override suspend fun markCompleted() { completed.value = true }
        override suspend fun markCommunityInvitationHandled() { communityInvitationHandled.value = true }
    }

    private class Buffers : BufferRepository by FakeBuffers() {
        val channels = MutableStateFlow<List<NotificationChannelRow>>(emptyList())
        override fun observeNotificationChannels() = channels
        override fun observeJoinedChannelNames(networkId: Long) = channels.map { rows ->
            rows.filter { it.networkId == networkId && it.joined }.map { it.displayName.lowercase() }.toSet()
        }
        override suspend fun joinedBufferId(networkId: Long, normalizedChannel: String) =
            channels.value.firstOrNull { it.networkId == networkId && it.joined && it.displayName.equals(normalizedChannel, true) }?.bufferId
        fun joined(networkId: Long = 1, id: Long = 91) {
            channels.value = listOf(NotificationChannelRow(id, networkId, "#motd", true, false, false))
        }
    }

    private class Connections : NoopConnectionManager() {
        override val connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(emptyMap())
        override val channelJoinOutcomes = MutableSharedFlow<ChannelJoinOutcome>()
        var accepted = true
        var rejection: ChannelJoinOutcome.Rejected? = null
        val joins = mutableListOf<Pair<Long, String>>()
        override suspend fun joinChannel(networkId: Long, channel: String, key: String?): Boolean {
            joins += networkId to channel
            rejection?.let { channelJoinOutcomes.emit(it) }
            return accepted
        }
    }

    private class Fixture(
        routes: List<NetworkEntity> = listOf(direct()),
        val prefs: Prefs = Prefs(),
    ) {
        val buffers = Buffers()
        val connections = Connections().apply { connectionStates.value = routes.associate { it.id to ready() } }
        val networks = FakeNetworks(routes)
        fun vm(active: Boolean = true) = ChatListViewModel(
            bufferRepository = buffers,
            networkRepository = networks,
            connectionManager = connections,
            gapFiller = NoopHistoryGapFiller,
            historyResync = object : HistoryResyncController {
                override fun syncStatus(bufferId: Long) = flowOf<HistorySyncStatus>(HistorySyncStatus.Idle)
                override suspend fun reconcileBuffer(buffer: BufferEntity, client: IrcClient, statusOwnerId: Long, preserveUnread: Boolean, isCurrent: () -> Boolean) = HistoryResyncState.Idle
                override suspend fun reconcilePendingMessage(buffer: BufferEntity, client: IrcClient, isCurrent: () -> Boolean) = HistoryResyncState.Idle
            },
            channelCloseCoordinator = object : ChannelCloseCoordinator {
                override fun start() = Unit
                override suspend fun requestClose(bufferId: Long) = Unit
            },
            readMarkerRepository = FakeReadMarkers(),
            settingsRepository = FakeSettings(),
            onboardingPrefs = prefs,
            globalFeedPrefs = object : GlobalFeedPrefs {
                override val enabled = flowOf(false)
                override suspend fun setEnabled(enabled: Boolean) = Unit
            },
            dickordLabsPrefs = fakeDickordLabsPrefs(),
            savedStateHandle = SavedStateHandle(),
            appVisibility = AlwaysOnScreen,
        ).also { it.setCommunityVisitActive(active) }
    }

    @Test fun invitationIsConsumedOnlyWhenDisplayedAndSurvivesUntilVisitEnds() = runTest {
        val fixture = Fixture()
        val vm = fixture.vm(active = false)
        runCurrent()
        assertFalse(fixture.prefs.communityInvitationHandled.value)
        vm.setCommunityVisitActive(true)
        runCurrent()
        assertTrue(vm.community.value.invitation)
        assertFalse(fixture.prefs.communityInvitationHandled.value)
        vm.communityInvitationDisplayed()
        runCurrent()
        assertTrue(fixture.prefs.communityInvitationHandled.value)
        assertTrue(vm.community.value.invitation)
        vm.setCommunityVisitActive(false)
        vm.setCommunityVisitActive(true)
        runCurrent()
        assertFalse(vm.community.value.invitation)
        val reopened = fixture.vm()
        reopened.setCommunityVisitActive(true)
        runCurrent()
        assertFalse(reopened.community.value.invitation)
    }

    @Test fun knownAndLateMembersAreSuppressedPermanently() = runTest {
        for (alreadyJoined in listOf(true, false)) {
            val child = direct().copy(role = NetworkRole.BOUNCER_CHILD, parentId = 9, host = "soju.example")
            val fixture = Fixture(listOf(child))
            fixture.connections.connectionStates.value = mapOf(1L to ready("Libera.Chat"))
            if (alreadyJoined) fixture.buffers.joined()
            val vm = fixture.vm()
            vm.setCommunityVisitActive(true)
            runCurrent()
            if (!alreadyJoined) {
                assertTrue(vm.community.value.invitation)
                fixture.buffers.joined()
                runCurrent()
            }
            assertFalse(vm.community.value.invitation)
            assertTrue(fixture.prefs.communityInvitationHandled.value)
            fixture.buffers.channels.value = emptyList()
            vm.setCommunityVisitActive(false)
            vm.setCommunityVisitActive(true)
            runCurrent()
            assertFalse(vm.community.value.invitation)
            assertTrue(fixture.connections.joins.isEmpty())
        }
    }

    @Test fun permanentActionOpensJoinedBufferWithoutSendingJoin() = runTest {
        val fixture = Fixture()
        fixture.prefs.communityInvitationHandled.value = true
        fixture.buffers.joined()
        val vm = fixture.vm()
        runCurrent()
        vm.requestCommunity()
        runCurrent()
        assertEquals(91L, vm.community.value.openBufferId)
        assertTrue(fixture.connections.joins.isEmpty())
    }

    @Test fun acceptedWriteWaitsForPersistedSelfJoin() = runTest {
        val fixture = Fixture()
        val vm = fixture.vm()
        runCurrent()
        vm.requestCommunity()
        runCurrent()
        assertTrue(vm.community.value.joining)
        assertNull(vm.community.value.openBufferId)
        fixture.buffers.joined()
        runCurrent()
        assertEquals(91L, vm.community.value.openBufferId)
        assertFalse(vm.community.value.joining)
    }

    @Test fun synchronousServerRejectionCannotBeLostAfterAcceptedWrite() = runTest {
        val fixture = Fixture()
        fixture.connections.rejection = ChannelJoinOutcome.Rejected(1, "#motd", "Invite only", "473", ChannelJoinRejectionKind.INVITE_ONLY)
        val vm = fixture.vm()
        runCurrent()
        vm.requestCommunity()
        runCurrent()
        assertEquals(CommunityError.REJECTED, vm.community.value.error)
        assertEquals("Invite only", vm.community.value.rejectionReason)
        assertNull(vm.community.value.openBufferId)
    }

    @Test fun rejectedWriteAndMissingJoinNeverNavigate() = runTest {
        for (accepted in listOf(false, true)) {
            val fixture = Fixture()
            fixture.connections.accepted = accepted
            val vm = fixture.vm()
            runCurrent()
            vm.requestCommunity()
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue(vm.community.value.error != null)
            assertFalse(vm.community.value.joining)
            assertNull(vm.community.value.openBufferId)
        }
    }

    @Test fun cancelPreventsLateJoinNavigationAndDoesNotRetry() = runTest {
        val fixture = Fixture()
        val vm = fixture.vm()
        runCurrent()
        vm.requestCommunity()
        runCurrent()
        vm.cancelCommunity()
        fixture.buffers.joined()
        advanceTimeBy(60_000)
        runCurrent()
        assertNull(vm.community.value.openBufferId)
        assertFalse(vm.community.value.joining)
        assertEquals(listOf(1L to "#motd"), fixture.connections.joins)
    }

    @Test fun multipleKnownRoutesRequireChoiceAndUseChosenBouncerChild() = runTest {
        val root = direct().copy(id = 2, role = NetworkRole.BOUNCER_ROOT, host = "soju.example")
        val child = root.copy(id = 3, role = NetworkRole.BOUNCER_CHILD, parentId = 2, bouncerNetId = "upstream")
        val fixture = Fixture(listOf(direct(), root, child))
        fixture.connections.connectionStates.value = mapOf(1L to ready(), 2L to ready("Libera.Chat"), 3L to ready("Libera.Chat"))
        val vm = fixture.vm()
        runCurrent()
        vm.requestCommunity()
        runCurrent()
        assertTrue(vm.community.value.picker)
        assertEquals(setOf(1L, 3L), vm.community.value.routes.map { it.network.id }.toSet())
        assertTrue(fixture.connections.joins.isEmpty())
        vm.chooseCommunityNetwork(3)
        runCurrent()
        fixture.buffers.joined(3, 93)
        runCurrent()
        assertEquals(93L, vm.community.value.openBufferId)
        assertEquals(listOf(3L to "#motd"), fixture.connections.joins)
        assertEquals(listOf(direct(), root, child), fixture.networks.rows.value)
    }

    @Test fun unknownBouncerUpstreamRequiresConfirmationInsteadOfTrustingAlias() = runTest {
        val child = direct().copy(role = NetworkRole.BOUNCER_CHILD, parentId = 9, host = "irc.libera.chat", name = "Libera.Chat")
        val fixture = Fixture(listOf(child))
        val vm = fixture.vm()
        runCurrent()
        vm.requestCommunity()
        runCurrent()
        assertTrue(vm.community.value.picker)
        assertFalse(vm.community.value.routes.single().identified)
        assertTrue(fixture.connections.joins.isEmpty())
        vm.chooseCommunityNetwork(child.id)
        runCurrent()
        fixture.buffers.joined()
        runCurrent()
        assertEquals(91L, vm.community.value.openBufferId)
    }

    @Test fun networkIdentityRequiresLiveUpstreamOrExactSecureDirectEndpoint() {
        assertTrue(isCommunityNetwork(direct(), null))
        assertFalse(isCommunityNetwork(direct().copy(tls = false), null))
        assertFalse(isCommunityNetwork(direct().copy(port = 6667), null))
        assertFalse(isCommunityNetwork(direct().copy(wsUrl = "wss://gateway.example/irc"), null))
        assertFalse(isCommunityNetwork(direct().copy(host = "irc.libera.chat.example"), null))
        assertFalse(isCommunityNetwork(direct().copy(host = "znc.example", name = "Libera.Chat"), ready()))
        assertTrue(isCommunityNetwork(direct().copy(host = "znc.example"), ready("Libera.Chat")))
        assertTrue(isCommunityNetwork(direct().copy(role = NetworkRole.BOUNCER_CHILD, parentId = 9, host = "soju.example"), ready("Libera.Chat")))
        assertFalse(isCommunityNetwork(direct().copy(role = NetworkRole.BOUNCER_ROOT), ready("Libera.Chat")))
    }

    @Test fun uniqueLiveZncIdentityUsesConfiguredRouteWithoutCreatingDirectNetwork() = runTest {
        val znc = direct().copy(id = 17, host = "znc.example", port = 7777, name = "personal bouncer")
        val fixture = Fixture(listOf(znc))
        fixture.connections.connectionStates.value = mapOf(17L to ready("Libera.Chat"))
        val vm = fixture.vm()
        runCurrent()
        vm.requestCommunity()
        runCurrent()
        fixture.buffers.joined(17, 117)
        runCurrent()
        assertEquals(117L, vm.community.value.openBufferId)
        assertEquals(listOf(17L to "#motd"), fixture.connections.joins)
    }

    @Test fun disconnectedMemberCanStillOpenExistingConversation() = runTest {
        val fixture = Fixture()
        fixture.connections.connectionStates.value = emptyMap()
        fixture.buffers.joined()
        val vm = fixture.vm()
        runCurrent()
        vm.requestCommunity()
        runCurrent()
        assertEquals(91L, vm.community.value.openBufferId)
        assertTrue(fixture.connections.joins.isEmpty())
    }

    @Test fun disconnectWhileWaitingReportsFailureInsteadOfOpeningLateBuffer() = runTest {
        val fixture = Fixture()
        val vm = fixture.vm()
        runCurrent()
        vm.requestCommunity()
        runCurrent()
        fixture.connections.connectionStates.value = emptyMap()
        runCurrent()
        assertEquals(CommunityError.CONNECTION, vm.community.value.error)
        fixture.buffers.joined()
        runCurrent()
        assertNull(vm.community.value.openBufferId)
        assertFalse(vm.community.value.joining)
    }

    @Test fun leavingChatListCancelsPendingJoinNavigation() = runTest {
        val fixture = Fixture()
        val vm = fixture.vm()
        runCurrent()
        vm.requestCommunity()
        runCurrent()
        vm.setCommunityVisitActive(false)
        fixture.buffers.joined()
        advanceTimeBy(60_000)
        runCurrent()
        assertNull(vm.community.value.openBufferId)
        assertFalse(vm.community.value.joining)
    }

    @Test fun noConfiguredRouteOffersSetupWithoutSendingJoin() = runTest {
        val fixture = Fixture(emptyList())
        val vm = fixture.vm()
        runCurrent()
        vm.requestCommunity()
        runCurrent()
        assertTrue(vm.community.value.picker)
        assertTrue(vm.community.value.routes.isEmpty())
        assertTrue(fixture.connections.joins.isEmpty())
        assertNull(vm.community.value.openBufferId)
    }

    @Test fun unverifiedBouncerMembershipSuppressesInvitationButStillRequiresRouteConfirmation() = runTest {
        val child = direct().copy(role = NetworkRole.BOUNCER_CHILD, parentId = 9, host = "soju.example", name = "personal upstream")
        val fixture = Fixture(listOf(child))
        fixture.buffers.joined()
        val vm = fixture.vm()
        runCurrent()
        assertFalse(vm.community.value.invitation)
        assertTrue(fixture.prefs.communityInvitationHandled.value)
        vm.requestCommunity()
        runCurrent()
        assertTrue(vm.community.value.picker)
        assertNull(vm.community.value.openBufferId)
        vm.chooseCommunityNetwork(child.id)
        runCurrent()
        assertEquals(91L, vm.community.value.openBufferId)
        assertTrue(fixture.connections.joins.isEmpty())
    }

    @Test fun knownOtherUpstreamIsNotOfferedAsCommunityRoute() = runTest {
        val other = direct().copy(id = 2, role = NetworkRole.BOUNCER_CHILD, parentId = 9, host = "soju.example", name = "Libera.Chat")
        val fixture = Fixture(listOf(direct(), other))
        fixture.connections.connectionStates.value = mapOf(1L to ready(), 2L to ready("OFTC"))
        val vm = fixture.vm()
        runCurrent()
        vm.requestCommunity()
        runCurrent()
        fixture.buffers.joined()
        runCurrent()
        assertEquals(91L, vm.community.value.openBufferId)
        assertEquals(listOf(1L to "#motd"), fixture.connections.joins)
    }

    companion object {
        private fun direct() = NetworkEntity(id = 1, role = NetworkRole.DIRECT, name = "Libera", host = "irc.libera.chat", port = 6697, tls = true, nick = "me", username = "me", realname = "Me")
        private fun ready(network: String? = null) = IrcClientState.Ready("me", emptySet(), network?.let { mapOf("NETWORK" to it) } ?: emptyMap())
    }
}
