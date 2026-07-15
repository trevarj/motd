package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

        override fun start() {
            starts++
            isAlive = true
        }

        override fun stop() {
            stops++
            isAlive = false
        }

        override suspend fun stopAndJoin() = stop()
        override fun onNetworkAvailable() = Unit
        override fun onNetworkLost() = Unit
        override fun probe() { probes++ }
    }

    private fun network(id: Long = 1, host: String = "irc.example") = NetworkEntity(
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
    fun concurrentStartupAndReconcile_createOneObserverSetAndActor() = runTest {
        val created = mutableListOf<FakeActor>()
        val registry = ConnectionRegistry(
            backgroundScope,
            actorFactory = { _, _ -> FakeActor().also(created::add) },
            isConfigurationFailure = { false },
        )

        val starts = (1..20).map { async { registry.beginStart() } }.awaitAll()
        assertEquals(1, starts.count { it })
        val observer = backgroundScope.launch { awaitCancellation() }
        registry.attachObservers(listOf(observer))
        (1..20).map {
            async { registry.reconcile(listOf(network() to "fp"), setOf(1), emptySet()) }
        }.awaitAll()

        assertEquals(1, created.size)
        assertEquals(1, created.single().starts)
        assertEquals(1, registry.snapshot.value.observerCount)
        assertEquals(setOf(1L), registry.snapshot.value.actors.keys)
    }

    @Test
    fun configurationChangeReplacesActor_andLateCallbacksAreRejected() = runTest {
        val created = mutableListOf<FakeActor>()
        val registry = ConnectionRegistry(
            backgroundScope,
            actorFactory = { _, _ -> FakeActor().also(created::add) },
            isConfigurationFailure = { it.startsWith("config:") },
        )
        val row = network()
        registry.beginStart()
        registry.reconcile(listOf(row to "first"), setOf(1), emptySet())
        val firstGeneration = registry.snapshot.value.actors.getValue(1).generation
        registry.actorState(1, firstGeneration, "first", IrcClientState.Ready("me", emptySet(), emptyMap()))
        runCurrent()

        registry.reconcile(listOf(row.copy(host = "changed") to "second"), setOf(1), emptySet())
        val secondGeneration = registry.snapshot.value.actors.getValue(1).generation
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
        assertFalse(registry.snapshot.value.actors.containsKey(1))
        assertFalse(registry.snapshot.value.states.containsKey(1))
    }

    @Test
    fun stopAwaitsActorAndJobCleanup_andTimeoutJobsCannotSurvive() = runTest {
        val created = mutableListOf<FakeActor>()
        val registry = ConnectionRegistry(
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
    fun disconnectCancelsInFlightCallback_andRejectsLateCallback() = runTest {
        val registry = ConnectionRegistry(
            backgroundScope,
            actorFactory = { _, _ -> FakeActor() },
            isConfigurationFailure = { false },
        )
        registry.beginStart()
        registry.reconcile(listOf(network() to "fp"), setOf(1), emptySet())
        val generation = registry.snapshot.value.actors.getValue(1).generation
        val entered = CompletableDeferred<Unit>()
        val callback = async {
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
    fun foregroundProbe_targetsReadyActors_andConflatesRepeatedRequests() = runTest {
        val created = mutableListOf<FakeActor>()
        val registry = ConnectionRegistry(
            backgroundScope,
            actorFactory = { _, _ -> FakeActor().also(created::add) },
            isConfigurationFailure = { false },
        )
        registry.beginStart()
        registry.reconcile(listOf(network() to "fp"), setOf(1), emptySet())
        val generation = registry.snapshot.value.actors.getValue(1).generation
        registry.actorState(1, generation, "fp", IrcClientState.Ready("me", emptySet(), emptyMap()))
        runCurrent()

        registry.probeReady()
        registry.probeReady()
        runCurrent()

        assertEquals(1, created.single().probes)
    }

    @Test
    fun stopCancelsInFlightCallback_andClearsEveryOwnedResource() = runTest {
        val registry = ConnectionRegistry(
            backgroundScope,
            actorFactory = { _, _ -> FakeActor() },
            isConfigurationFailure = { it.startsWith("config:") },
        )
        registry.beginStart()
        registry.reconcile(listOf(network() to "fp"), setOf(1), emptySet())
        val generation = registry.snapshot.value.actors.getValue(1).generation
        registry.actorState(1, generation, "fp", IrcClientState.Failed("config: bad", fatal = true))
        runCurrent()
        val entered = CompletableDeferred<Unit>()
        val callback = async {
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
    fun disconnectOrderedAfterReconcile_winsWithoutActorResurrection() = runTest {
        val created = mutableListOf<FakeActor>()
        val registry = ConnectionRegistry(
            backgroundScope,
            actorFactory = { _, _ -> FakeActor().also(created::add) },
            isConfigurationFailure = { false },
        )
        registry.beginStart()
        registry.reconcile(listOf(network() to "fp"), setOf(1), emptySet())

        val reconcile = async {
            registry.reconcile(listOf(network(host = "changed") to "changed"), setOf(1), emptySet())
        }
        runCurrent()
        val disconnect = async { registry.disconnect(1) }
        reconcile.await()
        disconnect.await()

        assertFalse(registry.snapshot.value.actors.containsKey(1))
        assertEquals(2, created.size)
        assertEquals(1, created.last().stops)
    }
}
