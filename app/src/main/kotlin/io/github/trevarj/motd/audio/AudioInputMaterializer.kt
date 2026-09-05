package io.github.trevarj.motd.audio

import android.security.NetworkSecurityPolicy
import io.github.trevarj.motd.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

enum class AudioInputFailureKind {
    UNSUPPORTED_SCHEME,
    FILE_UNAVAILABLE,
    EXPIRED,
    MISSING_ENCRYPTION_KEY,
    INVALID_ENCRYPTION_KEY,
    AUTHENTICATION_FAILED,
    ROUTE_UNAVAILABLE,
    TLS_FAILED,
    HTTP_AUTHENTICATION_FAILED,
    HTTP_FAILED,
    READ_FAILED,
    TOO_LARGE,
}

class AudioInputException(
    val kind: AudioInputFailureKind,
) : Exception(kind.safeMessage)

private val AudioInputFailureKind.safeMessage: String
    get() =
        when (this) {
            AudioInputFailureKind.UNSUPPORTED_SCHEME -> "This audio source is not supported"
            AudioInputFailureKind.FILE_UNAVAILABLE -> "The local audio file is unavailable"
            AudioInputFailureKind.EXPIRED -> "This audio link has expired"
            AudioInputFailureKind.MISSING_ENCRYPTION_KEY -> "The encrypted audio link is missing its key"
            AudioInputFailureKind.INVALID_ENCRYPTION_KEY -> "The encrypted audio key is invalid"
            AudioInputFailureKind.AUTHENTICATION_FAILED -> "The encrypted audio could not be authenticated"
            AudioInputFailureKind.ROUTE_UNAVAILABLE -> "The audio network route is unavailable"
            AudioInputFailureKind.TLS_FAILED -> "The secure audio connection failed"
            AudioInputFailureKind.HTTP_AUTHENTICATION_FAILED -> "The audio host rejected access"
            AudioInputFailureKind.HTTP_FAILED -> "The audio host could not provide the file"
            AudioInputFailureKind.READ_FAILED -> "The audio file could not be read"
            AudioInputFailureKind.TOO_LARGE -> "The audio file is larger than 100 MiB"
        }

class LocalAudioLease internal constructor(
    val file: File,
    private val owned: Boolean,
    private val managed: AudioFileLease? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (managed != null) {
            managed.close()
        } else if (owned && file.exists() && !file.delete()) {
            throw IOException("The local audio file could not be deleted")
        }
    }
}

