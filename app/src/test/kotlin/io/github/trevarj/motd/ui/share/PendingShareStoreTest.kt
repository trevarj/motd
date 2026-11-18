package io.github.trevarj.motd.ui.share

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun assignedFilesAreKeyedPerBufferAndConsumedOnce() {
        store.assignFile(1L, file("a"))
        store.assignFile(2L, file("b"))
        assertNull(store.consumeFile(3L))
        assertEquals(file("a"), store.consumeFile(1L))
        assertNull(store.consumeFile(1L))
        // Consuming one buffer's file leaves the others untouched.
        assertEquals(file("b"), store.consumeFile(2L))
    }

    @Test fun stalePickerCannotConsumeANewerShare() {
        val original = PendingShare.Text("first")
        store.set(original)
        val replacement = PendingShare.Text("second")
        store.set(replacement)

        assertFalse(store.consumeIfUnchanged(original))
        assertEquals(replacement, store.peek())
        assertTrue(store.consumeIfUnchanged(replacement))
        assertNull(store.peek())
    }
}
