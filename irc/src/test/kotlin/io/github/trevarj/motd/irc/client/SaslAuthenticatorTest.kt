package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.proto.IrcMessage
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

class SaslAuthenticatorTest {
    @Test
    fun `soju network selector is authcid with empty authzid`() {
        val auth = SaslAuthenticator(SaslMechanism.PLAIN, "motd/libera", "secret")
        val step = auth.onMessage(IrcMessage(command = "AUTHENTICATE", params = listOf("+")))
            as SaslAuthenticator.Step.Send

        val encoded = step.lines.single().removePrefix("AUTHENTICATE ")
        assertEquals("\u0000motd/libera\u0000secret", String(Base64.getDecoder().decode(encoded)))
    }

    @Test
    fun `ordinary account keeps authzid compatibility`() {
        val auth = SaslAuthenticator(SaslMechanism.PLAIN, "motd", "secret")
        val step = auth.onMessage(IrcMessage(command = "AUTHENTICATE", params = listOf("+")))
            as SaslAuthenticator.Step.Send

        val encoded = step.lines.single().removePrefix("AUTHENTICATE ")
        assertEquals("motd\u0000motd\u0000secret", String(Base64.getDecoder().decode(encoded)))
    }
}
