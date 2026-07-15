package io.github.trevarj.motd.irc.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Liveness watchdog (plans/02).
 *
 * After [idleBeforePing] of inbound silence the client sends `PING motd-<epoch>`; if no line at
 * all arrives within a further [pingGrace], the connection is considered dead and [onDead] fires
 * (the client then stops the transport).
 *
 * Timing is purely [delay]-driven so it works under coroutines-test virtual time: every inbound
 * line bumps a generation counter, and the timer loop restarts whenever it observes a bump.
 */
internal class PingWatchdog(
    private val scope: CoroutineScope,
    private val sendPing: suspend (payload: String) -> Unit,
    private val onDead: suspend () -> Unit,
    private val idleBeforePing: Long = 90_000L,
    private val pingGrace: Long = 30_000L,
) {
    private val generation = AtomicLong(0)
    private val probeMutex = Mutex()
    private val dead = AtomicBoolean(false)
    private var job: Job? = null

    fun start() {
        stop()
        dead.set(false)
        job = scope.launch {
            while (isActive) {
                val genAtStart = generation.get()
                delay(idleBeforePing)
                if (generation.get() != genAtStart) continue // inbound arrived; restart idle window.

                // Idle threshold reached: probe.
                if (!probe(pingGrace)) return@launch
            }
        }
    }

    fun onInbound() {
        generation.incrementAndGet()
    }

    /**
     * Send an immediate liveness probe and wait for the same grace period used by the idle
     * watchdog. Any inbound IRC line (including the PONG to this probe) proves that the transport
     * is still alive. [graceMs] lets foreground recovery use a shorter UX-oriented deadline while
     * the periodic watchdog retains its conservative [pingGrace]. Probes are serialized so the
     * periodic watchdog and a foreground probe cannot race or produce duplicate teardown callbacks.
     *
     * Returns true when inbound traffic arrived during the grace period. A timed-out probe invokes
     * [onDead] exactly once for this watchdog run, just like the periodic watchdog path.
    */
    suspend fun probe(graceMs: Long = pingGrace): Boolean = probeMutex.withLock {
        require(graceMs > 0) { "probe grace must be positive" }
        runCatching { sendPing("motd-${System.currentTimeMillis()}") }
        // Match the periodic watchdog: traffic that arrived before the probe was written does
        // not satisfy this probe's grace window.
        val genAtPing = generation.get()
        delay(graceMs)
        if (generation.get() != genAtPing) return@withLock true

        if (dead.compareAndSet(false, true)) {
            // No inbound line during the grace window -> dead.
            onDead()
        }
        false
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
