package io.github.trevarj.motd.ui.chatlist

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.JoinedChannelRow
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkIdentityDao
import io.github.trevarj.motd.data.db.NetworkIdentityEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.GlobalFeedPrefs
import io.github.trevarj.motd.data.prefs.OnboardingPrefs
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.sync.NoopHistoryGapFiller
import io.github.trevarj.motd.gesture.FakeBuffers
import io.github.trevarj.motd.gesture.FakeNetworks
import io.github.trevarj.motd.gesture.FakeReadMarkers
import io.github.trevarj.motd.gesture.FakeSettings
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.service.AlwaysOnScreen
import io.github.trevarj.motd.service.ChannelCloseCoordinator
import io.github.trevarj.motd.service.ChannelJoinOutcome
import io.github.trevarj.motd.service.ChannelJoinRejectionKind
import io.github.trevarj.motd.service.HistoryResyncController
import io.github.trevarj.motd.service.HistoryResyncState
import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.testing.NoopConnectionManager
import io.github.trevarj.motd.ui.channellist.ChannelJoinFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
class ChatListNewConversationJoinTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val models = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        models.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun exactJoinWaitsForCurrentSelfJoinAndCanonicalRowWithoutListing() =
        runTest {
            val fixture = Fixture(this)
            val rows = fixture.memberships.getValue(1L)
            // Neither a stale, persisted membership nor somebody else's JOIN can open the target.
            rows.value = listOf(JoinedChannelRow(10, 1, "+ROOM{LAB}"))
            fixture.model.joinChannel(1, "  +Room[Lab]  ", null)
            fixture.transports.getValue(1L).feed(":other!u@h JOIN +room{lab}")
            runCurrent()
            assertEquals(listOf(JoinWrite(1, "+Room[Lab]", null, false)), fixture.connections.writes)
            assertEquals(NewConversationJoinState(joining = true), fixture.state)

            rows.value = emptyList()
            fixture.memberships.getValue(2L).value = listOf(JoinedChannelRow(20, 2, "+room{lab}"))
            fixture.transports.getValue(1L).feed(":me!u@h JOIN +room{lab}")
            runCurrent()
            assertEquals(NewConversationJoinState(joining = true), fixture.state)

            rows.value = listOf(JoinedChannelRow(11, 1, "+ROOM{LAB}"))
            runCurrent()
            assertEquals(NewConversationJoinState(openBufferId = 11), fixture.state)
            assertTrue(fixture.model.consumeNewConversationJoin(11))
            assertFalse(fixture.model.consumeNewConversationJoin(11))
            assertTrue(fixture.transports.values.none { transport -> transport.sent.any { it.startsWith("LIST") } })
        }

    @Test
    fun delayedISupportUsesPersistedRulesAndWaitsForTheExactJoinedRoom() =
        runTest {
            val fixture = Fixture(this, persistedIdentity = NetworkIdentityEntity(1, "ascii", "#+"), delayIsupport = true)
            val transport = fixture.transports.getValue(1L)
            val rows = fixture.memberships.getValue(1L)
            val alias = JoinedChannelRow(10, 1, "#team{")
            val target = JoinedChannelRow(11, 1, "#team[")
            fixture.roomKeys[target.bufferId] = "#team["
            rows.value = listOf(alias)
            transport.feed(":me!u@h JOIN #team{")
            runCurrent()
            assertEquals(
                "#+",
                fixture.model.newConversationChannelRules
                    .value
                    .getValue(1L)
                    .chanTypes,
            )
            fixture.model.joinChannel(1, "&team", null)
            fixture.assertFailure(R.string.chatlist_join_invalid_channel)

            fixture.model.joinChannel(1, "#team[", null)
            runCurrent()
            assertEquals(listOf(JoinWrite(1, "#team[", null, false)), fixture.connections.writes)
            assertEquals(NewConversationJoinState(joining = true), fixture.state)

            transport.feed(":srv 005 me CASEMAPPING=ascii CHANTYPES=#+ :supported", ":me!u@h JOIN #team[")
            runCurrent()
            assertEquals(NewConversationJoinState(joining = true), fixture.state)
            rows.value = listOf(alias, target)
            runCurrent()
            assertEquals(11L, fixture.state.openBufferId)
            assertFalse(fixture.model.consumeNewConversationJoin(10))
            assertTrue(fixture.model.consumeNewConversationJoin(11))
        }

    @Test
    fun removedIdentityKeysRestoreDefaultsAndSelectByStoredRoomKey() =
        runTest {
            val fixture = Fixture(this, persistedIdentity = NetworkIdentityEntity(1, "ascii", "#+"), delayIsupport = true)
            val transport = fixture.transports.getValue(1L)
            val oldAsciiRoom = JoinedChannelRow(10, 1, "#team[")
            val defaultRoom = JoinedChannelRow(11, 1, "#team{")
            fixture.roomKeys[oldAsciiRoom.bufferId] = "#team["
            fixture.memberships.getValue(1L).value = listOf(oldAsciiRoom, defaultRoom)
            transport.feed(":srv 005 me -CASEMAPPING -CHANTYPES :supported", ":me!u@h JOIN #team{")
            runCurrent()
            fixture.connections.publishStates()
            runCurrent()
            assertEquals(
                "#&",
                fixture.model.newConversationChannelRules
                    .value
                    .getValue(1L)
                    .chanTypes,
            )
            fixture.model.joinChannel(1, "+team", null)
            fixture.assertFailure(R.string.chatlist_join_invalid_channel)

            fixture.model.joinChannel(1, "#team[", null)
            runCurrent()
            assertTrue(fixture.connections.writes.isEmpty())
            assertEquals(11L, fixture.state.openBufferId)
            assertTrue(fixture.model.consumeNewConversationJoin(11))
        }

    @Test
    fun missingCasemapDoesNotConfirmAnUnadvertisedPunctuationAlias() =
        runTest {
            val fixture = Fixture(this, delayIsupport = true)
            val transport = fixture.transports.getValue(1L)
            fixture.memberships.getValue(1L).value = listOf(JoinedChannelRow(10, 1, "#team{"))
            transport.feed(":me!u@h JOIN #team{")
            runCurrent()
            fixture.model.joinChannel(1, "#team[", null)
            runCurrent()
            assertEquals(listOf(JoinWrite(1, "#team[", null, false)), fixture.connections.writes)
            assertEquals(NewConversationJoinState(joining = true), fixture.state)
            transport.feed(":me!u@h PART #team{ :gone", ":me!u@h JOIN #team[")
            runCurrent()
            assertEquals(NewConversationJoinState(joining = true), fixture.state)
            assertFalse(fixture.model.consumeNewConversationJoin(10))
        }

    @Test
    fun advertisedChannelTypesAndUtf8KeyLimitAreAuthoritative() =
        runTest {
            val fixture = Fixture(this)
            val rules = fixture.model.newConversationChannelRules
            assertEquals("+", rules.value.getValue(1L).chanTypes)
            assertEquals("#", rules.value.getValue(2L).chanTypes)
            assertEquals("#&", rules.value.getValue(3L).chanTypes)
            assertFalse(4L in rules.value) // Bouncer root is not a selectable network.
            for (target in listOf("#wrong", "plain", "+", "+x,+y", "+x +y", "+x\r\nJOIN +y")) {
                fixture.model.joinChannel(1, target, null)
                fixture.assertFailure(R.string.chatlist_join_invalid_channel)
            }
            for (key in listOf("two words", "comma,key", "cr\rkey", "€".repeat(131))) {
                fixture.model.joinChannel(1, "+private", key)
                fixture.assertFailure(R.string.channel_list_join_invalid_key, revealKey = true)
            }
            fixture.transports.getValue(1L).feed(":srv 005 me CHANTYPES= :supported")
            runCurrent()
            fixture.connections.publishStates()
            runCurrent()
            assertEquals("", rules.value.getValue(1L).chanTypes)
            fixture.model.joinChannel(1, "+private", null)
            fixture.assertFailure(R.string.chatlist_join_invalid_channel)
            fixture.model.joinChannel(3, "#room", null)
            fixture.assertFailure(R.string.chatlist_join_connection_lost)
            assertTrue(fixture.connections.writes.isEmpty())
        }

    @Test
    fun serverRejectionIsObservedBeforeWriteReturnsAndNeverEchoesTheKey() =
        runTest {
            val fixture = Fixture(this)
            val badKey = ChannelJoinOutcome.Rejected(1, "+LOCKED{ROOM}", "Bad key: wrong-secret", "475", ChannelJoinRejectionKind.BAD_KEY)
            fixture.model.joinChannel(1, "+Locked[Room]", null)
            runCurrent()
            val pending = badKey.copy(requestId = fixture.connections.lastRequestId)
            fixture.connections.channelJoinOutcomes.emit(pending.copy(networkId = 2))
            fixture.connections.channelJoinOutcomes.emit(pending.copy(channel = "+unsolicited"))
            runCurrent()
            assertEquals(NewConversationJoinState(joining = true), fixture.state)
            fixture.connections.channelJoinOutcomes.emit(pending)
            runCurrent()
            fixture.assertFailure(R.string.channel_list_join_bad_key, needsKey = true)

            fixture.connections.onWrite = { requestId ->
                fixture.connections.channelJoinOutcomes.emit(badKey.copy(code = "474", kind = ChannelJoinRejectionKind.OTHER, requestId = requestId))
                awaitCancellation()
            }
            fixture.model.joinChannel(1, "+Locked[Room]", "wrong-secret")
            runCurrent()
            assertEquals(JoinWrite(1, "+Locked[Room]", "wrong-secret", false), fixture.connections.writes.last())
            fixture.assertFailure(R.string.channel_list_join_rejected)

            fixture.connections.onWrite = { false }
            fixture.model.joinChannel(1, "+Locked[Room]", "other-key")
            runCurrent()
            fixture.assertFailure(R.string.channel_list_join_not_sent, revealKey = true)
        }

    @Test
    fun cancelledSameChannelRejectionDoesNotWinAgainstTheCurrentRetry() =
        runTest {
            val fixture = Fixture(this)
            fixture.model.joinChannel(1, "+room", "old-key")
            runCurrent()
            val stale =
                ChannelJoinOutcome.Rejected(
                    1,
                    "+room",
                    "Old bad key",
                    "475",
                    ChannelJoinRejectionKind.BAD_KEY,
                    fixture.connections.lastRequestId,
                )
            fixture.model.cancelNewConversationJoin()
            fixture.model.joinChannel(1, "+ROOM", "new-key")
            runCurrent()
            val currentId = fixture.connections.lastRequestId
            assertTrue(currentId != stale.requestId)
            fixture.connections.channelJoinOutcomes.emit(stale.copy(requestId = null))
            fixture.connections.channelJoinOutcomes.emit(stale)
            runCurrent()
            assertEquals(NewConversationJoinState(joining = true), fixture.state)

            fixture.connections.channelJoinOutcomes.emit(stale.copy(requestId = currentId))
            runCurrent()
            fixture.assertFailure(R.string.channel_list_join_bad_key, needsKey = true)
        }

    @Test
    fun cancelAndRetargetIgnorePriorRepliesButOpenExistingLiveMembershipWithoutJoiningAgain() =
        runTest {
            val fixture = Fixture(this)
            fixture.model.joinChannel(1, "+old", null)
            runCurrent()
            fixture.model.cancelNewConversationJoin()
            fixture.model.joinChannel(2, "#new", null)
            runCurrent()
            fixture.transports.getValue(1L).feed(":me!u@h JOIN +old")
            fixture.memberships.getValue(1L).value = listOf(JoinedChannelRow(10, 1, "+old"))
            fixture.connections.channelJoinOutcomes.emit(ChannelJoinOutcome.Rejected(1, "+old", "Too late", "475", ChannelJoinRejectionKind.BAD_KEY))
            runCurrent()
            assertEquals(NewConversationJoinState(joining = true), fixture.state)
            assertFalse(fixture.model.consumeNewConversationJoin(10))

            fixture.model.joinChannel(1, "+old", null)
            runCurrent()
            assertEquals(listOf(JoinWrite(1, "+old", null, false), JoinWrite(2, "#new", null, false)), fixture.connections.writes)
            assertEquals(10L, fixture.state.openBufferId)
            fixture.transports.getValue(2L).feed(":me!u@h JOIN #new")
            fixture.memberships.getValue(2L).value = listOf(JoinedChannelRow(20, 2, "#new"))
            runCurrent()
            assertTrue(fixture.model.consumeNewConversationJoin(10))
        }

    @Test
    fun socketReplacementAndLostSelfMembershipInvalidatePendingNavigation() =
        runTest {
            val fixture = Fixture(this)
            fixture.model.joinChannel(1, "+old", null)
            runCurrent()
            val replacement = TestTransport()
            fixture.connections.clients[1L] = readyClient(replacement, chanTypes = "+", caseMapping = "ascii")
            fixture.connections.publishStates()
            fixture.transports.getValue(1L).feed(":me!u@h JOIN +old")
            fixture.memberships.getValue(1L).value = listOf(JoinedChannelRow(10, 1, "+old"))
            runCurrent()
            fixture.assertFailure(R.string.chatlist_join_connection_lost)
            assertFalse(fixture.model.consumeNewConversationJoin(10))

            fixture.model.joinChannel(1, "+old", null)
            replacement.feed(":me!u@h JOIN +old")
            runCurrent()
            assertEquals(10L, fixture.state.openBufferId)
            replacement.feed(":me!u@h PART +old :gone")
            runCurrent()
            assertNull(fixture.state.openBufferId)
            assertFalse(fixture.model.consumeNewConversationJoin(10))

            fixture.model.joinChannel(1, "+new", null)
            runCurrent()
            assertEquals(NewConversationJoinState(joining = true), fixture.state)
            replacement.close()
            runCurrent()
            fixture.assertFailure(R.string.chatlist_join_connection_lost)
        }

    @Test
    fun aTimeoutDoesNotPublishNavigationForLateMembership() =
        runTest {
            val fixture = Fixture(this)
            fixture.connections.onWrite = { awaitCancellation() }
            fixture.model.joinChannel(1, "+slow", "optional-key")
            runCurrent()
            advanceTimeBy(20_000L)
            runCurrent()
            fixture.assertFailure(R.string.channel_list_join_timeout, revealKey = true)
            fixture.transports.getValue(1L).feed(":me!u@h JOIN +slow")
            fixture.memberships.getValue(1L).value = listOf(JoinedChannelRow(10, 1, "+slow"))
            runCurrent()
            fixture.assertFailure(R.string.channel_list_join_timeout, revealKey = true)
        }

    private inner class Fixture(
        scope: TestScope,
        persistedIdentity: NetworkIdentityEntity? = null,
        delayIsupport: Boolean = false,
    ) {
        val transports = mapOf(1L to TestTransport(), 2L to TestTransport())
        val memberships = transports.mapValues { MutableStateFlow(emptyList<JoinedChannelRow>()) }
        val roomKeys = mutableMapOf<Long, String>()
        val connections =
            TestConnections(
                transports
                    .mapValues { (id, transport) ->
                        if (id == 1L && delayIsupport) {
                            scope.readyClient(transport, chanTypes = null, caseMapping = null)
                        } else {
                            scope.readyClient(transport, if (id == 1L) "+" else "#")
                        }
                    }.toMutableMap(),
            )
        val model: ChatListViewModel
        val state get() = model.newConversationJoin.value

        init {
            connections.publishStates()
            val networks =
                (1L..4L).map { id ->
                    NetworkEntity(
                        id = id,
                        name = "Network $id",
                        role = if (id == 4L) NetworkRole.BOUNCER_ROOT else NetworkRole.DIRECT,
                        host = "irc.example",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    )
                }
            model =
                ChatListViewModel(
                    bufferRepository =
                        object : BufferRepository by FakeBuffers() {
                            override fun observeJoinedChannels(networkId: Long) = memberships.getValue(networkId)

                            override suspend fun joinedBufferId(
                                networkId: Long,
                                normalizedChannel: String,
                            ): Long? =
                                memberships
                                    .getValue(networkId)
                                    .value
                                    .firstOrNull {
                                        it.networkId == networkId &&
                                            (roomKeys[it.bufferId] ?: IrcCaseMapping.Rfc1459.normalize(it.displayName)) == normalizedChannel
                                    }?.bufferId
                        },
                    networkRepository = FakeNetworks(networks),
                    connectionManager = connections,
                    historyResync =
                        object : HistoryResyncController {
                            override fun syncStatus(bufferId: Long) = flowOf<HistorySyncStatus>(HistorySyncStatus.Idle)

                            override suspend fun reconcileBuffer(
                                buffer: BufferEntity,
                                client: IrcClient,
                                statusOwnerId: Long,
                                preserveUnread: Boolean,
                                isCurrent: () -> Boolean,
                            ) = HistoryResyncState.Idle

                            override suspend fun reconcilePendingMessage(
                                buffer: BufferEntity,
                                client: IrcClient,
                                isCurrent: () -> Boolean,
                            ) = HistoryResyncState.Idle
                        },
                    gapFiller = NoopHistoryGapFiller,
                    channelCloseCoordinator =
                        object : ChannelCloseCoordinator {
                            override fun start() = Unit

                            override suspend fun requestClose(bufferId: Long) = Unit
                        },
                    readMarkerRepository = FakeReadMarkers(),
                    settingsRepository = FakeSettings(),
                    onboardingPrefs =
                        object : OnboardingPrefs {
                            override val completed = flowOf(true)
                            override val communityInvitationHandled = flowOf(true)

                            override suspend fun markCompleted() = Unit

                            override suspend fun markCommunityInvitationHandled() = Unit
                        },
                    globalFeedPrefs =
                        object : GlobalFeedPrefs {
                            override val enabled = flowOf(false)

                            override suspend fun setEnabled(enabled: Boolean) = Unit
                        },
                    dickordLabsPrefs = fakeDickordLabsPrefs(),
                    savedStateHandle = SavedStateHandle(),
                    appVisibility = AlwaysOnScreen,
                    context = context,
                    networkIdentityDao = TestIdentities(persistedIdentity),
                )
            models.put("chat-list", model)
            scope.runCurrent()
        }

        fun assertFailure(
            error: Int,
            needsKey: Boolean = false,
            revealKey: Boolean = false,
        ) {
            assertEquals(NewConversationJoinState(error = ChannelJoinFailure(context.getString(error), needsKey), revealKey = revealKey), state)
        }
    }

    private class TestIdentities(
        identity: NetworkIdentityEntity?,
    ) : NetworkIdentityDao {
        private val identities = MutableStateFlow(listOfNotNull(identity))

        override fun observeAll() = identities

        override fun observe(networkId: Long) = identities.map { byNetworkNow(networkId) }

        override suspend fun byNetwork(networkId: Long) = byNetworkNow(networkId)

        override fun byNetworkNow(networkId: Long) = identities.value.firstOrNull { it.networkId == networkId }

        override suspend fun upsert(identity: NetworkIdentityEntity) {
            identities.update { rows -> rows.filterNot { it.networkId == identity.networkId } + identity }
        }

        override suspend fun updateSelfNick(
            networkId: Long,
            selfNick: String,
        ): Int {
            val identity = byNetworkNow(networkId) ?: return 0
            upsert(identity.copy(selfNick = selfNick))
            return 1
        }
    }

    private data class JoinWrite(
        val networkId: Long,
        val channel: String,
        val key: String?,
        val rememberKey: Boolean,
    )

    private class TestConnections(
        val clients: MutableMap<Long, IrcClient>,
    ) : NoopConnectionManager() {
        override val channelJoinOutcomes = MutableSharedFlow<ChannelJoinOutcome>()
        val writes = mutableListOf<JoinWrite>()
        var lastRequestId: String? = null
        var onWrite: suspend (String) -> Boolean = { true }

        fun publishStates() {
            states.value = clients.mapValues { it.value.state.value }
        }

        override fun clientFor(networkId: Long) = clients[networkId]

        override suspend fun joinChannelForRejection(
            networkId: Long,
            expectedClient: IrcClient,
            channel: String,
            key: String?,
            requestId: String,
        ): Boolean {
            if (clients[networkId] !== expectedClient) return false
            lastRequestId = requestId
            writes += JoinWrite(networkId, channel, key, rememberKey = false)
            return onWrite(requestId)
        }
    }

    private fun TestScope.readyClient(
        transport: TestTransport,
        chanTypes: String?,
        caseMapping: String? = "rfc1459",
    ): IrcClient {
        val client =
            IrcClient(
                IrcClientConfig("irc.example", 6697, true, "me", "me", "Me"),
                TransportFactory { _, _, _, _, _ -> transport },
                backgroundScope,
            ).apply { start() }
        runCurrent()
        transport.feed(":srv CAP * LS :message-tags")
        runCurrent()
        transport.feed(":srv CAP me ACK :message-tags")
        val support = listOfNotNull(chanTypes?.let { "CHANTYPES=$it" }, caseMapping?.let { "CASEMAPPING=$it" })
        if (support.isNotEmpty()) transport.feed(":srv 005 me ${support.joinToString(" ")} :supported")
        transport.feed(":srv 001 me :Welcome")
        runCurrent()
        check(client.state.value is IrcClientState.Ready)
        return client
    }

    private class TestTransport : IrcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        override val incoming = inbound.consumeAsFlow()

        override suspend fun connect() = Unit

        override suspend fun send(line: String) {
            sent += line
        }

        override suspend fun close() {
            inbound.close()
        }

        fun feed(vararg lines: String) {
            lines.forEach { check(inbound.trySend(it).isSuccess) }
        }
    }
}
