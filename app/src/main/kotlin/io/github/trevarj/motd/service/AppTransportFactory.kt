package io.github.trevarj.motd.service

import android.content.Context
import android.security.KeyChain
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
 * App-side [TransportFactory] wrapping [OkioLineTransport]. Injects an [SSLContext] carrying the
 * network's Android KeyChain client certificate (for SASL EXTERNAL), and rewrites (port, tls) to
 * satisfy any live STS policy for the host before connecting.
 */
class AppTransportFactory(
    private val appContext: Context,
    private val stsStore: StsPolicyStore,
    private val clientCertAlias: String?,
) : TransportFactory {
    override fun create(host: String, port: Int, tls: Boolean): IrcTransport {
        // STS enforcement: a live policy forces TLS on the pinned port.
        val policy = runBlocking { stsStore.policyFor(host) }
        val effTls = tls || policy != null
        val effPort = policy?.port ?: port
        val sslContext = if (effTls && clientCertAlias != null) buildClientCertContext(clientCertAlias) else null
        return OkioLineTransport(host, effPort, effTls, sslContext)
    }

    /** SSLContext with a KeyManager exposing the KeyChain client cert; default trust managers. */
    private fun buildClientCertContext(alias: String): SSLContext? = runCatching {
        val km = KeyChainKeyManager(appContext, alias).also { it.resolve() }
        val keyManagers: Array<KeyManager> = arrayOf(km)
        SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
    }.getOrNull()
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
