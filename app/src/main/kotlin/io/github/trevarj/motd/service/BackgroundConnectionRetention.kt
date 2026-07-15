package io.github.trevarj.motd.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Cancellable process-background grace timer.
 *
 * The first background signal owns the deadline; repeated lifecycle/Doze callbacks cannot extend
 * it. Foregrounding or leaving the eligible delivery mode cancels both the deadline and its
 * callback, so an obsolete hand-off cannot tear down a newly resumed connection.
 */
internal class BackgroundConnectionRetention(
    private val scope: CoroutineScope,
    private val graceMs: Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val lock = Any()
    private var backgroundSinceMs: Long? = null
    private var expiryJob: Job? = null

    val isRetaining: Boolean
        get() = synchronized(lock) { expiryJob != null }

    val graceElapsed: Boolean
        get() = synchronized(lock) {
            backgroundSinceMs?.let { nowMs() - it >= graceMs } == true
        }

    fun onBackgrounded(onElapsed: suspend () -> Unit) {
        val job = synchronized(lock) {
            val startedAt = backgroundSinceMs ?: nowMs().also { backgroundSinceMs = it }
            if (expiryJob != null) return
            val remainingMs = (graceMs - (nowMs() - startedAt)).coerceAtLeast(0L)
            scope.launch(start = CoroutineStart.LAZY) {
                delay(remainingMs)
                val runningJob = currentCoroutineContext().job
                try {
                    onElapsed()
                } finally {
                    synchronized(lock) {
                        if (expiryJob === runningJob) expiryJob = null
                    }
                }
            }.also { expiryJob = it }
        }
        job.start()
    }

    fun cancel() {
        synchronized(lock) {
            backgroundSinceMs = null
            expiryJob?.cancel()
            expiryJob = null
        }
    }
}
