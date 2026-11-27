package io.github.trevarj.motd.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.db.NetworkBufferToolRow
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkIgnoreEntity
import io.github.trevarj.motd.data.repo.NetworkIgnoreRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NetworkToolsUiState(
    val networkId: Long = 0,
    val network: NetworkEntity? = null,
    val ignores: List<NetworkIgnoreEntity> = emptyList(),
    val buffers: List<NetworkBufferToolRow> = emptyList(),
    val connected: Boolean = false,
    /** Own nick on this network, offered as the first MODE target suggestion. Null unless Ready. */
    val selfNick: String? = null,
    val status: NetworkToolsStatus? = null,
)

/**
 * Outcome of the last tool action. Deliberately a sealed type rather than a message string: the
 * ViewModel must not decide user-facing wording, so the screen maps each case to a string resource.
 */
sealed interface NetworkToolsStatus {
    data object IgnoreAdded : NetworkToolsStatus

    data class IgnoreFailed(
        val message: String,
    ) : NetworkToolsStatus

    data object NotConnected : NetworkToolsStatus

    data class CommandSent(
        val command: String,
    ) : NetworkToolsStatus

    data class CommandFailed(
        val command: String,
        val message: String,
    ) : NetworkToolsStatus

    data object MissingFields : NetworkToolsStatus
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NetworkToolsViewModel
    @Inject
    constructor(
        private val networkRepository: NetworkRepository,
        private val toolsRepository: NetworkIgnoreRepository,
        private val connectionManager: ConnectionManager,
    ) : ViewModel() {
        private val networkIdFlow = MutableStateFlow<Long?>(null)
        private val statusFlow = MutableStateFlow<NetworkToolsStatus?>(null)

        fun init(networkId: Long) {
            networkIdFlow.value = networkId
        }

        private val networkFlow =
            networkIdFlow.flatMapLatest { id ->
                if (id == null) flowOf<NetworkEntity?>(null) else flow { emit(networkRepository.networkById(id)) }
            }

        private val ignoresFlow =
            networkIdFlow.flatMapLatest { id ->
                if (id == null) flowOf(emptyList()) else toolsRepository.observeIgnores(id)
            }

        private val buffersFlow =
            networkIdFlow.flatMapLatest { id ->
                if (id == null) flowOf(emptyList()) else toolsRepository.observeBuffers(id)
            }

        // One-shot: unmuting marked a muted backlog read, so the screen can report it and offer an undo.
        private val _muteBacklogSuppressions = MutableSharedFlow<MuteBacklogSuppression>(extraBufferCapacity = 1)
        val muteBacklogSuppressions: SharedFlow<MuteBacklogSuppression> = _muteBacklogSuppressions.asSharedFlow()

        val state: StateFlow<NetworkToolsUiState> =
            combine(
                combine(
                    networkIdFlow,
                    networkFlow,
                    ignoresFlow,
                    buffersFlow,
                    connectionManager.connectionStates,
                ) { networkId, network, ignores, buffers, states ->
                    val ready = states[networkId] as? IrcClientState.Ready
                    NetworkToolsUiState(
                        networkId = networkId ?: 0,
                        network = network,
                        ignores = ignores,
                        buffers = buffers,
                        connected = ready != null,
                        selfNick = ready?.nick,
                    )
                },
                statusFlow,
            ) { base, status -> base.copy(status = status) }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = NetworkToolsUiState(),
            )

        fun addIgnore(
            pattern: String,
            dropMessages: Boolean,
            suppressedSignals: Int,
        ) = viewModelScope.launch {
            val networkId = state.value.networkId.takeIf { it != 0L } ?: return@launch
            toolsRepository
                .addIgnore(networkId, pattern, dropMessages, suppressedSignals)
                .onSuccess { statusFlow.value = NetworkToolsStatus.IgnoreAdded }
                .onFailure { statusFlow.value = NetworkToolsStatus.IgnoreFailed(it.message.orEmpty()) }
        }

        fun setIgnoreEnabled(
            id: Long,
            enabled: Boolean,
        ) = viewModelScope.launch {
            toolsRepository.setIgnoreEnabled(id, enabled)
        }

        fun deleteIgnore(id: Long) =
            viewModelScope.launch {
                toolsRepository.deleteIgnore(id)
            }

        fun setMuted(
            bufferId: Long,
            muted: Boolean,
        ) = viewModelScope.launch {
            toolsRepository.setMuted(bufferId, muted)?.let { _muteBacklogSuppressions.emit(it) }
        }

        /** Put back the mute backlog floor an unmute advanced past (snackbar undo). */
        fun undoMuteBacklogSuppression(suppression: MuteBacklogSuppression) =
            viewModelScope.launch {
                toolsRepository.restoreMuteBacklog(suppression)
            }

        fun oper(
            username: String,
            password: String,
        ) = send(operMessage(username, password))

        fun kill(
            nick: String,
            reason: String,
        ) = send(killMessage(nick, reason))

        fun mode(
            target: String,
            modes: String,
            args: String,
        ) = send(modeMessage(target, modes, args))

        fun rehash(server: String) = send(rehashMessage(server))

        fun connectServer(
            server: String,
            port: String,
            remote: String,
        ) = send(connectMessage(server, port, remote))

        fun squit(
            server: String,
            reason: String,
        ) = send(squitMessage(server, reason))

        /**
         * Send a message the screen already built and previewed, so the confirmed line and the sent
         * line are the same object.
         */
        fun send(message: IrcMessage) =
            viewModelScope.launch {
                val networkId = state.value.networkId.takeIf { it != 0L } ?: return@launch
                val client = connectionManager.clientFor(networkId)
                if (client == null) {
                    statusFlow.value = NetworkToolsStatus.NotConnected
                    return@launch
                }
                val validation = runCatching { message.serialize() }.exceptionOrNull()
                if (validation != null) {
                    statusFlow.value = NetworkToolsStatus.CommandFailed(message.command, validation.message.orEmpty())
                    return@launch
                }
                if (message.params.any(String::isBlank)) {
                    statusFlow.value = NetworkToolsStatus.MissingFields
                    return@launch
                }
                runCatching { client.send(message) }
                    .onSuccess { statusFlow.value = NetworkToolsStatus.CommandSent(message.command) }
                    .onFailure {
                        statusFlow.value = NetworkToolsStatus.CommandFailed(message.command, it.message.orEmpty())
                    }
            }
    }
