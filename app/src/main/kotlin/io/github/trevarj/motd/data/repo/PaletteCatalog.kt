package io.github.trevarj.motd.data.repo

import android.content.Context
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.data.prefs.PaletteDocument
import io.github.trevarj.motd.data.prefs.decodePaletteTree
import io.github.trevarj.motd.data.prefs.parsePaletteJson
import io.github.trevarj.motd.data.prefs.readPaletteBytes
import io.github.trevarj.motd.data.prefs.validatePalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val CATALOG_MAX_BYTES = 1024 * 1024
private const val CATALOG_ENDPOINT = "https://raw.githubusercontent.com/trevarj/motd/main/palettes/index.json"

data class PaletteCatalogState(
    val palettes: List<PaletteDocument> = emptyList(),
    val loading: Boolean = false,
    val cached: Boolean = false,
    val error: String? = null,
)

@Singleton
class PaletteCatalog
    @VisibleForTesting
    internal constructor(
        context: Context,
        private val endpoint: URL,
    ) {
        @Inject
        constructor(
            @ApplicationContext context: Context,
        ) : this(context, URL(CATALOG_ENDPOINT))

        private val cache = File(context.filesDir, "palette-catalog.json")
        private val mutex = Mutex()
        private var hasCatalog = false
        private val mutableState = MutableStateFlow(PaletteCatalogState())
        val state: StateFlow<PaletteCatalogState> = mutableState.asStateFlow()

        suspend fun refresh() {
            mutex.withLock {
                mutableState.value = mutableState.value.copy(loading = true, error = null)
                try {
                    val cached =
                        withContext(Dispatchers.IO) {
                            if (!cache.isFile) {
                                null
                            } else {
                                try {
                                    cache.inputStream().use { decodePaletteCatalog(readPaletteBytes(it, CATALOG_MAX_BYTES)) }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }
                    if (cached != null) {
                        hasCatalog = true
                        mutableState.value = PaletteCatalogState(cached, loading = true, cached = true)
                    }
                    val bytes = fetch()
                    val palettes = decodePaletteCatalog(bytes)
                    withContext(Dispatchers.IO) {
                        val temporary = File.createTempFile("palette-catalog-", ".json", cache.parentFile)
                        try {
                            FileOutputStream(temporary).use {
                                it.write(bytes)
                                it.fd.sync()
                            }
                            currentCoroutineContext().ensureActive()
                            Files.move(temporary.toPath(), cache.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        } finally {
                            temporary.delete()
                        }
                    }
                    hasCatalog = true
                    mutableState.value = PaletteCatalogState(palettes)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    mutableState.value = mutableState.value.copy(cached = hasCatalog, error = "Could not refresh the community catalog. Retry.")
                } finally {
                    mutableState.value = mutableState.value.copy(loading = false)
                }
            }
        }

        private suspend fun fetch(): ByteArray =
            withTimeoutOrNull(15_000) {
                val deadline = System.nanoTime() + 15_000_000_000L
                val connection = endpoint.openConnection(Proxy.NO_PROXY) as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Accept-Encoding", "gzip")
                coroutineScope {
                    val request =
                        async(Dispatchers.IO) {
                            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Catalog HTTP response rejected")
                            val encoding = connection.contentEncoding
                            connection.inputStream.use { stream ->
                                val input =
                                    object : FilterInputStream(stream) {
                                        private fun checkDeadline() {
                                            val remaining = (deadline - System.nanoTime()) / 1_000_000
                                            if (remaining <= 0) throw IOException("Catalog request timed out")
                                            connection.readTimeout = minOf(5_000L, remaining).toInt().coerceAtLeast(1)
                                        }

                                        override fun read(): Int {
                                            checkDeadline()
                                            return `in`.read()
                                        }

                                        override fun read(
                                            buffer: ByteArray,
                                            offset: Int,
                                            length: Int,
                                        ): Int {
                                            checkDeadline()
                                            return `in`.read(buffer, offset, length)
                                        }
                                    }
                                when {
                                    encoding == null || encoding.equals("identity", true) -> readPaletteBytes(input, CATALOG_MAX_BYTES)
                                    encoding.equals("gzip", true) -> GZIPInputStream(input).use { readPaletteBytes(it, CATALOG_MAX_BYTES) }
                                    else -> throw IOException("Unsupported catalog encoding")
                                }
                            }
                        }
                    try {
                        request.await()
                    } finally {
                        connection.disconnect()
                        request.cancel()
                    }
                }
            } ?: throw IOException("Catalog request timed out")
    }

internal fun decodePaletteCatalog(bytes: ByteArray): List<PaletteDocument> {
    val root =
        parsePaletteJson(bytes, CATALOG_MAX_BYTES) as? JsonObject
            ?: throw IllegalArgumentException("Catalog must be an object")
    require(root.keys == setOf("format", "version", "palettes")) { "Invalid catalog fields" }
    require(root["format"] == JsonPrimitive("motd-palette-catalog")) { "Invalid catalog format" }
    require(root["version"] == JsonPrimitive(1)) { "Unsupported catalog version" }
    val entries = root["palettes"] as? JsonArray ?: throw IllegalArgumentException("Invalid catalog entries")
    require(entries.size <= 256) { "Too many catalog entries" }
    val palettes = entries.map { decodePaletteTree(it).also { palette -> validatePalette(palette, requireBothModes = true) } }
    require(palettes.map { it.id }.toSet().size == palettes.size) { "Duplicate catalog IDs" }
    return palettes
}

internal fun catalogPaletteLink(
    id: String,
    revision: Int,
): String {
    require(Regex("[a-z0-9][a-z0-9-]{0,63}").matches(id) && revision > 0)
    return "https://github.com/trevarj/motd/blob/main/palettes/$id/$revision.motd-palette.json"
}
