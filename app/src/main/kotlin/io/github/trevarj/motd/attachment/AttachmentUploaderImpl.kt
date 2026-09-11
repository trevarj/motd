package io.github.trevarj.motd.attachment

import android.content.ContentResolver
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.audio.MediaRouteResolver
import io.github.trevarj.motd.audio.NetworkMediaRoute
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

internal object MultipartEncoding {
    fun field(
        boundary: String,
        name: String,
        value: String,
    ): ByteArray =
        ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            .toByteArray(StandardCharsets.UTF_8)

    fun fileHeader(
        boundary: String,
        fieldName: String,
        fileName: String,
        mime: String,
    ): ByteArray =
        (
            "--$boundary\r\nContent-Disposition: form-data; name=\"${escape(fieldName)}\"; filename=\"${escape(fileName)}\"\r\n" +
                "Content-Type: $mime\r\n\r\n"
        ).toByteArray(StandardCharsets.UTF_8)

    fun ending(boundary: String): ByteArray = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)

    private fun escape(value: String) =
        value
            .replace("\\", "_")
            .replace("\"", "_")
            .replace("\r", "_")
            .replace("\n", "_")
}

@Singleton
class AttachmentUploaderImpl
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val connectionManager: ConnectionManager,
        // The narrow seam rather than the provider itself, so the file-host binding below is testable
        // without standing up Room.
        private val routeProvider: MediaRouteResolver,
    ) : AttachmentUploader {
        private val resolver: ContentResolver = context.contentResolver

        override fun upload(
            source: AttachmentSource,
            config: PasteBackendConfig,
            context: AttachmentUploadContext,
        ): Flow<UploadProgress> =
            flow {
                val safe = normalizedConfig(config)
                if (safe.backend == AttachmentBackend.CUSTOM_0X0 && validateEndpoint(safe.endpoint) == null) {
                    throw UploadException("Configure a valid Custom HTTPS URL in Settings › Uploads.")
                }
                val knownSize = source.sizeOrNull()
                if (knownSize != null && knownSize > safe.sizeLimitBytes) throw UploadException("File exceeds the configured upload limit")
                require(safe.backend.supports(source)) { "${safe.backend.label} does not support this attachment type" }
                val progress: suspend (Long, Long?) -> Unit = { sent, total ->
                    emit(UploadProgress.Transferring(sent, total))
                }
                when (safe.protocol) {
                    PasteProtocol.TERMBIN -> {
                        emit(uploadTermbin(source as AttachmentSource.Text, safe))
                    }

                    PasteProtocol.MULTIPART_0X0 -> {
                        emit(
                            if (safe.backend == AttachmentBackend.CUSTOM_0X0) {
                                uploadCustom(source, safe, progress)
                            } else {
                                upload0x0(source, safe, progress)
                            },
                        )
                    }

                    PasteProtocol.RAW_CNET -> {
                        emit(uploadCNet(source, safe, progress))
                    }

                    PasteProtocol.MULTIPART_UGUU -> {
                        emit(uploadUguu(source, safe, progress))
                    }

                    PasteProtocol.MULTIPART_CATBOX -> {
                        emit(uploadCatbox(source, safe, progress))
                    }

                    PasteProtocol.SOJU_FILEHOST -> {
                        emit(uploadSojuFileHost(source, safe, context, progress))
                    }
                }
            }.flowOn(Dispatchers.IO)

        private suspend fun uploadCustom(
            source: AttachmentSource,
            config: PasteBackendConfig,
            progress: suspend (Long, Long?) -> Unit,
        ): UploadProgress.Complete {
            val boundary = "motd-${java.util.UUID.randomUUID()}"
            val fields =
                buildList {
                    addAll(multipart0x0Fields(config))
                    add("expiration" to "never")
                    add("burn_after" to "0")
                    add("privacy" to "unlisted")
                    add("syntax_highlight" to "none")
                    if (config.password.isNotBlank()) add("uploader_password" to config.password)
                }
            val connection =
                connection(config.endpoint, "POST").apply {
                    instanceFollowRedirects = false
                    doOutput = true
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    basicAuthHeader(config.username, config.password)?.let { setRequestProperty("Authorization", it) }
                    setChunkedStreamingMode(STREAM_BUFFER_BYTES)
                }
            return executeUpload(connection) {
                connection.outputStream.use { output ->
                    fields.forEach { (name, value) -> output.write(MultipartEncoding.field(boundary, name, value)) }
                    output.write(MultipartEncoding.fileHeader(boundary, "file", source.displayName(), source.mimeType()))
                    streamSource(source, config, output, progress)
                    output.write(MultipartEncoding.ending(boundary))
                }
                val code = connection.responseCode
                val location = connection.getHeaderField("Location")
                val body = if (code in 200..299) response(connection) else ""
                if (code !in 200..299 && code !in 300..399) {
                    throw UploadException("Upload failed (HTTP $code)")
                }
                if (code in 300..399 && !isCustomUploadRedirect(config.endpoint, location)) {
                    throw UploadException("Upload returned an unexpected redirect.")
                }
                UploadProgress.Complete(record(source, config, customResultUrl(config.endpoint, location, body)))
            }
        }

        private suspend fun uploadTermbin(
            source: AttachmentSource.Text,
            config: PasteBackendConfig,
        ): UploadProgress.Complete {
            val bytes = source.text.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > config.sizeLimitBytes) throw UploadException("Paste exceeds the configured upload limit")
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("termbin.com", 9999), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                val output = socket.getOutputStream()
                output.write(bytes)
                output.write('\n'.code)
                output.flush()
                socket.shutdownOutput()
                val url = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine()?.trim()
                if (url.isNullOrBlank()) throw UploadException("Termbin returned an empty response")
                return UploadProgress.Complete(UploadRecord(url, AttachmentBackend.TERMBIN, source.name, "text/plain", bytes.size.toLong()))
            }
        }

        private suspend fun upload0x0(
            source: AttachmentSource,
            config: PasteBackendConfig,
            progress: suspend (Long, Long?) -> Unit,
        ): UploadProgress.Complete =
            uploadMultipart(
                source = source,
                config = config,
                fieldName = "file",
                fields = multipart0x0Fields(config),
                progress = progress,
                parse = { body, connection ->
                    val resultUrl = firstHttpsUrl(body)
                    UploadProgress.Complete(record(source, config, resultUrl, connection.getHeaderField("X-Token")))
                },
            )

        private suspend fun uploadUguu(
            source: AttachmentSource,
            config: PasteBackendConfig,
            progress: suspend (Long, Long?) -> Unit,
        ): UploadProgress.Complete =
            uploadMultipart(
                source = source,
                config = config,
                fieldName = "files[]",
                fields = emptyList(),
                progress = progress,
                parse = { body, _ ->
                    UploadProgress.Complete(record(source, config, BackendResponses.uguu(body)))
                },
            )

        private suspend fun uploadCatbox(
            source: AttachmentSource,
            config: PasteBackendConfig,
            progress: suspend (Long, Long?) -> Unit,
        ): UploadProgress.Complete =
            uploadMultipart(
                source = source,
                config = config,
                fieldName = "fileToUpload",
                fields =
                    buildList {
                        add("reqtype" to "fileupload")
                        if (config.backend == AttachmentBackend.LITTERBOX) {
                            add("time" to config.litterboxExpiry)
                        }
                    },
                progress = progress,
                parse = { body, _ -> UploadProgress.Complete(record(source, config, firstHttpsUrl(body))) },
            )

        private suspend fun uploadCNet(
            source: AttachmentSource,
            config: PasteBackendConfig,
            progress: suspend (Long, Long?) -> Unit,
        ): UploadProgress.Complete {
            val connection =
                connection(config.endpoint, "PUT").apply {
                    doOutput = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", source.mimeType())
                    setRequestProperty("X-FileName", source.displayName())
                    setRequestProperty("X-UUID", "")
                    source.sizeOrNull()?.let(::setFixedLengthStreamingMode) ?: setChunkedStreamingMode(STREAM_BUFFER_BYTES)
                }
            return executeUpload(connection) {
                connection.outputStream.use { output -> streamSource(source, config, output, progress) }
                val response = response(connection)
                val (url, deleteKey) = BackendResponses.cnet(response)
                UploadProgress.Complete(record(source, config, url, deleteKey))
            }
        }

        private suspend fun uploadSojuFileHost(
            source: AttachmentSource,
            config: PasteBackendConfig,
            context: AttachmentUploadContext,
            progress: suspend (Long, Long?) -> Unit,
        ): UploadProgress.Complete {
            val networkId =
                context.networkId
                    ?: throw UploadException("Choose a chat with a connected Soju file host.")
            val ready =
                connectionManager.connectionStates.value[networkId] as? IrcClientState.Ready
                    ?: throw UploadException("This IRC network is not connected.")
            val route =
                routeProvider.routeForNetwork(networkId)
                    ?: throw UploadException("No route for this network.")
            if (route.proxyError != null) throw UploadException(route.proxyError)
            return route.use {
                // An embedded IRC peer already received this same credential. Its public FILEHOST
                // cannot share the private destination name used inside the tunnel, so its own
                // authenticated ISUPPORT advertisement is the authority. Direct connections still
                // require the advertised host to match the configured IRC host.
                val endpoint =
                    if (route.endpoint.obfsMode == ObfsMode.EMBEDDED_REALITY) {
                        httpsUploadUri(ready.isupport[SOJU_FILEHOST_TOKEN])?.toString()
                            ?: throw UploadException("This IRC network is not advertising a Soju file host.")
                    } else {
                        when (val advertised = sojuFileHostEndpoint(ready.isupport, route.endpoint.host)) {
                            is SojuFileHostEndpoint.Usable -> {
                                advertised.url
                            }

                            is SojuFileHostEndpoint.OffHost -> {
                                throw UploadException(sojuOffHostMessage(advertised))
                            }

                            SojuFileHostEndpoint.Unavailable -> {
                                throw UploadException("This IRC network is not advertising a Soju file host.")
                            }
                        }
                    }
                val acceptPost = probeAcceptPost(route, endpoint)
                val connection =
                    route.open(endpoint, authenticated = true).apply {
                        requestMethod = "POST"
                        doOutput = true
                        useCaches = false
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        setRequestProperty("Content-Type", source.mimeType())
                        setRequestProperty("Content-Disposition", "attachment; filename=\"${source.displayName().sanitizeHeader()}\"")
                        setRequestProperty("User-Agent", USER_AGENT)
                        source.sizeOrNull()?.let(::setFixedLengthStreamingMode)
                            ?: setChunkedStreamingMode(STREAM_BUFFER_BYTES)
                    }
                executeUpload(connection) {
                    connection.outputStream.use { output -> streamSource(source, config, output, progress) }
                    val code = connection.responseCode
                    val location = connection.getHeaderField("Location")
                    if (code != HttpURLConnection.HTTP_CREATED || location.isNullOrBlank()) {
                        throw UploadException(
                            sojuUploadFailureMessage(code, readBodySnippet(connection), acceptPost, source.mimeType()),
                        )
                    }
                    UploadProgress.Complete(record(source, config.copy(endpoint = endpoint), resolveSojuLocation(endpoint, location)))
                }
            }
        }

        /**
         * Best-effort OPTIONS probe for the server's advertised Accept-Post types. The filehost spec
         * makes Accept-Post advisory (soju itself never sends it), and enforcing it client-side
         * rejected uploads real servers accept — the POST response is the only authority. The
         * advertised list is kept solely to explain a later POST failure.
         */
        private fun probeAcceptPost(
            route: NetworkMediaRoute,
            endpoint: String,
        ): String? =
            runCatching {
                val connection =
                    route.open(endpoint, authenticated = true).apply {
                        requestMethod = "OPTIONS"
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        setRequestProperty("Accept", "*/*")
                        setRequestProperty("User-Agent", USER_AGENT)
                    }
                try {
                    connection.responseCode
                    // Accept-Post may legally be split across several header lines; merge them all.
                    connection.headerFields.entries
                        .filter { (key, _) -> key?.equals("Accept-Post", ignoreCase = true) == true }
                        .flatMap { it.value.orEmpty() }
                        .filter(String::isNotBlank)
                        .joinToString(",")
                        .takeIf(String::isNotBlank)
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()

        private suspend fun uploadMultipart(
            source: AttachmentSource,
            config: PasteBackendConfig,
            fieldName: String,
            fields: List<Pair<String, String>>,
            progress: suspend (Long, Long?) -> Unit,
            parse: (String, HttpURLConnection) -> UploadProgress.Complete,
        ): UploadProgress.Complete {
            val boundary = "motd-${java.util.UUID.randomUUID()}"
            val connection =
                connection(config.endpoint, "POST").apply {
                    doOutput = true
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    setChunkedStreamingMode(STREAM_BUFFER_BYTES)
                }
            return executeUpload(connection) {
                connection.outputStream.use { output ->
                    fields.forEach { (name, value) -> output.write(MultipartEncoding.field(boundary, name, value)) }
                    output.write(MultipartEncoding.fileHeader(boundary, fieldName, source.displayName(), source.mimeType()))
                    streamSource(source, config, output, progress)
                    output.write(MultipartEncoding.ending(boundary))
                }
                parse(response(connection), connection)
            }
        }

        override suspend fun delete(record: UploadRecord) {
            val token = record.deletionToken ?: throw UploadException("This upload has no deletion token")
            val payload =
                if (record.backend == AttachmentBackend.CRAFTERBIN) {
                    "token=${java.net.URLEncoder.encode(token, StandardCharsets.UTF_8.name())}&delete="
                        .toByteArray(StandardCharsets.UTF_8)
                } else {
                    null
                }
            val connection =
                connection(record.url, if (payload == null) "DELETE" else "POST").apply {
                    if (payload == null) {
                        setRequestProperty(
                            if (record.backend == AttachmentBackend.CNET) "X-Delete-Key" else "X-Token",
                            token,
                        )
                    } else {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                        setFixedLengthStreamingMode(payload.size)
                    }
                }
            executeUpload(connection) {
                payload?.let { connection.outputStream.use { output -> output.write(it) } }
                if (connection.responseCode !in 200..299) throw UploadException("Delete failed (HTTP ${connection.responseCode})")
            }
        }

        private fun AttachmentSource.open(resolver: ContentResolver): InputStream =
            when (this) {
                is AttachmentSource.Text -> text.byteInputStream(StandardCharsets.UTF_8)
                is AttachmentSource.Document -> resolver.openInputStream(uri)
                is AttachmentSource.Photo -> resolver.openInputStream(uri)
                is AttachmentSource.LocalFile -> file.inputStream()
            } ?: throw IOException("Unable to open attachment")

        private suspend fun streamSource(
            source: AttachmentSource,
            config: PasteBackendConfig,
            output: java.io.OutputStream,
            progress: suspend (Long, Long?) -> Unit,
        ) {
            source.open(resolver).use { input ->
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                val throttle = UploadProgressThrottle(System.nanoTime())
                val total = source.sizeOrNull()
                var sent = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    sent += count
                    if (sent > config.sizeLimitBytes) throw UploadException("Upload exceeds the configured limit")
                    output.write(buffer, 0, count)
                    if (throttle.shouldEmit(sent, System.nanoTime())) progress(sent, total)
                }
                if (throttle.shouldEmit(sent, System.nanoTime(), final = true)) progress(sent, total)
            }
        }

        private suspend fun <T> executeUpload(
            connection: HttpURLConnection,
            block: suspend () -> T,
        ): T {
            // HttpURLConnection uses blocking I/O. Disconnect from the cancellation callback so a
            // dismissed progress sheet does not wait for a stalled write/read timeout.
            val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { connection.disconnect() }
            return try {
                block()
            } finally {
                cancellationHandle?.dispose()
                connection.disconnect()
            }
        }
    }

