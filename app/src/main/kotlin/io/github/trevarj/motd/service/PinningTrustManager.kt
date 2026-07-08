package io.github.trevarj.motd.service

import android.annotation.SuppressLint
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Handshake failure carrying the presented leaf-cert details so the connection layer can surface a
 * TOFU [CertPrompt]. Extends [CertificateException] so it propagates as the TLS handshake cause.
 * [changed] = true when a previously-pinned cert now differs (possible MITM / rotation).
 */
class CertUntrustedException(
    val host: String,
    val port: Int,
    val sha256: String,          // lowercase hex of the presented leaf
    val subject: String,
    val issuer: String,
    val notBefore: Long,         // epoch ms
    val notAfter: Long,          // epoch ms
    val changed: Boolean,
) : CertificateException(
    "Untrusted certificate for $host:$port (sha256=$sha256, changed=$changed)",
)

/** Outcome of the pure TOFU pin decision (plans/12). */
enum class CertDecision { TRUST, PROMPT, CHANGED }

/**
 * Pure TOFU decision over a stored pin and a presented leaf fingerprint. Isolated from the
 * [X509Certificate] / platform-trust glue so it is directly unit-testable:
 *  - no pin, cert not CA-valid → PROMPT (first-use trust request)
 *  - no pin, cert CA-valid     → TRUST (normal path; caller still enforces hostname)
 *  - pin matches               → TRUST
 *  - pin differs               → CHANGED (warn / re-prompt)
 */
fun certDecision(pinned: String?, presentedSha256: String, caValid: Boolean): CertDecision =
    when {
        pinned == null -> if (caValid) CertDecision.TRUST else CertDecision.PROMPT
        pinned.equals(presentedSha256, ignoreCase = true) -> CertDecision.TRUST
        else -> CertDecision.CHANGED
    }

/**
 * Per-connection [X509TrustManager] realizing TOFU leaf pinning (plans/12). Built by
 * [AppTransportFactory] with the host/port and the pin looked up at create() time.
 *
 * checkServerTrusted:
 *  - pinned & matches  → accept.
 *  - pinned & differs  → throw CertUntrustedException(changed = true).
 *  - no pin            → delegate to the platform default trust manager; on its
 *    CertificateException throw CertUntrustedException(changed = false) carrying the cert details;
 *    on success accept (normal CA-valid path — hostname is still enforced upstream when unpinned).
 */
// CustomX509TrustManager: a custom trust manager is intentional and required for TOFU leaf pinning
// (plans/12). Unpinned hosts are NOT weakened — validation is delegated to the platform default
// X509TrustManager; only an explicit user-pinned leaf fingerprint bypasses CA/path checks, which is
// a strictly stronger guarantee. It never blindly accepts certificates.
@SuppressLint("CustomX509TrustManager")
class PinningTrustManager(
    private val host: String,
    private val port: Int,
    private val pinnedSha256: String?,
    /**
     * Invoked with the exception just before it is thrown, so the connection layer can recover the
     * cert details even though IrcClient collapses the handshake failure into a Failed-state
     * string. IrcClient is pure JVM and must not depend on this app type.
     */
    private val onUntrusted: (CertUntrustedException) -> Unit = {},
) : X509TrustManager {

    // Platform default trust manager for the CA-valid delegation path (unpinned hosts).
    private val delegate: X509TrustManager = run {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("empty certificate chain")
        val presented = sha256Hex(leaf)

        if (pinnedSha256 != null) {
            if (pinnedSha256.equals(presented, ignoreCase = true)) return // pinned & matches
            throw untrusted(leaf, presented, changed = true)              // pinned & differs
        }
        // No pin: honor the platform trust anchors; only prompt when CA/path validation fails.
        try {
            delegate.checkServerTrusted(chain, authType)
        } catch (_: CertificateException) {
            throw untrusted(leaf, presented, changed = false)
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        delegate.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    private fun untrusted(leaf: X509Certificate, sha256: String, changed: Boolean): CertUntrustedException {
        val ex = CertUntrustedException(
            host = host,
            port = port,
            sha256 = sha256,
            subject = leaf.subjectX500Principal.name,
            issuer = leaf.issuerX500Principal.name,
            notBefore = leaf.notBefore.time,
            notAfter = leaf.notAfter.time,
            changed = changed,
        )
        onUntrusted(ex)
        return ex
    }

    companion object {
        /** Lowercase hex SHA-256 of a cert's DER encoding (the pinned leaf fingerprint). */
        fun sha256Hex(cert: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
