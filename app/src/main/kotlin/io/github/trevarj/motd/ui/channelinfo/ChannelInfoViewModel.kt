package io.github.trevarj.motd.ui.channelinfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.attachment.sojuFileHostAdvertised
import io.github.trevarj.motd.avatar.AvatarController
import io.github.trevarj.motd.avatar.ConversationAvatarOutcome
import io.github.trevarj.motd.avatar.NoopAvatarController
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.JoinedChannelRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.db.NetworkIdentityDao
import io.github.trevarj.motd.data.db.UserDao
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.matchesConfiguredNick
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkIgnoreRepository
import io.github.trevarj.motd.data.repo.NoopNetworkIgnoreRepository
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.service.ChannelWatch
import io.github.trevarj.motd.service.ChannelWatchDuration
import io.github.trevarj.motd.service.ChannelWatchState
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.RosterLoadState
import io.github.trevarj.motd.service.isForever
import io.github.trevarj.motd.ui.chat.ComposerDraftStore
import io.github.trevarj.motd.ui.chat.NickSheetState
import io.github.trevarj.motd.ui.chat.WhoisInfo
import io.github.trevarj.motd.ui.chat.parseWhois
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChannelInfoUiState(
    val buffer: BufferEntity? = null,
    val sections: List<MemberSection> = emptyList(),
    val memberNicks: List<String> = emptyList(),
    val memberCount: Int? = null,
    val rosterState: RosterLoadState = RosterLoadState.NOT_LOADED,
    val hasStaleMembers: Boolean = false,
    // Round 4: global friend/fool sets. Fools are pulled into their own section.
    val foolMembers: List<MemberEntity> = emptyList(),
    val friends: Set<String> = emptySet(),
    val fools: Set<String> = emptySet(),
    val identityRules: IrcIdentityRules = IrcIdentityRules(),
    // Round 5: true when the viewer holds op in this channel (moderation gate).
    val canModerate: Boolean = false,
    // Fuzzy member search. When [searchResults] is non-null the list renders a flat ranked set
    // instead of the prefix sections; null (query blank) means sectioned mode.
    val query: String = "",
    val searchResults: List<MemberEntity>? = null,
    // Network latency for this channel's network (#34); null until the first PONG completes or
    // while disconnected. Surfaced subtly in Channel Info rather than the chat header.
    val lagMs: Long? = null,
    val connected: Boolean = false,
    val selfNick: String? = null,
    val sojuFileHostAvailable: Boolean = false,
    /**
     * ISUPPORT-derived mode vocabulary for this network, null until the connection is Ready. It
     * says which modes the server *advertises*, never which modes are currently set: nothing in
     * the app parses 324/367, so no control built on this may claim current channel state.
     */
    val modeCatalog: ModeCatalog? = null,
    val joinedChannels: List<JoinedChannelRow> = emptyList(),
)

/** What a channel tool did, for the screen to phrase. The ViewModel owns no user-facing wording. */
enum class ChannelToolSummary { MODE, BAN, UNBAN, EXCEPTION }

/** One-shot feedback for an operator action, collected into the Channel Info snackbar. */
data class QueuedChannelToolEvent(
    val id: Long,
    val event: ChannelToolEvent,
)

sealed interface ChannelToolEvent {
    data class Sent(
        val summary: ChannelToolSummary,
        val arg: String? = null,
    ) : ChannelToolEvent

    data class InviteRequestSent(
        val nick: String,
        val channel: String,
    ) : ChannelToolEvent

    data object InviteSendFailed : ChannelToolEvent

    /** The network has no live client, so nothing was written. Never fail silently. */
    data object NotConnected : ChannelToolEvent
}

/** Per-network runtime facts folded into one value so the state combine stays within its arity. */
internal data class NetworkRuntime(
    val lagMs: Long?,
    val connected: Boolean,
    val isupport: Map<String, String>?,
    val selfNick: String?,
)

/** Local write acceptance, distinct from a later server echo or numeric rejection. */
sealed interface TopicMutationState {
    data object Idle : TopicMutationState

