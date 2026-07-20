package io.github.trevarj.motd.irc.ext

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadMarkerTest {
    @Test
    fun `set preserves required millisecond precision for exact seconds`() {
        assertEquals(
            "MARKREAD alice timestamp=1970-01-01T00:00:01.000Z",
            ReadMarkerCommands.set("alice", 1_000).serialize(),
        )
    }

    @Test
    fun `set preserves nonzero milliseconds`() {
        assertEquals(
            "MARKREAD alice timestamp=1970-01-01T00:00:01.234Z",
            ReadMarkerCommands.set("alice", 1_234).serialize(),
        )
    }
}
