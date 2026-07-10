package io.github.trevarj.motd.ui.chatlist

import androidx.lifecycle.SavedStateHandle
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.DeliveryMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Covers the delete-then-part sequencing in ChatListViewModel.deleteBuffer. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListDeleteTest {

    /** Records the delete calls; other reads return empty streams (state is not under test here). */
    private open class FakeBufferRepository : BufferRepository {
        val deleted = mutableListOf<Long>()
        override fun observeChatList(): Flow<List<ChatListRow>> = flowOf(emptyList())
        override fun observeBuffer(id: Long): Flow<BufferEntity?> = flowOf(null)
        override fun observeMembers(bufferId: Long): Flow<List<MemberEntity>> = flowOf(emptyList())
        override suspend fun setPinned(id: Long, pinned: Boolean) = Unit
        override suspend fun setMuted(id: Long, muted: Boolean) = Unit
        override suspend fun deleteBuffer(id: Long) { deleted += id }
    }

    private class FakeNetworkRepository : NetworkRepository {
        override fun observeNetworks(): Flow<List<NetworkEntity>> = flowOf(emptyList())
        override suspend fun addNetwork(n: NetworkEntity): Long = 0
        override suspend fun updateNetwork(n: NetworkEntity) = Unit
        override suspend fun deleteNetwork(id: Long) = Unit
        override suspend fun networkById(id: Long): NetworkEntity? = null
        override suspend fun childrenOf(rootId: Long): List<NetworkEntity> = emptyList()
    }

    /** Appends every part/delete to a shared [ops] log so ordering can be asserted. */
    private class FakeConnectionManager(private val ops: MutableList<String>) : ConnectionManager {
        override val connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(emptyMap())
        override fun clientFor(networkId: Long): IrcClient? = null
        override suspend fun startAll() = Unit
        override suspend fun stopAll() = Unit
        override suspend fun connect(networkId: Long) = Unit
        override suspend fun disconnect(networkId: Long) = Unit
        override suspend fun reconnectStale() = Unit
        override suspend fun sendMessage(bufferId: Long, text: String, replyToMsgid: String?) = Unit
        override suspend fun sendTyping(bufferId: Long, state: String) = Unit
        override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) = Unit
        override suspend fun joinChannel(networkId: Long, channel: String) = Unit
        override suspend fun partChannel(bufferId: Long, reason: String?) { ops += "part:$bufferId" }
        override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long = 0
        override suspend fun ensureServerBuffer(networkId: Long): Long = 0
        override suspend fun markRead(bufferId: Long, upToTime: Long) = Unit
        override suspend fun evaluatePushMode() = Unit
        override val certPrompts = MutableStateFlow<List<CertPrompt>>(emptyList())
        override suspend fun trustCert(prompt: CertPrompt) = Unit
        override fun dismissCertPrompt(prompt: CertPrompt) = Unit
    }

    private class FakeSettingsRepository : SettingsRepository {
        override val settings = MutableStateFlow(
            Settings(ThemeMode.SYSTEM, true, DeliveryMode.PERSISTENT_SOCKET),
        )
        override suspend fun setThemeMode(m: ThemeMode) = Unit
        override suspend fun setDynamicColor(enabled: Boolean) = Unit
        override suspend fun setDeliveryMode(m: DeliveryMode) = Unit
        override suspend fun setLayoutDensity(d: LayoutDensity) = Unit
        override suspend fun setNickColorsEnabled(enabled: Boolean) = Unit
        override suspend fun setNickColorPalette(p: NickColorPalette) = Unit
        override suspend fun setNickColorOverride(nick: String, hue: Int?) = Unit
        override suspend fun setFriend(nick: String, isFriend: Boolean) = Unit
        override suspend fun setFool(nick: String, isFool: Boolean) = Unit
        override suspend fun setFoolsMode(m: FoolsMode) = Unit
        override suspend fun setShowJoinPartQuit(show: Boolean) = Unit
        override suspend fun setAvatarStyle(style: AvatarStyle) = Unit
    }

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(buffers: BufferRepository, cm: ConnectionManager) =
        ChatListViewModel(
            bufferRepository = buffers,
            networkRepository = FakeNetworkRepository(),
            connectionManager = cm,
            settingsRepository = FakeSettingsRepository(),
            savedStateHandle = SavedStateHandle(),
        )

    private fun row(id: Long, type: BufferType, name: String) = ChatListRow(
        bufferId = id, networkId = 1, networkName = "libera",
        displayName = name, type = type, pinned = false, muted = false,
        lastMessageText = null, lastMessageSender = null, lastMessageTime = null,
        unreadCount = 0, mentionCount = 0,
    )

    @Test
    fun deleteChannel_partsThenDeletes_inThatOrder() = runTest {
        val ops = mutableListOf<String>()
        val buffers = object : FakeBufferRepository() {
            override suspend fun deleteBuffer(id: Long) { super.deleteBuffer(id); ops += "delete:$id" }
        }
        val vm = vm(buffers, FakeConnectionManager(ops))

        vm.deleteBuffer(row(7, BufferType.CHANNEL, "#kotlin"))
        runCurrent()

        // PART must precede the delete so the client can send PART before the buffer disappears.
        assertEquals(listOf("part:7", "delete:7"), ops)
        assertEquals(listOf(7L), buffers.deleted)
    }

    @Test
    fun deleteQuery_doesNotPart() = runTest {
        val ops = mutableListOf<String>()
        val buffers = object : FakeBufferRepository() {
            override suspend fun deleteBuffer(id: Long) { super.deleteBuffer(id); ops += "delete:$id" }
        }
        val vm = vm(buffers, FakeConnectionManager(ops))

        vm.deleteBuffer(row(9, BufferType.QUERY, "carol"))
        runCurrent()

        assertEquals(listOf("delete:9"), ops)   // no part for a DM
        assertEquals(listOf(9L), buffers.deleted)
    }

    @Test
    fun deleteServer_doesNotPart() = runTest {
        val ops = mutableListOf<String>()
        val buffers = object : FakeBufferRepository() {
            override suspend fun deleteBuffer(id: Long) { super.deleteBuffer(id); ops += "delete:$id" }
        }
        val vm = vm(buffers, FakeConnectionManager(ops))

        vm.deleteBuffer(row(3, BufferType.SERVER, "*"))
        runCurrent()

        assertEquals(listOf("delete:3"), ops)
    }
}
