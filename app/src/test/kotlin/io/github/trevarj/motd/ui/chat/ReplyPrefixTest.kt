package io.github.trevarj.motd.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
}
