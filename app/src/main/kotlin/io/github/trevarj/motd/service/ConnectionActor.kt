package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Minimal seam over [IrcClient] so [ConnectionActor]'s reconnect loop is unit-testable with a fake
 * (a real [IrcClient] driven through CAP/registration under virtual time is fragile).
 */
interface ManagedConnection {
    val state: StateFlow<IrcClientState>
    val criticalEvents: ReceiveChannel<IrcEvent>

    fun start()

    fun stop()

    /** Completes after the producer has closed [criticalEvents] on a normal terminal connection. */
    suspend fun awaitTermination()

    /**
     * Latest PING/PONG round-trip latency in ms (issue #34), or null when no measurement is
     * available. Defaults to a constant null flow for fakes that do not model latency.
     */
    val lag: StateFlow<Long?> get() = NULL_LAG_FLOW

    /**
     * Send an immediate watchdog-style liveness probe. Implementations that do not expose a
     * transport-level probe retain the historical no-op/healthy behavior.
     */
    suspend fun probeLiveness(graceMs: Long): Boolean = true
}

/** Constant null latency flow backing [ManagedConnection.lag] for connections without a probe. */
private val NULL_LAG_FLOW: StateFlow<Long?> = kotlinx.coroutines.flow.MutableStateFlow(null)

/** Adapter wrapping a real [IrcClient]. */
class IrcClientConnection(
    val client: IrcClient,
    private val onStop: () -> Unit = {},
) : ManagedConnection {
    override val state: StateFlow<IrcClientState> get() = client.state
    override val criticalEvents: ReceiveChannel<IrcEvent> get() = client.criticalEvents
    override val lag: StateFlow<Long?> get() = client.lag

    override fun start() = client.start()

    override suspend fun awaitTermination() = client.awaitTermination()

    override suspend fun probeLiveness(graceMs: Long): Boolean = client.probeLiveness(graceMs)

    override fun stop() {
        client.stop()
        onStop()
    }
}

/**
 * Why a backing-off actor was told to retry now. Both causes cut the remaining backoff delay short;
 * they differ only in whether they are allowed to reset the escalation the delay was built from.
 */
enum class WakeCause {
    /**
     * Android connectivity reported a default network (`ConnectivityManager.onAvailable`). Resets
     * the escalation only on a genuine loss→available edge: a flapping network delivers onAvailable
     * repeatedly without any loss in between, and resetting on each one pins the attempt counter at
     * 0 and turns backoff into a permanent ~2s redial storm.
     */
    Connectivity,

    /**
     * The app was foregrounded (`ConnectionRegistry.wakeNonReady`, raised by
     * `ConnectionManagerImpl.reconnectStale`). Always resets: after Doze the counter sits at the cap
     * from dials that fast-failed against a dead radio, and this wake is human-paced, so it cannot
     * storm.
     */
    Foreground,
}

/**
 * Drives one physical socket. Owns the reconnect loop with exponential backoff, the
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

    fun onNetworkAvailable(cause: WakeCause)

    fun onNetworkLost()

    /** Request one immediate probe when the current connection is Ready. Requests are conflated. */
    fun probe() = Unit
}

