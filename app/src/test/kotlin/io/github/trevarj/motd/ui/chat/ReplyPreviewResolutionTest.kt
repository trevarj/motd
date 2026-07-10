package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyPreviewResolutionTest {
    @Test
    fun `row-local repository result converts to preview without a Paging scan`() {
        val reply = message(id = 2, msgid = "target", sender = "bob", text = "reply target")
            .toReplyPreviewData()

        assertEquals("bob", reply?.sender)
        assertEquals("reply target", reply?.text)
    }

    @Test
    fun `missing repository result remains an absent preview`() {
        val reply: MessageEntity? = null
        assertNull(reply?.toReplyPreviewData())
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
