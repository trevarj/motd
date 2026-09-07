package io.github.trevarj.motd.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * [PinningTrustManager.checkServerTrusted] — the code that decides whether CA validation is
 * bypassed (TOFU pinning). The pure decision table is covered by [CertDecisionTest]; this
 * covers the glue that reads the presented leaf and either short-circuits on the pin or delegates
 * to the platform trust manager.
 *
 * The leaf is a test-local [X509Certificate] subclass rather than a generated cert: the class only
 * reads the DER encoding, the two principals and the validity dates, and the project ships no
 * certificate-generation dependency (no BouncyCastle, no Conscrypt).
 */
class PinningTrustManagerTest {
    private val host = "irc.example"
    private val port = 6697

    private val subject = X500Principal("CN=irc.example, O=motd tests")
    private val issuer = X500Principal("CN=Untrusted Test CA, O=motd tests")
    private val notBefore = Date(1_700_000_000_000L)
    private val notAfter = Date(1_800_000_000_000L)

    private val leaf =
        FakeX509Certificate(
            der = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x2a),
            subject = subject,
            issuer = issuer,
            validFrom = notBefore,
            validTo = notAfter,
        )

    /** Expected fingerprint, computed here rather than via the production helper. */
    private val leafSha256 =
        MessageDigest
            .getInstance("SHA-256")
            .digest(leaf.encoded)
            .joinToString("") { "%02x".format(it) }

    private val untrustedCalls = mutableListOf<CertUntrustedException>()

    private fun manager(pinnedSha256: String?) =
        PinningTrustManager(
            host = host,
            port = port,
            pinnedSha256 = pinnedSha256,
            onUntrusted = { untrustedCalls += it },
        )

    @Test
    fun `pinned leaf matching the presented cert is accepted without delegating`() {
        // A pin bypasses CA/path validation, so this fake — which no platform anchor can chain to —
        // must still be accepted.
        manager(leafSha256).checkServerTrusted(arrayOf(leaf), "RSA")

        assertEquals(emptyList<CertUntrustedException>(), untrustedCalls)
    }

    @Test
    fun `pin comparison ignores case`() {
        manager(leafSha256.uppercase()).checkServerTrusted(arrayOf(leaf), "RSA")

        assertEquals(emptyList<CertUntrustedException>(), untrustedCalls)
    }

    @Test
    fun `host-aware pin validation accepts equivalent IPv6 forms but rejects another address`() {
        val configuredHost = "[2001:0DB8:0000:0000:0000:0000:0000:0001]"
        val trustManager = PinningTrustManager(configuredHost, port, leafSha256)

        assertEquals(listOf(leaf), trustManager.checkServerTrusted(arrayOf(leaf), "RSA", "2001:db8::1"))
        assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(arrayOf(leaf), "RSA", "2001:db8::2")
        }
        val wrongPin = PinningTrustManager(configuredHost, port, "0".repeat(64))
        val rejected =
            assertThrows(CertUntrustedException::class.java) {
                wrongPin.checkServerTrusted(arrayOf(leaf), "RSA", "2001:db8::1")
            }
        assertTrue(rejected.changed)
    }

    @Test
    fun `pinned leaf differing from the presented cert throws changed and notifies once`() {
        val pinned = "0".repeat(64)

        val thrown =
            assertThrows(CertUntrustedException::class.java) {
                manager(pinned).checkServerTrusted(arrayOf(leaf), "RSA")
            }

        assertTrue(thrown.changed)
        // onUntrusted fires exactly once, before the throw, with the very exception that propagates.
        assertEquals(1, untrustedCalls.size)
        assertSame(thrown, untrustedCalls.single())
    }

    @Test
    fun `untrusted exception carries the presented leaf details`() {
        val thrown =
            assertThrows(CertUntrustedException::class.java) {
                manager("0".repeat(64)).checkServerTrusted(arrayOf(leaf), "RSA")
            }

        assertEquals(host, thrown.host)
        assertEquals(port, thrown.port)
        assertEquals(leafSha256, thrown.sha256)
        assertEquals(subject.name, thrown.subject)
        assertEquals(issuer.name, thrown.issuer)
        assertEquals(notBefore.time, thrown.notBefore)
        assertEquals(notAfter.time, thrown.notAfter)
    }

    @Test
    fun `the leaf is the first chain entry, not a later one`() {
        val other =
            FakeX509Certificate(
                der = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x7f),
                subject = issuer,
                issuer = issuer,
                validFrom = notBefore,
                validTo = notAfter,
            )

        // Pinning the intermediate must not accept the chain: only the leaf is compared.
        val otherSha256 =
            MessageDigest
                .getInstance("SHA-256")
                .digest(other.encoded)
                .joinToString("") { "%02x".format(it) }

        val thrown =
            assertThrows(CertUntrustedException::class.java) {
                manager(otherSha256).checkServerTrusted(arrayOf(leaf, other), "RSA")
            }

        assertEquals(leafSha256, thrown.sha256)
        assertEquals(subject.name, thrown.subject)
    }

    @Test
    fun `null chain fails plainly without a TOFU prompt`() {
        val thrown =
            assertThrows(CertificateException::class.java) {
                manager(pinnedSha256 = null).checkServerTrusted(null, "RSA")
            }

        // A plain CertificateException, NOT a CertUntrustedException: an empty chain carries no
        // fingerprint to pin, so it must never surface a first-use trust prompt.
        assertFalse(thrown is CertUntrustedException)
        assertEquals("empty certificate chain", thrown.message)
        assertEquals(emptyList<CertUntrustedException>(), untrustedCalls)
    }

    @Test
    fun `empty chain fails plainly without a TOFU prompt`() {
        val thrown =
            assertThrows(CertificateException::class.java) {
                manager(leafSha256).checkServerTrusted(emptyArray(), "RSA")
            }

        assertFalse(thrown is CertUntrustedException)
        assertEquals("empty certificate chain", thrown.message)
        assertEquals(emptyList<CertUntrustedException>(), untrustedCalls)
    }

    @Test
    fun `unpinned cert that no platform anchor trusts prompts with changed false`() {
        // No pin: the platform default trust manager runs and rejects this self-signed fake, which
        // is the first-use case — a prompt, not a pin change.
        val thrown =
            assertThrows(CertUntrustedException::class.java) {
                manager(pinnedSha256 = null).checkServerTrusted(arrayOf(leaf), "RSA")
            }

        assertFalse(thrown.changed)
        assertEquals(leafSha256, thrown.sha256)
        assertEquals(host, thrown.host)
        assertEquals(port, thrown.port)
        assertEquals(1, untrustedCalls.size)
        assertSame(thrown, untrustedCalls.single())
    }

    @Test
    fun `accepted issuers come from the platform trust store`() {
        // Guards the assumption the unpinned test rests on: the delegate really is the JDK/platform
        // trust manager, so its rejection above is genuine CA/path validation.
        val issuers = manager(pinnedSha256 = null).acceptedIssuers

        assertNotNull(issuers)
        assertTrue("platform trust store has no anchors", issuers.isNotEmpty())
    }
}