internal fun basicAuthHeader(
    username: String,
    password: String,
): String? {
    if (username.isBlank()) return null
    val token =
        java.util.Base64
            .getEncoder()
            .encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
    return "Basic $token"
}

internal fun isCustomUploadRedirect(
    endpoint: String,
    location: String?,
): Boolean {
    val (origin, resolved) =
        runCatching {
            val origin = URI(endpoint)
            origin to origin.resolve(location?.trim()?.takeIf(String::isNotBlank) ?: return false)
        }.getOrNull() ?: return false
    val originPort = if (origin.port >= 0) origin.port else 443
    val resolvedPort = if (resolved.port >= 0) resolved.port else 443
    if (
        !resolved.scheme.equals("https", ignoreCase = true) ||
        !origin.host.equals(resolved.host, ignoreCase = true) ||
        originPort != resolvedPort
    ) {
        return false
    }
    val path = resolved.path.orEmpty().trimEnd('/')
    if (path.endsWith("/incorrect") || path == "incorrect") return true
    val slug = path.substringAfterLast("/upload/", missingDelimiterValue = "")
    return slug.isNotBlank() && '/' !in slug
}

/** Custom POST: Location `/upload/{slug}` becomes `/file/{slug}`; else Location if https; else body. */
internal fun customResultUrl(
    endpoint: String,
    location: String?,
    body: String = "",
): String {
    val resolved =
        location
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { URI(endpoint).resolve(it).toString() }.getOrNull() }
    if (resolved != null) {
        val uri =
            runCatching { URI(resolved) }.getOrNull()
                ?: throw UploadException("Upload returned an invalid URL.")
        val path = uri.path.orEmpty().trimEnd('/')
        if (path.endsWith("/incorrect") || path == "incorrect") {
            throw UploadException("Password was rejected.")
        }
        val slug =
            path
                .substringAfterLast("/upload/", missingDelimiterValue = "")
                .takeIf { it.isNotBlank() && '/' !in it }
        if (slug != null) {
            return validateEndpoint(URI(uri.scheme, uri.authority, "/file/$slug", null, null).toString())
                ?: throw UploadException("Upload returned an invalid HTTPS URL.")
        }
        validateEndpoint(resolved)?.let { return it }
    }
    return firstHttpsUrl(body)
}

