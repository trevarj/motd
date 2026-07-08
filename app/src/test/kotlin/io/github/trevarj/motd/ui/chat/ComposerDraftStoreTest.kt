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
}
