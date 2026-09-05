package io.github.trevarj.motd.ui.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-local hand-off for an inbound share: MainActivity parks the payload, the picker assigns
 * it to a buffer, and the destination screen consumes it exactly once. Mirrors the one-shot prefill
 * seam in [io.github.trevarj.motd.ui.chat.ComposerDraftStore]; nothing here survives process death.
 */
@Singleton
class PendingShareStore
    @Inject
    constructor() {
        private val pending = AtomicReference<PendingShare?>(null)
        private val files = ConcurrentHashMap<Long, PendingShare.File>()
        private val agentContexts = ConcurrentHashMap<Long, MutableStateFlow<PendingShare.AgentContext?>>()

        /** Park the newest share; an older un-picked payload is abandoned. */
        fun set(share: PendingShare) {
            pending.set(share)
        }

        fun peek(): PendingShare? = pending.get()

        /** Context preparation must not replace a share whose picker was canceled. */
        fun setIfEmpty(share: PendingShare): Boolean = pending.compareAndSet(null, share)

        fun consumeIfUnchanged(share: PendingShare): Boolean = pending.compareAndSet(share, null)

        fun agentContext(bufferId: Long): StateFlow<PendingShare.AgentContext?> = agentContexts.getOrPut(bufferId) { MutableStateFlow(null) }.asStateFlow()

        /** Never overwrite context already being reviewed at the destination. */
        fun assignAgentContext(
            bufferId: Long,
            share: PendingShare.AgentContext,
        ): Boolean = agentContexts.getOrPut(bufferId) { MutableStateFlow(null) }.compareAndSet(null, share)

        fun updateAgentContext(
            bufferId: Long,
            expected: PendingShare.AgentContext,
            replacement: PendingShare.AgentContext,
        ): Boolean = agentContexts[bufferId]?.compareAndSet(expected, replacement) == true

        fun clearAgentContext(
            bufferId: Long,
            expected: PendingShare.AgentContext,
        ): Boolean = agentContexts[bufferId]?.compareAndSet(expected, null) == true

        /** Return and clear the un-assigned payload. */
        fun consume(): PendingShare? = pending.getAndSet(null)

        /** Route a shared file to [bufferId], where the chat screen opens the upload sheet for it. */
        fun assignFile(
            bufferId: Long,
            file: PendingShare.File,
        ) {
            files[bufferId] = file
        }

        /** Return and clear the file assigned to [bufferId]. */
        fun consumeFile(bufferId: Long): PendingShare.File? = files.remove(bufferId)
    }
