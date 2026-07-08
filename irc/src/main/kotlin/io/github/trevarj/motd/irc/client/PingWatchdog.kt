package io.github.trevarj.motd.irc.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private var job: Job? = null

    fun start() {
        stop()
        job = scope.launch {
            while (isActive) {
                val genAtStart = generation.get()
                delay(idleBeforePing)
                if (generation.get() != genAtStart) continue // inbound arrived; restart idle window.

                // Idle threshold reached: probe.
                sendPing("motd-${System.currentTimeMillis()}")
                val genAtPing = generation.get()
                delay(pingGrace)
                if (generation.get() == genAtPing) {
                    // No inbound line during the grace window -> dead.
                    onDead()
                    return@launch
                }
            }
        }
    }

    fun onInbound() {
        generation.incrementAndGet()
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
