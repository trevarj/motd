package io.github.trevarj.motd.diagnostics

import android.os.SystemClock
import android.util.Log
import io.github.trevarj.motd.BuildConfig
import java.util.concurrent.atomic.AtomicLong

/**
 * Opt-in structured trace for the chat auto-follow investigation.
 *
 * Enable on a debuggable build with:
 * `adb shell setprop log.tag.MotdAutoFollow DEBUG`
 *
 * Callers must pass classification and identity metadata only; message bodies, nicks, network
 * addresses, and credentials never belong in this trace.
 */
internal object AutoFollowTrace {
    const val TAG = "MotdAutoFollow"

    private val sessions = AtomicLong(0)

    fun nextSessionId(): Long = sessions.incrementAndGet()

    inline fun record(
        event: String,
        bufferId: Long? = null,
        sessionId: Long? = null,
        details: () -> String = { "" },
    ) {
        if (!BuildConfig.DEBUG || !Log.isLoggable(TAG, Log.DEBUG)) return
        Log.d(
            TAG,
            formatAutoFollowTrace(
                timestampNanos = SystemClock.elapsedRealtimeNanos(),
                event = event,
                bufferId = bufferId,
                sessionId = sessionId,
                details = details(),
            ),
        )
    }
}

internal fun formatAutoFollowTrace(
    timestampNanos: Long,
    event: String,
    bufferId: Long?,
    sessionId: Long?,
    details: String,
): String = buildString {
    append("t_ns=").append(timestampNanos)
    append(" event=").append(event)
    if (bufferId != null) append(" buffer=").append(bufferId)
    if (sessionId != null) append(" session=").append(sessionId)
    val normalized = details.trim()
    if (normalized.isNotEmpty()) append(' ').append(normalized)
}
