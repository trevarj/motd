package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.HistorySyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest
import javax.inject.Inject
import javax.inject.Singleton

/** Manual hide lasts for this process, independently of navigation entries and saved state. */
@Singleton
class NetworkActivityBannerSession
    @Inject
    constructor() {
        private val _hidden = MutableStateFlow(false)
        val hidden = _hidden.asStateFlow()

        fun hide() {
            _hidden.value = true
        }
    }

/** Observed failures only, retained by the chat-list entry's ViewModel, never persisted. */
enum class NetworkActivityKind { CONNECTION, HISTORY_FAILED, HISTORY_PARTIAL }

enum class NetworkActivityDisposition { CONNECTED, STOPPED, REMOVED, NO_LONGER_REPORTED, UNAVAILABLE, SUPERSEDED }

enum class NetworkActivityAction { CONNECT, SETTINGS, SERVER_MESSAGES, OPEN_CHAT, RETRY_HISTORY, ACKNOWLEDGE }

data class NetworkActivityIssue(
    val episodeId: Long,
    val revision: Long,
    val networkId: Long,
    /** Source status-map key; observing a redirect can return a different canonical entity ID. */
    val bufferId: Long? = null,
    val networkName: String,
    val chatName: String? = null,
    val kind: NetworkActivityKind,
    val reason: String,
    val fatal: Boolean = false,
    val firstSeen: Long,
    val lastSeen: Long,
    val occurrences: Int = 1,
    val acknowledged: Boolean = false,
    val retrying: Boolean = false,
    val settled: Boolean = true,
    val disposition: NetworkActivityDisposition? = null,
    val targetAvailable: Boolean = true,
) {
    val severity: Int get() =
        if (fatal) {
            3
        } else if (kind == NetworkActivityKind.HISTORY_PARTIAL) {
            1
        } else {
            2
        }
}

data class NetworkActivityChat(
    val bufferId: Long,
    val name: String,
    val status: HistorySyncStatus,
)

data class NetworkActivityNetwork(
    val id: Long,
    val name: String,
    val connection: IrcClientState?,
    val history: List<NetworkActivityChat> = emptyList(),
    val certificatePending: Boolean = false,
)

data class NetworkActivityState(
    val networks: List<NetworkActivityNetwork> = emptyList(),
    val active: List<NetworkActivityIssue> = emptyList(),
    val recent: List<NetworkActivityIssue> = emptyList(),
    val latestAttentionSequence: Long = 0,
) {
    /** Acknowledged live episodes are suppression tombstones, not rows or proof of recovery. */
    val unacknowledgedCount: Int get() = active.count { !it.acknowledged }
}

internal class NetworkActivityLedger {
    private var sequence = 0L
    private var attentionSequence = 0L
    val latestAttentionSequence: Long get() = attentionSequence
    private var state = NetworkActivityState()
    private var previousConnections = emptyMap<Long, Any?>()
    private var previousHistory = emptyMap<Long, HistorySyncStatus>()
    private val stoppedConnections = mutableMapOf<Long, Any?>()
    private val stoppedHistory = mutableMapOf<Long, HistorySyncStatus?>()

