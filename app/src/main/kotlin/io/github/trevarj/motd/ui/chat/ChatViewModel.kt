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
import io.github.trevarj.motd.data.db.effectiveReadFloorTime
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.db.UserDao
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.repo.LinkPreviewRepository
import io.github.trevarj.motd.data.repo.MessageRepository
import io.github.trevarj.motd.data.prefs.normalizeNick
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.AppearancePrefs
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.data.prefs.ReplyConfig
import io.github.trevarj.motd.data.prefs.ReplyPrefs
import io.github.trevarj.motd.data.visibility.MessageVisibilityReader
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.diagnostics.AutoFollowTrace
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.canSendReactionTags
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.RosterLoadState
import io.github.trevarj.motd.service.PresenceKey
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.service.ForegroundBufferTracker
import io.github.trevarj.motd.service.HistoryResyncController
import io.github.trevarj.motd.service.HistoryRefreshRange
import io.github.trevarj.motd.service.HistoryResyncState
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.trevarj.motd.ui.components.ReactionChip
import io.github.trevarj.motd.ui.components.ReplyPreviewData
import javax.inject.Inject

private const val MAX_REPLY_PREVIEW_CACHE = 128
private const val MAX_REACTION_WINDOW_MSGIDS = 500

/**
 * Single UI state for the chat screen (plans/07). `pagingFlow` is the cached message stream;
 * `replyTo`/`typingNicks`/`connState` drive the composer + header. `members` feeds autocomplete.
 */
data class ChatState(
    val buffer: BufferEntity? = null,
    val memberCount: Int? = null,
    val typingNicks: List<String> = emptyList(),
    val replyTo: MessageEntity? = null,
    // Null means the buffer/connection snapshot has not loaded yet. Do not use Disconnected as a
    // loading sentinel: it briefly paints a false status while entering an already-connected chat.
    val connState: IrcClientState? = null,
    val presence: Map<PresenceKey, PresenceState> = emptyMap(),
)

internal fun MessageEntity.toReplyPreviewData(): ReplyPreviewData = ReplyPreviewData(sender, text)

/**
 * Wire text for resending a failed row. An ACTION is stored with its `/me ` prefix stripped, so
 * re-prefix it; the manager rewrites `/me ` back into a CTCP ACTION. Non-ACTION kinds resend
 * verbatim (plans/15 #10).
 */
fun resendText(kind: io.github.trevarj.motd.data.db.MessageKind, text: String): String =
    if (kind == io.github.trevarj.motd.data.db.MessageKind.ACTION) "/me $text" else text

