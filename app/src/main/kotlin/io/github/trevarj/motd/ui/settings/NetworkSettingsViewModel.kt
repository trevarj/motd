package io.github.trevarj.motd.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.ServerForm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NetworkSettingsUiState(
    val loaded: Boolean = false,
    val entity: NetworkEntity? = null,
    // User-facing display name (alias). Editable so a bouncer root need not show its raw host/IP.
    val displayName: String = "",
    val server: ServerForm = ServerForm(),
    val auth: AuthForm = AuthForm(),
    // Round 5 (plans/16 §5.3): live status + autoConnect editing.
    val connState: IrcClientState = IrcClientState.Disconnected,
    val autoConnect: Boolean = true,
    val parentName: String? = null,   // root name for a BOUNCER_CHILD's "Managed by" row
) {
    val canSave: Boolean get() = server.isValid && auth.isValid
}

@HiltViewModel
class NetworkSettingsViewModel @Inject constructor(
    private val networkRepository: NetworkRepository,
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(NetworkSettingsUiState())
    val state: StateFlow<NetworkSettingsUiState> = _state.asStateFlow()

    private var networkId: Long = 0

    fun init(networkId: Long) {
        if (_state.value.loaded) return
        this.networkId = networkId
        viewModelScope.launch {
            val n = networkRepository.networkById(networkId)
            val parentName = n?.parentId?.let { networkRepository.networkById(it)?.name }
            _state.value = NetworkSettingsUiState(
                loaded = true,
                entity = n,
                displayName = n?.name.orEmpty(),
                server = n?.toServerForm() ?: ServerForm(),
                auth = n?.toAuthForm() ?: AuthForm(),
                autoConnect = n?.autoConnect ?: true,
                parentName = parentName,
            )
        }
        // Mirror this network's live connection state into the status header.
        viewModelScope.launch {
            connectionManager.connectionStates.collect { states ->
                _state.value = _state.value.copy(
                    connState = states[networkId] ?: IrcClientState.Disconnected,
                )
            }
        }
    }

    fun editServer(server: ServerForm) { _state.value = _state.value.copy(server = server) }
    fun editAuth(auth: AuthForm) { _state.value = _state.value.copy(auth = auth) }
    fun editDisplayName(name: String) { _state.value = _state.value.copy(displayName = name) }

    fun connect() = viewModelScope.launch { connectionManager.connect(networkId) }
    fun disconnect() = viewModelScope.launch { connectionManager.disconnect(networkId) }

    /** Persist autoConnect immediately; reconcile reacts (sticky intent guards live state, §4). */
    fun setAutoConnect(enabled: Boolean) = viewModelScope.launch {
        _state.value = _state.value.copy(autoConnect = enabled)
        _state.value.entity?.let { networkRepository.updateNetwork(it.copy(autoConnect = enabled)) }
    }

    fun openServerBuffer(onOpen: (Long) -> Unit) = viewModelScope.launch {
        onOpen(connectionManager.ensureServerBuffer(networkId))
    }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val current = _state.value.entity ?: return@launch
        val updated = buildNetworkEntity(
            server = _state.value.server,
            auth = _state.value.auth,
            role = current.role,
            id = current.id,
            // A blank alias falls back to the existing name (never persist an empty display name).
            name = _state.value.displayName.trim().ifBlank { current.name },
            parentId = current.parentId,
            bouncerNetId = current.bouncerNetId,
            // Persist the current autoConnect value alongside the form fields.
        ).copy(autoConnect = _state.value.autoConnect)
        networkRepository.updateNetwork(updated)
        onDone()
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        _state.value.entity?.let { networkRepository.deleteNetwork(it.id) }
        onDone()
    }
}
