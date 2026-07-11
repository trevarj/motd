package io.github.trevarj.motd.ui.settings

import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
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
    fun `embedded REALITY persists only the VLESS link and never a proxy endpoint`() {
        val entity = buildNetworkEntity(
            server = ServerForm(host = "bnc.example.org", nick = "me"),
            auth = AuthForm(),
            role = NetworkRole.DIRECT,
            obfsMode = ObfsMode.EMBEDDED_REALITY,
            proxyHost = "127.0.0.1",
            proxyPort = 1080,
            obfsLink = "  vless://uuid@example:443?type=tcp  ",
        )

        assertEquals(ObfsMode.EMBEDDED_REALITY, entity.obfsMode)
        assertEquals("vless://uuid@example:443?type=tcp", entity.obfsLink)
        assertNull(entity.proxyHost)
        assertNull(entity.proxyPort)
    }

    @Test
    fun `soju root uses the collected nick, not the SASL username`() {
        // soju form now collects host/port/TLS + nick, plus SASL user/password for the bouncer.
        val entity = buildNetworkEntity(
            server = ServerForm(host = "bnc.example.org", port = "6697", tls = true, nick = "trev"),
            auth = AuthForm(mode = AuthMode.PLAIN, saslUser = "trevbnc", saslPassword = "secret"),
            role = NetworkRole.BOUNCER_ROOT,
        )
        assertEquals("PLAIN", entity.saslMechanism)
        assertEquals("trevbnc", entity.saslUser) // bouncer SASL login
        assertEquals("secret", entity.saslPassword)
        // NICK/USER on the root socket use the collected nick (not the SASL login user).
        assertEquals("trev", entity.nick)
        assertEquals("trev", entity.username) // soju ident derives from nick
        // soju: assume the login username is the real name.
        assertEquals("trevbnc", entity.realname)
        assertNull(entity.clientCertAlias)
    }

    @Test
    fun `soju root falls back to SASL user then placeholder when nick blank`() {
        // Defensive: if a nick somehow reaches building blank, prefer the SASL user, else "motd",
        // so the NICK/USER registration lines are never blank.
        val fromSasl = buildNetworkEntity(
            server = ServerForm(host = "bnc.example.org"),
            auth = AuthForm(mode = AuthMode.PLAIN, saslUser = "trevbnc", saslPassword = "p"),
            role = NetworkRole.BOUNCER_ROOT,
        )
        assertEquals("trevbnc", fromSasl.nick)

        val placeholder = buildNetworkEntity(
            server = ServerForm(host = "bnc.example.org"),
            auth = AuthForm(mode = AuthMode.PLAIN),
            role = NetworkRole.BOUNCER_ROOT,
        )
        assertEquals("motd", placeholder.nick)
    }

    @Test
    fun `soju forces sasl plain even when auth mode is none`() {
        // The collapsed soju form never surfaces a mechanism picker; PLAIN is derived from the role.
        val entity = buildNetworkEntity(
            server = ServerForm(host = "bnc.example.org", port = "6697", nick = "trev"),
            auth = AuthForm(mode = AuthMode.NONE, saslUser = "trevbnc", saslPassword = "secret"),
            role = NetworkRole.BOUNCER_ROOT,
        )
        assertEquals("PLAIN", entity.saslMechanism)
        assertEquals("trevbnc", entity.saslUser)
        assertEquals("secret", entity.saslPassword)
    }

    @Test
    fun `soju trims whitespace on host nick and username`() {
        val entity = buildNetworkEntity(
            server = ServerForm(host = "  bnc.example.org  ", port = "6697", nick = "  trev  "),
            auth = AuthForm(mode = AuthMode.PLAIN, saslUser = "  trevbnc  ", saslPassword = "secret"),
            role = NetworkRole.BOUNCER_ROOT,
        )
        assertEquals("bnc.example.org", entity.host)
        assertEquals("trev", entity.nick)
        assertEquals("trevbnc", entity.saslUser)
        assertEquals("trevbnc", entity.realname) // username assumed to be the real name
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
