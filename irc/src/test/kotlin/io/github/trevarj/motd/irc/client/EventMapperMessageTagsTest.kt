package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventMapperMessageTagsTest {
    private val mapper = EventMapper({ "me" }, { Isupport() })

    @Test
    fun `legacy reply tag maps onto chat message`() {
        val event = mapper.map(
            IrcMessage.parse("@draft/reply=parent :alice!u@h PRIVMSG #chan :hello"),
        ) as IrcEvent.ChatMessage

        assertEquals("parent", event.replyToMsgid)
    }

    @Test
    fun `reaction aliases map onto tag message`() {
        val event = mapper.map(
            IrcMessage.parse("@+react=👍;+reply=parent :alice!u@h TAGMSG #chan"),
        ) as IrcEvent.TagMessage

        assertEquals("👍", event.reactEmoji)
        assertEquals("parent", event.reactTargetMsgid)
    }

    @Test
    fun `unreaction aliases remain raw mutations`() {
        val event = mapper.map(
            IrcMessage.parse("@+unreact=👍;+reply=parent :alice!u@h TAGMSG #chan"),
        )

        assertTrue(event is IrcEvent.Raw)
    }
}
