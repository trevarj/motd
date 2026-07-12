package io.github.trevarj.motd.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PagingSnapshotTest {
    @Test fun returnsSnapshotItemWhenStable() {
        assertEquals("row", pagingSnapshotItemOrNull(0, 1) { "row" })
    }

    @Test fun returnsNullWhenCountIsAlreadyEmpty() {
        assertNull(pagingSnapshotItemOrNull<String>(0, 0) { error("must not read") })
    }

    @Test fun returnsNullWhenSnapshotEmptiesAfterCountRead() {
        assertNull(pagingSnapshotItemOrNull<String>(0, 1) { throw IndexOutOfBoundsException("snapshot replaced") })
    }
}
