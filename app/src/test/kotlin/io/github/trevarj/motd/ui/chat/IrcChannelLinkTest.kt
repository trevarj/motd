package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.JoinedChannelRow
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.NoopBouncerKindPrefs
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.gesture.FakeBuffers
import io.github.trevarj.motd.gesture.FakeNetworks
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.service.ChannelJoinOutcome
import io.github.trevarj.motd.service.ChannelJoinRejectionKind
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IrcChannelLinkTest {
    private val dispatcher = StandardTestDispatcher()
    private val link = IrcChannelLink("irc.example.test", 6697, true, "#Room")

    @Before
    fun setMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun parsesSingleDecodedTargetWithExplicitTransport() {
        assertEquals(link, parseIrcChannelLink("IRCS://IRC.Example.test./Room"))
        assertEquals(link, parseIrcChannelLink("ircs://irc.example.test/%23Room"))
        assertEquals(link, parseIrcChannelLink("ircs://irc.example.test/#Room"))
        assertEquals(link.copy(channel = "##Room"), parseIrcChannelLink("ircs://irc.example.test/%23%23Room"))
        assertEquals(link.copy(channel = "##Room"), parseIrcChannelLink("ircs://irc.example.test/##Room"))
        assertEquals(link.copy(tls = false, port = 6667), parseIrcChannelLink("irc://irc.example.test/Room"))
        assertEquals(
            IrcChannelLink("host", 6667, false, "#Room:key"),
            parseIrcChannelLink("irc://host/Room:key"),
        )
        assertEquals(
            IrcChannelLink("2001:db8::1", 7000, true, "#A+B%23"),
            parseIrcChannelLink("ircs://[2001:DB8::1]:7000/A+B%2523"),
        )
        assertEquals("xn--bcher-kva.example", parseIrcChannelLink("irc://bücher.example/Room")?.host)
    }

    @Test
    fun rejectsAmbiguousTargetsAndInvalidAuthorities() {
        val urls =
            listOf(
                "irc://host",
                "irc://host/",
                "irc://host/#",
                "irc://host/Room#Other",
                "irc://host/#Room?join",
                "irc://host/Room?join",
                "irc://host/Room?",
                "irc://host/#Room#Other",
                "irc://host/One/Two",
                "irc://host/One%2FTwo",
                "irc://host/One%2cTwo",
                "irc://host/%20Room",
                "irc://host/Room%0d%0aJOIN",
                "irc://host/Room%E2%80%A8Other",
                "irc://host/Room%E2%80%AE",
                "irc://host/Room%FF",
                "irc://host/Room%ZZ",
                "irc://host/:key",
                "irc://host/Room:",
                "irc://host/Room:key:other",
                "irc://host/Room,key",
                "irc://nick:pass@host/Room",
                "irc://nick%40host/Room",
                "irc://host:/Room",
                "irc://host:0/Room",
                "irc://host:65536/Room",
                "irc://host:-1/Room",
                "irc://host:abc/Room",
                "irc://host../Room",
                "irc://[2001:::1]/Room",
                "irc://[fe80::1%25wlan0]/Room",
            )
        for (url in urls) {
            assertNull(url, parseIrcChannelLink(url))
        }
    }

    @Test
    fun matchesExactAccountsAndOnlyCachedSojuUpstreams() {
        val root = network(3).copy(role = NetworkRole.BOUNCER_ROOT, host = "relay.example.test")
        val child = root.copy(id = 4, role = NetworkRole.BOUNCER_CHILD, parentId = root.id, bouncerNetId = "a")
        val rows =
            listOf(
                network(1).copy(host = "IRC.EXAMPLE.TEST."),
                network(2),
                root,
                child,
                network(5).copy(port = 6667),
                network(6).copy(tls = false),
                network(7).copy(host = "alias.example.test"),
                network(8),
                network(9).copy(saslUser = "account/network"),
                network(10).copy(serverPassword = "ordinary-secret"),
                network(11).copy(serverPassword = "network:relay-secret"),
            )
        val upstream = mapOf("host" to link.host, "tls" to "1")
        val cached = { rootId: Long, netId: String -> upstream.takeIf { rootId == root.id && netId == "a" } }
        assertEquals(listOf(1L, 2L, 4L, 10L), matchingIrcLinkAccounts(link, rows, setOf(8L), cached).map { it.id })
        assertEquals(listOf(1L, 2L, 10L), matchingIrcLinkAccounts(link, rows, setOf(8L)) { _, _ -> null }.map { it.id })
        assertTrue(matchingIrcLinkAccounts(link.copy(host = root.host), rows, emptySet()) { _, _ -> mapOf("host" to root.host) }.isEmpty())
        assertTrue(matchingIrcLinkAccounts(link, listOf(root, child), emptySet()) { _, _ -> upstream + ("port" to "bad") }.isEmpty())
    }

    @Test
    fun cancellationAndAccountSelectionDoNotConnectOrJoinBeforeConfirmation() =
        runTest(dispatcher) {
            val connections = Connections()
            val model = model(connections, Buffers(sourceNetwork = 2L), network(1), network(2))
            model.review(link, 50L)
            runCurrent()
            val accountState = model.review.value
            assertEquals(listOf(1L, 2L), accountState.accounts.map { it.id })
            assertEquals(2L, accountState.selectedId)
            model.selectAccount(1L)
            val selectedReview = model.review.value
            assertEquals(IrcLinkAction.CONNECT_AND_JOIN, selectedReview.accounts.first().action)
            model.dismiss()
            assertNull(model.review.value.link)
            assertTrue(connections.connected.isEmpty())
            assertTrue(connections.joins.isEmpty())

            val nonMatchingSource = model(connections, Buffers(sourceNetwork = 3L), network(1), network(2))
            nonMatchingSource.review(link, 50L)
            runCurrent()
            assertNull(nonMatchingSource.review.value.selectedId)
            nonMatchingSource.confirm()
            runCurrent()
            assertTrue(connections.connected.isEmpty())
        }

    @Test
    fun confirmsOfflineAccountAndOpensOnlyAfterCanonicalMembership() =
        runTest(dispatcher) {
            val connections = Connections()
            val mappedLink = link.copy(channel = "#Room[")
            val buffers = Buffers(reportedName = { it.uppercase().replace('{', '[') })
            val model = model(connections, buffers, network(1))
            model.review(mappedLink, 50L)
            runCurrent()
            model.confirm()
            runCurrent()
            assertEquals(listOf(1L), connections.connected)
            assertTrue(connections.joins.isEmpty())
            val transport = readyClient(connections)
            assertEquals(listOf(1L to mappedLink.channel), connections.joins)
            assertNull(model.review.value.joinedId)
            buffers.joined.value = mapOf((2L to "#room{") to 99L)
            runCurrent()
            assertNull(model.review.value.joinedId)
            buffers.joined.value += (1L to "#room{") to 60L
            runCurrent()
            assertNull("A stale joined row cannot authorize navigation without live membership", model.review.value.joinedId)
            transport.feed(":nick!user@host JOIN :#ROOM[")
            runCurrent()
            assertEquals(60L, model.review.value.joinedId)
        }

    @Test
    fun opensAnExistingMembershipWithoutJoiningAndDoesNotSilentlyWidenApproval() =
        runTest(dispatcher) {
            val connections = Connections()
            val transport = readyClient(connections)
            transport.feed(":nick!user@host JOIN :#ROOM")
            runCurrent()
            val buffers = Buffers()
            val model = model(connections, buffers, network(1))
            model.review(link, 50L)
            runCurrent()
            val openReview = model.review.value
            assertEquals(IrcLinkAction.OPEN, openReview.accounts.single().action)
            assertNull(openReview.joinedId)
            model.confirm()
            runCurrent()
            assertNull(model.review.value.joinedId)
            assertTrue(connections.joins.isEmpty())
            buffers.joined.value = mapOf((1L to "#room") to 60L)
            runCurrent()
            assertEquals(60L, model.review.value.joinedId)
            assertTrue(connections.joins.isEmpty())
            model.dismiss()
            model.review(link, 50L)
            runCurrent()
            buffers.joined.value = emptyMap()
            connections.client?.stop()
            connections.client = null
            connections.states.value = emptyMap()
            model.confirm()
            runCurrent()
            val offlineReview = model.review.value
            assertEquals(IrcLinkAction.CONNECT_AND_JOIN, offlineReview.accounts.single().action)
            assertTrue(connections.connected.isEmpty())
            assertTrue(connections.joins.isEmpty())
        }

    @Test
    fun handlesImmediateRejectionAndTimeoutWithoutOpeningAnUnjoinedRoom() =
        runTest(dispatcher) {
            val connections = Connections()
            readyClient(connections)
            val model = model(connections, Buffers(), network(1))
            model.review(link, 50L)
            runCurrent()
            connections.rejection = ChannelJoinOutcome.Rejected(1L, "#ROOM", "Invite only", "473", ChannelJoinRejectionKind.INVITE_ONLY)
            model.confirm()
            runCurrent()
            assertEquals("Invite only", model.review.value.error)
            assertNull(model.review.value.working)
            assertNull(model.review.value.joinedId)
            connections.rejection = null
            model.confirm()
            advanceTimeBy(20_001)
            assertEquals("Channel join timed out", model.review.value.error)
            assertNull(model.review.value.working)
            assertNull(model.review.value.joinedId)
        }

    @Test
    fun endpointReboundWhileWaitingRequiresFreshApprovalOfSoleRemainingAccount() =
        runTest(dispatcher) {
            val connections = Connections()
            val row = network(1)
            val other = network(2)
            val networks = FakeNetworks(listOf(row, other))
            val model = IrcLinkViewModel(networks, Buffers(), connections, NoopBouncerKindPrefs)
            model.review(link, 50L)
            runCurrent()
            model.selectAccount(1L)
            model.confirm()
            runCurrent()
            assertEquals(listOf(1L), connections.connected)
            networks.rows.value = listOf(row.copy(host = "different.example.test"), other)
            readyClient(connections)
            runCurrent()
            val refreshed = model.review.value
            assertEquals(listOf(2L), refreshed.accounts.map { it.id })
            assertEquals(2L, refreshed.selectedId)
            assertEquals(listOf(1L), connections.connected)
            assertTrue(refreshed.error?.contains("Review the action again") == true)
            assertNull(refreshed.joinedId)
            assertTrue(connections.joins.isEmpty())
            model.confirm()
            runCurrent()
            assertEquals(listOf(1L, 2L), connections.connected)
            model.dismiss()
        }

    @Test
    fun committedRoomIsNotOpenedAfterItsPhysicalConnectionIsReplaced() =
        runTest(dispatcher) {
            val connections = Connections()
            val oldTransport = readyClient(connections)
            val buffers = Buffers()
            val model = model(connections, buffers, network(1))
            model.review(link, 50L)
            runCurrent()
            model.confirm()
            runCurrent()
            oldTransport.feed(":nick!user@host JOIN :#ROOM")
            buffers.joined.value = mapOf((1L to "#room") to 60L)
            runCurrent()
            assertEquals(60L, model.review.value.joinedId)
            readyClient(connections)
            assertNull(model.consumeJoined())
            val failure = model.review.value.error
            assertTrue(failure?.contains("membership changed") == true)
        }

    private suspend fun TestScope.readyClient(connections: Connections): TestTransport {
        val transport = TestTransport()
        val client =
            IrcClient(
                IrcClientConfig(link.host, link.port, link.tls, "nick", "user", "Person"),
                TransportFactory { _, _, _, _, _ -> transport },
                backgroundScope,
            )
        connections.client = client
        client.start()
        runCurrent()
        transport.feed(":srv CAP * LS :batch message-tags")
        runCurrent()
        transport.feed(":srv CAP nick ACK :batch message-tags")
        transport.feed(":srv 005 nick CHANTYPES=# CASEMAPPING=rfc1459 :supported")
        transport.feed(":srv 001 nick :Welcome")
        runCurrent()
        val ready = client.state.value
        check(ready is IrcClientState.Ready)
        connections.states.value = mapOf(1L to ready)
        runCurrent()
        return transport
    }

    private fun model(
        connections: Connections,
        buffers: Buffers,
        vararg rows: NetworkEntity,
    ) = IrcLinkViewModel(FakeNetworks(rows.toList()), buffers, connections, NoopBouncerKindPrefs)

    private fun network(id: Long) =
        NetworkEntity(
            id = id,
            name = "Network $id",
            role = NetworkRole.DIRECT,
            host = link.host,
            port = link.port,
            tls = link.tls,
            nick = "nick$id",
            username = "user$id",
            realname = "Person $id",
        )

    private class Buffers(
        private val sourceNetwork: Long? = null,
        private val reportedName: (String) -> String = { it },
    ) : BufferRepository by FakeBuffers() {
        val joined = MutableStateFlow<Map<Pair<Long, String>, Long>>(emptyMap())

        override suspend fun buffer(id: Long) =
            sourceNetwork?.let {
                BufferEntity(id = id, networkId = it, type = BufferType.CHANNEL, name = "#source", displayName = "#source")
            }

        override fun observeJoinedChannels(networkId: Long) =
            joined.map { rows ->
                rows.mapNotNull { (key, id) ->
                    if (key.first == networkId) JoinedChannelRow(id, networkId, reportedName(key.second)) else null
                }
            }
    }

    private class Connections : NoopConnectionManager() {
        override val channelJoinOutcomes = MutableSharedFlow<ChannelJoinOutcome>()
        val connected = mutableListOf<Long>()
        val joins = mutableListOf<Pair<Long, String>>()
        var rejection: ChannelJoinOutcome.Rejected? = null
        var client: IrcClient? = null

        override fun clientFor(networkId: Long): IrcClient? = client.takeIf { networkId == 1L }

        override suspend fun connect(networkId: Long) {
            connected += networkId
        }

        override suspend fun joinChannel(
            networkId: Long,
            channel: String,
            key: String?,
            rememberKey: Boolean,
        ): Boolean {
            assertNull(key)
            joins += networkId to channel
            rejection?.let { channelJoinOutcomes.emit(it) }
            return true
        }
    }

    private class TestTransport : IrcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        override val incoming = inbound.consumeAsFlow()

        override suspend fun connect() = Unit

        override suspend fun send(line: String) = Unit

        override suspend fun close() {
            inbound.close()
        }

        suspend fun feed(line: String) {
            inbound.send(line)
        }
    }
}
