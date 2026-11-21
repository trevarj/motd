package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.event.IrcClientState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class ConnectionActorSnapshot(
    val connection: ManagedConnection?,
    val isAlive: Boolean,
    val fingerprint: String,
    val generation: Long,
)

internal data class ConnectionRegistrySnapshot(
    val started: Boolean = false,
    val actors: Map<Long, ConnectionActorSnapshot> = emptyMap(),
    val states: Map<Long, IrcClientState> = emptyMap(),
    val observerCount: Int = 0,
    val pendingEchoCount: Int = 0,
    val fingerprintCount: Int = 0,
    val terminalFingerprintCount: Int = 0,
    val callbackCount: Int = 0,
)

/** Single command-loop owner for connection actors and their lifecycle-adjacent jobs. */
internal class ConnectionRegistry(
    private val scope: CoroutineScope,
    private val actorFactory: (NetworkEntity, Long) -> ConnectionLifecycleActor,
    private val isConfigurationFailure: (String) -> Boolean,
    private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
) {
    private sealed interface Command {
        data class BeginStart(
            val result: CompletableDeferred<Boolean>,
        ) : Command

        data class AttachObservers(
            val jobs: List<Job>,
            val result: CompletableDeferred<Unit>,
        ) : Command

        data class Stop(
            val result: CompletableDeferred<List<Job>>,
        ) : Command

        data class Reconcile(
            val rows: List<Pair<NetworkEntity, String>>,
            val wantedIds: Set<Long>,
            val awaitingCertTrust: Set<Long>,
            val result: CompletableDeferred<Unit>,
        ) : Command

        data class Connect(
            val row: NetworkEntity,
            val fingerprint: String,
            val result: CompletableDeferred<Unit>,
        ) : Command

        data class EnsureScoped(
            val row: NetworkEntity,
            val fingerprint: String,
            val awaitingCertTrust: Set<Long>,
            val result: CompletableDeferred<Unit>,
        ) : Command

        data class Disconnect(
            val networkId: Long,
            val result: CompletableDeferred<Unit>,
        ) : Command

        data class ActorState(
            val networkId: Long,
            val generation: Long,
            val fingerprint: String,
            val state: IrcClientState,
            val result: CompletableDeferred<Unit>,
        ) : Command

        data class ActorConnection(
            val networkId: Long,
            val generation: Long,
            val connection: ManagedConnection?,
        ) : Command

        data class ActorStopped(
            val networkId: Long,
            val generation: Long,
        ) : Command

        data class HistoryCatchUpFinished(
            val networkId: Long,
            val generation: Long,
            val result: CompletableDeferred<Unit>,
        ) : Command

        data class Callback(
            val networkId: Long,
            val generation: Long,
            val block: suspend () -> Unit,
            val result: CompletableDeferred<Boolean>,
        ) : Command

        data class CallbackFinished(
            val token: Long,
        ) : Command

        data object NetworkAvailable : Command

        data object NetworkLost : Command

        data object WakeNonReady : Command

        data object ProbeReady : Command

        data class ArmEchoTimeout(
            val key: String,
            val timeoutMs: Long,
            val onTimeout: suspend () -> Unit,
        ) : Command

        data class EchoTimeoutFinished(
            val key: String,
            val token: Long,
        ) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val generations = ConnectionGenerationGate()

    private data class OwnedActor(
        val actor: ConnectionLifecycleActor,
        val fingerprint: String,
        val generation: Long,
        var connection: ManagedConnection? = null,
        var isAlive: Boolean = false,
    )

    private val actors = LinkedHashMap<Long, OwnedActor>()
    private val states = LinkedHashMap<Long, IrcClientState>()
    private val terminalFingerprints = HashMap<Long, String>()

    // Connection-lifecycle state, not a fetch lock: it tracks the in-flight reconnect catch-up
    // generation per network so entryHistoryReady stays gated until that catch-up settles. All
    // CHATHISTORY wire serialization lives in HistoryPageLoader's per-network lock, not here.
    private val historyCatchUpGenerations = HashMap<Long, Long>()
    private val observerJobs = mutableListOf<Job>()
    private val pendingEchoJobs = HashMap<String, Pair<Long, Job>>()

    private data class CallbackJob(
        val networkId: Long,
        val generation: Long,
        val job: Job,
    )

    private val callbackJobs = HashMap<Long, CallbackJob>()
    private val echoTokens = AtomicLong()
    private val callbackTokens = AtomicLong()
    private val probeReadyPending = AtomicBoolean(false)
    private var started = false
    private var initialReconcileComplete = false

    private val _snapshot = MutableStateFlow(ConnectionRegistrySnapshot())
    val snapshot: StateFlow<ConnectionRegistrySnapshot> = _snapshot.asStateFlow()
    private val _states = MutableStateFlow<Map<Long, IrcClientState>>(emptyMap())
    val connectionStates: StateFlow<Map<Long, IrcClientState>> = _states.asStateFlow()
    private val _connectionActivity =
        MutableStateFlow(
            ConnectionActivitySnapshot(initializationComplete = false),
        )
    val connectionActivity: StateFlow<ConnectionActivitySnapshot> = _connectionActivity.asStateFlow()

    init {
        scope.launch {
            for (command in commands) {
                // Per-iteration containment, not decoration: [commands] is UNLIMITED and never
                // closed, so a throw that kills this loop still lets every later [request] send
                // successfully and then park forever on a CompletableDeferred nobody will
                // complete. One bad command would wedge every connect/disconnect/reconcile for
                // the process lifetime.
                try {
                    handle(command)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    diagnostics.record("connections", "command_failed") {
                        mapOf(
                            "command" to command::class.simpleName,
                            "error" to error::class.simpleName,
                        )
                    }
                    releaseWaiter(command)
                }
            }
        }
    }

    /**
     * Answer the failed command's waiter so surviving the throw is not just the loop's privilege.
     *
     * The caller is already parked inside [request]; leaving it there converts a contained failure
     * back into the permanent wedge this guard exists to prevent. Every waiter settles on the same
     * "it did not happen" answer the callback barrier in [Command.Callback] already gives.
     * Completing exceptionally instead would only relocate the crash into the requester's scope.
     */
    private fun releaseWaiter(command: Command) {
        when (command) {
            is Command.BeginStart -> command.result.complete(false)

            is Command.AttachObservers -> command.result.complete(Unit)

            is Command.Stop -> command.result.complete(emptyList())

            is Command.Reconcile -> command.result.complete(Unit)

            is Command.Connect -> command.result.complete(Unit)

            is Command.EnsureScoped -> command.result.complete(Unit)

            is Command.Disconnect -> command.result.complete(Unit)

            is Command.ActorState -> command.result.complete(Unit)

            is Command.HistoryCatchUpFinished -> command.result.complete(Unit)

            is Command.Callback -> command.result.complete(false)

            // Fire-and-forget commands have no waiter to release. Kept exhaustive on purpose: a
            // new command with a result must decide its own contained answer here.
            is Command.ActorConnection,
            is Command.ActorStopped,
            is Command.CallbackFinished,
            is Command.ArmEchoTimeout,
            is Command.EchoTimeoutFinished,
            Command.NetworkAvailable,
            Command.NetworkLost,
            Command.WakeNonReady,
            Command.ProbeReady,
            -> Unit
        }
    }

    suspend fun beginStart(): Boolean = request { Command.BeginStart(it) }

    suspend fun attachObservers(jobs: List<Job>) = request<Unit> { Command.AttachObservers(jobs, it) }

    suspend fun stop() {
        request<List<Job>> { Command.Stop(it) }.forEach { it.cancelAndJoin() }
    }

    suspend fun reconcile(
        rows: List<Pair<NetworkEntity, String>>,
        wantedIds: Set<Long>,
        awaitingCertTrust: Set<Long>,
    ) = request<Unit> { Command.Reconcile(rows, wantedIds, awaitingCertTrust, it) }

    suspend fun connect(
        row: NetworkEntity,
        fingerprint: String,
    ) = request<Unit> { Command.Connect(row, fingerprint, it) }

    suspend fun ensureScoped(
        row: NetworkEntity,
        fingerprint: String,
        awaitingCertTrust: Set<Long>,
    ) = request<Unit> { Command.EnsureScoped(row, fingerprint, awaitingCertTrust, it) }

    suspend fun disconnect(networkId: Long) = request<Unit> { Command.Disconnect(networkId, it) }

    suspend fun actorState(
        networkId: Long,
        generation: Long,
        fingerprint: String,
        state: IrcClientState,
    ) = request<Unit> { Command.ActorState(networkId, generation, fingerprint, state, it) }

    fun actorConnection(
        networkId: Long,
        generation: Long,
        connection: ManagedConnection?,
    ) {
        commands.trySend(Command.ActorConnection(networkId, generation, connection))
    }

    fun actorStopped(
        networkId: Long,
        generation: Long,
    ) {
        commands.trySend(Command.ActorStopped(networkId, generation))
    }

    suspend fun historyCatchUpFinished(
        networkId: Long,
        generation: Long,
    ) = request<Unit> { Command.HistoryCatchUpFinished(networkId, generation, it) }

    suspend fun runIfCurrent(
        networkId: Long,
        generation: Long,
        block: suspend () -> Unit,
    ): Boolean = request { Command.Callback(networkId, generation, block, it) }

    fun armEchoTimeout(
        key: String,
        timeoutMs: Long,
        onTimeout: suspend () -> Unit,
    ) {
        commands.trySend(Command.ArmEchoTimeout(key, timeoutMs, onTimeout))
    }

    fun networkAvailable() {
        commands.trySend(Command.NetworkAvailable)
    }

    fun networkLost() {
        commands.trySend(Command.NetworkLost)
    }

    fun wakeNonReady() {
        commands.trySend(Command.WakeNonReady)
    }

    /** Queue one liveness probe for every currently Ready actor; repeated requests are conflated. */
    fun probeReady() {
        if (!probeReadyPending.compareAndSet(false, true)) return
        if (!commands.trySend(Command.ProbeReady).isSuccess) probeReadyPending.set(false)
    }

    fun isCurrent(
        networkId: Long,
        generation: Long,
    ): Boolean = generations.isCurrent(networkId, generation)

    private suspend fun handle(command: Command) {
        when (command) {
            is Command.BeginStart -> {
                val changed = !started
                if (changed) {
                    started = true
                    initialReconcileComplete = false
                    publish()
                }
                command.result.complete(changed)
            }

            is Command.AttachObservers -> {
                if (started) {
                    observerJobs += command.jobs
                } else {
                    command.jobs.forEach(Job::cancel)
                }
                publish()
                command.result.complete(Unit)
            }

            is Command.Stop -> {
                started = false
                initialReconcileComplete = true
                probeReadyPending.set(false)
                val cleanupJobs = observerJobs.toList() + pendingEchoJobs.values.map { it.second }
                cleanupJobs.forEach(Job::cancel)
                observerJobs.clear()
                pendingEchoJobs.clear()
                // Generations MUST be invalidated before any stopAndJoin(): an actor's readyJob
                // releases the history gate from a NonCancellable finally, which is a suspending
                // round-trip back through THIS command loop. Invalidating first makes that release
                // see a dead generation and short-circuit, so the loop never joins a child that is
                // waiting on the loop. Reorder these two lines and shutdown deadlocks.
                generations.invalidateAll()
                callbackJobs.values.map { it.job }.forEach { it.cancelAndJoin() }
                callbackJobs.clear()
                actors.values.map { it.actor }.forEach { it.stopAndJoin() }
                actors.clear()
                states.clear()
                terminalFingerprints.clear()
                historyCatchUpGenerations.clear()
                publish()
                command.result.complete(cleanupJobs)
            }

            is Command.Reconcile -> {
                if (!started) {
                    command.result.complete(Unit)
                    return
                }
                val rowsById = command.rows.associate { it.first.id to it }
                for (id in actors.keys.toList()) {
                    if (id !in command.wantedIds) removeActor(id, clearTerminal = true)
                }
                command.wantedIds.forEach { id ->
                    val (row, fingerprint) = rowsById[id] ?: return@forEach
                    ensureActor(row, fingerprint, command.awaitingCertTrust)
                }
                initialReconcileComplete = true
                publish()
                command.result.complete(Unit)
            }

            is Command.Connect -> {
                if (!started) {
                    command.result.complete(Unit)
                    return
                }
                // Automatic reconciliation honors terminal configuration failures to avoid a
                // retry storm, but an explicit user action must be able to retry unchanged
                // settings after a transient provider failure.
                terminalFingerprints.remove(command.row.id)
                removeActor(command.row.id, clearTerminal = false)
                ensureActor(command.row, command.fingerprint, emptySet())
                publish()
                command.result.complete(Unit)
            }

            is Command.EnsureScoped -> {
                ensureActor(command.row, command.fingerprint, command.awaitingCertTrust)
                publish()
                command.result.complete(Unit)
            }

            is Command.Disconnect -> {
                removeActor(command.networkId, clearTerminal = false)
                publish()
                command.result.complete(Unit)
            }

            is Command.ActorState -> {
                if (generations.isCurrent(command.networkId, command.generation)) {
                    if (command.state is IrcClientState.Failed && command.state.fatal &&
                        isConfigurationFailure(command.state.reason)
                    ) {
                        terminalFingerprints[command.networkId] = command.fingerprint
                    }
                    val prior = states[command.networkId]
                    when {
                        command.state is IrcClientState.Ready && prior !is IrcClientState.Ready -> {
                            historyCatchUpGenerations[command.networkId] = command.generation
                        }

                        command.state !is IrcClientState.Ready -> {
                            historyCatchUpGenerations.remove(command.networkId)
                        }
                    }
                    states[command.networkId] = command.state
                    publish()
                }
                command.result.complete(Unit)
            }

            is Command.ActorConnection -> {
                if (generations.isCurrent(command.networkId, command.generation)) {
                    actors[command.networkId]?.connection = command.connection
                    publish()
                }
            }

            is Command.ActorStopped -> {
                if (generations.isCurrent(command.networkId, command.generation)) {
                    actors[command.networkId]?.let {
                        it.connection = null
                        it.isAlive = false
                    }
                    historyCatchUpGenerations.remove(command.networkId)
                    publish()
                }
            }

            is Command.HistoryCatchUpFinished -> {
                if (historyCatchUpGenerations[command.networkId] == command.generation) {
                    historyCatchUpGenerations.remove(command.networkId)
                    publish()
                }
                command.result.complete(Unit)
            }

            is Command.Callback -> {
                if (!generations.isCurrent(command.networkId, command.generation)) {
                    command.result.complete(false)
                    return
                }
                val token = callbackTokens.incrementAndGet()
                val job =
                    scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                        try {
                            if (generations.isCurrent(command.networkId, command.generation)) {
                                command.block()
                                command.result.complete(true)
                            } else {
                                command.result.complete(false)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            // Deliberate crash-containment barrier: a failed post-Ready Room write must
                            // not take the process down. The write is still lost, so record it —
                            // otherwise the loss is completely silent.
                            diagnostics.record("connections", "callback_failed") {
                                mapOf(
                                    "network_id" to command.networkId,
                                    "error" to error::class.simpleName,
                                )
                            }
                            command.result.complete(false)
                        } finally {
                            command.result.complete(false)
                            commands.trySend(Command.CallbackFinished(token))
                        }
                    }
                callbackJobs[token] = CallbackJob(command.networkId, command.generation, job)
                publish()
                job.start()
            }

            is Command.CallbackFinished -> {
                callbackJobs.remove(command.token)
                publish()
            }

            // The two wake sources stay distinguishable inside the actor: connectivity may only
            // reset the backoff escalation on a real loss→available edge, while the (human-paced)
            // foreground wake always does. See WakeCause.
            Command.NetworkAvailable -> {
                actors.values.forEach { it.actor.onNetworkAvailable(WakeCause.Connectivity) }
            }

            Command.NetworkLost -> {
                actors.values.forEach { it.actor.onNetworkLost() }
            }

            Command.WakeNonReady -> {
                actors.forEach { (networkId, registered) ->
                    if (registered.actor.isAlive && states[networkId] !is IrcClientState.Ready) {
                        registered.actor.onNetworkAvailable(WakeCause.Foreground)
                    }
                }
            }

            Command.ProbeReady -> {
                try {
                    actors.forEach { (networkId, registered) ->
                        if (registered.actor.isAlive && states[networkId] is IrcClientState.Ready) {
                            registered.actor.probe()
                        }
                    }
                } finally {
                    probeReadyPending.set(false)
                }
            }

            is Command.ArmEchoTimeout -> {
                if (!started) return
                pendingEchoJobs.remove(command.key)?.second?.cancel()
                val token = echoTokens.incrementAndGet()
                val job =
                    scope.launch {
                        delay(command.timeoutMs)
                        command.onTimeout()
                        commands.send(Command.EchoTimeoutFinished(command.key, token))
                    }
                pendingEchoJobs[command.key] = token to job
                publish()
            }

            is Command.EchoTimeoutFinished -> {
                if (pendingEchoJobs[command.key]?.first == command.token) {
                    pendingEchoJobs.remove(command.key)
                    publish()
                }
            }
        }
    }

    private suspend fun ensureActor(
        row: NetworkEntity,
        fingerprint: String,
        awaitingCertTrust: Set<Long>,
    ) {
        if (terminalFingerprints[row.id] == fingerprint) return
        terminalFingerprints.remove(row.id)
        val existing = actors[row.id]
        if (existing != null &&
            !shouldRebuildActor(
                fingerprintChanged = existing.fingerprint != fingerprint,
                actorAlive = existing.actor.isAlive,
                lastState = states[row.id],
                awaitingCertTrust = row.id in awaitingCertTrust,
            )
        ) {
            return
        }

        // Invalidate before stopAndJoin -- see the ordering note in Command.Stop.
        generations.invalidate(row.id)
        existing?.actor?.stopAndJoin()
        val generation = generations.begin(row.id)
        val actor = actorFactory(row, generation)
        val owned = OwnedActor(actor, fingerprint, generation, actor.connection)
        actors[row.id] = owned
        actor.start()
        owned.isAlive = actor.isAlive
    }

    private suspend fun removeActor(
        networkId: Long,
        clearTerminal: Boolean,
    ) {
        // Invalidate before stopAndJoin -- see the ordering note in Command.Stop.
        generations.invalidate(networkId)
        callbackJobs.filterValues { it.networkId == networkId }.toList().forEach { (token, callback) ->
            callback.job.cancelAndJoin()
            callbackJobs.remove(token)
        }
        actors.remove(networkId)?.actor?.stopAndJoin()
        // State belongs to the actor generation. Keeping its last Connecting/Failed value after
        // the actor is removed leaves an orphan in connectionStates forever: the drawer can show
        // a replacement network as Ready while the global banner still reports the stale actor.
        states.remove(networkId)
        historyCatchUpGenerations.remove(networkId)
        if (clearTerminal) terminalFingerprints.remove(networkId)
    }

    private fun publish() {
        val immutableStates = states.toMap()
        _snapshot.value =
            ConnectionRegistrySnapshot(
                started = started,
                actors =
                    actors.mapValues { (_, owned) ->
                        ConnectionActorSnapshot(
                            connection = owned.connection,
                            isAlive = owned.isAlive,
                            fingerprint = owned.fingerprint,
                            generation = owned.generation,
                        )
                    },
                states = immutableStates,
                observerCount = observerJobs.size,
                pendingEchoCount = pendingEchoJobs.size,
                fingerprintCount = actors.size,
                terminalFingerprintCount = terminalFingerprints.size,
                callbackCount = callbackJobs.size,
            )
        _connectionActivity.value =
            ConnectionActivitySnapshot(
                states = immutableStates,
                progressing = actors.mapValues { (_, actor) -> actor.isAlive },
                initializationComplete = initialReconcileComplete,
                historyCatchUpPending = historyCatchUpGenerations.keys.toSet(),
            )
        _states.value = immutableStates
    }

    private suspend fun <T> request(command: (CompletableDeferred<T>) -> Command): T {
        val result = CompletableDeferred<T>()
        commands.send(command(result))
        return result.await()
    }
}
