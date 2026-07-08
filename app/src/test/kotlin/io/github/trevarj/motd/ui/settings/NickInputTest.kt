package io.github.trevarj.motd.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NickInputTest {

    @Test
    fun trims_surrounding_whitespace() {
        assertEquals("alice", sanitizeNickInput("  alice  "))
    }

    @Test
    fun blank_is_rejected() {
        assertNull(sanitizeNickInput(""))
        assertNull(sanitizeNickInput("   "))
    }

    @Test
    fun internal_whitespace_is_rejected() {
        assertNull(sanitizeNickInput("al ice"))
        assertNull(sanitizeNickInput("a\tb"))
    }

    @Test
    fun comma_is_rejected() {
        assertNull(sanitizeNickInput("alice,bob"))
    }

    @Test
    fun channel_sigils_are_rejected() {
        assertNull(sanitizeNickInput("#chan"))
        assertNull(sanitizeNickInput("&chan"))
    }

    @Test
    fun plain_nick_is_kept() {
        assertEquals("bob_", sanitizeNickInput("bob_"))
        assertEquals("[away]nick", sanitizeNickInput("[away]nick"))
    }
}