/**
 * Minimal [X509Certificate] stand-in. Only the members [PinningTrustManager] reads carry real
 * values; the platform trust manager rejects it during path building, which is what the unpinned
 * test needs. The abstract remainder returns inert values rather than throwing so that JDK path
 * building fails as a certificate error instead of an unrelated runtime error.
 */
private class FakeX509Certificate(
    private val der: ByteArray,
    private val subject: X500Principal,
    private val issuer: X500Principal,
    private val validFrom: Date,
    private val validTo: Date,
) : X509Certificate() {
    override fun getEncoded(): ByteArray = der.copyOf()

    // X509Certificate derives these from the DER encoding by default; the fake encoding is not a
    // parseable certificate, so serve them directly.
    override fun getSubjectX500Principal(): X500Principal = subject

    override fun getIssuerX500Principal(): X500Principal = issuer

    override fun getNotBefore(): Date = Date(validFrom.time)

    override fun getNotAfter(): Date = Date(validTo.time)

    override fun getSubjectDN(): Principal = subject

    override fun getIssuerDN(): Principal = issuer

    override fun checkValidity() = Unit

    override fun checkValidity(date: Date) = Unit

    override fun getVersion(): Int = 3

    override fun getSerialNumber(): BigInteger = BigInteger.ONE

    override fun getTBSCertificate(): ByteArray = der.copyOf()

    override fun getSignature(): ByteArray = ByteArray(0)

    override fun getSigAlgName(): String = "SHA256withRSA"

    override fun getSigAlgOID(): String = "1.2.840.113549.1.1.11"

    override fun getSigAlgParams(): ByteArray? = null

    override fun getIssuerUniqueID(): BooleanArray? = null

    override fun getSubjectUniqueID(): BooleanArray? = null

    override fun getKeyUsage(): BooleanArray? = null

    override fun getBasicConstraints(): Int = -1

    override fun getPublicKey(): PublicKey = FakePublicKey

    override fun verify(key: PublicKey) = throw UnsupportedOperationException("fake certificate")

    override fun verify(
        key: PublicKey,
        sigProvider: String?,
    ) = throw UnsupportedOperationException("fake certificate")

    override fun toString(): String = "FakeX509Certificate(subject=${subject.name})"

    // X509Extension
    override fun hasUnsupportedCriticalExtension(): Boolean = false

    override fun getCriticalExtensionOIDs(): MutableSet<String> = mutableSetOf()

    override fun getNonCriticalExtensionOIDs(): MutableSet<String> = mutableSetOf()

    override fun getExtensionValue(oid: String): ByteArray? = null
}

private object FakePublicKey : PublicKey {
    override fun getAlgorithm(): String = "RSA"

    override fun getFormat(): String = "X.509"

    override fun getEncoded(): ByteArray = ByteArray(0)
}