    data object Submitting : TopicMutationState

    data object Accepted : TopicMutationState

    data object Failed : TopicMutationState
}

/** Local PART write acceptance, distinct from a later self-PART echo or server rejection. */
sealed interface LeaveMutationState {
    data object Idle : LeaveMutationState

    data object Submitting : LeaveMutationState

    data object Failed : LeaveMutationState
}

/** One-shot screen effects emitted only after a local operation is accepted. */
sealed interface ChannelInfoOperationEvent {
    data object LeaveAccepted : ChannelInfoOperationEvent
}

/** What the channel-info notifications row reports. */
sealed interface ChannelNotifyLevel {
    data object MentionsOnly : ChannelNotifyLevel

    data object Muted : ChannelNotifyLevel

    /** [minutesLeft] null means the watch never expires. */
    data class All(
        val minutesLeft: Int?,
        val overridesMute: Boolean,
    ) : ChannelNotifyLevel
}

internal const val NOTIFY_LEVEL_TICK_MS = 30_000L

/** A live watch on this buffer wins over the mute; remaining time rounds up to a whole minute. */
internal fun deriveNotifyLevel(
    muted: Boolean,
    watch: ChannelWatchState?,
    bufferId: Long,
    nowMillis: Long,
): ChannelNotifyLevel {
    val active = watch?.takeIf { it.bufferId == bufferId && it.expiresAt > nowMillis }
    return when {
        active != null -> {
            ChannelNotifyLevel.All(
                minutesLeft = if (active.isForever) null else ceilMinutes(active.expiresAt - nowMillis),
                overridesMute = muted,
            )
        }

        muted -> {
            ChannelNotifyLevel.Muted
        }

        else -> {
            ChannelNotifyLevel.MentionsOnly
        }
    }
}

