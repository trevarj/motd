package io.github.trevarj.motd.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AutoFollowTraceTest {
    @Test
    fun `trace format is stable and omits absent identifiers`() {
        assertEquals(
            "t_ns=123 event=room_insert kind=PRIVMSG self=false",
            formatAutoFollowTrace(
                timestampNanos = 123,
                event = "room_insert",
                bufferId = null,
                sessionId = null,
                details = "  kind=PRIVMSG self=false  ",
            ),
        )
    }

    @Test
    fun `trace format includes correlation identifiers without content fields`() {
        val line = formatAutoFollowTrace(
            timestampNanos = 456,
            event = "follow_decision",
            bufferId = 9,
            sessionId = 4,
            details = "old_count=10 new_count=11 follow=true",
        )

        assertEquals(
            "t_ns=456 event=follow_decision buffer=9 session=4 " +
                "old_count=10 new_count=11 follow=true",
            line,
        )
        assertFalse(line.contains("text="))
        assertFalse(line.contains("nick="))
    }
}