    fun observe(
        networks: List<NetworkEntity>,
        connections: Map<Long, IrcClientState>,
        statuses: Map<Long, HistorySyncStatus>,
        buffers: Map<Long, BufferEntity?>,
        certificatePending: Set<Long>,
        now: Long,
    ): NetworkActivityState {
        val active = mutableListOf<NetworkActivityIssue>()
        val recent = state.recent.toMutableList()
        val connectionSignatures = connections.mapValues { connectionSignature(it.value) }

        fun finish(
            issue: NetworkActivityIssue,
            disposition: NetworkActivityDisposition,
        ) {
            if (!issue.acknowledged) recent.add(0, issue.copy(disposition = disposition, targetAvailable = disposition != NetworkActivityDisposition.REMOVED))
        }
        for (issue in state.active) {
            val network = networks.firstOrNull { it.id == issue.networkId }
            val buffer = issue.bufferId?.let(buffers::get)
            val removed = network == null || (issue.bufferId != null && buffers.containsKey(issue.bufferId) && (buffer == null || buffer.dismissed || buffer.pendingCloseAt != null))
            val status = issue.bufferId?.let(statuses::get)
            when {
                removed -> {
                    finish(issue, NetworkActivityDisposition.REMOVED)
                }

                issue.bufferId == null && connections[issue.networkId] is IrcClientState.Ready -> {
                    finish(issue, NetworkActivityDisposition.CONNECTED)
                }

                issue.bufferId != null && (status == null || status == HistorySyncStatus.Idle) -> {
                    finish(issue, NetworkActivityDisposition.NO_LONGER_REPORTED)
                }

                issue.bufferId != null && status == HistorySyncStatus.Unavailable -> {
                    finish(issue, NetworkActivityDisposition.UNAVAILABLE)
                }

                else -> {
                    val changed = if (issue.bufferId == null) previousConnections[issue.networkId] != connectionSignatures[issue.networkId] else previousHistory[issue.bufferId] != status
                    val retrying = if (issue.bufferId == null) connections[issue.networkId].let { it == IrcClientState.Connecting || it == IrcClientState.Registering } else status == HistorySyncStatus.Queued || status == HistorySyncStatus.AwaitingConnection || status == HistorySyncStatus.Syncing
                    val settled = if (issue.bufferId == null) connections[issue.networkId] is IrcClientState.Failed else status is HistorySyncStatus.Failed || status is HistorySyncStatus.Partial
                    val networkName = network?.name ?: issue.networkName
                    val chatName = buffer?.displayName ?: issue.chatName
                    active +=
                        if (changed || networkName != issue.networkName || chatName != issue.chatName || retrying != issue.retrying || settled != issue.settled) {
                            issue.copy(revision = issue.revision + if (changed) 1 else 0, networkName = networkName, chatName = chatName, retrying = retrying, settled = settled)
                        } else {
                            issue
                        }
                }
            }
        }

        fun failure(
            network: NetworkEntity,
            bufferId: Long?,
            buffer: BufferEntity?,
            kind: NetworkActivityKind,
            reason: String,
            fatal: Boolean,
            changed: Boolean,
        ) {
            // Buffer presence separates history from connection; Failed/Partial are severity changes.
            val index = active.indexOfFirst { it.networkId == network.id && it.bufferId == bufferId }
            if (index < 0 || active[index].reason != reason) {
                if (index >= 0) finish(active.removeAt(index), NetworkActivityDisposition.SUPERSEDED)
                attentionSequence++
                active += NetworkActivityIssue(++sequence, 0, network.id, bufferId, network.name, buffer?.displayName, kind, reason, fatal, now, now)
            } else if (changed) {
                val old = active[index]
                val updated = old.copy(kind = kind, lastSeen = now, occurrences = old.occurrences + 1, fatal = fatal)
                if (updated.severity > old.severity) attentionSequence++
                active[index] = updated.copy(acknowledged = old.acknowledged && updated.severity <= old.severity)
            }
        }
        for (network in networks) {
            val signature = connectionSignatures[network.id]
            if (stoppedConnections.containsKey(network.id)) {
                if (stoppedConnections[network.id] == signature) continue
                stoppedConnections.remove(network.id)
            }
            val failed = connections[network.id] as? IrcClientState.Failed ?: continue
            failure(network, null, null, NetworkActivityKind.CONNECTION, failed.reason, failed.fatal, previousConnections[network.id] != signature)
        }
        for ((id, status) in statuses) {
            if (stoppedHistory.containsKey(id)) {
                if (stoppedHistory[id] == status) continue
                stoppedHistory.remove(id)
            }
            val buffer = buffers[id]?.takeUnless { it.dismissed || it.pendingCloseAt != null } ?: continue
            val network = networks.firstOrNull { it.id == buffer.networkId } ?: continue
            when (status) {
                is HistorySyncStatus.Failed -> failure(network, id, buffer, NetworkActivityKind.HISTORY_FAILED, status.reason, false, previousHistory[id] != status)
                is HistorySyncStatus.Partial -> failure(network, id, buffer, NetworkActivityKind.HISTORY_PARTIAL, status.reason, false, previousHistory[id] != status)
                else -> Unit
            }
        }
        previousConnections = connectionSignatures
        previousHistory = statuses
        state =
            NetworkActivityState(
                networks =
                    networks.map { network ->
                        NetworkActivityNetwork(network.id, network.name, connections[network.id], statuses.mapNotNull { (id, status) -> buffers[id]?.takeIf { it.networkId == network.id && !it.dismissed && it.pendingCloseAt == null }?.let { NetworkActivityChat(id, it.displayName, status) } }, network.id in certificatePending)
                    },
                active = active,
                latestAttentionSequence = attentionSequence,
                recent =
                    recent.take(20).map { issue ->
                        val buffer = issue.bufferId?.let(buffers::get)
                        val removed = networks.none { it.id == issue.networkId } || (issue.bufferId != null && buffers.containsKey(issue.bufferId) && (buffer == null || buffer.dismissed || buffer.pendingCloseAt != null))
                        if (removed && issue.targetAvailable) issue.copy(targetAvailable = false) else issue
                    },
            )
        return state
    }

