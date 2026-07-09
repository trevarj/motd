package io.github.trevarj.motd.ui.settings

import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.client.SaslMechanism
import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.AuthMode
import io.github.trevarj.motd.ui.onboarding.ServerForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Field-matrix coverage for [buildNetworkEntity] across the connection variants. */
class NetworkFormTest {

    @Test
    fun `soju root seeds identity from the SASL username`() {
        // soju form collects only host/port/TLS + SASL user/password; nick/username/realname blank.
        val entity = buildNetworkEntity(
            server = ServerForm(host = "bnc.example.org", port = "6697", tls = true),
            auth = AuthForm(mode = AuthMode.PLAIN, saslUser = "trev", saslPassword = "secret"),
            role = NetworkRole.BOUNCER_ROOT,
        )
        assertEquals("PLAIN", entity.saslMechanism)
        assertEquals("trev", entity.saslUser)
        assertEquals("secret", entity.saslPassword)
        // USER/NICK still sent on the root socket -> seed identity from the SASL login username.
        assertEquals("trev", entity.nick)
        assertEquals("trev", entity.username)
        assertEquals("trev", entity.realname)
        assertNull(entity.clientCertAlias)
    }

    @Test
    fun `soju root falls back to placeholder identity when no SASL user`() {
        val entity = buildNetworkEntity(
            server = ServerForm(host = "bnc.example.org"),
            auth = AuthForm(mode = AuthMode.PLAIN),
            role = NetworkRole.BOUNCER_ROOT,
        )
        // No nick, no SASL user -> non-blank placeholder so USER/NICK stay well-formed.
        assertEquals("motd", entity.nick)
        assertEquals("motd", entity.username)
        assertEquals("motd", entity.realname)
    }

    @Test
    fun `direct none defaults username and realname to nick`() {
        val entity = buildNetworkEntity(
            server = ServerForm(host = "irc.libera.chat", nick = "me"),
            auth = AuthForm(mode = AuthMode.NONE),
            role = NetworkRole.DIRECT,
        )
        assertEquals(SaslMechanism.NONE.name, entity.saslMechanism)
        assertEquals("me", entity.nick)
        assertEquals("me", entity.username) // ident defaults to nick
        assertEquals("me", entity.realname)
        assertNull(entity.saslUser)
        assertNull(entity.saslPassword)
        assertNull(entity.clientCertAlias)
    }

    @Test
    fun `direct plain keeps distinct ident and SASL account`() {
        // USER ident (username) and SASL account (saslUser) are distinct in IRC; keep both.
        val entity = buildNetworkEntity(
            server = ServerForm(host = "irc.libera.chat", nick = "me", username = "identd"),
            auth = AuthForm(mode = AuthMode.PLAIN, saslUser = "account", saslPassword = "pw"),
            role = NetworkRole.DIRECT,
        )
        assertEquals("identd", entity.username)
        assertEquals("account", entity.saslUser)
        assertEquals("pw", entity.saslPassword)
        assertEquals("PLAIN", entity.saslMechanism)
    }

    @Test
    fun `direct external persists cert alias and no password`() {
        val entity = buildNetworkEntity(
            server = ServerForm(host = "irc.libera.chat", nick = "me"),
            auth = AuthForm(mode = AuthMode.EXTERNAL, certAlias = "my-cert"),
            role = NetworkRole.DIRECT,
        )
        assertEquals("EXTERNAL", entity.saslMechanism)
        assertEquals("my-cert", entity.clientCertAlias)
        assertNull(entity.saslUser)
        assertNull(entity.saslPassword)
    }
}
