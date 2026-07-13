package io.github.trevarj.motd.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.data.prefs.PresetEnrollmentPrefs
import io.github.trevarj.motd.obfs.VlessLink
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.liberaEndpointChanged
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
    // Opt-in IRC-over-WebSocket URL (plans/19 §3.3); blank = default TCP/TLS transport.
    val wsUrl: String = "",
    // Opt-in obfuscation/proxy (plans/20 Phase 1). NONE = direct; SOCKS5/REALITY reveal host/port;
    // TOR pins Orbot's 127.0.0.1:9050. Port kept as a string for text-field editing.
    val obfsMode: ObfsMode = ObfsMode.NONE,
    val proxyHost: String = "",
    val proxyPort: String = "",
    val obfsLink: String = "",
    val server: ServerForm = ServerForm(),
    val auth: AuthForm = AuthForm(),
    // Round 5 (plans/16 §5.3): live status + autoConnect editing.
    val connState: IrcClientState = IrcClientState.Disconnected,
    val autoConnect: Boolean = true,
    val parentName: String? = null,   // root name for a BOUNCER_CHILD's "Managed by" row
    /** A bouncer transport/login change can invalidate all locally imported child mirrors. */
    val pendingBouncerIdentityChange: BouncerIdentityChange? = null,
) {
    val vlessLinkError: String?
        get() = if (obfsMode == ObfsMode.EMBEDDED_REALITY) vlessLinkValidationError(obfsLink) else null
    val hasUnsavedChanges: Boolean get() {
        val current = entity ?: return false
        return displayName.trim().ifBlank { current.name } != current.name ||
            wsUrl.trim().ifBlank { null } != current.wsUrl?.trim()?.ifBlank { null } ||
            obfsMode != (current.obfsMode ?: ObfsMode.NONE) ||
            (obfsMode == ObfsMode.SOCKS5 && (
                proxyHost.trim().ifBlank { null } != current.proxyHost?.trim()?.ifBlank { null } ||
                    proxyPort.toIntOrNull() != current.proxyPort
                )) ||
            (obfsMode == ObfsMode.EMBEDDED_REALITY &&
                obfsLink.trim().ifBlank { null } != current.obfsLink?.trim()?.ifBlank { null }) ||
            server != current.toServerForm() || auth != current.toAuthForm() || autoConnect != current.autoConnect
    }
    val isValid: Boolean get() = server.isValid && auth.isValid && vlessLinkError == null
    val canSave: Boolean get() = isValid && hasUnsavedChanges
}

/** User-safe validation copy for the VLESS field; never echoes a potentially sensitive URI. */
internal fun vlessLinkValidationError(link: String): String? {
    return VlessLink.parse(link).exceptionOrNull()?.message
}

/** Details displayed before changing the endpoint/credentials a bouncer's child mirrors inherit. */
data class BouncerIdentityChange(val localMirrorCount: Int)

/**
 * Whether two bouncer-root rows address the same inherited child transport/login identity.
 *
 * This deliberately excludes display and IRC identity fields (nick/user/realname), the SASL
 * password, and proxy/VLESS configuration: those edits do not make existing local mirrors refer
 * to a different bouncer account. Host comparison follows DNS semantics and treats incidental
 * whitespace in optional text fields as non-semantic.
 */
internal fun bouncerIdentityChanged(before: NetworkEntity, after: NetworkEntity): Boolean =
    normalizeBouncerHost(before.host) != normalizeBouncerHost(after.host) ||
        before.port != after.port ||
        before.tls != after.tls ||
        before.wsUrl.normalizedOptional() != after.wsUrl.normalizedOptional() ||
        before.saslMechanism != after.saslMechanism ||
        before.saslUser.normalizedOptional() != after.saslUser.normalizedOptional() ||
        before.clientCertAlias.normalizedOptional() != after.clientCertAlias.normalizedOptional()

private fun normalizeBouncerHost(host: String): String = host.trim().trimEnd('.').lowercase()
private fun String?.normalizedOptional(): String? = this?.trim()?.ifBlank { null }

