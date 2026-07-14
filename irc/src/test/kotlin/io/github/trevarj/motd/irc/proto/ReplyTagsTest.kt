package io.github.trevarj.motd.irc.proto

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyTagsTest {
    @Test
    fun `standard reply tag wins over compatibility aliases`() {
        val message = IrcMessage.parse(
            "@draft/reply=server-draft;reply=server;+draft/reply=client-draft;+reply=standard " +
                ":alice!u@h PRIVMSG #chan :hello",
        )

        assertEquals("standard", message.replyReference())
    }

    @Test
    fun `legacy draft client tag is recognized`() {
        val message = IrcMessage.parse(
            "@+draft/reply=parent :alice!u@h PRIVMSG #chan :hello",
        )

        assertEquals("parent", message.replyReference())
    }

    @Test
    fun `server-tagged history reply is recognized`() {
        val message = IrcMessage.parse(
            "@draft/reply=parent :alice!u@h PRIVMSG #chan :hello",
        )

        assertEquals("parent", message.replyReference())
    }

    @Test
    fun `reaction and unreaction compatibility aliases are recognized`() {
        val reaction = IrcMessage.parse("@+react=👍;+reply=parent TAGMSG #chan")
        val unreaction = IrcMessage.parse("@draft/unreact=👍;draft/reply=parent TAGMSG #chan")

        assertEquals("👍", reaction.reactionValue())
        assertEquals("parent", reaction.replyReference())
        assertEquals("👍", unreaction.unreactionValue())
        assertEquals("parent", unreaction.replyReference())
    }
}
