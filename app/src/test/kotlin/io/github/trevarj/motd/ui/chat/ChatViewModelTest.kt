package io.github.trevarj.motd.ui.chat

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.UserDao
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.AppearancePrefs
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.ChatWallpaper
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.ReplyConfig
import io.github.trevarj.motd.data.prefs.ReplyPrefs
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.repo.LinkPreviewRepository
import io.github.trevarj.motd.data.repo.MessageRepository
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.data.visibility.MessageVisibilityReader
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.ForegroundBufferTracker
import io.github.trevarj.motd.service.HistoryResyncCoordinator
import io.github.trevarj.motd.service.IrcEventSink
import io.github.trevarj.motd.service.PresenceKey
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.service.ReadMarkerSnapshotter
import io.github.trevarj.motd.service.RosterLoadState
import io.github.trevarj.motd.service.TypingTracker
import io.github.trevarj.motd.ui.nav.ChatRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: MotdDatabase
    private lateinit var network: NetworkEntity
    private lateinit var channel: BufferEntity
    private lateinit var query: BufferEntity
    private lateinit var processor: EventProcessor

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        network = NetworkEntity(
            name = "test",
            role = NetworkRole.DIRECT,
            host = "irc.example",
            port = 6697,
            nick = "me",
            username = "me",
            realname = "Me",
        ).let { it.copy(id = db.networkDao().insert(it)) }
        channel = BufferEntity(
            networkId = network.id,
            name = "#room",
            displayName = "#room",
            type = BufferType.CHANNEL,
        ).let { it.copy(id = db.bufferDao().insert(it)) }
        query = BufferEntity(
            networkId = network.id,
            name = "alice",
            displayName = "alice",
            type = BufferType.QUERY,
        ).let { it.copy(id = db.bufferDao().insert(it)) }
        processor = EventProcessor(db, TypingTrackerImpl(), io.github.trevarj.motd.data.sync.MessageNotifier.Noop)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `message submission sends reply metadata and stops typing`() = runTest {
        val manager = FakeConnectionManager(network.id, IrcClientState.Ready("me", emptySet(), emptyMap()))
        val vm = viewModel(channel, manager)
        vm.state.first { it.buffer != null }
        val parent = message(channel.id, "parent", msgid = "parent-1", sender = "alice")
        vm.setReply(parent)
        vm.state.first { it.replyTo?.msgid == "parent-1" }

        vm.submit("answer", {}, {})
        advanceUntilIdle()

        assertEquals(listOf(SentMessage(channel.id, "answer", "parent-1")), manager.messages)
        assertEquals(listOf(channel.id to "done"), manager.typing)
        assertTrue(vm.state.value.replyTo == null)
    }

    @Test
    fun `msg submission creates query target and opens it`() = runTest {
        val manager = FakeConnectionManager(network.id)
        val vm = viewModel(channel, manager)
        vm.state.first { it.buffer != null }
        val opened = mutableListOf<Long>()

        vm.submit("/msg alice hello there", opened::add)
        advanceUntilIdle()

        assertEquals(listOf(SentMessage(query.id, "hello there", null)), manager.messages)
        assertEquals(listOf(query.id), opened)
    }

    @Test
    fun `moderation commands are ignored outside channel buffers`() = runTest {
        val manager = FakeConnectionManager(network.id)
        val vm = viewModel(query, manager)
        vm.state.first { it.buffer != null }

        vm.submit("/kick alice", {}, {})
        vm.submit("/ban alice", {}, {})
        advanceUntilIdle()

        assertTrue(manager.sentLines.isEmpty())
    }

    @Test
    fun `server buffer invalid raw command surfaces snackbar without sending`() = runTest {
        val server = BufferEntity(
            networkId = network.id,
            name = "*",
            displayName = "test",
            type = BufferType.SERVER,
        ).let { it.copy(id = db.bufferDao().insert(it)) }
        val manager = FakeConnectionManager(network.id)
        val vm = viewModel(server, manager)
        vm.state.first { it.buffer != null }

        vm.submit("/", {}, {})
        advanceUntilIdle()

        assertEquals("invalid", vm.snackbar.value)
        assertTrue(manager.sentLines.isEmpty())
    }

    private fun viewModel(buffer: BufferEntity, manager: FakeConnectionManager): ChatViewModel {
        val settings = FakeSettingsRepository()
        val eventSink: IrcEventSink = processor
        val history = HistoryResyncCoordinator(
            db = db,
            processor = processor,
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        )
        return ChatViewModel(
            savedStateHandle = SavedStateHandle(mapOf("bufferId" to buffer.id)),
            messageRepository = FakeMessageRepository(),
            bufferRepository = FakeBufferRepository(buffer),
            connectionManager = manager,
            typingTracker = FakeTypingTracker(),
            foregroundBufferTracker = FakeForegroundBufferTracker(),
            linkPreviewRepository = object : LinkPreviewRepository {
                override suspend fun preview(url: String): LinkPreview? = null
            },
            draftStore = ComposerDraftStore(),
            scrollPositionStore = ChatScrollPositionStore(),
            eventSink = eventSink,
            settingsRepository = settings,
            replyPrefs = FakeReplyPrefs(),
            visibilityReader = MessageVisibilityReader(db),
            historyResyncCoordinator = history,
            userDao = db.userDao(),
            contentPreviewPrefs = FakeContentPreviewPrefs(),
            appearancePrefs = FakeAppearancePrefs(),
        )
    }

    private fun message(
        bufferId: Long,
        text: String,
        msgid: String,
        sender: String,
    ) = MessageEntity(
        bufferId = bufferId,
        msgid = msgid,
        serverTime = 1,
        sender = sender,
        kind = MessageKind.PRIVMSG,
        text = text,
        dedupKey = msgid,
    )

    private data class SentMessage(val bufferId: Long, val text: String, val replyTo: String?)

    private class FakeConnectionManager(
        networkId: Long,
        state: IrcClientState = IrcClientState.Ready("me", emptySet(), emptyMap()),
    ) : ConnectionManager {
        override val connectionStates = MutableStateFlow(mapOf(networkId to state))
        override val presenceStates: StateFlow<Map<PresenceKey, PresenceState>> =
            MutableStateFlow(emptyMap())
        override val rosterStates: StateFlow<Map<Long, RosterLoadState>> = MutableStateFlow(emptyMap())
        override val certPrompts = MutableStateFlow<List<CertPrompt>>(emptyList())
        val messages = mutableListOf<SentMessage>()
        val typing = mutableListOf<Pair<Long, String>>()
        val sentLines = mutableListOf<String>()

        override fun clientFor(networkId: Long): IrcClient? = null
        override suspend fun startAll() = Unit
        override suspend fun stopAll() = Unit
        override suspend fun connect(networkId: Long) = Unit
        override suspend fun disconnect(networkId: Long) = Unit
        override suspend fun reconnectStale() = Unit
        override suspend fun sendMessage(bufferId: Long, text: String, replyToMsgid: String?) {
            messages += SentMessage(bufferId, text, replyToMsgid)
        }
        override suspend fun sendTyping(bufferId: Long, state: String) { typing += bufferId to state }
        override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) = Unit
        override suspend fun joinChannel(networkId: Long, channel: String) = Unit
        override suspend fun partChannel(bufferId: Long, reason: String?) = Unit
        override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long = 2L
        override suspend fun ensureServerBuffer(networkId: Long): Long = 3L
        override suspend fun markRead(bufferId: Long, upToTime: Long) = Unit
        override suspend fun evaluatePushMode() = Unit
        override suspend fun trustCert(prompt: CertPrompt) = Unit
        override fun dismissCertPrompt(prompt: CertPrompt) = Unit
        override suspend fun requestMembers(bufferId: Long, force: Boolean) = Unit
        override suspend fun acceptInvite(messageId: Long) = Unit
        override suspend fun dismissInvite(messageId: Long) = Unit
    }

    private class FakeBufferRepository(private val current: BufferEntity) : BufferRepository {
        override fun observeChatList(): Flow<List<ChatListRow>> = flowOf(emptyList())
        override fun observeBuffer(id: Long): Flow<BufferEntity?> = flowOf(current.takeIf { it.id == id })
        override fun observeMembers(bufferId: Long): Flow<List<MemberEntity>> = flowOf(emptyList())
        override suspend fun setPinned(id: Long, pinned: Boolean) = Unit
        override suspend fun setMuted(id: Long, muted: Boolean) = Unit
        override suspend fun deleteBuffer(id: Long) = Unit
    }

    private class FakeMessageRepository : MessageRepository {
        override fun messages(bufferId: Long): Flow<PagingData<MessageEntity>> = flowOf(PagingData.empty())
        override fun reactions(bufferId: Long, msgids: List<String>): Flow<List<ReactionEntity>> = flowOf(emptyList())
        override fun reactionsForBuffer(bufferId: Long): Flow<List<ReactionEntity>> = flowOf(emptyList())
        override suspend fun byMsgid(bufferId: Long, msgid: String): MessageEntity? = null
        override fun observeByMsgid(bufferId: Long, msgid: String): Flow<MessageEntity?> = flowOf(null)
        override suspend fun awaitMsgid(id: Long, timeoutMs: Long): String? = null
        override suspend fun countNewerThan(bufferId: Long, serverTime: Long, id: Long): Int = 0
        override suspend fun firstUnreadOtherTime(bufferId: Long, after: Long): Long? = null
        override suspend fun deleteMessage(id: Long) = Unit
    }

    private class FakeTypingTracker : TypingTracker {
        override fun typingNicks(bufferId: Long): StateFlow<List<String>> = MutableStateFlow(emptyList())
    }

    private class FakeForegroundBufferTracker : ForegroundBufferTracker {
        override val foregroundBufferId = MutableStateFlow<Long?>(null)
        override fun set(bufferId: Long?) { foregroundBufferId.value = bufferId }
    }

    private class FakeSettingsRepository : SettingsRepository {
        override val settings = MutableStateFlow(Settings())
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
        override suspend fun setChatWallpaper(w: ChatWallpaper) = Unit
        override suspend fun setShowComposerEmoji(show: Boolean) = Unit
    }

    private class FakeReplyPrefs : ReplyPrefs {
        override val config = MutableStateFlow(ReplyConfig())
        override suspend fun setVisibleChannelPrefix(enabled: Boolean) = Unit
    }

    private class FakeContentPreviewPrefs : ContentPreviewPrefs {
        override val config = MutableStateFlow(ContentPreviewConfig())
        override suspend fun setShowImages(show: Boolean) = Unit
        override suspend fun setShowLinkPreviews(show: Boolean) = Unit
    }

    private class FakeAppearancePrefs : AppearancePrefs {
        override val config = MutableStateFlow(AppearanceConfig())
        override suspend fun setTheme(theme: ColorThemePreset) = Unit
        override suspend fun setWallpaper(selection: io.github.trevarj.motd.data.prefs.WallpaperSelection) = Unit
        override suspend fun setUiFontScale(percent: Int) = Unit
        override suspend fun setConversationFontScale(percent: Int) = Unit
    }
}
