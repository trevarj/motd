package io.github.trevarj.motd.audio

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class AudioMediaCache
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val mutex = Mutex()
        val cache: SimpleCache by lazy {
            SimpleCache(
                File(context.cacheDir, "audio-cache/media3").also(File::mkdirs),
                LeastRecentlyUsedCacheEvictor(AudioCacheStore.MAX_AUDIO_CACHE_BYTES),
                StandaloneDatabaseProvider(context),
            )
        }

        fun dataSourceFactory(): DataSource.Factory {
            val direct = DefaultDataSource.Factory(context)
            val cached =
                CacheDataSource
                    .Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(
                        DataSource.Factory { OriginalUriDataSource(direct.createDataSource()) },
                    ).setCacheKeyFactory { dataSpec ->
                        audioMediaCacheKey(dataSpec.uri.toString())
                    }.setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            return DataSource.Factory {
                SchemeAwareDataSource(cached.createDataSource(), direct.createDataSource())
            }
        }

        suspend fun copyIfComplete(
            url: String,
            output: File,
            maximumBytes: Long = Long.MAX_VALUE,
            onProgress: (Long, Long?) -> Unit = { _, _ -> },
        ): Boolean =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val remoteUrl = url.substringBefore('#')
                    val key = audioMediaCacheKey(remoteUrl)
                    removeLegacyKey(remoteUrl)
                    val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
                    if (length <= 0 || !cache.isCached(key, 0, length)) return@withLock false
                    val source =
                        CacheDataSource
                            .Factory()
                            .setCache(cache)
                            .setUpstreamDataSourceFactory(null)
                            .createDataSource()
                    try {
                        source.open(
                            DataSpec
                                .Builder()
                                .setUri(remoteUrl.toUri())
                                .setKey(key)
                                .build(),
                        )
                        onProgress(0L, length)
                        output.outputStream().use { sink ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            var copied = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = source.read(buffer, 0, buffer.size)
                                if (count < 0) break
                                if (count == 0) continue
                                if (count.toLong() > maximumBytes - copied) {
                                    throw AudioInputException(AudioInputFailureKind.TOO_LARGE)
                                }
                                sink.write(buffer, 0, count)
                                copied += count
                                onProgress(copied, length)
                            }
                            if (copied != length) throw IOException("Incomplete cached audio")
                        }
                        true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: AudioInputException) {
                        throw error
                    } catch (_: Exception) {
                        false
                    } finally {
                        runCatching { source.close() }
                    }
                }
            }

        suspend fun putComplete(
            url: String,
            input: File,
            maximumBytes: Long = Long.MAX_VALUE,
        ) = withContext(Dispatchers.IO) {
            val length = input.length()
            if (length <= 0L) throw IOException("Downloaded audio is empty")
            if (length > maximumBytes) throw AudioInputException(AudioInputFailureKind.TOO_LARGE)
            mutex.withLock {
                val remoteUrl = url.substringBefore('#')
                val key = audioMediaCacheKey(remoteUrl)
                removeLegacyKey(remoteUrl)
                val currentLength = ContentMetadata.getContentLength(cache.getContentMetadata(key))
                if (currentLength == length && cache.isCached(key, 0, length)) return@withLock
                removeResourceChecked(key)
                val hole =
                    cache.startReadWriteNonBlocking(key, 0, length)
                        ?: throw IOException("Audio cache write could not be acquired")
                if (hole.isCached) {
                    if (cache.isCached(key, 0, length)) return@withLock
                    throw IOException("Audio cache write could not be acquired")
                }
                var staged: File? = null
                var complete = false
                try {
                    val target = cache.startFile(key, 0, length)
                    staged = target
                    FileOutputStream(target).use { sink ->
                        input.inputStream().use { source ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            var copied = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = source.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                if (count.toLong() > length - copied) throw IOException("Downloaded audio changed while caching")
                                sink.write(buffer, 0, count)
                                copied += count
                            }
                            if (copied != length) throw IOException("Downloaded audio changed while caching")
                        }
                        sink.flush()
                        sink.fd.sync()
                    }
                    val metadata = ContentMetadataMutations()
                    ContentMetadataMutations.setContentLength(metadata, length)
                    ContentMetadataMutations.setRedirectedUri(metadata, null)
                    cache.applyContentMetadataMutations(key, metadata)
                    cache.commitFile(target, length)
                    staged = null
                    if (!cache.isCached(key, 0, length)) throw IOException("Audio cache commit was incomplete")
                    complete = true
                } finally {
                    cache.releaseHoleSpan(hole)
                    if (!complete) {
                        val stagingDeleteFailed = staged?.let { it.exists() && !it.delete() } == true
                        removeResourceChecked(key)
                        if (stagingDeleteFailed) throw IOException("Audio cache staging file could not be deleted")
                    }
                }
            }
        }

        suspend fun status(url: String): AudioCacheStatus =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val remoteUrl = url.substringBefore('#')
                    val key = audioMediaCacheKey(remoteUrl)
                    removeLegacyKey(remoteUrl)
                    val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
                    val cachedBytes = cache.getCachedSpans(key).sumOf { span -> span.length }
                    when {
                        length > 0 && cache.isCached(key, 0, length) -> AudioCacheStatus.CACHED
                        cachedBytes > 0L -> AudioCacheStatus.PARTIAL
                        else -> AudioCacheStatus.NOT_CACHED
                    }
                }
            }

        /** Returns byte-accurate cache progress, or null until the response length is known. */
        suspend fun downloadFraction(url: String): Float? =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val remoteUrl = url.substringBefore('#')
                    val key = audioMediaCacheKey(remoteUrl)
                    removeLegacyKey(remoteUrl)
                    val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
                    val cachedBytes = cache.getCachedSpans(key).sumOf { span -> span.length }
                    audioDownloadFraction(cachedBytes, length)
                }
            }

        suspend fun pruneLegacyKeys() =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    removeResourcesChecked(
                        cache.keys.filterNot(::isHashedAudioMediaKey),
                    )
                    cache.keys.forEach { key ->
                        cache.applyContentMetadataMutations(
                            key,
                            ContentMetadataMutations.setRedirectedUri(ContentMetadataMutations(), null),
                        )
                    }
                    if (cache.keys.any { !isHashedAudioMediaKey(it) }) {
                        throw IOException("Legacy audio cache entries could not be deleted")
                    }
                }
            }

        suspend fun clear() =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    removeResourcesChecked(cache.keys.toList())
                    if (cache.keys.isNotEmpty()) throw IOException("Audio media cache could not be cleared")
                }
            }

        private fun removeLegacyKey(remoteUrl: String) {
            if (remoteUrl in cache.keys) removeResourceChecked(remoteUrl)
        }

        private fun removeResourcesChecked(keys: List<String>) {
            var failure: IOException? = null
            keys.forEach { key ->
                try {
                    removeResourceChecked(key)
                } catch (error: IOException) {
                    failure?.addSuppressed(error) ?: run { failure = error }
                }
            }
            failure?.let { throw it }
        }

        private fun removeResourceChecked(key: String) {
            val files = cache.getCachedSpans(key).mapNotNull { it.file }
            cache.removeResource(key)
            if (key in cache.keys || files.any(File::exists)) {
                throw IOException("Audio media cache entry could not be deleted")
            }
        }

        private class OriginalUriDataSource(
            private val delegate: DataSource,
        ) : DataSource {
            private var requestedUri: Uri? = null

            override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)

            override fun open(dataSpec: DataSpec): Long {
                requestedUri = dataSpec.uri
                return delegate.open(dataSpec)
            }

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int = delegate.read(buffer, offset, length)

            override fun getUri(): Uri? = requestedUri

            override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

            override fun close() {
                try {
                    delegate.close()
                } finally {
                    requestedUri = null
                }
            }
        }

        private class SchemeAwareDataSource(
            private val cached: DataSource,
            private val direct: DataSource,
        ) : DataSource {
            private var active: DataSource? = null

            override fun addTransferListener(transferListener: TransferListener) {
                cached.addTransferListener(transferListener)
                direct.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                val scheme = dataSpec.uri.scheme
                active = if (scheme.equals("http", true) || scheme.equals("https", true)) cached else direct
                return checkNotNull(active).open(dataSpec)
            }

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int = checkNotNull(active).read(buffer, offset, length)

            override fun getUri(): Uri? = active?.uri

            override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()

            override fun close() {
                active?.close()
                active = null
            }
        }

        private companion object {
            const val COPY_BUFFER_BYTES = 32 * 1024
        }
    }

internal fun audioMediaCacheKey(url: String): String {
    val digest =
        MessageDigest
            .getInstance("SHA-256")
            .digest(url.substringBefore('#').toByteArray(Charsets.UTF_8))
    return buildString(AUDIO_MEDIA_CACHE_KEY_PREFIX.length + digest.size * 2) {
        append(AUDIO_MEDIA_CACHE_KEY_PREFIX)
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }
}

private fun isHashedAudioMediaKey(key: String): Boolean =
    key.length == AUDIO_MEDIA_CACHE_KEY_PREFIX.length + SHA256_HEX_LENGTH &&
        key.startsWith(AUDIO_MEDIA_CACHE_KEY_PREFIX) &&
        key.substring(AUDIO_MEDIA_CACHE_KEY_PREFIX.length).all { it in HEX_DIGITS }

private const val AUDIO_MEDIA_CACHE_KEY_PREFIX = "audio-sha256:"
private const val SHA256_HEX_LENGTH = 64
private const val HEX_DIGITS = "0123456789abcdef"

internal fun audioDownloadFraction(
    cachedBytes: Long,
    totalBytes: Long,
): Float? =
    totalBytes.takeIf { it > 0L }?.let { total ->
        (cachedBytes.coerceAtLeast(0L).toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }
