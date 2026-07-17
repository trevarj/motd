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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Channel-browser UI state (plans/16 §5.7).
 *
 * [loaded] distinguishes "fetched, no results" from "not fetched yet". [requiresQuery] is true
 * when the server lacks ELIST 'U' and the user must enter a search mask before the first fetch
 * (Confirmed decision #6). [isRoot] disables browsing for an unbound soju BOUNCER_ROOT (LIST is
 * meaningless on the root connection).
 */
data class ChannelListUiState(
    val networkId: Long = 0,
    val networkName: String = "",
    val connState: IrcClientState = IrcClientState.Disconnected,
    val initialized: Boolean = false,
    val query: String = "",
    val listings: List<ChannelListing> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val requiresQuery: Boolean = false,
    val isRoot: Boolean = false,
    val error: String? = null,
    val joiningChannel: String? = null,
    val joinError: String? = null,
) {
    val isReady: Boolean get() = connState is IrcClientState.Ready
    val availability: ChannelBrowserAvailability
        get() = channelBrowserAvailability(initialized, isRoot, connState)
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
            val network = networkRepository.networkById(networkId)
            _state.value = _state.value.copy(
                networkName = network?.name.orEmpty(),
                isRoot = network?.role == NetworkRole.BOUNCER_ROOT,
            )
            connectionManager.connectionStates.collect { states ->
                val clientState = connectionManager.clientFor(networkId)?.state?.value
                val conn = channelBrowserConnectionState(states[networkId], clientState)
                val requiresQuery = (conn as? IrcClientState.Ready)
                    ?.let { !canAutoFetch(it.isupport["ELIST"]) }
                    ?: _state.value.requiresQuery
                _state.value = _state.value.copy(
                    connState = conn,
                    initialized = true,
                    requiresQuery = requiresQuery,
                )
                // Auto-fetch the busiest channels on entry when ELIST 'U' is supported; otherwise
                // gate on a search mask (Confirmed #6). Roots never LIST.
                if (conn is IrcClientState.Ready && !_state.value.loaded && !_state.value.isRoot) {
                    if (canAutoFetch(conn.isupport["ELIST"])) {
                        fetch()
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
        if (s.loading || s.isRoot || !s.isReady || (s.requiresQuery && s.query.isBlank())) return
        val args = listArgsFor(s.query)
        _state.value = s.copy(loading = true, error = null)
        viewModelScope.launch {
            val client = withTimeoutOrNull(CLIENT_WAIT_TIMEOUT_MS) {
                var current = connectionManager.clientFor(networkId)
                while (current == null) {
                    delay(CLIENT_WAIT_POLL_MS)
                    current = connectionManager.clientFor(networkId)
                }
                current
            }
            val result = if (client == null) {
                Result.failure(IllegalStateException("Channel listing is not available yet. Try again."))
            } else runCatching {
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

    /** Join [channel], then pop; the buffer appears in the chat list on the JOIN echo. */
    fun join(channel: String, onDone: () -> Unit) {
        if (_state.value.joiningChannel != null) return
        _state.value = _state.value.copy(joiningChannel = channel, joinError = null)
        viewModelScope.launch {
            val result = runCatching { connectionManager.joinChannel(networkId, channel) }
            _state.value = _state.value.copy(
                joiningChannel = null,
                joinError = result.exceptionOrNull()?.message,
            )
            if (result.isSuccess) onDone()
        }
    }

    private companion object {
        const val LIST_CAP = 2000
        const val CLIENT_WAIT_TIMEOUT_MS = 2_000L
        const val CLIENT_WAIT_POLL_MS = 50L
    }
}
