package io.github.trevarj.motd.ui.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Single UI state for the chat list screen. See plans/07. */
data class ChatListState(
    val rows: List<ChatListRow> = emptyList(),
    val connection: Map<Long, IrcClientState> = emptyMap(),
    val networks: List<NetworkEntity> = emptyList(),
    val loading: Boolean = true,
)

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val bufferRepository: BufferRepository,
    private val networkRepository: NetworkRepository,
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    val state: StateFlow<ChatListState> =
        combine(
            bufferRepository.observeChatList(),
            networkRepository.observeNetworks(),
            connectionManager.connectionStates,
        ) { rows, networks, connection ->
            ChatListState(
                rows = rows,
                connection = connection,
                networks = networks,
                loading = false,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChatListState(),
        )

    fun setPinned(bufferId: Long, pinned: Boolean) = viewModelScope.launch {
        bufferRepository.setPinned(bufferId, pinned)
    }

    fun setMuted(bufferId: Long, muted: Boolean) = viewModelScope.launch {
        bufferRepository.setMuted(bufferId, muted)
    }

    fun joinChannel(networkId: Long, channel: String) = viewModelScope.launch {
        connectionManager.joinChannel(networkId, channel)
    }

    /** Find-or-create a query buffer, then hand the id to [onOpen] for navigation. */
    fun messageUser(networkId: Long, nick: String, onOpen: (Long) -> Unit) = viewModelScope.launch {
        val bufferId = connectionManager.ensureQueryBuffer(networkId, nick)
        onOpen(bufferId)
    }
}
