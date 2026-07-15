package io.github.trevarj.motd.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerDraftStoreTest {

    @Test fun consume_returns_pushed_then_null_second_time() {
        val store = ComposerDraftStore()
        store.push(1L, "alice: ")
        assertEquals("alice: ", store.consume(1L))
        assertNull(store.consume(1L))
    }

    @Test fun empty_buffer_consumes_null() {
        assertNull(ComposerDraftStore().consume(42L))
    }

    @Test fun double_push_concatenates() {
        val store = ComposerDraftStore()
        store.push(1L, "alice: ")
        store.push(1L, "bob: ")
        assertEquals("alice: bob: ", store.consume(1L))
    }

    @Test fun buffers_are_isolated() {
        val store = ComposerDraftStore()
        store.push(1L, "alice: ")
        store.push(2L, "bob: ")
        assertEquals("bob: ", store.consume(2L))
        assertEquals("alice: ", store.consume(1L))
        assertNull(store.consume(1L))
        assertNull(store.consume(2L))
    }

    @Test fun drafts_are_saved_and_loaded_without_consuming() {
        val store = ComposerDraftStore()

        store.saveDraft(1L, "hello")

        assertEquals("hello", store.loadDraft(1L))
        assertEquals("hello", store.loadDraft(1L))
        assertNull(store.loadDraft(2L))
    }

    @Test fun blank_draft_is_removed_and_clear_is_idempotent() {
        val store = ComposerDraftStore()
        store.saveDraft(1L, "hello")

        store.saveDraft(1L, "   ")
        assertNull(store.loadDraft(1L))
        store.saveDraft(1L, "again")
        store.clearDraft(1L)
        store.clearDraft(1L)
        assertNull(store.loadDraft(1L))
    }
}
