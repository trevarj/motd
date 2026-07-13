package io.github.trevarj.motd.ui.settings.addnetwork

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.AuthMode
import io.github.trevarj.motd.ui.onboarding.ConnectionChoice
import io.github.trevarj.motd.ui.onboarding.ServerForm
import io.github.trevarj.motd.ui.settings.buildNetworkEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The three phases of the add-network flow (plans/16 §5.4). */
enum class AddNetworkPhase { FORM, TESTING, FAILED }

data class AddNetworkUiState(
    val kind: ConnectionChoice = ConnectionChoice.NETWORK,
    val server: ServerForm = ServerForm(),
    val auth: AuthForm = AuthForm(),
    val phase: AddNetworkPhase = AddNetworkPhase.FORM,
    val networkId: Long? = null,          // created row during the connect test
    val connState: IrcClientState? = null,
    val error: String? = null,
    val presetId: NetworkPresetId = NetworkPresetId.CUSTOM,
    val showPlaintextWarning: Boolean = false,
    val plaintextConfirmed: Boolean = false,
) {
    val isSoju: Boolean get() = kind == ConnectionChoice.SOJU
    val role: NetworkRole get() = if (isSoju) NetworkRole.BOUNCER_ROOT else NetworkRole.DIRECT
    val canSubmit: Boolean get() = phase == AddNetworkPhase.FORM && server.isValid && auth.isValid
}

/**
 * Add-network flow (plans/16 §5.4): collect a DIRECT or soju BOUNCER_ROOT network via the shared
 * [buildNetworkEntity], run a connect test, and on Ready finish (soju roots jump to the bouncer
 * manager). On failure the user may retry, edit, or "Save anyway" (confirmed decision #4).
 */
@HiltViewModel
class AddNetworkViewModel @Inject constructor(
    private val networkRepository: NetworkRepository,
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(AddNetworkUiState())
    val state: StateFlow<AddNetworkUiState> = _state.asStateFlow()

    // Live connect-test collector; cancelled when the test is abandoned/retried so a stale row's
    // state can never drive navigation after we deleted it.
    private var testJob: Job? = null

    fun setKind(kind: ConnectionChoice) {
        // soju pins SASL PLAIN (same rule as OnboardingReducer): soju logs in with PLAIN.
        val auth = if (kind == ConnectionChoice.SOJU) {
            _state.value.auth.copy(mode = AuthMode.PLAIN)
        } else {
            _state.value.auth
        }
        _state.value = _state.value.copy(
            kind = kind,
            auth = auth,
            presetId = if (kind == ConnectionChoice.NETWORK) _state.value.presetId else NetworkPresetId.CUSTOM,
            showPlaintextWarning = false,
            plaintextConfirmed = false,
        )
    }

    fun editServer(server: ServerForm) {
        val current = _state.value
        val selected = networkPreset(current.presetId)
        _state.value = current.copy(
            server = server,
            presetId = if (selected?.matches(server) == true) current.presetId else NetworkPresetId.CUSTOM,
            showPlaintextWarning = false,
            plaintextConfirmed = false,
        )
    }

    fun selectPreset(id: NetworkPresetId) {
        val current = _state.value
        val preset = networkPreset(id)
        if (preset == null) {
            _state.value = current.copy(
                presetId = NetworkPresetId.CUSTOM,
                showPlaintextWarning = false,
                plaintextConfirmed = false,
            )
            return
        }
        val (server, auth) = applyNetworkPreset(preset, current.server)
        _state.value = current.copy(
            kind = ConnectionChoice.NETWORK,
            server = server,
            auth = auth,
            presetId = id,
            showPlaintextWarning = false,
            plaintextConfirmed = false,
        )
    }

    fun editAuth(auth: AuthForm) {
        // Re-pin PLAIN for soju so a NONE/EXTERNAL choice can't sneak in on the root.
        val pinned = if (_state.value.isSoju) auth.copy(mode = AuthMode.PLAIN) else auth
        _state.value = _state.value.copy(auth = pinned)
    }

    /** Create the row, connect, and observe its live state. */
    fun submit(onOpenBouncerNetworks: (Long) -> Unit, onDone: () -> Unit) {
        if (!_state.value.canSubmit) return
        if (!_state.value.isSoju && !_state.value.server.tls && !_state.value.plaintextConfirmed) {
            _state.value = _state.value.copy(showPlaintextWarning = true)
            return
        }
        testJob?.cancel()
        testJob = viewModelScope.launch {
            val s = _state.value
            val entity = buildNetworkEntity(
                server = s.server,
                auth = s.auth,
                role = s.role,
                name = networkPreset(s.presetId)?.displayName ?: s.server.host,
            )
            val networkId = networkRepository.addNetwork(entity)
            _state.value = _state.value.copy(
                phase = AddNetworkPhase.TESTING,
                networkId = networkId,
                connState = null,
                error = null,
            )
            connectionManager.connect(networkId)

            connectionManager.connectionStates.collect { states ->
                val cs = states[networkId] ?: return@collect
                _state.value = _state.value.copy(connState = cs)
                when (cs) {
                    is IrcClientState.Ready ->
                        if (_state.value.isSoju) onOpenBouncerNetworks(networkId) else onDone()
                    is IrcClientState.Failed ->
                        _state.value = _state.value.copy(phase = AddNetworkPhase.FAILED, error = cs.reason)
                    else -> Unit
                }
            }
        }
    }

    fun confirmPlaintext(onOpenBouncerNetworks: (Long) -> Unit, onDone: () -> Unit) {
        _state.value = _state.value.copy(showPlaintextWarning = false, plaintextConfirmed = true)
        submit(onOpenBouncerNetworks, onDone)
    }

    fun dismissPlaintextWarning() {
        _state.value = _state.value.copy(showPlaintextWarning = false)
    }

    /** Retry after a failed test: delete the half-created row, keep the fields, resubmit. */
    fun retry(onOpenBouncerNetworks: (Long) -> Unit, onDone: () -> Unit) {
        viewModelScope.launch {
            deleteHalfCreated()
            _state.value = _state.value.copy(
                phase = AddNetworkPhase.FORM,
                networkId = null,
                connState = null,
                error = null,
            )
            submit(onOpenBouncerNetworks, onDone)
        }
    }

    /** Keep the failed row so the user can fix it later in NetworkSettings (decision #4). */
    fun saveAnyway(onDone: () -> Unit) {
        testJob?.cancel()
        // The row stays in the DB; leave the connect intent as-is (the user may fix and reconnect).
        onDone()
    }

    /** Back to the form to edit fields; delete the half-created row first. */
    fun editForm() {
        viewModelScope.launch {
            deleteHalfCreated()
            _state.value = _state.value.copy(
                phase = AddNetworkPhase.FORM,
                networkId = null,
                connState = null,
                error = null,
            )
        }
    }

    /** Back-press during TESTING/FAILED: drop the half-created row, then leave. */
    fun abandon(onDone: () -> Unit) {
        viewModelScope.launch {
            deleteHalfCreated()
            onDone()
        }
    }

    private suspend fun deleteHalfCreated() {
        testJob?.cancel()
        _state.value.networkId?.let { networkRepository.deleteNetwork(it) }
    }
}
