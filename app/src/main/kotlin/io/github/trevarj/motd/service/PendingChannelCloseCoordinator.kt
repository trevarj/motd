package io.github.trevarj.motd.service

import io.github.trevarj.motd.bouncer.BouncerServClient
import io.github.trevarj.motd.bouncer.BouncerServCommands
import io.github.trevarj.motd.bouncer.BouncerServResult
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.irc.event.IrcClientState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-wide entry point for durable CHANNEL leave/delete requests. */
interface ChannelCloseCoordinator {
    /** Start observing persisted requests. Safe to call repeatedly. */
    fun start()

    /** Hide the channel immediately and arrange for its server-side close. */
    suspend fun requestClose(bufferId: Long)
}

/**
 * Retries channel closes from Room whenever the relevant IRC connection is Ready. A pending row
 * remains in Room (and therefore hidden from all normal target projections) until the server-side
 * action has the required acceptance signal.
 */
@Singleton
class PendingChannelCloseCoordinator private constructor(
    private val db: MotdDatabase,
    private val connections: ConnectionManager,
    private val bouncerServ: BouncerServClient,
    private val clock: AppClock,
    private val scope: CoroutineScope,
    private val retryWait: suspend (attempt: Int) -> Unit,
) : ChannelCloseCoordinator {
    @Inject
    constructor(
        db: MotdDatabase,
        connections: ConnectionManager,
        bouncerServ: BouncerServClient,
        clock: AppClock,
        @ApplicationScope scope: CoroutineScope,
    ) : this(
        db = db,
        connections = connections,
        bouncerServ = bouncerServ,
        clock = clock,
        scope = scope,
        retryWait = { attempt -> delay(channelCloseRetryDelayMillis(attempt)) },
    )

    private val started = AtomicBoolean(false)
    private val attempts = ConcurrentHashMap<Long, Mutex>()
    private val retryJobs = ConcurrentHashMap<Long, Job>()
    private val retryCounts = ConcurrentHashMap<Long, Int>()

    private enum class CloseOutcome {
        COMPLETED,
        AWAITING_CONFIRMATION,
        RETRY,
    }

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // StateFlow emits its current snapshot on subscription, which is what recovers rows
            // after process/ViewModel recreation even when the connection became Ready earlier.
            connections.connectionStates.collect {
                retryPendingCloses()
            }
        }
    }

    override suspend fun requestClose(bufferId: Long) {
        db.bufferDao().markPendingClose(bufferId, clock.nowMillis())
        // ChatListViewModel starts the process-scoped observer before it can issue requests.
        // Attempt directly so an already-Ready connection closes promptly; failed/offline rows
        // remain durable and the observer retries them on the next connection-state emission.
        retryPendingCloses()
    }

    /** One-shot seam used by tests and by [requestClose]; retries all currently eligible rows. */
    suspend fun retryPendingCloses() {
        val states = connections.connectionStates.value
        db.bufferDao().pendingChannelCloses().forEach { buffer ->
            val network = db.networkDao().byId(buffer.networkId) ?: return@forEach
            if (!isReadyFor(network, states)) return@forEach
            attempt(buffer.id)
        }
    }

    private suspend fun attempt(bufferId: Long) {
        val guard = attempts.getOrPut(bufferId) { Mutex() }
        guard.withLock {
            val buffer = db.bufferDao().observeById(bufferId) ?: run {
                clearRetry(bufferId)
                return
            }
            if (buffer.type != BufferType.CHANNEL || buffer.pendingCloseAt == null) {
                clearRetry(bufferId)
                return
            }
            val network = db.networkDao().byId(buffer.networkId) ?: return
            if (!isReadyFor(network, connections.connectionStates.value)) return

            val outcome = try {
                if (network.role == NetworkRole.BOUNCER_CHILD) {
                    closeSojuChannel(buffer, network)
                } else {
                    closeDirectChannel(buffer, network)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CloseOutcome.RETRY
            }
            when (outcome) {
                CloseOutcome.COMPLETED -> {
                    db.bufferDao().deleteBuffer(buffer.id)
                    clearRetry(buffer.id)
                }
                CloseOutcome.AWAITING_CONFIRMATION,
                CloseOutcome.RETRY,
                -> scheduleRetry(buffer.id)
            }
        }
    }

    private suspend fun closeSojuChannel(
        buffer: BufferEntity,
        child: NetworkEntity,
    ): CloseOutcome {
        val rootId = child.parentId ?: return CloseOutcome.RETRY
        if (connections.connectionStates.value[rootId] !is IrcClientState.Ready) {
            return CloseOutcome.RETRY
        }
        val deleteResult = bouncerServ.execute(
            rootNetworkId = rootId,
            command = BouncerServCommands.channelDelete(
                channel = buffer.displayName,
                network = child.name,
            ),
        )
        if (!deleteResult.isSuccessfulBouncerMutation()) return CloseOutcome.RETRY

        // BouncerServ can reply while reporting a command-level error. Reconcile against the
        // authoritative channel listing and delete local history only once the target is absent.
        val statusResult = bouncerServ.execute(
            rootNetworkId = rootId,
            command = BouncerServCommands.channelStatus(child.name),
        )
        val remainingChannels = statusResult.bouncerChannelNames() ?: return CloseOutcome.RETRY
        return if (remainingChannels.none { it.equals(buffer.displayName, ignoreCase = true) }) {
            CloseOutcome.COMPLETED
        } else {
            CloseOutcome.RETRY
        }
    }

    private suspend fun closeDirectChannel(
        buffer: BufferEntity,
        network: NetworkEntity,
    ): CloseOutcome {
        if (connections.connectionStates.value[network.id] !is IrcClientState.Ready) {
            return CloseOutcome.RETRY
        }
        // The boolean seam distinguishes a real transport write from a client that disappeared
        // between the Ready snapshot above and the send boundary. A successful write still does
        // not erase history: EventProcessor waits for the matching self-PART (or 403/442) first.
        return if (connections.partChannelForClose(buffer.id)) {
            CloseOutcome.AWAITING_CONFIRMATION
        } else {
            CloseOutcome.RETRY
        }
    }

    private fun isReadyFor(
        network: NetworkEntity,
        states: Map<Long, IrcClientState>,
    ): Boolean {
        val relevantId = if (network.role == NetworkRole.BOUNCER_CHILD) {
            network.parentId ?: return false
        } else {
            network.id
        }
        return states[relevantId] is IrcClientState.Ready
    }

    private fun scheduleRetry(bufferId: Long) {
        retryJobs.compute(bufferId) { _, active ->
            if (active?.isActive == true) {
                active
            } else {
                scope.launch {
                    val attempt = retryCounts.merge(bufferId, 1, Int::plus) ?: 1
                    try {
                        retryWait(attempt)
                    } catch (cancelled: CancellationException) {
                        retryJobs.remove(bufferId)
                        throw cancelled
                    }
                    retryJobs.remove(bufferId)
                    retryPendingCloses()
                }
            }
        }
    }

    private fun clearRetry(bufferId: Long) {
        retryJobs.remove(bufferId)?.cancel()
        retryCounts.remove(bufferId)
        attempts.remove(bufferId)
    }

    internal companion object {
        fun forTest(
            db: MotdDatabase,
            connections: ConnectionManager,
            bouncerServ: BouncerServClient,
            clock: AppClock,
            scope: CoroutineScope,
            retryWait: suspend (attempt: Int) -> Unit,
        ): PendingChannelCloseCoordinator = PendingChannelCloseCoordinator(
            db = db,
            connections = connections,
            bouncerServ = bouncerServ,
            clock = clock,
            scope = scope,
            retryWait = retryWait,
        )
    }
}

