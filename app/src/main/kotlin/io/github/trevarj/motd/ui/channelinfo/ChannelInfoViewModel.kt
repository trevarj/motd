package io.github.trevarj.motd.ui.channelinfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.UserDao
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.RosterLoadState
import io.github.trevarj.motd.ui.chat.ComposerDraftStore
import io.github.trevarj.motd.ui.chat.NickSheetState
import io.github.trevarj.motd.ui.chat.WhoisInfo
import io.github.trevarj.motd.ui.chat.parseWhois
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import javax.inject.Inject

data class ChannelInfoUiState(
    val buffer: BufferEntity? = null,
    val sections: List<MemberSection> = emptyList(),
    val memberCount: Int? = null,
    val rosterState: RosterLoadState = RosterLoadState.NOT_LOADED,
    val hasStaleMembers: Boolean = false,
    // Round 4 (plans/13 §3.6): global friend/fool sets. Fools are pulled into their own section.
    val foolMembers: List<MemberEntity> = emptyList(),
    val friends: Set<String> = emptySet(),
    val fools: Set<String> = emptySet(),
    // Round 5 (plans/16 §5.8): true when the viewer holds op in this channel (moderation gate).
    val canModerate: Boolean = false,
)

internal data class RosterPresentation(val memberCount: Int?, val hasStaleMembers: Boolean)