class ConnectionActor(
    val networkId: Long,
    private val scope: CoroutineScope,
    private val connectionFactory: suspend () -> ManagedConnection,
    private val onState: suspend (Long, IrcClientState) -> Unit,
    private val onEvents: suspend (Long, List<IrcEvent>) -> Unit,
    private val onReady: suspend (ManagedConnection) -> Unit,
    private val onConnectionChanged: (Long, ManagedConnection?) -> Unit = { _, _ -> },
    private val onLag: (Long, Long?) -> Unit = { _, _ -> },
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
    /**
     * Reports the reconnect schedule so a journal can show what the loop actually did. Called with
     * `scheduled` when a delay is chosen and `elapsed`/`woke_early` when the wait ends, so a reader
     * can tell a slow recovery apart from one that never woke. Defaulted to a no-op: the loop stays
     * deterministic under virtual time and the existing tests construct the actor unchanged.
     */
    private val onBackoff: (phase: String, attempt: Int, delayMs: Long) -> Unit = { _, _, _ -> },
) : ConnectionLifecycleActor {
    @Volatile override var connection: ManagedConnection? = null
        private set

    private var job: Job? = null

    /**
     * Wake signals for a backing-off loop. The payload is whether the wake invalidates the current
     * escalation; conflated like the channel itself, so back-to-back wakes keep the last cause.
     */
    private val retryNow = Channel<Boolean>(Channel.CONFLATED)
    private val probeNow = Channel<Unit>(Channel.CONFLATED)
    private val probeRequested = AtomicBoolean(false)

    /**
     * Armed by [onNetworkLost] and consumed by the next connectivity wake, so "connectivity is
     * available" only counts as a loss→available edge once per actual loss.
     */
    private val connectivityLost = AtomicBoolean(false)

    /**
     * True while the reconnect loop coroutine is still running. Goes false once the loop returns —
     * i.e. a fatal Failed or a cert-untrusted park completed the [job]. The manager uses this as a
     * liveness signal: an actor sitting in [ConnectionManagerImpl]'s actor map with a dead job
     * cannot recover on its own, so a self-healing reconcile must drop and rebuild it.
     */
    override val isAlive: Boolean get() = job?.isActive == true

    override fun start() {
        if (job?.isActive == true) return
        job =
            scope.launch { loop() }.also { running ->
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

    /**
     * Skip the remaining backoff delay and retry immediately. Named for its original caller (the
     * connectivity callback), but it is the actor's general "a retry is worth trying now" entry:
     * app-foreground recovery raises it too via `ConnectionRegistry.wakeNonReady()`.
     *
     * [cause] decides whether the escalation is also reset — see [WakeCause]. Both causes still cut
     * the wait short, so a flapping network is redialled promptly; it just is not redialled from the
     * 2s base forever.
     */
    override fun onNetworkAvailable(cause: WakeCause) {
        val resetsBackoff =
            when (cause) {
                WakeCause.Foreground -> true
                WakeCause.Connectivity -> connectivityLost.getAndSet(false)
            }
        retryNow.trySend(resetsBackoff)
    }

    /** Network lost: fast-fail the current connect attempt so backoff starts promptly. */
    override fun onNetworkLost() {
        connectivityLost.set(true)
        connection?.stop()
    }

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
            val collector =
                attemptScope.launch { collectCriticalEvents(conn.criticalEvents) }
            // Forward latency readings for this connection's lifetime; cancelled with the attempt
            // scope so a replaced/destroyed actor never reports a stale measurement.
            val lagCollector =
                attemptScope.launch {
                    conn.lag.collect { lag -> onLag(networkId, lag) }
                }
            val outcome =
                try {
                    runConnection(conn) { attempt = 0 }
                } finally {
                    if (currentCoroutineContext().isActive) {
                        // State becomes terminal before the socket reader can publish its final event.
                        // Wait for the producer to close, then let the sole consumer empty the queue.
                        conn.awaitTermination()
                        collector.join()
                    } else {
                        collector.cancelAndJoin()
                    }
                    conn.stop()
                    connection = null
                    onConnectionChanged(networkId, null)
                    // Stop forwarding readings before clearing the published latency, so a final
                    // emission cannot race the null and repaint a stale RTT during backoff.
                    lagCollector.cancel()
                    onLag(networkId, null)
                }

            // TOFU: a cert failure parks the actor (awaiting user trust) rather than backoff-looping.
            val certFailure = pendingCertFailure()
            if (certFailure != null) {
                onState(networkId, IrcClientState.Failed("certificate not trusted", fatal = false))
                onCertUntrusted(networkId, certFailure)
                return
            }

            when (outcome) {
                Outcome.Fatal -> {
                    return
                }

                Outcome.RetryImmediately -> {}

                Outcome.Retry -> {
                    // A disconnected/non-fatal Failed snapshot describes the socket that just
                    // ended. Publish the current operation (retrying) during backoff so the UI
                    // does not retain a stale SOCKS/tunnel error until the next dial begins.
                    onState(networkId, IrcClientState.Connecting)
                    val delayMs = backoffDelayMs(attempt)
                    onBackoff("scheduled", attempt, delayMs)
                    val scheduledFor = attempt
                    attempt++
                    val wake = waitBeforeRetry(delayMs)
                    onBackoff(if (wake != null) "woke_early" else "elapsed", scheduledFor, delayMs)
                    if (wake != null) {
                        // One wake releases every actor on the same scheduler tick (the registry
                        // hands it to each of them in a synchronous forEach), so without an offset a
                        // bouncer root and all of its children redial through one endpoint
                        // simultaneously. Spread the woken dials across a small window instead.
                        val stagger = wakeStaggerMs()
                        if (stagger > 0) {
                            onBackoff("wake_stagger", scheduledFor, stagger)
                            delay(stagger)
                        }
                        if (wake) {
                            // A wake that carries a reset invalidates the failures this escalation
                            // was built on: after Doze the counter sits at the cap from dials that
                            // fast-failed against a dead network, and without this reset one failed
                            // post-wake dial would serve the full ~90s cap as the user's very next
                            // wait. Re-escalate from the base instead. A bare connectivity
                            // onAvailable with no preceding loss carries no reset, so a flapping
                            // network cannot pin the counter at 0 and dial every ~2s forever.
                            attempt = 0
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun collectCriticalEvents(events: ReceiveChannel<IrcEvent>) {
        for (event in events) {
            if (!event.isPeerPresence()) {
                onEvents(networkId, listOf(event))
                continue
            }
            val batch = ArrayList<IrcEvent>(64)
            batch.add(event)
            // ponytail: one deadline per batch; a busy socket must not postpone a commit forever.
            // Quiet peers wait up to 250ms; any other event flushes them in wire order immediately.
            val deadline = CoroutineScope(currentCoroutineContext()).launch { delay(5_000) }
            try {
                while (batch.size < 64) {
                    val (received, next) =
                        select<Pair<Boolean, IrcEvent?>> {
                            deadline.onJoin { false to null }
                            events.onReceiveCatching { true to it.getOrNull() }
                            onTimeout(250) { false to null }
                        }
                    if (next == null) {
                        onEvents(networkId, batch)
                        if (received) return // Closed: drain the final batch before reconnecting.
                        break
                    }
                    if (!next.isPeerPresence()) {
                        onEvents(networkId, batch)
                        onEvents(networkId, listOf(next))
                        break
                    }
                    batch.add(next)
                }
                if (batch.size == 64) onEvents(networkId, batch)
            } finally {
                deadline.cancel()
            }
        }
    }

    private fun IrcEvent.isPeerPresence(): Boolean = (this is IrcEvent.Joined && !isSelf) || (this is IrcEvent.Parted && !isSelf)

    private enum class Outcome { Fatal, Retry, RetryImmediately }

    /**
     * Wait for the connection to terminate. On Ready, runs [onReady] and arms a stability timer
     * that invokes [resetBackoff] after 5 minutes still-connected. Returns Fatal for a fatal
     * Failed, Retry otherwise.
     */
    private suspend fun runConnection(
        conn: ManagedConnection,
        resetBackoff: () -> Unit,
    ): Outcome {
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
                            stableJob =
                                connectionScope.launch {
                                    delay(STABLE_RESET_MS)
                                    resetBackoff()
                                }
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

                    is IrcClientState.Registering -> {
                        // Transport is established (SOCKS/TCP/TLS all done) and IRC registration is
                        // in flight. Forwarded — unlike Connecting, which the loop entered with —
                        // so the diagnostic journal can split dial time from registration time and
                        // the manager can revive bouncer children as soon as the shared endpoint
                        // proves reachable (ConnectionManagerImpl.reviveChildrenOf).
                        onState(networkId, state)
                        state = conn.state.first { it != state }
                    }

                    IrcClientState.Connecting -> {
                        state = conn.state.first { it != state }
                    }
                }
            }
        } finally {
            readyJob?.cancelAndJoin()
            stableJob?.cancelAndJoin()
        }
    }

    private fun outcomeFor(state: IrcClientState): Outcome = if (state is IrcClientState.Failed && state.fatal) Outcome.Fatal else Outcome.Retry

    private fun clearProbeRequests() {
        probeRequested.set(false)
        while (probeNow.tryReceive().isSuccess) { }
    }

    private sealed interface ReadyUpdate {
        data object Probe : ReadyUpdate

        data class StateChanged(
            val state: IrcClientState,
        ) : ReadyUpdate
    }

    private sealed interface ProbeUpdate {
        data class Completed(
            val live: Boolean,
        ) : ProbeUpdate

        data class StateChanged(
            val state: IrcClientState,
        ) : ProbeUpdate
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
        val probe =
            connectionScope.async {
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

    /**
     * Wait for the backoff delay, waking early on [onNetworkAvailable]. Returns null when the full
     * delay was served, otherwise the wake's "resets the escalation" verdict (see [WakeCause]).
     *
     * [onNetworkAvailable] is the single wake entry, but it is not only raised by Android
     * connectivity: `ConnectionRegistry` also raises it for every non-Ready actor on
     * `wakeNonReady()`, which `ConnectionManagerImpl.reconnectStale()` calls on every app-foreground
     * transition. So a bouncer that returns while the device stayed online is redialled as soon as
     * the user next foregrounds motd, and an explicit user connect bypasses the wait entirely by
     * rebuilding the actor. Absent any of those, the delay is served in full, which is deliberate:
     * it is what keeps a genuinely-down server from being hammered.
     */
    private suspend fun waitBeforeRetry(delayMs: Long): Boolean? {
        // Signals raised while the previous dial was in flight had nothing waiting on them; drop
        // them together with the reset they carried rather than let them cut a later wait short.
        while (retryNow.tryReceive().isSuccess) { /* drain stale signals */ }
        val timer = CoroutineScope(currentCoroutineContext()).launch { delay(delayMs) }
        var wake: Boolean? = null
        try {
            select {
                retryNow.onReceive { wake = it }
                timer.onJoin { }
            }
        } finally {
            timer.cancelAndJoin()
        }
        return wake
    }

    /** delay = min(cap, base * 2^attempt) * jitter(0.7..1.3). */
    fun backoffDelayMs(attempt: Int): Long {
        val exp = BASE_MS * (1L shl attempt.coerceAtMost(MAX_SHIFT))
        val capped = minOf(CAP_MS, exp)
        val jitter = JITTER_LOW + random() * (JITTER_HIGH - JITTER_LOW)
        return (capped * jitter).toLong()
    }

    /**
     * Per-wake offset in 0..[WAKE_STAGGER_MAX_MS], drawn from the same injected [random] the jitter
     * uses so tests stay deterministic. Deliberately much shorter than the 2s backoff base: it
     * de-synchronizes simultaneously-woken actors without measurably delaying the recovery the wake
     * exists to deliver.
     */
    fun wakeStaggerMs(): Long = (WAKE_STAGGER_MAX_MS * random()).toLong()

    companion object {
        const val BASE_MS = 2_000L
        const val CAP_MS = 90_000L
        const val WAKE_STAGGER_MAX_MS = 1_000L
        const val STABLE_RESET_MS = 5 * 60 * 1000L
        const val FOREGROUND_PROBE_GRACE_MS = 5_000L
        const val JITTER_LOW = 0.7
        const val JITTER_HIGH = 1.3
        private const val MAX_SHIFT = 30
    }
}
