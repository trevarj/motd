package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import org.junit.Assert.assertEquals
import org.junit.Test

class EventMapperTopicTest {
    private val mapper = EventMapper({ "me" }, { Isupport() })

    @Test
    fun `topic numerics map to state snapshots`() {
        assertEquals(
            IrcEvent.TopicSnapshot("#Room", "Welcome to the room"),
            mapper.map(IrcMessage.parse(":server 332 me #Room :Welcome to the room")),
        )
        assertEquals(
            IrcEvent.TopicSnapshot("#Room", ""),
            mapper.map(IrcMessage.parse(":server 331 me #Room :No topic is set")),
        )
    }
}
