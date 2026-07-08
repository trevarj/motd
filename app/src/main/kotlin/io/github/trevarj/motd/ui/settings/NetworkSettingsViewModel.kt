package io.github.trevarj.motd.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.ServerForm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NetworkSettingsUiState(
    val loaded: Boolean = false,
    val entity: NetworkEntity? = null,
    val server: ServerForm = ServerForm(),
    val auth: AuthForm = AuthForm(),
) {
    val canSave: Boolean get() = server.isValid && auth.isValid
}

@HiltViewModel
class NetworkSettingsViewModel @Inject constructor(
    private val networkRepository: NetworkRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NetworkSettingsUiState())
    val state: StateFlow<NetworkSettingsUiState> = _state.asStateFlow()

    fun init(networkId: Long) {
        if (_state.value.loaded) return
        viewModelScope.launch {
            // NetworkRepository has no byId; take the first snapshot of the observed list to seed
            // the form, then let the user edit locally without further overwrites.
            val list = networkRepository.observeNetworks().first()
            val n = list.firstOrNull { it.id == networkId }
            _state.value = NetworkSettingsUiState(
                loaded = true,
                entity = n,
                server = n?.toServerForm() ?: ServerForm(),
                auth = n?.toAuthForm() ?: AuthForm(),
            )
        }
    }

    fun editServer(server: ServerForm) { _state.value = _state.value.copy(server = server) }
    fun editAuth(auth: AuthForm) { _state.value = _state.value.copy(auth = auth) }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val current = _state.value.entity ?: return@launch
        val updated = buildNetworkEntity(
            server = _state.value.server,
            auth = _state.value.auth,
            role = current.role,
            id = current.id,
            name = current.name,
            parentId = current.parentId,
            bouncerNetId = current.bouncerNetId,
        )
        networkRepository.updateNetwork(updated)
        onDone()
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        _state.value.entity?.let { networkRepository.deleteNetwork(it.id) }
        onDone()
    }
}
