package io.github.trevarj.motd.di

import androidx.paging.ExperimentalPagingApi
import androidx.paging.PagingData
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.prefs.PushKeys
import io.github.trevarj.motd.data.prefs.PushPrefs
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.ChatHistoryMediatorFactory
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.repo.LinkPreviewRepository
import io.github.trevarj.motd.data.repo.MessageRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.data.repo.SearchRepository
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.ForegroundBufferTracker
import io.github.trevarj.motd.service.IrcEventSink
import io.github.trevarj.motd.service.TypingTracker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

// All impls in this file are temporary WP1 stubs so the app compiles and lints green with a
// complete Hilt object graph. WP4/WP5/WP9 provide real implementations; WP10 rebinds and
// deletes these @Deprecated stubs.

@Deprecated("WP10 removes")
@Singleton
internal class StubNetworkRepository @Inject constructor() : NetworkRepository {
    override fun observeNetworks(): Flow<List<NetworkEntity>> = flowOf(emptyList())
    override suspend fun addNetwork(n: NetworkEntity): Long = 0
    override suspend fun updateNetwork(n: NetworkEntity) {}
    override suspend fun deleteNetwork(id: Long) {}
}

@Deprecated("WP10 removes")
@Singleton
internal class StubBufferRepository @Inject constructor() : BufferRepository {
    override fun observeChatList(): Flow<List<ChatListRow>> = flowOf(emptyList())
    override fun observeBuffer(id: Long): Flow<BufferEntity?> = flowOf(null)
    override fun observeMembers(bufferId: Long): Flow<List<MemberEntity>> = flowOf(emptyList())
    override suspend fun setPinned(id: Long, pinned: Boolean) {}
    override suspend fun setMuted(id: Long, muted: Boolean) {}
}

@Deprecated("WP10 removes")
@Singleton
internal class StubMessageRepository @Inject constructor() : MessageRepository {
    override fun messages(bufferId: Long): Flow<PagingData<MessageEntity>> = flowOf(PagingData.empty())
    override fun reactions(bufferId: Long, msgids: List<String>): Flow<List<ReactionEntity>> = flowOf(emptyList())
}

@Deprecated("WP10 removes")
@Singleton
internal class StubSearchRepository @Inject constructor() : SearchRepository {
    override fun search(query: String, bufferId: Long?): Flow<List<SearchHit>> = flowOf(emptyList())
}

@Deprecated("WP10 removes")
@Singleton
internal class StubLinkPreviewRepository @Inject constructor() : LinkPreviewRepository {
    override suspend fun preview(url: String): LinkPreview? = null
}

@Deprecated("WP10 removes")
@Singleton
internal class StubSettingsRepository @Inject constructor() : SettingsRepository {
    override val settings: Flow<Settings> = flowOf(Settings())
    override suspend fun setThemeMode(m: ThemeMode) {}
    override suspend fun setDynamicColor(enabled: Boolean) {}
    override suspend fun setDeliveryMode(m: DeliveryMode) {}
}

@Deprecated("WP10 removes")
@Singleton
internal class StubPushPrefs @Inject constructor() : PushPrefs {
    private var endpoint: String? = null
    private var keys: PushKeys? = null
    override suspend fun endpoint(): String? = endpoint
    override suspend fun setEndpoint(endpoint: String?) { this.endpoint = endpoint }
    override suspend fun keys(): PushKeys? = keys
    override suspend fun setKeys(keys: PushKeys) { this.keys = keys }
}

@Deprecated("WP10 removes")
@Singleton
internal class StubChatHistoryMediatorFactory @Inject constructor() : ChatHistoryMediatorFactory {
    @OptIn(ExperimentalPagingApi::class)
    override fun create(bufferId: Long): RemoteMediator<Int, MessageEntity> =
        object : RemoteMediator<Int, MessageEntity>() {
            override suspend fun load(
                loadType: androidx.paging.LoadType,
                state: PagingState<Int, MessageEntity>,
            ): MediatorResult = MediatorResult.Success(endOfPaginationReached = true)
        }
}

@Deprecated("WP10 removes")
@Singleton
internal class StubTypingTracker @Inject constructor() : TypingTracker {
    override fun typingNicks(bufferId: Long): StateFlow<List<String>> = MutableStateFlow(emptyList())
}

@Deprecated("WP10 removes")
@Singleton
internal class StubForegroundBufferTracker @Inject constructor() : ForegroundBufferTracker {
    private val _foreground = MutableStateFlow<Long?>(null)
    override val foregroundBufferId: StateFlow<Long?> = _foreground
    override fun set(bufferId: Long?) { _foreground.value = bufferId }
}

@Deprecated("WP10 removes")
@Singleton
internal class StubConnectionManager @Inject constructor() : ConnectionManager {
    override val connectionStates: StateFlow<Map<Long, IrcClientState>> = MutableStateFlow(emptyMap())
    override fun clientFor(networkId: Long): IrcClient? = null
    override suspend fun startAll() {}
    override suspend fun stopAll() {}
    override suspend fun connect(networkId: Long) {}
    override suspend fun disconnect(networkId: Long) {}
    override suspend fun sendMessage(bufferId: Long, text: String, replyToMsgid: String?) {}
    override suspend fun sendTyping(bufferId: Long, state: String) {}
    override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) {}
    override suspend fun joinChannel(networkId: Long, channel: String) {}
    override suspend fun partChannel(bufferId: Long) {}
    override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long = 0
    override suspend fun markRead(bufferId: Long, upToTime: Long) {}
}

@Deprecated("WP10 removes")
@Singleton
internal class StubIrcEventSink @Inject constructor() : IrcEventSink {
    override suspend fun process(networkId: Long, event: IrcEvent) {}
}
