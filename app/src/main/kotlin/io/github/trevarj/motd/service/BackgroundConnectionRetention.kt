package io.github.trevarj.motd.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
        get() = synchronized(lock) { expiryJob?.isActive == true }

    val graceElapsed: Boolean
        get() = synchronized(lock) {
            backgroundSinceMs?.let { nowMs() - it >= graceMs } == true
        }

    fun onBackgrounded(onElapsed: suspend () -> Unit) {
        val remainingMs = synchronized(lock) {
            val startedAt = backgroundSinceMs ?: nowMs().also { backgroundSinceMs = it }
            if (expiryJob?.isActive == true) return
            (graceMs - (nowMs() - startedAt)).coerceAtLeast(0L)
        }
        val job = scope.launch {
            delay(remainingMs)
            synchronized(lock) { expiryJob = null }
            onElapsed()
        }
        synchronized(lock) { expiryJob = job }
    }

    fun cancel() {
        synchronized(lock) {
            backgroundSinceMs = null
            expiryJob?.cancel()
            expiryJob = null
        }
    }
}
