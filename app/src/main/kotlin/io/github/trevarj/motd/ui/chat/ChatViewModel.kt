package io.github.trevarj.motd.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.repo.LinkPreviewRepository
import io.github.trevarj.motd.data.repo.MessageRepository
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.trevarj.motd.ui.components.ReactionChip
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

/**
 * Wire text for resending a failed row. An ACTION is stored with its `/me ` prefix stripped, so
 * re-prefix it; the manager rewrites `/me ` back into a CTCP ACTION. Non-ACTION kinds resend
 * verbatim (plans/15 #10).
 */
fun resendText(kind: io.github.trevarj.motd.data.db.MessageKind, text: String): String =
    if (kind == io.github.trevarj.motd.data.db.MessageKind.ACTION) "/me $text" else text

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
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val route: ChatRoute = savedStateHandle.toRoute<ChatRoute>()
    val bufferId: Long = route.bufferId

    // Behavioral filter (JPQ visibility + fools HIDE). Distinct so unrelated settings edits don't
    // re-emit the paging stream (plans/13 §2.5). ChatState is untouched — the screen collects
    // [settings] separately (mirrors R1 keeping the 5-ary combine stable).
    private val filterSpec = settingsRepository.settings
        .map { MessageFilterSpec(it.showJoinPartQuit, it.fools, it.foolsMode) }
        .distinctUntilChanged()

    /** Cached Paging stream filtered per [MessageFilterSpec]; collected once in the screen. */
    val messages: Flow<PagingData<MessageEntity>> =
        messageRepository.messages(bufferId)
            .combine(filterSpec) { paging, spec -> paging.filter { keepMessage(it, spec) } }
            .cachedIn(viewModelScope)

    /** Full settings for the timeline (friends/fools/foolsMode/nick styling); collected in the screen. */
    val settings: StateFlow<Settings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

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

    // --- reactions aggregation (plans/15 #5, #18) ---

    /** Msgids currently visible in the paging window; the screen keeps this current. */
    private val visibleMsgids = MutableStateFlow<List<String>>(emptyList())

    fun setVisibleMsgids(ids: List<String>) {
        if (visibleMsgids.value != ids) visibleMsgids.value = ids
    }

    /**
     * Reaction chips keyed by msgid, aggregated in the VM so the value survives across message
     * arrivals (no blank frame from an emptyList re-seed) and picks up echo-confirm msgid swaps as
     * soon as [visibleMsgids] changes. Buffer-scoped reactions avoid the SQLite IN(...) overflow
     * (plans/15 #5); we filter to the visible window here.
     */
    val reactionChips: StateFlow<Map<String, List<ReactionChip>>> = combine(
        messageRepository.reactionsForBuffer(bufferId),
        visibleMsgids,
        connState,
    ) { all, visible, conn ->
        val visibleSet = visible.toHashSet()
        val relevant = all.filter { it.targetMsgid in visibleSet }
        val myNick = (conn as? IrcClientState.Ready)?.nick
        aggregateReactions(relevant, myNick, nickNormalizer())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // --- read marker snapshot (plans/15 #2) ---

    // Frozen on buffer entry so the "— New messages —" divider and the FAB unread badge keep a
    // stable boundary instead of flashing/vanishing as markRead advances the live marker.
    private val _readMarkerSnapshot = MutableStateFlow<Long?>(null)
    val readMarkerSnapshot: StateFlow<Long?> = _readMarkerSnapshot.asStateFlow()

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

    /**
     * Retry a failed message: drop the old failed row first (no permanent duplicate), then resend.
     * An ACTION is stored with its display text stripped of the `/me ` prefix, so re-prefix it to
     * resend as an ACTION rather than a plain PRIVMSG (plans/15 #10).
     */
    fun retry(message: MessageEntity) = viewModelScope.launch {
        messageRepository.deleteMessage(message.id)
        connectionManager.sendMessage(bufferId, resendText(message.kind, message.text), message.replyToMsgid)
    }

    /** Delete a failed local row without resending (action-sheet delete affordance, plans/15 #10). */
    fun deleteFailed(message: MessageEntity) = viewModelScope.launch {
        messageRepository.deleteMessage(message.id)
    }

    suspend fun linkPreview(url: String): LinkPreview? = linkPreviewRepository.preview(url)

    /** Transient one-shot messages surfaced as a snackbar by the screen (plans/16 §5.6). */
    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar: StateFlow<String?> = _snackbar.asStateFlow()

    fun consumeSnackbar() { _snackbar.value = null }

    /**
     * Parse [raw] and execute the resulting [ChatCommand]. `onOpenBuffer` navigates for /msg /query;
     * `onOpenChannelList` navigates for /list. Clears the reply and stops typing on a normal send.
     *
     * A SERVER buffer is a raw-send surface (plans/16 §5.6): every submission is sent as a raw IRC
     * line to the network (one leading `/` stripped) — [parseCommand] is bypassed, and a PRIVMSG to
     * `"*"` is never sent.
     */
    fun submit(raw: String, onOpenBuffer: (Long) -> Unit, onOpenChannelList: (Long) -> Unit = {}) = viewModelScope.launch {
        val networkId = state.value.buffer?.networkId
        if (state.value.buffer?.type == BufferType.SERVER) {
            submitRawLine(networkId, raw)
            return@launch
        }
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
            // `/away [msg]` — confirmations (305/306) land in the SERVER buffer via §5.6.3.
            is ChatCommand.Away -> networkId?.let { nid ->
                connectionManager.clientFor(nid)
                    ?.send(IrcMessage(command = "AWAY", params = listOfNotNull(cmd.message)))
            }
            is ChatCommand.Whois -> openNickSheet(cmd.nick)
            is ChatCommand.ChannelList -> networkId?.let(onOpenChannelList)
            // Moderation guarded to CHANNEL buffers; a no-op elsewhere.
            is ChatCommand.Kick -> if (state.value.buffer?.type == BufferType.CHANNEL) kick(cmd.nick, cmd.reason)
            is ChatCommand.Ban -> if (state.value.buffer?.type == BufferType.CHANNEL) ban(cmd.nick)
            is ChatCommand.RawLine -> networkId?.let { nid ->
                connectionManager.clientFor(nid)?.send(IrcMessage.parse(cmd.line))
            }
        }
    }

    /** Raw-send for the SERVER buffer: strip one leading `/`, parse, send. Parse failure snackbars. */
    private suspend fun submitRawLine(networkId: Long?, raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        val nid = networkId ?: return
        val line = if (trimmed.startsWith("/")) trimmed.substring(1) else trimmed
        val msg = runCatching { IrcMessage.parse(line) }.getOrNull()
        if (msg == null || msg.command.isBlank()) {
            _snackbar.value = "invalid" // sentinel; the screen maps to chat_server_invalid_command
            return
        }
        connectionManager.clientFor(nid)?.send(msg)
        replyTo.value = null
    }

    // --- nick sheet + whois (plans/16 §5.8) ---

    private val _nickSheet = MutableStateFlow<NickSheetState?>(null)
    val nickSheet: StateFlow<NickSheetState?> = _nickSheet.asStateFlow()

    /**
     * Open the nick sheet for [nick]. With `labeled-response` we WHOIS via a labeled request, parse
     * the numerics, and fold the details in when they land (30s label timeout, guarded); otherwise
     * a plain WHOIS is sent and the numerics surface in the server buffer (§5.6.3) while the sheet
     * shows "Details in server messages". Actions render immediately regardless.
     */
    fun openNickSheet(nick: String) {
        _nickSheet.value = NickSheetState(nick = nick)
        val networkId = state.value.buffer?.networkId ?: return
        val client = connectionManager.clientFor(networkId) ?: return
        val whoisMsg = IrcMessage(command = "WHOIS", params = listOf(nick))
        if (client.hasCap("labeled-response")) {
            viewModelScope.launch {
                val lines = runCatching { client.sendLabeled(whoisMsg) }.getOrNull().orEmpty()
                val info = parseWhois(lines)
                // Only fold in if the sheet is still open for this nick.
                if (info != null && _nickSheet.value?.nick == nick) {
                    _nickSheet.value = NickSheetState(nick = nick, whois = info)
                }
            }
        } else {
            viewModelScope.launch { client.send(whoisMsg) }
        }
    }

    fun dismissNickSheet() { _nickSheet.value = null }

    // --- moderation executors (plans/16 §5.8), CHANNEL buffers only ---

    /** MODE <channel> +o/-o/+v/-v <nick>. */
    fun setMemberMode(nick: String, mode: Char, grant: Boolean) = viewModelScope.launch {
        val nid = state.value.buffer?.networkId ?: return@launch
        val channel = state.value.buffer?.name ?: return@launch
        val flag = (if (grant) "+" else "-") + mode
        connectionManager.clientFor(nid)?.send(IrcMessage(command = "MODE", params = listOf(channel, flag, nick)))
    }

    /** KICK <channel> <nick> [:reason]. */
    fun kick(nick: String, reason: String?) = viewModelScope.launch {
        val nid = state.value.buffer?.networkId ?: return@launch
        val channel = state.value.buffer?.name ?: return@launch
        val params = if (reason.isNullOrBlank()) listOf(channel, nick) else listOf(channel, nick, reason)
        connectionManager.clientFor(nid)?.send(IrcMessage(command = "KICK", params = params))
    }

    /** MODE <channel> +b <banMask(nick)>. */
    fun ban(nick: String) = viewModelScope.launch {
        val nid = state.value.buffer?.networkId ?: return@launch
        val channel = state.value.buffer?.name ?: return@launch
        connectionManager.clientFor(nid)
            ?.send(IrcMessage(command = "MODE", params = listOf(channel, "+b", io.github.trevarj.motd.ui.channelinfo.banMask(nick))))
    }

    /** Toggle [nick]'s friend/fool membership (reuses SettingsRepository semantics). */
    fun toggleFriend(nick: String) = viewModelScope.launch {
        val settings = settingsRepository.settings.firstOrNull() ?: return@launch
        settingsRepository.setFriend(nick, io.github.trevarj.motd.data.prefs.normalizeNick(nick) !in settings.friends)
    }

    fun toggleFool(nick: String) = viewModelScope.launch {
        val settings = settingsRepository.settings.firstOrNull() ?: return@launch
        settingsRepository.setFool(nick, io.github.trevarj.motd.data.prefs.normalizeNick(nick) !in settings.fools)
    }

    /**
     * True when the viewer holds op in the current CHANNEL buffer (drives moderation visibility,
     * Confirmed #7). Own prefixes come from the members list; prefix order from ISUPPORT.
     */
    fun canModerate(): Boolean {
        val buffer = state.value.buffer ?: return false
        if (buffer.type != BufferType.CHANNEL) return false
        val myNick = (connState.value as? IrcClientState.Ready)?.nick ?: return false
        val normalize = nickNormalizer()
        val me = state.value.members.firstOrNull { normalize(it.nick) == normalize(myNick) } ?: return false
        val order = buffer.networkId.let { connectionManager.clientFor(it) }
            ?.let { io.github.trevarj.motd.ui.channelinfo.prefixOrderFrom(it.isupport.prefixModes) }
            ?: io.github.trevarj.motd.ui.channelinfo.DEFAULT_PREFIX_ORDER
        return io.github.trevarj.motd.ui.channelinfo.canModerate(me.prefixes, order)
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

    // Nullable-event StateFlow instead of a replay-less SharedFlow so a NotFound resolved in init
    // (before the screen subscribes) is not dropped; the UI clears it via [onJumpFailedShown]
    // (plans/15 #13).
    private val _jumpFailed = MutableStateFlow(false)
    val jumpFailed: StateFlow<Boolean> = _jumpFailed.asStateFlow()

    // Re-resolve is allowed exactly once per navigation; a second index shift falls through to the
    // not-loaded snackbar rather than looping (plans/15 #12).
    private var reresolveUsed = false

    init {
        if (jumpTime > 0 && savedStateHandle.get<Boolean>(JUMP_CONSUMED_KEY) != true) {
            savedStateHandle[JUMP_CONSUMED_KEY] = true
            resolveJump()
        }
        // Freeze the read-marker once, on the first buffer emission, so the unread divider/badge
        // stay put instead of collapsing as markRead advances the live marker (plans/15 #2).
        viewModelScope.launch {
            _readMarkerSnapshot.value = bufferRepository.observeBuffer(bufferId).firstOrNull()?.readMarkerTime
        }
    }

    private fun resolveJump() = viewModelScope.launch {
        // The buffer name (chathistory target) may not be in `state` yet on first composition;
        // read it directly from the repo so the AROUND fallback has a target.
        val name = bufferRepository.observeBuffer(bufferId).firstOrNull()?.name
        publishResolve(name)
    }

    private suspend fun publishResolve(name: String?) {
        when (val r = resolver.resolve(bufferId, jumpMsgid, jumpTime, name)) {
            is ChatJumpResolver.Result.Target -> {
                // Force a distinct emission so the screen's LaunchedEffect(jumpTarget) always
                // re-runs, even when the re-resolved index equals the previous one (plans/15 #12).
                _jumpTarget.value = null
                _jumpTarget.value = r
            }
            ChatJumpResolver.Result.NotFound -> {
                _jumpTarget.value = null
                _jumpFailed.value = true
            }
        }
    }

    /** Screen calls this after it has scrolled to (or given up on) the current target. */
    fun onJumpHandled() {
        _jumpTarget.value = null
    }

    /** Screen calls this after showing the not-loaded snackbar so it does not re-fire. */
    fun onJumpFailedShown() {
        _jumpFailed.value = false
    }

    /**
     * Re-resolve the same target once when a live message shifted indices mid-jump. The single-shot
     * guard means the screen can always call [onJumpHandled] after it; a repeat request just clears
     * the target so the not-loaded path takes over (plans/15 #12).
     */
    fun reresolveJumpOnce() = viewModelScope.launch {
        if (reresolveUsed) {
            _jumpTarget.value = null
            _jumpFailed.value = true
            return@launch
        }
        reresolveUsed = true
        publishResolve(state.value.buffer?.name)
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
