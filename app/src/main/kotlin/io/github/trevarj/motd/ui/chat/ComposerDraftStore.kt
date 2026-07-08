package io.github.trevarj.motd.ui.chat

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-lifetime, memory-only composer draft prefill, consume-once (plans/10, plans/11 §A).
 *
 * ChannelInfo writes via [push] before popping back; ChatScreen reads via [consume] after
 * re-entering composition, so a plain map pull is race-free (no SharedFlow needed).
 */
@Singleton
class ComposerDraftStore @Inject constructor() {
    private val drafts = ConcurrentHashMap<Long, String>()

    /** Append [text] to any queued prefill for [bufferId] (two mentions queue as "alice: bob: "). */
    fun push(bufferId: Long, text: String) {
        drafts.merge(bufferId, text) { old, new -> old + new }
    }

    /** Return and remove the queued prefill for [bufferId]; null when empty (atomic consume-once). */
    fun consume(bufferId: Long): String? = drafts.remove(bufferId)
}
