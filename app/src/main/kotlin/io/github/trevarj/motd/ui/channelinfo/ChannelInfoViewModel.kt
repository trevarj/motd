package io.github.trevarj.motd.ui.channelinfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.ui.chat.ComposerDraftStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChannelInfoUiState(
    val buffer: BufferEntity? = null,
    val sections: List<MemberSection> = emptyList(),
    val memberCount: Int = 0,
    // Round 4 (plans/13 §3.6): global friend/fool sets. Fools are pulled into their own section.
    val foolMembers: List<MemberEntity> = emptyList(),
    val friends: Set<String> = emptySet(),
    val fools: Set<String> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChannelInfoViewModel @Inject constructor(
    private val bufferRepository: BufferRepository,
    private val connectionManager: ConnectionManager,
    private val draftStore: ComposerDraftStore,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val bufferIdFlow = MutableStateFlow<Long?>(null)

    fun init(bufferId: Long) { bufferIdFlow.value = bufferId }

    private val bufferFlow = bufferIdFlow.flatMapLatest { id ->
        if (id == null) flowOf(null) else bufferRepository.observeBuffer(id)
    }

    private val membersFlow = bufferIdFlow.flatMapLatest { id ->
        if (id == null) flowOf(emptyList<MemberEntity>()) else bufferRepository.observeMembers(id)
    }

    val state: StateFlow<ChannelInfoUiState> =
        combine(bufferFlow, membersFlow, settingsRepository.settings) { buffer, members, settings ->
            val order = prefixOrderForBuffer(buffer)
            val social = sectionMembersSocial(members, order, settings.fools)
            ChannelInfoUiState(
                buffer = buffer,
                sections = social.sections,
                memberCount = members.size,
                foolMembers = social.fools,
                friends = settings.friends,
                fools = settings.fools,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChannelInfoUiState(),
        )

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
}
