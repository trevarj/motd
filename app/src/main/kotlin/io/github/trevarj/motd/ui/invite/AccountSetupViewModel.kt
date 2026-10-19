package io.github.trevarj.motd.ui.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.AccountEnrollmentDraft
import io.github.trevarj.motd.data.prefs.AccountEnrollmentPhase
import io.github.trevarj.motd.data.prefs.AccountEnrollmentProvider
import io.github.trevarj.motd.data.prefs.InviteEnrollmentStore
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.client.AccountRegistrationPolicy
import io.github.trevarj.motd.irc.client.AccountRegistrationResult
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.client.NickServIdentifySyntax
import io.github.trevarj.motd.irc.client.SaslMechanism
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.isDirectLiberaEndpoint
import io.github.trevarj.motd.service.isDirectOftcEndpoint
import io.github.trevarj.motd.ui.settings.sanitizeNickInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import javax.inject.Inject

private const val ACCOUNT_READY_TIMEOUT_MS = 30_000L
private const val NICKSERV_REPLY_TIMEOUT_MS = 15_000L

enum class AccountSetupPhase { FORM, SUBMITTING, VERIFY, ACTIVATING, SUCCESS, FAILED, UNSUPPORTED }

data class AccountSetupUiState(
    val phase: AccountSetupPhase = AccountSetupPhase.FORM,
    val network: NetworkEntity? = null,
    val provider: AccountEnrollmentProvider? = null,
    val account: String = "",
    val email: String = "",
    val password: String = "",
    val emailRequired: Boolean = false,
    val verification: String = "",
    val serverMessage: String? = null,
    val verificationUrl: String? = null,
    val error: String? = null,
)

sealed interface AccountSetupEvent {
    data object Complete : AccountSetupEvent
}

