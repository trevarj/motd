package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingMessagePlanTest {
    @Test
    fun `utf8 chunks respect byte limit and never split a code point`() {
        val chunks = splitUtf8("hello 😀 world café", maxBytes = 10)

        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 10 })
        assertEquals("hello😀worldcafé", chunks.joinToString(""))
        assertTrue(chunks.none(::hasUnpairedSurrogate))
    }

    @Test
    fun `multiline action only wraps the first physical line`() {
        val chunks = prepareOutgoingMessageChunks("/me waves\nplain text", isBouncerServ = false)

        assertEquals(
            listOf(
                OutgoingMessageChunk("\u0001ACTION waves\u0001", "waves", MessageKind.ACTION),
                OutgoingMessageChunk("plain text", "plain text", MessageKind.PRIVMSG),
            ),
            chunks,
        )
    }

    @Test
    fun `long action chunks are individually valid actions and keep display text clean`() {
        val text = "/me " + "😀".repeat(80)
        val chunks = prepareOutgoingMessageChunks(text, isBouncerServ = false, maxBytes = 40)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.kind == MessageKind.ACTION })
        assertTrue(chunks.all { it.wireText.startsWith("\u0001ACTION ") && it.wireText.endsWith("\u0001") })
        assertTrue(chunks.all { it.wireText.toByteArray(Charsets.UTF_8).size <= 40 })
        assertEquals("😀".repeat(80), chunks.joinToString("") { it.displayText })
    }

    @Test
    fun `bouncer service rejects multiline and oversized commands before planning`() {
        assertTrue(prepareOutgoingMessageChunks("help\nserver status", isBouncerServ = true).isEmpty())
        assertTrue(prepareOutgoingMessageChunks("x".repeat(401), isBouncerServ = true).isEmpty())
    }

    @Test
    fun `bouncer service transcript redacts secrets while wire text stays intact`() {
        val chunks = prepareOutgoingMessageChunks(
            "network create -addr irc.example -pass hunter2",
            isBouncerServ = true,
        )

        assertEquals("network create -addr irc.example -pass hunter2", chunks.single().wireText)
        assertFalse(chunks.single().displayText.contains("hunter2"))
        assertTrue(chunks.single().displayText.contains("<redacted>"))
    }

    private fun hasUnpairedSurrogate(text: String): Boolean {
        for (index in text.indices) {
            when {
                text[index].isHighSurrogate() &&
                    (index + 1 >= text.length || !text[index + 1].isLowSurrogate()) -> return true
                text[index].isLowSurrogate() &&
                    (index == 0 || !text[index - 1].isHighSurrogate()) -> return true
            }
        }
        return false
    }
}
