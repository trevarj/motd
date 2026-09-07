package io.github.trevarj.motd.audio

import android.util.LruCache
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class CachedAudioMetadata(
    val metadata: AudioMetadata?,
)

interface AudioMetadataRepository {
    fun cached(url: String): CachedAudioMetadata? = null

    suspend fun metadata(
        url: String,
        networkId: Long?,
    ): AudioMetadata?
}

@Singleton
class AudioMetadataRepositoryImpl
    @Inject
    constructor(
        private val routeProvider: MediaRouteResolver,
        @ApplicationScope private val applicationScope: CoroutineScope,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : AudioMetadataRepository {
        private val cache = LruCache<String, Holder>(CACHE_SIZE)
        private val inFlight = ConcurrentHashMap<String, Deferred<Holder>>()

        override fun cached(url: String): CachedAudioMetadata? = synchronized(cache) { cache.get(url)?.let { CachedAudioMetadata(it.value) } }

        override suspend fun metadata(
            url: String,
            networkId: Long?,
        ): AudioMetadata? {
            cached(url)?.let { return it.metadata }
            return sharedFetch(url, networkId).await().value
        }

        private fun sharedFetch(
            url: String,
            networkId: Long?,
        ): Deferred<Holder> {
            val key = "$networkId|$url"
            val created =
                applicationScope.async(ioDispatcher) {
                    val result =
                        try {
                            head(url, networkId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    Holder(result).also { holder ->
                        synchronized(cache) { cache.put(url, holder) }
                    }
                }
            val existing = inFlight.putIfAbsent(key, created)
            if (existing != null) {
                created.cancel()
                return existing
            }
            created.invokeOnCompletion { inFlight.remove(key, created) }
            return created
        }

        private suspend fun head(
            url: String,
            networkId: Long?,
        ): AudioMetadata? =
            withContext(ioDispatcher) {
                if (!isExtensionlessHttpsAudioCandidate(url)) return@withContext null
                val route = networkId?.let { routeProvider.routeForNetwork(it) } ?: return@withContext null
                route.useAndClose { mediaRoute ->
                    if (mediaRoute.proxyError != null) return@useAndClose null
                    val connection =
                        mediaRoute.open(url).apply {
                            requestMethod = "HEAD"
                            instanceFollowRedirects = true
                            useCaches = false
                            connectTimeout = TIMEOUT_MS
                            readTimeout = TIMEOUT_MS
                            setRequestProperty("Accept", "audio/*")
                            setRequestProperty("User-Agent", USER_AGENT)
                        }
                    try {
                        currentCoroutineContext().ensureActive()
                        val code = connection.responseCode
                        if (code !in 200..299) return@useAndClose null
                        val mime =
                            connection
                                .getHeaderField("Content-Type")
                                ?.substringBefore(';')
                                ?.trim()
                                ?.lowercase()
                        if (mime?.startsWith("audio/") != true) return@useAndClose null
                        AudioMetadata(
                            url = connection.url.toString(),
                            mimeType = mime,
                            sizeBytes =
                                connection
                                    .getHeaderFieldLong("Content-Length", -1L)
                                    .takeIf { it >= 0 },
                        )
                    } finally {
                        connection.disconnect()
                    }
                }
            }

        private inline fun <T> NetworkMediaRoute.useAndClose(block: (NetworkMediaRoute) -> T): T =
            try {
                block(this)
            } finally {
                close()
            }

        private class Holder(
            val value: AudioMetadata?,
        )

        private companion object {
            const val CACHE_SIZE = 256
            const val TIMEOUT_MS = 5_000
            const val USER_AGENT = "motd-Android (https://github.com/trevarj/motd)"
        }
    }
