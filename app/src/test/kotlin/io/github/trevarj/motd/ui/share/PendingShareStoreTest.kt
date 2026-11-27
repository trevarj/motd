package io.github.trevarj.motd.ui.share

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingShareStoreTest {
    private val store = PendingShareStore()

    private fun file(id: String) = PendingShare.File(Uri.parse("content://files/$id"), "image/png")

    @Test fun consumeReturnsThePayloadOnlyOnce() {
        store.set(PendingShare.Text("hello"))
        assertEquals(PendingShare.Text("hello"), store.peek())
        assertEquals(PendingShare.Text("hello"), store.consume())
        assertNull(store.peek())
        assertNull(store.consume())
    }

    @Test fun setOverwritesAnUnpickedPayload() {
        store.set(PendingShare.Text("first"))
        store.set(PendingShare.Text("second"))
        assertEquals(PendingShare.Text("second"), store.consume())
    }

    @Test fun assignedAttachmentsAreKeyedPerBufferAndConsumedOnce() {
        val batch = PendingShare.Files(listOf(file("b"), file("a"), file("b")))
        val single = file("c")
        assertTrue(store.assignAttachment(2L, single))
        assertTrue(store.assignAttachment(1L, batch))
        assertNull(store.consumeAttachment(3L))
        val assignedBatch = checkNotNull(store.consumeAttachment(1L))
        assertEquals(batch, assignedBatch.share)
        assertNull(store.consumeAttachment(1L))
        val assignedSingle = checkNotNull(store.consumeAttachment(2L))
        assertEquals(single, assignedSingle.share)
        assertNull(assignedSingle.batchLease)
        checkNotNull(assignedBatch.batchLease).close()
    }

    @Test fun singletonBatchRemainsDistinctFromSingleFileShare() {
        val batch = PendingShare.Files(listOf(file("a")))
        assertTrue(store.assignAttachment(1L, batch))
        val assigned = checkNotNull(store.consumeAttachment(1L))
        assertEquals(batch, assigned.share)
        checkNotNull(assigned.batchLease).close()
    }

    @Test fun stalePickerCannotConsumeANewerShare() {
        val original = PendingShare.Files(listOf(file("a"), file("b")))
        store.set(original)
        val replacement = original.copy()
        store.set(replacement)

        assertFalse(store.consumeIfUnchanged(original))
        assertEquals(replacement, store.peek())
        assertTrue(store.consumeIfUnchanged(replacement))
        assertNull(store.peek())
    }

    @Test fun batchReservationStartsBeforeRoomConsumesAndBlocksAnotherAssignment() {
        val original = PendingShare.Files(listOf(file("a")))
        val next = PendingShare.Files(listOf(file("b")))
        assertTrue(store.assignAttachment(1L, original))
        val assigned = checkNotNull(store.peekAttachment(1L))
        assertTrue(store.hasActiveBatchReview)
        assertFalse(store.assignAttachment(1L, next))
        assertFalse(store.assignAttachment(2L, next))
        assertFalse(store.assignAttachment(1L, file("c")))
        assertSame(original, assigned.share)
        assertSame(assigned, store.consumeAttachment(1L))
        assertFalse(store.assignAttachment(1L, next))
        checkNotNull(assigned.batchLease).close()
        assertTrue(store.assignAttachment(1L, next))
    }

    @Test fun discardingAnAbandonedRouteCannotReleaseItsReplacement() {
        val batch = PendingShare.Files(listOf(file("a")))
        assertTrue(store.assignAttachment(1L, batch))
        val first = checkNotNull(store.peekAttachment(1L))
        assertTrue(store.discardAttachment(1L, first))
        assertFalse(store.hasActiveBatchReview)

        assertTrue(store.assignAttachment(1L, batch))
        val second = checkNotNull(store.peekAttachment(1L))
        assertFalse(store.discardAttachment(1L, first))
        checkNotNull(first.batchLease).close()
        assertSame(second, store.peekAttachment(1L))
        assertTrue(store.hasActiveBatchReview)
        assertTrue(store.discardAttachment(1L, second))
        assertFalse(store.hasActiveBatchReview)
    }

    @Test fun pickerKeepsItsClaimWhenBatchAssignmentCannotBeReserved() {
        val batch = PendingShare.Files(listOf(file("a")))
        val competing = PendingShare.Files(listOf(file("b")))
        assertTrue(store.assignAttachment(1L, batch))
        store.set(competing)
        assertFalse(store.assignPendingAttachment(2L, competing))
        assertSame(competing, store.peek())
    }
}
