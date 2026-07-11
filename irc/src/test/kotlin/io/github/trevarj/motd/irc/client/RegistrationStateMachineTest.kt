package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.proto.IrcMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationStateMachineTest {
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
    }

    private fun cap(subcommand: String, caps: String) =
        IrcMessage(command = "CAP", params = listOf("*", subcommand, caps))

    private fun List<RegistrationStateMachine.Action>.sentLines(): List<String> =
        filterIsInstance<RegistrationStateMachine.Action.Send>().map { it.line }

    private fun List<RegistrationStateMachine.Action>.anySendStartingWith(prefix: String): Boolean =
        sentLines().any { it.startsWith(prefix) }
}
