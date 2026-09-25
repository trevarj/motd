package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
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
        private val events = Channel<IrcEvent>(Channel.UNLIMITED)
        override val state: StateFlow<IrcClientState> = _state
        override val criticalEvents: ReceiveChannel<IrcEvent> = events
        var startCount = 0
        var stopped = false
        var probeResult = true
        var probeCount = 0
        var probeAwait: CompletableDeferred<Boolean>? = null

        override fun start() {
            startCount++
            stopped = false
            _state.value = IrcClientState.Registering
        }

        override fun stop() {
            stopped = true
            _state.value = IrcClientState.Disconnected
            events.close()
        }

        override suspend fun awaitTermination() = Unit

        suspend fun emit(event: IrcEvent) = events.send(event)

        fun transition(state: IrcClientState) {
            _state.value = state
            if (state is IrcClientState.Disconnected || state is IrcClientState.Failed) events.close()
        }

        var lastProbeGraceMs: Long? = null

        override suspend fun probeLiveness(graceMs: Long): Boolean {
            probeCount++
            lastProbeGraceMs = graceMs
            return probeAwait?.await() ?: probeResult
        }
    }

    private fun eventContext(
        id: String,
        time: Long = 1_000,
    ) = MessageContext(id, time, null, null, null)

    @Test
    fun backoffSequence_exponentialWithCap_and_jitterBounds() {
        val actor =
            ConnectionActor(
                networkId = 1,
                scope = TestScope(),
                connectionFactory = { FakeConnection() },
                onState = { _, _ -> },
                onEvents = { _, _ -> },
                onReady = {},
                random = { 0.0 }, // jitter = 0.7
            )
        // base 2s * 2^attempt * 0.7, capped at 90s * 0.7.
        assertEquals((2000 * 0.7).toLong(), actor.backoffDelayMs(0))
        assertEquals((4000 * 0.7).toLong(), actor.backoffDelayMs(1))
        assertEquals((8000 * 0.7).toLong(), actor.backoffDelayMs(2))
        // Attempt 6 → 128s exceeds 90s cap.
        assertEquals((90_000 * 0.7).toLong(), actor.backoffDelayMs(6))
        assertEquals((90_000 * 0.7).toLong(), actor.backoffDelayMs(20))

        val high =
            ConnectionActor(
                1,
                TestScope(),
                { FakeConnection() },
                { _, _ -> },
                { _, _ -> },
                {},
                random = { 1.0 }, // jitter 1.3
            )
        assertEquals((2000 * 1.3).toLong(), high.backoffDelayMs(0))
        assertEquals((90_000 * 1.3).toLong(), high.backoffDelayMs(30))
    }

    @Test
    fun fatalFailure_stopsActor_noRetry() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val conns = ArrayDeque<FakeConnection>()
            val actor =
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = { FakeConnection().also { conns.addLast(it) } },
                    onState = { _, _ -> },
                    onEvents = { _, _ -> },
                    onReady = {},
                    random = { 0.5 },
                )
            actor.start()
            scope.advanceUntilIdle()
            // First (only) connection reaches a fatal Failed.
            conns.first().transition(IrcClientState.Failed("sasl", fatal = true))
            scope.advanceUntilIdle()
            // No second connection was created (no retry).
            assertEquals(1, conns.size)
            actor.stop()
        }

    @Test
    fun disconnect_retriesAfterBackoff() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val conns = ArrayDeque<FakeConnection>()
            val actor =
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = { FakeConnection().also { conns.addLast(it) } },
                    onState = { _, _ -> },
                    onEvents = { _, _ -> },
                    onReady = {},
                    random = { 0.5 }, // jitter 1.0
                )
            actor.start()
            scope.testScheduler.runCurrent()
            assertEquals(1, conns.size)

            // Non-fatal disconnect → schedules a retry after backoff (attempt 0 → 2000ms @ jitter 1.0).
            conns.first().transition(IrcClientState.Disconnected)
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
    fun normalTerminalConnection_drainsCriticalEventsBeforeReconnect() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val conns = ArrayDeque<FakeConnection>()
            val firstEventStarted = CompletableDeferred<Unit>()
            val releaseFirstEvent = CompletableDeferred<Unit>()
            val processed = mutableListOf<String>()
            val actor =
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = { FakeConnection().also(conns::addLast) },
                    onState = { _, _ -> },
                    onEvents = { _, events ->
                        val code = (events.single() as IrcEvent.ServerError).code
                        if (code == "first") {
                            firstEventStarted.complete(Unit)
                            releaseFirstEvent.await()
                        }
                        processed += code
                    },
                    onReady = {},
                    random = { 0.5 },
                )
            actor.start()
            scope.testScheduler.runCurrent()
            val first = conns.first()
            first.emit(IrcEvent.ServerError("first", emptyList(), ""))
            first.emit(IrcEvent.ServerError("second", emptyList(), ""))
            first.transition(IrcClientState.Disconnected)
            scope.testScheduler.runCurrent()
            firstEventStarted.await()

            assertEquals(1, conns.size)
            assertTrue(processed.isEmpty())

            releaseFirstEvent.complete(Unit)
            scope.testScheduler.runCurrent()

            assertEquals(listOf("first", "second"), processed)
            assertEquals(1, conns.size)
            actor.stop()
        }

    @Test
    fun registeringIsForwardedBetweenConnectingAndReady() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val conn = FakeConnection()
            val states = mutableListOf<IrcClientState>()
            val actor =
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = { conn },
                    onState = { _, state -> states += state },
                    onEvents = { _, _ -> },
                    onReady = {},
                    random = { 0.5 },
                )
            actor.start()
            scope.testScheduler.runCurrent()

            // The fake's start() lands straight in Registering; the loop published Connecting first
            // and must forward the Registering edge (startup step 0/3A: the journal splits dial time
            // from registration time on it, and the manager revives bouncer children on it).
            assertEquals(listOf(IrcClientState.Connecting, IrcClientState.Registering), states)

            val ready = IrcClientState.Ready("motd", emptySet(), emptyMap())
            conn.transition(ready)
            scope.testScheduler.runCurrent()
            assertEquals(ready, states.last())
            actor.stop()
        }

    @Test
    fun retryBackoffPublishesCurrentConnectingStateInsteadOfStaleFailure() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val conn = FakeConnection()
            val states = mutableListOf<IrcClientState>()
            val actor =
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = { conn },
                    onState = { _, state -> states += state },
                    onEvents = { _, _ -> },
                    onReady = {},
                    random = { 0.5 },
                )
            actor.start()
            scope.testScheduler.runCurrent()

            conn.transition(IrcClientState.Failed("SOCKS5 proxy not connected", fatal = false))
            scope.testScheduler.runCurrent()

            assertTrue(states.any { it is IrcClientState.Failed })
            assertEquals(IrcClientState.Connecting, states.last())
            actor.stop()
        }

    @Test
    fun networkAvailable_skipsRemainingBackoff() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val conns = ArrayDeque<FakeConnection>()
            val actor =
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = { FakeConnection().also { conns.addLast(it) } },
                    onState = { _, _ -> },
                    onEvents = { _, _ -> },
                    onReady = {},
                    random = { 1.0 }, // jitter 1.3, longer wait
                )
            actor.start()
            scope.testScheduler.runCurrent()
            conns.first().transition(IrcClientState.Disconnected)
            scope.testScheduler.runCurrent()
            assertEquals(1, conns.size)
            // Fire onNetworkAvailable → wake without waiting the full 2.6s backoff. The woken dial is
            // still spread by the wake stagger (1000ms at this jitter), well inside that backoff.
            actor.onNetworkAvailable(WakeCause.Connectivity)
            scope.testScheduler.runCurrent()
            scope.testScheduler.advanceTimeBy(ConnectionActor.WAKE_STAGGER_MAX_MS)
            scope.testScheduler.runCurrent()
            assertTrue(conns.size >= 2)
            actor.stop()
        }

    /**
     * The stagger itself: one wake releases every actor on the same scheduler tick, so the woken
     * dial is offset by a jittered slice of [ConnectionActor.WAKE_STAGGER_MAX_MS] instead of firing
     * with every sibling at once. Same injected `random` as the backoff jitter, so it is
     * deterministic here and independent per actor in production.
     */
    @Test
    fun wake_staggersTheWokenDialAcrossASmallWindow() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val conns = ArrayDeque<FakeConnection>()
            val actor =
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = { FakeConnection().also { conns.addLast(it) } },
                    onState = { _, _ -> },
                    onEvents = { _, _ -> },
                    onReady = {},
                    random = { 0.5 },
                )
            actor.start()
            scope.testScheduler.runCurrent()
            conns.first().transition(IrcClientState.Disconnected)
            scope.testScheduler.runCurrent()

            actor.onNetworkAvailable(WakeCause.Foreground)
            scope.testScheduler.runCurrent()
            // Half the window at jitter 0.5: the wake cut the backoff short but did not redial yet.
            assertEquals(1, conns.size)
            scope.testScheduler.advanceTimeBy(ConnectionActor.WAKE_STAGGER_MAX_MS / 2 - 1)
            scope.testScheduler.runCurrent()
            assertEquals(1, conns.size)
            scope.testScheduler.advanceTimeBy(1)
            scope.testScheduler.runCurrent()
            assertEquals(2, conns.size)
            actor.stop()

            // The window bounds: a zero draw is a zero offset (the stagger spreads, it never adds a
            // floor) and a full draw never exceeds the window.
            val newActor = { draw: Double ->
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = { FakeConnection() },
                    onState = { _, _ -> },
                    onEvents = { _, _ -> },
                    onReady = {},
                    random = { draw },
                )
            }
            assertEquals(0L, newActor(0.0).wakeStaggerMs())
            assertEquals(ConnectionActor.WAKE_STAGGER_MAX_MS, newActor(1.0).wakeStaggerMs())
        }

    @Test
    fun wakeSignal_resetsBackoffEscalation() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val conns = ArrayDeque<FakeConnection>()
            val actor =
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = { FakeConnection().also { conns.addLast(it) } },
                    onState = { _, _ -> },
                    onEvents = { _, _ -> },
                    onReady = {},
                    random = { 0.5 }, // jitter 1.0
                )
            actor.start()
            scope.testScheduler.runCurrent()
            assertEquals(1, conns.size)

            // Escalate to attempt 2 by serving two full waits (post-Doze shape: fast-failing dials).
            listOf(2_000L, 4_000L).forEach { delayMs ->
                conns.last().transition(IrcClientState.Disconnected)
                scope.testScheduler.runCurrent()
                scope.testScheduler.advanceTimeBy(delayMs)
                scope.testScheduler.runCurrent()
            }
            assertEquals(3, conns.size)

            // The app-foreground wake cuts the 8s wait short (modulo the 500ms stagger at this jitter)...
            conns.last().transition(IrcClientState.Disconnected)
            scope.testScheduler.runCurrent()
            actor.onNetworkAvailable(WakeCause.Foreground)
            scope.testScheduler.runCurrent()
            scope.testScheduler.advanceTimeBy(ConnectionActor.WAKE_STAGGER_MAX_MS / 2)
            scope.testScheduler.runCurrent()
            assertEquals(4, conns.size)

            // ...and resets the escalation: the woken dial's own failure schedules the 2s base wait
            // again instead of continuing to 16s, so the user is not parked behind the old cap.
            conns.last().transition(IrcClientState.Disconnected)
            scope.testScheduler.runCurrent()
            scope.testScheduler.advanceTimeBy(1_999)
            scope.testScheduler.runCurrent()
            assertEquals(4, conns.size)
            scope.testScheduler.advanceTimeBy(1)
            scope.testScheduler.runCurrent()
            assertEquals(5, conns.size)
            actor.stop()
        }

    /**
     * The flapping-network redial storm this splits the wake cause for: Android delivers
     * `onAvailable` repeatedly on an unstable network (Wi-Fi roaming, a captive portal reasserting
     * itself) without a matching `onLost`. Treating each one as "the failures this escalation was
     * built on are stale" pinned the attempt counter at 0, so the actor dialled a dead server every
     * ~2s indefinitely. Without a loss to pair with, connectivity now only skips the remaining wait.
     */
    @Test
    fun repeatedConnectivityWakes_withoutALoss_keepEscalatingTheBackoff() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val conns = ArrayDeque<FakeConnection>()
            val actor =
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = { FakeConnection().also { conns.addLast(it) } },
                    onState = { _, _ -> },
                    onEvents = { _, _ -> },
                    onReady = {},
                    random = { 0.5 }, // jitter 1.0
                )
            actor.start()
            scope.testScheduler.runCurrent()

            // Three failed dials, each one woken early by a bare onAvailable.
            repeat(3) {
                conns.last().transition(IrcClientState.Disconnected)
                scope.testScheduler.runCurrent()
                actor.onNetworkAvailable(WakeCause.Connectivity)
                scope.testScheduler.runCurrent()
                scope.testScheduler.advanceTimeBy(ConnectionActor.WAKE_STAGGER_MAX_MS / 2)
                scope.testScheduler.runCurrent()
            }
            assertEquals(4, conns.size)

            // The escalation survived all three: attempt 3 schedules 16s, not the 2s base.
            conns.last().transition(IrcClientState.Disconnected)
            scope.testScheduler.runCurrent()
            scope.testScheduler.advanceTimeBy(15_999)
            scope.testScheduler.runCurrent()
            assertEquals(4, conns.size)
            scope.testScheduler.advanceTimeBy(1)
            scope.testScheduler.runCurrent()
            assertEquals(5, conns.size)
            actor.stop()
        }

    /**
     * The other half of the connectivity contract: a real loss→available edge is exactly the case
     * where the recorded failures ARE stale (they were dialled against a dead radio), so that wake
     * still resets. The armed loss is consumed once, so the onAvailable duplicates Android emits
     * after it do not each re-arm a reset.
     */
    @Test
    fun connectivityWake_afterALoss_resetsBackoffEscalationOnce() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val conns = ArrayDeque<FakeConnection>()
            val actor =
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = { FakeConnection().also { conns.addLast(it) } },
                    onState = { _, _ -> },
                    onEvents = { _, _ -> },
                    onReady = {},
                    random = { 0.5 }, // jitter 1.0
                )
            actor.start()
            scope.testScheduler.runCurrent()

            // Escalate to attempt 2 by serving two full waits.
            listOf(2_000L, 4_000L).forEach { delayMs ->
                conns.last().transition(IrcClientState.Disconnected)
                scope.testScheduler.runCurrent()
                scope.testScheduler.advanceTimeBy(delayMs)
                scope.testScheduler.runCurrent()
            }
            assertEquals(3, conns.size)

            // The radio dropped and came back: the escalation is reset, so the next failure waits 2s.
            conns.last().transition(IrcClientState.Disconnected)
            scope.testScheduler.runCurrent()
            actor.onNetworkLost()
            actor.onNetworkAvailable(WakeCause.Connectivity)
            scope.testScheduler.runCurrent()
            scope.testScheduler.advanceTimeBy(ConnectionActor.WAKE_STAGGER_MAX_MS / 2)
            scope.testScheduler.runCurrent()
            assertEquals(4, conns.size)

            conns.last().transition(IrcClientState.Disconnected)
            scope.testScheduler.runCurrent()
            scope.testScheduler.advanceTimeBy(1_999)
            scope.testScheduler.runCurrent()
            assertEquals(4, conns.size)
            scope.testScheduler.advanceTimeBy(1)
            scope.testScheduler.runCurrent()
            assertEquals(5, conns.size)

            // The loss was consumed by that first wake, so a second bare onAvailable only skips the
            // wait: the failure after it serves the escalated 8s (attempt 2), not the 2s base again.
            conns.last().transition(IrcClientState.Disconnected)
            scope.testScheduler.runCurrent()
            actor.onNetworkAvailable(WakeCause.Connectivity)
            scope.testScheduler.runCurrent()
            scope.testScheduler.advanceTimeBy(ConnectionActor.WAKE_STAGGER_MAX_MS / 2)
            scope.testScheduler.runCurrent()
            assertEquals(6, conns.size)
            conns.last().transition(IrcClientState.Disconnected)
            scope.testScheduler.runCurrent()
            scope.testScheduler.advanceTimeBy(7_999)
            scope.testScheduler.runCurrent()
            assertEquals(6, conns.size)
            scope.testScheduler.advanceTimeBy(1)
            scope.testScheduler.runCurrent()
            assertEquals(7, conns.size)
            actor.stop()
        }

    /**
     * The protective half of the wake contract: with no wake signal at all, a server that is
     * genuinely down must keep escalating to the 90s cap and must not redial early. Pins the
     * anti-storm behavior that [networkAvailable_skipsRemainingBackoff] deliberately bypasses.
     */
    @Test
    fun sustainedOutage_escalatesToCappedBackoff_andNeverRedialsEarly() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val conns = ArrayDeque<FakeConnection>()
            val actor =
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = { FakeConnection().also { conns.addLast(it) } },
                    onState = { _, _ -> },
                    onEvents = { _, _ -> },
                    onReady = {},
                    random = { 0.5 }, // jitter 1.0, so the delay is exactly the nominal schedule
                )
            actor.start()
            scope.testScheduler.runCurrent()
            assertEquals(1, conns.size)

            // Attempts 0..5 double from the 2s base. Each one is served in full.
            listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L).forEachIndexed { index, delayMs ->
                conns.last().transition(IrcClientState.Disconnected)
                scope.testScheduler.runCurrent()
                assertEquals(index + 1, conns.size)
                scope.testScheduler.advanceTimeBy(delayMs + 1)
                scope.testScheduler.runCurrent()
                assertEquals(index + 2, conns.size)
            }

            // Attempt 6 would be 128s; the cap holds it at 90s and the actor serves all of it.
            val beforeCap = conns.size
            conns.last().transition(IrcClientState.Disconnected)
            scope.testScheduler.runCurrent()
            scope.testScheduler.advanceTimeBy(89_000)
            scope.testScheduler.runCurrent()
            assertEquals(beforeCap, conns.size)
            scope.testScheduler.advanceTimeBy(1_500)
            scope.testScheduler.runCurrent()
            assertEquals(beforeCap + 1, conns.size)
            actor.stop()
        }

    @Test
    fun connectionLeavingReady_cancelsConnectionOwnedSetup() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val conn = FakeConnection()
            var setupStarted = false
            var setupCancelled = false
            val actor =
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = { conn },
                    onState = { _, _ -> },
                    onEvents = { _, _ -> },
                    onReady = {
                        setupStarted = true
                        try {
                            awaitCancellation()
                        } finally {
                            setupCancelled = true
                        }
                    },
                    random = { 0.5 },
                )
            actor.start()
            scope.testScheduler.runCurrent()
            conn.transition(IrcClientState.Ready("motd", emptySet(), emptyMap()))
            scope.testScheduler.runCurrent()
            assertTrue(setupStarted)

            conn.transition(IrcClientState.Disconnected)
            scope.testScheduler.runCurrent()
            assertTrue(setupCancelled)
            actor.stop()
        }

    @Test
    fun readySnapshotChangesArePublishedWithoutRestartingConnectionSetup() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val conn = FakeConnection()
            val states = mutableListOf<IrcClientState>()
            var setupCount = 0
            val actor =
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = { conn },
                    onState = { _, state -> states += state },
                    onEvents = { _, _ -> },
                    onReady = {
                        setupCount++
                        awaitCancellation()
                    },
                    random = { 0.5 },
                )
            actor.start()
            scope.testScheduler.runCurrent()

            conn.transition(IrcClientState.Ready("motd", setOf("batch"), emptyMap()))
            scope.testScheduler.runCurrent()
            conn.transition(
                IrcClientState.Ready(
                    "motd",
                    setOf("batch", "draft/chathistory"),
                    mapOf("CHATHISTORY" to "100"),
                ),
            )
            scope.testScheduler.runCurrent()

            assertEquals(1, setupCount)
            assertEquals(
                listOf(setOf("batch"), setOf("batch", "draft/chathistory")),
                states.filterIsInstance<IrcClientState.Ready>().map { it.caps },
            )
            actor.stop()
        }

    @Test
    fun foregroundProbe_isConflated_andHealthyReadyConnectionIsPreserved() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val conn = FakeConnection()
            val actor =
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = { conn },
                    onState = { _, _ -> },
                    onEvents = { _, _ -> },
                    onReady = {},
                )
            actor.start()
            scope.testScheduler.runCurrent()
            conn.transition(IrcClientState.Ready("motd", emptySet(), emptyMap()))
            scope.testScheduler.runCurrent()

            actor.probe()
            actor.probe()
            scope.testScheduler.runCurrent()

            assertEquals(1, conn.probeCount)
            assertEquals(ConnectionActor.FOREGROUND_PROBE_GRACE_MS, conn.lastProbeGraceMs)
            assertTrue(conn._state.value is IrcClientState.Ready)
            assertTrue(!conn.stopped)
            actor.stop()
        }

    @Test
    fun foregroundProbe_timeoutStopsConnection_andRetries() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val conns = ArrayDeque<FakeConnection>()
            val actor =
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = {
                        FakeConnection().also {
                            if (conns.isNotEmpty()) it.probeResult = true
                            conns.addLast(it)
                        }
                    },
                    onState = { _, _ -> },
                    onEvents = { _, _ -> },
                    onReady = {},
                    random = { 0.5 },
                )
            actor.start()
            scope.testScheduler.runCurrent()
            val first = conns.first()
            first.transition(IrcClientState.Ready("motd", emptySet(), emptyMap()))
            scope.testScheduler.runCurrent()

            first.probeResult = false
            actor.probe()
            scope.testScheduler.runCurrent()

            assertTrue(first.stopped)
            // A failed foreground probe bypasses normal backoff and redials immediately.
            scope.testScheduler.runCurrent()
            assertTrue(conns.size >= 2)
            actor.stop()
        }

    @Test
    fun foregroundProbe_doesNotMaskConnectionFailureDuringGrace() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val conn = FakeConnection()
            val states = mutableListOf<IrcClientState>()
            val actor =
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = { conn },
                    onState = { _, state -> states += state },
                    onEvents = { _, _ -> },
                    onReady = {},
                )
            actor.start()
            scope.testScheduler.runCurrent()
            conn.transition(IrcClientState.Ready("motd", emptySet(), emptyMap()))
            scope.testScheduler.runCurrent()

            conn.probeAwait = CompletableDeferred()
            actor.probe()
            scope.testScheduler.runCurrent()
            conn.transition(IrcClientState.Disconnected)
            scope.testScheduler.runCurrent()

            assertTrue(states.any { it is IrcClientState.Disconnected })
            actor.stop()
        }

    @Test
    fun stopAndJoin_awaitsConnectionOwnedSetupAndCollectorCleanup() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val conn = FakeConnection()
            var setupCancelled = false
            val actor =
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = { conn },
                    onState = { _, _ -> },
                    onEvents = { _, _ -> awaitCancellation() },
                    onReady = {
                        try {
                            awaitCancellation()
                        } finally {
                            setupCancelled = true
                        }
                    },
                )
            actor.start()
            scope.testScheduler.runCurrent()
            conn.transition(IrcClientState.Ready("motd", emptySet(), emptyMap()))
            scope.testScheduler.runCurrent()

            actor.stopAndJoin()

            assertTrue(setupCancelled)
            assertTrue(conn.stopped)
            assertTrue(!actor.isAlive)
        }

    @Test
    fun peerPresenceCapsAt64AndFlushesBeforeOrdinarySelfAndEofWithoutLosingOrder() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val conn = FakeConnection()
            val batches = mutableListOf<List<IrcEvent>>()
            val actor =
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = { conn },
                    onState = { _, _ -> },
                    onEvents = { _, events -> batches += events },
                    onReady = {},
                )
            val peers =
                (0 until 70).map { index ->
                    val context = eventContext("peer-$index", index.toLong())
                    if (index % 2 == 0) {
                        IrcEvent.Joined(context, "peer$index", "#room", null, null, false)
                    } else {
                        IrcEvent.Parted(context, "peer${index - 1}", "#room", null, false)
                    }
                }
            val chat =
                IrcEvent.ChatMessage(
                    eventContext("chat"),
                    IrcEvent.ChatKind.PRIVMSG,
                    Prefix("someone"),
                    "#room",
                    "hello",
                    false,
                    null,
                )
            val self = IrcEvent.Joined(eventContext("self"), "me", "#room", null, null, true)
            val quit = IrcEvent.Quit(eventContext("quit"), "someone", null)
            val afterSelf =
                (70 until 73).map {
                    IrcEvent.Joined(eventContext("$it"), "peer$it", "#room", null, null, false)
                }
            actor.start()
            scope.testScheduler.runCurrent()
            (peers + chat + self + afterSelf + quit).forEach { conn.emit(it) }
            scope.testScheduler.runCurrent()
            assertEquals(listOf(64, 6, 1, 1, 3, 1), batches.map { it.size })
            assertEquals(peers + chat + self + afterSelf + quit, batches.flatten())
            val eofPeer = IrcEvent.Parted(eventContext("eof"), "last", "#room", null, false)
            conn.emit(eofPeer)
            conn.transition(IrcClientState.Disconnected)
            scope.testScheduler.runCurrent()
            assertEquals(listOf(eofPeer), batches.last())
            assertEquals(peers + chat + self + afterSelf + quit + eofPeer, batches.flatten())
            actor.stop()
        }

    @Test
    fun peerPresenceFlushesAfter250msQuietOrFiveSecondsOfContinuousEvents() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val conn = FakeConnection()
            val batches = mutableListOf<List<IrcEvent>>()
            val actor =
                ConnectionActor(
                    networkId = 1,
                    scope = scope,
                    connectionFactory = { conn },
                    onState = { _, _ -> },
                    onEvents = { _, events -> batches += events },
                    onReady = {},
                )
            actor.start()
            scope.testScheduler.runCurrent()
            val isolated = IrcEvent.Joined(eventContext("isolated"), "one", "#room", null, null, false)
            conn.emit(isolated)
            scope.testScheduler.runCurrent()
            scope.testScheduler.advanceTimeBy(249)
            scope.testScheduler.runCurrent()
            assertTrue(batches.isEmpty())
            scope.testScheduler.advanceTimeBy(1)
            scope.testScheduler.runCurrent()
            assertEquals(listOf(listOf(isolated)), batches)

            val storm =
                (0 until 50).map {
                    IrcEvent.Joined(eventContext("storm-$it"), "peer$it", "#room", null, null, false)
                }
            storm.forEachIndexed { index, event ->
                if (index > 0) scope.testScheduler.advanceTimeBy(100)
                conn.emit(event)
                scope.testScheduler.runCurrent()
            }
            scope.testScheduler.advanceTimeBy(99)
            scope.testScheduler.runCurrent()
            assertEquals(listOf(listOf(isolated)), batches)
            scope.testScheduler.advanceTimeBy(1)
            scope.testScheduler.runCurrent()
            assertEquals(listOf(listOf(isolated), storm), batches)
            val afterDeadline = IrcEvent.Parted(eventContext("after-deadline"), "one", "#room", null, false)
            conn.emit(afterDeadline)
            conn.transition(IrcClientState.Disconnected)
            scope.testScheduler.runCurrent()
            assertEquals(listOf(listOf(isolated), storm, listOf(afterDeadline)), batches)
            actor.stop()
        }

    @Test
    fun dozePushHandoffRequiresBackgroundIdleAndUnifiedPush() {
        assertTrue(shouldApplyDozePushHandoff(false, true, DeliveryMode.UNIFIED_PUSH))
        assertTrue(!shouldApplyDozePushHandoff(true, true, DeliveryMode.UNIFIED_PUSH))
        assertTrue(!shouldApplyDozePushHandoff(false, false, DeliveryMode.UNIFIED_PUSH))
        assertTrue(!shouldApplyDozePushHandoff(false, true, DeliveryMode.PERSISTENT_SOCKET))
    }

    @Test
    fun unifiedPushResumesSocketsOnlyOnABackgroundDozeExit() {
        assertTrue(shouldResumeSocketsAfterDozeExit(false, true, false, DeliveryMode.UNIFIED_PUSH))
        assertTrue(!shouldResumeSocketsAfterDozeExit(true, true, false, DeliveryMode.UNIFIED_PUSH))
        assertTrue(!shouldResumeSocketsAfterDozeExit(false, false, false, DeliveryMode.UNIFIED_PUSH))
        assertTrue(!shouldResumeSocketsAfterDozeExit(false, true, true, DeliveryMode.UNIFIED_PUSH))
        assertTrue(!shouldResumeSocketsAfterDozeExit(false, true, false, DeliveryMode.PERSISTENT_SOCKET))
    }

    @Test
    fun persistentSocketArmsTheKeeperOnEveryForegroundWithAWantedNetwork() {
        // The gap this closes: PERSISTENT_SOCKET only ever armed the service from
        // MainActivity.onCreate, so a warm re-entry (notification tap via onNewIntent, or a return
        // after the status notification's Stop) brought connections back with a freezable process.
        assertTrue(
            shouldArmForegroundKeeper(
                deliveryMode = DeliveryMode.PERSISTENT_SOCKET,
                hasWantedEmbeddedReality = false,
                hasWantedNetwork = true,
            ),
        )
        assertTrue(
            !shouldArmForegroundKeeper(
                deliveryMode = DeliveryMode.PERSISTENT_SOCKET,
                hasWantedEmbeddedReality = false,
                hasWantedNetwork = false,
            ),
        )
        // UNIFIED_PUSH is unchanged: only embedded REALITY gets a keeper, and only while wanted.
        assertTrue(
            shouldArmForegroundKeeper(
                deliveryMode = DeliveryMode.UNIFIED_PUSH,
                hasWantedEmbeddedReality = true,
                hasWantedNetwork = true,
            ),
        )
        assertTrue(
            !shouldArmForegroundKeeper(
                deliveryMode = DeliveryMode.UNIFIED_PUSH,
                hasWantedEmbeddedReality = false,
                hasWantedNetwork = true,
            ),
        )
    }
}
