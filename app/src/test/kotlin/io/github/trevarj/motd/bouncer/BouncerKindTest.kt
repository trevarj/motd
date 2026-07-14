package io.github.trevarj.motd.bouncer

import io.github.trevarj.motd.ui.onboarding.AuthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BouncerKindTest {
    @Test
    fun `ZNC login builds username slash network PLAIN credentials`() {
        val login = ZncLoginForm(" motd ", " libera ", "secret")
        assertTrue(login.isValid)
        assertEquals("motd/libera", login.authcid)
        assertEquals(AuthMode.PLAIN, login.toAuthForm().mode)
        assertEquals("motd/libera", login.toAuthForm().saslUser)
    }

    @Test
    fun `ZNC login rejects blank and slash-bearing components`() {
        assertFalse(ZncLoginForm().isValid)
        assertFalse(ZncLoginForm("motd/libera", "libera", "secret").isValid)
        assertFalse(ZncLoginForm("motd", "libera/test", "secret").isValid)
    }

    @Test
    fun `persisted ZNC login parses safely`() {
        assertEquals(ZncLoginForm("motd", "libera", "secret"), parseZncLogin("motd/libera", "secret"))
        assertEquals(ZncLoginForm(username = "malformed", password = "secret"), parseZncLogin("malformed", "secret"))
    }
}
