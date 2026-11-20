package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.event.ServerTimeSource
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelJoinOutcomeTest {
    @Test
    fun `473 produces a target keyed join rejection while ready`() {
        val outcome =
            channelJoinOutcome(
                networkId = 7,
                event = IrcEvent.ServerError("473", listOf("me", "#locked"), "Cannot join channel (+i)"),
                identityRules = IrcIdentityRules(),
            )

        assertTrue(outcome is ChannelJoinOutcome.Rejected)
        outcome as ChannelJoinOutcome.Rejected
        assertEquals(7L, outcome.networkId)
        assertEquals("#locked", outcome.channel)
        assertEquals("Cannot join channel (+i)", outcome.reason)
    }

    @Test
    fun `477 classifies account required`() {
        val outcome =
            channelJoinOutcome(
                networkId = 7,
                event = IrcEvent.ServerError("477", listOf("me", "#registered"), "Log in first"),
                identityRules = IrcIdentityRules(),
            ) as ChannelJoinOutcome.Rejected

        assertEquals(ChannelJoinRejectionKind.ACCOUNT_REQUIRED, outcome.kind)
        assertEquals("477", outcome.code)
    }

    @Test
    fun `ircv3 fail join classifies bad key codes and keeps the target`() {
        val replies =
            listOf(
                "CANNOT_JOIN" to ChannelJoinRejectionKind.OTHER,
                "BAD_KEY" to ChannelJoinRejectionKind.BAD_KEY,
                "BAD_CHANNEL_KEY" to ChannelJoinRejectionKind.BAD_KEY,
            )
        for ((code, kind) in replies) {
            val outcome =
                channelJoinOutcome(
                    networkId = 7,
                    event =
                        IrcEvent.StandardReply(
                            ctx = MessageContext(null, 0, null, null, null, ServerTimeSource.LOCAL),
                            severity = IrcEvent.StandardReplySeverity.FAIL,
                            commandName = "JOIN",
                            code = code,
                            context = listOf("#locked"),
                            description = "Cannot join channel",
                        ),
                    identityRules = IrcIdentityRules(),
                ) as ChannelJoinOutcome.Rejected

            assertEquals("#locked", outcome.channel)
            assertEquals(kind, outcome.kind)
        }
    }
}
