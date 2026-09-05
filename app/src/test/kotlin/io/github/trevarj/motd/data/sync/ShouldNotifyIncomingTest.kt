package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.data.db.BufferType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShouldNotifyIncomingTest {
    @Test
    fun `query always notifies`() {
        assertTrue(shouldNotifyIncoming(BufferType.QUERY, hasMention = false, consoleNotice = false, watchedChat = false))
    }

    @Test
    fun `channel mention notifies`() {
        assertTrue(shouldNotifyIncoming(BufferType.CHANNEL, hasMention = true, consoleNotice = false, watchedChat = false))
    }

    @Test
    fun `channel without mention stays silent`() {
        assertFalse(shouldNotifyIncoming(BufferType.CHANNEL, hasMention = false, consoleNotice = false, watchedChat = false))
    }

    @Test
    fun `watched channel chat notifies without a mention`() {
        assertTrue(shouldNotifyIncoming(BufferType.CHANNEL, hasMention = false, consoleNotice = false, watchedChat = true))
    }

    @Test
    fun `console notice notifies`() {
        assertTrue(shouldNotifyIncoming(BufferType.SERVER, hasMention = false, consoleNotice = true, watchedChat = false))
    }

    @Test
    fun `server buffer without console notice stays silent even when watched`() {
        assertFalse(shouldNotifyIncoming(BufferType.SERVER, hasMention = true, consoleNotice = false, watchedChat = true))
    }
}
