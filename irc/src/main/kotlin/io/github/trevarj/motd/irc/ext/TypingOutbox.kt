package io.github.trevarj.motd.irc.ext

/**
 * Throttles outbound `+typing` TAGMSGs (plans/02).
 *
 * At most one TAGMSG per (target, state) per [throttleMs]. "done" is always sent immediately
 * and clears the throttle window for that target (so a fresh "active" afterwards goes out at
 * once).
 *
 * Pure decision logic — the client performs the actual send. [shouldSend] mutates the throttle
 * state and returns whether the caller should emit the TAGMSG now.
 */
internal class TypingOutbox(
    private val throttleMs: Long = 3_000L,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    // target -> last send time for the currently-throttled state.
    private data class Window(val state: String, val sentAt: Long)

    private val windows = HashMap<String, Window>()

    fun shouldSend(target: String, state: String): Boolean {
        if (state == "done") {
            // Always send; reset throttle so the next "active" isn't suppressed.
            windows.remove(target)
            return true
        }
        val w = windows[target]
        val t = now()
        if (w != null && w.state == state && t - w.sentAt < throttleMs) {
            return false
        }
        windows[target] = Window(state, t)
        return true
    }
}
