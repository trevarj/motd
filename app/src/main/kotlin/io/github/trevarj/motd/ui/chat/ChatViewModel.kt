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
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.ForegroundBufferTracker
import io.github.trevarj.motd.service.TypingTracker
import io.github.trevarj.motd.ui.nav.ChatRoute
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val bufferRepository: BufferRepository,
    private val connectionManager: ConnectionManager,
    private val typingTracker: TypingTracker,
    private val foregroundBufferTracker: ForegroundBufferTracker,
    private val linkPreviewRepository: LinkPreviewRepository,
) : ViewModel() {

    val bufferId: Long = savedStateHandle.toRoute<ChatRoute>().bufferId

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
            is ChatCommand.Part -> connectionManager.partChannel(bufferId)
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

    /**
     * Isupport-normalized nick folding for autocomplete; lowercase fallback when no live client.
     */
    fun nickNormalizer(): (String) -> String {
        val nid = state.value.buffer?.networkId
        val isupport = nid?.let { connectionManager.clientFor(it)?.isupport }
        return isupport?.let { { name: String -> it.normalize(name) } } ?: { it.lowercase() }
    }
}
