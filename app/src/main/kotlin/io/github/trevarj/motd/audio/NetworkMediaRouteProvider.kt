package io.github.trevarj.motd.audio

import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.service.LocalSocksProvider
import io.github.trevarj.motd.service.PinningTrustManager
import io.github.trevarj.motd.service.resolveTransportProxy
import io.github.trevarj.motd.service.sameTlsHost
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

data class NetworkMediaRoute(
    val networkId: Long,
    val endpoint: NetworkEntity,
    val proxy: Proxy?,
    val proxyError: String?,
    val authorizationHeader: String?,
    val endpointPinnedSha256: String? = null,
    private val release: () -> Unit = {},
) : AutoCloseable {
    fun open(
        url: String,
        authenticated: Boolean = false,
    ): HttpURLConnection {
        proxyError?.let { throw IOException(it) }
        val parsedUrl = URL(url)
        val connection =
            if (proxy != null) {
                parsedUrl.openConnection(proxy)
            } else {
                parsedUrl.openConnection()
            } as HttpURLConnection
        val trustManager = pinningTrustManager(parsedUrl)
        if (connection is HttpsURLConnection && trustManager != null) {
            connection.sslSocketFactory =
                SSLContext
                    .getInstance("TLS")
                    .apply {
                        init(null, arrayOf(trustManager), null)
                    }.socketFactory
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        if (authenticated) {
            authorizationHeader?.let { connection.setRequestProperty("Authorization", it) }
        }
        return connection
    }

    /** Only this endpoint's approved leaf may bypass CA/hostname checks, including its filehost port. */
    internal fun pinningTrustManager(url: URL): PinningTrustManager? {
        val pin = endpointPinnedSha256 ?: return null
        if (!url.protocol.equals("https", ignoreCase = true) || !sameTlsHost(endpoint.host, url.host)) return null
        val port = url.port.takeIf { it >= 0 } ?: url.defaultPort
        return PinningTrustManager(url.host, port, pin)
    }

    override fun close() = release()
}

/** Narrow seam over [NetworkMediaRouteProvider] so HTTP repositories are testable without Room. */
fun interface MediaRouteResolver {
    suspend fun routeForNetwork(networkId: Long): NetworkMediaRoute?

    /** Preview traffic may use the direct device route only after the user's explicit privacy opt-in. */
    suspend fun routeForPreview(networkId: Long): NetworkMediaRoute? = routeForNetwork(networkId)
}

/**
 * Whether URL-only avatar/network-icon requests may use the device connection for one network.
 * Those legacy requests have no route tag and are withheld on obfuscated networks unless the user
 * explicitly opts in. Tagged chat media always follows its owning network, regardless of this policy.
 */
fun interface DirectMediaPolicy {
    suspend fun directMediaAllowed(networkId: Long): Boolean
}

@Singleton
class NetworkMediaRouteProvider
    @Inject
    constructor(
        private val db: MotdDatabase,
        private val localSocksProvider: LocalSocksProvider,
        private val certTrustStore: CertTrustStore,
        private val contentPreviewPrefs: ContentPreviewPrefs,
    ) : MediaRouteResolver,
        DirectMediaPolicy {
        override suspend fun routeForNetwork(networkId: Long): NetworkMediaRoute? = resolveRoute(networkId, directWhenOptedIn = false)

        override suspend fun routeForPreview(networkId: Long): NetworkMediaRoute? = resolveRoute(networkId, directWhenOptedIn = true)

        private suspend fun resolveRoute(
            networkId: Long,
            directWhenOptedIn: Boolean,
        ): NetworkMediaRoute? {
            val row = db.networkDao().byId(networkId) ?: return null
            val endpoint =
                if (row.role == NetworkRole.BOUNCER_CHILD) {
                    row.parentId?.let { db.networkDao().byId(it) } ?: return null
                } else {
                    row
                }
            val authorizationHeader =
                endpoint.basicAuthorizationHeader(
                    childNetworkSelector = row.bouncerNetId.takeIf { row.role == NetworkRole.BOUNCER_CHILD },
                )
            val endpointPinnedSha256 = certTrustStore.pinnedFor(endpoint.host, endpoint.port)
            val obfuscated = endpoint.obfsMode != null && endpoint.obfsMode != ObfsMode.NONE
            if (directWhenOptedIn && obfuscated && contentPreviewPrefs.config.first().directMediaOnProxiedNetworks) {
                return NetworkMediaRoute(
                    networkId = networkId,
                    endpoint = endpoint,
                    proxy = null,
                    proxyError = null,
                    authorizationHeader = authorizationHeader,
                    endpointPinnedSha256 = endpointPinnedSha256,
                )
            }
            val resolved = resolveTransportProxy(endpoint, localSocksProvider, ownerKey = "media-$networkId")
            return NetworkMediaRoute(
                networkId = networkId,
                endpoint = endpoint,
                proxy = resolved.proxy,
                proxyError = resolved.error,
                authorizationHeader = authorizationHeader,
                endpointPinnedSha256 = endpointPinnedSha256,
                release = resolved.release,
            )
        }

        override suspend fun directMediaAllowed(networkId: Long): Boolean {
            // Unknown networks always fail closed: the opt-in cannot rescue a fetch whose transport
            // policy we cannot even resolve.
            val row = db.networkDao().byId(networkId) ?: return false
            // A bouncer child shares its physical endpoint (and therefore its transport policy) with
            // the bouncer root, exactly as routeForNetwork does above.
            val endpoint =
                if (row.role == NetworkRole.BOUNCER_CHILD) {
                    row.parentId?.let { db.networkDao().byId(it) } ?: return false
                } else {
                    row
                }
            if (endpoint.obfsMode == null || endpoint.obfsMode == ObfsMode.NONE) return true
            // URL-only requests would fetch outside the tunnel. Permit them only after explicit opt-in.
            return contentPreviewPrefs.config.first().directMediaOnProxiedNetworks
        }
    }

/** Known IRC networks whose app-global media requests may use the device connection directly. */
internal fun directMediaAllowedNetworkIds(
    networks: List<NetworkEntity>,
    directMediaOnProxiedNetworks: Boolean,
): Set<Long> {
    val byId = networks.associateBy(NetworkEntity::id)
    return networks
        .asSequence()
        .filter { row ->
            val endpoint =
                if (row.role == NetworkRole.BOUNCER_CHILD) {
                    row.parentId?.let(byId::get) ?: return@filter false
                } else {
                    row
                }
            endpoint.obfsMode == null || endpoint.obfsMode == ObfsMode.NONE || directMediaOnProxiedNetworks
        }.mapTo(mutableSetOf(), NetworkEntity::id)
}

internal fun NetworkEntity.basicAuthorizationHeader(childNetworkSelector: String? = null): String? {
    if (!saslMechanism.equals("PLAIN", ignoreCase = true)) return null
    val baseUser =
        saslUser?.takeIf(String::isNotBlank)
            ?: username.takeIf(String::isNotBlank)
            ?: nick.takeIf(String::isNotBlank)
            ?: return null
    val user = childNetworkSelector?.takeIf(String::isNotBlank)?.let { "$baseUser/$it" } ?: baseUser
    val password = saslPassword?.takeIf(String::isNotBlank) ?: return null
    val token = Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
    return "Basic $token"
}
