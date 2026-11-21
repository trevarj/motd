package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.diagnostics.RecordingDiagnostics
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.chat.entryHistoryReady
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionRegistryTest {
    private class FakeActor : ConnectionLifecycleActor {
        override val connection: ManagedConnection? = null
        override var isAlive = false
        var starts = 0
        var stops = 0
        var probes = 0
        var wakes = 0
        val wakeCauses = mutableListOf<WakeCause>()
        var losses = 0

        override fun start() {
            starts++
            isAlive = true
        }

        override fun stop() {
            stops++
            isAlive = false
        }

        override suspend fun stopAndJoin() = stop()

        override fun onNetworkAvailable(cause: WakeCause) {
            wakes++
            wakeCauses += cause
        }

        override fun onNetworkLost() {
            losses++
        }

        override fun probe() {
            probes++
        }
    }

    private fun network(
        id: Long = 1,
        host: String = "irc.example",
    ) = NetworkEntity(
        id = id,
        name = "network-$id",
        role = NetworkRole.DIRECT,
        host = host,
        port = 6697,
        nick = "me",
        username = "me",
        realname = "Me",
    )

    @Test
    fun scopedConnectionStartsOnlyItsTargetAndIsKeptWhenAdopted() =
        runTest {
            val created = mutableListOf<FakeActor>()
            val registry = ConnectionRegistry(backgroundScope, { _, _ -> FakeActor().also(created::add) }, { false })
            val target = network()
            val other = network(id = 2)

            registry.ensureScoped(target, "primary", emptySet())
            assertFalse(registry.snapshot.value.started)
            assertEquals(setOf(1L), registry.snapshot.value.actors.keys)
            val originalGeneration =
                registry.snapshot.value.actors
                    .getValue(1)
                    .generation

            registry.beginStart()
            registry.reconcile(listOf(target to "primary", other to "other"), setOf(1, 2), emptySet())
            assertEquals(setOf(1L, 2L), registry.snapshot.value.actors.keys)
            assertEquals(
                originalGeneration,
                registry.snapshot.value.actors
                    .getValue(1)
                    .generation,
            )
            assertEquals(0, created.first().stops)

            registry.reconcile(listOf(target to "primary", other to "other"), setOf(2), emptySet())
            assertEquals(setOf(2L), registry.snapshot.value.actors.keys)
            assertEquals(1, created.first().stops)
        }

    @Test
    fun connectionActivityPublishesLifecycleStateAndProgressAtomically() =
        runTest {
            val registry =
                ConnectionRegistry(
                    backgroundScope,
                    actorFactory = { _, _ -> FakeActor() },
                    isConfigurationFailure = { false },
                )

            registry.beginStart()
            assertFalse(registry.connectionActivity.value.initializationComplete)
            registry.reconcile(listOf(network() to "fp"), setOf(1), emptySet())
            val generation =
                registry.snapshot.value.actors
                    .getValue(1)
                    .generation
            registry.actorState(1, generation, "fp", IrcClientState.Connecting)
            runCurrent()

            val activity = registry.connectionActivity.value
            assertTrue(activity.initializationComplete)
            assertTrue(activity.progressing.getValue(1))
            assertEquals(IrcClientState.Connecting, activity.states.getValue(1))
            assertEquals(registry.connectionStates.value, activity.states)
        }

    @Test
    fun historyCatchupReadinessStaysAtomicAcrossRecoverableReconnect() =
        runTest {
            val registry =
                ConnectionRegistry(
                    backgroundScope,
                    actorFactory = { _, _ -> FakeActor() },
                    isConfigurationFailure = { false },
                )
            registry.beginStart()
            registry.reconcile(listOf(network() to "fp"), setOf(1), emptySet())
            val generation =
                registry.snapshot.value.actors
                    .getValue(1)
                    .generation

            registry.actorState(1, generation, "fp", IrcClientState.Ready("me", emptySet(), emptyMap()))
            runCurrent()
            assertFalse(entryHistoryReady(registry.connectionActivity.value, 1))

            registry.actorState(1, generation, "fp", IrcClientState.Failed("retry", fatal = false))
            runCurrent()
            val retrying = registry.connectionActivity.value
            assertTrue(retrying.states[1] is IrcClientState.Failed)
            assertFalse(1 in retrying.historyCatchUpPending)
            assertFalse(entryHistoryReady(retrying, 1))

            registry.actorState(1, generation, "fp", IrcClientState.Ready("me", emptySet(), emptyMap()))
            runCurrent()
            assertFalse(entryHistoryReady(registry.connectionActivity.value, 1))

            registry.historyCatchUpFinished(1, generation)
            runCurrent()
            assertTrue(entryHistoryReady(registry.connectionActivity.value, 1))
        }

    @Test
    fun concurrentStartupAndReconcile_createOneObserverSetAndActor() =
        runTest {
            val created = mutableListOf<FakeActor>()
            val registry =
                ConnectionRegistry(
                    backgroundScope,
                    actorFactory = { _, _ -> FakeActor().also(created::add) },
                    isConfigurationFailure = { false },
                )

            val starts = (1..20).map { async { registry.beginStart() } }.awaitAll()
            assertEquals(1, starts.count { it })
            val observer = backgroundScope.launch { awaitCancellation() }
            registry.attachObservers(listOf(observer))
            (1..20)
                .map {
                    async { registry.reconcile(listOf(network() to "fp"), setOf(1), emptySet()) }
                }.awaitAll()

            assertEquals(1, created.size)
            assertEquals(1, created.single().starts)
            assertEquals(1, registry.snapshot.value.observerCount)
            assertEquals(setOf(1L), registry.snapshot.value.actors.keys)
        }

    @Test
    fun configurationChangeReplacesActor_andLateCallbacksAreRejected() =
        runTest {
            val created = mutableListOf<FakeActor>()
            val registry =
                ConnectionRegistry(
                    backgroundScope,
                    actorFactory = { _, _ -> FakeActor().also(created::add) },
                    isConfigurationFailure = { it.startsWith("config:") },
                )
            val row = network()
            registry.beginStart()
            registry.reconcile(listOf(row to "first"), setOf(1), emptySet())
            val firstGeneration =
                registry.snapshot.value.actors
                    .getValue(1)
                    .generation
            registry.actorState(1, firstGeneration, "first", IrcClientState.Ready("me", emptySet(), emptyMap()))
            runCurrent()

            registry.reconcile(listOf(row.copy(host = "changed") to "second"), setOf(1), emptySet())
            val secondGeneration =
                registry.snapshot.value.actors
                    .getValue(1)
                    .generation
            assertTrue(secondGeneration > firstGeneration)
            assertEquals(1, created.first().stops)
            assertEquals(2, created.size)

            registry.actorState(1, firstGeneration, "first", IrcClientState.Failed("stale", fatal = true))
            runCurrent()
            assertTrue(registry.snapshot.value.states[1] is IrcClientState.Ready)

            registry.actorState(1, secondGeneration, "second", IrcClientState.Failed("config: bad", fatal = true))
            runCurrent()
            registry.reconcile(listOf(row to "second"), setOf(1), emptySet())
            assertEquals(2, created.size)
            registry.reconcile(listOf(row.copy(host = "third") to "third"), setOf(1), emptySet())
            assertEquals(3, created.size)

            registry.disconnect(1)
            registry.actorState(1, secondGeneration, "second", IrcClientState.Ready("late", emptySet(), emptyMap()))
            runCurrent()
            assertFalse(
                registry.snapshot.value.actors
                    .containsKey(1),
            )
            assertFalse(
                registry.snapshot.value.states
                    .containsKey(1),
            )
        }

    @Test
    fun explicitConnect_retriesTerminalConfigurationFailureWithSameFingerprint() =
        runTest {
            val created = mutableListOf<FakeActor>()
            val registry =
                ConnectionRegistry(
                    backgroundScope,
                    actorFactory = { _, _ -> FakeActor().also(created::add) },
                    isConfigurationFailure = { it.startsWith("config:") },
                )
            val row = network()
            registry.beginStart()
            registry.reconcile(listOf(row to "fp"), setOf(row.id), emptySet())
            val firstGeneration =
                registry.snapshot.value.actors
                    .getValue(row.id)
                    .generation
            registry.actorState(
                row.id,
                firstGeneration,
                "fp",
                IrcClientState.Failed("config: provider unavailable", fatal = true),
            )
            runCurrent()
            assertEquals(1, registry.snapshot.value.terminalFingerprintCount)

            registry.connect(row, "fp")

            assertEquals(2, created.size)
            assertEquals(1, created.first().stops)
            assertEquals(0, registry.snapshot.value.terminalFingerprintCount)
            assertTrue(
                registry.snapshot.value.actors
                    .getValue(row.id)
                    .generation > firstGeneration,
            )
        }

    @Test
    fun stopAwaitsActorAndJobCleanup_andTimeoutJobsCannotSurvive() =
        runTest {
            val created = mutableListOf<FakeActor>()
            val registry =
                ConnectionRegistry(
                    backgroundScope,
                    actorFactory = { _, _ -> FakeActor().also(created::add) },
                    isConfigurationFailure = { false },
                )
            registry.beginStart()
            registry.reconcile(listOf(network() to "fp"), setOf(1), emptySet())
            val observer = backgroundScope.launch { awaitCancellation() }
            registry.attachObservers(listOf(observer))
            var timedOut = false
            registry.armEchoTimeout("1:label", 30_000) { timedOut = true }
            runCurrent()
            assertEquals(1, registry.snapshot.value.pendingEchoCount)

            registry.stop()
            assertFalse(observer.isActive)
            assertEquals(1, created.single().stops)
            assertEquals(ConnectionRegistrySnapshot(), registry.snapshot.value)
            advanceTimeBy(30_000)
            runCurrent()
            assertFalse(timedOut)
        }

    @Test
    fun disconnectCancelsInFlightCallback_andRejectsLateCallback() =
        runTest {
            val registry =
                ConnectionRegistry(
                    backgroundScope,
                    actorFactory = { _, _ -> FakeActor() },
                    isConfigurationFailure = { false },
                )
            registry.beginStart()
            registry.reconcile(listOf(network() to "fp"), setOf(1), emptySet())
            val generation =
                registry.snapshot.value.actors
                    .getValue(1)
                    .generation
            val entered = CompletableDeferred<Unit>()
            val callback =
                async {
                    registry.runIfCurrent(1, generation) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            entered.await()

            registry.disconnect(1)
            assertFalse(callback.await())
            assertFalse(registry.runIfCurrent(1, generation) { error("late callback ran") })
        }

    @Test
    fun callbackFailureCompletesFalse_withoutEscapingApplicationScope() =
        runTest {
            val registry =
                ConnectionRegistry(
                    backgroundScope,
                    actorFactory = { _, _ -> FakeActor() },
                    isConfigurationFailure = { false },
                )
            registry.beginStart()
            registry.reconcile(listOf(network() to "fp"), setOf(1), emptySet())
            val generation =
                registry.snapshot.value.actors
                    .getValue(1)
                    .generation

            assertFalse(registry.runIfCurrent(1, generation) { error("post-ready setup failed") })
            runCurrent()

            assertEquals(0, registry.snapshot.value.callbackCount)
            assertTrue(
                registry.snapshot.value.actors
                    .containsKey(1),
            )
        }

    @Test
    fun callbackFailure_isRecordedWithoutReachingTheCommandLoop() =
        runTest {
            val diagnostics = RecordingDiagnostics()
            val registry =
                ConnectionRegistry(
                    backgroundScope,
                    actorFactory = { _, _ -> FakeActor() },
                    isConfigurationFailure = { false },
                    diagnostics = diagnostics,
                )
            registry.beginStart()
            registry.reconcile(listOf(network() to "fp"), setOf(1), emptySet())
            val generation =
                registry.snapshot.value.actors
                    .getValue(1)
                    .generation

            assertFalse(registry.runIfCurrent(1, generation) { error("post-ready setup failed") })
            runCurrent()

            val recorded = diagnostics.events.single { it.event == "callback_failed" }
            assertEquals("connections", recorded.component)
            assertEquals(1L, recorded.fields["network_id"])
            assertEquals("IllegalStateException", recorded.fields["error"])
            // The swallow is the callback's own barrier: the command loop never sees the failure.
            assertTrue(diagnostics.events.none { it.event == "command_failed" })
        }

    @Test
    fun commandFailure_isContainedAndLeavesTheLoopServing() =
        runTest {
            val diagnostics = RecordingDiagnostics()
            var explode = true
            val registry =
                ConnectionRegistry(
                    backgroundScope,
                    actorFactory = { _, _ -> if (explode) error("actor construction failed") else FakeActor() },
                    isConfigurationFailure = { false },
                    diagnostics = diagnostics,
                )
            registry.beginStart()

            // Unguarded this never returns: the throw kills the loop, and the commands channel is
            // UNLIMITED and never closed, so this reconcile parks on a deferred nobody completes.
            // Bounded on virtual time so the regression reports as a wedge instead of a suite timeout.
            withTimeout(WEDGE_TIMEOUT_MS) {
                registry.reconcile(listOf(network() to "fp"), setOf(1), emptySet())
            }
            assertTrue(
                registry.snapshot.value.actors
                    .isEmpty(),
            )

            explode = false
            registry.reconcile(listOf(network() to "fp"), setOf(1), emptySet())
            assertTrue(
                registry.snapshot.value.actors
                    .containsKey(1),
            )
            // A second waiting round-trip proves the loop is still draining, not just alive.
            registry.disconnect(1)
            assertTrue(
                registry.snapshot.value.actors
                    .isEmpty(),
            )

            val recorded = diagnostics.events.single { it.event == "command_failed" }
            assertEquals("connections", recorded.component)
            assertEquals("Reconcile", recorded.fields["command"])
            assertEquals("IllegalStateException", recorded.fields["error"])
        }

    @Test
    fun foregroundProbe_targetsReadyActors_andConflatesRepeatedRequests() =
        runTest {
            val created = mutableListOf<FakeActor>()
            val registry =
                ConnectionRegistry(
                    backgroundScope,
                    actorFactory = { _, _ -> FakeActor().also(created::add) },
                    isConfigurationFailure = { false },
                )
            registry.beginStart()
            registry.reconcile(listOf(network() to "fp"), setOf(1), emptySet())
            val generation =
                registry.snapshot.value.actors
                    .getValue(1)
                    .generation
            registry.actorState(1, generation, "fp", IrcClientState.Ready("me", emptySet(), emptyMap()))
            runCurrent()

            registry.probeReady()
            registry.probeReady()
            runCurrent()

            assertEquals(1, created.single().probes)
        }

    /**
     * App-foreground recovery: `reconnectStale` wakes actors that are mid-backoff so a bouncer that
     * came back while the device stayed online is redialled on the next foreground instead of
     * waiting out the remaining exponential delay. The wake must stay narrow so it cannot become a
     * wake-everything reconnect storm: a healthy Ready socket is probed, never redialled, and a
     * dead (fatal/cert-parked) actor is left for reconcile to rebuild.
     */
    @Test
    fun foregroundWake_targetsBackingOffActors_andSparesReadyAndDeadOnes() =
        runTest {
            val created = mutableMapOf<Long, FakeActor>()
            val registry =
                ConnectionRegistry(
                    backgroundScope,
                    actorFactory = { row, _ -> FakeActor().also { created[row.id] = it } },
                    isConfigurationFailure = { false },
                )
            val retrying = network(id = 1)
            val ready = network(id = 2)
            val dead = network(id = 3)
            val rows = listOf(retrying to "fp1", ready to "fp2", dead to "fp3")
            registry.beginStart()
            registry.reconcile(rows, setOf(retrying.id, ready.id, dead.id), emptySet())

            // The backing-off actor publishes Connecting while it serves the retry delay.
            registry.actorState(
                retrying.id,
                registry.snapshot.value.actors
                    .getValue(retrying.id)
                    .generation,
                "fp1",
                IrcClientState.Connecting,
            )
            registry.actorState(
                ready.id,
                registry.snapshot.value.actors
                    .getValue(ready.id)
                    .generation,
                "fp2",
                IrcClientState.Ready("me", emptySet(), emptyMap()),
            )
            // A fatal/cert-parked actor's loop has returned; only reconcile may rebuild it.
            created.getValue(dead.id).isAlive = false
            runCurrent()

            registry.wakeNonReady()
            runCurrent()

            assertEquals(1, created.getValue(retrying.id).wakes)
            assertEquals(0, created.getValue(ready.id).wakes)
            assertEquals(0, created.getValue(dead.id).wakes)
            // Carried as Foreground: this wake is human-paced, so it is the one allowed to reset the
            // actor's backoff escalation outright.
            assertEquals(listOf(WakeCause.Foreground), created.getValue(retrying.id).wakeCauses)
        }

    /**
     * Connectivity wakes every actor, including the Ready ones (their socket may be dead on a
     * network that just changed), and carries the cause that only resets backoff on a real
     * loss→available edge. Losing the distinction is what let a flapping network pin every actor's
     * attempt counter at 0 and redial roughly every 2s forever.
     */
    @Test
    fun connectivityCallbacks_reachEveryActor_taggedAsConnectivity() =
        runTest {
            val created = mutableMapOf<Long, FakeActor>()
            val registry =
                ConnectionRegistry(
                    backgroundScope,
                    actorFactory = { row, _ -> FakeActor().also { created[row.id] = it } },
                    isConfigurationFailure = { false },
                )
            val retrying = network(id = 1)
            val ready = network(id = 2)
            registry.beginStart()
            registry.reconcile(listOf(retrying to "fp1", ready to "fp2"), setOf(retrying.id, ready.id), emptySet())
            registry.actorState(
                ready.id,
                registry.snapshot.value.actors
                    .getValue(ready.id)
                    .generation,
                "fp2",
                IrcClientState.Ready("me", emptySet(), emptyMap()),
            )
            runCurrent()

            registry.networkLost()
            registry.networkAvailable()
            runCurrent()

            listOf(retrying.id, ready.id).forEach { id ->
                assertEquals(1, created.getValue(id).losses)
                assertEquals(listOf(WakeCause.Connectivity), created.getValue(id).wakeCauses)
            }
        }

    @Test
    fun stopCancelsInFlightCallback_andClearsEveryOwnedResource() =
        runTest {
            val registry =
                ConnectionRegistry(
                    backgroundScope,
                    actorFactory = { _, _ -> FakeActor() },
                    isConfigurationFailure = { it.startsWith("config:") },
                )
            registry.beginStart()
            registry.reconcile(listOf(network() to "fp"), setOf(1), emptySet())
            val generation =
                registry.snapshot.value.actors
                    .getValue(1)
                    .generation
            registry.actorState(1, generation, "fp", IrcClientState.Failed("config: bad", fatal = true))
            runCurrent()
            val entered = CompletableDeferred<Unit>()
            val callback =
                async {
                    registry.runIfCurrent(1, generation) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            entered.await()
            assertEquals(1, registry.snapshot.value.callbackCount)
            assertEquals(1, registry.snapshot.value.fingerprintCount)
            assertEquals(1, registry.snapshot.value.terminalFingerprintCount)

            registry.stop()

            assertFalse(callback.await())
            assertEquals(ConnectionRegistrySnapshot(), registry.snapshot.value)
            assertFalse(registry.runIfCurrent(1, generation) { error("callback survived shutdown") })
        }

    @Test
    fun disconnectOrderedAfterReconcile_winsWithoutActorResurrection() =
        runTest {
            val created = mutableListOf<FakeActor>()
            val registry =
                ConnectionRegistry(
                    backgroundScope,
                    actorFactory = { _, _ -> FakeActor().also(created::add) },
                    isConfigurationFailure = { false },
                )
            registry.beginStart()
            registry.reconcile(listOf(network() to "fp"), setOf(1), emptySet())

            val reconcile =
                async {
                    registry.reconcile(listOf(network(host = "changed") to "changed"), setOf(1), emptySet())
                }
            runCurrent()
            val disconnect = async { registry.disconnect(1) }
            reconcile.await()
            disconnect.await()

            assertFalse(
                registry.snapshot.value.actors
                    .containsKey(1),
            )
            assertEquals(2, created.size)
            assertEquals(1, created.last().stops)
        }

    @Test
    fun reconcileRemovingActors_clearsConnectingAndFailedPublishedStates() =
        runTest {
            val registry =
                ConnectionRegistry(
                    backgroundScope,
                    actorFactory = { _, _ -> FakeActor() },
                    isConfigurationFailure = { false },
                )
            val connecting = network(id = 1)
            val accountRequired = network(id = 2)
            val rows = listOf(connecting to "connecting-fp", accountRequired to "failed-fp")
            registry.beginStart()
            registry.reconcile(rows, setOf(connecting.id, accountRequired.id), emptySet())
            registry.actorState(
                connecting.id,
                registry.snapshot.value.actors
                    .getValue(connecting.id)
                    .generation,
                "connecting-fp",
                IrcClientState.Connecting,
            )
            registry.actorState(
                accountRequired.id,
                registry.snapshot.value.actors
                    .getValue(accountRequired.id)
                    .generation,
                "failed-fp",
                IrcClientState.Failed("ACCOUNT_REQUIRED", fatal = true),
            )
            runCurrent()
            assertEquals(setOf(connecting.id, accountRequired.id), registry.connectionStates.value.keys)

            registry.reconcile(rows, emptySet(), emptySet())

            assertTrue(
                registry.snapshot.value.actors
                    .isEmpty(),
            )
            assertTrue(registry.connectionStates.value.isEmpty())
        }

    private companion object {
        /** Virtual-time bound for a request that a wedged command loop would never answer. */
        const val WEDGE_TIMEOUT_MS = 5_000L
    }
}
