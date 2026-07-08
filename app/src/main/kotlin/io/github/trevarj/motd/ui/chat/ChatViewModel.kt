package io.github.trevarj.motd.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.repo.LinkPreviewRepository
import io.github.trevarj.motd.data.repo.MessageRepository
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.ForegroundBufferTracker
import io.github.trevarj.motd.service.IrcEventSink
import io.github.trevarj.motd.service.TypingTracker
import io.github.trevarj.motd.ui.nav.ChatRoute
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * Single UI state for the chat screen (plans/07). `pagingFlow` is the cached message stream;
 * `replyTo`/`typingNicks`/`connState` drive the composer + header. `members` feeds autocomplete.
 */
data class ChatState(
    val buffer: BufferEntity? = null,
    val members: List<MemberEntity> = emptyList(),
    val typingNicks: List<String> = emptyList(),
    val replyTo: MessageEntity? = null,
    val connState: IrcClientState = IrcClientState.Disconnected,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val bufferRepository: BufferRepository,
    private val connectionManager: ConnectionManager,
    private val typingTracker: TypingTracker,
    private val foregroundBufferTracker: ForegroundBufferTracker,
    private val linkPreviewRepository: LinkPreviewRepository,
    private val draftStore: ComposerDraftStore,
    private val eventSink: IrcEventSink,
) : ViewModel() {

    private val route: ChatRoute = savedStateHandle.toRoute<ChatRoute>()
    val bufferId: Long = route.bufferId

    /** Cached Paging stream; collected once in the screen. */
    val messages: Flow<PagingData<MessageEntity>> =
        messageRepository.messages(bufferId).cachedIn(viewModelScope)

    private val replyTo = MutableStateFlow<MessageEntity?>(null)

    private val connState: StateFlow<IrcClientState> = bufferRepository.observeBuffer(bufferId)
        .combine(connectionManager.connectionStates) { buffer, states ->
            buffer?.let { states[it.networkId] } ?: IrcClientState.Disconnected
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IrcClientState.Disconnected)

    val state: StateFlow<ChatState> = combine(
        bufferRepository.observeBuffer(bufferId),
        bufferRepository.observeMembers(bufferId),
        typingTracker.typingNicks(bufferId),
        replyTo,
        connState,
    ) { buffer, members, typing, reply, conn ->
        ChatState(
            buffer = buffer,
            members = members,
            typingNicks = typing,
            replyTo = reply,
            connState = conn,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatState())

    /** Reactions for the currently visible msgids; the screen supplies the id set. */
    fun reactions(msgids: List<String>): Flow<List<ReactionEntity>> =
        messageRepository.reactions(bufferId, msgids)

    // --- lifecycle: foreground tracker + mark-read (plans/07) ---

    fun onResume() {
        foregroundBufferTracker.set(bufferId)
    }

    fun onPause() {
        foregroundBufferTracker.set(null)
    }

    /**
     * Mark read up to [time] (the newest visible/loaded server time). Called on resume with the
     * newest loaded message and whenever a new message lands while the list is at the bottom
     * (plans/07). [time] <= 0 is treated as "nothing to mark" and skipped.
     */
    fun markRead(time: Long) {
        if (time <= 0) return
        viewModelScope.launch { connectionManager.markRead(bufferId, time) }
    }

    // --- composer actions ---

    fun setReply(message: MessageEntity?) {
        replyTo.value = message
    }

    fun sendTyping(active: Boolean) = viewModelScope.launch {
        connectionManager.sendTyping(bufferId, if (active) "active" else "done")
    }

    fun react(msgid: String, emoji: String) = viewModelScope.launch {
        connectionManager.sendReact(bufferId, msgid, emoji)
    }

    fun retry(message: MessageEntity) = viewModelScope.launch {
        connectionManager.sendMessage(bufferId, message.text, message.replyToMsgid)
    }

    suspend fun linkPreview(url: String): LinkPreview? = linkPreviewRepository.preview(url)

    /**
     * Parse [raw] and execute the resulting [ChatCommand]. `onOpenBuffer` navigates for /msg /query.
     * Clears the reply and stops typing on a successful send.
     */
    fun submit(raw: String, onOpenBuffer: (Long) -> Unit) = viewModelScope.launch {
        val networkId = state.value.buffer?.networkId
        when (val cmd = parseCommand(raw)) {
            is ChatCommand.None -> Unit
            is ChatCommand.Message -> {
                connectionManager.sendMessage(bufferId, cmd.text, replyTo.value?.msgid)
                replyTo.value = null
                connectionManager.sendTyping(bufferId, "done")
            }
            is ChatCommand.Join -> networkId?.let { connectionManager.joinChannel(it, cmd.channel) }
            is ChatCommand.Part -> connectionManager.partChannel(bufferId, cmd.reason)
            is ChatCommand.Msg -> networkId?.let { nid ->
                val target = connectionManager.ensureQueryBuffer(nid, cmd.nick)
                connectionManager.sendMessage(target, cmd.text)
                onOpenBuffer(target)
            }
            is ChatCommand.Query -> networkId?.let { nid ->
                onOpenBuffer(connectionManager.ensureQueryBuffer(nid, cmd.nick))
            }
            is ChatCommand.Nick -> networkId?.let { nid ->
                connectionManager.clientFor(nid)?.send(IrcMessage(command = "NICK", params = listOf(cmd.nick)))
            }
            is ChatCommand.Topic -> networkId?.let { nid ->
                val channel = state.value.buffer?.name ?: return@launch
                connectionManager.clientFor(nid)
                    ?.send(IrcMessage(command = "TOPIC", params = listOf(channel, cmd.topic)))
            }
            is ChatCommand.RawLine -> networkId?.let { nid ->
                connectionManager.clientFor(nid)?.send(IrcMessage.parse(cmd.line))
            }
        }
    }

    // --- composer prefill (mention → draft, plans/11 §A) ---

    /** Consume-once composer prefill queued by ChannelInfo before popping back; null when none. */
    fun consumePrefill(): String? = draftStore.consume(bufferId)

    // --- search deep-jump (plans/11 §C) ---

    private val jumpMsgid: String? = route.jumpToMsgid
    private val jumpTime: Long = route.jumpToTime

    /**
     * CHATHISTORY AROUND fetch used by [ChatJumpResolver] when a msgid target is not yet local:
     * requires a live client with `draft/chathistory`, fetches ~100 messages around [timeMs], and
     * feeds them through the sole IRC→Room writer. Returns true when events were persisted.
     */
    private val resolver = ChatJumpResolver(messageRepository) { name, timeMs, limit ->
        val networkId = state.value.buffer?.networkId ?: return@ChatJumpResolver false
        val client = connectionManager.clientFor(networkId) ?: return@ChatJumpResolver false
        if (!client.hasCap("draft/chathistory")) return@ChatJumpResolver false
        val result = runCatching {
            client.chathistory(
                ChatHistoryRequest(
                    subcommand = ChatHistoryRequest.Subcommand.AROUND,
                    target = name,
                    bound1 = "timestamp=${Instant.ofEpochMilli(timeMs)}",
                    limit = limit,
                ),
            )
        }.getOrNull() ?: return@ChatJumpResolver false
        if (result.events.isEmpty()) return@ChatJumpResolver false
        eventSink.process(networkId, IrcEvent.HistoryBatch(name, result.events))
        true
    }

    private val _jumpTarget = MutableStateFlow<ChatJumpResolver.Result.Target?>(null)
    /** Resolved jump target (index + optional highlight msgid); null when nothing to jump to. */
    val jumpTarget: StateFlow<ChatJumpResolver.Result.Target?> = _jumpTarget.asStateFlow()

    private val _jumpFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits when a jump could not be resolved (cap miss / not loaded) → snackbar. */
    val jumpFailed: SharedFlow<Unit> = _jumpFailed.asSharedFlow()

    init {
        if (jumpTime > 0 && savedStateHandle.get<Boolean>(JUMP_CONSUMED_KEY) != true) {
            savedStateHandle[JUMP_CONSUMED_KEY] = true
            resolveJump()
        }
    }

    private fun resolveJump() = viewModelScope.launch {
        // The buffer name (chathistory target) may not be in `state` yet on first composition;
        // read it directly from the repo so the AROUND fallback has a target.
        val name = bufferRepository.observeBuffer(bufferId).firstOrNull()?.name
        when (val r = resolver.resolve(bufferId, jumpMsgid, jumpTime, name)) {
            is ChatJumpResolver.Result.Target -> _jumpTarget.value = r
            ChatJumpResolver.Result.NotFound -> _jumpFailed.tryEmit(Unit)
        }
    }

    /** Screen calls this after it has scrolled to (or given up on) the current target. */
    fun onJumpHandled() {
        _jumpTarget.value = null
    }

    /** Re-resolve the same target once when a live message shifted indices mid-jump. */
    fun reresolveJumpOnce() = viewModelScope.launch {
        val name = state.value.buffer?.name
        when (val r = resolver.resolve(bufferId, jumpMsgid, jumpTime, name)) {
            is ChatJumpResolver.Result.Target -> _jumpTarget.value = r
            ChatJumpResolver.Result.NotFound -> {
                _jumpTarget.value = null
                _jumpFailed.tryEmit(Unit)
            }
        }
    }

    /**
     * Isupport-normalized nick folding for autocomplete; lowercase fallback when no live client.
     */
    fun nickNormalizer(): (String) -> String {
        val nid = state.value.buffer?.networkId
        val isupport = nid?.let { connectionManager.clientFor(it)?.isupport }
        return isupport?.let { { name: String -> it.normalize(name) } } ?: { it.lowercase() }
    }

    private companion object {
        // Survives config changes so a jump resolves exactly once per navigation.
        const val JUMP_CONSUMED_KEY = "jump_consumed"
    }
}