@HiltViewModel
class NetworkSettingsViewModel @Inject constructor(
    private val networkRepository: NetworkRepository,
    private val connectionManager: ConnectionManager,
    private val presetEnrollmentPrefs: PresetEnrollmentPrefs,
) : ViewModel() {

    private val _state = MutableStateFlow(NetworkSettingsUiState())
    val state: StateFlow<NetworkSettingsUiState> = _state.asStateFlow()

    private var networkId: Long = 0
    private var pendingBouncerUpdate: NetworkEntity? = null

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
                wsUrl = n?.wsUrl.orEmpty(),
                obfsMode = n?.obfsMode ?: ObfsMode.NONE,
                proxyHost = n?.proxyHost.orEmpty(),
                proxyPort = n?.proxyPort?.toString().orEmpty(),
                obfsLink = n?.obfsLink.orEmpty(),
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
    fun editWsUrl(url: String) { _state.value = _state.value.copy(wsUrl = url) }

    fun editObfsMode(mode: ObfsMode) { _state.value = _state.value.copy(obfsMode = mode) }
    fun editProxyHost(host: String) { _state.value = _state.value.copy(proxyHost = host) }
    fun editProxyPort(port: String) {
        _state.value = _state.value.copy(proxyPort = port.filter(Char::isDigit))
    }
    fun editObfsLink(link: String) { _state.value = _state.value.copy(obfsLink = link) }

    /** "Route via Tor (Orbot)" shortcut: pin SOCKS5 at 127.0.0.1:9050 (plans/19 §3.4). */
    fun useTorShortcut() {
        _state.value = _state.value.copy(
            obfsMode = ObfsMode.TOR,
            proxyHost = "127.0.0.1",
            proxyPort = "9050",
        )
    }

    fun connect() = viewModelScope.launch { connectionManager.connect(networkId) }
    fun disconnect() = viewModelScope.launch { connectionManager.disconnect(networkId) }

    /** Auto-connect is staged with the rest of the form so the labeled Save action is coherent. */
    fun setAutoConnect(enabled: Boolean) { _state.value = _state.value.copy(autoConnect = enabled) }

    fun openServerBuffer(onOpen: (Long) -> Unit) = viewModelScope.launch {
        onOpen(connectionManager.ensureServerBuffer(networkId))
    }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val current = _state.value.entity ?: return@launch
        val updated = updatedNetwork(current)
        val children = if (current.role == NetworkRole.BOUNCER_ROOT && bouncerIdentityChanged(current, updated)) {
            networkRepository.childrenOf(current.id)
        } else {
            emptyList()
        }
        if (children.isNotEmpty()) {
            pendingBouncerUpdate = updated
            _state.value = _state.value.copy(
                pendingBouncerIdentityChange = BouncerIdentityChange(children.size),
            )
        } else {
            if (liberaEndpointChanged(current, updated)) {
                presetEnrollmentPrefs.revokeLiberaEligibility(current.id)
            }
            networkRepository.updateNetwork(updated)
            onDone()
        }
    }

    /** Keep child mirrors only when the user explicitly accepts their inherited identity change. */
    fun keepLocalMirrors(onDone: () -> Unit) = resolveBouncerIdentityChange(
        removeLocalMirrors = false,
        onDone = onDone,
    )

    /** Remove only local child mirrors/history; this never deletes bouncer-side networks. */
    fun removeLocalMirrors(onDone: () -> Unit) = resolveBouncerIdentityChange(
        removeLocalMirrors = true,
        onDone = onDone,
    )

    fun cancelBouncerIdentityChange() {
        pendingBouncerUpdate = null
        _state.value = _state.value.copy(pendingBouncerIdentityChange = null)
    }

    private fun resolveBouncerIdentityChange(removeLocalMirrors: Boolean, onDone: () -> Unit) =
        viewModelScope.launch {
            val updated = pendingBouncerUpdate ?: return@launch
            // Write the root first. Deleting a local child cascades only through its local Room
            // rows; it does not issue a bouncer-side network deletion command.
            networkRepository.updateNetwork(updated)
            if (removeLocalMirrors) {
                networkRepository.childrenOf(updated.id).forEach { child ->
                    networkRepository.deleteNetwork(child.id)
                }
            }
            pendingBouncerUpdate = null
            _state.value = _state.value.copy(
                entity = updated,
                pendingBouncerIdentityChange = null,
            )
            onDone()
        }

    private fun updatedNetwork(current: NetworkEntity): NetworkEntity =
        buildNetworkEntity(
            server = _state.value.server,
            auth = _state.value.auth,
            role = current.role,
            id = current.id,
            // A blank alias falls back to the existing name (never persist an empty display name).
            name = _state.value.displayName.trim().ifBlank { current.name },
            parentId = current.parentId,
            bouncerNetId = current.bouncerNetId,
            // Blank clears the WSS override, reverting to the default TCP/TLS transport.
            wsUrl = _state.value.wsUrl.trim().ifBlank { null },
            // Obfuscation/proxy (plans/20 Phase 1).
            obfsMode = _state.value.obfsMode,
            proxyHost = _state.value.proxyHost,
            proxyPort = _state.value.proxyPort.toIntOrNull(),
            obfsLink = _state.value.obfsLink,
            // Persist the current autoConnect value alongside the form fields.
        ).copy(autoConnect = _state.value.autoConnect)

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        _state.value.entity?.let {
            presetEnrollmentPrefs.revokeLiberaEligibility(it.id)
            networkRepository.deleteNetwork(it.id)
        }
        onDone()
    }
}