internal fun channelCloseRetryDelayMillis(attempt: Int): Long {
    val shift = (attempt.coerceAtLeast(1) - 1).coerceAtMost(3)
    return (CHANNEL_CLOSE_RETRY_INITIAL_MS shl shift).coerceAtMost(CHANNEL_CLOSE_RETRY_MAX_MS)
}

private fun BouncerServResult.isSuccessfulBouncerMutation(): Boolean =
    this is BouncerServResult.Success && replies.none(::looksLikeBouncerError)

private fun BouncerServResult.bouncerChannelNames(): Set<String>? {
    val success = this as? BouncerServResult.Success ?: return null
    if (success.replies.any(::looksLikeBouncerError)) return null
    val replies = success.replies.map(String::trim).filter(String::isNotEmpty)
    if (replies.isEmpty()) return null
    if (replies.any { it.startsWith("no channels", ignoreCase = true) }) return emptySet()
    val names = replies.mapNotNull { line ->
        BOUNCER_CHANNEL_ROW.matchEntire(line)?.groupValues?.get(1)
    }
    // Unknown output is not proof that the channel disappeared. Keep the durable request and
    // retry instead of deleting history against a response format we did not understand.
    return names.takeIf { it.size == replies.size }?.toSet()
}

private fun looksLikeBouncerError(line: String): Boolean {
    val normalized = line.trim().lowercase()
    return normalized.startsWith("error") ||
        normalized.startsWith("failed") ||
        normalized.startsWith("unknown ") ||
        normalized.startsWith("usage:")
}

private val BOUNCER_CHANNEL_ROW = Regex("^(.+) \\[(.+)]$")
private const val CHANNEL_CLOSE_RETRY_INITIAL_MS = 15_000L
private const val CHANNEL_CLOSE_RETRY_MAX_MS = 60_000L
