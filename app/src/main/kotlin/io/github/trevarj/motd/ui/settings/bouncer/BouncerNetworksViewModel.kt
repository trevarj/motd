package io.github.trevarj.motd.ui.settings.bouncer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.bouncer.BouncerServCapabilities
import io.github.trevarj.motd.bouncer.BouncerServClient
import io.github.trevarj.motd.bouncer.BouncerServCommand
import io.github.trevarj.motd.bouncer.BouncerServCommands
import io.github.trevarj.motd.bouncer.BouncerServResult
import io.github.trevarj.motd.bouncer.ChannelCommandFields
import io.github.trevarj.motd.bouncer.NetworkCommandFields
import io.github.trevarj.motd.bouncer.UserCommandFields
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BouncerNetworksUiState(
    val root: NetworkEntity? = null,
    val rootState: IrcClientState = IrcClientState.Disconnected,
    val rows: List<BouncerNetRow> = emptyList(),
    val capabilities: BouncerServCapabilities = BouncerServCapabilities(),
    val transcript: List<BouncerTranscriptEntry> = emptyList(),
    val channels: List<BouncerChannelRow> = emptyList(),
    val selectedTab: BouncerControlTab = BouncerControlTab.NETWORKS,
    val loading: Boolean = false,
    val probing: Boolean = false,
    val commandBusy: Boolean = false,
    val busyNetIds: Set<String> = emptySet(),
    val notice: String? = null,
    val error: String? = null,
    val pendingUserDeletion: PendingUserDeletion? = null,
)

/**
 * soju control center. Machine-readable BOUNCER state remains authoritative for network identity;
 * BouncerServ supplies the guided account/channel/admin actions and the persistent console.
 */
