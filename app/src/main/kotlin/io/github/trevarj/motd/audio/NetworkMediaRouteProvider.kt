package io.github.trevarj.motd.audio

import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.service.LocalSocksProvider
import io.github.trevarj.motd.service.PinningTrustManager
import io.github.trevarj.motd.service.resolveTransportProxy
import io.github.trevarj.motd.service.sameTlsHost
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
}

@Singleton
class NetworkMediaRouteProvider
    @Inject
    constructor(
        private val db: MotdDatabase,
        private val localSocksProvider: LocalSocksProvider,
        private val certTrustStore: CertTrustStore,
    ) : MediaRouteResolver {
        override suspend fun routeForNetwork(networkId: Long): NetworkMediaRoute? {
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
