package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.prefs.matchesConfiguredNick
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSoundDecisionTest {
    @Test
    fun `send and receive select distinct cues`() {
        assertEquals(
            ChatSoundCue.SEND,
            outgoingChatSoundCue(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                muted = false,
            ),
        )
        assertEquals(
            ChatSoundCue.RECEIVE,
            incomingChatSoundCue(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.CHANNEL,
                muted = false,
                senderIsFool = false,
            ),
        )
    }

    @Test
    fun `incoming sound is limited to the open foreground chat`() {
        assertTrue(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.CHANNEL,
                muted = false,
                senderIsFool = false,
            ),
        )
        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 8,
                bufferId = 7,
                type = BufferType.CHANNEL,
                muted = false,
                senderIsFool = false,
            ),
        )
    }

    @Test
    fun `incoming sound respects master mute fools and server buffers`() {
        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = false,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.QUERY,
                muted = false,
                senderIsFool = false,
            ),
        )
        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.QUERY,
                muted = true,
                senderIsFool = false,
            ),
        )
        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.QUERY,
                muted = false,
                senderIsFool = true,
            ),
        )
        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.SERVER,
                muted = false,
                senderIsFool = false,
            ),
        )
    }

    @Test
    fun `outgoing sound requires enabled open unmuted chat`() {
        assertTrue(
            shouldPlayOutgoingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                muted = false,
            ),
        )
        assertFalse(
            shouldPlayOutgoingChatSound(
                enabled = true,
                foregroundBufferId = null,
                bufferId = 7,
                muted = false,
            ),
        )
        assertFalse(
            shouldPlayOutgoingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                muted = true,
            ),
        )
        assertNull(
            outgoingChatSoundCue(
                enabled = true,
                foregroundBufferId = null,
                bufferId = 7,
                muted = false,
            ),
        )
    }

    @Test
    fun `sound fool suppression follows the networks casemapping`() {
        val configured = setOf("listener~")
        val rfc = IrcIdentityRules(caseMapping = IrcCaseMapping.Rfc1459)
        val strict = IrcIdentityRules(caseMapping = IrcCaseMapping.Rfc1459Strict)

        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.QUERY,
                muted = false,
                senderIsFool = rfc.matchesConfiguredNick("listener^", configured),
            ),
        )
        assertTrue(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.QUERY,
                muted = false,
                senderIsFool = strict.matchesConfiguredNick("listener^", configured),
            ),
        )
    }

    @Test
    fun `sound fool suppression follows canonical account across nick changes`() {
        val rules = IrcIdentityRules(caseMapping = IrcCaseMapping.Ascii)

        assertTrue(
            isFoolForChatSound(
                fools = setOf("stable-account"),
                identityRules = rules,
                senderAccount = "stable-account",
                normalizedActor = rules.normalize("new-nick"),
            ),
        )
        assertFalse(
            isFoolForChatSound(
                fools = setOf("old-nick"),
                identityRules = rules,
                senderAccount = null,
                normalizedActor = rules.normalize("new-nick"),
            ),
        )
    }
}