/**
 * The URL a completed upload reports, resolved against the endpoint it was posted to.
 *
 * Shape-checked only, deliberately: this is the link that goes into the message, not a request the
 * client authenticates, so a file host that serves its downloads from a CDN stays usable.
 */
internal fun resolveSojuLocation(
    endpoint: String,
    location: String,
): String {
    val resolved = URI(endpoint).resolve(location).toString()
    return httpsUploadUri(resolved)?.toString()
        ?: throw UploadException("Soju file host returned an invalid HTTPS URL.")
}

/**
 * Refusal text for a file host advertised on a host other than the network's own.
 *
 * Names the advertised host so a misconfigured bouncer reads differently from a hostile one.
 */
internal fun sojuOffHostMessage(endpoint: SojuFileHostEndpoint.OffHost): String =
    "This IRC network advertises a file host at ${endpoint.advertisedHost}, not ${endpoint.networkHost}. " +
        "Refusing to send this network's credentials to another host."

private fun String.sanitizeHeader(): String = replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_")

internal fun String.acceptsMime(mimeType: String): Boolean {
    // The sent type may carry parameters ("text/plain; charset=utf-8") and may be a wildcard or
    // octet-stream when the content resolver cannot name it — neither reads as a server rejection.
    val requested = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
    if (requested.isEmpty() || requested == "application/octet-stream" || requested.endsWith("/*")) return true
    return split(',').map { it.substringBefore(';').trim().lowercase(Locale.ROOT) }.any { accepted ->
        accepted == "*/*" ||
            accepted == requested ||
            accepted.endsWith("/*") && requested.startsWith(accepted.removeSuffix("*"))
    }
}

