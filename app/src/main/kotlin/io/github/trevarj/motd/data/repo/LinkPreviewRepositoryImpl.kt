package io.github.trevarj.motd.data.repo

import android.util.LruCache
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// OG-tag link preview. HttpURLConnection GET, 5s connect/read timeouts, only text/html, body
// capped at 512 KB. Results (including nulls for unfetchable / non-HTML pages) are cached in an
// in-memory LruCache(256) so repeated renders don't refetch. The OG parser is a small
// regex-based extractor (no HTML-parser dependency) and is unit-tested against fixtures.
@Singleton
class LinkPreviewRepositoryImpl @Inject constructor(
    private val contentPreviewPrefs: ContentPreviewPrefs,
) : LinkPreviewRepository {
    // LruCache does not permit null values, so wrap results in an Optional-ish holder.
    private val cache = LruCache<String, Holder>(CACHE_SIZE)
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun preview(url: String): LinkPreview? {
        // Gate before even consulting cached metadata: disabled means neither network nor render.
        if (!contentPreviewPrefs.config.first().showLinkPreviews) return null
        cache.get(url)?.let { return it.value }
        val result = try {
            fetch(url)
        } catch (cancelled: CancellationException) {
            // A row leaving composition is normal cancellation, not a negative preview result.
            // Propagate it so a later visible row may retry instead of poisoning the LRU with null.
            throw cancelled
        } catch (_: Exception) {
            null
        }
        cache.put(url, Holder(result))
        return result
    }

    private suspend fun fetch(url: String): LinkPreview? = suspendCancellableCoroutine { continuation ->
        val connection = AtomicReference<HttpURLConnection?>()
        val worker = AtomicReference<Job?>()
        continuation.invokeOnCancellation {
            // HttpURLConnection reads do not reliably honor interruption. Detach the caller
            // immediately, close asynchronously because disconnect itself may block, and bound
            // any reluctant worker by the existing five-second socket timeout.
            worker.get()?.cancel()
            connection.get()?.let { conn -> fetchScope.launch { conn.disconnect() } }
        }
        val job = fetchScope.launch {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "text/html")
            }
            connection.set(conn)
            try {
                conn.connect()
                val result = if (conn.responseCode !in 200..299) {
                    null
                } else {
                    val contentType = conn.contentType?.substringBefore(';')?.trim()?.lowercase()
                    if (contentType != "text/html") null
                    else parseOgTags(url, conn.inputStream.readCapped(MAX_BYTES))
                }
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                conn.disconnect()
            }
        }
        worker.set(job)
        if (!continuation.isActive) {
            job.cancel()
            connection.get()?.let { conn -> fetchScope.launch { conn.disconnect() } }
        }
    }

    private fun InputStream.readCapped(max: Int): String {
        val buf = ByteArray(8 * 1024)
        val out = ByteArray(max)
        var total = 0
        while (total < max) {
            val read = read(buf, 0, minOf(buf.size, max - total))
            if (read == -1) break
            System.arraycopy(buf, 0, out, total, read)
            total += read
        }
        return String(out, 0, total, Charsets.UTF_8)
    }

    companion object {
        private const val CACHE_SIZE = 256
        private const val TIMEOUT_MS = 5_000
        private const val MAX_BYTES = 512 * 1024

        // <meta property="og:*" content="..."> in either attribute order, single or double quotes.
        private val OG_TITLE = ogRegex("og:title")
        private val OG_DESCRIPTION = ogRegex("og:description")
        private val OG_IMAGE = ogRegex("og:image")
        private val OG_SITE_NAME = ogRegex("og:site_name")
        private val TITLE_TAG = Regex(
            "<title[^>]*>(.*?)</title>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        private fun ogRegex(property: String): Regex {
            val p = Regex.escape(property)
            // content-before-property and property-before-content variants.
            val pattern =
                "<meta[^>]*?property\\s*=\\s*[\"']$p[\"'][^>]*?content\\s*=\\s*[\"'](.*?)[\"'][^>]*?>" +
                    "|<meta[^>]*?content\\s*=\\s*[\"'](.*?)[\"'][^>]*?property\\s*=\\s*[\"']$p[\"'][^>]*?>"
            return Regex(pattern, RegexOption.IGNORE_CASE)
        }

        private fun Regex.firstGroup(html: String): String? {
            val m = find(html) ?: return null
            return (m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
                ?: m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() })
                ?.let(::decodeEntities)?.trim()?.takeIf { it.isNotEmpty() }
        }

        // Minimal HTML entity decode for the handful common in OG text.
        private fun decodeEntities(s: String): String =
            s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")

        /** Pure OG/title extractor — unit-tested directly against fixture HTML. */
        fun parseOgTags(url: String, html: String): LinkPreview? {
            val title = OG_TITLE.firstGroup(html)
                ?: TITLE_TAG.find(html)?.groupValues?.getOrNull(1)?.let(::decodeEntities)?.trim()
                    ?.takeIf { it.isNotEmpty() }
            val description = OG_DESCRIPTION.firstGroup(html)
            val image = OG_IMAGE.firstGroup(html)
            val siteName = OG_SITE_NAME.firstGroup(html)
            // Nothing extractable → treat as no preview (negative-cacheable).
            if (title == null && description == null && image == null && siteName == null) {
                return null
            }
            return LinkPreview(
                url = url,
                title = title,
                description = description,
                imageUrl = image,
                siteName = siteName,
            )
        }
    }

    private class Holder(val value: LinkPreview?)
}
