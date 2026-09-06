package io.github.trevarj.motd.data.repo

import android.util.LruCache
import io.github.trevarj.motd.audio.MediaRouteResolver
import io.github.trevarj.motd.audio.NetworkMediaRoute
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.di.IoDispatcher
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Production fetch limits for link previews. The single relaxation flag exists for unit tests,
 * which exercise MockWebServer over cleartext loopback — exactly what the destination policy
 * forbids in production.
 */
data class LinkPreviewFetchPolicy(
    /** HTTPS-only plus loopback/private/link-local/ULA destination blocking, on every hop. */
    val enforceDestinationPolicy: Boolean = true,
    /** Whole-fetch deadline; connect/read timeouts alone cannot bound a byte-dripping server. */
    val fetchDeadlineMs: Long = 15_000,
    /** Bound on concurrently in-flight preview fetches across the process. */
    val maxConcurrentFetches: Int = 4,
    /** Manual redirect-hop cap; each hop is validated like the original URL. */
    val maxRedirects: Int = 4,
)

/** Internal signal for failures that must remain uncached and user-retryable. */
internal class RetryableLinkPreviewException(
    val classification: String,
    val status: Int? = null,
) : IOException(classification)

// Declared web/text/media link preview. HttpURLConnection GET, 5s connect/read timeouts, HTML body
// capped at 512 KB, text body capped at 16 KB, and Wikipedia summaries capped at 128 KB.
// Definitive negative results live in a bounded process cache; retryable failures do not. Concurrent
// callers for the same network+URL await one shared request. HTML scanning remains dependency-free; Wikipedia
// summaries use the already-pinned kotlinx.serialization JSON parser.
//
// Every request is opened through the owning network's media route (never authenticated), so a
// preview for a proxied network traverses that network's proxy and fails closed — no direct
// fallback — when the proxy cannot be established or the network identity is unknown.
@Singleton
class LinkPreviewRepositoryImpl
    @Inject
    constructor(
        private val contentPreviewPrefs: ContentPreviewPrefs,
        private val routeResolver: MediaRouteResolver,
        private val fetchPolicy: LinkPreviewFetchPolicy,
        @ApplicationScope private val applicationScope: CoroutineScope,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
    ) : LinkPreviewRepository {
        // LruCache does not permit null values, so wrap results in an Optional-ish holder.
        private val cache = LruCache<String, Holder>(CACHE_SIZE)
        private val inFlight = ConcurrentHashMap<String, Deferred<Holder>>()
        private val fetchPermits = Semaphore(fetchPolicy.maxConcurrentFetches)

        override fun cachedPreview(
            url: String,
            networkId: Long?,
        ): CachedLinkPreview? =
            synchronized(cache) {
                cache.get(cacheKey(url, networkId))?.let { CachedLinkPreview(it.value) }
            }

        override suspend fun preview(
            url: String,
            networkId: Long?,
        ): LinkPreview? {
            // Gate before even consulting cached metadata: disabled means neither network nor render.
            if (!contentPreviewPrefs.config.first().showLinkPreviews) return null
            cachedPreview(url, networkId)?.let { return it.preview }
            val result = sharedFetch(url, networkId).await()
            result.failure?.let { throw it }
            return result.value
        }

        /**
         * The process-owned request survives one lazy row leaving composition: other rows (or a later
         * recycle of the same row) can join it, and its completed positive/negative value is retained.
         * Keyed per network as well as per URL because the route (and therefore the answer) differs.
         */
        private fun sharedFetch(
            url: String,
            networkId: Long?,
        ): Deferred<Holder> {
            val key = cacheKey(url, networkId)
            val created =
                applicationScope.async(ioDispatcher, start = CoroutineStart.LAZY) {
                    val holder =
                        try {
                            // Permit bounds concurrent work; deadline covers routing, redirects, and body reads.
                            Holder(
                                fetchPermits.withPermit {
                                    withTimeout(fetchPolicy.fetchDeadlineMs) { fetchRouted(url, networkId) }
                                },
                            )
                        } catch (_: TimeoutCancellationException) {
                            Holder(failure = RetryableLinkPreviewException("deadline"))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (retryable: RetryableLinkPreviewException) {
                            Holder(failure = retryable)
                        } catch (_: IOException) {
                            Holder(failure = RetryableLinkPreviewException("network"))
                        } catch (_: Exception) {
                            Holder(failure = RetryableLinkPreviewException("unexpected"))
                        }
                    holder.failure?.let { retryable ->
                        diagnostics.record("link_preview", "fetch_failed") {
                            mapOf(
                                "failure_class" to retryable.classification,
                                "http_status" to retryable.status,
                                "url_fingerprint" to diagnostics.fingerprint(url),
                            )
                        }
                    } ?: synchronized(cache) { cache.put(key, holder) }
                    holder
                }
            val existing = inFlight.putIfAbsent(key, created)
            if (existing != null) {
                created.cancel()
                return existing
            }
            created.invokeOnCompletion { inFlight.remove(key, created) }
            created.start()
            return created
        }

        private suspend fun fetchRouted(
            url: String,
            networkId: Long?,
        ): LinkPreview? {
            // Fail closed and retry later: route identity/readiness can recover without process restart.
            if (networkId == null) throw RetryableLinkPreviewException("route_missing")
            val route = routeResolver.routeForPreview(networkId) ?: throw RetryableLinkPreviewException("route_missing")
            return try {
                // Never fall back direct when the owning network's proxy is unavailable.
                if (route.proxyError != null) throw RetryableLinkPreviewException("proxy_unavailable")
                fetch(url, route)
            } finally {
                route.close()
            }
        }

        private suspend fun fetch(
            url: String,
            route: NetworkMediaRoute,
        ): LinkPreview? =
            suspendCancellableCoroutine { continuation ->
                val connection = AtomicReference<HttpURLConnection?>()
                val worker = AtomicReference<Job?>()
                continuation.invokeOnCancellation {
                    // HttpURLConnection reads do not reliably honor interruption. Detach the caller
                    // immediately, close asynchronously because disconnect itself may block, and bound
                    // any reluctant worker by the existing five-second socket timeout.
                    worker.get()?.cancel()
                    connection.get()?.let { conn -> applicationScope.launch(ioDispatcher) { conn.disconnect() } }
                }
                val job =
                    applicationScope.launch(ioDispatcher) {
                        try {
                            // Wikimedia's summary response is purpose-built for link previews. Fall back to
                            // the ordinary HTML parser when a page or language edition does not expose it.
                            val summaryUrl = wikipediaSummaryUrl(url)
                            val summary =
                                if (summaryUrl == null) {
                                    null
                                } else {
                                    try {
                                        fetchWikipediaSummary(url, summaryUrl, route, connection)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        // A summary outage must not suppress metadata available from the page.
                                        if (!isActive) return@launch
                                        null
                                    }
                                }
                            if (!isActive) return@launch
                            val result = summary ?: fetchGenericPreview(url, route, connection)
                            if (continuation.isActive) continuation.resume(result)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                worker.set(job)
                if (!continuation.isActive) {
                    job.cancel()
                    connection.get()?.let { conn -> applicationScope.launch(ioDispatcher) { conn.disconnect() } }
                }
            }

        private fun fetchWikipediaSummary(
            articleUrl: String,
            summaryUrl: String,
            route: NetworkMediaRoute,
            connection: AtomicReference<HttpURLConnection?>,
        ): LinkPreview? =
            request(summaryUrl, WIKIPEDIA_ACCEPT, route, connection) { conn ->
                val contentType = conn.getHeaderField("Content-Type")
                if (contentType?.substringBefore(';')?.trim()?.equals("application/json", ignoreCase = true) != true ||
                    !hasSupportedContentEncoding(conn)
                ) {
                    return@request null
                }
                val bytes = conn.readCappedBody(WIKIPEDIA_MAX_BYTES)
                parseWikipediaSummary(articleUrl, decodeBody(bytes, contentType))
            }

        private fun fetchGenericPreview(
            url: String,
            route: NetworkMediaRoute,
            connection: AtomicReference<HttpURLConnection?>,
        ): LinkPreview? =
            request(url, GENERIC_ACCEPT, route, connection) { conn ->
                if (!hasSupportedContentEncoding(conn)) return@request null
                val contentType = conn.getHeaderField("Content-Type")
                val finalUrl = conn.url.toString()
                if (mediaType(contentType).startsWith("image/")) {
                    return@request LinkPreview(
                        url = finalUrl,
                        title = textTitle(finalUrl),
                        description = contentType?.substringBefore(';')?.trim(),
                        imageUrl = finalUrl,
                        siteName = conn.url.host,
                        kind = LinkPreviewKind.WEB,
                    )
                }
                val declared = responseKind(contentType)
                val generic = isGenericContentType(contentType)
                if (!generic && (declared == LinkPreviewKind.VIDEO || declared == LinkPreviewKind.FILE)) {
                    return@request filePreview(finalUrl, contentType, declared)
                }
                if (!generic && declared == null) return@request null
                val textByExtension = hasTextExtension(finalUrl)
                val maxBytes =
                    if (declared == LinkPreviewKind.WEB || (generic && !textByExtension)) {
                        HTML_MAX_BYTES
                    } else {
                        TEXT_MAX_BYTES
                    }
                val bytes = conn.readCappedBody(maxBytes)
                val decoded = decodeBody(bytes, contentType)
                when (val kind = responseKind(contentType, finalUrl, decoded)) {
                    LinkPreviewKind.WEB -> {
                        parseOgTags(finalUrl, decoded)
                    }

                    LinkPreviewKind.TEXT -> {
                        parseTextPreview(
                            finalUrl,
                            decodeBody(bytes.copyOf(minOf(bytes.size, TEXT_MAX_BYTES)), contentType),
                        )
                    }

                    LinkPreviewKind.VIDEO, LinkPreviewKind.FILE -> {
                        filePreview(finalUrl, contentType, kind)
                    }

                    LinkPreviewKind.WIKIPEDIA, null -> {
                        null
                    }
                }
            }

        private fun <T> request(
            url: String,
            accept: String,
            route: NetworkMediaRoute,
            connection: AtomicReference<HttpURLConnection?>,
            read: (HttpURLConnection) -> T?,
        ): T? {
            var current = url
            var redirects = 0
            while (true) {
                val parsed = runCatching { URL(current) }.getOrNull() ?: return null
                // Validate every hop. Proxy routes never resolve target hostnames locally.
                if (fetchPolicy.enforceDestinationPolicy &&
                    !validateDestination(parsed, resolveDns = route.proxy == null)
                ) {
                    return null
                }
                // The route can attach a Basic SASL header; previews are always opened
                // unauthenticated so credentials can never travel to an arbitrary host.
                val conn =
                    route.open(current).apply {
                        requestMethod = "GET"
                        connectTimeout = TIMEOUT_MS
                        readTimeout = TIMEOUT_MS
                        instanceFollowRedirects = false
                        setRequestProperty("Accept", accept)
                        setRequestProperty("Accept-Encoding", "identity")
                        setRequestProperty("User-Agent", USER_AGENT)
                    }
                connection.set(conn)
                val next: String
                try {
                    conn.connect()
                    val code = conn.responseCode
                    when {
                        code in 200..299 -> {
                            return read(conn)
                        }

                        code in REDIRECT_CODES -> {
                            val location = conn.getHeaderField("Location") ?: return null
                            next = runCatching { URL(parsed, location).toString() }.getOrNull() ?: return null
                        }

                        code == 404 || code == 410 || code in PERMANENT_HTTP_CODES -> {
                            return null
                        }

                        else -> {
                            throw RetryableLinkPreviewException("http_status", code)
                        }
                    }
                } finally {
                    connection.compareAndSet(conn, null)
                    conn.disconnect()
                }
                if (++redirects > fetchPolicy.maxRedirects) return null
                current = next
            }
        }

        private fun HttpURLConnection.readCappedBody(max: Int): ByteArray {
            val stream = inputStream
            return (if (getHeaderField("Content-Encoding").equals("gzip", ignoreCase = true)) GZIPInputStream(stream) else stream)
                .use { it.readCappedBytes(max) }
        }

        private fun InputStream.readCappedBytes(max: Int): ByteArray {
            val buf = ByteArray(8 * 1024)
            val out = ByteArray(max)
            var total = 0
            while (total < max) {
                val read = read(buf, 0, minOf(buf.size, max - total))
                if (read == -1) break
                System.arraycopy(buf, 0, out, total, read)
                total += read
            }
            return out.copyOf(total)
        }

        private fun hasSupportedContentEncoding(connection: HttpURLConnection): Boolean =
            connection.getHeaderField("Content-Encoding").isNullOrBlank() ||
                connection.getHeaderField("Content-Encoding").equals("identity", ignoreCase = true) ||
                connection.getHeaderField("Content-Encoding").equals("gzip", ignoreCase = true)

        companion object {
            private const val CACHE_SIZE = 256
            private const val TIMEOUT_MS = 5_000
            private const val HTML_MAX_BYTES = 512 * 1024
            private const val TEXT_MAX_BYTES = 16 * 1024
            private const val WIKIPEDIA_MAX_BYTES = 128 * 1024
            private const val TEXT_MAX_CODE_POINTS = 2_048

            private const val HTML_SNIFF_MAX_BYTES = 4 * 1024
            private const val GENERIC_ACCEPT = "text/html, application/xhtml+xml, text/*, application/json, application/xml, image/*, video/*, application/*;q=0.1"
            private const val WIKIPEDIA_ACCEPT = "application/json"
            private const val USER_AGENT = "motd-Android (https://github.com/trevarj/motd)"
            private const val WIKIPEDIA_SITE_NAME = "Wikipedia"
            private val JSON = Json { ignoreUnknownKeys = true }
            private val WIKIPEDIA_HOST = Regex("""(?:^|[.])wikipedia[.]org$""", RegexOption.IGNORE_CASE)
            private val WIKIPEDIA_WHITESPACE = Regex("""\s+""")
            private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
            private val PERMANENT_HTTP_CODES = setOf(400, 401, 405, 406, 411, 413, 414, 415, 422)
            private val IPV4_LITERAL = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
            private val GENERIC_MEDIA_TYPES =
                setOf("", "application/octet-stream", "binary/octet-stream", "application/download")
            private val TEXT_APPLICATION_TYPES =
                setOf(
                    "application/javascript",
                    "application/x-javascript",
                    "application/ecmascript",
                    "application/x-ecmascript",
                    "application/yaml",
                    "application/x-yaml",
                    "application/toml",
                    "application/x-toml",
                    "application/sql",
                    "application/x-sql",
                    "application/ndjson",
                    "application/x-ndjson",
                    "application/json-seq",
                    "application/x-sh",
                    "application/x-shellscript",
                    "application/x-bash",
                    "application/x-zsh",
                    "application/x-httpd-php",
                    "application/x-httpd-php-source",
                    "application/x-php",
                    "application/x-ruby",
                    "application/x-perl",
                    "application/graphql",
                )
            private val TEXT_EXTENSIONS =
                setOf(
                    "txt",
                    "md",
                    "rst",
                    "log",
                    "csv",
                    "tsv",
                    "json",
                    "jsonl",
                    "ndjson",
                    "xml",
                    "yaml",
                    "yml",
                    "toml",
                    "ini",
                    "conf",
                    "config",
                    "env",
                    "properties",
                    "kt",
                    "kts",
                    "java",
                    "py",
                    "js",
                    "ts",
                    "tsx",
                    "jsx",
                    "c",
                    "cpp",
                    "h",
                    "hpp",
                    "rs",
                    "go",
                    "rb",
                    "php",
                    "sh",
                    "bash",
                    "zsh",
                    "fish",
                    "sql",
                    "swift",
                    "scala",
                    "clj",
                    "ex",
                    "exs",
                    "erl",
                    "hs",
                    "lua",
                    "r",
                    "pl",
                    "pm",
                    "groovy",
                    "gradle",
                    "nix",
                    "scm",
                    "proto",
                    "graphql",
                    "gql",
                    "vue",
                    "svelte",
                    "dart",
                    "css",
                    "scss",
                    "sass",
                    "less",
                )

            private fun cacheKey(
                url: String,
                networkId: Long?,
            ): String = "$networkId|$url"

            /**
             * Per-hop SSRF policy: HTTPS only, and never a destination inside the local machine or
             * private network. [resolveDns] performs the lookup locally for direct connections; via a
             * proxy the literal checks still apply but the hostname is left for the proxy to resolve.
             */
            internal fun isAllowedDestination(
                url: URL,
                resolveDns: Boolean,
            ): Boolean =
                try {
                    validateDestination(url, resolveDns)
                } catch (_: RetryableLinkPreviewException) {
                    false
                }

            private fun validateDestination(
                url: URL,
                resolveDns: Boolean,
            ): Boolean {
                if (!url.protocol.equals("https", ignoreCase = true)) return false
                val host =
                    url.host
                        .orEmpty()
                        .removePrefix("[")
                        .removeSuffix("]")
                        .trimEnd('.')
                if (host.isEmpty()) return false
                if (looksLikeIpLiteral(host)) {
                    return ipLiteralOrNull(host)?.let { !isDisallowedAddress(it) } ?: false
                }
                if (!resolveDns) return true
                val addresses =
                    try {
                        InetAddress.getAllByName(host)
                    } catch (_: Exception) {
                        throw RetryableLinkPreviewException("dns")
                    }
                return addresses.none(::isDisallowedAddress)
            }

            /** Literal-only parse — [InetAddress.getByName] never queries DNS for detected address syntax. */
            private fun ipLiteralOrNull(host: String): InetAddress? = if (looksLikeIpLiteral(host)) runCatching { InetAddress.getByName(host) }.getOrNull() else null

            private fun looksLikeIpLiteral(host: String): Boolean {
                if (host.contains(':') || IPV4_LITERAL.matches(host) || host.all(Char::isDigit)) return true
                val parts = host.split('.')
                return parts.size <= 4 &&
                    parts.all { part ->
                        part.isNotEmpty() &&
                            (
                                part.all(Char::isDigit) ||
                                    part.startsWith("0x", ignoreCase = true) && part.drop(2).isNotEmpty() &&
                                    part.drop(2).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                            )
                    }
            }

            internal fun isDisallowedAddress(address: InetAddress): Boolean =
                address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
                    address.isAnyLocalAddress || address.isMulticastAddress ||
                    // IPv6 unique-local fc00::/7, which isSiteLocalAddress does not cover.
                    (address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC)

            internal fun responseKind(contentType: String?): LinkPreviewKind? {
                val mediaType = mediaType(contentType)
                return when {
                    mediaType == "text/html" || mediaType == "application/xhtml+xml" || mediaType.startsWith("image/") -> LinkPreviewKind.WEB

                    mediaType.startsWith("text/") -> LinkPreviewKind.TEXT

                    mediaType == "application/json" || mediaType == "application/xml" ||
                        mediaType in TEXT_APPLICATION_TYPES ||
                        (mediaType.startsWith("application/") && (mediaType.endsWith("+json") || mediaType.endsWith("+xml"))) -> LinkPreviewKind.TEXT

                    mediaType.startsWith("video/") -> LinkPreviewKind.VIDEO

                    mediaType.startsWith("audio/") || mediaType.isBlank() -> null

                    else -> LinkPreviewKind.FILE
                }
            }

            private fun responseKind(
                contentType: String?,
                url: String,
                decoded: String,
            ): LinkPreviewKind? {
                val mediaType = mediaType(contentType)
                val generic = mediaType in GENERIC_MEDIA_TYPES
                if (!generic) return responseKind(contentType)
                if (looksLikeHtml(decoded.take(HTML_SNIFF_MAX_BYTES))) return LinkPreviewKind.WEB
                if (hasTextExtension(url) || isProbablyText(decoded.take(TEXT_MAX_BYTES))) return LinkPreviewKind.TEXT
                return if (mediaType.isBlank()) null else LinkPreviewKind.FILE
            }

            private fun mediaType(contentType: String?): String =
                contentType
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
                    .orEmpty()

            private fun isGenericContentType(contentType: String?): Boolean = mediaType(contentType) in GENERIC_MEDIA_TYPES

            internal fun hasTextExtension(url: String): Boolean =
                runCatching {
                    val name = URL(url).path.substringAfterLast('/').lowercase()
                    name.removePrefix(".") in TEXT_EXTENSIONS || name.substringAfterLast('.', "") in TEXT_EXTENSIONS
                }.getOrDefault(false)

            private fun looksLikeHtml(prefix: String): Boolean {
                val normalized = prefix.trimStart('\uFEFF', ' ', '\t', '\r', '\n').lowercase()
                return normalized.startsWith("<!doctype html") || normalized.startsWith("<html") ||
                    normalized.startsWith("<?xml") && "<html" in normalized ||
                    normalized.startsWith("<head") || normalized.startsWith("<meta") || normalized.startsWith("<title")
            }

            internal fun isProbablyText(text: String): Boolean {
                if (text.isEmpty() || '\u0000' in text || '\uFFFD' in text) return false
                var printable = 0
                var total = 0
                var index = 0
                while (index < text.length && total < 4_096) {
                    val codePoint = text.codePointAt(index)
                    index += Character.charCount(codePoint)
                    total++
                    val type = Character.getType(codePoint)
                    if (codePoint == '\n'.code || codePoint == '\r'.code || codePoint == '\t'.code ||
                        (type != Character.CONTROL.toInt() && type != Character.FORMAT.toInt())
                    ) {
                        printable++
                    }
                }
                return total > 0 && printable * 5 >= total * 4
            }

            internal fun charsetFromContentType(contentType: String?): Charset {
                val value =
                    Regex("(?:^|;)\\s*charset\\s*=\\s*(?:\\\"([^\\\"]*)\\\"|([^;\\s]*))", RegexOption.IGNORE_CASE)
                        .find(contentType.orEmpty())
                        ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                return runCatching { value?.takeIf(String::isNotBlank)?.let(Charset::forName) ?: Charsets.UTF_8 }
                    .getOrDefault(Charsets.UTF_8)
            }

            internal fun decodeBody(
                bytes: ByteArray,
                contentType: String?,
            ): String {
                val (charset, offset) =
                    when {
                        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> {
                            Charsets.UTF_8 to 3
                        }

                        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> {
                            Charsets.UTF_16BE to 2
                        }

                        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> {
                            Charsets.UTF_16LE to 2
                        }

                        else -> {
                            val prefix = bytes.take(HTML_SNIFF_MAX_BYTES).toByteArray().toString(Charsets.ISO_8859_1)
                            val declared = charsetNameFromContentType(contentType)
                            val xml = charsetNameFromXmlDeclaration(prefix)
                            val meta = LinkPreviewHtmlScanner.scan(prefix).charset
                            sequenceOf(declared, xml, meta)
                                .filterNotNull()
                                .mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }
                                .firstOrNull()
                                .orEmptyCharset() to 0
                        }
                    }
                return String(bytes, offset, bytes.size - offset, charset)
            }

            private fun Charset?.orEmptyCharset(): Charset = this ?: Charsets.UTF_8

            private fun charsetNameFromContentType(contentType: String?): String? =
                Regex("(?:^|;)\\s*charset\\s*=\\s*(?:\\\"([^\\\"]*)\\\"|([^;\\s]*))", RegexOption.IGNORE_CASE)
                    .find(contentType.orEmpty())
                    ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                    ?.takeIf(String::isNotBlank)

            private fun charsetNameFromXmlDeclaration(prefix: String): String? =
                Regex("""<\?xml\s+[^>]*?encoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(prefix)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.takeIf(String::isNotBlank)

            internal fun sanitizeText(text: String): String? {
                val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
                val out = StringBuilder()
                var kept = 0
                var index = 0
                while (index < normalized.length && kept < TEXT_MAX_CODE_POINTS) {
                    val codePoint = normalized.codePointAt(index)
                    index += Character.charCount(codePoint)
                    val type = Character.getType(codePoint)
                    if (codePoint == '\n'.code || codePoint == '\t'.code ||
                        (type != Character.CONTROL.toInt() && type != Character.FORMAT.toInt())
                    ) {
                        out.appendCodePoint(codePoint)
                        kept++
                    }
                }
                return out.toString().takeIf { it.isNotBlank() }
            }

            internal fun textTitle(url: String): String {
                val parsed = URL(url)
                val segment =
                    parsed.path
                        .split('/')
                        .lastOrNull { it.isNotBlank() }
                        ?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it) }
                return segment
                    ?.let(::sanitizeText)
                    ?.replace(WIKIPEDIA_WHITESPACE, " ")
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: parsed.host
            }

            internal fun parseTextPreview(
                url: String,
                text: String,
            ): LinkPreview? {
                if (!isProbablyText(text)) return null
                val body = sanitizeText(text) ?: return null
                val host = URL(url).host
                return LinkPreview(url, textTitle(url), body, null, host, LinkPreviewKind.TEXT)
            }

            internal fun filePreview(
                url: String,
                contentType: String?,
                kind: LinkPreviewKind,
            ): LinkPreview =
                LinkPreview(
                    url = url,
                    title = textTitle(url),
                    description = contentType?.substringBefore(';')?.trim()?.takeIf(String::isNotBlank),
                    imageUrl = null,
                    siteName = URL(url).host,
                    kind = kind,
                )

            internal fun wikipediaSummaryUrl(url: String): String? {
                val parsed = runCatching { URL(url) }.getOrNull() ?: return null
                if (parsed.protocol != "http" && parsed.protocol != "https") return null
                if (!WIKIPEDIA_HOST.containsMatchIn(parsed.host)) return null
                val title =
                    when {
                        parsed.path.startsWith("/wiki/") -> {
                            parsed.path.removePrefix("/wiki/")
                        }

                        parsed.path == "/w/index.php" -> {
                            parsed.query
                                ?.split('&')
                                ?.firstOrNull { it.startsWith("title=") }
                                ?.substringAfter('=')
                                ?.replace("+", "%20")
                        }

                        else -> {
                            null
                        }
                    }?.takeIf(String::isNotBlank) ?: return null
                val canonicalHost = parsed.host.replace(".m.wikipedia.org", ".wikipedia.org")
                return "https://$canonicalHost/api/rest_v1/page/summary/$title"
            }

            internal fun parseWikipediaSummary(
                url: String,
                rawJson: String,
            ): LinkPreview? =
                runCatching {
                    val root =
                        JSON.parseToJsonElement(rawJson) as? JsonObject
                            ?: return@runCatching null
                    val title = root.string("title")?.cleanWikipediaText()
                    val extract =
                        (root.string("extract") ?: root.string("description"))
                            ?.cleanWikipediaText()
                    val image =
                        (root["thumbnail"] as? JsonObject)
                            ?.string("source")
                            ?.takeIf(::isHttpUrl)
                    if (title == null && extract == null && image == null) {
                        null
                    } else {
                        LinkPreview(
                            url = url,
                            title = title,
                            description = extract,
                            imageUrl = image,
                            siteName = WIKIPEDIA_SITE_NAME,
                            kind = LinkPreviewKind.WIKIPEDIA,
                        )
                    }
                }.getOrNull()

            private fun JsonObject.string(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull

            private fun String.cleanWikipediaText(): String? = sanitizeText(this)?.replace(WIKIPEDIA_WHITESPACE, " ")?.trim()?.takeIf(String::isNotEmpty)

            private fun isHttpUrl(value: String): Boolean =
                runCatching {
                    val parsed = URL(value)
                    val host =
                        parsed.host
                            .removePrefix("[")
                            .removeSuffix("]")
                            .trimEnd('.')
                    (parsed.protocol == "http" || parsed.protocol == "https") && !isRestrictedMetadataHost(host)
                }.getOrDefault(false)

            private val POPULAR_VIDEO_HOSTS =
                setOf(
                    "youtube.com",
                    "youtu.be",
                    "vimeo.com",
                    "dailymotion.com",
                    "twitch.tv",
                    "streamable.com",
                    "tiktok.com",
                )

            private val HTML_ENTITY = Regex("&(?:#(?:[xX][0-9a-fA-F]+|[0-9]+)|[a-zA-Z]+);")
            private val NAMED_ENTITIES =
                mapOf(
                    "amp" to "&",
                    "lt" to "<",
                    "gt" to ">",
                    "quot" to "\"",
                    "nbsp" to " ",
                    "ndash" to "–",
                    "mdash" to "—",
                    "hellip" to "…",
                    "rsquo" to "’",
                    "lsquo" to "‘",
                    "rdquo" to "”",
                    "ldquo" to "“",
                )

            // Decode one entity layer only; leave unknown, malformed, and surrogate references intact.
            private fun decodeEntities(s: String): String =
                HTML_ENTITY.replace(s) { match ->
                    val ref = match.value.substring(1, match.value.lastIndex)
                    if (!ref.startsWith('#')) {
                        NAMED_ENTITIES[ref.lowercase()] ?: match.value
                    } else {
                        val number = ref.drop(1)
                        val codePoint =
                            if (number.startsWith("x", ignoreCase = true)) {
                                number.drop(1).toIntOrNull(16)
                            } else {
                                number.toIntOrNull()
                            }
                        codePoint
                            ?.takeIf { Character.isValidCodePoint(it) && it !in 0xD800..0xDFFF }
                            ?.let { String(Character.toChars(it)) }
                            ?: match.value
                    }
                }

            internal fun isPopularVideoUrl(url: String): Boolean =
                runCatching {
                    val host = URL(url).host.lowercase().trimEnd('.')
                    POPULAR_VIDEO_HOSTS.any { root -> host == root || host.endsWith(".$root") }
                }.getOrDefault(false)

            private fun cleanMetadata(value: String?): String? =
                value
                    ?.let(::decodeEntities)
                    ?.let(::sanitizeText)
                    ?.replace(WIKIPEDIA_WHITESPACE, " ")
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)

            private fun safeMetadataImageUrl(
                baseUrl: String,
                value: String?,
            ): String? =
                runCatching {
                    val resolved = URL(URL(baseUrl), value ?: return null)
                    if (resolved.protocol != "http" && resolved.protocol != "https") return null
                    val host =
                        resolved.host
                            .removePrefix("[")
                            .removeSuffix("]")
                            .trimEnd('.')
                    if (isRestrictedMetadataHost(host)) {
                        return null
                    }
                    resolved.toString()
                }.getOrNull()

            private fun isRestrictedMetadataHost(host: String): Boolean =
                host.isEmpty() || host.equals("localhost", ignoreCase = true) || host.endsWith(".localhost", ignoreCase = true) ||
                    looksLikeIpLiteral(host) && ipLiteralOrNull(host)?.let(::isDisallowedAddress) != false

            /** Bounded, linear head metadata extraction. Successful HTML always gets a safe fallback card. */
            fun parseOgTags(
                url: String,
                html: String,
            ): LinkPreview? {
                val fetched = runCatching { URL(url) }.getOrNull() ?: return null
                val metadata = LinkPreviewHtmlScanner.scan(html.take(HTML_MAX_BYTES))

                fun metas(name: String): Sequence<String> =
                    metadata.values[name]
                        .orEmpty()
                        .asSequence()
                        .mapNotNull(::cleanMetadata)

                fun meta(name: String): String? = metas(name).firstOrNull()
                val title =
                    meta("og:title")
                        ?: meta("twitter:title")
                        ?: cleanMetadata(metadata.title)
                        ?: textTitle(url)
                val description =
                    meta("og:description")
                        ?: meta("twitter:description")
                        ?: meta("description")
                val image =
                    sequenceOf("og:image:secure_url", "og:image", "twitter:image", "twitter:image:src")
                        .flatMap(::metas)
                        .mapNotNull { candidate -> safeMetadataImageUrl(url, candidate) }
                        .firstOrNull()
                val video =
                    isPopularVideoUrl(url) ||
                        meta("og:type")?.startsWith("video", ignoreCase = true) == true ||
                        meta("og:video") != null ||
                        meta("twitter:player") != null ||
                        meta("twitter:card")?.startsWith("player", ignoreCase = true) == true
                return LinkPreview(
                    url = url,
                    title = title,
                    description = description,
                    imageUrl = image,
                    siteName = fetched.host,
                    kind = if (video) LinkPreviewKind.VIDEO else LinkPreviewKind.WEB,
                )
            }
        }

        private class Holder(
            val value: LinkPreview? = null,
            val failure: RetryableLinkPreviewException? = null,
        )
    }
