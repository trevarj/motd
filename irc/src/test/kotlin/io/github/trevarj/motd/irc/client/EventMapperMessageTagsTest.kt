package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.ServerTimeSource
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventMapperMessageTagsTest {
    private val mapper = EventMapper({ "me" }, { Isupport() })

    @Test
    fun `legacy reply tag maps onto chat message`() {
        val event =
            mapper.map(
                IrcMessage.parse("@draft/reply=parent :alice!u@h PRIVMSG #chan :hello"),
            ) as IrcEvent.ChatMessage

        assertEquals("parent", event.replyToMsgid)
    }

    @Test
    fun `message context distinguishes tagged time from local fallback`() {
        val tagged =
            mapper.map(
                IrcMessage.parse(
                    "@time=2026-07-16T19:09:19.000Z :alice!u@h PRIVMSG #chan :tagged",
                ),
            ) as IrcEvent.ChatMessage
        val local =
            mapper.map(
                IrcMessage.parse(":alice!u@h PRIVMSG #chan :local"),
            ) as IrcEvent.ChatMessage

        assertEquals(ServerTimeSource.TAG, tagged.ctx.serverTimeSource)
        assertEquals(ServerTimeSource.LOCAL, local.ctx.serverTimeSource)
    }

    @Test
    fun `bare bot tag marks chat messages and ignores its value`() {
        val bot = mapper.map(IrcMessage.parse("@bot :alice!u@h PRIVMSG #chan :hello")) as IrcEvent.ChatMessage
        val valued = mapper.map(IrcMessage.parse("@bot=future :alice!u@h NOTICE me :hello")) as IrcEvent.ChatMessage
        val human = mapper.map(IrcMessage.parse(":alice!u@h PRIVMSG #chan :hello")) as IrcEvent.ChatMessage

        assertTrue(bot.isBot)
        assertTrue(valued.isBot)
        assertFalse(human.isBot)
    }

    @Test
    fun `namespaced server tags remain in message context`() {
        val event =
            mapper.map(
                IrcMessage.parse("@trevarj.github.io/sidecar/security=e2ee-verified :alice!u@h PRIVMSG me :secret"),
            ) as IrcEvent.ChatMessage

        assertEquals("e2ee-verified", event.ctx.extensionTags["trevarj.github.io/sidecar/security"])
    }

    @Test
    fun `reaction aliases map onto tag message`() {
        val event =
            mapper.map(
                IrcMessage.parse("@+react=👍;+reply=parent :alice!u@h TAGMSG #chan"),
            ) as IrcEvent.TagMessage

        assertEquals("👍", event.reactEmoji)
        assertEquals("parent", event.reactTargetMsgid)
    }

    @Test
    fun `unreaction aliases remain raw mutations`() {
        val event =
            mapper.map(
                IrcMessage.parse("@+unreact=👍;+reply=parent :alice!u@h TAGMSG #chan"),
            )

        assertTrue(event is IrcEvent.Raw)
    }

    @Test
    fun `message redaction maps target msgid and optional reason`() {
        val event =
            mapper.map(
                IrcMessage.parse(":oper!u@h REDACT #chan parent :spam cleanup"),
            ) as IrcEvent.MessageRedacted

        assertEquals("oper", event.source.nick)
        assertEquals("#chan", event.target)
        assertEquals("parent", event.targetMsgid)
        assertEquals("spam cleanup", event.reason)
    }

    @Test
    fun `message redaction cap requires message tags`() {
        assertEquals(
            setOf("message-tags", "draft/message-redaction"),
            CapNegotiator.requestSet(setOf("message-tags", "draft/message-redaction"), emptySet()),
        )
        assertFalse(
            "draft/message-redaction" in
                CapNegotiator.requestSet(setOf("draft/message-redaction"), emptySet()),
        )
    }

    @Test
    fun `standard replies map to typed events`() {
        val event =
            mapper.map(
                IrcMessage.parse("@label=attempt-1 :irc.example FAIL PRIVMSG CANNOTSENDTOCHAN #chan :Cannot send"),
            ) as IrcEvent.StandardReply

        assertEquals(IrcEvent.StandardReplySeverity.FAIL, event.severity)
        assertEquals("PRIVMSG", event.commandName)
        assertEquals("CANNOTSENDTOCHAN", event.code)
        assertEquals(listOf("#chan"), event.context)
        assertEquals("Cannot send", event.description)
        assertEquals("attempt-1", event.ctx.label)
    }

    @Test
    fun `channel rename maps to typed event`() {
        val event =
            mapper.map(
                IrcMessage.parse(":oper!u@h RENAME #old #new :moving"),
            ) as IrcEvent.ChannelRenamed

        assertEquals("oper", event.actor)
        assertEquals("#old", event.oldName)
        assertEquals("#new", event.newName)
        assertEquals("moving", event.reason)
    }
}
