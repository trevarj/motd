package io.github.trevarj.motd.ui.chatlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.PresenceKey
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.service.ReadMarkerSnapshotter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Single UI state for the chat list screen. See plans/07 + plans/16 §3.4. */
data class ChatListState(
    val rows: List<ChatListRow> = emptyList(),
    val connection: Map<Long, IrcClientState> = emptyMap(),
    val queryPresence: Map<Long, PresenceState> = emptyMap(),
    val networks: List<NetworkEntity> = emptyList(),
    val loading: Boolean = true,
    // Round 4 (plans/13 §3.5): global friend/fool sets drive chat-list sectioning.
    val friends: Set<String> = emptySet(),
    val fools: Set<String> = emptySet(),
    // Round 5 (plans/16 §3): drawer server selector + scoping.
    val selectedNetworkId: Long? = null,
    val drawerRows: List<DrawerRow> = emptyList(),
    val allUnread: Int = 0, // "All chats" unread rollup (non-muted)
    val allMentions: Int = 0, // "All chats" mention rollup
) {
    /** Effective unread count for the current drawer scope; muted activity stays row-local. */
    val scopedUnreadCount: Int
        get() = rows.filterNot { it.type == BufferType.SERVER || it.muted }.sumOf { it.unreadCount }

    /** The scoped network's name, or null when unscoped (drives the top-bar title/chip). */
    val selectedNetworkName: String?
        get() = selectedNetworkId?.let { id -> networks.firstOrNull { it.id == id }?.name }

    /** Every network is absent from the map or Disconnected -> the "Go online" affordance shows. */
    val allOffline: Boolean
        get() = networks.all { connection[it.id].let { s -> s == null || s is IrcClientState.Disconnected } }
}

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val bufferRepository: BufferRepository,
    private val networkRepository: NetworkRepository,
    private val connectionManager: ConnectionManager,
    private val readMarkerRepository: ReadMarkerSnapshotter,
    private val settingsRepository: SettingsRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // Scope selection survives config changes; null = unified list (default).
    private val selection = MutableStateFlow(savedStateHandle.get<Long?>(KEY_SELECTED))

    val state: StateFlow<ChatListState> =
        combine(
            bufferRepository.observeChatList(),
            networkRepository.observeNetworks(),
            connectionManager.connectionStates.combine(connectionManager.presenceStates) { connection, presence ->
                connection to presence
            },
            settingsRepository.settings,
            selection,
        ) { rows, networks, connectionAndPresence, settings, selected ->
            val (connection, presence) = connectionAndPresence
            // If the selected network was deleted, fall back to the unified list.
            val validSelection = selected?.takeIf { id -> networks.any { it.id == id } }
            if (validSelection != selected) setSelection(validSelection)

            ChatListState(
                rows = scopeRows(rows, validSelection, networks),
                connection = connection,
                queryPresence = rows.asSequence()
                    .filter { it.type == BufferType.QUERY }
                    .mapNotNull { row ->
                        val normalize = connectionManager.clientFor(row.networkId)?.isupport?.let { it::normalize }
                            ?: return@mapNotNull null
                        presence[PresenceKey(row.networkId, normalize(row.displayName))]?.let { row.bufferId to it }
                    }
                    .toMap(),
                networks = networks,
                loading = false,
                friends = settings.friends,
                fools = settings.fools,
                selectedNetworkId = validSelection,
                drawerRows = buildDrawerRows(networks, rows, connection),
                allUnread = rows.filterNot { it.muted }.sumOf { it.unreadCount },
                allMentions = rows.filterNot { it.muted }.sumOf { it.mentionCount },
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

    /**
     * Delete a chat/buffer from the list. A CHANNEL is PARTed first (no-op when not connected —
     * [ConnectionManager.partChannel] only sends when a live client exists), then the buffer and all
     * of its content (messages/members/reactions) are removed. QUERY/SERVER rows just remove.
     *
     * Scope note: list scoping keys off networkId, never a bufferId, so deleting a buffer cannot be
     * the scoped selection — no scope reset is needed here.
     */
    fun deleteBuffer(row: ChatListRow) = viewModelScope.launch {
        if (row.type == BufferType.CHANNEL) {
            connectionManager.partChannel(row.bufferId)
        }
        bufferRepository.deleteBuffer(row.bufferId)
    }

    /** Find-or-create a query buffer, then hand the id to [onOpen] for navigation. */
    fun messageUser(networkId: Long, nick: String, onOpen: (Long) -> Unit) = viewModelScope.launch {
        val bufferId = connectionManager.ensureQueryBuffer(networkId, nick)
        onOpen(bufferId)
    }

    // -- Round 5: drawer selection + per-network / global connectivity (plans/16 §3.4) --

    /** Scope the list to [networkId] (root includes children); null clears the scope. */
    fun selectNetwork(networkId: Long?) = setSelection(networkId)

    fun connect(networkId: Long) = viewModelScope.launch { connectionManager.connect(networkId) }

    fun disconnect(networkId: Long) = viewModelScope.launch { connectionManager.disconnect(networkId) }

    /** Global go-offline: disconnect every network (in-memory intent, resets on restart). */
    fun goOffline() = viewModelScope.launch {
        state.value.networks.forEach { connectionManager.disconnect(it.id) }
    }

    /** Global go-online: connect everything (explicit "connect all", may include autoConnect=false). */
    fun goOnline() = viewModelScope.launch {
        state.value.networks.forEach { connectionManager.connect(it.id) }
    }

    /** Find-or-create the SERVER buffer for [networkId], then navigate to it. */
    fun openServerBuffer(networkId: Long, onOpen: (Long) -> Unit) = viewModelScope.launch {
        onOpen(connectionManager.ensureServerBuffer(networkId))
    }

    /** Mark every currently unread chat in the current drawer scope through one Room snapshot. */
    fun markCurrentScopeRead() {
        val bufferIds = unreadBufferIds(state.value.rows)
        if (bufferIds.isEmpty()) return
        viewModelScope.launch {
            readMarkerRepository.latestIncoming(bufferIds).forEach { marker ->
                val timestamp = marker.timestamp ?: return@forEach
                runCatching { connectionManager.markRead(marker.bufferId, timestamp) }
            }
        }
    }

    private fun setSelection(networkId: Long?) {
        selection.value = networkId
        savedStateHandle[KEY_SELECTED] = networkId
    }

    private companion object {
        const val KEY_SELECTED = "selected_network"
    }
}

/** Pure selection seam: muted/SERVER/zero-unread rows never participate in mark-all. */
internal fun unreadBufferIds(rows: List<ChatListRow>): List<Long> = rows
    .asSequence()
    .filter { !it.muted && it.type != BufferType.SERVER && it.unreadCount > 0 }
    .map { it.bufferId }
    .distinct()
    .toList()
