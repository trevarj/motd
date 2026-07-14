package io.github.trevarj.motd.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.bouncer.BouncerKind
import io.github.trevarj.motd.bouncer.SojuLoginForm
import io.github.trevarj.motd.bouncer.ZncLoginForm
import io.github.trevarj.motd.data.prefs.BouncerKindPrefs
import io.github.trevarj.motd.data.prefs.NoopBouncerKindPrefs
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.ui.settings.buildNetworkEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the onboarding wizard's side effects and folds their results back through the pure
 * [onboardingReducer]. All wizard state lives in [OnboardingState]; the ViewModel only orchestrates.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val networkRepository: NetworkRepository,
    private val connectionManager: ConnectionManager,
    private val bouncerKindPrefs: BouncerKindPrefs = NoopBouncerKindPrefs,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    private fun dispatch(action: OnboardingAction) {
        _state.value = onboardingReducer(_state.value, action)
    }

    // -- pure navigation / edits ---------------------------------------------------------------

    fun next() {
        val before = _state.value
        dispatch(OnboardingAction.Next)
        // Kick off the connect test when entering the CONNECT step.
        if (before.step == OnboardingStep.AUTH && _state.value.step == OnboardingStep.CONNECT) {
            runConnectTest()
        }
    }

    fun back() {
        // Leaving the connect test tears down the half-created network + its state collector, so
        // editing settings (e.g. toggling TLS) and reconnecting doesn't leave a stale actor
        // retrying with the old config — otherwise a failed TLS attempt keeps spamming
        // "Unable to parse TLS packet header" behind a later plaintext retry.
        val leavingConnect = _state.value.step == OnboardingStep.CONNECT
        dispatch(OnboardingAction.Back)
        if (leavingConnect) cleanupConnectTest()
    }

    fun chooseConnection(choice: ConnectionChoice) = dispatch(OnboardingAction.ChooseConnection(choice))
    fun chooseBouncerKind(kind: BouncerKind) = dispatch(OnboardingAction.ChooseBouncerKind(kind))
    fun applyLiberaPreset() = dispatch(OnboardingAction.ApplyLiberaPreset)
    fun editServer(server: ServerForm) = dispatch(OnboardingAction.EditServer(server))
    fun editAuth(auth: AuthForm) = dispatch(OnboardingAction.EditAuth(auth))
    fun editSojuLogin(login: SojuLoginForm) = dispatch(OnboardingAction.EditSojuLogin(login))
    fun editZncLogin(login: ZncLoginForm) = dispatch(OnboardingAction.EditZncLogin(login))
    fun toggleBouncerNetwork(netId: String) = dispatch(OnboardingAction.ToggleBouncerNetwork(netId))

    // -- side effects --------------------------------------------------------------------------

    // Tracks the in-flight connect-test coroutine (creates the network + collects its state) so a
    // new attempt or a Back cancels it — otherwise each attempt leaks a never-ending collector.
    private var connectTestJob: Job? = null

    private fun runConnectTest() {
        // Drop any network + collector from a prior attempt first, so reconnecting after a settings
        // change (e.g. TLS) rebuilds cleanly rather than piling up stale actors.
        connectTestJob?.cancel()
        val prior = _state.value.networkId
        connectTestJob = viewModelScope.launch {
            if (prior != null) networkRepository.deleteNetwork(prior)
            val s = _state.value
            val server = if (s.isZnc) {
                s.server.copy(
                    username = s.zncLogin.username.trim(),
                    realname = s.server.nick.trim(),
                )
            } else {
                s.server
            }
            val entity = buildNetworkEntity(
                server = server,
                auth = s.activeAuth,
                role = s.role,
                name = if (s.isZnc) s.zncLogin.network.trim() else s.server.host,
            )
            val networkId = networkRepository.addNetwork(entity)
            dispatch(OnboardingAction.NetworkCreated(networkId))

            connectionManager.connect(networkId)

            // Mirror this network's live IrcClientState into the wizard state log.
            connectionManager.connectionStates.collect { states ->
                val cs = states[networkId] ?: return@collect
                if (cs != _state.value.connState) {
                    dispatch(OnboardingAction.ConnStateChanged(cs))
                    if (cs is IrcClientState.Ready && s.isSoju && !_state.value.bouncerListLoaded) {
                        loadBouncerNetworks(networkId)
                    }
                }
            }
        }
    }

    /** Cancel the connect-test collector and delete its half-created network; reset connect state. */
    private fun cleanupConnectTest() {
        connectTestJob?.cancel()
        connectTestJob = null
        _state.value.networkId?.let { id -> viewModelScope.launch { networkRepository.deleteNetwork(id) } }
        _state.value = _state.value.copy(
            networkId = null,
            connState = null,
            stateLog = emptyList(),
            bouncerNetworks = emptyList(),
            bouncerListLoaded = false,
        )
    }

    /** Retry after a failed connect test: rerun (runConnectTest drops the prior half-created row). */
    fun retryConnect() {
        dispatch(OnboardingAction.Error(null))
        runConnectTest()
    }

    private fun loadBouncerNetworks(networkId: Long) = viewModelScope.launch {
        val client = connectionManager.clientFor(networkId) ?: return@launch
        val rows = runCatching { client.bouncerListNetworks() }.getOrDefault(emptyList())
            .map { bn ->
                BouncerNetworkRow(
                    netId = bn.netId,
                    name = bn.attrs["name"] ?: bn.attrs["host"] ?: bn.netId,
                    selected = false,
                )
            }
        dispatch(OnboardingAction.BouncerListed(rows))
    }

    /** Add a bouncer network via `bouncerAddNetwork`, then append it to the import list. */
    fun addBouncerNetwork(name: String, host: String) = viewModelScope.launch {
        val networkId = _state.value.networkId ?: return@launch
        val client = connectionManager.clientFor(networkId) ?: return@launch
        val attrs = mapOf("name" to name, "host" to host)
        val netId = runCatching { client.bouncerAddNetwork(attrs) }.getOrNull() ?: return@launch
        dispatch(OnboardingAction.BouncerAdded(BouncerNetworkRow(netId, name, selected = true)))
    }

    /**
     * Persist selected bouncer child networks as BOUNCER_CHILD rows, then finish.
     * For direct networks this is a no-op beyond finishing.
     */
    fun finish(onDone: () -> Unit) = viewModelScope.launch {
        val s = _state.value
        val rootId = s.networkId
        if (s.isZnc && rootId != null) bouncerKindPrefs.markZnc(rootId)
        if (s.isSoju && rootId != null) {
            // The root's BOUNCER NETWORK handler may have already auto-created child rows from
            // soju's notifications; dedup against them by bouncerNetId so we never create a second
            // child for the same upstream (duplicate children bind the same netId and fail SASL
            // 904). Then explicitly connect each imported child: a plain reconcile will not rebuild
            // a child actor that parked on a transient failure during onboarding, but connect()
            // force-rebuilds it, so the freshly imported network connects without an app restart.
            val existing = networkRepository.childrenOf(rootId)
            s.bouncerNetworks.filter { it.selected }.forEach { row ->
                val childId = existing.firstOrNull { it.bouncerNetId == row.netId }?.id
                    ?: networkRepository.addNetwork(
                        childEntity(rootParentId = rootId, row = row, seed = s),
                    )
                connectionManager.connect(childId)
            }
        }
        onDone()
    }

    // Children share the root's transport identity + SASL; buildNetworkEntity applies the same
    // soju identity-seed defaults (nick/username/realname from the SASL login username) so the
    // child's USER/NICK lines are well-formed, then binds via bouncerNetId.
    private fun childEntity(rootParentId: Long, row: BouncerNetworkRow, seed: OnboardingState) =
        buildNetworkEntity(
            server = seed.server,
            auth = seed.sojuLogin.toAuthForm(),
            role = io.github.trevarj.motd.data.db.NetworkRole.BOUNCER_CHILD,
            name = row.name,
            parentId = rootParentId,
            bouncerNetId = row.netId,
        )
}
