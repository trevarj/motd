package io.github.trevarj.motd.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.bouncer.BouncerKind
import io.github.trevarj.motd.bouncer.SojuLoginForm
import io.github.trevarj.motd.bouncer.ZncLoginForm
import io.github.trevarj.motd.data.prefs.BouncerKindPrefs
import io.github.trevarj.motd.data.prefs.HistorySyncDepth
import io.github.trevarj.motd.data.prefs.NoopBouncerKindPrefs
import io.github.trevarj.motd.data.prefs.OnboardingPrefs
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.ui.settings.addnetwork.NetworkPresetId
import io.github.trevarj.motd.ui.settings.addnetwork.networkPreset
import io.github.trevarj.motd.ui.settings.buildNetworkEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val BOUNCER_CLIENT_WAIT_ATTEMPTS = 40
private const val BOUNCER_CLIENT_WAIT_DELAY_MS = 250L

/**
 * Drives the onboarding wizard's side effects and folds their results back through the pure
 * [onboardingReducer]. All wizard state lives in [OnboardingState]; the ViewModel only orchestrates.
 */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val networkRepository: NetworkRepository,
        private val connectionManager: ConnectionManager,
        private val onboardingPrefs: OnboardingPrefs,
        private val bouncerOperations: OnboardingBouncerOperations,
        private val settingsRepository: SettingsRepository,
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
            if (
                before.step == OnboardingStep.AUTH &&
                before.canAdvance &&
                !before.isBouncer &&
                !before.server.tls &&
                !before.plaintextConfirmed
            ) {
                dispatch(OnboardingAction.ShowPlaintextWarning)
                return
            }
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

        fun selectPreset(id: NetworkPresetId) = dispatch(OnboardingAction.SelectPreset(id))

        fun editServer(server: ServerForm) = dispatch(OnboardingAction.EditServer(server))

        fun editAuth(auth: AuthForm) = dispatch(OnboardingAction.EditAuth(auth))

        fun editSojuLogin(login: SojuLoginForm) = dispatch(OnboardingAction.EditSojuLogin(login))

        fun editZncLogin(login: ZncLoginForm) = dispatch(OnboardingAction.EditZncLogin(login))

        fun toggleBouncerNetwork(netId: String) = dispatch(OnboardingAction.ToggleBouncerNetwork(netId))

        fun editBouncerAddDraft(draft: BouncerAddDraft) = dispatch(OnboardingAction.EditBouncerAddDraft(draft))

        fun selectHistorySyncDepth(depth: HistorySyncDepth) = dispatch(OnboardingAction.SelectHistorySyncDepth(depth))

        fun confirmPlaintext() {
            dispatch(OnboardingAction.ConfirmPlaintext)
            next()
        }

        fun dismissPlaintextWarning() = dispatch(OnboardingAction.DismissPlaintextWarning)

        fun skip(onDone: () -> Unit) =
            viewModelScope.launch {
                onboardingPrefs.markCompleted()
                onDone()
            }

        // -- side effects --------------------------------------------------------------------------

        // Tracks the in-flight connect-test coroutine (creates the network + collects its state) so a
        // new attempt or a Back cancels it — otherwise each attempt leaks a never-ending collector.
        private var connectTestJob: Job? = null

        // soju delivers LISTNETWORKS as ordinary BOUNCER NETWORK notifications. Keep observing the
        // client's live snapshot after the initial request: the reply can legitimately arrive after
        // bouncerListNetworks() has returned its short empty snapshot.
        private var bouncerNetworksJob: Job? = null
        private var nextBouncerSessionGeneration = 0L
        private var nextBouncerListAttempt = 0L

        private fun runConnectTest() {
            // Drop any network + collector from a prior attempt first, so reconnecting after a settings
            // change (e.g. TLS) rebuilds cleanly rather than piling up stale actors.
            connectTestJob?.cancel()
            stopBouncerNetworkSync()
            _state.value =
                _state.value.copy(
                    bouncerDiscovery = null,
                    bouncerSessionGeneration = 0L,
                    bouncerListAttempt = 0L,
                    bouncerAdd = BouncerAddState.Idle,
                    bouncerAddDraft = BouncerAddDraft(),
                )
            val prior = _state.value.networkId
            connectTestJob =
                viewModelScope.launch {
                    if (prior != null) networkRepository.deleteNetwork(prior)
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
                    val entity =
                        buildNetworkEntity(
                            server = server,
                            auth = s.activeAuth,
                            role = s.role,
                            name =
                                when {
                                    s.isZnc -> s.zncLogin.network.trim()
                                    else -> networkPreset(s.presetId)?.displayName ?: s.server.host
                                },
                        )
                    val networkId = networkRepository.addNetwork(entity)
                    dispatch(OnboardingAction.NetworkCreated(networkId))

                    connectionManager.connect(networkId)

                    // Mirror this network's live IrcClientState into the wizard state log.
                    connectionManager.connectionStates.collect { states ->
                        val cs = states[networkId] ?: return@collect
                        if (cs != _state.value.connState) {
                            dispatch(OnboardingAction.ConnStateChanged(cs))
                            if (cs is IrcClientState.Ready && s.isSoju) {
                                // A reconnect swaps the physical client while retaining the root row. Rebind
                                // discovery on every Ready transition so an old client's StateFlow cannot
                                // keep the import list stale after the socket has recovered.
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
            stopBouncerNetworkSync()
            _state.value.networkId?.let { id -> viewModelScope.launch { networkRepository.deleteNetwork(id) } }
            _state.value =
                _state.value.copy(
                    networkId = null,
                    connState = null,
                    stateLog = emptyList(),
                    bouncerDiscovery = null,
                    bouncerSessionGeneration = 0L,
                    bouncerListAttempt = 0L,
                    bouncerAdd = BouncerAddState.Idle,
                    bouncerAddDraft = BouncerAddDraft(),
                )
        }

        /** Retry after a failed connect test: rerun (runConnectTest drops the prior half-created row). */
        fun retryConnect() {
            dispatch(OnboardingAction.Error(null))
            runConnectTest()
        }

        private fun loadBouncerNetworks(networkId: Long) {
            stopBouncerNetworkSync()
            val current = _state.value
            val sessionGeneration =
                if (current.networkId == networkId && current.bouncerDiscovery != null) {
                    current.bouncerSessionGeneration
                } else {
                    ++nextBouncerSessionGeneration
                }
            val attempt = ++nextBouncerListAttempt
            dispatch(OnboardingAction.BouncerListLoading(networkId, sessionGeneration, attempt))
            bouncerNetworksJob =
                viewModelScope.launch {
                    val snapshots =
                        awaitCurrentOnboardingResource(
                            expectedNetworkId = networkId,
                            currentNetworkId = { _state.value.networkId },
                            lookup = bouncerOperations::snapshots,
                            maxAttempts = BOUNCER_CLIENT_WAIT_ATTEMPTS,
                            delayMs = BOUNCER_CLIENT_WAIT_DELAY_MS,
                        ) ?: run {
                            dispatch(
                                OnboardingAction.BouncerListFailed(
                                    networkId,
                                    sessionGeneration,
                                    attempt,
                                    BouncerOperationError.ConnectionLost,
                                ),
                            )
                            return@launch
                        }
                    launch {
                        snapshots.collect { networks ->
                            // A retry can replace the root while a late notification from the old
                            // connection is in flight. Do not let that stale snapshot mutate the wizard.
                            dispatch(
                                OnboardingAction.BouncerSnapshot(
                                    networkId,
                                    sessionGeneration,
                                    networks.toBouncerRows(),
                                ),
                            )
                        }
                    }
                    // This sends LISTNETWORKS for bouncers that do not push an initial state. The
                    // collector above remains active after its short snapshot wait, so delayed ordinary
                    // BOUNCER NETWORK replies still populate the import list.
                    try {
                        dispatch(
                            OnboardingAction.BouncerListed(
                                networkId,
                                sessionGeneration,
                                attempt,
                                bouncerOperations.list(networkId).toBouncerRows(),
                            ),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        dispatch(
                            OnboardingAction.BouncerListFailed(
                                networkId,
                                sessionGeneration,
                                attempt,
                                bouncerOperationError(error),
                            ),
                        )
                    }
                }
        }

        /** Explicit refresh after a failed or stale LISTNETWORKS request. */
        fun retryBouncerDiscovery() {
            _state.value.networkId?.let(::loadBouncerNetworks)
        }

        private fun stopBouncerNetworkSync() {
            bouncerNetworksJob?.cancel()
            bouncerNetworksJob = null
        }

        /** Add a bouncer network only once per pending request; the reducer owns the input draft. */
        fun addBouncerNetwork() {
            val state = _state.value
            if (state.bouncerAdd is BouncerAddState.Submitting || !state.bouncerAddDraft.isValid) return
            val networkId = state.networkId ?: return
            val sessionGeneration = state.bouncerSessionGeneration
            val draft = state.bouncerAddDraft
            dispatch(OnboardingAction.BouncerAddSubmitting(networkId, sessionGeneration))
            viewModelScope.launch {
                try {
                    val netId = bouncerOperations.add(networkId, draft.name, draft.host)
                    dispatch(
                        OnboardingAction.BouncerAdded(
                            networkId,
                            sessionGeneration,
                            BouncerNetworkRow(netId, draft.name, selected = true),
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    dispatch(OnboardingAction.BouncerAddFailed(networkId, sessionGeneration, bouncerOperationError(error)))
                }
            }
        }

        /**
         * Persist selected bouncer child networks as BOUNCER_CHILD rows, then finish.
         * For direct networks this is a no-op beyond finishing.
         */
        fun finish(onDone: () -> Unit) =
            viewModelScope.launch {
                val s = _state.value
                stopBouncerNetworkSync()
                val rootId = s.networkId
                if (s.isZnc && rootId != null) bouncerKindPrefs.markZnc(rootId)
                if (s.isSoju && rootId != null) {
                    // Persist before connecting: each child's first catch-up reads this depth, so writing it
                    // afterwards would let the imported networks start on the previous window.
                    settingsRepository.setHistorySyncDepth(s.historySyncDepth)
                    // Explicitly import only selected rows. Then connect each imported child: a plain
                    // reconcile will not rebuild a child actor that parked on a transient failure during
                    // onboarding, but connect() force-rebuilds it, so the freshly imported network connects
                    // without an app restart.
                    val existing = networkRepository.childrenOf(rootId)
                    s.bouncerNetworks.filter { it.selected }.forEach { row ->
                        val childId =
                            existing.firstOrNull { it.bouncerNetId == row.netId }?.id
                                ?: networkRepository.addNetwork(
                                    childEntity(rootParentId = rootId, row = row, seed = s),
                                )
                        connectionManager.connect(childId)
                    }
                }
                onboardingPrefs.markCompleted()
                onDone()
            }

        // Children share the root's transport identity + SASL; buildNetworkEntity applies the same
        // soju identity-seed defaults (nick/username/realname from the SASL login username) so the
        // child's USER/NICK lines are well-formed, then binds via bouncerNetId.
        private fun childEntity(
            rootParentId: Long,
            row: BouncerNetworkRow,
            seed: OnboardingState,
        ) = buildNetworkEntity(
            server = seed.server,
            auth = seed.sojuLogin.toAuthForm(),
            role = io.github.trevarj.motd.data.db.NetworkRole.BOUNCER_CHILD,
            name = row.name,
            parentId = rootParentId,
            bouncerNetId = row.netId,
        )
    }

private fun Map<String, Map<String, String>>.toBouncerRows(): List<BouncerNetworkRow> =
    map { (netId, attrs) ->
        BouncerNetworkRow(
            netId = netId,
            name = attrs["name"] ?: attrs["host"] ?: netId,
            selected = false,
        )
    }

private fun List<io.github.trevarj.motd.irc.client.BouncerNetwork>.toBouncerRows(): List<BouncerNetworkRow> = associate { it.netId to it.attrs }.toBouncerRows()

internal suspend fun <T> awaitCurrentOnboardingResource(
    expectedNetworkId: Long,
    currentNetworkId: () -> Long?,
    lookup: (Long) -> T?,
    maxAttempts: Int,
    delayMs: Long,
): T? {
    repeat(maxAttempts.coerceAtLeast(0)) {
        if (currentNetworkId() != expectedNetworkId) return null
        lookup(expectedNetworkId)?.let { return it }
        delay(delayMs)
    }
    if (currentNetworkId() != expectedNetworkId) return null
    return lookup(expectedNetworkId)
}
