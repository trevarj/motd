package io.github.trevarj.motd.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.NetworkIdentityDao
import io.github.trevarj.motd.data.db.ComposerDraftEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.effectiveLocalReadAnchor
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.db.UserDao
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.repo.LinkPreviewRepository
import io.github.trevarj.motd.data.repo.MessageRepository
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
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.canSendReactionTags
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.RosterLoadState
import io.github.trevarj.motd.service.PresenceKey
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.service.ForegroundBufferTracker
import io.github.trevarj.motd.service.HistoryResyncController
import io.github.trevarj.motd.service.HistoryRefreshRange
import io.github.trevarj.motd.service.HistoryResyncState
import io.github.trevarj.motd.service.IrcEventSink
import io.github.trevarj.motd.service.SendAcceptance
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
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

data class ComposerDraftState(
    val text: String = "",
    val hydrated: Boolean = false,
    val revision: Long = 0,
)

private data class DraftSnapshot(
    val roomId: Long,
    val text: String,
    val replyToEventId: Long?,
    val revision: Long,
)

/** One user-visible draft revision may produce at most one outbound submission. */
private data class DraftSubmissionKey(
    val roomId: Long,
    val revision: Long,
)

private sealed interface DraftCommand {
    data class Persist(val snapshot: DraftSnapshot) : DraftCommand
    data class PrepareSubmission(
        val snapshot: DraftSnapshot,
        val result: CompletableDeferred<ComposerDraftEntity?>,
    ) : DraftCommand
    data class ClearSubmission(
        val snapshot: DraftSnapshot,
        val persisted: ComposerDraftEntity,
        val result: CompletableDeferred<Boolean>,
    ) : DraftCommand
}

