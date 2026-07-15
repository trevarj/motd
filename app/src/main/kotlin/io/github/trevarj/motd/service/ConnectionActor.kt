package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Minimal seam over [IrcClient] so [ConnectionActor]'s reconnect loop is unit-testable with a fake
 * (a real [IrcClient] driven through CAP/registration under virtual time is fragile).
 */
interface ManagedConnection {
    val state: StateFlow<IrcClientState>
    val events: Flow<IrcEvent>
    fun start()
    fun stop()

    /**
     * Send an immediate watchdog-style liveness probe. Implementations that do not expose a
     * transport-level probe retain the historical no-op/healthy behavior.
     */
    suspend fun probeLiveness(graceMs: Long): Boolean = true
}

/** Adapter wrapping a real [IrcClient]. */
class IrcClientConnection(
    val client: IrcClient,
    private val onStop: () -> Unit = {},
) : ManagedConnection {
    override val state: StateFlow<IrcClientState> get() = client.state
    override val events: SharedFlow<IrcEvent> get() = client.events
    override fun start() = client.start()
    override suspend fun probeLiveness(graceMs: Long): Boolean = client.probeLiveness(graceMs)
    override fun stop() {
        client.stop()
        onStop()
    }
}

/**
 * Drives one physical socket (plans/05). Owns the reconnect loop with exponential backoff, the
 * per-connection EventProcessor collector, catch-up, and network-callback fast-retry/fast-fail.
 *
 * Backoff: base 2s, ×2^attempt, cap 90s, jitter 0.7–1.3. The attempt counter resets to 0 after
 * 5 minutes of continuous Ready. A fatal Failed stops the actor without retry.
 *
 * All time/randomness is injected so the loop is deterministic under virtual time in tests.
 */
internal interface ConnectionLifecycleActor {
    val connection: ManagedConnection?
    val isAlive: Boolean
    fun start()
    fun stop()
    suspend fun stopAndJoin()
    fun onNetworkAvailable()
    fun onNetworkLost()
    /** Request one immediate probe when the current connection is Ready. Requests are conflated. */
    fun probe() = Unit
}

