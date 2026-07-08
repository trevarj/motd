package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.service.TypingTracker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory typing state (not persisted). Written by [EventProcessor] on TAGMSG(+typing),
 * read by ChatViewModel (WP7). A nick is added on "active"/"paused" and removed on "done"
 * (or when it stops being reported); each `active` also refreshes an expiry so stale typers
 * age out even without an explicit "done".
 */
@Singleton
class TypingTrackerImpl @Inject constructor() : TypingTracker {
    private data class Entry(val nick: String, val expiresAt: Long)

    private val flows = HashMap<Long, MutableStateFlow<List<String>>>()
    private val entries = HashMap<Long, MutableList<Entry>>()
    private val lock = Any()

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
        }
    }

    private fun flowFor(bufferId: Long): MutableStateFlow<List<String>> =
        flows.getOrPut(bufferId) { MutableStateFlow(emptyList()) }

    private companion object {
        const val ACTIVE_TTL_MS = 6_000L
    }
}
