package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import io.github.trevarj.motd.irc.proto.Prefix
import org.junit.Assert.assertEquals
import org.junit.Test

class EventMapperMonitorTest {
    private val mapper = EventMapper({ "me" }, { Isupport() })

    @Test fun `monitor numerics map to typed snapshots and deltas`() {
        assertEquals(
            IrcEvent.MonitorOnline(
                listOf(Prefix("Alice", "~user", "cloak.example"), Prefix("Bob")),
            ),
            mapper.map(IrcMessage.parse(":server 730 me :Alice!~user@cloak.example,Bob")),
        )
        assertEquals(
            IrcEvent.MonitorOffline(listOf("Alice", "Bob")),
            mapper.map(IrcMessage.parse(":server 731 me :Alice,Bob")),
        )
        assertEquals(
            IrcEvent.MonitorList(listOf("Alice", "Bob")),
            mapper.map(IrcMessage.parse(":server 732 me :Alice,Bob")),
        )
        assertEquals(IrcEvent.MonitorListEnd, mapper.map(IrcMessage.parse(":server 733 me :end")))
        assertEquals(
            IrcEvent.MonitorLimitExceeded(100, listOf("Alice", "Bob"), "limit exceeded"),
            mapper.map(IrcMessage.parse(":server 734 me 100 Alice,Bob :limit exceeded")),
        )
    }

    @Test fun `malformed online identities are skipped without corrupting valid nicks`() {
        assertEquals(
            IrcEvent.MonitorOnline(listOf(Prefix("Good"), Prefix("Partial"))),
            mapper.map(IrcMessage.parse(":server 730 me :Good,!bad@host,Partial!user")),
        )
    }
}
