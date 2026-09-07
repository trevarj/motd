package io.github.trevarj.motd.audio

import coil.request.ImageRequest
import io.github.trevarj.motd.data.repo.LinkPreviewFetchPolicy
import io.github.trevarj.motd.data.repo.LinkPreviewRepositoryImpl
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext

private data class NetworkMediaIdentity(
    val networkId: Long?,
)

/** Keep Coil's HTTP fetcher and caches; only tagged chat media selects an IRC network route. */
fun ImageRequest.Builder.networkMediaData(
    url: String,
    networkId: Long?,
    retry: Int = 0,
): ImageRequest.Builder {
    val key = "network-media:$networkId:$url"
    return data(url)
        .tag(NetworkMediaIdentity::class.java, NetworkMediaIdentity(networkId))
        .diskCacheKey(key)
        .memoryCacheKey("$key:retry=$retry")
}

@Singleton
class NetworkMediaHttp
    @Inject
    constructor(
        private val routes: MediaRouteResolver,
        private val fetchPolicy: LinkPreviewFetchPolicy = LinkPreviewFetchPolicy(),
    ) {
        private val exchanges = ConcurrentHashMap<Call, RoutedExchange>()
        private val transport =
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

        // Untagged URL-only avatars/icons retain ordinary OkHttp behavior and their existing UI policy.
        val client: OkHttpClient =
            OkHttpClient
                .Builder()
                .eventListener(
                    object : EventListener() {
                        override fun canceled(call: Call) {
                            exchanges[call]?.finish(cancel = true)
                        }
                    },
                ).addInterceptor(::intercept)
                .build()

        fun callFactory(networkId: Long?): Call.Factory =
            Call.Factory { request ->
                client.newCall(
                    request
                        .newBuilder()
                        .tag(NetworkMediaIdentity::class.java, NetworkMediaIdentity(networkId))
                        .build(),
                )
            }

        private fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val identity = original.tag(NetworkMediaIdentity::class.java) ?: return chain.proceed(original)
            // Let OkHttp itself return its cache-only miss, without even acquiring a tunnel lease.
            if (original.cacheControl.onlyIfCached) return chain.proceed(original)
            val networkId = identity.networkId ?: throw IOException("Media has no owning network")
            if (original.method != "GET" && original.method != "HEAD") throw IOException("Media requests must be GET or HEAD")
            val outer = chain.call()
            val exchange = RoutedExchange { exchanges.remove(outer) }
            exchanges[outer] = exchange
            try {
                if (outer.isCanceled()) throw IOException("Canceled")
                val route =
                    runBlocking(exchange.routingJob) {
                        val resolved = routes.routeForNetwork(networkId) ?: throw IOException("Media network is unavailable")
                        exchange.attach(resolved)
                        resolved
                    }
                route.proxyError?.let { throw IOException(it) }
                if (route.networkId != networkId) throw IOException("Media route belongs to another network")
                // Never reuse an idle connection from another network or an earlier tunnel lease.
                val routeTransport =
                    transport
                        .newBuilder()
                        .proxy(route.proxy ?: Proxy.NO_PROXY)
                        .connectionPool(ConnectionPool(0, 5, TimeUnit.MINUTES))
                        .apply {
                            if (fetchPolicy.enforceDestinationPolicy && (route.proxy == null || route.proxy.type() == Proxy.Type.DIRECT)) {
                                dns { hostname ->
                                    Dns.SYSTEM.lookup(hostname).also { addresses ->
                                        if (addresses.any { LinkPreviewRepositoryImpl.isDisallowedAddress(it) }) {
                                            throw UnknownHostException("Media destination is not public")
                                        }
                                    }
                                }
                            }
                        }.build()
                var request = original.withoutCredentials()
                var redirects = 0
                while (true) {
                    val url = request.url.toUrl()
                    // The proxy owns hostname resolution. Direct DNS is checked at lookup above,
                    // not in a separate lookup that could race DNS rebinding before the connection.
                    if (fetchPolicy.enforceDestinationPolicy && !LinkPreviewRepositoryImpl.isAllowedDestination(url, resolveDns = false)) {
                        throw IOException("Media destination is not allowed")
                    }
                    val trustManager = route.pinningTrustManager(url)
                    val targetTransport =
                        if (trustManager == null) {
                            routeTransport
                        } else {
                            val socketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
                            routeTransport
                                .newBuilder()
                                .sslSocketFactory(socketFactory, trustManager)
                                .hostnameVerifier { _, _ -> true }
                                .build()
                        }
                    val call = targetTransport.newCall(request)
                    exchange.attach(call)
                    val response = call.execute()
                    if (response.code !in LinkPreviewRepositoryImpl.REDIRECT_CODES) {
                        return response.newBuilder().body(exchange.body(response.body)).build()
                    }
                    request =
                        response.use {
                            if (++redirects > fetchPolicy.maxRedirects) throw IOException("Too many media redirects")
                            val location = response.header("Location") ?: throw IOException("Media redirect has no destination")
                            val next = request.url.resolve(location) ?: throw IOException("Invalid media redirect")
                            request
                                .newBuilder()
                                .url(next)
                                .build()
                                .withoutCredentials()
                        }
                }
            } catch (error: Exception) {
                exchange.finish(cancel = true)
                throw if (error is IOException) error else IOException("Could not load media using its network", error)
            }
        }
    }

private fun Request.withoutCredentials(): Request =
    newBuilder()
        .url(
            url
                .newBuilder()
                .username("")
                .password("")
                .build(),
        ).removeHeader("Authorization")
        .removeHeader("Proxy-Authorization")
        .removeHeader("Cookie")
        .removeHeader("Cookie2")
        .removeHeader("Host")
        .build()

/** The outer call has no exchange of its own, so its cancellation must reach the real streaming call. */
private class RoutedExchange(
    private val onFinish: () -> Unit,
) {
    val routingJob = Job()
    private val finished = AtomicBoolean()
    private val route = AtomicReference<NetworkMediaRoute?>()
    private val call = AtomicReference<Call?>()

    fun attach(value: NetworkMediaRoute) {
        route.set(value)
        if (finished.get()) {
            route.getAndSet(null)?.close()
            throw IOException("Canceled")
        }
    }

    fun attach(value: Call) {
        call.set(value)
        if (finished.get()) {
            call.getAndSet(null)?.cancel()
            throw IOException("Canceled")
        }
    }

    fun finish(cancel: Boolean = false) {
        if (!finished.compareAndSet(false, true)) return
        routingJob.cancel()
        val inner = call.getAndSet(null)
        if (cancel) inner?.cancel()
        try {
            route.getAndSet(null)?.close()
        } finally {
            onFinish()
        }
    }

    fun body(delegate: ResponseBody): ResponseBody =
        object : ResponseBody() {
            private val source =
                object : ForwardingSource(delegate.source()) {
                    override fun read(
                        sink: Buffer,
                        byteCount: Long,
                    ): Long =
                        try {
                            super.read(sink, byteCount).also { if (it == -1L) finish() }
                        } catch (error: IOException) {
                            finish(cancel = true)
                            throw error
                        }

                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            finish()
                        }
                    }
                }.buffer()

            override fun contentType() = delegate.contentType()

            override fun contentLength() = delegate.contentLength()

            override fun source() = source
        }
}
