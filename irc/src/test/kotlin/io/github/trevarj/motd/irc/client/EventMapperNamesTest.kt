package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventMapperNamesTest {
    @Test fun `userhost in names retains all prefixes username and host`() {
        val isupport = Isupport().apply { update(listOf("PREFIX=(qaohv)~&@%+")) }
        val mapper = EventMapper({ "me" }, { isupport }, now = { 1L })

        assertNull(
            mapper.map(
                IrcMessage.parse(
                    ":irc.example 353 me = #Room :~@Nick!~user@host.example +Plain Broken!user !bad@host",
                ),
            ),
        )
        val names = mapper.map(IrcMessage.parse(":irc.example 366 me #Room :End of NAMES")) as IrcEvent.Names

        assertEquals("#Room", names.channel)
        assertEquals(
            IrcEvent.Names.Member("Nick", "~@", "~user", "host.example"),
            names.members[0],
        )
        assertEquals(IrcEvent.Names.Member("Plain", "+", null, null), names.members[1])
        assertEquals(IrcEvent.Names.Member("Broken", "", null, null), names.members[2])
        assertEquals(3, names.members.size)
    }
}
