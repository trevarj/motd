package io.github.trevarj.motd.dickord

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class DickordPortalState(
    val loading: Boolean = true,
    val enabled: Boolean = false,
    val groups: List<DickordPortalGroup> = emptyList(),
    val selectedGroupKey: String = DICKORD_PORTAL_DMS_KEY,
    val showArchived: Boolean = false,
    val offline: Boolean = true,
)

internal sealed interface DickordPortalRowLookup {
    data object Loading : DickordPortalRowLookup

    data class Found(
        val row: ChatListRow,
    ) : DickordPortalRowLookup

    data object Missing : DickordPortalRowLookup
}

@HiltViewModel
class DickordPortalViewModel
    @Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
        private val prefs: DickordLabsPrefs,
        private val bufferRepository: BufferRepository,
        private val connectionManager: ConnectionManager,
    ) : ViewModel() {
        private val rows = MutableStateFlow<List<ChatListRow>?>(null)
        private val enabled = MutableStateFlow<Boolean?>(null)
        private val selectedGroupKey =
            MutableStateFlow(savedStateHandle.get<String>(KEY_SELECTED_GROUP) ?: DICKORD_PORTAL_DMS_KEY)
        private val showArchived = MutableStateFlow(savedStateHandle.get<Boolean>(KEY_SHOW_ARCHIVED) ?: false)
        private val activeEntryIds = MutableStateFlow<Set<String>>(emptySet())
        private val requestedReadyNetworks = mutableSetOf<Long>()

        internal val state: StateFlow<DickordPortalState> =
            combine(
                rows,
                enabled,
                selectedGroupKey,
                showArchived,
                connectionManager.connectionStates,
            ) { currentRows, currentEnabled, requestedGroup, archived, connections ->
                if (currentRows == null || currentEnabled == null) {
                    DickordPortalState(
                        selectedGroupKey = requestedGroup,
                        showArchived = archived,
                    )
                } else {
                    val groups = if (currentEnabled) presentDickordPortal(currentRows, archived) else emptyList()
                    val selected =
                        requestedGroup.takeIf { !currentEnabled || groups.any { group -> group.key == requestedGroup } }
                            ?: DICKORD_PORTAL_DMS_KEY
                    if (selected != requestedGroup) persistSelectedGroup(selected)
                    val owningNetworks = dickordOwningNetworkIds(currentRows)
                    DickordPortalState(
                        loading = false,
                        enabled = currentEnabled,
                        groups = groups,
                        selectedGroupKey = selected,
                        showArchived = archived,
                        offline =
                            currentEnabled &&
                                groups.any { it.conversations.isNotEmpty() } &&
                                owningNetworks.none { connections[it] is IrcClientState.Ready },
                    )
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, DickordPortalState())

        init {
            viewModelScope.launch {
                bufferRepository.observeChatList().collect { rows.value = it }
            }
            viewModelScope.launch {
                prefs.enabled.collect { enabled.value = it }
            }
            viewModelScope.launch {
                combine(rows, enabled, connectionManager.connectionStates, activeEntryIds, ::AutomaticSnapshotInputs)
                    .collect { input -> requestAutomaticSnapshots(input) }
            }
        }

        fun selectGroup(key: String) {
            if (state.value.groups.none { it.key == key }) return
            persistSelectedGroup(key)
        }

        fun setShowArchived(show: Boolean) {
            if (showArchived.value == show) return
            showArchived.value = show
            savedStateHandle[KEY_SHOW_ARCHIVED] = show
        }

        fun setEntryActive(
            entryId: String,
            active: Boolean,
        ) {
            activeEntryIds.update { entries -> if (active) entries + entryId else entries - entryId }
        }

        fun refresh() {
            val networkIds = dickordOwningNetworkIds(rows.value.orEmpty())
            viewModelScope.launch {
                networkIds.forEach { connectionManager.requestDickordChannelSnapshot(it) }
            }
        }

        internal fun canonicalRow(bufferId: Long): Flow<DickordPortalRowLookup> =
            flow {
                emit(DickordPortalRowLookup.Loading)
                val canonicalId = bufferRepository.canonicalBufferId(bufferId)
                if (canonicalId == null) {
                    emit(DickordPortalRowLookup.Missing)
                } else {
                    emitAll(
                        rows
                            .filterNotNull()
                            .map { currentRows ->
                                currentRows
                                    .firstOrNull { it.bufferId == canonicalId }
                                    ?.let(DickordPortalRowLookup::Found)
                                    ?: DickordPortalRowLookup.Missing
                            }.distinctUntilChanged(),
                    )
                }
            }

        private suspend fun requestAutomaticSnapshots(input: AutomaticSnapshotInputs) {
            val readyNetworks =
                input.connections
                    .filterValues { it is IrcClientState.Ready }
                    .keys
            requestedReadyNetworks.retainAll(readyNetworks)
            if (input.rows == null || input.enabled != true || input.activeEntryIds.isEmpty()) return

            dickordOwningNetworkIds(input.rows).forEach { networkId ->
                val ready = input.connections[networkId] as? IrcClientState.Ready ?: return@forEach
                if ("message-tags" !in ready.caps || !requestedReadyNetworks.add(networkId)) return@forEach
                connectionManager.requestDickordChannelSnapshot(networkId)
            }
        }

        private fun persistSelectedGroup(key: String) {
            selectedGroupKey.value = key
            savedStateHandle[KEY_SELECTED_GROUP] = key
        }

        private companion object {
            const val KEY_SELECTED_GROUP = "dickord_portal_group"
            const val KEY_SHOW_ARCHIVED = "dickord_portal_archived"
        }
    }

private data class AutomaticSnapshotInputs(
    val rows: List<ChatListRow>?,
    val enabled: Boolean?,
    val connections: Map<Long, IrcClientState>,
    val activeEntryIds: Set<String>,
)

private fun dickordOwningNetworkIds(rows: List<ChatListRow>): List<Long> =
    rows
        .asSequence()
        .filter { it.type == BufferType.CHANNEL && isDickordChannel(it.displayName) }
        .map(ChatListRow::networkId)
        .distinct()
        .sorted()
        .toList()
