package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Prefix
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventOriginTest {
    @Test
    fun `history persists silently without owning session state`() {
        assertFalse(EventOrigin.HISTORY.notifies)
        assertFalse(EventOrigin.HISTORY.mutatesSessionState)
        assertTrue(EventOrigin.HISTORY.accepts(IrcEvent.TopicChanged(context(), "#motd", "old", null)))
    }

    @Test
    fun `push accepts only its persistence allowlist`() {
        assertTrue(EventOrigin.PUSH.notifies)
        assertFalse(EventOrigin.PUSH.mutatesSessionState)
        assertTrue(EventOrigin.PUSH.accepts(chat()))
        assertTrue(
            EventOrigin.PUSH.accepts(
                IrcEvent.TagMessage(context(), Prefix("alice"), "#motd", null, null, null),
            ),
        )
        assertTrue(EventOrigin.PUSH.accepts(IrcEvent.Invited(context(), "alice", "me", "#motd")))
        assertTrue(EventOrigin.PUSH.accepts(IrcEvent.Raw(IrcMessage.parse(":alice TAGMSG #motd"))))
        assertFalse(EventOrigin.PUSH.accepts(IrcEvent.ReadMarker("#motd", 10)))
        assertFalse(EventOrigin.PUSH.accepts(IrcEvent.Joined(context(), "alice", "#motd", null, null, false)))
    }

    private fun chat() = IrcEvent.ChatMessage(
        ctx = context(),
        source = Prefix("alice"),
        target = "#motd",
        text = "hello",
        kind = IrcEvent.ChatKind.PRIVMSG,
        isSelf = false,
        replyToMsgid = null,
    )

    private fun context() = MessageContext(
        msgid = "event",
        serverTime = 1,
        account = null,
        batchId = null,
        label = null,
    )
}
