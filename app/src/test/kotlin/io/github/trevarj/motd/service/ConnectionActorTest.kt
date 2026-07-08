package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionActorTest {

    /** Fake connection whose state the test drives explicitly. */
    private class FakeConnection : ManagedConnection {
        val _state = MutableStateFlow<IrcClientState>(IrcClientState.Disconnected)
        val _events = MutableSharedFlow<IrcEvent>(extraBufferCapacity = 64)
        override val state: StateFlow<IrcClientState> = _state
        override val events: SharedFlow<IrcEvent> = _events
        var startCount = 0
        var stopped = false
        override fun start() { startCount++; stopped = false; _state.value = IrcClientState.Registering }
        override fun stop() { stopped = true }
    }

    @Test
    fun backoffSequence_exponentialWithCap_and_jitterBounds() {
        val actor = ConnectionActor(
            networkId = 1, scope = TestScope(),
            connectionFactory = { FakeConnection() },
            onState = { _, _ -> }, onEvent = { _, _ -> }, onReady = {},
            random = { 0.0 }, // jitter = 0.7
        )
        // base 2s * 2^attempt * 0.7, capped at 90s * 0.7.
        assertEquals((2000 * 0.7).toLong(), actor.backoffDelayMs(0))
        assertEquals((4000 * 0.7).toLong(), actor.backoffDelayMs(1))
        assertEquals((8000 * 0.7).toLong(), actor.backoffDelayMs(2))
        // Attempt 6 → 128s exceeds 90s cap.
        assertEquals((90_000 * 0.7).toLong(), actor.backoffDelayMs(6))
        assertEquals((90_000 * 0.7).toLong(), actor.backoffDelayMs(20))

        val high = ConnectionActor(
            1, TestScope(), { FakeConnection() }, { _, _ -> }, { _, _ -> }, {}, random = { 1.0 }, // jitter 1.3
        )
        assertEquals((2000 * 1.3).toLong(), high.backoffDelayMs(0))
        assertEquals((90_000 * 1.3).toLong(), high.backoffDelayMs(30))
    }

    @Test
    fun fatalFailure_stopsActor_noRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val conns = ArrayDeque<FakeConnection>()
        val actor = ConnectionActor(
            networkId = 1, scope = scope,
            connectionFactory = { FakeConnection().also { conns.addLast(it) } },
            onState = { _, _ -> }, onEvent = { _, _ -> }, onReady = {}, random = { 0.5 },
        )
        actor.start()
        scope.advanceUntilIdle()
        // First (only) connection reaches a fatal Failed.
        conns.first()._state.value = IrcClientState.Failed("sasl", fatal = true)
        scope.advanceUntilIdle()
        // No second connection was created (no retry).
        assertEquals(1, conns.size)
        actor.stop()
    }

    @Test
    fun disconnect_retriesAfterBackoff() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val conns = ArrayDeque<FakeConnection>()
        val actor = ConnectionActor(
            networkId = 1, scope = scope,
            connectionFactory = { FakeConnection().also { conns.addLast(it) } },
            onState = { _, _ -> }, onEvent = { _, _ -> }, onReady = {}, random = { 0.5 }, // jitter 1.0
        )
        actor.start()
        scope.testScheduler.runCurrent()
        assertEquals(1, conns.size)

        // Non-fatal disconnect → schedules a retry after backoff (attempt 0 → 2000ms @ jitter 1.0).
        conns.first()._state.value = IrcClientState.Disconnected
        scope.testScheduler.runCurrent() // process the disconnect, enter backoff wait
        // Before the delay elapses, still only one connection.
        scope.testScheduler.advanceTimeBy(1_000)
        scope.testScheduler.runCurrent()
        assertEquals(1, conns.size)
        // After the full backoff, a fresh connection is created.
        scope.testScheduler.advanceTimeBy(1_500)
        scope.testScheduler.runCurrent()
        assertTrue(conns.size >= 2)
        actor.stop()
    }

    @Test
    fun networkAvailable_skipsRemainingBackoff() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val conns = ArrayDeque<FakeConnection>()
        val actor = ConnectionActor(
            networkId = 1, scope = scope,
            connectionFactory = { FakeConnection().also { conns.addLast(it) } },
            onState = { _, _ -> }, onEvent = { _, _ -> }, onReady = {}, random = { 1.0 }, // jitter 1.3, longer wait
        )
        actor.start()
        scope.testScheduler.runCurrent()
        conns.first()._state.value = IrcClientState.Disconnected
        scope.testScheduler.runCurrent()
        assertEquals(1, conns.size)
        // Fire onNetworkAvailable → wake immediately without waiting the full backoff.
        actor.onNetworkAvailable()
        scope.testScheduler.runCurrent()
        assertTrue(conns.size >= 2)
        actor.stop()
    }
}
