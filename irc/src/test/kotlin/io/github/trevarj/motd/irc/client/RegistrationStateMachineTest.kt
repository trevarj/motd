package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationStateMachineTest {
    @Test
    fun `labeled-response is requested only alongside batch during registration`() {
        val config = IrcClientConfig("irc.example", 6697, true, "motd", "motd", "motd", extraCaps = setOf("labeled-response"))
        val withoutBatch = RegistrationStateMachine(config)
        withoutBatch.start()
        assertEquals(listOf("CAP END"), withoutBatch.onMessage(cap("LS", "labeled-response")).sentLines())

        val withBatch = RegistrationStateMachine(config)
        withBatch.start()
        assertEquals(listOf("CAP REQ :batch labeled-response"), withBatch.onMessage(cap("LS", "labeled-response batch")).sentLines())
        assertEquals(listOf("CAP END"), withBatch.onMessage(cap("ACK", "labeled-response batch")).sentLines())
        val ready = withBatch.onMessage(IrcMessage(command = "001", params = listOf("motd", "Welcome")))
        assertEquals(setOf("batch", "labeled-response"), ready.filterIsInstance<RegistrationStateMachine.Action.Complete>().single().caps)
    }

    @Test
    fun `labeled-response is unavailable if batch is missing or withdrawn before welcome`() {
        val config = IrcClientConfig("irc.example", 6697, true, "motd", "motd", "motd")
        for (batchLoss in listOf(null, cap("ACK", "-batch"), cap("DEL", "batch"))) {
            val machine = RegistrationStateMachine(config)
            machine.start()
            machine.onMessage(cap("LS", "batch labeled-response"))
            machine.onMessage(cap("ACK", if (batchLoss == null) "labeled-response" else "batch labeled-response"))
            if (batchLoss != null) machine.onMessage(batchLoss)
            val welcome = machine.onMessage(IrcMessage(command = "001", params = listOf("motd", "Welcome")))
            assertEquals(emptySet<String>(), welcome.filterIsInstance<RegistrationStateMachine.Action.Complete>().single().caps)
        }
    }

    @Test
    fun `server password is sent before CAP NICK and USER`() {
        val machine =
            RegistrationStateMachine(
                IrcClientConfig(
                    host = "cloak",
                    port = 6697,
                    tls = true,
                    nick = "motd",
                    username = "motd-android",
                    realname = "motd",
                    serverPassword = "trev/libera:secret",
                ),
            )

        assertEquals(
            listOf(
                "PASS trev/libera:secret",
                "CAP LS 302",
                "NICK motd",
                "USER motd-android 0 * :motd",
            ),
            machine.start().sentLines(),
        )
    }

    @Test
    fun `server password uses safe trailing parameter serialization`() {
        val machine =
            RegistrationStateMachine(
                IrcClientConfig(
                    host = "irc.example",
                    port = 6697,
                    tls = true,
                    nick = "motd",
                    username = "motd",
                    realname = "motd",
                    serverPassword = "secret with spaces",
                ),
            )

        assertEquals("PASS :secret with spaces", machine.start().sentLines().first())
    }

    @Test
    fun `invalid server password fails without transmitting registration`() {
        val machine =
            RegistrationStateMachine(
                IrcClientConfig(
                    host = "irc.example",
                    port = 6697,
                    tls = true,
                    nick = "motd",
                    username = "motd",
                    realname = "motd",
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
        val machine =
            RegistrationStateMachine(
                IrcClientConfig("cloak", 6697, true, "motd", "motd", "motd", serverPassword = "bad"),
            )

        val fail =
            machine
                .onMessage(
                    IrcMessage(command = "464", params = listOf("motd", "Password incorrect")),
                ).single() as RegistrationStateMachine.Action.Fail

        assertEquals("server password rejected", fail.reason)
        assertTrue(fail.fatal)
    }

    @Test
    fun `NickServ identify supports all argument forms after welcome`() {
        val base =
            IrcClientConfig(
                host = "irc.example",
                port = 6697,
                tls = true,
                nick = "motd",
                username = "motd",
                realname = "motd",
                nickServPassword = "nick-secret",
            )

        val nickPassword = RegistrationStateMachine(base)
        nickPassword.start()
        nickPassword.onMessage(cap("LS", ""))
        val nickPasswordWelcome =
            nickPassword.onMessage(
                IrcMessage(command = "001", params = listOf("motd", "Welcome")),
            )
        assertEquals(
            listOf("PRIVMSG NickServ :IDENTIFY motd nick-secret"),
            nickPasswordWelcome.sentLines(),
        )
        assertTrue(
            nickPasswordWelcome.indexOfFirst { it is RegistrationStateMachine.Action.Send } <
                nickPasswordWelcome.indexOfFirst { it is RegistrationStateMachine.Action.Complete },
        )

        val passwordNick =
            RegistrationStateMachine(
                base.copy(nickServIdentifySyntax = NickServIdentifySyntax.PASSWORD_NICK),
            )
        passwordNick.start()
        passwordNick.onMessage(cap("LS", ""))
        assertEquals(
            listOf("PRIVMSG NickServ :IDENTIFY nick-secret motd"),
            passwordNick
                .onMessage(
                    IrcMessage(command = "001", params = listOf("motd", "Welcome")),
                ).sentLines(),
        )

        val passwordOnly =
            RegistrationStateMachine(
                base.copy(nickServIdentifySyntax = NickServIdentifySyntax.PASSWORD_ONLY),
            )
        passwordOnly.start()
        passwordOnly.onMessage(cap("LS", ""))
        assertEquals(
            listOf("PRIVMSG NickServ :IDENTIFY nick-secret"),
            passwordOnly
                .onMessage(
                    IrcMessage(command = "001", params = listOf("motd", "Welcome")),
                ).sentLines(),
        )
    }

    @Test
    fun `alternate nick runs configured recovery then requests preferred nick`() {
        val machine =
            RegistrationStateMachine(
                IrcClientConfig(
                    host = "irc.example",
                    port = 6697,
                    tls = true,
                    nick = "motd",
                    username = "motd",
                    realname = "motd",
                    nickServPassword = "nick-secret",
                    nickServRecoveryCommands = listOf("GHOST", "REGAIN"),
                ),
            )

        machine.start()
        assertEquals(
            listOf("NICK motd_"),
            machine.onMessage(IrcMessage(command = "433", params = listOf("*", "motd"))).sentLines(),
        )
        machine.onMessage(cap("LS", ""))

        assertEquals(
            listOf(
                "PRIVMSG NickServ :IDENTIFY motd nick-secret",
                "PRIVMSG NickServ :GHOST motd nick-secret",
                "PRIVMSG NickServ :REGAIN motd nick-secret",
                "NICK motd",
            ),
            machine
                .onMessage(
                    IrcMessage(command = "001", params = listOf("motd_", "Welcome")),
                ).sentLines(),
        )
    }

    @Test
    fun `invalid persisted NickServ values fail before registration`() {
        listOf(
            IrcClientConfig(
                "irc.example",
                6697,
                true,
                "motd",
                "motd",
                "motd",
                nickServPassword = "two words",
            ),
            IrcClientConfig(
                "irc.example",
                6697,
                true,
                "motd",
                "motd",
                "motd",
                nickServPassword = "secret",
                nickServRecoveryCommands = listOf("QUIT now"),
            ),
            IrcClientConfig(
                "irc.example",
                6697,
                true,
                "motd",
                "motd",
                "motd",
                nickServPassword = "x".repeat(500),
            ),
        ).forEach { config ->
            val fail =
                RegistrationStateMachine(config).start().single()
                    as RegistrationStateMachine.Action.Fail
            assertEquals("invalid NickServ configuration", fail.reason)
            assertTrue(fail.fatal)
        }
    }

    @Test
    fun `disabled or missing NickServ recovery sends no recovery commands`() {
        val disabled =
            RegistrationStateMachine(
                IrcClientConfig(
                    "irc.example",
                    6697,
                    true,
                    "motd",
                    "motd",
                    "motd",
                    nickServPassword = "secret",
                ),
            )
        disabled.start()
        disabled.onMessage(IrcMessage(command = "433", params = listOf("*", "motd")))
        disabled.onMessage(cap("LS", ""))
        assertEquals(
            listOf("PRIVMSG NickServ :IDENTIFY motd secret"),
            disabled
                .onMessage(
                    IrcMessage(command = "001", params = listOf("motd_", "Welcome")),
                ).sentLines(),
        )

        val missing =
            RegistrationStateMachine(
                IrcClientConfig(
                    "irc.example",
                    6697,
                    true,
                    "motd",
                    "motd",
                    "motd",
                    nickServRecoveryCommands = listOf("REGAIN"),
                ),
            )
        missing.start()
        missing.onMessage(IrcMessage(command = "433", params = listOf("*", "motd")))
        missing.onMessage(cap("LS", ""))
        assertTrue(
            missing
                .onMessage(
                    IrcMessage(command = "001", params = listOf("motd_", "Welcome")),
                ).sentLines()
                .isEmpty(),
        )
    }

    @Test
    fun `SASL configuration ignores NickServ fallback`() {
        val machine =
            RegistrationStateMachine(
                IrcClientConfig(
                    "irc.example",
                    6697,
                    true,
                    "motd",
                    "motd",
                    "motd",
                    sasl = SaslMechanism.PLAIN,
                    nickServPassword = "two words",
                    nickServRecoveryCommands = listOf("QUIT now"),
                ),
            )

        assertTrue(machine.start().none { it is RegistrationStateMachine.Action.Fail })
    }

    @Test
    fun `bouncer bind negotiates replay safety before fallback ready`() {
        val machine =
            RegistrationStateMachine(
                IrcClientConfig(
                    host = "soju",
                    port = 6697,
                    tls = true,
                    nick = "motd",
                    username = "motd",
                    realname = "motd",
                    bouncerNetId = "2",
                ),
            )

        machine.start()
        val req =
            machine.onMessage(
                cap(
                    "LS",
                    "sasl soju.im/bouncer-networks cap-notify draft/chathistory batch " +
                        "message-tags server-time echo-message extended-monitor",
                ),
            )
        val replayBarrier =
            "sasl soju.im/bouncer-networks draft/chathistory batch message-tags server-time"
        assertEquals(listOf("CAP REQ :$replayBarrier"), req.sentLines())

        val afterAck = machine.onMessage(cap("ACK", replayBarrier))
        assertEquals(listOf("BOUNCER BIND 2", "CAP END"), afterAck.sentLines())

        val afterFirstBindCapChange = machine.onMessage(cap("DEL", "extended-monitor"))
        assertTrue(afterFirstBindCapChange.any { it is RegistrationStateMachine.Action.Complete })
        assertTrue(afterFirstBindCapChange.sentLines().isEmpty())
        assertEquals(listOf("CAP REQ :echo-message"), afterFirstBindCapChange.deferredLines())
        val ready = afterFirstBindCapChange.filterIsInstance<RegistrationStateMachine.Action.Complete>().single()
        assertTrue(ready.caps.containsAll(replayBarrier.split(' ')))
        assertEquals(setOf("echo-message"), ready.deferredCaps)
        assertTrue(afterFirstBindCapChange.deferredLines().all { it.substringAfter("CAP REQ :").contains(' ').not() })
    }

    @Test
    fun `registration FAIL surfaces instead of hanging`() {
        val machine =
            RegistrationStateMachine(
                IrcClientConfig(
                    host = "soju",
                    port = 6697,
                    tls = true,
                    nick = "motd",
                    username = "motd",
                    realname = "motd",
                    bouncerNetId = "404",
                ),
            )

        val actions =
            machine.onMessage(
                IrcMessage(command = "FAIL", params = listOf("BOUNCER", "INVALID_NETID", "No such network")),
            )

        val fail = actions.single() as RegistrationStateMachine.Action.Fail
        assertEquals("INVALID_NETID No such network", fail.reason)
        assertTrue(fail.fatal)
    }

    @Test
    fun `pre-away sends AWAY before CAP END when capability is acked`() {
        val machine =
            RegistrationStateMachine(
                IrcClientConfig(
                    host = "irc.example",
                    port = 6697,
                    tls = true,
                    nick = "motd",
                    username = "motd",
                    realname = "motd",
                    initialAwayMessage = "back later",
                ),
            )

        machine.start()
        assertEquals(listOf("CAP REQ :draft/pre-away"), machine.onMessage(cap("LS", "draft/pre-away")).sentLines())
        assertEquals(
            listOf("AWAY :back later", "CAP END"),
            machine.onMessage(cap("ACK", "draft/pre-away")).sentLines(),
        )
    }

    @Test
    fun `initial away falls back after welcome when pre-away is absent`() {
        val machine =
            RegistrationStateMachine(
                IrcClientConfig(
                    host = "irc.example",
                    port = 6697,
                    tls = true,
                    nick = "motd",
                    username = "motd",
                    realname = "motd",
                    initialAwayMessage = "back later",
                ),
            )

        machine.start()
        assertEquals(listOf("CAP END"), machine.onMessage(cap("LS", "")).sentLines())
        val welcome = machine.onMessage(IrcMessage(command = "001", params = listOf("motd", "Welcome")))

        assertTrue(welcome.any { it is RegistrationStateMachine.Action.Complete })
        assertEquals(listOf("AWAY :back later"), welcome.sentLines())
    }

    @Test
    fun `pre-away failure is reported and retried after welcome`() {
        val machine =
            RegistrationStateMachine(
                IrcClientConfig(
                    host = "irc.example",
                    port = 6697,
                    tls = true,
                    nick = "motd",
                    username = "motd",
                    realname = "motd",
                    initialAwayMessage = "back later",
                ),
            )

        machine.start()
        machine.onMessage(cap("LS", "draft/pre-away"))
        machine.onMessage(cap("ACK", "draft/pre-away"))
        val failure =
            machine
                .onMessage(
                    IrcMessage(command = "FAIL", params = listOf("AWAY", "INVALID_PARAMS", "rejected")),
                ).single() as RegistrationStateMachine.Action.Emit
        val reply = failure.event as IrcEvent.StandardReply
        assertEquals("AWAY", reply.commandName)

        val welcome = machine.onMessage(IrcMessage(command = "001", params = listOf("motd", "Welcome")))
        assertEquals(listOf("AWAY :back later"), welcome.sentLines())
    }

    @Test
    fun `account network authcid negotiates replay safety before fallback ready`() {
        val machine =
            RegistrationStateMachine(
                IrcClientConfig(
                    host = "soju",
                    port = 6697,
                    tls = true,
                    nick = "motd",
                    username = "motd",
                    realname = "motd",
                    saslUser = "motd/libera",
                ),
            )

        machine.start()
        val req =
            machine.onMessage(
                cap(
                    "LS",
                    "cap-notify sasl soju.im/bouncer-networks draft/chathistory batch message-tags " +
                        "server-time znc.in/server-time-iso echo-message",
                ),
            )
        val replayBarrier = "sasl draft/chathistory batch message-tags server-time"
        assertEquals(listOf("CAP REQ :$replayBarrier"), req.sentLines())

        val afterAck = machine.onMessage(cap("ACK", replayBarrier))
        assertEquals(listOf("CAP END"), afterAck.sentLines())

        val afterCapChange = machine.onMessage(cap("DEL", "extended-monitor"))
        assertTrue(afterCapChange.any { it is RegistrationStateMachine.Action.Complete })
        assertTrue(afterCapChange.sentLines().isEmpty())
        assertEquals(listOf("CAP REQ :echo-message"), afterCapChange.deferredLines())
    }

    @Test
    fun `normal bouncer welcome exposes deferred capability decisions`() {
        val machine =
            RegistrationStateMachine(
                IrcClientConfig(
                    host = "soju",
                    port = 6697,
                    tls = true,
                    nick = "motd",
                    username = "motd",
                    realname = "motd",
                    bouncerNetId = "2",
                ),
            )
        machine.start()
        val replayBarrier =
            "sasl soju.im/bouncer-networks draft/chathistory batch message-tags server-time"
        machine.onMessage(cap("LS", "$replayBarrier draft/read-marker"))
        machine.onMessage(cap("ACK", replayBarrier))

        val welcome = machine.onMessage(IrcMessage(command = "001", params = listOf("motd", "Welcome")))
        val ready = welcome.filterIsInstance<RegistrationStateMachine.Action.Complete>().single()

        assertTrue("draft/read-marker" in ready.deferredCaps)
        assertTrue(welcome.sentLines().contains("CAP REQ :draft/read-marker"))
    }

    @Test
    fun `fallback CAP DEL updates ready snapshot and drops stale deferred caps`() {
        val machine =
            RegistrationStateMachine(
                IrcClientConfig(
                    host = "soju",
                    port = 6697,
                    tls = true,
                    nick = "motd",
                    username = "motd",
                    realname = "motd",
                    bouncerNetId = "2",
                ),
            )

        machine.start()
        val replayBarrier =
            "sasl soju.im/bouncer-networks draft/chathistory batch message-tags server-time"
        machine.onMessage(cap("LS", "$replayBarrier extended-monitor"))
        machine.onMessage(cap("ACK", replayBarrier))

        val actions = machine.onMessage(cap("DEL", "message-tags extended-monitor"))
        val ready = actions.filterIsInstance<RegistrationStateMachine.Action.Complete>().single()

        assertTrue("message-tags" !in ready.caps)
        assertTrue("draft/chathistory" in ready.caps)
        assertTrue("message-tags" !in ready.deferredCaps)
        assertTrue(
            actions.deferredLines().none {
                it.endsWith("message-tags") || it.endsWith("extended-monitor")
            },
        )
    }

    @Test
    fun `fallback CAP NEW adds newly advertised desired cap to deferred requests`() {
        val machine =
            RegistrationStateMachine(
                IrcClientConfig(
                    host = "soju",
                    port = 6697,
                    tls = true,
                    nick = "motd",
                    username = "motd",
                    realname = "motd",
                    bouncerNetId = "2",
                ),
            )

        machine.start()
        val replayBarrier =
            "sasl soju.im/bouncer-networks draft/chathistory batch message-tags server-time"
        machine.onMessage(cap("LS", replayBarrier))
        machine.onMessage(cap("ACK", replayBarrier))

        val actions =
            machine.onMessage(
                cap("NEW", "draft/metadata-2=before-connect,max-keys=0,max-value-bytes=1"),
            )

        assertTrue(actions.deferredLines().contains("CAP REQ :draft/metadata-2"))
        val ready = actions.filterIsInstance<RegistrationStateMachine.Action.Complete>().single()
        assertTrue(ready.caps.none { it.startsWith("draft/metadata-2") })
        assertTrue("draft/metadata-2" in ready.deferredCaps)
    }

    private fun cap(
        subcommand: String,
        caps: String,
    ) = IrcMessage(command = "CAP", params = listOf("*", subcommand, caps))

    private fun List<RegistrationStateMachine.Action>.sentLines(): List<String> = filterIsInstance<RegistrationStateMachine.Action.Send>().map { it.line }

    private fun List<RegistrationStateMachine.Action>.deferredLines(): List<String> = filterIsInstance<RegistrationStateMachine.Action.SendDeferred>().map { it.line }
}
