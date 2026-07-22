package io.github.trevarj.motd.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineMessageTagTest {
    @Test
    fun `server msgid is the timeline selector once present`() {
        assertEquals("chat_message_msgid-123", timelineMessageTag("msgid-123", 42L))
    }

    @Test
    fun `pending row falls back to canonical event id`() {
        assertEquals("chat_message_42", timelineMessageTag(null, 42L))
    }
}
