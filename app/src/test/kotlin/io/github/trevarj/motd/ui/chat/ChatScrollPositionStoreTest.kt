package io.github.trevarj.motd.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatScrollPositionStoreTest {
    @Test
    fun `stores last position per buffer`() {
        val store = ChatScrollPositionStore()
        val first = ChatScrollPosition(index = 4, offset = 12, msgid = "a", serverTime = 100, rowId = 1)
        val second = ChatScrollPosition(index = 0, offset = 0, msgid = "b", serverTime = 200, rowId = 2)

        store.put(10, first)
        store.put(11, second)

        assertEquals(first, store.get(10))
        assertEquals(second, store.get(11))
        assertNull(store.get(12))
    }
}