class ConnectionActor(
    val networkId: Long,
    private val scope: CoroutineScope,
    private val connectionFactory: suspend () -> ManagedConnection,
    private val onState: (Long, IrcClientState) -> Unit,
    private val onEvent: suspend (Long, IrcEvent) -> Unit,
    private val onReady: suspend (ManagedConnection) -> Unit,
    private val onConnectionChanged: (Long, ManagedConnection?) -> Unit = { _, _ -> },
    private val onStopped: (Long) -> Unit = {},
    private val random: () -> Double = { Random.nextDouble() },
    /**
     * Returns the pending TOFU cert failure for this network, if the last connect attempt failed on
     * an untrusted/changed leaf cert. When present, the actor parks in a quiescent "awaiting trust"
     * state instead of backoff-looping (which would spam prompts). Cleared by the manager.
     */
    private val pendingCertFailure: suspend () -> CertUntrustedException? = { null },
    /** Publishes a cert prompt for this network when a cert failure parks the actor. */
    private val onCertUntrusted: suspend (Long, CertUntrustedException) -> Unit = { _, _ -> },
) : ConnectionLifecycleActor {
    @Volatile override var connection: ManagedConnection? = null
        private set

    private var job: Job? = null
    private val retryNow = Channel<Unit>(Channel.CONFLATED)
    private val probeNow = Channel<Unit>(Channel.CONFLATED)
    private val probeRequested = AtomicBoolean(false)

    /**
     * True while the reconnect loop coroutine is still running. Goes false once the loop returns —
     * i.e. a fatal Failed or a cert-untrusted park completed the [job]. The manager uses this as a
     * liveness signal: an actor sitting in [ConnectionManagerImpl]'s actor map with a dead job
     * cannot recover on its own, so a self-healing reconcile must drop and rebuild it.
     */
    override val isAlive: Boolean get() = job?.isActive == true

    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }.also { running ->
            running.invokeOnCompletion { onStopped(networkId) }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        clearProbeRequests()
        connection?.stop()
        connection = null
    }

    override suspend fun stopAndJoin() {
        val running = job
        job = null
        running?.cancelAndJoin()
        clearProbeRequests()
        connection?.stop()
        connection = null
    }

    /** Network available again: skip the remaining backoff delay and retry immediately. */
    override fun onNetworkAvailable() { retryNow.trySend(Unit) }

    /** Network lost: fast-fail the current connect attempt so backoff starts promptly. */
    override fun onNetworkLost() { connection?.stop() }

    /**
     * Queue one foreground liveness check. The request is consumed by the actor's connection loop,
     * so cancellation/replacement of this actor also cancels the probe and cannot touch the next
     * actor's connection.
     */
    override fun probe() {
        if (job?.isActive != true) return
        if (!probeRequested.compareAndSet(false, true)) return
        if (!probeNow.trySend(Unit).isSuccess) probeRequested.set(false)
    }

    private suspend fun loop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val conn = connectionFactory()
            connection = conn
            onConnectionChanged(networkId, conn)
            onState(networkId, IrcClientState.Connecting)
            conn.start()

            val attemptScope = CoroutineScope(currentCoroutineContext())
            val collector = attemptScope.launch { conn.events.collect { onEvent(networkId, it) } }
            val outcome = try {
                runConnection(conn) { attempt = 0 }
            } finally {
                collector.cancelAndJoin()
                conn.stop()
                connection = null
                onConnectionChanged(networkId, null)
            }

            // TOFU: a cert failure parks the actor (awaiting user trust) rather than backoff-looping.
            val certFailure = pendingCertFailure()
            if (certFailure != null) {
                onState(networkId, IrcClientState.Failed("certificate not trusted", fatal = false))
                onCertUntrusted(networkId, certFailure)
                return
            }

            when (outcome) {
                Outcome.Fatal -> return
                Outcome.RetryImmediately -> Unit
                Outcome.Retry -> {
                    // A disconnected/non-fatal Failed snapshot describes the socket that just
                    // ended. Publish the current operation (retrying) during backoff so the UI
                    // does not retain a stale SOCKS/tunnel error until the next dial begins.
                    onState(networkId, IrcClientState.Connecting)
                    val delayMs = backoffDelayMs(attempt)
                    attempt++
                    waitBeforeRetry(delayMs)
                }
            }
        }
    }

    private enum class Outcome { Fatal, Retry, RetryImmediately }

    /**
     * Wait for the connection to terminate. On Ready, runs [onReady] and arms a stability timer
     * that invokes [resetBackoff] after 5 minutes still-connected. Returns Fatal for a fatal
     * Failed, Retry otherwise.
     */
    private suspend fun runConnection(conn: ManagedConnection, resetBackoff: () -> Unit): Outcome {
        val connectionScope = CoroutineScope(currentCoroutineContext())
        var state = conn.state.first { it !is IrcClientState.Connecting }
        var stableJob: Job? = null
        var readyJob: Job? = null
        var readyStarted = false
        try {
            while (true) {
                when (state) {
                    is IrcClientState.Ready -> {
                        onState(networkId, state)
                        if (!readyStarted) {
                            readyStarted = true
                            // Connection-scoped setup may wait for capabilities and retry transient
                            // operations such as CHATHISTORY. Run it alongside state observation so a
                            // dead socket cancels that work immediately instead of leaving the actor
                            // blocked in onReady for a series of request timeouts.
                            readyJob = connectionScope.launch { onReady(conn) }
                            stableJob = connectionScope.launch { delay(STABLE_RESET_MS); resetBackoff() }
                        }
                        // Runtime CAP ACK/DEL and late 005 replies republish Ready with a new
                        // snapshot. Surface those mutations without re-running one-time setup.
                        when (val update = awaitReadyUpdate(conn, state, connectionScope)) {
                            is ReadyUpdate.Probe -> {
                                when (val probe = awaitProbeOrState(conn, state, connectionScope)) {
                                    is ProbeUpdate.Completed -> {
                                        // A replacement/stop cancels this actor scope. Do not let an
                                        // old probe tear down a newly-created connection.
                                        if (!probe.live) {
                                            val terminal = conn.state.value
                                            conn.stop()
                                            if (terminal is IrcClientState.Failed && terminal.fatal) {
                                                onState(networkId, terminal)
                                                return Outcome.Fatal
                                            }
                                            // The failed foreground probe is authoritative for this
                                            // exact socket. Redial immediately instead of adding the
                                            // normal retry backoff to the user's resume latency.
                                            onState(networkId, IrcClientState.Disconnected)
                                            return Outcome.RetryImmediately
                                        }
                                        probeRequested.set(false)
                                    }
                                    is ProbeUpdate.StateChanged -> {
                                        probeRequested.set(false)
                                        val next = probe.state
                                        if (next is IrcClientState.Ready) {
                                            state = next
                                            continue
                                        }
                                        readyJob?.cancel()
                                        readyJob = null
                                        stableJob?.cancel()
                                        stableJob = null
                                        onState(networkId, next)
                                        return if (next is IrcClientState.Failed && next.fatal) {
                                            Outcome.Fatal
                                        } else {
                                            Outcome.RetryImmediately
                                        }
                                    }
                                }
                                continue
                            }
                            is ReadyUpdate.StateChanged -> {
                                val next = update.state
                                if (next is IrcClientState.Ready) {
                                    state = next
                                    continue
                                }
                                // A pending probe belongs to the connection that just left Ready.
                                // Drop it before the next retry so a later actor is never probed by
                                // an old foreground event.
                                probeRequested.set(false)
                                while (probeNow.tryReceive().isSuccess) { }
                                readyJob?.cancel()
                                readyJob = null
                                stableJob?.cancel()
                                stableJob = null
                                onState(networkId, next)
                                return outcomeFor(next)
                            }
                        }
                    }
                    is IrcClientState.Failed -> {
                        onState(networkId, state)
                        return outcomeFor(state)
                    }
                    is IrcClientState.Disconnected -> {
                        onState(networkId, state)
                        return Outcome.Retry
                    }
                    is IrcClientState.Registering,
                    IrcClientState.Connecting,
                    -> state = conn.state.first { it != state }
                }
            }
        } finally {
            readyJob?.cancelAndJoin()
            stableJob?.cancelAndJoin()
        }
    }

    private fun outcomeFor(state: IrcClientState): Outcome =
        if (state is IrcClientState.Failed && state.fatal) Outcome.Fatal else Outcome.Retry

    private fun clearProbeRequests() {
        probeRequested.set(false)
        while (probeNow.tryReceive().isSuccess) { }
    }

    private sealed interface ReadyUpdate {
        data object Probe : ReadyUpdate
        data class StateChanged(val state: IrcClientState) : ReadyUpdate
    }

    private sealed interface ProbeUpdate {
        data class Completed(val live: Boolean) : ProbeUpdate
        data class StateChanged(val state: IrcClientState) : ProbeUpdate
    }

    /** Wait for either a state transition or one conflated foreground probe request. */
    private suspend fun awaitReadyUpdate(
        conn: ManagedConnection,
        state: IrcClientState,
        connectionScope: CoroutineScope,
    ): ReadyUpdate {
        val stateWaiter = connectionScope.async { conn.state.first { it != state } }
        return try {
            select {
                probeNow.onReceive { ReadyUpdate.Probe }
                stateWaiter.onAwait { ReadyUpdate.StateChanged(it) }
            }
        } finally {
            stateWaiter.cancel()
        }
    }

    /** Run a probe without masking an EOF/state transition during its grace period. */
    private suspend fun awaitProbeOrState(
        conn: ManagedConnection,
        state: IrcClientState,
        connectionScope: CoroutineScope,
    ): ProbeUpdate {
        val probe = connectionScope.async {
            try {
                conn.probeLiveness(FOREGROUND_PROBE_GRACE_MS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
        }
        val stateWaiter = connectionScope.async { conn.state.first { it != state } }
        return try {
            select {
                probe.onAwait { ProbeUpdate.Completed(it) }
                stateWaiter.onAwait { ProbeUpdate.StateChanged(it) }
            }
        } finally {
            probe.cancel()
            stateWaiter.cancel()
        }
    }

    /** Wait for the backoff delay, waking early when the network becomes available. */
    private suspend fun waitBeforeRetry(delayMs: Long) {
        while (retryNow.tryReceive().isSuccess) { /* drain stale signals */ }
        val timer = CoroutineScope(currentCoroutineContext()).launch { delay(delayMs) }
        try {
            select {
                retryNow.onReceive { }
                timer.onJoin { }
            }
        } finally {
            timer.cancelAndJoin()
        }
    }

    /** delay = min(cap, base * 2^attempt) * jitter(0.7..1.3). */
    fun backoffDelayMs(attempt: Int): Long {
        val exp = BASE_MS * (1L shl attempt.coerceAtMost(MAX_SHIFT))
        val capped = minOf(CAP_MS, exp)
        val jitter = JITTER_LOW + random() * (JITTER_HIGH - JITTER_LOW)
        return (capped * jitter).toLong()
    }

    companion object {
        const val BASE_MS = 2_000L
        const val CAP_MS = 90_000L
        const val STABLE_RESET_MS = 5 * 60 * 1000L
        const val FOREGROUND_PROBE_GRACE_MS = 5_000L
        const val JITTER_LOW = 0.7
        const val JITTER_HIGH = 1.3
        private const val MAX_SHIFT = 30
    }
}
