package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WhoxTest {
    @Test fun `command uses exact consumed fields and bounded token`() {
        assertEquals("WHO #room %tuhnafr,999", WhoxCommands.request("#room", 999).serialize())
        runCatching { WhoxCommands.request("#room", 1_000) }
            .onSuccess { error("out-of-range token accepted") }
    }

    @Test fun `mapper parses canonical row placeholders and completion`() {
        val mapper = EventMapper({ "motd" }, { Isupport() }, now = { 1L })
        val row = mapper.map(
            IrcMessage.parse(":srv 354 motd 7 ~user cloak.example Nick 0 H@ :Real Name"),
        ) as IrcEvent.WhoxRow
        assertEquals(7, row.token)
        assertEquals("~user", row.username)
        assertEquals("cloak.example", row.host)
        assertEquals("Nick", row.nick)
        assertEquals(null, row.account)
        assertEquals("H@", row.flags)
        assertEquals("Real Name", row.realname)
        assertEquals(IrcEvent.WhoxComplete("#Room"), mapper.map(IrcMessage.parse(":srv 315 motd #Room :End")))
    }

    @Test fun `same normalized mask coalesces and concurrent masks keep tokens separate`() = runTest {
        val transport = FakeTransport()
        val client = registeredWhox(transport)

        val first = async { client.whox("#Room") }
        val duplicate = async { client.whox("#room") }
        val other = async { client.whox("#other") }
        runCurrent()
        val commands = transport.sent.filter { it.startsWith("WHO ") }
        assertEquals(2, commands.size)
        val roomToken = commands.first { it.startsWith("WHO #Room ") }.substringAfterLast(',').toInt()
        val otherToken = commands.first { it.startsWith("WHO #other ") }.substringAfterLast(',').toInt()
        assertTrue(roomToken in 0..999)
        assertTrue(otherToken in 0..999)
        assertTrue(roomToken != otherToken)

        transport.feed(":srv 354 motd $otherToken u h Other acct H :Other Real")
        transport.feed(":srv 315 motd #other :End")
        transport.feed(":srv 354 motd $roomToken u h Nick * G :Room Real")
        transport.feed(":srv 315 motd #ROOM :End")
        runCurrent()

        assertEquals(listOf("Nick"), first.await().rows.map { it.nick })
        assertEquals(first.await(), duplicate.await())
        assertEquals(listOf("Other"), other.await().rows.map { it.nick })
        assertTrue(first.await().completed)
    }

    @Test fun `unsupported server returns incomplete without wire command`() = runTest {
        val transport = FakeTransport()
        val client = registeredWhox(transport, advertiseWhox = false)
        val before = transport.sent.size
        val result = client.whox("#room")
        assertFalse(result.completed)
        assertTrue(result.rows.isEmpty())
        assertEquals(before, transport.sent.size)
    }

    private fun TestScope.clientScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    private suspend fun TestScope.registeredWhox(
        transport: FakeTransport,
        advertiseWhox: Boolean = true,
    ): IrcClient {
        val client = IrcClient(
            IrcClientConfig("irc.example", 6697, true, "motd", "motd", "MOTD"),
            transport.factory(),
            clientScope(),
        )
        client.start()
        runCurrent()
        transport.feed(":srv CAP * LS :message-tags")
        runCurrent()
        transport.feed(":srv CAP motd ACK :message-tags")
        runCurrent()
        val tokens = if (advertiseWhox) "WHOX CASEMAPPING=rfc1459" else "CASEMAPPING=rfc1459"
        transport.feed(":srv 005 motd $tokens :supported")
        transport.feed(":srv 001 motd :Welcome")
        runCurrent()
        return client
    }
}
