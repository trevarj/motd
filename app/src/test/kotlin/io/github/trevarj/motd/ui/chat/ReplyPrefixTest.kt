package io.github.trevarj.motd.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.trevarj.motd.data.db.BufferType
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyPrefixTest {

    @Test
    fun `prefix is visible in an empty reply draft`() {
        val out = prependReplyPrefix(TextFieldValue(""), "alice")

        assertEquals("alice: ", out.text)
        assertEquals(TextRange(7), out.selection)
    }

    @Test
    fun `prefix preserves existing text and cursor`() {
        val out = prependReplyPrefix(TextFieldValue("hello", selection = TextRange(2)), "alice")

        assertEquals("alice: hello", out.text)
        assertEquals(TextRange(9), out.selection)
    }

    @Test
    fun `prefix is not duplicated`() {
        val value = TextFieldValue("alice: hello", selection = TextRange(12))

        assertEquals(value, prependReplyPrefix(value, "alice"))
    }

    @Test
    fun `reply gesture adds configured prefix in a channel`() {
        val out = composerTextForReply(
            value = TextFieldValue("hello", selection = TextRange(5)),
            sender = "alice",
            bufferType = BufferType.CHANNEL,
            visibleReplyPrefix = true,
        )

        assertEquals("alice: hello", out.text)
        assertEquals(TextRange(12), out.selection)
    }

    @Test
    fun `reply gesture leaves draft unchanged when prefix is disabled or buffer is not a channel`() {
        val value = TextFieldValue("hello", selection = TextRange(3))

        assertEquals(
            value,
            composerTextForReply(value, "alice", BufferType.CHANNEL, visibleReplyPrefix = false),
        )
        assertEquals(
            value,
            composerTextForReply(value, "alice", BufferType.QUERY, visibleReplyPrefix = true),
        )
    }
}
