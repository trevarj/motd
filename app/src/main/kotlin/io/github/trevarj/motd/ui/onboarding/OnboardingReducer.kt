package io.github.trevarj.motd.ui.onboarding

import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState

/**
 * Pure state machine for the onboarding wizard. No Android/Compose/coroutine dependencies so the
 * whole flow is unit-testable via [onboardingReducer]. The ViewModel owns the side effects
 * (creating the NetworkEntity, connecting, listing bouncer networks) and folds their results back
 * in through actions.
 */

/** The wizard pages, in pager order. */
enum class OnboardingStep {
    WELCOME,
    CHOICE,
    SERVER,
    AUTH,
    CONNECT,
    FINISH,
}

/** Top-level path chosen on the CHOICE page. */
enum class ConnectionChoice { SOJU, NETWORK }

/** Auth mechanism selected on the AUTH page. Mirrors SaslMechanism names for persistence. */
enum class AuthMode { NONE, PLAIN, EXTERNAL }

/** Default IRC ports: 6697 for TLS, 6667 for plaintext. */
const val PORT_TLS = "6697"
const val PORT_PLAIN = "6667"

/** Editable server-form fields (step 3). */
data class ServerForm(
    val host: String = "",
    val port: String = PORT_TLS,
    val tls: Boolean = true,
    val nick: String = "",
    val username: String = "",
    val realname: String = "",
) {
    /** Effective username: explicit value, else falls back to nick (spec default). */
    val effectiveUsername: String get() = username.ifBlank { nick }

    /** True when [port] holds the default value for either TLS state (so a toggle may re-default it). */
    val portIsDefault: Boolean get() = port.isBlank() || port == PORT_TLS || port == PORT_PLAIN

    /**
     * Toggle TLS and re-default the port when the user hasn't typed a custom one, so 6697/6667
     * track the switch without clobbering an explicit port.
     */
    fun withTls(enabled: Boolean): ServerForm = copy(
        tls = enabled,
        port = if (portIsDefault) (if (enabled) PORT_TLS else PORT_PLAIN) else port,
    )

    /**
     * Minimal validity for enabling "next" on the direct-network SERVER step: host, valid port,
     * and a nick. The soju path validates against [isValidForSoju] (no nick collected there).
     */
    val isValid: Boolean
        get() = isValidForSoju && nick.isNotBlank()

    /** soju SERVER step needs only host + a valid port (identity comes from SASL). */
    val isValidForSoju: Boolean
        get() = host.isNotBlank() &&
            port.toIntOrNull()?.let { it in 1..65535 } == true
}

/** Auth-form fields (step 4). */
data class AuthForm(
    val mode: AuthMode = AuthMode.NONE,
    val saslUser: String = "",
    val saslPassword: String = "",
    val certAlias: String? = null,
) {
    val isValid: Boolean
        get() = when (mode) {
            AuthMode.NONE -> true
            AuthMode.PLAIN -> saslUser.isNotBlank() && saslPassword.isNotBlank()
            AuthMode.EXTERNAL -> certAlias != null
        }
}

/** A bouncer network row on the CONNECT page (soju import list / add form results). */
data class BouncerNetworkRow(
    val netId: String,
    val name: String,
    val selected: Boolean,
)

/** Full wizard state. */
data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val choice: ConnectionChoice? = null,
    val server: ServerForm = ServerForm(),
    val auth: AuthForm = AuthForm(),
    // Connect-test progress.
    val networkId: Long? = null,
    val connState: IrcClientState? = null,
    val stateLog: List<IrcClientState> = emptyList(),
    val bouncerNetworks: List<BouncerNetworkRow> = emptyList(),
    val bouncerListLoaded: Boolean = false,
    val error: String? = null,
) {
    val isSoju: Boolean get() = choice == ConnectionChoice.SOJU

    /** Network role implied by the choice (soju root vs. direct network). */
    val role: NetworkRole
        get() = if (isSoju) NetworkRole.BOUNCER_ROOT else NetworkRole.DIRECT

    /** True once the connect test reached a Ready state. */
    val isReady: Boolean get() = connState is IrcClientState.Ready

    /** Whether the "next" affordance should be enabled on the current step. */
    val canAdvance: Boolean
        get() = when (step) {
            OnboardingStep.WELCOME -> true
            OnboardingStep.CHOICE -> choice != null
            // soju collects only host/port on SERVER (identity is SASL); direct also needs a nick.
            OnboardingStep.SERVER -> if (isSoju) server.isValidForSoju else server.isValid
            OnboardingStep.AUTH -> auth.isValid
            OnboardingStep.CONNECT -> isReady
            OnboardingStep.FINISH -> true
        }
}