internal fun sojuUploadFailureMessage(
    code: Int,
    body: String,
    acceptPost: String?,
    mimeType: String,
): String {
    val detail = body.takeIf(String::isNotBlank)?.let { ": ${it.take(160)}" }.orEmpty()
    val hint =
        acceptPost
            ?.takeIf { !it.acceptsMime(mimeType) }
            ?.let { " The server says it accepts: $it." }
            .orEmpty()
    return "Soju file host upload failed (HTTP $code)$detail.$hint"
}

private fun readBodySnippet(connection: HttpURLConnection): String =
    runCatching {
        (connection.errorStream ?: connection.inputStream)?.bufferedReader()?.use { reader ->
            val buffer = CharArray(1024)
            val count = reader.read(buffer)
            if (count > 0) String(buffer, 0, count) else ""
        }
    }.getOrNull()?.trim().orEmpty()

/** Extra multipart fields for the 0x0-compatible upload path. x0.at ignores secret/expires, so it
 * sends only the file field; other 0x0-compatible backends forward the secret-url and expiry prefs. */
internal fun multipart0x0Fields(config: PasteBackendConfig): List<Pair<String, String>> =
    buildList {
        if (config.backend == AttachmentBackend.X0_AT) return@buildList
        if (config.secretUrl) add("secret" to "")
        config.expiry?.takeIf(String::isNotBlank)?.let { add("expires" to it) }
    }

