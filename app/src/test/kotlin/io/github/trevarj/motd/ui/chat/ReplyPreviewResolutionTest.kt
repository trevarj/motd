package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyPreviewResolutionTest {
    @Test
    fun resolves_target_only_from_the_loaded_window() {
        val loaded = listOf(
            message(id = 1, msgid = "newest", sender = "alice", text = "new"),
            message(id = 2, msgid = "target", sender = "bob", text = "reply target"),
            null,
        )
        val accessed = mutableListOf<Int>()

        val reply = resolveReplyFromLoadedItems("target", loaded.size) { index ->
            accessed += index
            loaded[index]
        }

        assertEquals("bob", reply?.sender)
        assertEquals("reply target", reply?.text)
        assertEquals(listOf(0, 1), accessed)
    }

    @Test
    fun does_not_resolve_an_unloaded_target() {
        val loaded = listOf(message(id = 1, msgid = "loaded", sender = "alice", text = "visible"))

        val reply = resolveReplyFromLoadedItems("not-loaded", loaded.size) { loaded[it] }

        assertNull(reply)
    }

    private fun message(id: Long, msgid: String, sender: String, text: String) = MessageEntity(
        id = id,
        bufferId = 1,
        msgid = msgid,
        serverTime = id,
        sender = sender,
        kind = MessageKind.PRIVMSG,
        text = text,
        dedupKey = msgid,
    )
}
