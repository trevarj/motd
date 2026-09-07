package io.github.trevarj.motd.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.staticCompositionLocalOf
import coil.request.CachePolicy
import coil.request.ImageRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.audio.NetworkMediaHttp
import io.github.trevarj.motd.audio.networkMediaData
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

enum class RemoteMediaNetwork {
    UNMETERED,
    METERED,
    UNAVAILABLE,
}

internal fun automaticRemoteMediaAllowed(
    network: RemoteMediaNetwork,
    config: ContentPreviewConfig,
): Boolean =
    when (network) {
        RemoteMediaNetwork.UNMETERED -> config.autoLoadOnUnmetered
        RemoteMediaNetwork.METERED -> config.autoLoadOnMetered
        RemoteMediaNetwork.UNAVAILABLE -> false
    }

internal fun classifyRemoteMediaNetwork(
    validatedInternet: Boolean,
    unmetered: Boolean,
): RemoteMediaNetwork =
    when {
        !validatedInternet -> RemoteMediaNetwork.UNAVAILABLE
        unmetered -> RemoteMediaNetwork.UNMETERED
        else -> RemoteMediaNetwork.METERED
    }

internal fun classifyRemoteMediaNetwork(capabilities: NetworkCapabilities?): RemoteMediaNetwork =
    classifyRemoteMediaNetwork(
        validatedInternet =
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        unmetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
    )

/** Active validated Android network, observed only while app UI collects it. */
@Singleton
class RemoteMediaNetworkMonitor
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val connectivity = context.getSystemService(ConnectivityManager::class.java)

        val network: Flow<RemoteMediaNetwork> =
            callbackFlow {
                fun publish() {
                    trySend(classifyRemoteMediaNetwork(connectivity.getNetworkCapabilities(connectivity.activeNetwork)))
                }
                val callback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) = publish()

                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities,
                        ) = publish()

                        override fun onLost(network: Network) = publish()

                        override fun onUnavailable() = publish()
                    }
                publish()
                connectivity.registerDefaultNetworkCallback(callback)
                awaitClose { connectivity.unregisterNetworkCallback(callback) }
            }.distinctUntilChanged()
    }

/** Fails closed in previews/tests unless app root supplies current automatic decision. */
val LocalAutomaticRemoteMedia = staticCompositionLocalOf { false }

/** Permission for URL-only avatars and network icons that cannot select a route. */
val LocalDirectRemoteMediaAllowed = staticCompositionLocalOf<(Long?) -> Boolean> { { false } }

/** Routed media never falls back to the platform HTTP stack when this is absent. */
val LocalNetworkMediaHttp = staticCompositionLocalOf<NetworkMediaHttp?> { null }

internal data class RemoteMediaConsent(
    val granted: Boolean = false,
    val grant: () -> Unit = {},
)

internal val LocalInlineMediaConsent = staticCompositionLocalOf { RemoteMediaConsent() }
internal val LocalLinkMediaConsent = staticCompositionLocalOf { RemoteMediaConsent() }

internal fun ImageRequest.Builder.remoteMediaData(
    data: Any?,
    networkAllowed: Boolean,
): ImageRequest.Builder =
    data(data).networkCachePolicy(
        if (networkAllowed) CachePolicy.ENABLED else CachePolicy.DISABLED,
    )

internal fun ImageRequest.Builder.routedRemoteMediaData(
    url: String,
    networkId: Long?,
    networkAllowed: Boolean,
    retry: Int = 0,
): ImageRequest.Builder = remoteMediaData(url, networkAllowed).networkMediaData(url, networkId, retry)
