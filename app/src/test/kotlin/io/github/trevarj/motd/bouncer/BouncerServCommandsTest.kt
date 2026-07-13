package io.github.trevarj.motd.bouncer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BouncerServCommandsTest {
    @Test fun quotes_posix_arguments_without_changing_safe_tokens() {
        assertEquals("libera", quoteBouncerArg("libera"))
        assertEquals("'hello world'", quoteBouncerArg("hello world"))
        assertEquals("'it'\\''s here'", quoteBouncerArg("it's here"))
        assertEquals("''", quoteBouncerArg(""))
    }

    @Test fun root_channel_mutations_use_channel_slash_network_not_network_flag() {
        val create = BouncerServCommands.channelCreate(
            "#motd",
            "libera",
            ChannelCommandFields(detached = true, relayDetached = "highlight", detachAfter = "1h"),
        ).wire
        assertEquals(
            "channel create #motd/libera -detached true -relay-detached highlight -detach-after 1h",
            create,
        )
        assertFalse(create.contains("-network"))
        assertEquals("channel delete #motd/libera", BouncerServCommands.channelDelete("#motd", "libera").wire)
        assertEquals("channel status -network libera", BouncerServCommands.channelStatus("libera").wire)
    }

    @Test fun network_update_emits_only_changed_fields() {
        assertEquals(
            "network update libera -nick newNick -enabled false",
            BouncerServCommands.networkUpdate(
                "libera",
                NetworkCommandFields(nick = "newNick", enabled = false),
            ).wire,
        )
    }

    @Test fun root_sasl_and_certfp_commands_always_identify_network() {
        assertEquals("sasl status -network libera", BouncerServCommands.saslStatus("libera").wire)
        assertEquals(
            "sasl set-plain -network libera alice secret",
            BouncerServCommands.saslSetPlain("libera", "alice", "secret").wire,
        )
        assertEquals(
            "certfp fingerprint -network libera",
            BouncerServCommands.certFpFingerprint("libera").wire,
        )
    }

    @Test fun rejects_multiline_and_blank_console_commands() {
        assertThrows(IllegalArgumentException::class.java) { BouncerServCommand("help\nserver status") }
        assertThrows(IllegalArgumentException::class.java) { BouncerServCommand("  ") }
    }

    @Test fun redacts_every_secret_family_and_unknown_arguments() {
        val samples = mapOf(
            "network create -addr irc.example -pass hunter2 -connect-command 'PRIVMSG NickServ :IDENTIFY p'" to
                listOf("hunter2", "IDENTIFY"),
            "sasl set-plain -network libera alice hunter2" to listOf("hunter2"),
            "user create -username bob -password hunter2" to listOf("hunter2"),
            "user update bob -password hunter2" to listOf("hunter2"),
            "user delete bob b4f2aa" to listOf("b4f2aa"),
            "network quote libera 'PRIVMSG NickServ :IDENTIFY hunter2'" to listOf("hunter2", "IDENTIFY"),
            "server notice private announcement" to listOf("private", "announcement"),
            "future command secret argument" to listOf("secret", "argument"),
        )
        for ((wire, secrets) in samples) {
            val safe = redactBouncerServCommand(wire)
            assertTrue(safe.contains("<redacted>"))
            secrets.forEach { assertFalse("$it leaked in $safe", safe.contains(it)) }
        }
    }

    @Test fun discovers_only_command_paths_after_help_marker() {
        assertEquals(
            setOf("network create", "channel status", "server debug"),
            parseAvailableCommandPaths(
                listOf("available commands: network create, channel status, server debug"),
            ),
        )
        assertEquals(
            setOf("network create", "network status"),
            parseAvailableCommandPaths(listOf("available commands: create, status"), "network"),
        )
    }

    @Test fun redacts_user_deletion_confirmation_token_from_transcript() {
        assertEquals(
            "To confirm user deletion, send \"user delete bob <redacted>\"",
            redactBouncerServReply("To confirm user deletion, send \"user delete bob b4f2aa\""),
        )
    }
}
