package io.github.trevarj.motd.ui.chat

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Process-lifetime composer state keyed by buffer id. */
@Singleton
class ComposerDraftStore @Inject constructor() {
    private val drafts = ConcurrentHashMap<Long, String>()
    private val prefills = ConcurrentHashMap<Long, String>()

    /** Save the current draft; blank text removes the entry. */
    fun saveDraft(bufferId: Long, text: String) {
        if (text.isBlank()) drafts.remove(bufferId) else drafts[bufferId] = text
    }

    /** Return the latest draft for [bufferId], without consuming it. */
    fun loadDraft(bufferId: Long): String? = drafts[bufferId]

    /** Remove the draft after a successful send. */
    fun clearDraft(bufferId: Long) {
        drafts.remove(bufferId)
    }

    /** Append [text] to any queued prefill for [bufferId] (two mentions queue as "alice: bob: "). */
    fun push(bufferId: Long, text: String) {
        prefills.merge(bufferId, text) { old, new -> old + new }
    }

    /** Return and remove the queued prefill for [bufferId]; null when empty (atomic consume-once). */
    fun consume(bufferId: Long): String? = prefills.remove(bufferId)
}
