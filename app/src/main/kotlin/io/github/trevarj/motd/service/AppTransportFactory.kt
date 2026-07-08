package io.github.trevarj.motd.service

import android.content.Context
import android.security.KeyChain
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.OkioLineTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager

/**
 * One persisted STS policy per host (plans/03): pins a TLS port and a `until` expiry (epoch ms).
 * Stored as JSON via the DataStore STS accessor.
 */
@Serializable
data class StsPolicy(val host: String, val port: Int, val until: Long)

/**
 * STS policy store over the DataStore `sts_policies` JSON blob (WP4's internal accessor).
 * Enforcement: rewrite (port, tls=true) before connect when a live policy exists.
 * Persistence: on Ready, parse the `sts=...` cap value and upsert a policy.
 */
class StsPolicyStore(private val prefs: DataStoreSettingsRepository) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun all(now: Long = System.currentTimeMillis()): Map<String, StsPolicy> {
        val raw = prefs.stsPolicies() ?: return emptyMap()
        val list = runCatching { json.decodeFromString<List<StsPolicy>>(raw) }.getOrDefault(emptyList())
        return list.filter { it.until > now }.associateBy { it.host.lowercase() }
    }

    suspend fun policyFor(host: String, now: Long = System.currentTimeMillis()): StsPolicy? =
        all(now)[host.lowercase()]

    suspend fun upsert(policy: StsPolicy) {
        val current = all().values.filterNot { it.host.equals(policy.host, ignoreCase = true) }
        prefs.setStsPolicies(json.encodeToString(current + policy))
    }

    /**
     * Parse an IRCv3 `sts` cap value (`duration=<s>[,port=<n>]`) into a policy for [host].
     * Applies only over TLS (a plaintext STS upgrade is a separate reconnect the caller performs);
     * returns null when no duration is present.
     */
    fun parse(host: String, capValue: String?, tls: Boolean, currentPort: Int, now: Long = System.currentTimeMillis()): StsPolicy? {
        if (capValue == null) return null
        val attrs = capValue.split(',').mapNotNull {
            val eq = it.indexOf('='); if (eq < 0) null else it.substring(0, eq) to it.substring(eq + 1)
        }.toMap()
        val duration = attrs["duration"]?.toLongOrNull() ?: return null
        if (!tls) return null
        val port = attrs["port"]?.toIntOrNull() ?: currentPort
        return StsPolicy(host = host, port = port, until = now + duration * 1000)
    }
}

/**
 * App-side [TransportFactory] wrapping [OkioLineTransport]. For TLS it always builds an
 * [SSLContext] pairing the optional Android KeyChain client-cert [KeyManager] (SASL EXTERNAL) with
 * a per-connection [PinningTrustManager] for TOFU leaf pinning (plans/12). It also rewrites
 * (port, tls) to satisfy any live STS policy before connecting.
 *
 * The pin is looked up (via [runBlocking], mirroring the STS lookup) at create() time. A pinned
 * host skips hostname verification (`verifyHostname = false`) because the exact-leaf pin is a
 * stronger guarantee and enables bare-IP / self-signed bouncer certs. Unpinned hosts keep full
 * CA + hostname validation, so Libera etc. stay prompt-free.
 */
class AppTransportFactory(
    private val appContext: Context,
    private val stsStore: StsPolicyStore,
    private val certStore: CertTrustStore,
    private val clientCertAlias: String?,
    /** Called from the handshake when an untrusted/changed leaf cert is hit; lets the connection
     *  layer publish a TOFU prompt even though IrcClient flattens the failure into a state string. */
    private val onCertUntrusted: (CertUntrustedException) -> Unit = {},
) : TransportFactory {
    override fun create(host: String, port: Int, tls: Boolean): IrcTransport {
        // STS enforcement: a live policy forces TLS on the pinned port.
        val policy = runBlocking { stsStore.policyFor(host) }
        val effTls = tls || policy != null
        val effPort = policy?.port ?: port
        if (!effTls) return OkioLineTransport(host, effPort, tls = false)

        val pinned = runBlocking { certStore.pinnedFor(host, effPort) }
        val trustManager = PinningTrustManager(host, effPort, pinned, onCertUntrusted)
        val sslContext = buildTlsContext(clientCertAlias, trustManager)
        // Pinned leaf → skip hostname verification (bare-IP certs); unpinned → enforce it.
        return OkioLineTransport(host, effPort, tls = true, sslContext = sslContext, verifyHostname = pinned == null)
    }

    /** SSLContext with the optional KeyChain client-cert KeyManager + the pinning trust manager. */
    private fun buildTlsContext(alias: String?, trustManager: TrustManager): SSLContext {
        val keyManagers: Array<KeyManager>? = alias?.let {
            val km = KeyChainKeyManager(appContext, it).also { m -> m.resolve() }
            arrayOf(km)
        }
        return SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf(trustManager), null) }
    }
}

/**
 * [X509KeyManager] resolving the private key + chain from the Android KeyChain by alias. KeyChain
 * calls are blocking; [resolve] is invoked on the transport's IO connect path before handshake.
 */
private class KeyChainKeyManager(private val ctx: Context, private val alias: String) : X509KeyManager {
    @Volatile private var resolvedKey: PrivateKey? = null
    @Volatile private var resolvedChain: Array<X509Certificate>? = null

    fun resolve() {
        resolvedKey = runCatching { KeyChain.getPrivateKey(ctx, alias) }.getOrNull()
        resolvedChain = runCatching { KeyChain.getCertificateChain(ctx, alias) }.getOrNull()
    }

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = alias
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(alias)
    override fun getPrivateKey(alias: String): PrivateKey? = resolvedKey
    override fun getCertificateChain(alias: String): Array<X509Certificate>? = resolvedChain
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
}
