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

    @Test fun canceledContextAndDestinationEditsCannotBeOverwrittenByAnotherHandoff() {
        val original = PendingShare.AgentContext(1, "#source", "frozen messages", "partial")
        val replacement = original.copy(originBufferId = 3, sourceLabel = "#other")
        assertTrue(store.setIfEmpty(original))
        assertFalse(store.setIfEmpty(replacement))
        assertEquals(original, store.peek())

        assertTrue(store.assignAgentContext(2, original))
        assertTrue(store.consumeIfUnchanged(original))
        assertFalse(store.assignAgentContext(2, replacement))
        val edited = original.copy(prompt = "reviewed messages")
        assertTrue(store.updateAgentContext(2, original, edited))
        assertFalse(store.clearAgentContext(2, original))
        assertEquals(edited, store.agentContext(2).value)
        assertNull(store.agentContext(3).value)
        assertTrue(store.clearAgentContext(2, edited))
        assertNull(store.agentContext(2).value)
    }
}