private fun connection(
    endpoint: String,
    method: String,
) = (URL(endpoint).openConnection() as HttpURLConnection).apply {
    requestMethod = method
    useCaches = false
    connectTimeout = CONNECT_TIMEOUT_MS
    readTimeout = READ_TIMEOUT_MS
}

private fun response(connection: HttpURLConnection): String {
    val code = connection.responseCode
    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
    val body =
        stream
            ?.bufferedReader()
            ?.use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(1024)
                while (output.length < MAX_RESPONSE_CHARS) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - output.length))
                    if (count < 0) break
                    output.append(buffer, 0, count)
                }
                output.toString()
            }?.trim()
            .orEmpty()
    if (code !in 200..299) throw UploadException("Upload failed (HTTP $code): ${body.take(160)}")
    return body
}

private fun parseJson(body: String) =
    runCatching { Json.parseToJsonElement(body) }
        .getOrElse { throw UploadException("Backend returned an invalid response") }

internal fun firstHttpsUrl(body: String): String =
    body
        .lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("https://") }
        ?.let(::requireHttpsUrl)
        ?: throw UploadException("Backend did not return an HTTPS URL")

private fun requireHttpsUrl(value: String): String =
    validateEndpoint(value)
        ?: throw UploadException("Backend returned an invalid HTTPS URL")