@HiltViewModel
class AccountSetupViewModel
    @Inject
    constructor(
        private val networks: NetworkRepository,
        private val connections: ConnectionManager,
        private val enrollment: InviteEnrollmentStore,
    ) : ViewModel() {
        private val _state = MutableStateFlow(AccountSetupUiState())
        val state: StateFlow<AccountSetupUiState> = _state.asStateFlow()
        private val _events = MutableSharedFlow<AccountSetupEvent>(extraBufferCapacity = 1)
        val events: SharedFlow<AccountSetupEvent> = _events.asSharedFlow()
        private var networkId: Long = 0
        private var initialized = false

        fun init(networkId: Long) {
            if (initialized) return
            initialized = true
            this.networkId = networkId
            viewModelScope.launch {
                val network = networks.networkById(networkId)
                val draft = enrollment.accountDraft(networkId)
                val resumingActivation = draft?.phase == AccountEnrollmentPhase.ACTIVATING
                if (network == null || network.role != NetworkRole.DIRECT || !network.tls ||
                    (network.saslMechanism != SaslMechanism.NONE.name && !resumingActivation)
                ) {
                    _state.value = AccountSetupUiState(phase = AccountSetupPhase.UNSUPPORTED, error = "Account setup requires an unauthenticated direct TLS network")
                    return@launch
                }
                var client = connections.clientFor(networkId)
                if (client == null && !resumingActivation) {
                    connections.connect(networkId)
                    withTimeoutOrNull(ACCOUNT_READY_TIMEOUT_MS) {
                        connections.connectionStates
                            .map { it[networkId] }
                            .filter { it is IrcClientState.Ready }
                            .first()
                    }
                    client = connections.clientFor(networkId)
                }
                val provider = draft?.provider ?: accountEnrollmentProvider(network, client?.accountRegistrationPolicy != null)
                if (provider == null) {
                    _state.value = AccountSetupUiState(phase = AccountSetupPhase.UNSUPPORTED, network = network, error = "This server does not advertise account registration")
                    return@launch
                }
                val readyNick = (connections.connectionStates.value[networkId] as? IrcClientState.Ready)?.nick ?: network.nick
                _state.value =
                    AccountSetupUiState(
                        phase = if (draft?.phase == AccountEnrollmentPhase.AWAITING_VERIFICATION) AccountSetupPhase.VERIFY else AccountSetupPhase.FORM,
                        network = network,
                        provider = provider,
                        account = draft?.account ?: readyNick,
                        email = draft?.email.orEmpty(),
                        password = draft?.password.orEmpty(),
                        emailRequired =
                            provider in setOf(AccountEnrollmentProvider.LIBERA, AccountEnrollmentProvider.OFTC) ||
                                client?.accountRegistrationPolicy?.emailRequired == true,
                        verificationUrl = draft?.verificationUrl,
                    )
                if (draft?.phase == AccountEnrollmentPhase.ACTIVATING) activate(draft)
            }
        }

        fun editAccount(value: String) {
            _state.value = _state.value.copy(account = value, error = null)
        }

        fun editEmail(value: String) {
            _state.value = _state.value.copy(email = value, error = null)
        }

        fun editPassword(value: String) {
            _state.value = _state.value.copy(password = value, error = null)
        }

        fun editVerification(value: String) {
            _state.value = _state.value.copy(verification = value, error = null)
        }

        fun submit() {
            val state = _state.value
            val network = state.network ?: return
            val provider = state.provider ?: return
            val password = state.password
            val account = sanitizeNickInput(state.account)
            if (account == null || provider == AccountEnrollmentProvider.OFTC && account.length < 2) {
                fail("Choose a valid account nickname")
                return
            }
            val email = state.email.trim().takeIf(String::isNotEmpty)
            if ((state.emailRequired || email != null) && !validEmail(email)) {
                fail("Enter a valid recovery email")
                return
            }
            val policy = if (provider == AccountEnrollmentProvider.IRCV3) connections.clientFor(networkId)?.accountRegistrationPolicy else null
            accountPasswordError(password, provider, policy)?.let {
                fail(it)
                return
            }
            viewModelScope.launch {
                var resumedDraft: AccountEnrollmentDraft? = null
                try {
                    val currentNick = (connections.connectionStates.value[networkId] as? IrcClientState.Ready)?.nick ?: network.nick
                    val effectiveAccount = if (currentNick != account) reconnectWithNick(network, account) else currentNick
                    val existingDraft = enrollment.accountDraft(networkId)
                    val draft =
                        existingDraft?.takeIf {
                            it.phase == AccountEnrollmentPhase.PREPARED && it.provider == provider && it.account == effectiveAccount &&
                                it.email == email && it.password == password
                        } ?: AccountEnrollmentDraft(networkId, provider, effectiveAccount, email, password)
                    if (draft === existingDraft) resumedDraft = draft
                    enrollment.putAccountDraft(draft)
                    _state.value = _state.value.copy(phase = AccountSetupPhase.SUBMITTING, account = effectiveAccount, error = null)
                    when (provider) {
                        AccountEnrollmentProvider.IRCV3 -> registerIrcv3(draft)
                        AccountEnrollmentProvider.LIBERA -> registerLibera(draft, resumed = resumedDraft != null)
                        AccountEnrollmentProvider.OFTC -> registerOftc(draft, resumed = resumedDraft != null)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: IrcCommandException) {
                    if (error.code == "ACCOUNT_EXISTS" && resumedDraft?.provider == AccountEnrollmentProvider.IRCV3) {
                        activate(requireNotNull(resumedDraft))
                    } else {
                        fail(error.message ?: error.code)
                    }
                } catch (error: Exception) {
                    fail(error.message ?: "Account registration failed")
                }
            }
        }

        fun verify() {
            val raw = _state.value.verification
            viewModelScope.launch {
                val draft =
                    enrollment.accountDraft(networkId) ?: run {
                        fail("Registration details expired; start again")
                        return@launch
                    }
                val code =
                    if (draft.provider == AccountEnrollmentProvider.OFTC) {
                        null
                    } else {
                        parseVerification(raw, draft) ?: run {
                            fail("Paste the verification code or exact verification command")
                            return@launch
                        }
                    }
                _state.value = _state.value.copy(phase = AccountSetupPhase.SUBMITTING, error = null)
                try {
                    when (draft.provider) {
                        AccountEnrollmentProvider.IRCV3 -> {
                            val result = connections.clientFor(networkId)?.verifyAccount(draft.account, requireNotNull(code)) ?: error("Connection unavailable")
                            if (result is AccountRegistrationResult.Success) {
                                activate(draft)
                            } else {
                                fail((result as AccountRegistrationResult.VerificationRequired).message)
                            }
                        }

                        AccountEnrollmentProvider.LIBERA -> {
                            val response = sendNickServ("VERIFY REGISTER ${draft.account} ${requireNotNull(code)}")
                            if (liberaVerificationSucceeded(response)) {
                                _state.value = _state.value.copy(serverMessage = response)
                                activate(draft)
                            } else {
                                _state.value = _state.value.copy(phase = AccountSetupPhase.VERIFY, serverMessage = null, error = response)
                            }
                        }

                        AccountEnrollmentProvider.OFTC -> {
                            activateOftc(draft)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    fail(error.message ?: "Verification failed")
                }
            }
        }

        fun requestOftcVerification() {
            viewModelScope.launch {
                val draft = enrollment.accountDraft(networkId)?.takeIf { it.provider == AccountEnrollmentProvider.OFTC } ?: return@launch
                try {
                    val identify = sendNickServ("IDENTIFY ${draft.password} ${draft.account}")
                    if (nickServRejected(identify)) error(identify)
                    val response = sendNickServ("REVERIFY")
                    val url = parseOftcVerificationUrl(response)
                    val pending = draft.copy(phase = AccountEnrollmentPhase.AWAITING_VERIFICATION, verificationUrl = url ?: draft.verificationUrl)
                    enrollment.putAccountDraft(pending)
                    _state.value =
                        _state.value.copy(
                            phase = AccountSetupPhase.VERIFY,
                            serverMessage = response,
                            verificationUrl = pending.verificationUrl,
                            error = if (url == null) "Open the NickServ conversation to find the verification link." else null,
                        )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _state.value = _state.value.copy(phase = AccountSetupPhase.VERIFY, error = error.message ?: "Could not request another link")
                }
            }
        }

        fun retry() {
            _state.value = _state.value.copy(phase = AccountSetupPhase.FORM, error = null)
        }

        fun dismissReminder() {
            viewModelScope.launch { enrollment.setAccountReminder(networkId, false) }
        }

        private suspend fun registerIrcv3(draft: AccountEnrollmentDraft) {
            val client = connections.clientFor(networkId) ?: error("Connection unavailable")
            when (val result = client.registerAccount("*", draft.email, draft.password)) {
                is AccountRegistrationResult.Success -> {
                    _state.value = _state.value.copy(serverMessage = result.message)
                    activate(draft.copy(account = result.account))
                }

                is AccountRegistrationResult.VerificationRequired -> {
                    val pending = draft.copy(account = result.account, phase = AccountEnrollmentPhase.AWAITING_VERIFICATION)
                    enrollment.putAccountDraft(pending)
                    _state.value = _state.value.copy(phase = AccountSetupPhase.VERIFY, serverMessage = result.message)
                }
            }
        }

        private suspend fun registerLibera(
            draft: AccountEnrollmentDraft,
            resumed: Boolean,
        ) {
            val response = sendNickServ("REGISTER ${draft.password} ${draft.email}")
            val alreadyRegistered = response.contains("already registered", ignoreCase = true)
            if (!liberaRegistrationAccepted(response) && !(resumed && alreadyRegistered)) {
                fail(response)
                return
            }
            val pending = draft.copy(phase = AccountEnrollmentPhase.AWAITING_VERIFICATION)
            enrollment.putAccountDraft(pending)
            _state.value = _state.value.copy(phase = AccountSetupPhase.VERIFY, serverMessage = response)
        }

        private suspend fun registerOftc(
            draft: AccountEnrollmentDraft,
            resumed: Boolean,
        ) {
            val response = sendNickServ("REGISTER ${draft.password} ${draft.email}")
            val rejected = response.contains("already registered", ignoreCase = true) || response.contains("cannot be registered", ignoreCase = true)
            if (rejected && !resumed) {
                fail(response)
                return
            }
            val url = parseOftcVerificationUrl(response)
            val pending = draft.copy(phase = AccountEnrollmentPhase.AWAITING_VERIFICATION, verificationUrl = url)
            enrollment.putAccountDraft(pending)
            _state.value =
                _state.value.copy(
                    phase = AccountSetupPhase.VERIFY,
                    serverMessage = response,
                    verificationUrl = url,
                    error = if (url == null) "Open the NickServ conversation to find the verification link." else null,
                )
        }

        private suspend fun sendNickServ(body: String): String =
            coroutineScope {
                val client = connections.clientFor(networkId) ?: error("Connection unavailable")
                val responses = Channel<String>(Channel.UNLIMITED)
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        client.broadcastEvents
                            .filter {
                                it is IrcEvent.ChatMessage &&
                                    it.source.nick.equals("NickServ", ignoreCase = true)
                            }.map { (it as IrcEvent.ChatMessage).text }
                            .collect(responses::send)
                    }
                try {
                    if (!client.sendSensitivePrivmsg("NickServ", body)) error("Connection unavailable")
                    val first =
                        withTimeoutOrNull(NICKSERV_REPLY_TIMEOUT_MS) { responses.receive() }
                            ?: return@coroutineScope "NickServ did not answer in time. Check the NickServ conversation before retrying."
                    buildList {
                        add(first)
                        while (true) add(withTimeoutOrNull(400L) { responses.receive() } ?: break)
                    }.joinToString("\n")
                } finally {
                    collector.cancel()
                }
            }

        private suspend fun activate(draft: AccountEnrollmentDraft) {
            if (draft.provider == AccountEnrollmentProvider.OFTC) {
                activateOftc(draft)
            } else {
                activateSasl(draft)
            }
        }

        private suspend fun activateOftc(draft: AccountEnrollmentDraft) {
            _state.value = _state.value.copy(phase = AccountSetupPhase.ACTIVATING, error = null)
            val identify = sendNickServ("IDENTIFY ${draft.password} ${draft.account}")
            if (nickServRejected(identify)) {
                _state.value = _state.value.copy(phase = AccountSetupPhase.VERIFY, error = identify)
                return
            }
            val info = sendNickServ("INFO ${draft.account}")
            if (!oftcAccountVerified(info)) {
                _state.value =
                    _state.value.copy(
                        phase = AccountSetupPhase.VERIFY,
                        serverMessage = info,
                        error = "OFTC has not confirmed verification yet. Complete the CAPTCHA, then try again.",
                    )
                return
            }
            val network = networks.networkById(networkId) ?: error("Network no longer exists")
            val activated = activateOftcNetwork(network, draft)
            networks.updateNetwork(activated)
            enrollment.clearAccountDraft(networkId)
            enrollment.setAccountReminder(networkId, false)
            _state.value = _state.value.copy(phase = AccountSetupPhase.SUCCESS, network = activated)
            _events.emit(AccountSetupEvent.Complete)
        }

        private suspend fun activateSasl(draft: AccountEnrollmentDraft) {
            val network = networks.networkById(networkId) ?: error("Network no longer exists")
            val guestNetwork =
                network.copy(
                    saslMechanism = SaslMechanism.NONE.name,
                    saslUser = null,
                    saslPassword = null,
                )
            enrollment.putAccountDraft(draft.copy(phase = AccountEnrollmentPhase.ACTIVATING))
            _state.value = _state.value.copy(phase = AccountSetupPhase.ACTIVATING, error = null)
            val wasAlreadyActivated = network.saslMechanism == SaslMechanism.PLAIN.name
            val activated =
                network.copy(
                    nick = draft.account,
                    saslMechanism = SaslMechanism.PLAIN.name,
                    saslUser = draft.account,
                    saslPassword = draft.password,
                    nickServPassword = null,
                    nickServRecoveryEnabled = false,
                )
            networks.updateNetwork(activated)
            connections.connect(networkId)
            val result =
                if (wasAlreadyActivated && connections.connectionStates.value[networkId] is IrcClientState.Ready) {
                    connections.connectionStates.value[networkId]
                } else {
                    withTimeoutOrNull(ACCOUNT_READY_TIMEOUT_MS) {
                        connections.connectionStates
                            .map { it[networkId] }
                            .dropWhile { it is IrcClientState.Ready }
                            .filter { it is IrcClientState.Ready || it is IrcClientState.Failed }
                            .first()
                    }
                }
            if (result is IrcClientState.Ready) {
                enrollment.clearAccountDraft(networkId)
                enrollment.setAccountReminder(networkId, false)
                _state.value = _state.value.copy(phase = AccountSetupPhase.SUCCESS, network = activated)
                _events.emit(AccountSetupEvent.Complete)
            } else {
                networks.updateNetwork(guestNetwork)
                connections.connect(networkId)
                val reason = (result as? IrcClientState.Failed)?.reason ?: "Account login timed out"
                if (reason.contains("SASL", ignoreCase = true)) {
                    enrollment.putAccountDraft(draft.copy(phase = AccountEnrollmentPhase.AWAITING_VERIFICATION))
                    _state.value = _state.value.copy(phase = AccountSetupPhase.VERIFY, error = reason)
                } else {
                    fail(reason)
                }
            }
        }

        private suspend fun reconnectWithNick(
            network: NetworkEntity,
            account: String,
        ): String {
            networks.updateNetwork(network.copy(nick = account, username = account))
            connections.connect(networkId)
            val ready =
                withTimeoutOrNull(ACCOUNT_READY_TIMEOUT_MS) {
                    connections.connectionStates
                        .map { it[networkId] }
                        .dropWhile { it is IrcClientState.Ready }
                        .filter { it is IrcClientState.Ready }
                        .first()
                } ?: error("Could not connect with that nickname")
            val actualNick = (ready as IrcClientState.Ready).nick
            _state.value = _state.value.copy(account = actualNick)
            return actualNick
        }

        private fun fail(message: String) {
            _state.value = _state.value.copy(phase = AccountSetupPhase.FAILED, error = message)
        }
    }

internal fun accountEnrollmentProvider(
    network: NetworkEntity,
    hasIrcv3Registration: Boolean,
): AccountEnrollmentProvider? =
    when {
        network.isDirectLiberaEndpoint() -> AccountEnrollmentProvider.LIBERA
        network.isDirectOftcEndpoint() -> AccountEnrollmentProvider.OFTC
        hasIrcv3Registration -> AccountEnrollmentProvider.IRCV3
        else -> null
    }

internal fun activateOftcNetwork(
    network: NetworkEntity,
    draft: AccountEnrollmentDraft,
): NetworkEntity =
    network.copy(
        nick = draft.account,
        saslMechanism = SaslMechanism.NONE.name,
        saslUser = null,
        saslPassword = null,
        nickServPassword = draft.password,
        nickServIdentifySyntax = NickServIdentifySyntax.PASSWORD_NICK.name,
        nickServRecoveryEnabled = true,
        nickServRecoverySequence = "REGAIN",
    )

internal fun validEmail(value: String?): Boolean = value != null && value.length <= 254 && '@' in value && value.none { it.isWhitespace() || it.isISOControl() }

internal fun accountPasswordError(
    password: String,
    provider: AccountEnrollmentProvider,
    policy: AccountRegistrationPolicy?,
): String? {
    if (password.isEmpty()) return "Choose a password"
    if (password.any(Char::isISOControl)) return "Password must not contain control characters"
    if (provider != AccountEnrollmentProvider.IRCV3 && password.any(Char::isWhitespace)) return "NickServ passwords must not contain whitespace"
    val bytes = password.toByteArray(Charsets.UTF_8).size
    if (bytes > 300) return "Password must be at most 300 UTF-8 bytes"
    if (provider == AccountEnrollmentProvider.IRCV3) {
        if (policy?.minPasswordLength?.let { bytes < it } == true) return "Password is shorter than the server minimum"
        if (policy?.maxPasswordLength?.let { bytes > it } == true) return "Password exceeds the server maximum"
    }
    return null
}

internal fun parseOftcVerificationUrl(response: String): String? =
    Regex("https://[^\\s<>\\\"']+")
        .findAll(response)
        .map { it.value.trimEnd('.', ',', ')', ']') }
        .firstOrNull { candidate ->
            runCatching { URI(candidate) }
                .getOrNull()
                ?.let { uri ->
                    uri.scheme.equals("https", ignoreCase = true) &&
                        uri.userInfo == null &&
                        uri.host?.let { host -> host.equals("oftc.net", true) || host.endsWith(".oftc.net", true) } == true
                } == true
        }

internal fun oftcAccountVerified(response: String): Boolean = !response.contains("unverified", ignoreCase = true) && Regex("\\bverified\\b", RegexOption.IGNORE_CASE).containsMatchIn(response)

internal fun liberaRegistrationAccepted(response: String): Boolean =
    !nickServRejected(response) &&
        (
            response.contains("activation instructions", ignoreCase = true) ||
                response.contains("verification command", ignoreCase = true) ||
                response.contains("complete registration", ignoreCase = true)
        )

internal fun liberaVerificationSucceeded(response: String): Boolean =
    !nickServRejected(response) &&
        (
            response.contains("has now been verified", ignoreCase = true) ||
                response.contains("already verified", ignoreCase = true) ||
                response.contains("verification successful", ignoreCase = true)
        )

internal fun nickServRejected(response: String): Boolean =
    response.contains("did not answer", ignoreCase = true) ||
        listOf("incorrect", "invalid password", "not registered", "not identified", "does not exist", "failed")
            .any { response.contains(it, ignoreCase = true) }

internal fun parseVerification(
    raw: String,
    draft: AccountEnrollmentDraft,
): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.any(Char::isISOControl)) return null
    if (' ' !in trimmed) return trimmed.takeIf { it.length <= 300 }
    val parts = trimmed.removePrefix("/").split(Regex("\\s+"))
    val command =
        when {
            parts.size == 6 && parts[0].equals("msg", true) && parts[1].equals("NickServ", true) -> parts.drop(2)
            parts.size == 4 -> parts
            else -> return null
        }
    if (!command[0].equals("VERIFY", true) || !command[1].equals("REGISTER", true) || command[2] != draft.account) return null
    return command[3].takeIf { it.isNotEmpty() && it.length <= 300 }
}
