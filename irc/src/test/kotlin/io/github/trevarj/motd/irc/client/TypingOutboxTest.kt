package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.ext.TypingOutbox
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingOutboxTest {

    @Test
    fun `active is throttled to one per window per target`() {
        var clock = 0L
        val outbox = TypingOutbox(throttleMs = 3_000L, now = { clock })

        assertTrue("first active sends", outbox.shouldSend("#a", "active"))
        clock = 1_000
        assertFalse("within window suppressed", outbox.shouldSend("#a", "active"))
        clock = 3_001
        assertTrue("after window sends again", outbox.shouldSend("#a", "active"))
    }

    @Test
    fun `distinct targets have independent windows`() {
        var clock = 0L
        val outbox = TypingOutbox(now = { clock })
        assertTrue(outbox.shouldSend("#a", "active"))
        assertTrue(outbox.shouldSend("#b", "active"))
    }

    @Test
    fun `done always sends immediately and clears throttle`() {
        var clock = 0L
        val outbox = TypingOutbox(throttleMs = 3_000L, now = { clock })
        assertTrue(outbox.shouldSend("#a", "active"))
        clock = 500
        // done is not throttled.
        assertTrue(outbox.shouldSend("#a", "done"))
        // A fresh active right after done is not suppressed (window cleared).
        clock = 600
        assertTrue(outbox.shouldSend("#a", "active"))
    }
}
