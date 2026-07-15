package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
}

/** Adapter wrapping a real [IrcClient]. */
class IrcClientConnection(
    val client: IrcClient,
    private val onStop: () -> Unit = {},
) : ManagedConnection {
    override val state: StateFlow<IrcClientState> get() = client.state
    override val events: SharedFlow<IrcEvent> get() = client.events
    override fun start() = client.start()
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
        connection?.stop()
        connection = null
    }

    override suspend fun stopAndJoin() {
        val running = job
        job = null
        running?.cancelAndJoin()
        connection?.stop()
        connection = null
    }

    /** Network available again: skip the remaining backoff delay and retry immediately. */
    override fun onNetworkAvailable() { retryNow.trySend(Unit) }

    /** Network lost: fast-fail the current connect attempt so backoff starts promptly. */
    override fun onNetworkLost() { connection?.stop() }

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

    private enum class Outcome { Fatal, Retry }

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
                        val next = conn.state.first { it != state }
                        if (next is IrcClientState.Ready) {
                            state = next
                            continue
                        }
                        readyJob?.cancel()
                        readyJob = null
                        stableJob?.cancel()
                        stableJob = null
                        onState(networkId, next)
                        return outcomeFor(next)
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
        const val JITTER_LOW = 0.7
        const val JITTER_HIGH = 1.3
        private const val MAX_SHIFT = 30
    }
}