private fun ceilMinutes(remainingMs: Long): Int = ((remainingMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)

internal data class RosterPresentation(
    val memberCount: Int?,
    val hasStaleMembers: Boolean,
)

internal fun rosterPresentation(
    cachedCount: Int,
    state: RosterLoadState,
): RosterPresentation =
    RosterPresentation(
        memberCount = cachedCount.takeIf { state == RosterLoadState.LOADED },
        hasStaleMembers = cachedCount > 0 && state != RosterLoadState.LOADED,
    )

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChannelInfoViewModel
    @Inject
    constructor(
        private val bufferRepository: BufferRepository,
        private val connectionManager: ConnectionManager,
        private val draftStore: ComposerDraftStore,
        private val settingsRepository: SettingsRepository,
        private val userDao: UserDao,
        private val networkIdentityDao: NetworkIdentityDao,
        private val networkIgnoreRepository: NetworkIgnoreRepository = NoopNetworkIgnoreRepository,
        private val avatarController: AvatarController = NoopAvatarController,
        private val channelWatch: ChannelWatch = ChannelWatch.Noop,
        private val clock: AppClock = AppClock(System::currentTimeMillis),
    ) : ViewModel() {
        private val bufferIdFlow = MutableStateFlow<Long?>(null)
        val presenceStates = connectionManager.presenceStates
        private val _topicMutation = MutableStateFlow<TopicMutationState>(TopicMutationState.Idle)
        val topicMutation: StateFlow<TopicMutationState> = _topicMutation
        private val _leaveMutation = MutableStateFlow<LeaveMutationState>(LeaveMutationState.Idle)
        val leaveMutation: StateFlow<LeaveMutationState> = _leaveMutation
        private val _operationEvents = MutableSharedFlow<ChannelInfoOperationEvent>(extraBufferCapacity = 1)
        val operationEvents: SharedFlow<ChannelInfoOperationEvent> = _operationEvents.asSharedFlow()

        // One-shot: unmuting marked the muted backlog read, so the screen can report it and offer an undo.
        private val _muteBacklogSuppressions = MutableSharedFlow<MuteBacklogSuppression>(extraBufferCapacity = 1)
        val muteBacklogSuppressions: SharedFlow<MuteBacklogSuppression> = _muteBacklogSuppressions.asSharedFlow()

        fun init(bufferId: Long) {
            bufferIdFlow.value = bufferId
            viewModelScope.launch { connectionManager.requestMembers(bufferId) }
        }

        private val bufferFlow =
            bufferIdFlow.flatMapLatest { id ->
                if (id == null) flowOf(null) else bufferRepository.observeBuffer(id)
            }

        private val membersFlow =
            bufferIdFlow.flatMapLatest { id ->
                if (id == null) flowOf(emptyList<MemberEntity>()) else bufferRepository.observeMembers(id)
            }

        // Fuzzy member search input. The visible query lives in the screen's local IME state; this
        // flow mirrors it so the sections/search-results re-derive without a network fetch.
        private val queryFlow = MutableStateFlow("")

        fun setQuery(query: String) {
            queryFlow.value = query
        }

        // Per-nick last-spoke time in this channel (PRIVMSG/NOTICE/ACTION, isSelf=0). Keyed by the
        // normalized actor stored on messages; looked up via identityRules.normalize(member.nick).
        private val lastSpokeFlow =
            bufferIdFlow.flatMapLatest { id ->
                if (id == null) flowOf(emptyMap<String, Long>()) else bufferRepository.observeLastSpokeByNick(id)
            }

        private val identityRulesFlow =
            bufferFlow.flatMapLatest { buffer ->
                if (buffer == null) {
                    flowOf(IrcIdentityRules())
                } else {
                    networkIdentityDao.observe(buffer.networkId).map { it?.identityRules ?: IrcIdentityRules() }
                }
            }

        // Gather the per-roster inputs that don't depend on [bufferFlow]'s prefix order: lastSpoke,
        // the search query, friend/fool sets, and the identity rules. Sectioning/ranking happen in the
        // outer combine where [order] (derived from buffer) is available.
        private val joinedChannelsFlow =
            bufferFlow.flatMapLatest { buffer ->
                buffer?.let { bufferRepository.observeJoinedChannels(it.networkId) } ?: flowOf(emptyList())
            }

        private data class DerivedRoster(
            val lastSpoke: Map<String, Long>,
            val query: String,
            val friends: Set<String>,
            val fools: Set<String>,
            val identityRules: IrcIdentityRules,
            val joinedChannels: List<JoinedChannelRow>,
        )

        private val derivedRosterFlow =
            combine(
                lastSpokeFlow,
                queryFlow,
                settingsRepository.settings,
                identityRulesFlow,
                joinedChannelsFlow,
            ) { lastSpoke, query, settings, identityRules, joinedChannels ->
                DerivedRoster(lastSpoke, query, settings.friends, settings.fools, identityRules, joinedChannels)
            }

        // Latency, Ready flag and the live ISUPPORT snapshot for this channel's network. Reading
        // ISUPPORT from Ready (rather than a clientFor snapshot) makes every server-derived control
        // recompute on each 005 batch, and re-emits on connect/disconnect so the op gate follows too.
        private val networkRuntimeFlow =
            bufferFlow.flatMapLatest { buffer ->
                if (buffer == null) {
                    flowOf(NetworkRuntime(null, connected = false, isupport = null, selfNick = null))
                } else {
                    connectionManager.lagStates
                        .combine(connectionManager.connectionStates) { lags, states ->
                            val ready = states[buffer.networkId] as? IrcClientState.Ready
                            NetworkRuntime(lags[buffer.networkId], ready != null, ready?.isupport, ready?.nick)
                        }
                }
            }

        val state: StateFlow<ChannelInfoUiState> =
            combine(
                bufferFlow,
                membersFlow,
                derivedRosterFlow,
                connectionManager.rosterStates,
                networkRuntimeFlow,
            ) { buffer, members, derived, rosterStates, runtime ->
                val modeCatalog = runtime.isupport?.let(ModeCatalog::from)
                // Prefer the reactive catalog's PREFIX over the clientFor snapshot when Ready.
                val order =
                    modeCatalog
                        ?.let { catalog -> prefixOrderFrom(catalog.prefixRoles.map { it.mode to it.glyph }) }
                        ?: prefixOrderForBuffer(buffer)
                val identityRules = derived.identityRules
                val lookup: (MemberEntity) -> Long? = { derived.lastSpoke[identityRules.normalize(it.nick)] }
                val sections: List<MemberSection>
                val foolMembers: List<MemberEntity>
                val searchResults: List<MemberEntity>?
                if (derived.query.isBlank()) {
                    val social =
                        sectionMembersSocial(
                            members,
                            order,
                            derived.fools,
                            identityRules,
                            comparator = activityMemberComparator(identityRules, lookup),
                        )
                    sections = social.sections
                    foolMembers = social.fools
                    searchResults = null
                } else {
                    sections = emptyList()
                    foolMembers = emptyList()
                    searchResults = rankMembersFuzzy(derived.query, members, identityRules::normalize, lookup)
                }
                val rosterState = buffer?.let { rosterStates[it.id] } ?: RosterLoadState.NOT_LOADED
                val presentation = rosterPresentation(members.size, rosterState)
                ChannelInfoUiState(
                    buffer = buffer,
                    sections = sections,
                    memberNicks = members.map(MemberEntity::nick),
                    memberCount = presentation.memberCount,
                    rosterState = rosterState,
                    hasStaleMembers = presentation.hasStaleMembers,
                    foolMembers = foolMembers,
                    friends = derived.friends,
                    fools = derived.fools,
                    identityRules = identityRules,
                    canModerate = viewerCanModerate(buffer, members, order),
                    query = derived.query,
                    searchResults = searchResults,
                    lagMs = runtime.lagMs,
                    connected = runtime.connected,
                    selfNick = runtime.selfNick,
                    sojuFileHostAvailable = runtime.isupport?.let(::sojuFileHostAdvertised) == true,
                    modeCatalog = modeCatalog,
                    joinedChannels = derived.joinedChannels,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ChannelInfoUiState(),
            )

        /**
         * Notification level for this channel: an active watch outranks the buffer mute, which
         * outranks the default mentions-only. Re-emits on a tick so the remaining minutes stay live.
         */
        val notifyLevel: StateFlow<ChannelNotifyLevel> =
            combine(bufferFlow, channelWatch.state) { buffer, watch ->
                Triple(buffer?.id, buffer?.muted == true, watch)
            }.flatMapLatest { (id, muted, watch) ->
                val bufferId = id
                if (bufferId == null) {
                    flowOf<ChannelNotifyLevel>(ChannelNotifyLevel.MentionsOnly)
                } else {
                    flow {
                        while (true) {
                            val level = deriveNotifyLevel(muted, watch, bufferId, clock.nowMillis())
                            emit(level)
                            if (level !is ChannelNotifyLevel.All || level.minutesLeft == null) break
                            delay(NOTIFY_LEVEL_TICK_MS)
                        }
                    }
                }
            }.distinctUntilChanged()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = ChannelNotifyLevel.MentionsOnly,
                )

        fun startWatch(duration: ChannelWatchDuration) =
            viewModelScope.launch {
                state.value.buffer?.let { channelWatch.start(it.id, duration.millis) }
            }

        fun stopWatch() = viewModelScope.launch { channelWatch.stop() }

        fun retryMembers() =
            viewModelScope.launch {
                state.value.buffer?.let { connectionManager.requestMembers(it.id, force = true) }
            }

        // Resolve prefix order from the live client's ISUPPORT when connected; fallback otherwise.
        private fun prefixOrderForBuffer(buffer: BufferEntity?): String {
            val networkId = buffer?.networkId ?: return DEFAULT_PREFIX_ORDER
            val client = connectionManager.clientFor(networkId) ?: return DEFAULT_PREFIX_ORDER
            return prefixOrderFrom(client.isupport.prefixModes)
        }

        fun setPinned(pinned: Boolean) =
            viewModelScope.launch {
                state.value.buffer?.let { bufferRepository.setPinned(it.id, pinned) }
            }

        fun setMuted(muted: Boolean) =
            viewModelScope.launch {
                state.value.buffer
                    ?.let { bufferRepository.setMuted(it.id, muted) }
                    ?.let { _muteBacklogSuppressions.emit(it) }
            }

        private val _avatarEvents = MutableSharedFlow<ConversationAvatarOutcome>(extraBufferCapacity = 1)
        val avatarEvents: SharedFlow<ConversationAvatarOutcome> = _avatarEvents.asSharedFlow()

        fun importAvatar(uri: android.net.Uri) = avatarAction { avatarController.importConversationAvatar(it, uri) }

        fun setAvatarUrl(url: String) = avatarAction { avatarController.setConversationAvatar(it, url) }

        fun resetAvatar() = avatarAction(avatarController::resetConversationAvatar)

        fun shareAvatar() = avatarAction(avatarController::shareConversationAvatar)

        fun clearSharedAvatar() = avatarAction(avatarController::clearSharedConversationAvatar)

        private fun avatarAction(action: suspend (Long) -> ConversationAvatarOutcome) =
            viewModelScope.launch {
                val roomId = state.value.buffer?.id ?: return@launch
                _avatarEvents.emit(action(roomId))
            }

        /** Put back the mute backlog floor an unmute advanced past (snackbar undo). */
        fun undoMuteBacklogSuppression(suppression: MuteBacklogSuppression) =
            viewModelScope.launch {
                bufferRepository.restoreMuteBacklog(suppression)
            }

        /** Reset a previous local PART failure before showing the leave confirmation. */
        fun beginLeave() {
            _leaveMutation.value = LeaveMutationState.Idle
        }

        /**
         * Leave only after the live transport accepts PART. This is deliberately not durable: a
         * later server rejection still needs labeled-response correlation (or the self-PART echo).
         */
        fun part() {
            if (_leaveMutation.value is LeaveMutationState.Submitting) return
            val bufferId = bufferIdFlow.value
            if (bufferId == null) {
                _leaveMutation.value = LeaveMutationState.Failed
                return
            }
            _leaveMutation.value = LeaveMutationState.Submitting
            viewModelScope.launch {
                val accepted =
                    try {
                        connectionManager.partChannelForClose(bufferId)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                if (accepted) {
                    _leaveMutation.value = LeaveMutationState.Idle
                    _operationEvents.emit(ChannelInfoOperationEvent.LeaveAccepted)
                } else {
                    _leaveMutation.value = LeaveMutationState.Failed
                }
            }
        }

        /** Open (or create) a DM with [nick], then hand the buffer id to [onOpen]. */
        fun messageMember(
            nick: String,
            onOpen: (Long) -> Unit,
        ) = viewModelScope.launch {
            val networkId = state.value.buffer?.networkId ?: return@launch
            val bufferId = connectionManager.ensureQueryBuffer(networkId, nick)
            onOpen(bufferId)
        }

        /**
         * Queue a "$nick: " prefill on the current buffer's composer draft, then [onDone] (pops back
         * to the chat). ChatScreen reads it via [ComposerDraftStore.consume] on re-entry.
         */
        fun mentionMember(
            nick: String,
            onDone: () -> Unit,
        ) {
            state.value.buffer?.let { draftStore.push(it.id, "$nick: ") }
            onDone()
        }

        /** Toggle [nick]'s friend membership (adding removes it from fools; see SettingsRepository). */
        fun toggleFriend(nick: String) =
            viewModelScope.launch {
                val current = state.value
                settingsRepository.setFriend(
                    nick,
                    !current.identityRules.matchesConfiguredNick(nick, current.friends),
                    current.identityRules,
                )
            }

        /** Toggle [nick]'s fool membership (adding removes it from friends). */
        fun toggleFool(nick: String) =
            viewModelScope.launch {
                val current = state.value
                settingsRepository.setFool(
                    nick,
                    !current.identityRules.matchesConfiguredNick(nick, current.fools),
                    current.identityRules,
                )
            }

        fun ignoreNickOnNetwork(nick: String) =
            viewModelScope.launch {
                val networkId = state.value.buffer?.networkId ?: return@launch
                networkIgnoreRepository.addIgnore(networkId, nick)
                dismissNickSheet()
            }

        // --- nick sheet + whois ---

        private val _nickSheet = MutableStateFlow<NickSheetState?>(null)
        val nickSheet: StateFlow<NickSheetState?> = _nickSheet
        private var nickDetailsJob: Job? = null

        /** Open the nick sheet for [nick]; WHOIS via labeled-response when available (see ChatViewModel). */
        fun openNickSheet(nick: String) {
            _nickSheet.value = NickSheetState(nick = nick)
            val networkId = state.value.buffer?.networkId ?: return
            viewModelScope.launch { state.value.buffer?.let { connectionManager.requestMembers(it.id) } }
            val client = connectionManager.clientFor(networkId)
            val normalized = client?.isupport?.normalize(nick) ?: state.value.identityRules.normalize(nick)
            nickDetailsJob?.cancel()
            nickDetailsJob =
                viewModelScope.launch {
                    combine(
                        userDao.observeByNick(networkId, normalized),
                        connectionManager.presenceStates,
                    ) { cached, presence ->
                        cached to
                            presence[
                                io.github.trevarj.motd.service
                                    .PresenceKey(networkId, normalized),
                            ]
                    }.collect { (cached, presence) ->
                        val current = _nickSheet.value
                        if (current?.nick == nick) _nickSheet.value = current.copy(cached = cached, presence = presence)
                    }
                }
            if (client == null) return
            val whoisMsg = IrcMessage(command = "WHOIS", params = listOf(nick))
            if (client.hasCap("labeled-response")) {
                viewModelScope.launch {
                    val lines = runCatching { client.sendLabeled(whoisMsg) }.getOrNull().orEmpty()
                    val info: WhoisInfo? = parseWhois(lines)
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

        // --- moderation executors ---

        private val _toolEvents = MutableSharedFlow<ChannelToolEvent>(extraBufferCapacity = 4)
        val toolEvents: SharedFlow<ChannelToolEvent> = _toolEvents.asSharedFlow()
        private val _inviteFeedback = MutableStateFlow<QueuedChannelToolEvent?>(null)
        val inviteFeedback: StateFlow<QueuedChannelToolEvent?> = _inviteFeedback
        private var nextInviteFeedbackId = 0L

        fun acknowledgeInviteFeedback(id: Long) {
            if (_inviteFeedback.value?.id == id) _inviteFeedback.value = null
        }

        /**
         * Single write path for every operator action: resolves the live client, reports
         * [ChannelToolEvent.NotConnected] instead of silently doing nothing when there isn't one, and
         * emits a [summary] the screen turns into snackbar copy.
         */
        private fun dispatch(
            summary: ChannelToolSummary,
            arg: String? = null,
            build: (target: String) -> List<IrcMessage>,
        ) = viewModelScope.launch {
            val buffer = state.value.buffer ?: return@launch
            val messages = build(buffer.ircTarget).takeIf { it.isNotEmpty() } ?: return@launch
            val client = connectionManager.clientFor(buffer.networkId)
            if (client == null) {
                _toolEvents.emit(ChannelToolEvent.NotConnected)
                return@launch
            }
            messages.forEach { client.send(it) }
            _toolEvents.emit(ChannelToolEvent.Sent(summary, arg))
        }

        private fun modeMessage(
            target: String,
            flag: String,
            vararg args: String,
        ) = IrcMessage(command = "MODE", params = listOf(target, flag) + args)

        /** MODE <channel> +o/-o/+v/-v <nick>. */
        fun setMemberMode(
            nick: String,
            mode: Char,
            grant: Boolean,
        ) = dispatch(ChannelToolSummary.MODE) { target ->
            listOf(modeMessage(target, (if (grant) "+" else "-") + mode, nick))
        }

        /** MODE <channel> +x/-x for an argument-less flag mode. */
        fun setFlagMode(
            letter: Char,
            enable: Boolean,
        ) = dispatch(ChannelToolSummary.MODE) { target ->
            listOf(modeMessage(target, (if (enable) "+" else "-") + letter))
        }

        /**
         * MODE <channel> +k <key>, or -k * to clear it. RFC 2812 wants the current key as the argument
         * when unsetting and the app never learns it (no 324 handling), so it sends the wildcard every
         * server in practice accepts.
         */
        fun setKey(key: String?) =
            dispatch(ChannelToolSummary.MODE) { target ->
                val trimmed = key?.trim()
                if (trimmed.isNullOrBlank()) {
                    listOf(modeMessage(target, "-k", "*"))
                } else {
                    listOf(modeMessage(target, "+k", trimmed))
                }
            }

        /** MODE <channel> +l <n>, or -l to remove the cap. Unsetting takes no argument. */
        fun setLimit(limit: Int?) =
            dispatch(ChannelToolSummary.MODE) { target ->
                if (limit == null || limit < 1) {
                    listOf(modeMessage(target, "-l"))
                } else {
                    listOf(modeMessage(target, "+l", limit.toString()))
                }
            }

        /** KICK <channel> <nick> [:reason]. */
        fun kick(
            nick: String,
            reason: String?,
        ) = dispatch(ChannelToolSummary.MODE) { target ->
            val params =
                if (reason.isNullOrBlank()) {
                    listOf(target, nick)
                } else {
                    listOf(target, nick, reason)
                }
            listOf(IrcMessage(command = "KICK", params = params))
        }

        /** MODE <channel> +b <banMask(nick)>. Kept for /ban parity with the command parser. */
        fun ban(nick: String) =
            dispatch(ChannelToolSummary.BAN, arg = nick) { target ->
                listOf(modeMessage(target, "+b", banMask(nick)))
            }

        /** Ban [mask], optionally kicking [nick] out in the same action. */
        fun banWithMask(
            nick: String?,
            mask: String,
            alsoKick: Boolean,
        ) {
            val trimmed = mask.trim().takeIf(String::isNotBlank) ?: return
            dispatch(ChannelToolSummary.BAN, arg = trimmed) { target ->
                buildList {
                    add(modeMessage(target, "+b", trimmed))
                    if (alsoKick && !nick.isNullOrBlank()) {
                        add(IrcMessage(command = "KICK", params = listOf(target, nick)))
                    }
                }
            }
        }

        /**
         * MODE <channel> +/-<letter> <mask> for a list mode. [letter] comes from the catalog (EXCEPTS
         * and INVEX advertise their own letter) rather than being hardcoded to e/I.
         */
        fun setListMask(
            letter: Char,
            mask: String,
            grant: Boolean,
        ) {
            val trimmed = mask.trim().takeIf(String::isNotBlank) ?: return
            val summary =
                when {
                    letter != 'b' -> ChannelToolSummary.EXCEPTION
                    grant -> ChannelToolSummary.BAN
                    else -> ChannelToolSummary.UNBAN
                }
            dispatch(summary, arg = trimmed) { target ->
                listOf(modeMessage(target, (if (grant) "+" else "-") + letter, trimmed))
            }
        }

        fun setBanMask(
            mask: String,
            grant: Boolean,
        ) = setListMask('b', mask, grant)

        fun invite(
            channel: JoinedChannelRow,
            nick: String,
            onResult: (Boolean) -> Unit = {},
        ) = viewModelScope.launch {
            val accepted =
                try {
                    connectionManager.inviteToChannel(channel.bufferId, nick)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            val event =
                if (accepted) {
                    ChannelToolEvent.InviteRequestSent(nick.trim(), channel.displayName)
                } else {
                    ChannelToolEvent.InviteSendFailed
                }
            _inviteFeedback.value = QueuedChannelToolEvent(++nextInviteFeedbackId, event)
            onResult(accepted)
        }

        fun setChannelMode(
            modes: String,
            args: String,
        ) {
            val trimmedModes = modes.trim().takeIf(String::isNotBlank) ?: return
            dispatch(ChannelToolSummary.MODE) { target ->
                val params =
                    listOf(target, trimmedModes) +
                        args.split(' ').map(String::trim).filter(String::isNotBlank)
                listOf(IrcMessage(command = "MODE", params = params))
            }
        }

        // --- ban-target host resolution ---

        private val _resolvedHost = MutableStateFlow<String?>(null)

        /** Address for the ban picker's currently selected nick, null until one is known. */
        val resolvedHost: StateFlow<String?> = _resolvedHost

        private val _resolvingHost = MutableStateFlow(false)

        /** True while a lookup is in flight, so the picker can say "looking up" instead of "unknown". */
        val resolvingHost: StateFlow<Boolean> = _resolvingHost
        private var hostJob: Job? = null
        private var hostNick: String? = null

        /** Point the host lookup at [nick]; null clears it (dialog closed or custom mask selected). */
        fun resolveHostFor(nick: String?) {
            if (hostNick == nick) return
            hostJob?.cancel()
            hostNick = nick
            _resolvedHost.value = null
            _resolvingHost.value = nick != null
            if (nick == null) return
            hostJob =
                viewModelScope.launch {
                    val host = resolveHost(nick)
                    // A newer selection has already taken over; don't clobber its result.
                    if (hostNick == nick) {
                        _resolvedHost.value = host
                        _resolvingHost.value = false
                    }
                }
        }

        /**
         * Address for [nick], cheapest source first: the WHOIS already on screen, then the cached
         * `user@host` in Room, then a labeled WHOIS. Null when none of them knows.
         */
        private suspend fun resolveHost(nick: String): String? {
            val networkId = state.value.buffer?.networkId ?: return null
            val client = connectionManager.clientFor(networkId)
            val normalize: (String) -> String =
                { client?.isupport?.normalize(it) ?: state.value.identityRules.normalize(it) }
            _nickSheet.value
                ?.takeIf { normalize(it.nick) == normalize(nick) }
                ?.details
                ?.host
                ?.takeIf(String::isNotBlank)
                ?.let { return it }
            hostFromUserHost(userDao.byNick(networkId, normalize(nick))?.hostmask)?.let { return it }
            if (client == null || !client.hasCap("labeled-response")) return null
            val lines =
                try {
                    client.sendLabeled(IrcMessage(command = "WHOIS", params = listOf(nick)))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return null
                }
            return parseWhois(lines)?.host?.takeIf(String::isNotBlank)
        }

        /** Reset a previous local result before opening the editor again. */
        fun beginTopicEdit() {
            _topicMutation.value = TopicMutationState.Idle
        }

        /**
         * Set the channel topic. Acceptance only means a Ready client wrote TOPIC to its live
         * transport; a later TopicChanged echo owns the Room update and numeric 482 stays separate.
         */
        fun setTopic(topic: String) {
            if (_topicMutation.value is TopicMutationState.Submitting) return
            val bufferId = bufferIdFlow.value
            if (bufferId == null) {
                _topicMutation.value = TopicMutationState.Failed
                return
            }
            _topicMutation.value = TopicMutationState.Submitting
            viewModelScope.launch {
                val accepted =
                    try {
                        connectionManager.setChannelTopic(bufferId, topic)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                _topicMutation.value =
                    if (accepted) {
                        TopicMutationState.Accepted
                    } else {
                        TopicMutationState.Failed
                    }
            }
        }

        /**
         * True when the viewer's own member row holds op-or-above in this CHANNEL buffer (Confirmed #7).
         * Self nick comes from the live client's Ready state; prefix order from ISUPPORT.
         */
        private fun viewerCanModerate(
            buffer: BufferEntity?,
            members: List<MemberEntity>,
            prefixOrder: String,
        ): Boolean {
            if (buffer?.type != BufferType.CHANNEL) return false
            val client = connectionManager.clientFor(buffer.networkId) ?: return false
            val myNick =
                (connectionManager.connectionStates.value[buffer.networkId] as? IrcClientState.Ready)?.nick
                    ?: return false
            val normalize: (String) -> String = { client.isupport.normalize(it) }
            val me = members.firstOrNull { normalize(it.nick) == normalize(myNick) } ?: return false
            return canModerate(me.prefixes, prefixOrder)
        }
    }