internal fun rosterPresentation(cachedCount: Int, state: RosterLoadState): RosterPresentation =
    RosterPresentation(
        memberCount = cachedCount.takeIf { state == RosterLoadState.LOADED },
        hasStaleMembers = cachedCount > 0 && state != RosterLoadState.LOADED,
    )

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChannelInfoViewModel @Inject constructor(
    private val bufferRepository: BufferRepository,
    private val connectionManager: ConnectionManager,
    private val draftStore: ComposerDraftStore,
    private val settingsRepository: SettingsRepository,
    private val userDao: UserDao,
) : ViewModel() {

    private val bufferIdFlow = MutableStateFlow<Long?>(null)

    fun init(bufferId: Long) {
        bufferIdFlow.value = bufferId
        viewModelScope.launch { connectionManager.requestMembers(bufferId) }
    }

    private val bufferFlow = bufferIdFlow.flatMapLatest { id ->
        if (id == null) flowOf(null) else bufferRepository.observeBuffer(id)
    }

    private val membersFlow = bufferIdFlow.flatMapLatest { id ->
        if (id == null) flowOf(emptyList<MemberEntity>()) else bufferRepository.observeMembers(id)
    }

    val state: StateFlow<ChannelInfoUiState> =
        combine(
            bufferFlow,
            membersFlow,
            settingsRepository.settings,
            connectionManager.rosterStates,
        ) { buffer, members, settings, rosterStates ->
            val order = prefixOrderForBuffer(buffer)
            val social = sectionMembersSocial(members, order, settings.fools)
            val rosterState = buffer?.let { rosterStates[it.id] } ?: RosterLoadState.NOT_LOADED
            val presentation = rosterPresentation(members.size, rosterState)
            ChannelInfoUiState(
                buffer = buffer,
                sections = social.sections,
                memberCount = presentation.memberCount,
                rosterState = rosterState,
                hasStaleMembers = presentation.hasStaleMembers,
                foolMembers = social.fools,
                friends = settings.friends,
                fools = settings.fools,
                canModerate = viewerCanModerate(buffer, members, order),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChannelInfoUiState(),
        )

    fun retryMembers() = viewModelScope.launch {
        state.value.buffer?.let { connectionManager.requestMembers(it.id, force = true) }
    }

    // Resolve prefix order from the live client's ISUPPORT when connected; fallback otherwise.
    private fun prefixOrderForBuffer(buffer: BufferEntity?): String {
        val networkId = buffer?.networkId ?: return DEFAULT_PREFIX_ORDER
        val client = connectionManager.clientFor(networkId) ?: return DEFAULT_PREFIX_ORDER
        return prefixOrderFrom(client.isupport.prefixModes)
    }

    fun setPinned(pinned: Boolean) = viewModelScope.launch {
        state.value.buffer?.let { bufferRepository.setPinned(it.id, pinned) }
    }

    fun setMuted(muted: Boolean) = viewModelScope.launch {
        state.value.buffer?.let { bufferRepository.setMuted(it.id, muted) }
    }

    fun part(onDone: () -> Unit) = viewModelScope.launch {
        state.value.buffer?.let { connectionManager.partChannel(it.id) }
        onDone()
    }

    /** Open (or create) a DM with [nick], then hand the buffer id to [onOpen]. */
    fun messageMember(nick: String, onOpen: (Long) -> Unit) = viewModelScope.launch {
        val networkId = state.value.buffer?.networkId ?: return@launch
        val bufferId = connectionManager.ensureQueryBuffer(networkId, nick)
        onOpen(bufferId)
    }

    /**
     * Queue a "$nick: " prefill on the current buffer's composer draft, then [onDone] (pops back
     * to the chat). ChatScreen reads it via [ComposerDraftStore.consume] on re-entry (plans/11 §A).
     */
    fun mentionMember(nick: String, onDone: () -> Unit) {
        state.value.buffer?.let { draftStore.push(it.id, "$nick: ") }
        onDone()
    }

    /** Toggle [nick]'s friend membership (adding removes it from fools; see SettingsRepository). */
    fun toggleFriend(nick: String) = viewModelScope.launch {
        settingsRepository.setFriend(nick, io.github.trevarj.motd.data.prefs.normalizeNick(nick) !in state.value.friends)
    }

    /** Toggle [nick]'s fool membership (adding removes it from friends). */
    fun toggleFool(nick: String) = viewModelScope.launch {
        settingsRepository.setFool(nick, io.github.trevarj.motd.data.prefs.normalizeNick(nick) !in state.value.fools)
    }

    // --- nick sheet + whois (plans/16 §5.8) ---

    private val _nickSheet = MutableStateFlow<NickSheetState?>(null)
    val nickSheet: StateFlow<NickSheetState?> = _nickSheet
    private var nickDetailsJob: Job? = null

    /** Open the nick sheet for [nick]; WHOIS via labeled-response when available (see ChatViewModel). */
    fun openNickSheet(nick: String) {
        _nickSheet.value = NickSheetState(nick = nick)
        val networkId = state.value.buffer?.networkId ?: return
        viewModelScope.launch { state.value.buffer?.let { connectionManager.requestMembers(it.id) } }
        val client = connectionManager.clientFor(networkId)
        val normalized = client?.isupport?.normalize(nick) ?: io.github.trevarj.motd.data.prefs.normalizeNick(nick)
        nickDetailsJob?.cancel()
        nickDetailsJob = viewModelScope.launch {
            combine(
                userDao.observeByNick(networkId, normalized),
                connectionManager.presenceStates,
            ) { cached, presence ->
                cached to presence[io.github.trevarj.motd.service.PresenceKey(networkId, normalized)]
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

    // --- moderation executors (plans/16 §5.8) ---

    /** MODE <channel> +o/-o/+v/-v <nick>. */
    fun setMemberMode(nick: String, mode: Char, grant: Boolean) = viewModelScope.launch {
        val buffer = state.value.buffer ?: return@launch
        val flag = (if (grant) "+" else "-") + mode
        connectionManager.clientFor(buffer.networkId)
            ?.send(IrcMessage(command = "MODE", params = listOf(buffer.name, flag, nick)))
    }

    /** KICK <channel> <nick> [:reason]. */
    fun kick(nick: String, reason: String?) = viewModelScope.launch {
        val buffer = state.value.buffer ?: return@launch
        val params = if (reason.isNullOrBlank()) listOf(buffer.name, nick) else listOf(buffer.name, nick, reason)
        connectionManager.clientFor(buffer.networkId)?.send(IrcMessage(command = "KICK", params = params))
    }

    /** MODE <channel> +b <banMask(nick)>. */
    fun ban(nick: String) = viewModelScope.launch {
        val buffer = state.value.buffer ?: return@launch
        connectionManager.clientFor(buffer.networkId)
            ?.send(IrcMessage(command = "MODE", params = listOf(buffer.name, "+b", banMask(nick))))
    }

    /**
     * Set the channel topic (plans/16 §5.8). Always offered (op requirements vary per channel); a
     * 482 error lands in the server buffer. The TopicChanged echo updates Room reactively.
     */
    fun setTopic(topic: String) = viewModelScope.launch {
        val buffer = state.value.buffer ?: return@launch
        connectionManager.clientFor(buffer.networkId)
            ?.send(IrcMessage(command = "TOPIC", params = listOf(buffer.name, topic)))
    }

    /**
     * True when the viewer's own member row holds op-or-above in this CHANNEL buffer (Confirmed #7).
     * Self nick comes from the live client's Ready state; prefix order from ISUPPORT.
     */
    private fun viewerCanModerate(buffer: BufferEntity?, members: List<MemberEntity>, prefixOrder: String): Boolean {
        if (buffer?.type != BufferType.CHANNEL) return false
        val client = connectionManager.clientFor(buffer.networkId) ?: return false
        val myNick = (connectionManager.connectionStates.value[buffer.networkId] as? IrcClientState.Ready)?.nick
            ?: return false
        val normalize: (String) -> String = { client.isupport.normalize(it) }
        val me = members.firstOrNull { normalize(it.nick) == normalize(myNick) } ?: return false
        return canModerate(me.prefixes, prefixOrder)
    }
}
