package io.github.trevarj.motd.service

import android.content.Context
import android.security.KeyChain
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.OkioLineTransport
import io.github.trevarj.motd.irc.transport.TransportConfigurationException
import io.github.trevarj.motd.irc.transport.TransportFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

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
 * Certificate and STS policy reads are resolved suspendingly before this synchronous transport
 * boundary is created. A pinned
 * host skips hostname verification (`verifyHostname = false`) because the exact-leaf pin is a
 * stronger guarantee and enables bare-IP / self-signed bouncer certs. Unpinned hosts keep full
 * CA + hostname validation, so Libera etc. stay prompt-free.
 */
class AppTransportFactory(
    private val appContext: Context,
    private val security: PreparedTransportSecurity,
    private val clientCertAlias: String?,
    /** Called from the handshake when an untrusted/changed leaf cert is hit; lets the connection
     *  layer publish a TOFU prompt even though IrcClient flattens the failure into a state string. */
    private val onCertUntrusted: (CertUntrustedException) -> Unit = {},
    /**
     * Optional SOCKS5 proxy to tunnel through (plans/19 §3.4, plans/20 Phase 1). Built per-network
     * by [ConnectionManagerImpl] from the row's obfsMode/proxyHost/proxyPort and captured here (like
     * [clientCertAlias]) so it need not thread through IrcClient. STS/pinning/hostname logic is
     * untouched: it still keys on the REAL host:port, which the proxy resolves and reaches remotely.
     */
    private val proxy: Proxy? = null,
    /**
     * A persisted proxy mode was enabled but could not be converted to a usable SOCKS endpoint.
     * This is kept separate from [proxy] so a malformed setting cannot silently become a direct
     * connection. The failure is returned from [IrcTransport.connect], where [IrcClient] already
     * maps connection errors onto its visible failed state.
     */
    private val proxyConfigurationError: String? = null,
) : TransportFactory {
    override fun create(host: String, port: Int, tls: Boolean, wsUrl: String?, proxy: Proxy?): IrcTransport {
        // The per-network proxy captured at construction is the source of truth; the create() param
        // (unused by IrcClient, present for the fun-interface) takes precedence if a caller supplies one.
        val effProxy = proxy ?: this.proxy

        transportConfigurationError(wsUrl, effProxy, proxyConfigurationError)?.let {
            return ConfigurationFailureTransport(it)
        }

        // Opt-in IRC-over-WebSocket transport (plans/19 §3.3). When a wsUrl is configured the
        // physical connection is a WebSocket to that URL instead of a raw TCP/TLS socket; TLS,
        // pinning, and hostname verification still key on the wsUrl's REAL host/port.
        if (!wsUrl.isNullOrBlank()) return createWs(wsUrl)

        // STS enforcement: a live policy forces TLS on the pinned port.
        val policy = security.stsPolicy
        val effTls = tls || policy != null
        val effPort = policy?.port ?: port
        if (!effTls) return OkioLineTransport(host, effPort, tls = false, proxy = effProxy)

        val pinned = security.tcpPin
        val trustManager = PinningTrustManager(host, effPort, pinned, onCertUntrusted)
        val sslContext = buildTlsContext(clientCertAlias, trustManager)
        // Pinned leaf → skip hostname verification (bare-IP certs); unpinned → enforce it. The proxy
        // (if any) tunnels the connection; TLS/SNI/pin still key on the real host:port through it.
        return OkioLineTransport(host, effPort, tls = true, sslContext = sslContext, verifyHostname = pinned == null, proxy = effProxy)
    }

    /**
     * Build a [WsLineTransport] for [wsUrl] (`wss://host:443/`). Reuses the exact TLS stack of the
     * TCP path: an [SSLContext] pairing the client-cert [KeyManager] with a [PinningTrustManager]
     * keyed on the WS URL's real host/port, and hostname verification that a pinned leaf skips
     * (matching `verifyHostname = false` above). Plaintext `ws://`/`ws+insecure://` (behind a
     * reverse proxy terminating TLS) connects without an SSL stack.
     */
    private fun createWs(wsUrl: String): IrcTransport {
        // OkHttp's HttpUrl parses ws/wss and fills the default port (443 for wss, 80 for ws).
        val (wsHost, wsPort) = wsEndpoint(wsUrl)
        val secure = wsUrl.startsWith("wss://")
        if (!secure) return WsLineTransport(url = wsUrl)

        val pinned = security.wsPin
        val trustManager = PinningTrustManager(wsHost, wsPort, pinned, onCertUntrusted)
        val sslContext = buildTlsContext(clientCertAlias, trustManager)
        // Pinned leaf → skip hostname verification (bare-IP/self-signed bouncer certs), mirroring the
        // TCP path's verifyHostname=false; the exact-leaf pin is the stronger guarantee. Unpinned →
        // pass null so OkHttp keeps its default verifier (full CA + hostname).
        val verifier: HostnameVerifier? =
            if (pinned == null) null else HostnameVerifier { _, _ -> true }
        return WsLineTransport(
            url = wsUrl,
            sslSocketFactory = sslContext.socketFactory,
            trustManager = trustManager as X509TrustManager,
            hostnameVerifier = verifier,
        )
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

/** Immutable policy snapshot prepared before entering [TransportFactory]'s synchronous API. */
data class PreparedTransportSecurity(
    val stsPolicy: StsPolicy?,
    val tcpPin: String?,
    val wsPin: String?,
)

suspend fun prepareTransportSecurity(
    host: String,
    port: Int,
    wsUrl: String?,
    policyFor: suspend (String) -> StsPolicy?,
    pinnedFor: suspend (String, Int) -> String?,
): PreparedTransportSecurity {
    if (!wsUrl.isNullOrBlank()) {
        val wsPin = wsUrl.takeIf { it.startsWith("wss://") }?.let { secureUrl ->
            val (wsHost, wsPort) = wsEndpoint(secureUrl)
            pinnedFor(wsHost, wsPort)
        }
        return PreparedTransportSecurity(stsPolicy = null, tcpPin = null, wsPin = wsPin)
    }
    val policy = policyFor(host)
    val tcpPin = pinnedFor(host, policy?.port ?: port)
    return PreparedTransportSecurity(policy, tcpPin, wsPin = null)
}

/** OkHttp-backed endpoint parsing plus a defensive fallback for unusual persisted URLs. */
internal fun wsEndpoint(wsUrl: String): Pair<String, Int> {
    val httpUrl = wsUrl.replaceFirst("wss://", "https://")
        .replaceFirst("ws+insecure://", "http://")
        .replaceFirst("ws://", "http://")
        .toHttpUrlOrNull()
    val host = httpUrl?.host ?: wsUrl.substringAfter("://").substringBefore('/').substringBefore(':')
    val port = httpUrl?.port ?: if (wsUrl.startsWith("wss://")) 443 else 80
    return host to port
}

/** Orbot's default local SOCKS5 endpoint, used by the TOR obfs shortcut (plans/19 §3.4). */
const val ORBOT_SOCKS_HOST = "127.0.0.1"
const val ORBOT_SOCKS_PORT = 9050

/**
 * Build the [Proxy] a network row's obfuscation config implies (plans/19 §3.4, plans/20 Phase 1),
 * or null for a direct connection. Pure so [ConnectionManagerImpl] can resolve it and the unit test
 * can assert the exact [Proxy] built.
 *
 * - `null`/`NONE` → null (direct).
 * - `SOCKS5`/`EMBEDDED_REALITY` → SOCKS5 at the given [proxyHost]:[proxyPort]. EMBEDDED_REALITY maps
 *   to a plain SOCKS5 for a locally-run sing-box client; the in-app core just
 *   supplies a loopback host/port through the same field.
 * - `TOR` → SOCKS5 pinned at Orbot's 127.0.0.1:9050 (host/port ignored).
 *
 * The destination is left UNRESOLVED (`createUnresolved`) so DNS is performed remotely by the proxy
 * (leak-free, `.onion`-capable) — the same rule [OkioLineTransport] applies when dialing.
 */
fun proxyForNetwork(obfsMode: ObfsMode?, proxyHost: String?, proxyPort: Int?): Proxy? = when (obfsMode) {
    null, ObfsMode.NONE -> null
    ObfsMode.TOR -> Proxy(
        Proxy.Type.SOCKS,
        InetSocketAddress.createUnresolved(ORBOT_SOCKS_HOST, ORBOT_SOCKS_PORT),
    )
    ObfsMode.SOCKS5, ObfsMode.EMBEDDED_REALITY -> {
        val host = proxyHost?.trim()?.ifBlank { null }
        // No usable host/port → treat as direct (defensive; the UI keeps host/port populated).
        if (host == null || proxyPort == null || proxyPort !in 1..65535) {
            null
        } else {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, proxyPort))
        }
    }
}

