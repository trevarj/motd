package io.github.trevarj.motd.ui.settings.bouncer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BouncerNetworksUiState(
    val root: NetworkEntity? = null,
    val rootState: IrcClientState = IrcClientState.Disconnected,
    val rows: List<BouncerNetRow> = emptyList(),
    val loading: Boolean = false,
    val busyNetIds: Set<String> = emptySet(),   // per-row in-flight guard
    val error: String? = null,
)

/**
 * soju bound-network manager (plans/16 §5.5): lists the networks the soju root knows about, lets
 * the user import/remove local mirrors and add/delete networks on the bouncer. Requires the root
 * connection to be Ready; otherwise the screen shows a connect card.
 */
@HiltViewModel
class BouncerNetworksViewModel @Inject constructor(
    private val networkRepository: NetworkRepository,
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(BouncerNetworksUiState())
    val state: StateFlow<BouncerNetworksUiState> = _state.asStateFlow()

    private var rootNetworkId: Long = 0
    private var initialized = false

    fun init(rootNetworkId: Long) {
        if (initialized) return
        initialized = true
        this.rootNetworkId = rootNetworkId
        viewModelScope.launch {
            _state.value = _state.value.copy(root = networkRepository.networkById(rootNetworkId))
        }
        viewModelScope.launch {
            connectionManager.connectionStates.collect { states ->
                val cs = states[rootNetworkId] ?: IrcClientState.Disconnected
                val wasReady = _state.value.rootState is IrcClientState.Ready
                _state.value = _state.value.copy(rootState = cs)
                // Refresh on the transition into Ready (the client is only usable then).
                if (cs is IrcClientState.Ready && !wasReady) refresh()
            }
        }
    }

    fun connect() = viewModelScope.launch { connectionManager.connect(rootNetworkId) }

    /** Re-read the live listing and local children, then merge. */
    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        val client = connectionManager.clientFor(rootNetworkId)
        val listing = if (client == null) {
            emptyList()
        } else {
            runCatching { client.bouncerListNetworks() }.getOrElse {
                _state.value = _state.value.copy(loading = false, error = it.message)
                return@launch
            }
        }
        val children = networkRepository.childrenOf(rootNetworkId)
        _state.value = _state.value.copy(
            loading = false,
            rows = mergeBouncerRows(listing, children),
        )
    }

    /** Insert the local BOUNCER_CHILD mirror; no-op if the netId is already imported. */
    fun importNetwork(row: BouncerNetRow) = viewModelScope.launch {
        val root = _state.value.root ?: return@launch
        // Re-read children inside the action to avoid the notify-mirror duplicate race (§9 risks).
        val existing = networkRepository.childrenOf(rootNetworkId)
        if (existing.any { it.bouncerNetId == row.netId }) {
            refreshInline()
            return@launch
        }
        networkRepository.addNetwork(
            root.copy(
                id = 0,
                name = row.name,
                role = NetworkRole.BOUNCER_CHILD,
                parentId = root.id,
                bouncerNetId = row.netId,
                host = row.host ?: root.host,
            ),
        )
        refreshInline()
    }

    /** Remove only the local mirror; the bouncer keeps the network. */
    fun removeLocal(row: BouncerNetRow) = viewModelScope.launch {
        row.childNetworkId?.let { networkRepository.deleteNetwork(it) }
        refreshInline()
    }

    /** Delete the network on the bouncer (and its local child if present). */
    fun deleteFromBouncer(row: BouncerNetRow) = viewModelScope.launch {
        withBusy(row.netId) {
            val client = connectionManager.clientFor(rootNetworkId)
            runCatching { client?.bouncerDeleteNetwork(row.netId) }.onFailure {
                _state.value = _state.value.copy(error = it.message)
            }
            // Remove the local child directly rather than relying on the BOUNCER NETWORK notify.
            row.childNetworkId?.let { networkRepository.deleteNetwork(it) }
        }
        refreshInline()
    }

    /** Add a network on the bouncer, then import its mirror (idempotent per importNetwork). */
    fun addNetwork(name: String, host: String, port: String?, nick: String?) = viewModelScope.launch {
        val client = connectionManager.clientFor(rootNetworkId) ?: return@launch
        val attrs = buildMap {
            if (name.isNotBlank()) put("name", name)
            if (host.isNotBlank()) put("host", host)
            port?.takeIf { it.isNotBlank() }?.let { put("port", it) }
            nick?.takeIf { it.isNotBlank() }?.let { put("nickname", it) }
        }
        val netId = runCatching { client.bouncerAddNetwork(attrs) }.getOrElse {
            _state.value = _state.value.copy(error = it.message)
            return@launch
        }
        // Refresh to pick up the new listing; import explicitly if the notify mirror lagged.
        refreshInline()
        if (netId.isNotBlank()) {
            val row = _state.value.rows.firstOrNull { it.netId == netId }
            if (row != null && row.childNetworkId == null) importNetwork(row)
        }
    }

    // Synchronous refresh body reused by the actions (they already run in viewModelScope).
    private suspend fun refreshInline() {
        val client = connectionManager.clientFor(rootNetworkId)
        val listing = if (client == null) emptyList()
        else runCatching { client.bouncerListNetworks() }.getOrElse {
            _state.value = _state.value.copy(error = it.message)
            return
        }
        val children = networkRepository.childrenOf(rootNetworkId)
        _state.value = _state.value.copy(rows = mergeBouncerRows(listing, children))
    }

    private inline fun BouncerNetworksUiState.busy(netId: String, on: Boolean): BouncerNetworksUiState =
        copy(busyNetIds = if (on) busyNetIds + netId else busyNetIds - netId)

    private suspend fun withBusy(netId: String, block: suspend () -> Unit) {
        _state.value = _state.value.busy(netId, true)
        try {
            block()
        } finally {
            _state.value = _state.value.busy(netId, false)
        }
    }
}
