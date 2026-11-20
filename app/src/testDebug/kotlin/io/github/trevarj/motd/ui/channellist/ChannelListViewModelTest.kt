package io.github.trevarj.motd.ui.channellist

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.JoinedChannelRow
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelListViewModelTest {
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
    fun switchingNetworksIgnoresThePreviousNetworksOutstandingList() =
        runTest {
            val browser = Fixture(this)
            val first = browser.transports.getValue(1L)
            val second = browser.transports.getValue(2L)
            assertEquals(1L, browser.state.networkId)
            assertTrue(browser.state.popular.loading)
            assertTrue("LIST" in first.sent)
            first.feed(":srv 322 me #old-before-switch 100 :Old network")
            runCurrent()

            browser.model.selectNetwork(2L)
            runCurrent()
            assertEquals(2L, browser.state.networkId)
            assertTrue(browser.state.popular.loading)
            assertTrue("LIST" in second.sent)
            second.feed(":srv 322 me #current 100 :Selected network", ":srv 323 me :End of LIST")
            runCurrent()
            assertTrue(browser.state.popular.loaded)
            val popularRows = browser.state.popular.listings
            assertEquals(listOf("#current"), popularRows.map { it.name })

            first.feed(":srv 322 me #old-after-switch 100 :Late reply", ":srv 323 me :End of LIST")
            runCurrent()
            assertEquals(2L, browser.state.networkId)
            assertEquals(popularRows, browser.state.popular.listings)
        }

    @Test
    fun acceptedExactJoinOpensOnlyAfterMatchingCanonicalMembership() =
        runTest {
            val browser = Fixture(this)
            browser.model.join(" #KnOt[Lab] ", key = null, openAfterJoin = true)
            runCurrent()
            assertEquals(listOf(Triple(1L, "#KnOt[Lab]", null)), browser.connections.joins)
            assertEquals(setOf("#knot{lab}"), browser.state.pendingChannels)
            assertNull(browser.state.openBufferId)

            browser.transports.getValue(1L).feed(":me!u@h JOIN #knot{lab}")
            browser.memberships.getValue(1L).value = listOf(JoinedChannelRow(10L, 1L, "#unrelated"))
            browser.memberships.getValue(2L).value = listOf(JoinedChannelRow(20L, 2L, "#knot{lab}"))
            runCurrent()
            assertEquals(mapOf("#unrelated" to 10L), browser.state.joinedChannels)
            assertEquals(setOf("#knot{lab}"), browser.state.pendingChannels)
            assertNull(browser.state.openBufferId)

            browser.memberships.getValue(1L).value += JoinedChannelRow(11L, 1L, "#KNOT{LAB}")
            runCurrent()
            assertTrue(browser.state.pendingChannels.isEmpty())
            assertEquals(11L, browser.state.openBufferId)
        }

    @Test
    fun departedMembershipCannotBeOpenedByAStaleRowOrPendingNavigation() =
        runTest {
            val browser = Fixture(this)
            val membership = browser.memberships.getValue(1L)
            membership.value = listOf(JoinedChannelRow(11L, 1L, "#room"))
            runCurrent()
            browser.model.openJoined(11L)
            assertEquals(11L, browser.state.openBufferId)

            membership.value = emptyList()
            runCurrent()
            assertEquals(false, browser.model.consumeOpen(11L))
            browser.model.openJoined(11L)
            assertNull(browser.state.openBufferId)
        }

    @Test
    fun badKeyOnlyRejectsTheMatchingJoinAndAnUnsentRetryStopsWaiting() =
        runTest {
            val browser = Fixture(this)
            browser.model.join("#Locked[Room]", key = null, openAfterJoin = true)
            browser.model.join("#Other", key = null, openAfterJoin = false)
            runCurrent()
            val badKey = ChannelJoinOutcome.Rejected(1L, "#LOCKED{ROOM}", "Bad key", "475", ChannelJoinRejectionKind.BAD_KEY)
            browser.connections.channelJoinOutcomes.emit(badKey.copy(networkId = 2L))
            browser.connections.channelJoinOutcomes.emit(badKey.copy(channel = "#unsolicited"))
            runCurrent()
            assertEquals(setOf("#locked{room}", "#other"), browser.state.pendingChannels)
            assertTrue(browser.state.joinErrors.isEmpty())

            browser.connections.channelJoinOutcomes.emit(badKey)
            runCurrent()
            assertEquals(setOf("#other"), browser.state.pendingChannels)
            assertEquals(
                mapOf("#locked{room}" to ChannelJoinFailure(context.getString(R.string.channel_list_join_bad_key), needsKey = true)),
                browser.state.joinErrors,
            )
            assertNull(browser.state.openBufferId)

            browser.connections.acceptsJoin = false
            browser.model.join("#Locked[Room]", key = "key", openAfterJoin = true)
            runCurrent()
            assertEquals(Triple(1L, "#Locked[Room]", "key"), browser.connections.joins.last())
            assertEquals(false, browser.connections.lastRememberKey)
            assertEquals(setOf("#other"), browser.state.pendingChannels)
            assertEquals(
                mapOf("#locked{room}" to ChannelJoinFailure(context.getString(R.string.channel_list_join_not_sent), needsKey = true)),
                browser.state.joinErrors,
            )
            assertNull(browser.state.openBufferId)
        }

    private inner class Fixture(
        scope: TestScope,
    ) {
        private val networks =
            listOf(1L, 2L).map { id ->
                NetworkEntity(
                    id = id,
                    name = "Network $id",
                    role = NetworkRole.DIRECT,
                    host = "irc.example",
                    port = 6697,
                    nick = "me",
                    username = "me",
                    realname = "Me",
                )
            }
        val transports = networks.associate { it.id to TestTransport() }
        val memberships = networks.associate { it.id to MutableStateFlow(emptyList<JoinedChannelRow>()) }
        val connections: TestConnections
        val model: ChannelListViewModel
        val state get() = model.state.value

        init {
            val clients =
                transports.mapValues { (_, transport) ->
                    IrcClient(
                        IrcClientConfig("irc.example", 6697, true, "me", "me", "Me"),
                        TransportFactory { _, _, _, _, _ -> transport },
                        scope.backgroundScope,
                    ).apply { start() }
                }
            scope.runCurrent()
            transports.values.forEach { it.feed(":srv CAP * LS :message-tags") }
            scope.runCurrent()
            transports.values.forEach {
                it.feed(
                    ":srv CAP me ACK :message-tags",
                    ":srv 005 me CHANTYPES=# CASEMAPPING=rfc1459 :supported",
                    ":srv 001 me :Welcome",
                )
            }
            scope.runCurrent()
            check(clients.values.all { it.state.value is IrcClientState.Ready })
            connections = TestConnections(clients)
            val buffers =
                object : BufferRepository by FakeBuffers() {
                    override fun observeJoinedChannels(networkId: Long) = memberships.getValue(networkId)
                }
            model = ChannelListViewModel(SavedStateHandle(mapOf("networkId" to 1L)), FakeNetworks(networks), buffers, connections, context)
            models.put("browse", model)
            model.start()
            scope.runCurrent()
        }
    }

    private class TestConnections(
        private val clients: Map<Long, IrcClient>,
    ) : NoopConnectionManager(clients.mapValues { it.value.state.value }) {
        override val channelJoinOutcomes = MutableSharedFlow<ChannelJoinOutcome>()
        val joins = mutableListOf<Triple<Long, String, String?>>()
        var acceptsJoin = true
        var lastRememberKey: Boolean? = null

        override fun clientFor(networkId: Long) = clients[networkId]

        override suspend fun joinChannel(
            networkId: Long,
            channel: String,
            key: String?,
            rememberKey: Boolean,
        ): Boolean {
            joins += Triple(networkId, channel, key)
            lastRememberKey = rememberKey
            return acceptsJoin
        }
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
