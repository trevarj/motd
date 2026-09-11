package io.github.trevarj.motd.dickord

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DickordPortalViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `cached rows remain visible offline and canonical lookup distinguishes loading found and missing`() =
        runTest(dispatcher) {
            val cached = row(10, folderId = 50, pinned = true)
            val buffers = FakeBuffers(listOf(cached), canonical = { id -> if (id == 99L) 10L else null })
            val viewModel = viewModel(FakePrefs(true), buffers, FakeConnections())
            runCurrent()

            assertFalse(viewModel.state.value.loading)
            assertTrue(viewModel.state.value.enabled)
            assertTrue(viewModel.state.value.offline)
            assertEquals(
                cached,
                viewModel.state.value.groups
                    .single { it.guildId == "100" }
                    .conversations
                    .single()
                    .row,
            )

            val found = viewModel.canonicalRow(99).take(2).toList()
            assertEquals(DickordPortalRowLookup.Loading, found[0])
            assertEquals(DickordPortalRowLookup.Found(cached), found[1])
            val missing = viewModel.canonicalRow(404).take(2).toList()
            assertEquals(listOf(DickordPortalRowLookup.Loading, DickordPortalRowLookup.Missing), missing)
        }

    @Test
    fun `active destination requests once per ready epoch and entry pauses cannot cancel another entry`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val client = readyClient(transport)
            val ready = client.state.value as IrcClientState.Ready
            val connections = FakeConnections(mapOf(1L to ready), mapOf(1L to client))
            val buffers = FakeBuffers(listOf(row(10)))
            val viewModel = viewModel(FakePrefs(true), buffers, connections)
            runCurrent()
            assertEquals(0, transport.snapshotRequests())

            viewModel.setEntryActive("portal", true)
            runCurrent()
            assertEquals(1, transport.snapshotRequests())

            buffers.rows.value = listOf(row(10).copy(unreadCount = 3, dickordChannelJson = guildJson(channelName = "renamed")))
            viewModel.setEntryActive("chat-10", true)
            viewModel.setEntryActive("portal", false)
            runCurrent()
            assertEquals(1, transport.snapshotRequests())

            connections.states.value = mapOf(1L to IrcClientState.Disconnected)
            runCurrent()
            connections.states.value = mapOf(1L to ready)
            runCurrent()
            assertEquals(2, transport.snapshotRequests())

            viewModel.setEntryActive("chat-10", false)
            connections.states.value = mapOf(1L to IrcClientState.Disconnected)
            runCurrent()
            connections.states.value = mapOf(1L to ready)
            runCurrent()
            assertEquals(2, transport.snapshotRequests())

            viewModel.setEntryActive("direct-phone-chat", true)
            runCurrent()
            assertEquals(3, transport.snapshotRequests())
        }

    @Test
    fun `automatic snapshots cover each owning network only after the lab is enabled`() =
        runTest(dispatcher) {
            val firstTransport = RecordingTransport()
            val secondTransport = RecordingTransport()
            val firstClient = readyClient(firstTransport)
            val secondClient = readyClient(secondTransport)
            val firstReady = firstClient.state.value as IrcClientState.Ready
            val secondReady = secondClient.state.value as IrcClientState.Ready
            val prefs = FakePrefs(false)
            val viewModel =
                viewModel(
                    prefs,
                    FakeBuffers(
                        listOf(
                            row(10, networkId = 1),
                            row(20, networkId = 2, rawName = "#discord.control", descriptor = null),
                        ),
                    ),
                    FakeConnections(
                        mapOf(1L to firstReady, 2L to secondReady),
                        mapOf(1L to firstClient, 2L to secondClient),
                    ),
                )
            viewModel.setEntryActive("portal", true)
            runCurrent()
            assertEquals(0, firstTransport.snapshotRequests())
            assertEquals(0, secondTransport.snapshotRequests())

            prefs.enabledState.value = true
            runCurrent()
            assertEquals(1, firstTransport.snapshotRequests())
            assertEquals(1, secondTransport.snapshotRequests())
        }

    @Test
    fun `explicit refresh always attempts while ordinary and background destinations do not`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val client = readyClient(transport)
            val ready = client.state.value as IrcClientState.Ready
            val connections = FakeConnections(mapOf(1L to ready), mapOf(1L to client))
            val viewModel = viewModel(FakePrefs(true), FakeBuffers(listOf(row(10))), connections)
            runCurrent()

            viewModel.setEntryActive("ordinary-route", false)
            connections.states.value = mapOf(1L to IrcClientState.Disconnected)
            runCurrent()
            connections.states.value = mapOf(1L to ready)
            runCurrent()
            assertEquals(0, transport.snapshotRequests())

            viewModel.refresh()
            runCurrent()
            viewModel.refresh()
            runCurrent()
            assertEquals(2, transport.snapshotRequests())
        }

    @Test
    fun `control buffer is enough to own an automatic snapshot without becoming a portal conversation`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val client = readyClient(transport)
            val ready = client.state.value as IrcClientState.Ready
            val control = row(5, rawName = "#discord.control", descriptor = null)
            val viewModel =
                viewModel(
                    FakePrefs(true),
                    FakeBuffers(listOf(control)),
                    FakeConnections(mapOf(1L to ready), mapOf(1L to client)),
                )
            runCurrent()

            assertTrue(
                viewModel.state.value.groups
                    .single()
                    .conversations
                    .isEmpty(),
            )
            viewModel.setEntryActive("portal", true)
            runCurrent()
            assertEquals(1, transport.snapshotRequests())
        }

    @Test
    fun `disable and enable restore rows and selection without changing saved organization`() =
        runTest(dispatcher) {
            val saved = SavedStateHandle()
            val prefs = FakePrefs(true)
            val original = row(10, pinned = true, folderId = 42)
            val buffers = FakeBuffers(listOf(original))
            val viewModel = viewModel(prefs, buffers, FakeConnections(), saved)
            runCurrent()

            viewModel.selectGroup("guild:1:100")
            runCurrent()
            prefs.enabledState.value = false
            runCurrent()
            assertFalse(viewModel.state.value.enabled)
            assertTrue(
                viewModel.state.value.groups
                    .isEmpty(),
            )
            assertEquals("guild:1:100", viewModel.state.value.selectedGroupKey)

            prefs.enabledState.value = true
            runCurrent()
            val restored =
                viewModel.state.value.groups
                    .single { it.guildId == "100" }
                    .conversations
                    .single()
                    .row
            assertEquals(original, restored)
            assertTrue(restored.pinned)
            assertEquals(42L, restored.folderId)
            assertEquals("guild:1:100", viewModel.state.value.selectedGroupKey)

            buffers.rows.value = emptyList()
            runCurrent()
            assertEquals(DICKORD_PORTAL_DMS_KEY, viewModel.state.value.selectedGroupKey)
            assertEquals(DICKORD_PORTAL_DMS_KEY, saved.get<String>("dickord_portal_group"))
        }

    @Test
    fun `archive mode and selected group survive ViewModel recreation`() =
        runTest(dispatcher) {
            val saved = SavedStateHandle()
            val archived = row(20, archived = true)
            val buffers = FakeBuffers(listOf(archived))
            val first = viewModel(FakePrefs(true), buffers, FakeConnections(), saved)
            runCurrent()
            first.setShowArchived(true)
            runCurrent()
            first.selectGroup("guild:1:100")
            runCurrent()

            val restored = viewModel(FakePrefs(true), buffers, FakeConnections(), saved)
            runCurrent()
            assertTrue(restored.state.value.showArchived)
            assertEquals("guild:1:100", restored.state.value.selectedGroupKey)
            assertEquals(
                listOf(20L),
                restored.state.value.groups
                    .single { it.guildId == "100" }
                    .conversations
                    .map { it.row.bufferId },
            )
        }

    @Test
    fun `snapshot transport requires a ready message-tags client and sends the exact TAGMSG`() =
        runTest(dispatcher) {
            assertFalse(FakeConnections().requestDickordChannelSnapshot(1))

            val noTagsTransport = RecordingTransport()
            val noTags = readyClient(noTagsTransport, "batch server-time")
            assertFalse(FakeConnections(clients = mapOf(1L to noTags)).requestDickordChannelSnapshot(1))
            assertEquals(0, noTagsTransport.snapshotRequests())

            val transport = RecordingTransport()
            val ready = readyClient(transport)
            assertTrue(FakeConnections(clients = mapOf(1L to ready)).requestDickordChannelSnapshot(1))
            assertEquals(listOf("@+dickord/channel-request=1 TAGMSG #discord.control"), transport.sent)
        }

    private fun viewModel(
        prefs: FakePrefs,
        buffers: FakeBuffers,
        connections: FakeConnections,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ) = DickordPortalViewModel(savedStateHandle, prefs, buffers, connections)

    private suspend fun TestScope.readyClient(
        transport: RecordingTransport,
        caps: String = "batch message-tags server-time",
    ): IrcClient {
        val client =
            IrcClient(
                IrcClientConfig("irc.example", 6697, true, "me", "me", "Me"),
                TransportFactory { _, _, _, _, _ -> transport },
                CoroutineScope(SupervisorJob() + coroutineContext),
            )
        client.start()
        runCurrent()
        transport.feed(":srv CAP * LS :$caps")
        runCurrent()
        transport.feed(":srv CAP me ACK :$caps")
        transport.feed(":srv 005 me CHANTYPES=# :supported")
        transport.feed(":srv 001 me :Welcome")
        runCurrent()
        check(client.state.value is IrcClientState.Ready)
        transport.sent.clear()
        return client
    }

    private class FakePrefs(
        initial: Boolean,
    ) : DickordLabsPrefs(ApplicationProvider.getApplicationContext<Context>()) {
        val enabledState = MutableStateFlow(initial)
        override val enabled: Flow<Boolean> = enabledState

        override suspend fun setEnabled(enabled: Boolean) {
            enabledState.value = enabled
        }
    }

    private class FakeBuffers(
        initial: List<ChatListRow>,
        private val canonical: suspend (Long) -> Long? = { it },
    ) : BufferRepository {
        val rows = MutableStateFlow(initial)

        override fun observeChatList(): Flow<List<ChatListRow>> = rows

        override suspend fun canonicalBufferId(id: Long): Long? = canonical(id)

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

        override suspend fun deleteBuffer(id: Long) = Unit
    }

    private class FakeConnections(
        initialStates: Map<Long, IrcClientState> = emptyMap(),
        private val clients: Map<Long, IrcClient> = emptyMap(),
    ) : NoopConnectionManager(initialStates) {
        override fun clientFor(networkId: Long): IrcClient? = clients[networkId]
    }

    private class RecordingTransport : IrcTransport {
        private val incomingLines = Channel<String>(Channel.UNLIMITED)
        val sent = CopyOnWriteArrayList<String>()
        override val incoming = incomingLines.consumeAsFlow()

        override suspend fun connect() = Unit

        override suspend fun send(line: String) {
            sent += line
        }

        override suspend fun close() {
            incomingLines.close()
        }

        suspend fun feed(line: String) {
            incomingLines.send(line)
        }

        fun snapshotRequests(): Int = sent.count { it == "@+dickord/channel-request=1 TAGMSG #discord.control" }
    }

    private fun row(
        id: Long,
        networkId: Long = 1,
        rawName: String = "#discord.server.channel$id",
        pinned: Boolean = false,
        archived: Boolean = false,
        folderId: Long? = null,
        descriptor: String? = guildJson(channelId = (1000 + id).toString()),
    ) = ChatListRow(
        bufferId = id,
        networkId = networkId,
        networkName = "Bridge $networkId",
        displayName = rawName,
        type = BufferType.CHANNEL,
        pinned = pinned,
        muted = false,
        lastMessageText = null,
        lastMessageSender = null,
        lastMessageTime = null,
        unreadCount = 0,
        mentionCount = 0,
        archived = archived,
        folderId = folderId,
        dickordChannelJson = descriptor,
    )

    private fun guildJson(
        channelId: String = "1010",
        channelName: String = "general",
    ): String =
        Json.encodeToString(
            DickordChannelDescriptor(
                v = 1,
                guildId = "100",
                guildName = "Example Server",
                channelId = channelId,
                channelType = 0,
                parentId = null,
                channelName = channelName,
            ),
        )
}
