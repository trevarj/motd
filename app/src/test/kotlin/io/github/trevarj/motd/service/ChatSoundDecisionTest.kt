package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSoundDecisionTest {
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
    }
}
