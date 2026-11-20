package io.github.trevarj.motd.ui.channellist

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.JoinedChannelRow
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.client.ChannelListing
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.service.ChannelJoinOutcome
import io.github.trevarj.motd.service.ChannelJoinRejectionKind
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.ui.nav.ChannelListRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class DirectoryResult(
    val listings: List<ChannelListing> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

data class DirectorySearch(
    val query: String,
    val result: DirectoryResult,
)

data class ChannelJoinFailure(
    val message: String,
    val needsKey: Boolean = false,
)

data class ChannelListUiState(
    val networkId: Long = 0,
    val networkName: String = "",
    val networks: List<NetworkEntity> = emptyList(),
    val connState: IrcClientState = IrcClientState.Disconnected,
    val initialized: Boolean = false,
    val query: String = "",
    val popular: DirectoryResult = DirectoryResult(),
    val smaller: DirectoryResult = DirectoryResult(),
    val includeSmaller: Boolean = false,
    val remote: DirectorySearch? = null,
    val isRoot: Boolean = false,
    val identityRules: IrcIdentityRules = IrcIdentityRules(),
    val joinedChannels: Map<String, Long> = emptyMap(),
    val pendingChannels: Set<String> = emptySet(),
    val joinErrors: Map<String, ChannelJoinFailure> = emptyMap(),
    val connectionError: String? = null,
    val openBufferId: Long? = null,
) {
    val isReady: Boolean get() = availability == ChannelBrowserAvailability.READY
    val availability: ChannelBrowserAvailability
        get() = channelBrowserAvailability(initialized, isRoot, connState)
}

@HiltViewModel
class ChannelListViewModel
    @Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
        private val networkRepository: NetworkRepository,
        private val bufferRepository: BufferRepository,
        private val connectionManager: ConnectionManager,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val initialNetworkId = savedStateHandle.toRoute<ChannelListRoute>().networkId
        private val _state = MutableStateFlow(ChannelListUiState(query = savedStateHandle.get<String>(QUERY).orEmpty()))
        val state: StateFlow<ChannelListUiState> = _state.asStateFlow()

        private var started = false
        private var membershipJob: Job? = null
        private var connectJob: Job? = null
        private var listJob: Job? = null
        private var readyClient: IrcClient? = null
        private var activeRequest: DirectoryRequest? = null
        private val queuedRequests = ArrayDeque<DirectoryRequest>()
        private var autoIncludeSmaller = true
        private var joinedRows = emptyList<JoinedChannelRow>()
        private val pending = mutableListOf<JoinAttempt>()
        private val failures = mutableMapOf<String, ChannelJoinFailure>()
        private var openAttempt: JoinAttempt? = null

        fun start() {
            if (started) return
            started = true
            viewModelScope.launch {
                networkRepository.observeNetworks().collect { networks ->
                    val current = _state.value
                    val eligible = networks.filter { it.role != NetworkRole.BOUNCER_ROOT }
                    val preferred: Long =
                        current.networkId.takeUnless { it == 0L } ?: savedStateHandle.get<Long>(NETWORK) ?: initialNetworkId
                    val selected =
                        eligible.firstOrNull { it.id == preferred }
                            ?: eligible.firstOrNull { it.parentId == preferred }
                            ?: eligible.firstOrNull { connectionManager.connectionStates.value[it.id] is IrcClientState.Ready }
                            ?: eligible.firstOrNull()
                    _state.value =
                        current.copy(
                            networks = eligible,
                            initialized = true,
                            isRoot = selected == null && networks.any { it.id == preferred && it.role == NetworkRole.BOUNCER_ROOT },
                        )
                    if (!current.initialized || (selected?.id ?: 0L) != current.networkId) {
                        activateNetwork(selected, restore = !current.initialized && selected?.id == preferred)
                    } else if (selected != null && selected.name != current.networkName) {
                        _state.value = _state.value.copy(networkName = selected.name)
                    }
                }
            }
            viewModelScope.launch {
                connectionManager.connectionStates.collect { updateConnection() }
            }
            viewModelScope.launch {
                connectionManager.channelJoinOutcomes.collect { outcome ->
                    val rejection = outcome as? ChannelJoinOutcome.Rejected ?: return@collect
                    val current = _state.value
                    if (current.networkId != rejection.networkId || !isCurrentReadyClient()) return@collect
                    val normalized = current.identityRules.normalize(rejection.channel)
                    val attempt = pending.firstOrNull { current.identityRules.normalize(it.channel) == normalized } ?: return@collect
                    val needsKey = rejection.kind == ChannelJoinRejectionKind.BAD_KEY
                    val message =
                        when {
                            needsKey -> context.getString(R.string.channel_list_join_bad_key)
                            attempt.withKey -> context.getString(R.string.channel_list_join_rejected)
                            else -> rejection.reason
                        }
                    failJoin(attempt, ChannelJoinFailure(message, needsKey))
                }
            }
        }

        fun selectNetwork(networkId: Long) {
            val current = _state.value
            if (networkId == current.networkId) return
            val selected = current.networks.firstOrNull { it.id == networkId } ?: return
            activateNetwork(selected, restore = false)
        }

        private fun activateNetwork(
            network: NetworkEntity?,
            restore: Boolean,
        ) {
            membershipJob?.cancel()
            connectJob?.cancel()
            connectJob = null
            cancelDirectory()
            readyClient = null
            clearPending()
            failures.clear()
            joinedRows = emptyList()
            val id = network?.id ?: 0L
            val smallerPreference = if (restore) savedStateHandle.get<Boolean>(SMALLER) else null
            autoIncludeSmaller = smallerPreference == null
            if (id != 0L) savedStateHandle[NETWORK] = id
            if (!restore) savedStateHandle.remove<Boolean>(SMALLER)
            val previous = _state.value
            _state.value =
                ChannelListUiState(
                    networkId = id,
                    networkName = network?.name.orEmpty(),
                    networks = previous.networks,
                    initialized = true,
                    isRoot = previous.isRoot,
                    includeSmaller = smallerPreference == true,
                    query = previous.query,
                )
            if (network == null) return
            membershipJob =
                viewModelScope.launch {
                    bufferRepository.observeJoinedChannels(id).collect { rows ->
                        joinedRows = rows
                        publishMembership()
                    }
                }
            updateConnection()
        }

        private fun updateConnection() {
            val current = _state.value
            if (current.networkId == 0L) return
            val client = connectionManager.clientFor(current.networkId)
            val connection = channelBrowserConnectionState(connectionManager.connectionStates.value[current.networkId], client?.state?.value)
            val live = client?.takeIf { connection is IrcClientState.Ready }
            val changed = live !== readyClient
            if (changed) {
                cancelDirectory()
                clearPending()
                readyClient = live
            }
            val latest = _state.value
            val rules = client?.isupport?.identityRules ?: latest.identityRules
            _state.value =
                latest.copy(
                    connState = if (connection is IrcClientState.Ready && live == null) IrcClientState.Connecting else connection,
                    identityRules = rules,
                    connectionError = if (live != null) null else latest.connectionError,
                )
            if (changed || rules != latest.identityRules) publishMembership()
            if (changed && live != null) {
                enqueue(DirectoryRequest.Popular)
                if (_state.value.includeSmaller) enqueue(DirectoryRequest.Smaller)
                val query = _state.value.remote?.query
                if (query != null) enqueue(DirectoryRequest.Search(query))
            }
        }

        fun connect() {
            val current = _state.value
            if (current.networkId == 0L ||
                (current.availability != ChannelBrowserAvailability.OFFLINE && current.availability != ChannelBrowserAvailability.FAILED) ||
                connectJob?.isActive == true
            ) {
                return
            }
            _state.value = current.copy(connectionError = null)
            connectJob =
                viewModelScope.launch {
                    try {
                        connectionManager.connect(current.networkId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        _state.value = _state.value.copy(connectionError = context.getString(R.string.channel_list_connect_failed))
                    }
                }
        }

        fun onQueryChange(query: String) {
            val current = _state.value
            if (query == current.query) return
            val term = networkSearchTerm(query)
            queuedRequests.removeAll { it is DirectoryRequest.Search && it.query != term }
            savedStateHandle[QUERY] = query
            _state.value = current.copy(query = query, remote = current.remote?.takeIf { it.query == term })
        }

        fun search(query: String) {
            onQueryChange(query)
            val term = networkSearchTerm(query) ?: return
            enqueue(DirectoryRequest.Search(term))
        }

        fun refresh() {
            enqueue(DirectoryRequest.Popular)
            if (_state.value.includeSmaller) enqueue(DirectoryRequest.Smaller)
        }

        fun retryDirectory(smaller: Boolean) {
            enqueue(if (smaller) DirectoryRequest.Smaller else DirectoryRequest.Popular)
        }

        fun setIncludeSmaller(include: Boolean) {
            val current = _state.value
            autoIncludeSmaller = false
            savedStateHandle[SMALLER] = include
            _state.value = current.copy(includeSmaller = include)
            if (include && !current.smaller.loaded) enqueue(DirectoryRequest.Smaller)
        }

        private fun enqueue(request: DirectoryRequest) {
            if (!isCurrentReadyClient()) return
            val current = _state.value
            _state.value = current.withResult(request, current.resultFor(request).copy(loading = true, error = null))
            if (request != activeRequest && request !in queuedRequests) queuedRequests.addLast(request)
            startNextRequest()
        }

        private fun startNextRequest() {
            val client = readyClient ?: return
            if (activeRequest != null || !isCurrentReadyClient()) return
            val request = queuedRequests.removeFirstOrNull() ?: return
            activeRequest = request
            listJob =
                viewModelScope.launch(start = CoroutineStart.LAZY) {
                    val result =
                        try {
                            val listings =
                                when (request) {
                                    DirectoryRequest.Popular -> client.listChannels(minUsers = SMALL_CHANNEL_MAX_USERS, cap = DIRECTORY_CHANNEL_LIMIT)
                                    DirectoryRequest.Smaller -> client.listChannels(maxUsers = SMALL_CHANNEL_MAX_USERS, cap = DIRECTORY_CHANNEL_LIMIT)
                                    is DirectoryRequest.Search -> client.listChannels(cap = CHANNEL_SEARCH_LIMIT, nameContains = request.query)
                                }
                            DirectoryResult(listings = listings, loaded = true)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            _state.value.resultFor(request).copy(
                                loading = false,
                                error = error.message ?: context.getString(R.string.channel_list_load_failed),
                            )
                        }
                    if (readyClient !== client || !isCurrentReadyClient()) return@launch
                    val current = _state.value
                    if (request !is DirectoryRequest.Search || current.remote?.query == request.query) {
                        _state.value = current.withResult(request, result)
                    }
                    if (autoIncludeSmaller && request == DirectoryRequest.Popular && result.loaded && result.listings.isEmpty()) {
                        setIncludeSmaller(true)
                    }
                    activeRequest = null
                    startNextRequest()
                }
            listJob?.start()
        }

        private fun cancelDirectory() {
            listJob?.cancel()
            activeRequest = null
            queuedRequests.clear()
            val current = _state.value
            _state.value =
                current.copy(
                    popular = current.popular.copy(loading = false),
                    smaller = current.smaller.copy(loading = false),
                    remote = current.remote?.let { it.copy(result = it.result.copy(loading = false)) },
                )
        }

        fun join(
            name: String,
            key: String?,
            openAfterJoin: Boolean,
        ) {
            val current = _state.value
            if (!isCurrentReadyClient()) return
            val channel = exactChannelTarget(name, current.identityRules) ?: return
            val normalized = current.identityRules.normalize(channel)
            current.joinedChannels[normalized]?.let {
                if (openAfterJoin) openJoined(it)
                return
            }
            if (normalized in current.pendingChannels) return
            if (key != null && (!key.isIrcParameter() || channel.encodeToByteArray().size + key.encodeToByteArray().size > 400)) {
                failures[channel] = ChannelJoinFailure(context.getString(R.string.channel_list_join_invalid_key), needsKey = true)
                publishMembership()
                return
            }
            failures.keys.removeAll { current.identityRules.normalize(it) == normalized }
            val attempt = JoinAttempt(channel, withKey = key != null)
            pending += attempt
            if (openAfterJoin) openAttempt = attempt
            publishMembership()
            attempt.job =
                viewModelScope.launch {
                    val sent =
                        try {
                            withTimeoutOrNull(JOIN_TIMEOUT_MS) {
                                if (connectionManager.joinChannel(current.networkId, channel, key, rememberKey = false)) awaitCancellation()
                                false
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                    val error = if (sent == null) R.string.channel_list_join_timeout else R.string.channel_list_join_not_sent
                    failJoin(attempt, ChannelJoinFailure(context.getString(error), attempt.withKey))
                }
        }

        fun consumeOpen(bufferId: Long): Boolean {
            val current = _state.value
            if (current.openBufferId != bufferId) return false
            _state.value = current.copy(openBufferId = null)
            return isCurrentReadyClient() && bufferId in current.joinedChannels.values
        }

        fun openJoined(bufferId: Long) {
            val current = _state.value
            if (!isCurrentReadyClient() || bufferId !in current.joinedChannels.values) return
            openAttempt = null
            _state.value = current.copy(openBufferId = bufferId)
        }

        private fun clearPending() {
            pending.forEach { it.job?.cancel() }
            pending.clear()
            openAttempt = null
        }

        private fun failJoin(
            attempt: JoinAttempt,
            error: ChannelJoinFailure,
        ) {
            if (!pending.remove(attempt)) return
            attempt.job?.cancel()
            if (openAttempt === attempt) openAttempt = null
            failures[attempt.channel] = error
            publishMembership()
        }

        private fun publishMembership() {
            val current = _state.value
            val rules = current.identityRules
            val joined = joinedRows.associate { rules.normalize(it.displayName) to it.bufferId }
            var openBufferId = current.openBufferId
            pending.removeAll { attempt ->
                val id = joined[rules.normalize(attempt.channel)] ?: return@removeAll false
                attempt.job?.cancel()
                if (openAttempt === attempt) {
                    openAttempt = null
                    if (isCurrentReadyClient()) openBufferId = id
                }
                true
            }
            failures.keys.removeAll { rules.normalize(it) in joined }
            _state.value =
                current.copy(
                    joinedChannels = joined,
                    pendingChannels = pending.mapTo(mutableSetOf()) { rules.normalize(it.channel) },
                    joinErrors = failures.entries.associate { rules.normalize(it.key) to it.value },
                    openBufferId = openBufferId,
                )
        }

        private fun isCurrentReadyClient(): Boolean {
            val client = readyClient ?: return false
            return client === connectionManager.clientFor(_state.value.networkId) && client.state.value is IrcClientState.Ready
        }

        private sealed interface DirectoryRequest {
            data object Popular : DirectoryRequest

            data object Smaller : DirectoryRequest

            data class Search(
                val query: String,
            ) : DirectoryRequest
        }

        private fun ChannelListUiState.resultFor(request: DirectoryRequest): DirectoryResult =
            when (request) {
                DirectoryRequest.Popular -> popular
                DirectoryRequest.Smaller -> smaller
                is DirectoryRequest.Search -> remote?.takeIf { it.query == request.query }?.result ?: DirectoryResult()
            }

        private fun ChannelListUiState.withResult(
            request: DirectoryRequest,
            result: DirectoryResult,
        ): ChannelListUiState =
            when (request) {
                DirectoryRequest.Popular -> copy(popular = result)
                DirectoryRequest.Smaller -> copy(smaller = result)
                is DirectoryRequest.Search -> copy(remote = DirectorySearch(request.query, result))
            }

        private class JoinAttempt(
            val channel: String,
            val withKey: Boolean,
        ) {
            var job: Job? = null
        }

        private companion object {
            const val JOIN_TIMEOUT_MS = 20_000L
            const val NETWORK = "directory_network"
            const val QUERY = "directory_query"
            const val SMALLER = "directory_smaller"
        }
    }