/** Libera.Chat one-tap preset (spec). */
val LIBERA_PRESET = ServerForm(host = "irc.libera.chat", port = "6697", tls = true)

/** All actions that can mutate wizard state. Pure — no side effects here. */
sealed interface OnboardingAction {
    data object Next : OnboardingAction
    data object Back : OnboardingAction
    data class GoTo(val step: OnboardingStep) : OnboardingAction

    data class ChooseConnection(val choice: ConnectionChoice) : OnboardingAction
    data object ApplyLiberaPreset : OnboardingAction

    data class EditServer(val server: ServerForm) : OnboardingAction
    data class EditAuth(val auth: AuthForm) : OnboardingAction

    // Connect-test lifecycle (folded back from ViewModel side effects).
    data class NetworkCreated(val networkId: Long) : OnboardingAction
    data class ConnStateChanged(val state: IrcClientState) : OnboardingAction
    data class BouncerListed(val rows: List<BouncerNetworkRow>) : OnboardingAction
    data class ToggleBouncerNetwork(val netId: String) : OnboardingAction
    data class BouncerAdded(val row: BouncerNetworkRow) : OnboardingAction
    data class Error(val message: String?) : OnboardingAction
}

/** Steps in order; used for Next/Back traversal. */
private val STEP_ORDER = OnboardingStep.entries

private fun nextStep(step: OnboardingStep): OnboardingStep {
    val idx = STEP_ORDER.indexOf(step)
    return STEP_ORDER.getOrElse(idx + 1) { step }
}

private fun prevStep(step: OnboardingStep): OnboardingStep {
    val idx = STEP_ORDER.indexOf(step)
    return STEP_ORDER.getOrElse(idx - 1) { step }
}

/** Pure reducer: (state, action) -> state. */
fun onboardingReducer(state: OnboardingState, action: OnboardingAction): OnboardingState =
    when (action) {
        is OnboardingAction.Next ->
            if (state.canAdvance) state.copy(step = nextStep(state.step)) else state

        is OnboardingAction.Back -> state.copy(step = prevStep(state.step))

        is OnboardingAction.GoTo -> state.copy(step = action.step)

        is OnboardingAction.ChooseConnection ->
            // soju logs in with SASL PLAIN (bouncer user + password); force the mode so the
            // AUTH step only needs to collect the two fields and the entity persists as PLAIN.
            state.copy(
                choice = action.choice,
                auth = if (action.choice == ConnectionChoice.SOJU) {
                    state.auth.copy(mode = AuthMode.PLAIN)
                } else {
                    state.auth
                },
            )

        is OnboardingAction.ApplyLiberaPreset ->
            state.copy(
                choice = ConnectionChoice.NETWORK,
                // Preserve any identity fields the user already typed.
                server = state.server.copy(
                    host = LIBERA_PRESET.host,
                    port = LIBERA_PRESET.port,
                    tls = LIBERA_PRESET.tls,
                ),
            )

        is OnboardingAction.EditServer -> state.copy(server = action.server)

        is OnboardingAction.EditAuth ->
            // soju is always SASL PLAIN: pin the mode so a residual NONE/EXTERNAL can't leak into
            // the persisted saslMechanism even if the picker path is bypassed.
            state.copy(
                auth = if (state.isSoju) action.auth.copy(mode = AuthMode.PLAIN) else action.auth,
            )

        is OnboardingAction.NetworkCreated -> state.copy(networkId = action.networkId)

        is OnboardingAction.ConnStateChanged ->
            state.copy(
                connState = action.state,
                stateLog = state.stateLog + action.state,
                error = (action.state as? IrcClientState.Failed)?.reason ?: state.error,
            )

        is OnboardingAction.BouncerListed ->
            state.copy(bouncerNetworks = action.rows, bouncerListLoaded = true)

        is OnboardingAction.ToggleBouncerNetwork ->
            state.copy(
                bouncerNetworks = state.bouncerNetworks.map {
                    if (it.netId == action.netId) it.copy(selected = !it.selected) else it
                },
            )

        is OnboardingAction.BouncerAdded ->
            state.copy(bouncerNetworks = state.bouncerNetworks + action.row)

        is OnboardingAction.Error -> state.copy(error = action.message)
    }