@HiltViewModel
class BouncerNetworksViewModel @Inject constructor(
    private val networkRepository: NetworkRepository,
    private val connectionManager: ConnectionManager,
    private val bouncerServ: BouncerServClient,
    private val messageDao: MessageDao,
) : ViewModel() {

    private val _state = MutableStateFlow(BouncerNetworksUiState())
    val state: StateFlow<BouncerNetworksUiState> = _state.asStateFlow()

    private var rootNetworkId: Long = 0
    private var initialized = false

    fun init(rootNetworkId: Long) {
        if (initialized) return
        initialized = true
        this.rootNetworkId = rootNetworkId
        viewModelScope.launch {
            val root = networkRepository.networkById(rootNetworkId)
            _state.update { current -> current.copy(root = root) }
        }
        viewModelScope.launch {
            messageDao.observeBouncerTranscript(rootNetworkId).collectLatest { rows ->
                _state.update { current ->
                    current.copy(
                        transcript = rows.asReversed().map { row ->
                            BouncerTranscriptEntry(row.sender, row.text, row.serverTime, row.isSelf)
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            connectionManager.connectionStates.collect { states ->
                val connectionState = states[rootNetworkId] ?: IrcClientState.Disconnected
                val wasReady = _state.value.rootState is IrcClientState.Ready
                _state.value = _state.value.copy(rootState = connectionState)
                if (connectionState is IrcClientState.Ready && !wasReady) {
                    refreshInline(showLoading = true)
                    probeInline()
                }
            }
        }
    }

    fun connect() = viewModelScope.launch { connectionManager.connect(rootNetworkId) }

    fun selectTab(tab: BouncerControlTab) {
        _state.value = _state.value.copy(selectedTab = tab)
    }

    fun clearFeedback() {
        _state.value = _state.value.copy(notice = null, error = null)
    }

    fun refresh() = viewModelScope.launch { refreshInline(showLoading = true) }

    fun probeCapabilities() = viewModelScope.launch { probeInline() }

    /** Insert the local BOUNCER_CHILD mirror; no-op if the netId is already imported. */
    fun importNetwork(row: BouncerNetRow) = viewModelScope.launch {
        val root = _state.value.root ?: return@launch
        val existing = networkRepository.childrenOf(rootNetworkId)
        if (existing.any { it.bouncerNetId == row.netId }) {
            refreshInline()
            return@launch
        }
        networkRepository.addNetwork(
            root.copy(
                id = 0,
                name = row.name,
                role = NetworkRole.BOUNCER_CHILD,
                parentId = root.id,
                bouncerNetId = row.netId,
                host = row.host ?: root.host,
            ),
        )
        refreshInline()
    }

    /** Remove only the local mirror; the bouncer keeps the network. */
    fun removeLocal(row: BouncerNetRow) = viewModelScope.launch {
        row.childNetworkId?.let { networkRepository.deleteNetwork(it) }
        refreshInline()
    }

    fun createNetwork(fields: NetworkCommandFields) {
        buildAndExecute(refreshNetworks = true) { BouncerServCommands.networkCreate(fields) }
    }

    fun updateNetwork(name: String, changed: NetworkCommandFields) {
        execute(BouncerServCommands.networkUpdate(name, changed), refreshNetworks = true)
    }

    fun deleteFromBouncer(row: BouncerNetRow) {
        execute(BouncerServCommands.networkDelete(row.name), refreshNetworks = true) {
            row.childNetworkId?.let { networkRepository.deleteNetwork(it) }
        }
    }

    fun channelStatus(network: String) = executeWithResult(BouncerServCommands.channelStatus(network)) { result ->
        if (result is BouncerServResult.Success) {
            _state.value = _state.value.copy(channels = parseChannelStatus(result.replies))
        }
    }

    fun createChannel(channel: String, network: String, fields: ChannelCommandFields) =
        execute(BouncerServCommands.channelCreate(channel, network, fields))

    fun updateChannel(channel: String, network: String, fields: ChannelCommandFields) =
        execute(BouncerServCommands.channelUpdate(channel, network, fields))

    fun deleteChannel(channel: String, network: String) =
        execute(BouncerServCommands.channelDelete(channel, network))

    fun updateAccount(nick: String?, realName: String?) =
        execute(BouncerServCommands.accountUpdate(nick, realName))

    fun saslStatus(network: String) = execute(BouncerServCommands.saslStatus(network))

    fun setSaslPlain(network: String, username: String, password: String) =
        execute(BouncerServCommands.saslSetPlain(network, username, password))

    fun resetSasl(network: String) = execute(BouncerServCommands.saslReset(network))

    fun generateCertFp(network: String, keyType: String) =
        execute(BouncerServCommands.certFpGenerate(network, keyType))

    fun showCertFp(network: String) = execute(BouncerServCommands.certFpFingerprint(network))

    fun userStatus(username: String?) = execute(BouncerServCommands.userStatus(username))

    fun createUser(username: String, password: String, administrator: Boolean, enabled: Boolean) =
        execute(BouncerServCommands.userCreate(username, password, administrator, enabled))

    fun updateUser(username: String?, changed: UserCommandFields) {
        val root = _state.value.root ?: return
        buildAndExecute {
            BouncerServCommands.userUpdate(
                username = username,
                currentUsername = root.username,
                administrator = _state.value.capabilities.administrator,
                changed = changed,
            )
        }
    }

    fun requestUserDeletion(username: String) {
        executeWithResult(BouncerServCommands.userDelete(username)) { result ->
            val success = result as? BouncerServResult.Success ?: return@executeWithResult
            val token = extractUserDeletionToken(username, success.replies) ?: return@executeWithResult
            _state.value = _state.value.copy(pendingUserDeletion = PendingUserDeletion(username, token))
        }
    }

    fun confirmUserDeletion() {
        val pending = _state.value.pendingUserDeletion ?: return
        _state.value = _state.value.copy(pendingUserDeletion = null)
        execute(BouncerServCommands.userDelete(pending.username, pending.token))
    }

    fun cancelUserDeletion() {
        _state.value = _state.value.copy(pendingUserDeletion = null)
    }

    fun runAsUser(username: String, nestedCommand: String) = buildAndExecute {
        BouncerServCommands.userRun(username, BouncerServCommand(nestedCommand))
    }

    fun serverStatus() = execute(BouncerServCommands.serverStatus())
    fun sendServerNotice(message: String) = execute(BouncerServCommands.serverNotice(message))
    fun setServerDebug(enabled: Boolean) = execute(BouncerServCommands.serverDebug(enabled))

    fun submitConsole(raw: String) {
        val command = runCatching { BouncerServCommand(raw) }.getOrElse {
            _state.value = _state.value.copy(error = it.message, notice = null)
            return
        }
        execute(command)
    }

    private fun execute(
        command: BouncerServCommand,
        refreshNetworks: Boolean = false,
        onSuccess: suspend () -> Unit = {},
    ) = executeWithResult(command, refreshNetworks) { result ->
        if (result is BouncerServResult.Success) onSuccess()
    }

    private fun buildAndExecute(
        refreshNetworks: Boolean = false,
        build: () -> BouncerServCommand,
    ) {
        val command = runCatching(build).getOrElse {
            _state.value = _state.value.copy(error = it.message, notice = null)
            return
        }
        execute(command, refreshNetworks)
    }

    private fun executeWithResult(
        command: BouncerServCommand,
        refreshNetworks: Boolean = false,
        onResult: suspend (BouncerServResult) -> Unit,
    ) {
        if (_state.value.commandBusy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(commandBusy = true, notice = null, error = null)
            val result = bouncerServ.execute(rootNetworkId, command)
            onResult(result)
            val failed = result !is BouncerServResult.Success
            _state.value = _state.value.copy(
                commandBusy = false,
                notice = result.safeSummary().takeUnless { failed },
                error = result.safeSummary().takeIf { failed },
            )
            if (refreshNetworks && result is BouncerServResult.Success) refreshInline()
            if (result.indicatesCapabilityDrift()) probeInline()
        }
    }

    private suspend fun probeInline() {
        if (_state.value.probing || _state.value.rootState !is IrcClientState.Ready) return
        _state.value = _state.value.copy(probing = true)
        val capabilities = bouncerServ.probe(rootNetworkId)
        _state.value = _state.value.copy(
            probing = false,
            capabilities = capabilities,
            selectedTab = if (
                _state.value.selectedTab == BouncerControlTab.ADMIN && !capabilities.administrator
            ) BouncerControlTab.CONSOLE else _state.value.selectedTab,
        )
    }

    private suspend fun refreshInline(showLoading: Boolean = false) {
        if (showLoading) _state.value = _state.value.copy(loading = true, error = null)
        val client = connectionManager.clientFor(rootNetworkId)
        val listing = if (client == null) {
            emptyList()
        } else {
            runCatching { client.bouncerListNetworks() }.getOrElse {
                _state.value = _state.value.copy(loading = false, error = it.message)
                return
            }
        }
        val children = networkRepository.childrenOf(rootNetworkId)
        _state.value = _state.value.copy(
            loading = false,
            rows = mergeBouncerRows(listing, children),
        )
    }

}

private fun BouncerServResult.indicatesCapabilityDrift(): Boolean {
    val replies = when (this) {
        is BouncerServResult.Success -> replies
        is BouncerServResult.Timeout -> replies
        else -> emptyList()
    }
    return replies.any { reply ->
        val lower = reply.lowercase()
        "command not found" in lower || "permission" in lower || "must be an admin" in lower
    }
}