private fun record(
    source: AttachmentSource,
    config: PasteBackendConfig,
    url: String,
    deletionToken: String? = null,
) = UploadRecord(
    url = url,
    backend = config.backend,
    displayName = source.displayName(),
    mimeType = source.mimeType(),
    sizeBytes = source.sizeOrNull(),
    deletionToken = deletionToken,
    endpoint = config.endpoint,
)

internal object BackendResponses {
    fun uguu(body: String): String {
        val root = parseJson(body).jsonObject
        val url =
            root["files"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("url")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: throw UploadException("Uguu response did not include a file URL")
        return requireHttpsUrl(url)
    }

    fun cnet(body: String): Pair<String, String?> {
        val root = parseJson(body).jsonObject
        val url =
            root["url"]?.jsonPrimitive?.contentOrNull
                ?: throw UploadException("paste.c-net.org response did not include a URL")
        return requireHttpsUrl(url) to root["delete_key"]?.jsonPrimitive?.contentOrNull
    }

    fun plain(body: String): String = firstHttpsUrl(body)
}

private fun AttachmentSource.displayName() =
    when (this) {
        is AttachmentSource.Text -> name
        is AttachmentSource.Document -> name
        is AttachmentSource.Photo -> name
        is AttachmentSource.LocalFile -> name
    }

private fun AttachmentSource.mimeType() =
    when (this) {
        is AttachmentSource.Text -> "text/plain; charset=utf-8"

        is AttachmentSource.Document -> mimeType ?: guessMimeType(name)

        // Some providers return no type for a picked photo; a filename-derived concrete type beats
        // "image/*", which is invalid as a request Content-Type and unmatchable against accept lists.
        is AttachmentSource.Photo -> mimeType ?: guessMimeType(name)

        is AttachmentSource.LocalFile -> mimeType
    }

internal fun guessMimeType(name: String): String =
    java.net.URLConnection.guessContentTypeFromName(name.lowercase(Locale.ROOT))
        ?: "application/octet-stream"

private fun AttachmentSource.sizeOrNull() =
    when (this) {
        is AttachmentSource.Text -> text.toByteArray(StandardCharsets.UTF_8).size.toLong()
        is AttachmentSource.Document -> size
        is AttachmentSource.Photo -> size
        is AttachmentSource.LocalFile -> size
    }

class UploadException(
    message: String,
) : IOException(message)

internal class UploadProgressThrottle(
    startedAtNanos: Long,
    private val byteStep: Long = 256L * 1024L,
    private val intervalNanos: Long = 100_000_000L,
) {
    private var lastBytes = 0L
    private var lastNanos = startedAtNanos

    fun shouldEmit(
        bytes: Long,
        nowNanos: Long,
        final: Boolean = false,
    ): Boolean {
        if (bytes == lastBytes) return false
        if (!final && bytes - lastBytes < byteStep && nowNanos - lastNanos < intervalNanos) return false
        lastBytes = bytes
        lastNanos = nowNanos
        return true
    }
}

private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 60_000
private const val STREAM_BUFFER_BYTES = 32 * 1024
private const val MAX_RESPONSE_CHARS = 64 * 1024
private const val USER_AGENT = "motd-Android (https://github.com/trevarj/motd)"
