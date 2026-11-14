package io.github.trevarj.motd.ui.settings.addnetwork

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.bouncer.BouncerKind
import io.github.trevarj.motd.bouncer.SojuLoginForm
import io.github.trevarj.motd.bouncer.ZncLoginForm
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.BouncerKindPrefs
import io.github.trevarj.motd.data.prefs.NoopBouncerKindPrefs
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The three phases of the add-network flow. */
enum class AddNetworkPhase { FORM, TESTING, FAILED }

data class AddNetworkUiState(
    val kind: ConnectionChoice = ConnectionChoice.NETWORK,
    val bouncerKind: BouncerKind = BouncerKind.SOJU,
    // ZNC already collects its own per-bouncer network name via zncLogin.network; this only
    // covers the DIRECT and soju-root cases, where nothing today lets the user name the row
    // before it's created.
    val displayName: String = "",
    val server: ServerForm = ServerForm(),
    val auth: AuthForm = AuthForm(),
    val sojuLogin: SojuLoginForm = SojuLoginForm(),
    val zncLogin: ZncLoginForm = ZncLoginForm(),
    val phase: AddNetworkPhase = AddNetworkPhase.FORM,
    val networkId: Long? = null, // created row during the connect test
    val provisionalCreated: Boolean = false,
    val connState: IrcClientState? = null,
    val error: String? = null,
    val presetId: NetworkPresetId = NetworkPresetId.CUSTOM,
    val showPlaintextWarning: Boolean = false,
    val plaintextConfirmed: Boolean = false,
    /** True when [submit] resolves to an existing connection instead of creating a new row. */
    val duplicateConnection: Boolean = false,
) {
    val isBouncer: Boolean get() = kind == ConnectionChoice.BOUNCER
    val isSoju: Boolean get() = isBouncer && bouncerKind == BouncerKind.SOJU
    val isZnc: Boolean get() = isBouncer && bouncerKind == BouncerKind.ZNC
    val role: NetworkRole get() = if (isSoju) NetworkRole.BOUNCER_ROOT else NetworkRole.DIRECT
    val activeAuth: AuthForm
        get() =
            when {
                isSoju -> sojuLogin.toAuthForm()
                isZnc -> zncLogin.toAuthForm()
                else -> auth
            }
    val canSubmit: Boolean
        get() =
            phase == AddNetworkPhase.FORM && server.isValid &&
                when {
                    isSoju -> sojuLogin.isValid
                    isZnc -> zncLogin.isValid
                    else -> auth.isValid
                }
}

/**
 * Add-network flow: collect a DIRECT or soju BOUNCER_ROOT network via the shared
 * [buildNetworkEntity], run a connect test, and on Ready finish (soju roots jump to the bouncer
 * manager). On failure the user may retry, edit, or "Save anyway" (confirmed decision #4).
 */