    fun current(
        issue: NetworkActivityIssue,
        includeRecent: Boolean = false,
    ): NetworkActivityIssue? =
        state.active.firstOrNull { it.episodeId == issue.episodeId && it.revision == issue.revision && it.kind == issue.kind && it.reason == issue.reason }
            ?: state.recent.takeIf { includeRecent }?.firstOrNull { it.targetAvailable && it.episodeId == issue.episodeId && it.revision == issue.revision && it.kind == issue.kind && it.reason == issue.reason }

    fun acknowledge(issue: NetworkActivityIssue): NetworkActivityState {
        val current = current(issue)?.takeUnless { it.acknowledged } ?: return state
        state = state.copy(active = state.active.map { if (it.episodeId == current.episodeId) it.copy(acknowledged = true, revision = it.revision + 1) else it })
        return state
    }

    fun clearRecent(): NetworkActivityState {
        state = state.copy(recent = emptyList())
        return state
    }

    fun stop(networkId: Long): NetworkActivityState {
        stoppedConnections[networkId] = previousConnections[networkId]
        state.active.filter { it.networkId == networkId }.forEach { issue -> issue.bufferId?.let { stoppedHistory[it] = previousHistory[it] } }
        val stopped = state.active.filter { it.networkId == networkId && !it.acknowledged }.map { it.copy(disposition = NetworkActivityDisposition.STOPPED) }
        state = state.copy(active = state.active.filterNot { it.networkId == networkId }, recent = (stopped.asReversed() + state.recent).take(20))
        return state
    }

    private fun connectionSignature(state: IrcClientState): Any = if (state is IrcClientState.Ready) "ready" else state
}

internal const val NETWORK_ACTIVITY_CONNECTION_GRACE_MS = 3_000L

/** Keeps connection grace across connecting/registering/retries; presented waiting opens the gate. */
internal class NetworkActivityPresenter {
    private var activeSince: Long? = null
    private var waitingPresented = false

    fun resolve(
        state: NetworkActivityState,
        chrome: ChatListSyncChrome,
        now: Long,
    ): Boolean {
        val connectionActive = state.active.any { it.bufferId == null && !it.acknowledged } || state.networks.any { it.connection == IrcClientState.Connecting || it.connection == IrcClientState.Registering }
        if (!connectionActive && chrome == ChatListSyncChrome.Hidden) {
            activeSince = null
            waitingPresented = false
        }
        if (connectionActive && activeSince == null) activeSince = now
        if (chrome is ChatListSyncChrome.Waiting) waitingPresented = true
        return waitingPresented || state.active.any { it.bufferId == null && !it.acknowledged && it.fatal } || activeSince?.let { now - it >= NETWORK_ACTIVITY_CONNECTION_GRACE_MS } == true
    }

    fun nextDeadline(now: Long): Long? = activeSince?.plus(NETWORK_ACTIVITY_CONNECTION_GRACE_MS)?.takeIf { !waitingPresented && it > now }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<Pair<NetworkActivityState, ChatListSyncChrome>>.presentNetworkActivity(now: () -> Long): Flow<Boolean> =
    flow {
        val presenter = NetworkActivityPresenter()
        emitAll(
            transformLatest { (state, chrome) ->
                while (true) {
                    emit(presenter.resolve(state, chrome, now()))
                    val deadline = presenter.nextDeadline(now()) ?: break
                    delay((deadline - now()).coerceAtLeast(0))
                }
            }.distinctUntilChanged(),
        )
    }
