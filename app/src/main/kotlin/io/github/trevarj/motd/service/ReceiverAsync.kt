package io.github.trevarj.motd.service

import android.content.BroadcastReceiver
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val RECEIVER_TIMEOUT_MS = 9_000L

/**
 * Run ordinary receiver work within Android's short broadcast lifetime. The push connector keeps
 * its separate callback bridge because its library-owned completion contract extends beyond a
 * normal [BroadcastReceiver.PendingResult].
 */
internal fun BroadcastReceiver.launchAsync(
    scope: CoroutineScope,
    tag: String,
    timeoutMs: Long = RECEIVER_TIMEOUT_MS,
    block: suspend () -> Unit,
) {
    val pending = goAsync()
    scope.launch {
        runBoundedReceiverWork(
            timeoutMs = timeoutMs,
            onTimeout = { Log.w(tag, "Broadcast work timed out after $timeoutMs ms") },
            onFailure = { error -> Log.w(tag, "Broadcast work failed", error) },
            finish = pending::finish,
            block = block,
        )
    }
}

internal suspend fun runBoundedReceiverWork(
    timeoutMs: Long,
    onTimeout: () -> Unit,
    onFailure: (Throwable) -> Unit,
    finish: () -> Unit,
    block: suspend () -> Unit,
) {
    try {
        withTimeout(timeoutMs) { block() }
    } catch (_: TimeoutCancellationException) {
        onTimeout()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFailure(error)
    } finally {
        finish()
    }
}
