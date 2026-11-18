package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientTagMappingTest {
    @Test
    fun `direct chat and tag messages retain only client-only tags`() {
        val mapper = EventMapper({ "me" }, { Isupport() })
        val raw = "@account=bot;+example.org/context=payload :bot!u@h PRIVMSG #chat :hello"
        val chat = mapper.map(IrcMessage.parse(raw)) as IrcEvent.ChatMessage

        assertEquals(mapOf("+example.org/context" to "payload"), chat.ctx.clientTags)
        assertFalse("account" in chat.ctx.clientTags)

        val tag = mapper.map(IrcMessage.parse("@account=bot;+example.org/context=state :bot!u@h TAGMSG #chat")) as IrcEvent.TagMessage
        assertEquals("state", tag.ctx.clientTags["+example.org/context"])
    }

    @Test
    fun `multiline plan keeps protocol tag only on opening batch`() {
        val plan =
            planChatMessage(
                target = "#chat",
                text = "first\nsecond",
                replyToMsgid = null,
                label = null,
                multilineLimits = MultilineLimits(maxBytes = 4096, maxLines = 10),
                protocolTags = mapOf("+example.org/context" to "payload"),
            ) as MultilineSendPlan.Batch

        assertEquals("payload", plan.opening.tags["+example.org/context"])
        assertTrue(plan.components.all { "+example.org/context" !in it.tags })
        assertTrue(plan.components.all { it.tags.keys.all { key -> key == "batch" || key == "draft/multiline-concat" } })
    }
}
