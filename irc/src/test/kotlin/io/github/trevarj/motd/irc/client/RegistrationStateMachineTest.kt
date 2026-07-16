package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.proto.IrcMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationStateMachineTest {
    @Test
    fun `server password is sent before CAP NICK and USER`() {
        val machine = RegistrationStateMachine(
            IrcClientConfig(
                host = "cloak",
                port = 6697,
                tls = true,
                nick = "motd",
                username = "motd-android",
                realname = "MOTD",
                serverPassword = "trev/libera:secret",
            ),
        )

        assertEquals(
            listOf(
                "PASS trev/libera:secret",
                "CAP LS 302",
                "NICK motd",
                "USER motd-android 0 * :MOTD",
            ),
            machine.start().sentLines(),
        )
    }

    @Test
    fun `server password uses safe trailing parameter serialization`() {
        val machine = RegistrationStateMachine(
            IrcClientConfig(
                host = "irc.example",
                port = 6697,
                tls = true,
                nick = "motd",
                username = "motd",
                realname = "MOTD",
                serverPassword = "secret with spaces",
            ),
        )

        assertEquals("PASS :secret with spaces", machine.start().sentLines().first())
    }

    @Test
    fun `invalid server password fails without transmitting registration`() {
        val machine = RegistrationStateMachine(
            IrcClientConfig(
                host = "irc.example",
                port = 6697,
                tls = true,
                nick = "motd",
                username = "motd",
                realname = "MOTD",
                serverPassword = "secret\r\nQUIT",
            ),
        )

        val actions = machine.start()
        val fail = actions.single() as RegistrationStateMachine.Action.Fail
        assertEquals("invalid server password", fail.reason)
        assertTrue(fail.fatal)
    }

    @Test
    fun `password mismatch is a fatal registration failure`() {
        val machine = RegistrationStateMachine(
            IrcClientConfig("cloak", 6697, true, "motd", "motd", "MOTD", serverPassword = "bad"),
        )

        val fail = machine.onMessage(
            IrcMessage(command = "464", params = listOf("motd", "Password incorrect")),
        ).single() as RegistrationStateMachine.Action.Fail

        assertEquals("server password rejected", fail.reason)
        assertTrue(fail.fatal)
    }

    @Test
    fun `bouncer bind fallback completes without racing post-welcome caps`() {
        val machine = RegistrationStateMachine(
            IrcClientConfig(
                host = "soju",
                port = 6697,
                tls = true,
                nick = "motd",
                username = "motd",
                realname = "MOTD",
                bouncerNetId = "2",
            ),
        )

        machine.start()
        val req = machine.onMessage(cap("LS", "sasl soju.im/bouncer-networks cap-notify draft/chathistory server-time"))
        assertEquals(listOf("CAP REQ :sasl soju.im/bouncer-networks"), req.sentLines())

        val afterAck = machine.onMessage(cap("ACK", "sasl soju.im/bouncer-networks"))
        assertEquals(listOf("BOUNCER BIND 2", "CAP END"), afterAck.sentLines())

        val afterFirstBindCapChange = machine.onMessage(cap("DEL", "draft/message-redaction"))
        assertTrue(afterFirstBindCapChange.any { it is RegistrationStateMachine.Action.Complete })
        assertTrue(afterFirstBindCapChange.sentLines().isEmpty())
        assertTrue(afterFirstBindCapChange.deferredLines().contains("CAP REQ :draft/chathistory"))
        assertTrue(afterFirstBindCapChange.deferredLines().all { it.substringAfter("CAP REQ :").contains(' ').not() })
    }

    @Test
    fun `registration FAIL surfaces instead of hanging`() {
        val machine = RegistrationStateMachine(
            IrcClientConfig(
                host = "soju",
                port = 6697,
                tls = true,
                nick = "motd",
                username = "motd",
                realname = "MOTD",
                bouncerNetId = "404",
            ),
        )

        val actions = machine.onMessage(
            IrcMessage(command = "FAIL", params = listOf("BOUNCER", "INVALID_NETID", "No such network")),
        )

        val fail = actions.single() as RegistrationStateMachine.Action.Fail
        assertEquals("INVALID_NETID No such network", fail.reason)
        assertTrue(fail.fatal)
    }

    @Test
    fun `account network authcid uses minimal pre-welcome caps`() {
        val machine = RegistrationStateMachine(
            IrcClientConfig(
                host = "soju",
                port = 6697,
                tls = true,
                nick = "motd",
                username = "motd",
                realname = "MOTD",
                saslUser = "motd/libera",
            ),
        )

        machine.start()
        val req = machine.onMessage(cap("LS", "cap-notify sasl soju.im/bouncer-networks draft/chathistory server-time"))
        assertEquals(listOf("CAP REQ :sasl"), req.sentLines())

        val afterAck = machine.onMessage(cap("ACK", "sasl"))
        assertEquals(listOf("CAP END"), afterAck.sentLines())

        val afterCapChange = machine.onMessage(cap("DEL", "extended-monitor"))
        assertTrue(afterCapChange.any { it is RegistrationStateMachine.Action.Complete })
        assertTrue(afterCapChange.sentLines().isEmpty())
        assertTrue(afterCapChange.deferredLines().contains("CAP REQ :server-time"))
    }

    private fun cap(subcommand: String, caps: String) =
        IrcMessage(command = "CAP", params = listOf("*", subcommand, caps))

    private fun List<RegistrationStateMachine.Action>.sentLines(): List<String> =
        filterIsInstance<RegistrationStateMachine.Action.Send>().map { it.line }

    private fun List<RegistrationStateMachine.Action>.deferredLines(): List<String> =
        filterIsInstance<RegistrationStateMachine.Action.SendDeferred>().map { it.line }
}
