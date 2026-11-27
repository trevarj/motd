package io.github.trevarj.motd.ui.share

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

class AssignedAttachment(
    val share: PendingShare.Attachment,
    val batchLease: AutoCloseable?,
)

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
        private val attachmentLock = Any()
        private val attachmentsByBuffer = mutableMapOf<Long, AssignedAttachment>()
        private var batchReview: BatchReviewLease? = null

        val hasActiveBatchReview: Boolean
            get() = synchronized(attachmentLock) { batchReview != null }

        private inner class BatchReviewLease : AutoCloseable {
            override fun close() {
                synchronized(attachmentLock) {
                    if (batchReview === this) batchReview = null
                }
            }
        }

        /** Park the newest share; an older un-picked payload is abandoned. */
        fun set(share: PendingShare) {
            pending.set(share)
        }

        fun peek(): PendingShare? = pending.get()

        fun consumeIfUnchanged(share: PendingShare): Boolean = pending.compareAndSet(share, null)

        /** Return and clear the un-assigned payload. */
        fun consume(): PendingShare? = pending.getAndSet(null)

        /** Reserve the batch review as part of assigning it, before the chat can consume it. */
        fun assignAttachment(
            bufferId: Long,
            share: PendingShare.Attachment,
        ): Boolean = assignAttachment(bufferId, share, claimPending = false)

        /** Assign only the exact share still owned by this picker. A failed assignment leaves it pending. */
        internal fun assignPendingAttachment(
            bufferId: Long,
            share: PendingShare.Attachment,
        ): Boolean = assignAttachment(bufferId, share, claimPending = true)

        private fun assignAttachment(
            bufferId: Long,
            share: PendingShare.Attachment,
            claimPending: Boolean,
        ): Boolean =
            synchronized(attachmentLock) {
                if (batchReview != null || bufferId in attachmentsByBuffer) return@synchronized false
                if (claimPending && !consumeIfUnchanged(share)) return@synchronized false
                val lease = if (share is PendingShare.Files) BatchReviewLease() else null
                attachmentsByBuffer[bufferId] = AssignedAttachment(share, lease)
                batchReview = lease
                true
            }

        /** Transfer one assignment and any batch lease to its destination. */
        fun consumeAttachment(bufferId: Long): AssignedAttachment? = synchronized(attachmentLock) { attachmentsByBuffer.remove(bufferId) }

        fun peekAttachment(bufferId: Long): AssignedAttachment? = synchronized(attachmentLock) { attachmentsByBuffer[bufferId] }

        /** Remove only the claim owned by the abandoned route. */
        fun discardAttachment(
            bufferId: Long,
            expected: AssignedAttachment,
        ): Boolean =
            synchronized(attachmentLock) {
                if (attachmentsByBuffer[bufferId] !== expected) return@synchronized false
                attachmentsByBuffer.remove(bufferId)
                expected.batchLease?.close()
                true
            }
    }