@HiltViewModel
class AddNetworkViewModel
    @Inject
    constructor(
        private val networkRepository: NetworkRepository,
        private val connectionManager: ConnectionManager,
        private val bouncerKindPrefs: BouncerKindPrefs = NoopBouncerKindPrefs,
    ) : ViewModel() {
        private val _state = MutableStateFlow(AddNetworkUiState())
        val state: StateFlow<AddNetworkUiState> = _state.asStateFlow()

        // Live connect-test collector; cancelled when the test is abandoned/retried so a stale row's
        // state can never drive navigation after we deleted it.
        private var testJob: Job? = null

        fun setKind(kind: ConnectionChoice) {
            _state.value =
                _state.value.copy(
                    kind = kind,
                    presetId = if (kind == ConnectionChoice.NETWORK) _state.value.presetId else NetworkPresetId.CUSTOM,
                    showPlaintextWarning = false,
                    plaintextConfirmed = false,
                    duplicateConnection = false,
                )
        }

        fun setBouncerKind(kind: BouncerKind) {
            _state.value =
                _state.value.copy(
                    bouncerKind = kind,
                    showPlaintextWarning = false,
                    plaintextConfirmed = false,
                    duplicateConnection = false,
                )
        }

        fun editServer(server: ServerForm) {
            val current = _state.value
            val selected = networkPreset(current.presetId)
            _state.value =
                current.copy(
                    server = server,
                    presetId = if (selected?.matches(server) == true) current.presetId else NetworkPresetId.CUSTOM,
                    showPlaintextWarning = false,
                    plaintextConfirmed = false,
                    duplicateConnection = false,
                )
        }

        fun selectPreset(id: NetworkPresetId) {
            val current = _state.value
            val preset = networkPreset(id)
            if (preset == null) {
                _state.value =
                    current.copy(
                        presetId = NetworkPresetId.CUSTOM,
                        showPlaintextWarning = false,
                        plaintextConfirmed = false,
                        duplicateConnection = false,
                    )
                return
            }
            val (server, auth) = applyNetworkPreset(preset, current.server)
            _state.value =
                current.copy(
                    kind = ConnectionChoice.NETWORK,
                    server = server,
                    auth = auth,
                    presetId = id,
                    showPlaintextWarning = false,
                    plaintextConfirmed = false,
                    duplicateConnection = false,
                )
        }

        fun editDisplayName(name: String) {
            _state.value = _state.value.copy(displayName = name, duplicateConnection = false)
        }

        fun editAuth(auth: AuthForm) {
            _state.value = _state.value.copy(auth = auth, duplicateConnection = false)
        }

        fun editSojuLogin(login: SojuLoginForm) {
            _state.value = _state.value.copy(sojuLogin = login, duplicateConnection = false)
        }

        fun editZncLogin(login: ZncLoginForm) {
            _state.value = _state.value.copy(zncLogin = login, duplicateConnection = false)
        }

        /** Create the row, connect, and observe its live state. */
        fun submit(
            onOpenBouncerNetworks: (Long) -> Unit,
            onDone: () -> Unit,
        ) {
            if (!_state.value.canSubmit) return
            if (!_state.value.server.tls && !_state.value.plaintextConfirmed) {
                _state.value = _state.value.copy(showPlaintextWarning = true)
                return
            }
            testJob?.cancel()
            testJob =
                viewModelScope.launch {
                    val s = _state.value
                    val server =
                        if (s.isZnc) {
                            s.server.copy(
                                username = s.zncLogin.username.trim(),
                                realname = s.server.nick.trim(),
                            )
                        } else {
                            s.server
                        }
                    val effectiveName =
                        when {
                            s.isZnc -> s.zncLogin.network.trim()
                            s.displayName.isNotBlank() -> s.displayName.trim()
                            else -> networkPreset(s.presetId)?.displayName ?: s.server.host
                        }
                    val existingNetworks = networkRepository.observeNetworks().first()
                    val entity =
                        buildNetworkEntity(
                            server = server,
                            auth = s.activeAuth,
                            role = s.role,
                            name = effectiveName,
                        )
                    val existingNetworkIds = existingNetworks.mapTo(mutableSetOf()) { it.id }
                    val networkId = networkRepository.addNetwork(entity)
                    if (networkId in existingNetworkIds) {
                        // Do not reconnect and close the form when repository dedup finds this exact
                        // connection. Keep every field intact so user can change account details.
                        _state.value = _state.value.copy(duplicateConnection = true)
                        return@launch
                    }
                    _state.value =
                        _state.value.copy(
                            phase = AddNetworkPhase.TESTING,
                            networkId = networkId,
                            provisionalCreated = networkId !in existingNetworkIds,
                            connState = null,
                            error = null,
                        )
                    connectionManager.connect(networkId)

                    var completionHandled = false
                    connectionManager.connectionStates.collect { states ->
                        val cs = states[networkId] ?: return@collect
                        _state.value = _state.value.copy(connState = cs)
                        when (cs) {
                            is IrcClientState.Ready -> {
                                if (!completionHandled) {
                                    completionHandled = true
                                    if (_state.value.isZnc) bouncerKindPrefs.markZnc(networkId)
                                    if (_state.value.isSoju) onOpenBouncerNetworks(networkId) else onDone()
                                }
                            }

                            is IrcClientState.Failed -> {
                                _state.value = _state.value.copy(phase = AddNetworkPhase.FAILED, error = cs.reason)
                            }

                            else -> {}
                        }
                    }
                }
        }

        fun confirmPlaintext(
            onOpenBouncerNetworks: (Long) -> Unit,
            onDone: () -> Unit,
        ) {
            _state.value = _state.value.copy(showPlaintextWarning = false, plaintextConfirmed = true)
            submit(onOpenBouncerNetworks, onDone)
        }

        fun dismissPlaintextWarning() {
            _state.value = _state.value.copy(showPlaintextWarning = false)
        }

        /** Retry after a failed test: delete the half-created row, keep the fields, resubmit. */
        fun retry(
            onOpenBouncerNetworks: (Long) -> Unit,
            onDone: () -> Unit,
        ) {
            viewModelScope.launch {
                deleteHalfCreated()
                _state.value =
                    _state.value.copy(
                        phase = AddNetworkPhase.FORM,
                        networkId = null,
                        provisionalCreated = false,
                        connState = null,
                        error = null,
                    )
                submit(onOpenBouncerNetworks, onDone)
            }
        }

        /** Keep the failed row so the user can fix it later in NetworkSettings (decision #4). */
        fun saveAnyway(onDone: () -> Unit) {
            testJob?.cancel()
            viewModelScope.launch {
                val state = _state.value
                if (state.isZnc) state.networkId?.let { bouncerKindPrefs.markZnc(it) }
                // The row stays in the DB; leave the connect intent as-is for later repair.
                onDone()
            }
        }

        /** Back to the form to edit fields; delete the half-created row first. */
        fun editForm() {
            viewModelScope.launch {
                deleteHalfCreated()
                _state.value =
                    _state.value.copy(
                        phase = AddNetworkPhase.FORM,
                        networkId = null,
                        provisionalCreated = false,
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
            _state.value.networkId?.takeIf { _state.value.provisionalCreated }?.let { networkId ->
                networkRepository.deleteNetwork(networkId)
            }
        }
    }
