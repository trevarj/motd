package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.ServerTimeSource
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
    fun `message context distinguishes tagged time from local fallback`() {
        val tagged = mapper.map(
            IrcMessage.parse(
                "@time=2026-07-16T19:09:19.000Z :alice!u@h PRIVMSG #chan :tagged",
            ),
        ) as IrcEvent.ChatMessage
        val local = mapper.map(
            IrcMessage.parse(":alice!u@h PRIVMSG #chan :local"),
        ) as IrcEvent.ChatMessage

        assertEquals(ServerTimeSource.TAG, tagged.ctx.serverTimeSource)
        assertEquals(ServerTimeSource.LOCAL, local.ctx.serverTimeSource)
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
