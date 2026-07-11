package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.irc.client.SaslMechanism
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [buildChildConfig] (#40). A soju BOUNCER_CHILD is a *bound connection to the
 * bouncer*, not a direct socket to the upstream network: it must connect on the bouncer's
 * host/port/tls and authenticate with the root account's SASL credentials using soju's
 * `account/network` authcid form.
 */
class BuildChildConfigTest {

    private fun root() = NetworkEntity(
        id = 1,
        name = "soju",
        role = NetworkRole.BOUNCER_ROOT,
        host = "bouncer.example.org",
        port = 6697,
        tls = true,
        nick = "motd",
        username = "motd",
        realname = "MOTD",
        saslMechanism = SaslMechanism.PLAIN.name,
        saslUser = "motd",
        saslPassword = "s3cret",
    )

    /** Child materialized from a soju BOUNCER NETWORK notify: carries the UPSTREAM host/port. */
    private fun child() = NetworkEntity(
        id = 2,
        name = "libera",
        role = NetworkRole.BOUNCER_CHILD,
        parentId = 1,
        bouncerNetId = "7",
        // soju's BOUNCER NETWORK attrs report the upstream server here — the bug source.
        host = "irc.libera.chat",
        port = 6697,
        tls = true,
        nick = "motd",
        username = "motd",
        realname = "MOTD",
        // A wrongly-mirrored child could even carry NO SASL; the fix pulls creds from the root.
        saslMechanism = SaslMechanism.NONE.name,
        saslUser = null,
        saslPassword = null,
    )

    @Test
    fun `child connects to the bouncer endpoint, not the upstream host`() {
        val cfg = buildChildConfig(child(), root())
        assertEquals("bouncer.example.org", cfg.host)
        assertEquals(6697, cfg.port)
        assertEquals(true, cfg.tls)
    }

    @Test
    fun `child authenticates with the root account SASL creds`() {
        val cfg = buildChildConfig(child(), root())
        assertEquals(SaslMechanism.PLAIN, cfg.sasl)
        assertEquals("motd/libera", cfg.saslUser)
        assertEquals("s3cret", cfg.saslPassword)
    }

    @Test
    fun `child selects upstream network via SASL authcid not BOUNCER BIND`() {
        val cfg = buildChildConfig(child(), root())
        assertNull(cfg.bouncerNetId)
    }

    @Test
    fun `child keeps its own nick and username identity`() {
        val cfg = buildChildConfig(child().copy(nick = "trev", username = "trev"), root())
        assertEquals("trev", cfg.nick)
        assertEquals("trev", cfg.username)
    }

    @Test
    fun `direct network uses its own fields and no bind`() {
        val direct = child().copy(role = NetworkRole.DIRECT, parentId = null, bouncerNetId = null)
        val cfg = buildChildConfig(direct, root = null)
        assertEquals("irc.libera.chat", cfg.host)
        assertNull(cfg.bouncerNetId)
    }

    @Test
    fun `orphan child with unresolved root falls back to its own fields`() {
        // Defensive: reconcile excludes orphan children, but buildChildConfig must not crash.
        val cfg = buildChildConfig(child(), root = null)
        assertEquals("irc.libera.chat", cfg.host)
        assertNull(cfg.bouncerNetId)
    }

    // -- WSS transport threading (plans/19 §3.3) ------------------------------

    @Test
    fun `direct network threads its own wsUrl into the config`() {
        val direct = child().copy(
            role = NetworkRole.DIRECT, parentId = null, bouncerNetId = null,
            wsUrl = "wss://irc.example.org:443/",
        )
        val cfg = buildChildConfig(direct, root = null)
        assertEquals("wss://irc.example.org:443/", cfg.wsUrl)
    }

    @Test
    fun `child inherits the bouncer root's wsUrl, not its own`() {
        // The physical socket is the bouncer's, so the WSS URL follows the root endpoint.
        val cfg = buildChildConfig(
            child().copy(wsUrl = "wss://upstream.example:443/"),
            root().copy(wsUrl = "wss://bnc.example.org:443/"),
        )
        assertEquals("wss://bnc.example.org:443/", cfg.wsUrl)
    }

    @Test
    fun `null wsUrl leaves the config on the default TCP transport`() {
        val cfg = buildChildConfig(child(), root())
        assertNull(cfg.wsUrl)
    }

    @Test
    fun `fingerprint changes when wsUrl changes so the actor restarts`() {
        val base = root()
        val fp1 = networkFingerprint(base)
        val fp2 = networkFingerprint(base.copy(wsUrl = "wss://bnc.example.org:443/"))
        assertNotEquals(fp1, fp2)
        // Same wsUrl -> stable fingerprint (no spurious restart).
        assertEquals(fp2, networkFingerprint(base.copy(wsUrl = "wss://bnc.example.org:443/")))
    }

    @Test
    fun `child fingerprint follows every inherited root transport field`() {
        val child = child()
        val root = root()
        val baseline = networkFingerprint(child, root)

        val rootTransportChanges = listOf(
            root.copy(host = "new-bouncer.example.org"),
            root.copy(port = 443),
            root.copy(tls = false),
            root.copy(saslMechanism = SaslMechanism.EXTERNAL.name),
            root.copy(saslUser = "other-account"),
            root.copy(saslPassword = "changed-secret"),
            root.copy(clientCertAlias = "client-cert"),
            root.copy(wsUrl = "wss://bnc.example.org:443/"),
            root.copy(obfsMode = ObfsMode.SOCKS5, proxyHost = "127.0.0.1", proxyPort = 1080),
            root.copy(obfsMode = ObfsMode.TOR),
        )

        rootTransportChanges.forEach { changedRoot ->
            assertNotEquals(baseline, networkFingerprint(child, changedRoot))
        }
        assertEquals(baseline, networkFingerprint(child, root))
    }
}
