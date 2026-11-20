package io.github.trevarj.motd.ui.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.data.prefs.InviteEnrollmentStore
import io.github.trevarj.motd.data.prefs.OnboardingPrefs
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.data.repo.normalizeHost
import io.github.trevarj.motd.invite.JoinInvite
import io.github.trevarj.motd.invite.JoinInviteCodec
import io.github.trevarj.motd.invite.JoinInviteV1
import io.github.trevarj.motd.invite.JoinInviteV2
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.service.ChannelJoinOutcome
import io.github.trevarj.motd.service.ChannelJoinRejectionKind
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.isDirectLiberaEndpoint
import io.github.trevarj.motd.service.isDirectOftcEndpoint
import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.ServerForm
import io.github.trevarj.motd.ui.settings.buildNetworkEntity
import io.github.trevarj.motd.ui.settings.sanitizeNickInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val READY_TIMEOUT_MS = 30_000L
private const val JOIN_TIMEOUT_MS = 20_000L

enum class JoinInvitePhase { REVIEW, IDENTITY, CONNECTING, JOINING, READY, FAILED }

data class JoinInviteUiState(
    val invite: JoinInvite? = null,
    val phase: JoinInvitePhase = JoinInvitePhase.REVIEW,
    val nick: String = "",
    val actualNick: String? = null,
    val networkId: Long? = null,
    val error: String? = null,
    val rejectionKind: ChannelJoinRejectionKind? = null,
    val accountSetupAvailable: Boolean = false,
    val showPlaintextWarning: Boolean = false,
    val plaintextConfirmed: Boolean = false,
    val pendingBufferId: Long? = null,
    val exitRequested: Boolean = false,
)

data class JoinInviteAccountSetup(
    val networkId: Long,
    val channel: String?,
)