@Singleton
class AudioInputMaterializer
    internal constructor(
        private val routeResolver: MediaRouteResolver,
        private val crypto: VoiceCrypto,
        private val cacheStore: AudioCacheStore,
        private val mediaCache: AudioMediaCache,
        private val ioDispatcher: CoroutineDispatcher,
        private val cleartextPermitted: (String) -> Boolean,
    ) {
        @Inject
        constructor(
            routeResolver: MediaRouteResolver,
            crypto: VoiceCrypto,
            cacheStore: AudioCacheStore,
            mediaCache: AudioMediaCache,
            @IoDispatcher ioDispatcher: CoroutineDispatcher,
        ) : this(
            routeResolver = routeResolver,
            crypto = crypto,
            cacheStore = cacheStore,
            mediaCache = mediaCache,
            ioDispatcher = ioDispatcher,
            cleartextPermitted = { host ->
                NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)
            },
        )

        suspend fun materialize(
            request: AudioPlaybackRequest,
            onProgress: (Long, Long?) -> Unit = { _, _ -> },
        ): LocalAudioLease {
            val produced = AtomicReference<LocalAudioLease?>()
            return try {
                withContext(ioDispatcher) {
                    try {
                        materializeOnIo(request, onProgress).also(produced::set)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: AudioInputException) {
                        throw error
                    } catch (_: IOException) {
                        throw AudioInputException(AudioInputFailureKind.READ_FAILED)
                    } catch (_: SecurityException) {
                        throw AudioInputException(AudioInputFailureKind.READ_FAILED)
                    } catch (_: Exception) {
                        throw AudioInputException(AudioInputFailureKind.READ_FAILED)
                    }
                }.also { delivered -> produced.compareAndSet(delivered, null) }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + ioDispatcher) {
                    try {
                        produced.getAndSet(null)?.close()
                    } catch (cleanupFailure: Throwable) {
                        cancelled.addSuppressed(cleanupFailure)
                    }
                }
                throw cancelled
            }
        }

        private suspend fun materializeOnIo(
            request: AudioPlaybackRequest,
            onProgress: (Long, Long?) -> Unit,
        ): LocalAudioLease {
            currentCoroutineContext().ensureActive()
            val attachment = request.attachment
            val parsed =
                try {
                    URI(attachment.url)
                } catch (_: URISyntaxException) {
                    throw AudioInputException(AudioInputFailureKind.UNSUPPORTED_SCHEME)
                }
            return when (parsed.scheme?.lowercase(Locale.ROOT)) {
                "file" -> {
                    leaseFile(parsed)
                }

                "http", "https" -> {
                    rejectExpired(attachment.expiry)
                    val remoteUrl = attachment.url.substringBefore('#')
                    if (attachment.encrypted) {
                        materializeEncrypted(remoteUrl, parsed.rawFragment, request.networkId, onProgress)
                    } else {
                        materializeClear(remoteUrl, request.networkId, onProgress)
                    }
                }

                else -> {
                    throw AudioInputException(AudioInputFailureKind.UNSUPPORTED_SCHEME)
                }
            }
        }

        private fun leaseFile(uri: URI): LocalAudioLease {
            val file =
                try {
                    File(URI(uri.scheme, uri.authority, uri.path, uri.query, null))
                } catch (_: IllegalArgumentException) {
                    throw AudioInputException(AudioInputFailureKind.FILE_UNAVAILABLE)
                } catch (_: URISyntaxException) {
                    throw AudioInputException(AudioInputFailureKind.FILE_UNAVAILABLE)
                }
            if (!file.isFile || !file.canRead()) throw AudioInputException(AudioInputFailureKind.FILE_UNAVAILABLE)
            if (file.length() > MAX_AUDIO_INPUT_BYTES) throw AudioInputException(AudioInputFailureKind.TOO_LARGE)
            return LocalAudioLease(file, owned = false)
        }

        private fun rejectExpired(expiry: String?) {
            if (expiry == null) return
            val instant =
                try {
                    Instant.parse(expiry)
                } catch (_: DateTimeParseException) {
                    throw AudioInputException(AudioInputFailureKind.EXPIRED)
                }
            if (!instant.isAfter(Instant.now())) throw AudioInputException(AudioInputFailureKind.EXPIRED)
        }

        private suspend fun materializeClear(
            url: String,
            networkId: Long?,
            onProgress: (Long, Long?) -> Unit,
        ): LocalAudioLease {
            val output = cacheStore.inputLease()
            try {
                if (mediaCache.copyIfComplete(url, output.file, MAX_AUDIO_INPUT_BYTES, onProgress)) {
                    currentCoroutineContext().ensureActive()
                    return LocalAudioLease(output.file, owned = true, managed = output)
                }
                download(url, networkId, output.file, onProgress)
                currentCoroutineContext().ensureActive()
                mediaCache.putComplete(url, output.file, MAX_AUDIO_INPUT_BYTES)
                currentCoroutineContext().ensureActive()
                return LocalAudioLease(output.file, owned = true, managed = output)
            } catch (error: Throwable) {
                closeAfterFailure(output, error)
            }
        }

        private suspend fun materializeEncrypted(
            url: String,
            fragment: String?,
            networkId: Long?,
            onProgress: (Long, Long?) -> Unit,
        ): LocalAudioLease {
            if (fragment.isNullOrBlank() || fragment.split('&').none { it.startsWith("motd-key=") }) {
                throw AudioInputException(AudioInputFailureKind.MISSING_ENCRYPTION_KEY)
            }
            val ciphertext = cachedCiphertext(url, onProgress) ?: downloadCiphertext(url, networkId, onProgress)
            currentCoroutineContext().ensureActive()
            val output = cacheStore.inputLease()
            try {
                crypto.decrypt(ciphertext, fragment, output.file)
                currentCoroutineContext().ensureActive()
                try {
                    cacheStore.trim()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A cache-maintenance failure must not discard valid caller-owned audio.
                }
                return LocalAudioLease(output.file, owned = true, managed = output)
            } catch (error: VoiceCryptoException) {
                val mapped =
                    when (error.kind) {
                        VoiceCryptoFailureKind.INVALID_KEY -> AudioInputException(AudioInputFailureKind.INVALID_ENCRYPTION_KEY)
                        VoiceCryptoFailureKind.INVALID_PAYLOAD -> AudioInputException(AudioInputFailureKind.AUTHENTICATION_FAILED)
                        VoiceCryptoFailureKind.AUTHENTICATION_FAILED -> AudioInputException(AudioInputFailureKind.AUTHENTICATION_FAILED)
                    }
                closeAfterFailure(output, mapped)
            } catch (error: Throwable) {
                closeAfterFailure(output, error)
            }
        }

        private fun cachedCiphertext(
            url: String,
            onProgress: (Long, Long?) -> Unit,
        ): File? {
            val cached = cacheStore.ciphertextFile(url)
            if (!cached.isFile || cached.length() <= 0L) return null
            val length = cached.length()
            if (length > MAX_AUDIO_INPUT_BYTES) {
                if (!cached.delete()) throw AudioInputException(AudioInputFailureKind.READ_FAILED)
                throw AudioInputException(AudioInputFailureKind.TOO_LARGE)
            }
            onProgress(length, length)
            return cached
        }

        private suspend fun downloadCiphertext(
            url: String,
            networkId: Long?,
            onProgress: (Long, Long?) -> Unit,
        ): File {
            val partial = cacheStore.tempFile("voice-ciphertext-", ".part")
            var failure: Throwable? = null
            try {
                download(url, networkId, partial, onProgress)
                currentCoroutineContext().ensureActive()
                val destination = cacheStore.ciphertextFile(url)
                try {
                    Files.move(
                        partial.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                return destination
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                if (partial.exists() && !partial.delete()) {
                    val cleanupFailure = IOException("Encrypted audio staging file could not be deleted")
                    failure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                }
            }
        }

        private suspend fun download(
            url: String,
            networkId: Long?,
            output: File,
            onProgress: (Long, Long?) -> Unit,
        ) {
            val route =
                try {
                    if (networkId == null) {
                        null
                    } else {
                        routeResolver.routeForNetwork(networkId)
                            ?: throw AudioInputException(AudioInputFailureKind.ROUTE_UNAVAILABLE)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: AudioInputException) {
                    throw error
                } catch (_: Exception) {
                    throw AudioInputException(AudioInputFailureKind.ROUTE_UNAVAILABLE)
                }
            try {
                if (route?.proxyError != null) throw AudioInputException(AudioInputFailureKind.ROUTE_UNAVAILABLE)
                val parsed = URL(url)
                // Clear HTTP reaches this method only after the playback/transcription confirmation;
                // keep the process policy HTTPS-only and bypass it narrowly for this one transfer.
                if (
                    parsed.protocol.equals("http", ignoreCase = true) &&
                    !cleartextPermitted(parsed.host.removeSurrounding("[", "]"))
                ) {
                    downloadClearHttp(parsed, route?.proxy, output, onProgress)
                } else {
                    val connection =
                        (route?.open(url) ?: parsed.openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            useCaches = false
                            connectTimeout = CONNECT_TIMEOUT_MS
                            readTimeout = READ_TIMEOUT_MS
                            setRequestProperty("Accept", ACCEPT_HEADER)
                            setRequestProperty("User-Agent", USER_AGENT)
                        }
                    downloadConnection(connection, output, onProgress)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AudioInputException) {
                throw error
            } catch (_: SSLException) {
                throw AudioInputException(AudioInputFailureKind.TLS_FAILED)
            } catch (_: Exception) {
                throw AudioInputException(AudioInputFailureKind.READ_FAILED)
            } finally {
                try {
                    route?.close()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw AudioInputException(AudioInputFailureKind.ROUTE_UNAVAILABLE)
                }
            }
        }

        private suspend fun downloadConnection(
            connection: HttpURLConnection,
            output: File,
            onProgress: (Long, Long?) -> Unit,
        ) {
            val input = AtomicReference<InputStream?>()
            cancellableBlocking(
                close = {
                    runCatching { input.getAndSet(null)?.close() }
                    runCatching { connection.disconnect() }
                },
            ) { checkCancellation ->
                checkHttpStatus(connection.responseCode)
                val total = connection.contentLengthLong.takeIf { it > 0L }
                if (total != null && total > MAX_AUDIO_INPUT_BYTES) {
                    throw AudioInputException(AudioInputFailureKind.TOO_LARGE)
                }
                onProgress(0L, total)
                val source = connection.inputStream
                input.set(source)
                output.outputStream().use { sink ->
                    copyStream(source, sink, total, checkCancellation, onProgress)
                }
            }
        }

        private suspend fun downloadClearHttp(
            url: URL,
            proxy: Proxy?,
            output: File,
            onProgress: (Long, Long?) -> Unit,
        ) {
            val port = url.port.takeIf { it >= 0 } ?: HTTP_DEFAULT_PORT
            val host = url.host.removeSurrounding("[", "]")
            val uri = url.toURI()
            val requestTarget =
                buildString {
                    append(uri.rawPath?.takeIf(String::isNotEmpty) ?: "/")
                    uri.rawQuery?.let {
                        append('?')
                        append(it)
                    }
                }
            val hostHeader =
                buildString {
                    if (host.contains(':')) append("[$host]") else append(host)
                    if (port != HTTP_DEFAULT_PORT) append(":$port")
                }
            val socket = Socket(proxy ?: Proxy.NO_PROXY)
            cancellableBlocking(close = { runCatching { socket.close() } }) { checkCancellation ->
                val address =
                    if (proxy?.type() == Proxy.Type.SOCKS) {
                        InetSocketAddress.createUnresolved(host, port)
                    } else {
                        InetSocketAddress(host, port)
                    }
                socket.connect(address, CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                val request = socket.getOutputStream().buffered()
                request.write(
                    (
                        "GET $requestTarget HTTP/1.1\r\n" +
                            "Host: $hostHeader\r\n" +
                            "Accept: $ACCEPT_HEADER\r\n" +
                            "User-Agent: $USER_AGENT\r\n" +
                            "Connection: close\r\n\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII),
                )
                request.flush()
                BufferedInputStream(socket.getInputStream()).use { response ->
                    val status = response.readHttpLine()
                    val code = status.split(' ', limit = 3).getOrNull(1)?.toIntOrNull() ?: throw IOException("Invalid HTTP status")
                    var contentLength: Long? = null
                    var chunked = false
                    var headerBytes = status.length + 2
                    while (true) {
                        val line = response.readHttpLine()
                        headerBytes += line.length + 2
                        if (headerBytes > MAX_HTTP_HEADER_BYTES) throw IOException("HTTP headers are too large")
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        if (separator <= 0) throw IOException("Invalid HTTP header")
                        val name = line.substring(0, separator).trim()
                        val value = line.substring(separator + 1).trim()
                        when {
                            name.equals("Content-Length", ignoreCase = true) -> {
                                val parsedLength = value.toLongOrNull()?.takeIf { it >= 0L } ?: throw IOException("Invalid content length")
                                if (contentLength != null && contentLength != parsedLength) throw IOException("Conflicting content length")
                                contentLength = parsedLength
                            }

                            name.equals("Transfer-Encoding", ignoreCase = true) -> {
                                chunked = value.split(',').any { it.trim().equals("chunked", ignoreCase = true) }
                                if (!chunked) throw IOException("Unsupported transfer encoding")
                            }
                        }
                    }
                    checkHttpStatus(code)
                    val total = contentLength?.takeUnless { chunked }?.takeIf { it > 0L }
                    if (total != null && total > MAX_AUDIO_INPUT_BYTES) {
                        throw AudioInputException(AudioInputFailureKind.TOO_LARGE)
                    }
                    onProgress(0L, total)
                    output.outputStream().use { sink ->
                        if (chunked) {
                            copyChunked(response, sink, checkCancellation, onProgress)
                        } else {
                            copyStream(response, sink, total, checkCancellation, onProgress)
                        }
                    }
                }
            }
        }

        private fun copyStream(
            input: InputStream,
            output: java.io.OutputStream,
            total: Long?,
            checkCancellation: () -> Unit,
            onProgress: (Long, Long?) -> Unit,
        ) {
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            var received = 0L
            while (total == null || received < total) {
                checkCancellation()
                val requested =
                    total
                        ?.let { remaining -> minOf(buffer.size.toLong(), remaining - received).toInt() }
                        ?: buffer.size
                val count = input.read(buffer, 0, requested)
                if (count < 0) break
                if (count == 0) continue
                if (count.toLong() > MAX_AUDIO_INPUT_BYTES - received) {
                    throw AudioInputException(AudioInputFailureKind.TOO_LARGE)
                }
                output.write(buffer, 0, count)
                received += count
                onProgress(received, total)
            }
            if (total != null && received != total) throw AudioInputException(AudioInputFailureKind.READ_FAILED)
        }

        private fun copyChunked(
            input: BufferedInputStream,
            output: java.io.OutputStream,
            checkCancellation: () -> Unit,
            onProgress: (Long, Long?) -> Unit,
        ) {
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            var received = 0L
            while (true) {
                checkCancellation()
                val chunkBytes =
                    input
                        .readHttpLine()
                        .substringBefore(';')
                        .trim()
                        .toLongOrNull(16)
                        ?: throw IOException("Invalid HTTP chunk")
                if (chunkBytes < 0L) throw IOException("Invalid HTTP chunk")
                if (chunkBytes == 0L) {
                    var trailerBytes = 0
                    while (true) {
                        val trailer = input.readHttpLine()
                        trailerBytes += trailer.length + 2
                        if (trailerBytes > MAX_HTTP_HEADER_BYTES) throw IOException("HTTP trailers are too large")
                        if (trailer.isEmpty()) return
                    }
                }
                if (chunkBytes > MAX_AUDIO_INPUT_BYTES - received) {
                    throw AudioInputException(AudioInputFailureKind.TOO_LARGE)
                }
                var remaining = chunkBytes
                while (remaining > 0L) {
                    checkCancellation()
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw IOException("Truncated HTTP chunk")
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    remaining -= count
                    received += count
                    onProgress(received, null)
                }
                if (input.read() != '\r'.code || input.read() != '\n'.code) throw IOException("Invalid HTTP chunk terminator")
            }
        }

        private fun InputStream.readHttpLine(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = read()
                if (next < 0) throw IOException("Truncated HTTP headers")
                if (next == '\n'.code) break
                bytes.write(next)
                if (bytes.size() > MAX_HTTP_LINE_BYTES) throw IOException("HTTP header line is too large")
            }
            val raw = bytes.toByteArray()
            val length = raw.size - if (raw.lastOrNull() == '\r'.code.toByte()) 1 else 0
            return String(raw, 0, length, StandardCharsets.ISO_8859_1)
        }

        private fun checkHttpStatus(code: Int) {
            when (code) {
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN,
                HttpURLConnection.HTTP_PROXY_AUTH,
                -> throw AudioInputException(AudioInputFailureKind.HTTP_AUTHENTICATION_FAILED)

                HttpURLConnection.HTTP_GONE -> throw AudioInputException(AudioInputFailureKind.EXPIRED)
            }
            if (code !in 200..299) throw AudioInputException(AudioInputFailureKind.HTTP_FAILED)
        }

        private suspend fun cancellableBlocking(
            close: () -> Unit,
            block: (() -> Unit) -> Unit,
        ) = suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation { close() }
            try {
                block { continuation.context.ensureActive() }
                if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            } finally {
                close()
            }
        }

        private fun closeAfterFailure(
            lease: AudioFileLease,
            failure: Throwable,
        ): Nothing {
            try {
                lease.close()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }

        private companion object {
            const val CONNECT_TIMEOUT_MS = 15_000
            const val READ_TIMEOUT_MS = 60_000
            const val DOWNLOAD_BUFFER_BYTES = 32 * 1024
            const val HTTP_DEFAULT_PORT = 80
            const val MAX_HTTP_LINE_BYTES = 8 * 1024
            const val MAX_HTTP_HEADER_BYTES = 64 * 1024
            const val ACCEPT_HEADER = "application/octet-stream, audio/*;q=0.9, */*;q=0.1"
            const val USER_AGENT = "motd-Android (https://github.com/trevarj/motd)"
        }
    }

internal const val MAX_AUDIO_INPUT_BYTES = 100L * 1024L * 1024L
