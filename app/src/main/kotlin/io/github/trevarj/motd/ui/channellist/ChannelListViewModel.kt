package io.github.trevarj.motd.ui.channellist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.client.ChannelListing
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.ui.nav.ChannelListRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Channel-browser UI state (plans/16 §5.7).
 *
 * [loaded] distinguishes "fetched, no results" from "not fetched yet". [gated] is true when the
 * server lacks ELIST 'U' and the user must enter a search mask before the first fetch (Confirmed
 * decision #6). [isRoot] disables browsing for an unbound soju BOUNCER_ROOT (LIST is meaningless
 * on the root connection).
 */
data class ChannelListUiState(
    val networkId: Long = 0,
    val connState: IrcClientState = IrcClientState.Disconnected,
    val query: String = "",
    val listings: List<ChannelListing> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val gated: Boolean = false,
    val isRoot: Boolean = false,
    val error: String? = null,
) {
    val isReady: Boolean get() = connState is IrcClientState.Ready
}

@HiltViewModel
class ChannelListViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val networkRepository: NetworkRepository,
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    private val networkId: Long = savedStateHandle.toRoute<ChannelListRoute>().networkId

    private val _state = MutableStateFlow(ChannelListUiState(networkId = networkId))
    val state: StateFlow<ChannelListUiState> = _state.asStateFlow()

    private var started = false

    /** Idempotent entry point: mirrors connection state; auto-fetches once Ready + ELIST 'U'. */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isRoot = networkRepository.networkById(networkId)?.role == NetworkRole.BOUNCER_ROOT,
            )
        }
        viewModelScope.launch {
            connectionManager.connectionStates.collect { states ->
                val conn = states[networkId] ?: IrcClientState.Disconnected
                _state.value = _state.value.copy(connState = conn)
                // Auto-fetch the busiest channels on entry when ELIST 'U' is supported; otherwise
                // gate on a search mask (Confirmed #6). Roots never LIST.
                if (conn is IrcClientState.Ready && !_state.value.loaded && !_state.value.isRoot) {
                    if (canAutoFetch(conn.isupport["ELIST"])) {
                        fetch()
                    } else if (!_state.value.gated) {
                        _state.value = _state.value.copy(gated = true)
                    }
                }
            }
        }
    }

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    /** Fetch (or re-fetch) via LIST/ELIST, then sort by user count descending. */
    fun fetch() {
        val s = _state.value
        if (s.loading || s.isRoot) return
        val client = connectionManager.clientFor(networkId) ?: return
        val args = listArgsFor(s.query)
        _state.value = s.copy(loading = true, error = null, gated = false)
        viewModelScope.launch {
            val result = runCatching {
                client.listChannels(mask = args.mask, minUsers = args.minUsers, cap = LIST_CAP)
            }
            _state.value = _state.value.copy(
                loading = false,
                loaded = result.isSuccess,
                listings = result.getOrNull()?.let(::sortListings) ?: _state.value.listings,
                error = result.exceptionOrNull()?.message,
            )
        }
    }

    fun connect() {
        viewModelScope.launch { connectionManager.connect(networkId) }
    }

    /** Join [channel], then pop; the buffer appears in the chat list on the JOIN echo. */
    fun join(channel: String, onDone: () -> Unit) {
        viewModelScope.launch {
            connectionManager.joinChannel(networkId, channel)
            onDone()
        }
    }

    private companion object {
        const val LIST_CAP = 2000
    }
}