@HiltViewModel
class JoinInviteViewModel
    @Inject
    constructor(
        private val networks: NetworkRepository,
        private val buffers: BufferRepository,
        private val connections: ConnectionManager,
        private val certs: CertTrustStore,
        private val enrollment: InviteEnrollmentStore,
        private val onboarding: OnboardingPrefs,
    ) : ViewModel() {
        private val _state = MutableStateFlow(JoinInviteUiState())
        val state: StateFlow<JoinInviteUiState> = _state.asStateFlow()
        private val _accountSetupEvents = MutableSharedFlow<JoinInviteAccountSetup>(extraBufferCapacity = 1)
        val accountSetupEvents: SharedFlow<JoinInviteAccountSetup> = _accountSetupEvents.asSharedFlow()
        private var work: Job? = null
        private var initializedPayload: String? = null
        private var provisionalNetworkId: Long? = null
        private var importedPin = false
        private var leaving = false
        private var committing = false
        private var exitFailed = false
        private var committedBufferId: Long? = null

        fun init(payload: String) {
            if (initializedPayload == payload) return
            initializedPayload = payload
            _state.value =
                runCatching { JoinInviteCodec.decode(payload) }
                    .fold(
                        onSuccess = { JoinInviteUiState(invite = it) },
                        onFailure = { JoinInviteUiState(phase = JoinInvitePhase.FAILED, error = it.message ?: "Invalid invite") },
                    )
        }

        fun continueToIdentity() {
            if (leaving || committing) return
            if (_state.value.invite != null) _state.value = _state.value.copy(phase = JoinInvitePhase.IDENTITY, error = null)
        }

        fun editNick(value: String) {
            _state.value = _state.value.copy(nick = value, error = null)
        }

        fun connect() {
            if (leaving || _state.value.phase != JoinInvitePhase.IDENTITY) return
            val invite = _state.value.invite ?: return
            val nick = sanitizeNickInput(_state.value.nick)
            if (nick == null) {
                _state.value = _state.value.copy(error = "Choose a nickname without spaces, commas, or channel symbols")
                return
            }
            if (!invite.tls && !_state.value.plaintextConfirmed) {
                _state.value = _state.value.copy(showPlaintextWarning = true)
                return
            }
            launchJoin(invite, nick)
        }

        fun confirmPlaintext() {
            _state.value = _state.value.copy(showPlaintextWarning = false, plaintextConfirmed = true)
            connect()
        }

        fun dismissPlaintextWarning() {
            _state.value = _state.value.copy(showPlaintextWarning = false)
        }

        fun retry() {
            if (leaving || committing || _state.value.phase != JoinInvitePhase.FAILED) return
            if (exitFailed) {
                leave()
                return
            }
            committedBufferId?.let { bufferId ->
                committing = true
                _state.value = _state.value.copy(phase = JoinInvitePhase.JOINING, error = null)
                viewModelScope.launch {
                    finishCommit { finishOnboarding(bufferId) }
                }
                return
            }
            _state.value = _state.value.copy(phase = JoinInvitePhase.IDENTITY, error = null, rejectionKind = null)
        }

        fun editChannelKey(value: String) {
            (_state.value.invite as? JoinInviteV1)?.let { invite ->
                _state.value = _state.value.copy(invite = invite.copy(channelKey = value.takeIf(String::isNotBlank)), error = null)
            }
        }

        fun setupAccount() {
            if (leaving || committing) return
            val invite = _state.value.invite as? JoinInviteV1 ?: return
            val id = _state.value.networkId ?: return
            _accountSetupEvents.tryEmit(JoinInviteAccountSetup(id, invite.channel))
        }

        fun onBack() {
            if (leaving || committing) return
            val current = _state.value
            when {
                current.showPlaintextWarning -> dismissPlaintextWarning()
                current.phase == JoinInvitePhase.IDENTITY -> _state.value = current.copy(phase = JoinInvitePhase.REVIEW, error = null)
                current.phase == JoinInvitePhase.READY -> Unit
                else -> leave()
            }
        }

        fun onBufferOpened(bufferId: Long) {
            val current = _state.value
            if (current.pendingBufferId == bufferId) _state.value = current.copy(pendingBufferId = null)
        }

        fun onExitHandled() {
            val current = _state.value
            if (current.exitRequested) _state.value = current.copy(exitRequested = false)
        }

        private fun leave() {
            leaving = true
            viewModelScope.launch {
                try {
                    work?.cancelAndJoin()
                    if (committedBufferId == null) {
                        provisionalNetworkId?.let {
                            enrollment.setProvisionalNetwork(it, false)
                            enrollment.setImportedCertPin(it, false)
                            networks.deleteNetwork(it)
                        }
                        _state.value.networkId?.let {
                            enrollment.restoreChannelKeyBackup(it)
                            enrollment.setImportedCertPin(it, false)
                        }
                        if (importedPin) {
                            _state.value.invite?.let { certs.unpin(it.host, it.port) }
                        }
                    }
                    _state.value = _state.value.copy(exitRequested = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    exitFailed = true
                    leaving = false
                    fail(error.message ?: "Could not leave the invitation")
                }
            }
        }

        private fun launchJoin(
            invite: JoinInvite,
            nick: String,
        ) {
            work?.cancel()
            _state.value = _state.value.copy(phase = JoinInvitePhase.CONNECTING, error = null)
            work =
                viewModelScope.launch {
                    try {
                        val all = networks.observeNetworks().first()
                        val existing = all.firstOrNull { compatibleInviteNetwork(it, invite) }
                        checkPin(invite)
                        ensureActive()
                        val networkId =
                            if (existing != null) {
                                existing.id.also {
                                    val provisional = enrollment.isProvisionalNetwork(it)
                                    val hasImportedPin = enrollment.hasImportedCertPin(it)
                                    ensureActive()
                                    if (provisional) provisionalNetworkId = it
                                    if (hasImportedPin) importedPin = true
                                }
                            } else {
                                withContext(NonCancellable) {
                                    createNetwork(invite, nick).also { provisionalNetworkId = it }
                                }
                            }
                        ensureActive()
                        _state.value = _state.value.copy(networkId = networkId)
                        if (existing == null) enrollment.setProvisionalNetwork(networkId, true)
                        if (importedPin) enrollment.setImportedCertPin(networkId, true)
                        ensureActive()

                        connections.connect(networkId)
                        val ready =
                            withTimeoutOrNull(READY_TIMEOUT_MS) {
                                connections.connectionStates
                                    .map { it[networkId] }
                                    .filter { it is IrcClientState.Ready || it is IrcClientState.Failed }
                                    .first()
                            }
                        if (ready is IrcClientState.Failed) {
                            fail(
                                ready.reason,
                                if (ready.reason.contains("ACCOUNT_REQUIRED", true)) {
                                    ChannelJoinRejectionKind.ACCOUNT_REQUIRED
                                } else {
                                    ChannelJoinRejectionKind.OTHER
                                },
                                accountSetupAvailable = false,
                            )
                            return@launch
                        }
                        val connected =
                            ready as? IrcClientState.Ready ?: run {
                                fail("Connection timed out")
                                return@launch
                            }
                        _state.value = _state.value.copy(phase = JoinInvitePhase.JOINING, actualNick = connected.nick)
                        if (invite is JoinInviteV2) {
                            val bufferId = connections.ensureQueryBuffer(networkId, invite.contactNick)
                            complete(networkId, bufferId)
                            return@launch
                        }

                        val channelInvite = invite as JoinInviteV1
                        val client = connections.clientFor(networkId)
                        val normalize: (String) -> String = client?.isupport?.let { support -> support::normalize } ?: IrcIdentityRules()::normalize
                        val normalized = normalize(channelInvite.channel)
                        buffers.joinedBufferId(networkId, normalized)?.let {
                            complete(networkId, it)
                            return@launch
                        }

                        if (channelInvite.channelKey != null) enrollment.prepareChannelKeyBackup(networkId, normalized)
                        if (!connections.joinChannel(networkId, channelInvite.channel, channelInvite.channelKey)) {
                            fail("Connection closed before channel join")
                            return@launch
                        }
                        awaitJoin(networkId, channelInvite, normalize)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        fail(error.message ?: "Invitation failed")
                    }
                }
        }

        private suspend fun awaitJoin(
            networkId: Long,
            invite: JoinInviteV1,
            normalize: (String) -> String,
        ) = coroutineScope {
            val rejection =
                async {
                    connections.channelJoinOutcomes
                        .filter {
                            it is ChannelJoinOutcome.Rejected && it.networkId == networkId &&
                                normalize(it.channel) == normalize(invite.channel)
                        }.first() as ChannelJoinOutcome.Rejected
                }
            val joined =
                async {
                    buffers
                        .observeJoinedChannelNames(networkId)
                        .filter { names -> names.any { normalize(it) == normalize(invite.channel) } }
                        .first()
                    buffers.joinedBufferId(networkId, normalize(invite.channel))
                }
            val result =
                withTimeoutOrNull(JOIN_TIMEOUT_MS) {
                    while (true) {
                        if (joined.isCompleted) return@withTimeoutOrNull joined.await()?.let { Result.success(it) }
                        if (rejection.isCompleted) return@withTimeoutOrNull Result.failure(JoinRejected(rejection.await()))
                        kotlinx.coroutines.delay(25)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }
            rejection.cancel()
            joined.cancel()
            result?.fold(
                onSuccess = { complete(networkId, it) },
                onFailure = { error ->
                    val outcome = (error as? JoinRejected)?.outcome
                    fail(
                        outcome?.reason ?: error.message.orEmpty(),
                        outcome?.kind ?: ChannelJoinRejectionKind.OTHER,
                        accountSetupAvailable = outcome?.kind == ChannelJoinRejectionKind.ACCOUNT_REQUIRED,
                    )
                },
            ) ?: fail("Channel join timed out")
        }

        private suspend fun checkPin(invite: JoinInvite) {
            val incoming = invite.certSha256 ?: return
            val existing = certs.pinnedFor(invite.host, invite.port)
            if (existing != null && !existing.equals(incoming, ignoreCase = true)) {
                error("Invitation certificate conflicts with existing trusted certificate")
            }
            if (existing == null) {
                withContext(NonCancellable) {
                    certs.pin(invite.host, invite.port, incoming)
                    importedPin = true
                }
            }
        }

        private suspend fun createNetwork(
            invite: JoinInvite,
            nick: String,
        ): Long =
            networks.addNetwork(
                buildNetworkEntity(
                    server =
                        ServerForm(
                            host = invite.host,
                            port = invite.port.toString(),
                            tls = invite.tls,
                            nick = nick,
                            username = nick,
                            realname = nick,
                        ),
                    auth = AuthForm(),
                    role = NetworkRole.DIRECT,
                    name = invite.networkName,
                ),
                requireNew = true,
            )

        private suspend fun complete(
            networkId: Long,
            bufferId: Long,
        ) {
            val network = networks.networkById(networkId)
            val accountSupported =
                network.isDirectLiberaEndpoint() || network.isDirectOftcEndpoint() ||
                    connections.clientFor(networkId)?.hasCap("draft/account-registration") == true
            currentCoroutineContext().ensureActive()
            if (leaving) return
            committing = true
            finishCommit {
                enrollment.commitInvite(networkId, enableAccountReminder = accountSupported && network?.saslMechanism == "NONE")
                provisionalNetworkId = null
                importedPin = false
                committedBufferId = bufferId
                finishOnboarding(bufferId)
            }
        }

        private suspend fun finishOnboarding(bufferId: Long) {
            onboarding.markCompleted()
            _state.value = _state.value.copy(phase = JoinInvitePhase.READY, pendingBufferId = bufferId)
        }

        private inline fun finishCommit(action: () -> Unit) {
            val failure =
                try {
                    action()
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    error
                } finally {
                    committing = false
                }
            if (failure != null) fail(failure.message ?: "Invitation failed")
        }

        private fun fail(
            message: String,
            kind: ChannelJoinRejectionKind = ChannelJoinRejectionKind.OTHER,
            accountSetupAvailable: Boolean = false,
        ) {
            _state.value =
                _state.value.copy(
                    phase = JoinInvitePhase.FAILED,
                    error = message,
                    rejectionKind = kind,
                    accountSetupAvailable = accountSetupAvailable,
                )
        }
    }

private class JoinRejected(
    val outcome: ChannelJoinOutcome.Rejected,
) : Exception(outcome.reason)

internal fun compatibleInviteNetwork(
    network: NetworkEntity,
    invite: JoinInvite,
): Boolean =
    network.role == NetworkRole.DIRECT &&
        normalizeHost(network.host) == normalizeHost(invite.host) && network.port == invite.port && network.tls == invite.tls &&
        network.saslUser?.contains('/') != true && network.serverPassword?.contains('/') != true