private data class PreparedDraftSubmission(
    val snapshot: DraftSnapshot,
    val persisted: ComposerDraftEntity?,
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
internal fun repositoryMessagePages(
    source: (MessageVisibilitySpec) -> Flow<PagingData<MessageEntity>>,
    specs: Flow<MessageVisibilitySpec>,
): Flow<PagingData<MessageEntity>> = specs.flatMapLatest(source)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val bufferRepository: BufferRepository,
    private val networkIdentityDao: NetworkIdentityDao,
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

    private val filterSpecs = settingsRepository.settings
        .combine(_hiddenFoolsRevealed) { settings, revealHiddenFools ->
            MessageVisibilitySpec.from(settings).copy(revealHiddenFools = revealHiddenFools)
        }
        .distinctUntilChanged()
    private val filterSpec = filterSpecs
        .stateIn(viewModelScope, SharingStarted.Eagerly, MessageVisibilitySpec())

    /** Every visibility change cancels the old generation and creates a positionally exact Pager. */
    val messages: Flow<PagingData<MessageEntity>> =
        repositoryMessagePages(
            source = { visibility -> messageRepository.messages(bufferId, visibility) },
            specs = filterSpecs,
        )
            .cachedIn(viewModelScope)

    /** Newest stored wire row, including ignored tails; effective bottom may acknowledge it. */
    val rawNewestAnchor: StateFlow<TimelineAnchor?> = visibilityReader.observeLatestRawAnchor(bufferId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Full settings for the timeline (friends/fools/foolsMode/nick styling); collected in the screen. */
    val settings: StateFlow<Settings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    fun setHiddenFoolsRevealed(revealed: Boolean) {
        _hiddenFoolsRevealed.value = revealed
    }

    private val uiEventQueue = ChatUiEventQueue()
    val uiEvents: StateFlow<List<QueuedChatUiEvent>> = uiEventQueue.pending

    fun acknowledgeUiEvent(id: Long) = uiEventQueue.acknowledge(id)

    private val replyTo = MutableStateFlow<MessageEntity?>(null)
    private val draftStateLock = Any()
    private val draftCommands = Channel<DraftCommand>(Channel.UNLIMITED)
    private val draftHydrated = CompletableDeferred<Unit>()
    private val _composerDraft = MutableStateFlow(ComposerDraftState())
    val composerDraft: StateFlow<ComposerDraftState> = _composerDraft.asStateFlow()
    private var currentDraftText = ""
    private var currentReplyToEventId: Long? = null
    private var nextDraftRevision = 0L
    private var draftTextEdited = false
    private var draftReplyEdited = false
    // A duplicate click can arrive before the UI has observed the accepted draft clear. Keep the
    // reservation with the draft state so every entry point gets the same exactly-once behavior.
    private val inFlightDraftSubmissions = mutableSetOf<DraftSubmissionKey>()
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

    private val persistedIdentity = buffer
        .flatMapLatest { current ->
            current?.let { room ->
                networkIdentityDao.observe(room.networkId)
            } ?: flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Live ISUPPORT wins while connected; persisted rules keep offline rendering deterministic. */
    val identityRules: StateFlow<IrcIdentityRules> = combine(
        buffer,
        connState,
        persistedIdentity,
    ) { current, connection, persisted ->
        if (current != null && connection is IrcClientState.Ready) {
            connectionManager.clientFor(current.networkId)?.isupport?.identityRules
                ?: persisted?.identityRules
                ?: IrcIdentityRules()
        } else {
            persisted?.identityRules ?: IrcIdentityRules()
        }
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IrcIdentityRules())

    val historyAvailability: StateFlow<HistoryAvailability> = combine(buffer, connState) { current, connection ->
        when {
            current == null || current.type == BufferType.SERVER -> HistoryAvailability.Unsupported
            connection !is IrcClientState.Ready -> HistoryAvailability.NegotiatingOrOffline
            else -> connectionManager.clientFor(current.networkId)?.historyAvailability
                ?: HistoryAvailability.NegotiatingOrOffline
        }
    }.distinctUntilChanged().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        HistoryAvailability.NegotiatingOrOffline,
    )

    private data class OwnIdentityLookup(
        val networkId: Long,
        val nick: String,
        val normalizedNick: String,
    )

    private data class OwnIdentity(val nick: String?, val account: String?)

    private val ownIdentity = combine(
        buffer,
        connState,
        identityRules,
        persistedIdentity,
    ) { current, connection, rules, persisted ->
        val nick = (connection as? IrcClientState.Ready)?.nick ?: persisted?.selfNick
        if (current == null || nick == null) null else OwnIdentityLookup(
            current.networkId,
            nick,
            rules.normalize(nick),
        )
    }.distinctUntilChanged().flatMapLatest { lookup ->
        lookup?.let {
            userDao.observeByNick(it.networkId, it.normalizedNick)
                .map { user -> OwnIdentity(it.nick, user?.account) }
        } ?: flowOf(OwnIdentity(null, null))
    }

    private var nextVisibleSession = 0L
    private val visibleSession = MutableStateFlow<Long?>(null)

    private data class AutomaticHistoryTrigger(
        val visibleSession: Long,
        val buffer: BufferEntity,
        val client: IrcClient,
    )

    init {
        viewModelScope.launch { runDraftWriter() }
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

    init {
        viewModelScope.launch {
            var lastTerminal: HistoryResyncState? = null
            historyResyncState.collect { result ->
                val event = when (result) {
                    is HistoryResyncState.Updated -> ChatUiEvent.HistoryUpdated(result.inserted)
                    HistoryResyncState.UpToDate -> ChatUiEvent.HistoryUpToDate
                    HistoryResyncState.Unsupported -> ChatUiEvent.HistoryUnsupported
                    is HistoryResyncState.Incomplete -> ChatUiEvent.HistoryIncomplete(result.inserted)
                    is HistoryResyncState.Capped -> ChatUiEvent.HistoryCapped(result.inserted, result.limit)
                    is HistoryResyncState.Failed -> ChatUiEvent.HistoryFailed
                    else -> null
                }
                if (event == null) {
                    lastTerminal = null
                } else if (lastTerminal != result) {
                    lastTerminal = result
                    uiEventQueue.enqueue(event)
                    if (
                        result is HistoryResyncState.Updated ||
                        result == HistoryResyncState.UpToDate ||
                        result == HistoryResyncState.Unsupported
                    ) {
                        historyResyncCoordinator.consumeState(operationalBufferId.value)
                    }
                }
            }
        }
    }

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
                    identityRules,
                ) { members, rosterStates, roomId, rules -> Triple(members, rosterStates[roomId], rules) }
                .distinctUntilChanged()
                .collect { (members, rosterState, rules) ->
                    val authoritative = rosterState == RosterLoadState.LOADED
                    val (nicks, known) = withContext(Dispatchers.Default) {
                        val nicks = if (authoritative) members.map { it.nick } else emptyList()
                        nicks to nicks.map(rules::normalize).toSet()
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
     * reactions arrive. The bounded loaded-window msgid list stays below SQLite's bind-variable
     * limit and avoids aggregating historical reaction rows when a populated chat opens.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val reactionChips: StateFlow<Map<String, List<ReactionChip>>> = combine(
        visibleMsgids
            .map { it.take(MAX_REACTION_WINDOW_MSGIDS) }
            .distinctUntilChanged()
            .flatMapLatest { ids ->
                if (ids.isEmpty()) flowOf(emptyList()) else messageRepository.reactions(bufferId, ids)
        },
        ownIdentity,
        identityRules,
    ) { visibleReactions, identity, rules ->
        aggregateReactions(visibleReactions, identity.nick, identity.account, rules)
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

    // Frozen on buffer entry so the "New messages" divider keeps a stable boundary instead of
    // flashing/vanishing as markRead advances the live marker. Used ONLY for the divider now.
    private val _readMarkerSnapshot = MutableStateFlow<TimelineAnchor?>(null)
    val readMarkerSnapshot: StateFlow<TimelineAnchor?> = _readMarkerSnapshot.asStateFlow()

    // Live read marker for the scroll-to-bottom FAB badge: unlike the frozen snapshot, this tracks
    // the buffer's real read marker, so once markRead advances it (at bottom) the badge count drops
    // to 0 and stays 0 when scrolling back up — instead of counting already-read messages until
    // re-entry (bug: badge doesn't clear until leaving the chat).
    val localReadAnchor: StateFlow<TimelineAnchor?> = buffer
        .map { it?.effectiveLocalReadAnchor }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun countUnreadBelowViewport(firstVisibleIndex: Int, marker: TimelineAnchor): Int =
        visibilityReader.countVisibleUnreadInTimelinePrefix(
            bufferId = bufferId,
            beforeIndex = firstVisibleIndex,
            after = marker,
            maxCount = 100,
            spec = filterSpec.value,
        )

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
    fun markRead(anchor: TimelineAnchor) {
        if (anchor.serverTime <= 0 || anchor.eventId <= 0) return
        val roomId = operationalBufferId.value
        AutoFollowTrace.record("markread_request", roomId) {
            "marker=${anchor.serverTime}:${anchor.eventId}"
        }
        viewModelScope.launch { connectionManager.markRead(roomId, anchor) }
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
        synchronized(draftStateLock) {
            draftReplyEdited = true
            replyTo.value = message
            currentReplyToEventId = message?.id
            draftCommands.trySend(DraftCommand.Persist(advanceDraftRevisionLocked()))
        }
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
            uiEventQueue.enqueue(ChatUiEvent.ReactionBlocked)
            return@launch
        }
        val msgid = message.msgid ?: resolveReactionMsgid(message.id)
        if (msgid == null) {
            uiEventQueue.enqueue(ChatUiEvent.ReactionTargetUnavailable)
            return@launch
        }
        try {
            connectionManager.sendReact(operationalBufferId.value, msgid, emoji)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            uiEventQueue.enqueue(ChatUiEvent.ReactionSendFailed)
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

    /** Retry mutates the same durable row; no replacement deletion is involved. */
    fun retry(message: MessageEntity) = viewModelScope.launch {
        if (connectionManager.retryMessage(message.id) is SendAcceptance.Rejected) {
            uiEventQueue.enqueue(ChatUiEvent.SendRejected)
        }
    }

    /** Delete a failed local row without resending (action-sheet delete affordance, plans/15 #10). */
    fun deleteFailed(message: MessageEntity) = viewModelScope.launch {
        messageRepository.deleteMessage(message.id)
    }

    suspend fun linkPreview(url: String): LinkPreview? =
        if (contentPreviews.value.showLinkPreviews) linkPreviewRepository.preview(url) else null

    fun cachedLinkPreview(url: String) =
        if (contentPreviews.value.showLinkPreviews) linkPreviewRepository.cachedPreview(url) else null

    fun refreshHistory(range: HistoryRefreshRange = HistoryRefreshRange.MISSING) {
        val currentBuffer = buffer.value ?: return
        val client = connectionManager.clientFor(currentBuffer.networkId)
        if (client == null || connState.value !is IrcClientState.Ready) {
            uiEventQueue.enqueue(ChatUiEvent.HistoryOffline)
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
                val roomId = operationalBufferId.value
                val submission = prepareDraftSubmission(raw) ?: return@launch
                try {
                    val result = connectionManager.sendMessage(
                        roomId,
                        cmd.text,
                        submission.snapshot.replyToEventId,
                    )
                    if (result is SendAcceptance.Accepted) {
                        clearDraftSubmission(submission)
                        connectionManager.sendTyping(roomId, "done")
                    } else if (result is SendAcceptance.Rejected) {
                        uiEventQueue.enqueue(ChatUiEvent.SendRejected)
                    }
                } finally {
                    releaseDraftSubmission(submission.snapshot)
                }
            }
            is ChatCommand.Join -> networkId?.let { connectionManager.joinChannel(it, cmd.channel) }
            is ChatCommand.Part -> connectionManager.partChannel(operationalBufferId.value, cmd.reason)
            is ChatCommand.Msg -> networkId?.let { nid ->
                val target = connectionManager.ensureQueryBuffer(nid, cmd.nick)
                val submission = prepareDraftSubmission(raw) ?: return@let
                try {
                    when (connectionManager.sendMessage(target, cmd.text)) {
                        is SendAcceptance.Accepted -> {
                            clearDraftSubmission(submission)
                            onOpenBuffer(target)
                        }
                        is SendAcceptance.Rejected -> uiEventQueue.enqueue(ChatUiEvent.SendRejected)
                    }
                } finally {
                    releaseDraftSubmission(submission.snapshot)
                }
            }
            is ChatCommand.Query -> networkId?.let { nid ->
                onOpenBuffer(connectionManager.ensureQueryBuffer(nid, cmd.nick))
            }
            is ChatCommand.Nick -> networkId?.let { nid ->
                connectionManager.clientFor(nid)?.send(IrcMessage(command = "NICK", params = listOf(cmd.nick)))
            }
            is ChatCommand.Topic -> networkId?.let { nid ->
                val channel = state.value.buffer?.ircTarget ?: return@launch
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
            uiEventQueue.enqueue(ChatUiEvent.InvalidCommand)
            return
        }
        val client = connectionManager.clientFor(nid) ?: return
        val submission = prepareDraftSubmission(raw) ?: return
        try {
            client.send(msg)
            clearDraftSubmission(submission)
        } finally {
            releaseDraftSubmission(submission.snapshot)
        }
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
        val normalizedNick = identityRules.value.normalize(nick)
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
        val channel = state.value.buffer?.ircTarget ?: return@launch
        val flag = (if (grant) "+" else "-") + mode
        connectionManager.clientFor(nid)?.send(IrcMessage(command = "MODE", params = listOf(channel, flag, nick)))
    }

    /** KICK <channel> <nick> [:reason]. */
    fun kick(nick: String, reason: String?) = viewModelScope.launch {
        val nid = state.value.buffer?.networkId ?: return@launch
        val channel = state.value.buffer?.ircTarget ?: return@launch
        val params = if (reason.isNullOrBlank()) listOf(channel, nick) else listOf(channel, nick, reason)
        connectionManager.clientFor(nid)?.send(IrcMessage(command = "KICK", params = params))
    }

    /** MODE <channel> +b <banMask(nick)>. */
    fun ban(nick: String) = viewModelScope.launch {
        val nid = state.value.buffer?.networkId ?: return@launch
        val channel = state.value.buffer?.ircTarget ?: return@launch
        connectionManager.clientFor(nid)
            ?.send(IrcMessage(command = "MODE", params = listOf(channel, "+b", io.github.trevarj.motd.ui.channelinfo.banMask(nick))))
    }

    /** Toggle [nick]'s friend/fool membership (reuses SettingsRepository semantics). */
    fun toggleFriend(nick: String) = viewModelScope.launch {
        val settings = settingsRepository.settings.firstOrNull() ?: return@launch
        val rules = identityRules.value
        val exists = settings.friends.any {
            rules.normalize(it.trim()) == rules.normalize(nick.trim())
        }
        settingsRepository.setFriend(nick, !exists, rules)
    }

    fun toggleFool(nick: String) = viewModelScope.launch {
        val settings = settingsRepository.settings.firstOrNull() ?: return@launch
        val rules = identityRules.value
        val exists = settings.fools.any {
            rules.normalize(it.trim()) == rules.normalize(nick.trim())
        }
        settingsRepository.setFool(nick, !exists, rules)
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

    fun saveDraft(text: String) {
        synchronized(draftStateLock) {
            draftTextEdited = true
            currentDraftText = text
            draftCommands.trySend(DraftCommand.Persist(advanceDraftRevisionLocked()))
        }
    }

    private suspend fun runDraftWriter() {
        val loaded = runCatching { draftStore.loadDraft(operationalBufferId.value) }.getOrNull()
        val loadedReply = loaded?.replyToEventId?.let {
            runCatching { messageRepository.byId(it) }.getOrNull()
        }
        val editedBeforeHydration: DraftSnapshot? = synchronized(draftStateLock) {
            val wasEdited = draftTextEdited || draftReplyEdited
            if (!draftTextEdited) currentDraftText = loaded?.text.orEmpty()
            if (!draftReplyEdited) {
                currentReplyToEventId = loadedReply?.id ?: loaded?.replyToEventId
                replyTo.value = loadedReply
            }
            val snapshot = advanceDraftRevisionLocked(hydrated = true)
            snapshot.takeIf { wasEdited }
        }
        editedBeforeHydration?.let {
            runCatching { draftStore.saveDraft(it.roomId, it.text, it.replyToEventId) }
        }
        draftHydrated.complete(Unit)

        for (command in draftCommands) {
            when (command) {
                is DraftCommand.Persist -> {
                    val current = synchronized(draftStateLock) {
                        _composerDraft.value.revision == command.snapshot.revision
                    }
                    if (current) {
                        runCatching {
                            draftStore.saveDraft(
                                command.snapshot.roomId,
                                command.snapshot.text,
                                command.snapshot.replyToEventId,
                            )
                        }
                    }
                }
                is DraftCommand.PrepareSubmission -> {
                    val unchanged = synchronized(draftStateLock) {
                        _composerDraft.value.revision == command.snapshot.revision
                    }
                    val persisted = if (unchanged) {
                        runCatching {
                            draftStore.saveDraft(
                                command.snapshot.roomId,
                                command.snapshot.text,
                                command.snapshot.replyToEventId,
                            )
                        }.getOrNull()
                    } else {
                        null
                    }
                    command.result.complete(persisted)
                }
                is DraftCommand.ClearSubmission -> {
                    val unchangedBeforeDelete = synchronized(draftStateLock) {
                        _composerDraft.value.revision == command.snapshot.revision
                    }
                    val deleted = unchangedBeforeDelete && runCatching {
                        draftStore.clearIfUnchanged(command.persisted)
                    }.getOrDefault(false)
                    val cleared = if (deleted) {
                        synchronized(draftStateLock) {
                            if (_composerDraft.value.revision != command.snapshot.revision) {
                                false
                            } else {
                                currentDraftText = ""
                                currentReplyToEventId = null
                                replyTo.value = null
                                advanceDraftRevisionLocked(hydrated = true)
                                true
                            }
                        }
                    } else {
                        false
                    }
                    command.result.complete(cleared)
                }
            }
        }
    }

    private fun advanceDraftRevisionLocked(hydrated: Boolean = _composerDraft.value.hydrated): DraftSnapshot {
        val revision = ++nextDraftRevision
        _composerDraft.value = ComposerDraftState(currentDraftText, hydrated, revision)
        return DraftSnapshot(
            roomId = operationalBufferId.value,
            text = currentDraftText,
            replyToEventId = currentReplyToEventId,
            revision = revision,
        )
    }

    /**
     * Reserve the currently rendered draft before any persistence or wire work begins.
     *
     * The composer owns edits through [saveDraft]. Treating a stale callback's [raw] text as a
     * new edit used to recreate a draft just after an accepted send, which turned one rapid UI
     * activation into multiple real IRC messages.
     */
    private suspend fun prepareDraftSubmission(raw: String): PreparedDraftSubmission? {
        draftHydrated.await()
        val result = CompletableDeferred<ComposerDraftEntity?>()
        val snapshot = synchronized(draftStateLock) {
            if (currentDraftText != raw) return@synchronized null
            val candidate = DraftSnapshot(
                roomId = operationalBufferId.value,
                text = currentDraftText,
                replyToEventId = currentReplyToEventId,
                revision = _composerDraft.value.revision,
            )
            val key = DraftSubmissionKey(candidate.roomId, candidate.revision)
            if (!inFlightDraftSubmissions.add(key)) return@synchronized null
            if (draftCommands.trySend(DraftCommand.PrepareSubmission(candidate, result)).isFailure) {
                inFlightDraftSubmissions.remove(key)
                return@synchronized null
            }
            candidate
        } ?: return null
        return try {
            PreparedDraftSubmission(snapshot, result.await())
        } catch (cancelled: CancellationException) {
            releaseDraftSubmission(snapshot)
            throw cancelled
        }
    }

    private fun releaseDraftSubmission(snapshot: DraftSnapshot) {
        synchronized(draftStateLock) {
            inFlightDraftSubmissions.remove(DraftSubmissionKey(snapshot.roomId, snapshot.revision))
        }
    }

    private suspend fun clearDraftSubmission(submission: PreparedDraftSubmission): Boolean {
        val persisted = submission.persisted ?: return false
        val result = CompletableDeferred<Boolean>()
        synchronized(draftStateLock) {
            draftCommands.trySend(
                DraftCommand.ClearSubmission(submission.snapshot, persisted, result),
            )
        }
        return result.await()
    }

    // --- search deep-jump (plans/11 §C) ---

    private val jumpMsgid: String? = route.jumpToMsgid
    private val jumpTime: Long = route.jumpToTime
    private val jumpEventId: Long? = route.jumpToEventId
    private class JumpRequest(
        val token: Long,
        val msgid: String?,
        val time: Long,
        val eventId: Long?,
        val settlesEntryPosition: Boolean,
    ) {
        var reresolveUsed: Boolean = false
    }

    private var nextJumpToken = 0L

    private var activeJumpRequest: JumpRequest? = if (
        jumpTime > 0 || jumpEventId != null || jumpMsgid != null
    ) {
        JumpRequest(++nextJumpToken, jumpMsgid, jumpTime, jumpEventId, settlesEntryPosition = true)
    } else {
        null
    }
    private var jumpResolveJob: Job? = null

    /**
     * CHATHISTORY AROUND fetch used by [ChatJumpResolver] when a msgid target is not yet local:
     * requires a live client with `draft/chathistory`, prefers the opaque msgid selector, and
     * persists the completed response and exact fallback request through the sole IRC→Room writer.
     */
    private val resolver = ChatJumpResolver(
        messages = messageRepository,
        fetchAround = fetch@ { name, msgid, timeMs, limit ->
            val buffer = state.value.buffer ?: return@fetch false
            val networkId = buffer.networkId
            val client = connectionManager.clientFor(networkId) ?: return@fetch false
            val availability = client.historyAvailability as? HistoryAvailability.Ready
                ?: return@fetch false
            try {
                fetchAroundHistoryPage(
                    target = name,
                    msgid = msgid,
                    timeMs = timeMs,
                    limit = limit,
                    availability = availability,
                    requestPage = client::chathistory,
                    persistPage = { request, response ->
                        eventSink.persistHistoryPage(
                            networkId,
                            request,
                            response,
                            expectedRoomId = buffer.id,
                        )
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        },
        countNewer = { targetBufferId, serverTime, id ->
            messageRepository.countNewerThan(
                targetBufferId,
                serverTime,
                id,
                filterSpecs.first(),
            )
        },
    )

    private val _jumpTarget = MutableStateFlow<ChatPositionTarget?>(null)
    /** Identity-bearing target; the screen acknowledges it only after placeholder validation. */
    val jumpTarget: StateFlow<ChatPositionTarget?> = _jumpTarget.asStateFlow()

    // Normal channel entry is also a one-shot position operation. Unlike a search deep-link it
    // has no highlight, but it must settle before read state can advance.
    private val _initialTarget = MutableStateFlow<ChatPositionTarget?>(null)
    val initialTarget: StateFlow<ChatPositionTarget?> = _initialTarget.asStateFlow()

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

    // Re-resolve is allowed exactly once per normal-entry target; explicit jump requests carry
    // their own guard so a superseded request cannot spend the newer request's retry.
    private var initialReresolveUsed = false

    init {
        val hasDeepJump = jumpTime > 0 || jumpEventId != null || jumpMsgid != null
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
            val realMarker = bufferRepository.observeBuffer(bufferId).firstOrNull()?.effectiveLocalReadAnchor
            _readMarkerSnapshot.value = if (realMarker == null) {
                null
            } else {
                visibilityReader.firstVisibleUnreadAnchor(bufferId, realMarker, entrySpec)
                    ?.let { TimelineAnchor(it.serverTime, it.eventId - 1L) }
            }
            // A deep-link owns positioning. A normal open restores this buffer's last in-memory
            // viewport when available; otherwise it remains at the newest row. The frozen unread
            // marker above still drives the divider and badge, but never repositions the window.
            if (!hasDeepJump && !_entryPositionSettled.value) {
                _initialTarget.value = restoredScrollPosition(entrySpec)
                    ?: ChatPositionTarget(index = 0)
            }
        }
        viewModelScope.launch {
            visibilityReader.observeEventRedirects().collect {
                restoreRedirectedViewport(filterSpec.value)
            }
        }
    }

    private suspend fun restoredScrollPosition(spec: MessageVisibilitySpec): ChatPositionTarget? {
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
        return ChatPositionTarget(
            index = index,
            offset = saved.offset.takeIf { anchor.id == canonicalSavedId } ?: 0,
            expectedEventId = anchor.id,
            expectedMsgid = anchor.msgid,
            serverTime = anchor.serverTime,
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
        initialReresolveUsed = false
        _initialTarget.value = ChatPositionTarget(
            index = index,
            offset = saved.offset,
            expectedEventId = anchor.id,
            expectedMsgid = anchor.msgid,
            serverTime = anchor.serverTime,
            fromSavedPosition = true,
        )
    }

    private fun resolveJump() {
        val request = activeJumpRequest ?: return
        jumpResolveJob?.cancel()
        // The buffer name (chathistory target) may not be in `state` yet on first composition;
        // read it directly from the repo so the AROUND fallback has a target.
        jumpResolveJob = viewModelScope.launch {
            val name = bufferRepository.observeBuffer(bufferId).firstOrNull()?.ircTarget
            publishResolve(name, request)
        }
    }

    private suspend fun publishResolve(name: String?, request: JumpRequest) {
        if (activeJumpRequest?.token != request.token) return
        when (val r = resolver.resolve(
            bufferId,
            request.msgid,
            request.time,
            name,
            eventId = request.eventId,
        )) {
            is ChatJumpResolver.Result.Resolved -> {
                if (activeJumpRequest?.token != request.token) return
                // Force a distinct emission so the screen's LaunchedEffect(jumpTarget) always
                // re-runs, even when the re-resolved index equals the previous one (plans/15 #12).
                _jumpTarget.value = null
                _jumpTarget.value = r.target.copy(requestToken = request.token)
            }
            ChatJumpResolver.Result.NotFound -> {
                if (activeJumpRequest?.token != request.token) return
                _jumpTarget.value = null
                failActiveJump(request)
            }
        }
    }

    /** Screen calls this after it has scrolled to (or given up on) the current target. */
    fun onJumpHandled(token: Long) {
        val request = activeJumpRequest?.takeIf { it.token == token } ?: return
        val settlesEntryPosition = request.settlesEntryPosition
        jumpResolveJob?.cancel()
        jumpResolveJob = null
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
        if (!_entryPositionSettled.value) markEntryPositionUnresolved()
    }

    fun onJumpUnresolved(token: Long) {
        val request = activeJumpRequest?.takeIf { it.token == token } ?: return
        _jumpTarget.value = null
        failActiveJump(request)
    }

    /**
     * Resolve and reveal a locally available replied-to message. Reply previews are only clickable
     * after their target has resolved from Room, so this normally remains a local index lookup; the
     * shared jump pipeline still supplies bounded paging, index-shift recovery, and highlighting.
     */
    fun jumpToRepliedMessage(msgid: String) {
        val settlesEntryPosition = !_entryPositionSettled.value && !_entryPositionUnresolved.value
        val request = JumpRequest(
            token = ++nextJumpToken,
            msgid = msgid,
            time = 0,
            eventId = null,
            settlesEntryPosition = settlesEntryPosition,
        )
        jumpResolveJob?.cancel()
        if (settlesEntryPosition) _initialTarget.value = null
        activeJumpRequest = request
        _jumpTarget.value = null
        jumpResolveJob = viewModelScope.launch {
            publishResolve(state.value.buffer?.ircTarget, request)
        }
    }

    fun retryReplyJump(request: ReplyJumpRequest) {
        jumpToRepliedMessage(request.msgid)
    }

    private fun failActiveJump(request: JumpRequest) {
        if (activeJumpRequest?.token != request.token) return
        jumpResolveJob = null
        activeJumpRequest = null
        if (request.settlesEntryPosition) {
            markEntryPositionUnresolved()
        } else {
            request.msgid?.let { msgid ->
                uiEventQueue.enqueue(ChatUiEvent.ReplyJumpUnavailable(ReplyJumpRequest(msgid)))
            }
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

    /**
     * Re-resolve the same target once when a live message shifted indices mid-jump. The single-shot
     * guard means the screen can always call [onJumpHandled] after it; a repeat request just clears
     * the target so the not-loaded path takes over (plans/15 #12).
     */
    fun reresolveJumpOnce(token: Long) {
        val request = activeJumpRequest?.takeIf { it.token == token } ?: return
        if (request.reresolveUsed) {
            _jumpTarget.value = null
            failActiveJump(request)
            return
        }
        request.reresolveUsed = true
        jumpResolveJob?.cancel()
        jumpResolveJob = viewModelScope.launch {
            publishResolve(state.value.buffer?.ircTarget, request)
        }
    }

    /** Saved positions use the same exact one-shot index repair as explicit jumps. */
    fun reresolveInitialOnce(target: ChatPositionTarget) = viewModelScope.launch {
        if (initialReresolveUsed) {
            onInitialPositionUnresolved()
            return@launch
        }
        initialReresolveUsed = true
        when (val result = resolver.resolve(
            bufferId = bufferId,
            msgid = target.expectedMsgid,
            timeMs = target.serverTime,
            bufferName = null,
            eventId = target.expectedEventId,
        )) {
            is ChatJumpResolver.Result.Resolved -> {
                _initialTarget.value = null
                _initialTarget.value = result.target.copy(
                    offset = target.offset,
                    highlightMsgid = null,
                    fromSavedPosition = target.fromSavedPosition,
                )
            }
            ChatJumpResolver.Result.NotFound -> onInitialPositionUnresolved()
        }
    }

    /**
     * Isupport-normalized nick folding for autocomplete; lowercase fallback when no live client.
     */
    fun nickNormalizer(): (String) -> String {
        val rules = identityRules.value
        return rules::normalize
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
