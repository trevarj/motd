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
class PendingChannelCloseCoordinator @Inject constructor(
    private val db: MotdDatabase,
    private val connections: ConnectionManager,
    private val bouncerServ: BouncerServClient,
    private val clock: AppClock,
    @ApplicationScope private val scope: CoroutineScope,
) : ChannelCloseCoordinator {
    private val started = AtomicBoolean(false)
    private val attempts = ConcurrentHashMap<Long, Mutex>()

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
            val buffer = db.bufferDao().observeById(bufferId) ?: return
            if (buffer.type != BufferType.CHANNEL || buffer.pendingCloseAt == null) return
            val network = db.networkDao().byId(buffer.networkId) ?: return
            if (!isReadyFor(network, connections.connectionStates.value)) return

            val accepted = try {
                if (network.role == NetworkRole.BOUNCER_CHILD) {
                    closeSojuChannel(buffer, network)
                } else {
                    closeDirectChannel(buffer, network)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (accepted) db.bufferDao().deleteBuffer(buffer.id)
        }
    }

    private suspend fun closeSojuChannel(buffer: BufferEntity, child: NetworkEntity): Boolean {
        val rootId = child.parentId ?: return false
        if (connections.connectionStates.value[rootId] !is IrcClientState.Ready) return false
        val result = bouncerServ.execute(
            rootNetworkId = rootId,
            command = BouncerServCommands.channelDelete(
                channel = buffer.displayName,
                network = child.name,
            ),
        )
        // A timeout/partial reply is not server acceptance. Keep the pending row for the next
        // Ready transition rather than erasing local history optimistically.
        return result is BouncerServResult.Success
    }

    private suspend fun closeDirectChannel(buffer: BufferEntity, network: NetworkEntity): Boolean {
        if (connections.connectionStates.value[network.id] !is IrcClientState.Ready) return false
        // The boolean seam distinguishes a real transport write from a client that disappeared
        // between the Ready snapshot above and the send boundary.
        return connections.partChannelForClose(buffer.id)
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
}
