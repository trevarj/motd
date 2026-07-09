package io.github.trevarj.motd.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the "trust reconnects ALL networks sharing a cert endpoint" behavior (#48).
 *
 * When several networks are parked in a cert-untrusted Failed state on the *same* self-signed
 * host:port — a soju bouncer root plus every bound child tunnels through one physical endpoint —
 * trusting the cert once pins it keyed by host:port and must reconnect the whole set, not only the
 * network whose prompt was shown. [networksSharingCertEndpoint] is the pure seam that
 * [ConnectionManagerImpl.trustCert] iterates to force-reconnect each affected network via
 * `connect(id)`. Same pure-function testing style as [ConnectionIntentsTest] / [BuildChildConfigTest].
 */
class TrustReconnectTest {

    private fun failure(
        host: String = "104.168.59.26",
        port: Int = 443,
        sha256: String = "aa",
        changed: Boolean = false,
    ) = CertUntrustedException(
        host = host,
        port = port,
        sha256 = sha256,
        subject = "CN=$host",
        issuer = "CN=$host",
        notBefore = 0L,
        notAfter = 0L,
        changed = changed,
    )

    @Test
    fun `root and its children on one endpoint are all reconnected`() {
        // Root (id 1) and two bound children (2, 3) all failed on the bouncer's 104.168.59.26:443.
        val certFailures = mapOf(
            1L to failure(),
            2L to failure(),
            3L to failure(),
        )
        assertEquals(setOf(1L, 2L, 3L), networksSharingCertEndpoint("104.168.59.26", 443, certFailures))
    }

    @Test
    fun `only networks on the same host and port are included`() {
        val certFailures = mapOf(
            1L to failure(host = "104.168.59.26", port = 443),  // matches
            2L to failure(host = "104.168.59.26", port = 6697), // same host, different port
            3L to failure(host = "irc.other.net", port = 443),  // different host
        )
        assertEquals(setOf(1L), networksSharingCertEndpoint("104.168.59.26", 443, certFailures))
    }

    @Test
    fun `host match is case-insensitive to mirror the pin key`() {
        // The cert store lowercases the host in its pin key, so matching must ignore case too.
        val certFailures = mapOf(1L to failure(host = "Bouncer.Example.Org"))
        assertEquals(
            setOf(1L),
            networksSharingCertEndpoint("bouncer.example.org", 443, certFailures),
        )
    }

    @Test
    fun `empty when no network is parked on the endpoint`() {
        assertEquals(
            emptySet<Long>(),
            networksSharingCertEndpoint("104.168.59.26", 443, emptyMap()),
        )
    }
}