/**
 * Recreate the repository Paging generation when a behavioral filter changes. Transforming the
 * same PagingData from `combine` would emit its single-collector pageEventFlow a second time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun filteredMessagePages(
    source: () -> Flow<PagingData<MessageEntity>>,
    specs: Flow<MessageVisibilitySpec>,
): Flow<PagingData<MessageEntity>> = specs.flatMapLatest { spec ->
    source().map { paging -> paging.filter { keepMessage(it, spec) } }
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val bufferRepository: BufferRepository,
    private val connectionManager: ConnectionManager,
    private val typingTracker: TypingTracker,
    private val foregroundBufferTracker: ForegroundBufferTracker,
    private val linkPreviewRepository: LinkPreviewRepository,
    private val draftStore: ComposerDraftStore,
    private val scrollPositionStore: ChatScrollPositionStore,
    private val eventSink: IrcEventSink,
    private val settingsRepository: SettingsRepository,
    private val replyPrefs: ReplyPrefs,
    private val visibilityReader: MessageVisibilityReader,
    private val historyResyncCoordinator: HistoryResyncController,
    private val userDao: UserDao,
    contentPreviewPrefs: ContentPreviewPrefs,
    appearancePrefs: AppearancePrefs,
) : ViewModel() {
    val appearance = appearancePrefs.config
    val contentPreviews: StateFlow<ContentPreviewConfig> = contentPreviewPrefs.config
        // Start closed until DataStore emits, so a persisted opt-out cannot race initial composition.
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ContentPreviewConfig(showImages = false, showLinkPreviews = false),
        )
    val replyConfig: StateFlow<ReplyConfig> = replyPrefs.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReplyConfig())

    private val route: ChatRoute = savedStateHandle.toRoute<ChatRoute>()
    val bufferId: Long = route.bufferId

    // Behavioral filter (JPQ visibility + fools HIDE). Distinct so unrelated settings edits don't
    // re-emit the paging stream (plans/13 §2.5). ChatState is untouched — the screen collects
    // [settings] separately (mirrors R1 keeping the 5-ary combine stable).
    private val _hiddenFoolsRevealed = MutableStateFlow(false)
    val hiddenFoolsRevealed: StateFlow<Boolean> = _hiddenFoolsRevealed.asStateFlow()

    private val filterSpec = settingsRepository.settings
        .combine(_hiddenFoolsRevealed) { settings, revealHiddenFools ->
            MessageVisibilitySpec.from(settings).copy(revealHiddenFools = revealHiddenFools)
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, MessageVisibilitySpec())

    /** Cached Paging stream filtered per [MessageFilterSpec]; collected once in the screen. */
    val messages: Flow<PagingData<MessageEntity>> =
        filteredMessagePages(
            source = { messageRepository.messages(bufferId) },
            specs = filterSpec,
        )
            .cachedIn(viewModelScope)

    /** Newest stored wire row, including ignored tails; effective bottom may acknowledge it. */
    val rawNewestTime: StateFlow<Long?> = visibilityReader.observeLatestRawTime(bufferId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Full settings for the timeline (friends/fools/foolsMode/nick styling); collected in the screen. */
    val settings: StateFlow<Settings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    fun setHiddenFoolsRevealed(revealed: Boolean) {
        _hiddenFoolsRevealed.value = revealed
    }

    private val replyTo = MutableStateFlow<MessageEntity?>(null)
    private val _members = MutableStateFlow<List<MemberEntity>>(emptyList())
    private val _memberNicks = MutableStateFlow<List<String>>(emptyList())
    val memberNicks: StateFlow<List<String>> = _memberNicks.asStateFlow()
    private val _knownNicks = MutableStateFlow<Set<String>>(emptySet())
    val knownNicks: StateFlow<Set<String>> = _knownNicks.asStateFlow()
    private val _memberCount = MutableStateFlow<Int?>(null)
    private var membersJob: Job? = null

    private val buffer: StateFlow<BufferEntity?> = bufferRepository.observeBuffer(bufferId)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The route id may be a durable redirect; all live screen behavior uses the winner id. */
    private val operationalBufferId: StateFlow<Long> = buffer
        .map { it?.id ?: bufferId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, bufferId)

    private val connState: StateFlow<IrcClientState?> = buffer
        .combine(connectionManager.connectionStates) { buffer, states ->
            buffer?.let { states[it.networkId] ?: IrcClientState.Disconnected }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var nextVisibleSession = 0L
    private val visibleSession = MutableStateFlow<Long?>(null)

    private data class AutomaticHistoryTrigger(
        val visibleSession: Long,
        val buffer: BufferEntity,
        val client: IrcClient,
    )

    init {
        viewModelScope.launch {
            combine(buffer, connState, visibleSession) { currentBuffer, connection, session ->
                val eligible = currentBuffer?.takeIf { it.type != BufferType.SERVER }
                val client = eligible?.let { connectionManager.clientFor(it.networkId) }
                if (session != null && eligible != null && connection is IrcClientState.Ready && client != null) {
                    AutomaticHistoryTrigger(session, eligible, client)
                } else {
                    null
                }
            }
                // Keep the null transition: it rearms the same client after a real disconnect.
                .distinctUntilChanged { old, new ->
                    old?.visibleSession == new?.visibleSession &&
                        old?.buffer?.id == new?.buffer?.id &&
                        old?.client === new?.client
                }
                .filterNotNull()
                .collectLatest { trigger ->
                    historyResyncCoordinator.reconcileBuffer(
                        buffer = trigger.buffer,
                        client = trigger.client,
                        isCurrent = {
                            connectionManager.clientFor(trigger.buffer.networkId) === trigger.client
                        },
                    )
                }
        }
        viewModelScope.launch {
            combine(buffer, visibleSession) { currentBuffer, session ->
                currentBuffer?.id?.takeIf { session != null }
            }.distinctUntilChanged().collect(foregroundBufferTracker::set)
        }
    }

    val historyResyncState: StateFlow<HistoryResyncState> = operationalBufferId
        .flatMapLatest(historyResyncCoordinator::state)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryResyncState.Idle)

    private val typingNicks = operationalBufferId
        .flatMapLatest(typingTracker::typingNicks)

    val state: StateFlow<ChatState> = combine(
        buffer,
        _memberCount,
        typingNicks,
        replyTo,
        connState.combine(connectionManager.presenceStates) { conn, presence -> conn to presence },
    ) { buffer, memberCount, typing, reply, connAndPresence ->
        val (conn, presence) = connAndPresence
        ChatState(
            buffer = buffer,
            memberCount = memberCount,
            typingNicks = typing,
            replyTo = reply,
            connState = conn,
            presence = presence,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatState())

    /**
     * Member rosters can be large on public IRC channels. Do not collect them as part of the first
     * chat-state emission. The screen requests them only when autocomplete or nick moderation needs
     * the roster, so opening a busy channel never rebuilds every visible message merely to show a
     * member count.
     */
    fun ensureMembersObserved() {
        if (membersJob == null) {
            membersJob = viewModelScope.launch {
                combine(
                    bufferRepository.observeMembers(bufferId).distinctUntilChanged(),
                    connectionManager.rosterStates,
                    operationalBufferId,
                ) { members, rosterStates, roomId -> members to rosterStates[roomId] }
                .distinctUntilChanged()
                .collect { (members, rosterState) ->
                    val authoritative = rosterState == RosterLoadState.LOADED
                    val (nicks, known) = withContext(Dispatchers.Default) {
                        val nicks = if (authoritative) members.map { it.nick } else emptyList()
                        nicks to nicks.map(::normalizeNick).toSet()
                    }
                    _members.value = if (authoritative) members else emptyList()
                    _memberNicks.value = nicks
                    _knownNicks.value = known
                    _memberCount.value = members.size.takeIf { authoritative }
                }
            }
        }
        viewModelScope.launch { connectionManager.requestMembers(operationalBufferId.value) }
    }

    // --- reactions aggregation (plans/15 #5, #18) ---

    /** Msgids from the bounded loaded Paging window, supplied by the screen on page changes. */
    private val visibleMsgids = MutableStateFlow<List<String>>(emptyList())

    fun setVisibleMsgids(ids: List<String>) {
        if (visibleMsgids.value != ids) visibleMsgids.value = ids
    }

    /**
     * Reaction chips keyed by msgid, aggregated in the VM so the value survives across message
     * arrivals (no blank frame from an emptyList re-seed) and picks up echo-confirm msgid swaps as
     * as reactions arrive. Buffer-scoped reactions avoid the SQLite IN(...) overflow; filtering
     * the result to a bounded loaded window prevents historical reaction rows from being
     * re-aggregated on the main thread when a populated chat opens.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val reactionChips: StateFlow<Map<String, List<ReactionChip>>> = combine(
        visibleMsgids
            .map { it.take(MAX_REACTION_WINDOW_MSGIDS) }
            .distinctUntilChanged()
            .flatMapLatest { ids ->
                if (ids.isEmpty()) flowOf(emptyList()) else messageRepository.reactions(bufferId, ids)
            },
        connState,
    ) { visibleReactions, conn ->
        val myNick = (conn as? IrcClientState.Ready)?.nick
        aggregateReactions(visibleReactions, myNick, nickNormalizer())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // Reply previews are requested only by composed rows. The bounded cache shares an in-flight
    // Room lookup across recompositions and its WhileSubscribed policy cancels unused collection.
    private val replyPreviewCache = object : LinkedHashMap<String, StateFlow<ReplyPreviewData?>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StateFlow<ReplyPreviewData?>>): Boolean =
            size > MAX_REPLY_PREVIEW_CACHE
    }

    fun replyPreview(msgid: String): StateFlow<ReplyPreviewData?> = synchronized(replyPreviewCache) {
        replyPreviewCache.getOrPut(msgid) {
            createReplyPreviewFlow(msgid, initialValue = null)
        }
    }

    private fun createReplyPreviewFlow(
        msgid: String,
        initialValue: ReplyPreviewData?,
    ): StateFlow<ReplyPreviewData?> = messageRepository.observeByMsgid(bufferId, msgid)
        .map { it?.toReplyPreviewData() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue)

    // --- read marker snapshot (plans/15 #2) ---

    // Frozen on buffer entry so the "— New messages —" divider keeps a stable boundary instead of
    // flashing/vanishing as markRead advances the live marker. Used ONLY for the divider now.
    private val _readMarkerSnapshot = MutableStateFlow<Long?>(null)
    val readMarkerSnapshot: StateFlow<Long?> = _readMarkerSnapshot.asStateFlow()

    // Live read marker for the scroll-to-bottom FAB badge: unlike the frozen snapshot, this tracks
    // the buffer's real read marker, so once markRead advances it (at bottom) the badge count drops
    // to 0 and stays 0 when scrolling back up — instead of counting already-read messages until
    // re-entry (bug: badge doesn't clear until leaving the chat).
    val readMarkerTime: StateFlow<Long?> = buffer
        .map { it?.effectiveReadFloorTime }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // --- lifecycle: foreground tracker + mark-read (plans/07) ---

    fun onResume() {
        AutoFollowTrace.record("chat_resume", operationalBufferId.value)
        foregroundBufferTracker.set(operationalBufferId.value)
        if (visibleSession.value == null) visibleSession.value = ++nextVisibleSession
    }

    fun onPause() {
        AutoFollowTrace.record("chat_pause", operationalBufferId.value)
        foregroundBufferTracker.set(null)
        visibleSession.value = null
    }

    /**
     * Mark read up to [time] (the newest visible/loaded server time). Called on resume with the
     * newest loaded message and whenever a new message lands while the list is at the bottom
     * (plans/07). [time] <= 0 is treated as "nothing to mark" and skipped.
     */
    fun markRead(time: Long) {
        if (time <= 0) return
        val roomId = operationalBufferId.value
        AutoFollowTrace.record("markread_request", roomId) { "marker=$time" }
        viewModelScope.launch { connectionManager.markRead(roomId, time) }
    }

    fun acceptInvite(messageId: Long) = viewModelScope.launch {
        connectionManager.acceptInvite(messageId)
    }

    fun dismissInvite(messageId: Long) = viewModelScope.launch {
        connectionManager.dismissInvite(messageId)
    }

    // --- composer actions ---

    fun setReply(message: MessageEntity?) {
        // The selected parent is already in memory. Seed its lookup so the optimistic outgoing row
        // renders the real quote on its first frame instead of flashing the unresolved placeholder.
        message?.msgid?.let { msgid ->
            synchronized(replyPreviewCache) {
                if (replyPreviewCache[msgid]?.value == null) {
                    replyPreviewCache[msgid] = createReplyPreviewFlow(
                        msgid = msgid,
                        initialValue = message.toReplyPreviewData(),
                    )
                }
            }
        }
        replyTo.value = message
    }

    fun sendTyping(active: Boolean) = viewModelScope.launch {
        connectionManager.sendTyping(operationalBufferId.value, if (active) "active" else "done")
    }

    /**
     * React to [message] with [emoji]. A confirmed message sends immediately. An own message still
     * pending its echo has `msgid == null`, so the tap MUST NOT be silently dropped (bug: reacting to
     * a just-sent message "sometimes did nothing"): defer the send until the row's msgid lands, then
     * fire the TAGMSG. The optimistic own-reaction chip is upserted inside sendReact, so it appears
     * instantly and reconciles with the server echo without duplicating. On queue timeout (echo never
     * arrived) surface a snackbar rather than failing silently.
     */
    fun react(message: MessageEntity, emoji: String) = viewModelScope.launch {
        val ready = connState.value as? IrcClientState.Ready
        val removing = message.msgid?.let { msgid ->
            reactionChips.value[msgid]?.firstOrNull { it.emoji == emoji }?.mine
        } == true
        if (ready == null || !canSendReactionTags(ready.caps, ready.isupport, removing)) {
            _snackbar.value = "reaction_blocked"
            return@launch
        }
        val msgid = message.msgid ?: resolveReactionMsgid(message.id)
        if (msgid == null) {
            _snackbar.value = "react_failed" // sentinel; screen maps to chat_react_failed
            return@launch
        }
        try {
            connectionManager.sendReact(operationalBufferId.value, msgid, emoji)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            _snackbar.value = "reaction_send_failed"
        }
    }

    /**
     * A bouncer without labeled-response can echo our send before its durable msgid is available.
     * The normal chat-open reconciliation may already own the network-wide history gate for target
     * discovery or gap repair. Use the coordinator's urgent newest-page path so msgid recovery only
     * waits for the client's current wire request, not the whole network pass. The Room observer
     * runs concurrently so either a socket echo or that history page resolves the reaction.
     */
    private suspend fun resolveReactionMsgid(messageId: Long): String? =
        coroutineScope {
            val reconciliation = launch {
                val currentBuffer = buffer.value
                val client = currentBuffer?.let { connectionManager.clientFor(it.networkId) }
                if (currentBuffer != null && client != null) {
                    historyResyncCoordinator.reconcilePendingMessage(
                        buffer = currentBuffer,
                        client = client,
                        isCurrent = {
                            connectionManager.clientFor(currentBuffer.networkId) === client
                        },
                    )
                }
            }
            try {
                messageRepository.awaitMsgid(messageId, REACT_QUEUE_TIMEOUT_MS)
            } finally {
                reconciliation.cancelAndJoin()
            }
        }

    /** Retry only retires the failed row after a replacement attempt is accepted. */
    fun retry(message: MessageEntity) = viewModelScope.launch {
        val result = connectionManager.sendMessageForRetry(
            operationalBufferId.value,
            resendText(message.kind, message.text),
            message.replyToMsgid,
        )
        if (result.replacementPersisted) messageRepository.deleteMessage(message.id)
    }

    /** Delete a failed local row without resending (action-sheet delete affordance, plans/15 #10). */
    fun deleteFailed(message: MessageEntity) = viewModelScope.launch {
        messageRepository.deleteMessage(message.id)
    }

    suspend fun linkPreview(url: String): LinkPreview? =
        if (contentPreviews.value.showLinkPreviews) linkPreviewRepository.preview(url) else null

    fun cachedLinkPreview(url: String) =
        if (contentPreviews.value.showLinkPreviews) linkPreviewRepository.cachedPreview(url) else null

    /** Transient one-shot messages surfaced as a snackbar by the screen (plans/16 §5.6). */
    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar: StateFlow<String?> = _snackbar.asStateFlow()

    fun consumeSnackbar() { _snackbar.value = null }

    fun refreshHistory(range: HistoryRefreshRange = HistoryRefreshRange.MISSING) {
        val currentBuffer = buffer.value ?: return
        val client = connectionManager.clientFor(currentBuffer.networkId)
        if (client == null || connState.value !is IrcClientState.Ready) {
            _snackbar.value = "history_offline"
            return
        }
        viewModelScope.launch {
            historyResyncCoordinator.resyncBuffer(
                buffer = currentBuffer,
                client = client,
                isCurrent = { connectionManager.clientFor(currentBuffer.networkId) === client },
                range = range,
            )
        }
    }

    fun cancelHistoryRefresh() = historyResyncCoordinator.cancelBufferResync(operationalBufferId.value)

    fun consumeHistoryResyncState() = historyResyncCoordinator.consumeState(operationalBufferId.value)

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
                connectionManager.sendMessage(operationalBufferId.value, cmd.text, replyTo.value?.msgid)
                replyTo.value = null
                connectionManager.sendTyping(operationalBufferId.value, "done")
            }
            is ChatCommand.Join -> networkId?.let { connectionManager.joinChannel(it, cmd.channel) }
            is ChatCommand.Part -> connectionManager.partChannel(operationalBufferId.value, cmd.reason)
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
    private var nickDetailsJob: Job? = null

    /**
     * Open the nick sheet for [nick]. WHOX is requested when available so query peers can populate
     * the same cached identity path as channel members. With `labeled-response` we also WHOIS via a
     * labeled request, parse the richer numerics, and fold the details in when they land (30s label
     * timeout, guarded); otherwise a plain WHOIS is sent and its numerics surface in the server
     * buffer (§5.6.3). Actions render immediately regardless.
     */
    fun openNickSheet(nick: String) {
        // Moderation visibility depends on our prefixes in the channel roster. Load it on this
        // explicit interaction, not on every channel entry.
        ensureMembersObserved()
        _nickSheet.value = NickSheetState(nick = nick)
        val networkId = state.value.buffer?.networkId ?: return
        val client = connectionManager.clientFor(networkId)
        val normalizedNick = client?.isupport?.normalize(nick) ?: normalizeNick(nick)
        nickDetailsJob?.cancel()
        nickDetailsJob = viewModelScope.launch {
            userDao.observeByNick(networkId, normalizedNick).collect { cached ->
                val current = _nickSheet.value
                if (current?.nick == nick) _nickSheet.value = current.copy(cached = cached)
            }
        }
        if (client == null) return
        if (client.isupport["WHOX"] != null) {
            // WhoxRow events still flow through EventProcessor while the correlated request waits,
            // so UserEntity and this sheet's userDao collector converge through the normal path.
            viewModelScope.launch { runCatching { client.whox(nick) } }
        }
        val whoisMsg = IrcMessage(command = "WHOIS", params = listOf(nick))
        if (client.hasCap("labeled-response")) {
            viewModelScope.launch {
                val lines = runCatching { client.sendLabeled(whoisMsg) }.getOrNull().orEmpty()
                val info = parseWhois(lines)
                // Only fold in if the sheet is still open for this nick.
                if (info != null && _nickSheet.value?.nick == nick) {
                    _nickSheet.value = _nickSheet.value?.copy(whois = info)
                }
            }
        } else {
            viewModelScope.launch { client.send(whoisMsg) }
        }
    }

    fun dismissNickSheet() {
        nickDetailsJob?.cancel()
        nickDetailsJob = null
        _nickSheet.value = null
    }

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
        val me = _members.value.firstOrNull { normalize(it.nick) == normalize(myNick) } ?: return false
        val order = buffer.networkId.let { connectionManager.clientFor(it) }
            ?.let { io.github.trevarj.motd.ui.channelinfo.prefixOrderFrom(it.isupport.prefixModes) }
            ?: io.github.trevarj.motd.ui.channelinfo.DEFAULT_PREFIX_ORDER
        return io.github.trevarj.motd.ui.channelinfo.canModerate(me.prefixes, order)
    }

    // --- composer prefill (mention → draft, plans/11 §A) ---

    /** Consume-once composer prefill queued by ChannelInfo before popping back; null when none. */
    fun consumePrefill(): String? = draftStore.consume(operationalBufferId.value)

    fun loadDraft(): String? = draftStore.loadDraft(operationalBufferId.value)

    fun saveDraft(text: String) = draftStore.saveDraft(operationalBufferId.value, text)

    fun clearDraft() = draftStore.clearDraft(operationalBufferId.value)

    // --- search deep-jump (plans/11 §C) ---

    private val jumpMsgid: String? = route.jumpToMsgid
    private val jumpTime: Long = route.jumpToTime
    private val jumpEventId: Long? = route.jumpToEventId
    private data class JumpRequest(
        val msgid: String?,
        val time: Long,
        val eventId: Long?,
        val settlesEntryPosition: Boolean,
    )

    private var activeJumpRequest: JumpRequest? = if (jumpTime > 0 || jumpEventId != null) {
        JumpRequest(jumpMsgid, jumpTime, jumpEventId, settlesEntryPosition = true)
    } else {
        null
    }

    /**
     * CHATHISTORY AROUND fetch used by [ChatJumpResolver] when a msgid target is not yet local:
     * requires a live client with `draft/chathistory`, fetches ~100 messages around [timeMs], and
     * feeds them through the sole IRC→Room writer. Returns true when events were persisted.
     */
    private val resolver = ChatJumpResolver(
        messages = messageRepository,
        fetchAround = fetch@ { name, timeMs, limit ->
            val networkId = state.value.buffer?.networkId ?: return@fetch false
            val client = connectionManager.clientFor(networkId) ?: return@fetch false
            val availability = client.historyAvailability as? HistoryAvailability.Ready
                ?: return@fetch false
            val result = runCatching {
                client.chathistory(
                    ChatHistoryRequest(
                        subcommand = ChatHistoryRequest.Subcommand.AROUND,
                        target = name,
                        bound1 = ChatHistorySelectors.timestamp(timeMs),
                        limit = minOf(limit, availability.pageLimit),
                    ),
                )
            }.getOrNull() as? ChatHistoryResponse.Messages ?: return@fetch false
            if (result.events.isEmpty()) return@fetch false
            eventSink.process(networkId, IrcEvent.HistoryBatch(name, result.events))
            true
        },
        countNewer = { targetBufferId, serverTime, id ->
            visibilityReader.countTimelineNewer(
                targetBufferId,
                serverTime,
                id,
                MessageVisibilitySpec.from(settingsRepository.settings.first()),
            )
        },
    )

    private val _jumpTarget = MutableStateFlow<ChatJumpResolver.Result.Target?>(null)
    /** Resolved jump target (index + optional highlight msgid); null when nothing to jump to. */
    val jumpTarget: StateFlow<ChatJumpResolver.Result.Target?> = _jumpTarget.asStateFlow()

    // Normal channel entry is also a one-shot position operation. Unlike a search deep-link it
    // has no highlight, but it must settle before read state can advance.
    private val _initialTarget = MutableStateFlow<ChatInitialPosition?>(null)
    val initialTarget: StateFlow<ChatInitialPosition?> = _initialTarget.asStateFlow()

    private val _entryPositionSettled = MutableStateFlow(
        savedStateHandle.get<Boolean>(ENTRY_POSITION_SETTLED_KEY) == true,
    )
    /** True only after a resolved entry/deep-link position has settled and may advance read state. */
    val entryPositionSettled: StateFlow<Boolean> = _entryPositionSettled.asStateFlow()

    private val _entryPositionUnresolved = MutableStateFlow(
        savedStateHandle.get<Boolean>(ENTRY_POSITION_UNRESOLVED_KEY) == true,
    )
    /** Durable explicit failure state: entry remains read-gated until the user navigates away. */
    val entryPositionUnresolved: StateFlow<Boolean> = _entryPositionUnresolved.asStateFlow()

    // Nullable-event StateFlow instead of a replay-less SharedFlow so a NotFound resolved in init
    // (before the screen subscribes) is not dropped; the UI clears it via [onJumpFailedShown]
    // (plans/15 #13).
    private val _jumpFailed = MutableStateFlow(false)
    val jumpFailed: StateFlow<Boolean> = _jumpFailed.asStateFlow()

    // Re-resolve is allowed exactly once per navigation; a second index shift falls through to the
    // not-loaded snackbar rather than looping (plans/15 #12).
    private var reresolveUsed = false

    init {
        val hasDeepJump = jumpTime > 0
        // `jump_consumed` only prevents duplicate work after a completed jump. If Android kills
        // the process while the first resolve/scroll is in flight, the restored handle has it set
        // but neither terminal entry-position state; re-publish the target/failure for the new UI.
        if (needsDeepJumpResolution(
                hasDeepJump = hasDeepJump,
                jumpConsumed = savedStateHandle.get<Boolean>(JUMP_CONSUMED_KEY) == true,
                entryPositionSettled = _entryPositionSettled.value,
                entryPositionUnresolved = _entryPositionUnresolved.value,
            )
        ) {
            savedStateHandle[JUMP_CONSUMED_KEY] = true
            resolveJump()
        }
        // Freeze the read-marker once, on the first buffer emission, so the unread divider/badge
        // stay put instead of collapsing as markRead advances the live marker (plans/15 #2).
        // Anchor it to the first message from SOMEONE ELSE past the marker (minus 1, so the existing
        // "> marker" divider/badge comparisons land on that message): your own sent messages must
        // never trip the "new messages" divider or the scroll-down badge, since you have read what
        // you just sent. Null (no real marker, or nothing unread from others) hides both.
        viewModelScope.launch {
            val entrySpec = MessageVisibilitySpec.from(settingsRepository.settings.first())
            val realMarker = bufferRepository.observeBuffer(bufferId).firstOrNull()?.effectiveReadFloorTime
            _readMarkerSnapshot.value = if (realMarker == null) {
                null
            } else {
                visibilityReader.firstVisibleUnreadTime(bufferId, realMarker, entrySpec)
                    ?.let { it - 1 }
            }
            // A deep-link owns positioning. A normal open first restores this buffer's last
            // in-memory viewport, then falls back to oldest unread incoming, then newest.
            if (!hasDeepJump && !_entryPositionSettled.value) {
                _initialTarget.value = restoredScrollPosition(entrySpec)
                    ?: unreadEntryPosition(realMarker, entrySpec)
                    ?: ChatInitialPosition(index = 0)
            }
        }
        viewModelScope.launch {
            visibilityReader.observeEventRedirects().collect {
                restoreRedirectedViewport(filterSpec.value)
            }
        }
    }

    private suspend fun restoredScrollPosition(spec: MessageVisibilitySpec): ChatInitialPosition? {
        val roomId = operationalBufferId.value
        val saved = scrollPositionStore.get(roomId)
            ?: bufferId.takeIf { it != roomId }?.let(scrollPositionStore::get)
            ?: return null
        val anchor = visibilityReader.resolveSavedAnchor(
            bufferId = bufferId,
            msgid = saved.msgid,
            serverTime = saved.serverTime,
            id = saved.rowId,
            spec = spec,
        ) ?: run {
            scrollPositionStore.remove(roomId)
            return null
        }
        val index = visibilityReader.countTimelineNewer(
            bufferId,
            anchor.serverTime,
            anchor.id,
            spec,
        )
        val canonicalSavedId = visibilityReader.resolveCanonicalEventId(saved.rowId)
        return ChatInitialPosition(
            index = index,
            offset = saved.offset.takeIf { anchor.id == canonicalSavedId } ?: 0,
            fromSavedPosition = true,
        )
    }

    /** Re-anchor an already-open viewport when coalescence replaces and retimestamps its row. */
    private suspend fun restoreRedirectedViewport(spec: MessageVisibilitySpec) {
        val roomId = operationalBufferId.value
        val saved = scrollPositionStore.get(roomId)
            ?: bufferId.takeIf { it != roomId }?.let(scrollPositionStore::get)
            ?: return
        val canonicalSavedId = visibilityReader.resolveCanonicalEventId(saved.rowId)
        if (canonicalSavedId == saved.rowId) return
        val anchor = visibilityReader.resolveSavedAnchor(
            bufferId = bufferId,
            msgid = saved.msgid,
            serverTime = saved.serverTime,
            id = saved.rowId,
            spec = spec,
        ) ?: return
        if (anchor.id != canonicalSavedId) return
        val index = visibilityReader.countTimelineNewer(
            bufferId,
            anchor.serverTime,
            anchor.id,
            spec,
        )
        scrollPositionStore.put(
            roomId,
            saved.copy(
                index = index,
                msgid = anchor.msgid,
                serverTime = anchor.serverTime,
                rowId = anchor.id,
            ),
        )
        _initialTarget.value = ChatInitialPosition(
            index = index,
            offset = saved.offset,
            fromSavedPosition = true,
        )
    }

    private suspend fun unreadEntryPosition(
        realMarker: Long?,
        spec: MessageVisibilitySpec,
    ): ChatInitialPosition? {
        val targetTime = realMarker?.let {
            visibilityReader.firstVisibleUnreadTime(bufferId, it, spec)
        } ?: return null
        return when (val result = resolver.resolve(bufferId, null, targetTime, null)) {
            is ChatJumpResolver.Result.Target -> ChatInitialPosition(index = result.index)
            ChatJumpResolver.Result.NotFound -> null
        }
    }

    private fun resolveJump() = viewModelScope.launch {
        // The buffer name (chathistory target) may not be in `state` yet on first composition;
        // read it directly from the repo so the AROUND fallback has a target.
        val name = bufferRepository.observeBuffer(bufferId).firstOrNull()?.ircTarget
        publishResolve(name)
    }

    private suspend fun publishResolve(name: String?) {
        val request = activeJumpRequest ?: return
        when (val r = resolver.resolve(
            bufferId,
            request.msgid,
            request.time,
            name,
            eventId = request.eventId,
        )) {
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
        val settlesEntryPosition = activeJumpRequest?.settlesEntryPosition == true
        activeJumpRequest = null
        _jumpTarget.value = null
        if (settlesEntryPosition) markEntryPositionSettled()
    }

    /** The screen completed its one-shot normal-entry positioning. */
    fun onInitialPositionHandled() {
        _initialTarget.value = null
        markEntryPositionSettled()
    }

    fun saveScrollPosition(position: ChatScrollPosition) {
        scrollPositionStore.put(operationalBufferId.value, position)
    }

    fun clearScrollPosition() {
        scrollPositionStore.remove(operationalBufferId.value)
    }

    /** A target could not be loaded safely; retain the read gate rather than marking it read. */
    fun onInitialPositionUnresolved() {
        _initialTarget.value = null
        markEntryPositionUnresolved()
    }

    fun onJumpUnresolved() {
        val settlesEntryPosition = activeJumpRequest?.settlesEntryPosition == true
        activeJumpRequest = null
        _jumpTarget.value = null
        if (settlesEntryPosition) markEntryPositionUnresolved()
    }

    /**
     * Resolve and reveal a locally available replied-to message. Reply previews are only clickable
     * after their target has resolved from Room, so this normally remains a local index lookup; the
     * shared jump pipeline still supplies bounded paging, index-shift recovery, and highlighting.
     */
    fun jumpToRepliedMessage(msgid: String) {
        activeJumpRequest = JumpRequest(
            msgid,
            time = 0,
            eventId = null,
            settlesEntryPosition = false,
        )
        reresolveUsed = false
        _jumpFailed.value = false
        viewModelScope.launch {
            publishResolve(state.value.buffer?.name)
        }
    }

    private fun markEntryPositionSettled() {
        savedStateHandle[ENTRY_POSITION_SETTLED_KEY] = true
        _entryPositionSettled.value = true
    }

    private fun markEntryPositionUnresolved() {
        savedStateHandle[ENTRY_POSITION_UNRESOLVED_KEY] = true
        _entryPositionUnresolved.value = true
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
        const val ENTRY_POSITION_SETTLED_KEY = "entry_position_settled"
        const val ENTRY_POSITION_UNRESOLVED_KEY = "entry_position_unresolved"

        // Max wait for a pending own message's msgid to land before a queued reaction gives up.
        // Urgent history recovery may wait behind one unlabeled 30s wire request before its own
        // bounded newest-page request. The observer still completes immediately on a fast echo.
        const val REACT_QUEUE_TIMEOUT_MS = 72_000L
    }
}

/** Whether a deep link still needs its target/failure published after SavedState restoration. */
internal fun needsDeepJumpResolution(
    hasDeepJump: Boolean,
    jumpConsumed: Boolean,
    entryPositionSettled: Boolean,
    entryPositionUnresolved: Boolean,
): Boolean = hasDeepJump && (!jumpConsumed || (!entryPositionSettled && !entryPositionUnresolved))
