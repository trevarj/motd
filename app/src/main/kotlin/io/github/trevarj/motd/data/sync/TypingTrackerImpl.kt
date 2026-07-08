package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.service.TypingTracker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * In-memory typing state (not persisted). Written by [EventProcessor] on TAGMSG(+typing),
 * read by ChatViewModel (WP7). A nick is added on "active"/"paused" and removed on "done"
 * (or when it stops being reported); each `active` also refreshes an expiry so stale typers
 * age out even without an explicit "done".
 *
 * A lost "done" would otherwise leave "alice is typing…" stuck forever, because expiry was only
 * re-evaluated when *another* typing event for the same buffer arrived. To fix that, each add
 * schedules a sweep at the entry's `expiresAt` (via [scope]) that removes expired entries and
 * re-emits, so the indicator clears on its own.
 */
@Singleton
class TypingTrackerImpl @Inject constructor() : TypingTracker {
    private data class Entry(val nick: String, val expiresAt: Long)

    private val flows = HashMap<Long, MutableStateFlow<List<String>>>()
    private val entries = HashMap<Long, MutableList<Entry>>()
    private val lock = Any()

    // Own scope so expiry sweeps outlive any single caller; sweeps are cheap and short-lived.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // At most one pending sweep per buffer (the soonest expiry); replaced when a new one is sooner.
    private val sweeps = HashMap<Long, Job>()

    override fun typingNicks(bufferId: Long): StateFlow<List<String>> =
        synchronized(lock) { flowFor(bufferId).asStateFlow() }

    /** Apply a typing state ("active" | "paused" | "done") for [nick] in [bufferId]. */
    fun onTyping(bufferId: Long, nick: String, state: String, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val list = entries.getOrPut(bufferId) { mutableListOf() }
            list.removeAll { it.nick == nick || it.expiresAt <= now }
            if (state == "active" || state == "paused") {
                list.add(Entry(nick, now + ACTIVE_TTL_MS))
            }
            flowFor(bufferId).value = list.map { it.nick }
            scheduleSweep(bufferId, list, now)
        }
    }

    /** (Re)arm the sweep for [bufferId] to fire at the soonest remaining expiry. */
    private fun scheduleSweep(bufferId: Long, list: List<Entry>, now: Long) {
        sweeps.remove(bufferId)?.cancel()
        val next = list.minOfOrNull { it.expiresAt } ?: return
        val delayMs = (next - now).coerceAtLeast(0)
        sweeps[bufferId] = scope.launch {
            delay(delayMs)
            sweep(bufferId)
        }
    }

    /** Drop expired entries for [bufferId], re-emit, and re-arm for the next expiry if any. */
    private fun sweep(bufferId: Long) {
        synchronized(lock) {
            val list = entries[bufferId] ?: return
            val now = System.currentTimeMillis()
            val before = list.size
            list.removeAll { it.expiresAt <= now }
            if (list.size != before) flowFor(bufferId).value = list.map { it.nick }
            sweeps.remove(bufferId)
            if (list.isNotEmpty()) scheduleSweep(bufferId, list, now)
        }
    }

    private fun flowFor(bufferId: Long): MutableStateFlow<List<String>> =
        flows.getOrPut(bufferId) { MutableStateFlow(emptyList()) }

    private companion object {
        const val ACTIVE_TTL_MS = 6_000L
    }
}