/**
 * Validate an enabled persisted proxy mode before a connection is built. This must be checked
 * alongside [proxyForNetwork]: null is a legitimate direct setting only for `NONE`, never for an
 * invalid enabled proxy. Keeping the error as data lets [AppTransportFactory] surface it through
 * the normal `IrcClientState.Failed` path instead of throwing while the connection actor is built.
 */
internal fun proxyConfigurationErrorForNetwork(
    obfsMode: ObfsMode?,
    proxyHost: String?,
    proxyPort: Int?,
): String? = when (obfsMode) {
    null, ObfsMode.NONE, ObfsMode.TOR -> null
    ObfsMode.SOCKS5, ObfsMode.EMBEDDED_REALITY -> when {
        proxyHost.isNullOrBlank() -> "SOCKS5 proxy host is required"
        proxyPort == null || proxyPort !in 1..65535 -> "SOCKS5 proxy port must be between 1 and 65535"
        else -> null
    }
}

/**
 * WSS is currently implemented by OkHttp without proxy plumbing. Selecting both transports used
 * to discard [proxy] and dial WSS directly, which is a censorship-bypass and DNS-leak hazard.
 */
internal fun transportConfigurationError(
    wsUrl: String?,
    proxy: Proxy?,
    proxyConfigurationError: String?,
): String? = proxyConfigurationError ?: if (!wsUrl.isNullOrBlank() && proxy != null) {
    "WebSocket transport cannot be used with a SOCKS5 or Tor proxy"
} else {
    null
}

/** A deferred configuration error: [connect] throws inside IrcClient's existing failure boundary. */
private class ConfigurationFailureTransport(private val reason: String) : IrcTransport {
    override suspend fun connect(): Nothing = throw TransportConfigurationException(reason)

    override val incoming = kotlinx.coroutines.flow.emptyFlow<String>()

    override suspend fun send(line: String): Nothing = throw TransportConfigurationException(reason)

    override suspend fun close() = Unit
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
