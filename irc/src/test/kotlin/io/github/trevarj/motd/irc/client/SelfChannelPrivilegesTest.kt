package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Isupport
import org.junit.Assert.assertEquals
import org.junit.Test

class SelfChannelPrivilegesTest {
    @Test
    fun deopDuringNamesWinsWithMixedChannelCase() {
        val support = Isupport().apply { update(listOf("PREFIX=(ov)@+")) }
        val tracker = SelfChannelPrivileges()
        val joined = setOf("#Room")
        tracker.apply(IrcEvent.NamesStarted("#Room"), support, "Me", joined)
        tracker.apply(
            IrcEvent.ModeChanged(MessageContext(null, 0, null, null, null), "#room", "-o", listOf("ME")),
            support,
            "Me",
            joined,
        )
        tracker.apply(
            IrcEvent.Names("#Room", listOf(IrcEvent.Names.Member("me", "@+", null, null))),
            support,
            "Me",
            joined,
        )
        assertEquals(setOf('v'), tracker.modes.value["#room"])
    }
}
