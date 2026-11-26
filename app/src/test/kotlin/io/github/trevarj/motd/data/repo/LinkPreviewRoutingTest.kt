package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.audio.MediaRouteResolver
import io.github.trevarj.motd.audio.NetworkMediaRoute
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.data.prefs.decodePalette
import io.github.trevarj.motd.data.prefs.encodePalette
import io.github.trevarj.motd.diagnostics.RecordingDiagnostics
import io.github.trevarj.motd.service.PinningTrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Finding-1/3 regressions: every preview fetch traverses the owning network's media route and
 * fails closed — never a direct fetch that would reveal the client address — and each fetch is
 * deadline-bounded with a global in-flight cap.
 */
@RunWith(RobolectricTestRunner::class)
class LinkPreviewRoutingTest {
    private lateinit var server: MockWebServer
    private lateinit var prefs: FakeContentPreviewPrefs

    // MockWebServer is cleartext loopback, which production destination policy forbids; routing
    // behavior is under test here, so only the destination policy is relaxed.
    private val relaxed = LinkPreviewFetchPolicy(enforceDestinationPolicy = false)

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        prefs = FakeContentPreviewPrefs()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun proxy_error_fails_closed_then_recovers_without_a_cached_failure() =
        runTest {
            var proxyDown = true
            val resolver = MediaRouteResolver { id -> route(id, proxyError = "local SOCKS unavailable".takeIf { proxyDown }) }
            val repository =
                LinkPreviewRepositoryImpl(
                    prefs,
                    resolver,
                    relaxed,
                    this,
                    StandardTestDispatcher(testScheduler),
                )
            val url = server.url("/page").toString()
            server.enqueue(htmlResponse("Recovered"))

            assertRetryable { repository.preview(url, NETWORK_ID) }
            assertNull(repository.cachedPreview(url, NETWORK_ID))
            assertEquals(0, server.requestCount)
            proxyDown = false
            assertEquals("Recovered", repository.preview(url, NETWORK_ID)?.title)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun unknown_network_identity_fetches_nothing() =
        runTest {
            val repository =
                LinkPreviewRepositoryImpl(
                    prefs,
                    { id -> route(id) },
                    relaxed,
                    this,
                    StandardTestDispatcher(testScheduler),
                )
            server.enqueue(htmlResponse("Leak"))

            assertRetryable { repository.preview(server.url("/page").toString(), null) }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun missing_route_fetches_nothing() =
        runTest {
            val repository =
                LinkPreviewRepositoryImpl(
                    prefs,
                    { _ -> null },
                    relaxed,
                    this,
                    StandardTestDispatcher(testScheduler),
                )
            server.enqueue(htmlResponse("Leak"))

            assertRetryable { repository.preview(server.url("/page").toString(), NETWORK_ID) }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun fetch_traverses_the_route_proxy_instead_of_connecting_directly() =
        runBlocking {
            // The MockWebServer plays an HTTP forward proxy: a proxied HttpURLConnection sends the
            // absolute-form request line to it, and never resolves or dials the target host itself.
            val proxyAddress = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", server.port))
            val resolver = MediaRouteResolver { id -> route(id, proxy = proxyAddress) }
            val repository = LinkPreviewRepositoryImpl(prefs, resolver, relaxed, this, Dispatchers.IO)
            server.enqueue(htmlResponse("Proxied"))

            val preview = repository.preview("http://preview-target.invalid/page", NETWORK_ID)

            assertNotNull(preview)
            assertEquals("Proxied", preview?.title)
            assertEquals(1, server.requestCount)
            val line = server.takeRequest().requestLine
            assertTrue("expected absolute-form proxy request, was: $line", "preview-target.invalid" in line)
        }

    @Test
    fun youtube_video_uses_proxied_oembed_instead_of_browser_block_html() =
        runBlocking {
            val pin = enableYoutubeTls()
            server.toProxyAddress().let { proxy ->
                val repository =
                    LinkPreviewRepositoryImpl(
                        prefs,
                        { id -> route(id, proxy = proxy, authorizationHeader = "Basic secret", host = "www.youtube.com", pin = pin) },
                        LinkPreviewFetchPolicy(),
                        this,
                        Dispatchers.IO,
                    )
                enqueueYoutubeResponse(youtubeResponse())
                val url = "https://youtu.be/dQw4w9WgXcQ?si=private&t=30"
                prefs.setShowLinkPreviews(false)
                assertNull(repository.preview(url, NETWORK_ID))
                assertEquals(0, server.requestCount)
                prefs.setShowLinkPreviews(true)

                val preview = repository.preview(url, NETWORK_ID)!!

                assertEquals(url, preview.url)
                assertEquals("A real video", preview.title)
                assertEquals("Video author", preview.description)
                assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", preview.imageUrl)
                assertEquals(LinkPreviewKind.VIDEO, preview.kind)
                assertFalse(preview.isDirectVideo)
                assertEquals("youtu.be", preview.siteName)
                assertEquals(preview, repository.preview(url, NETWORK_ID))
                assertEquals(2, server.requestCount)
                assertYoutubeTunnel()
                val request = server.takeRequest()
                assertEquals(
                    "/oembed?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DdQw4w9WgXcQ&format=json",
                    request.path,
                )
                assertEquals("www.youtube.com", request.getHeader("Host"))
                assertEquals("application/json", request.getHeader("Accept"))
                assertNull(request.getHeader("Authorization"))
                assertNotNull(request.handshake)
            }
        }

    @Test
    fun youtube_missing_video_is_negative_cached_without_fetching_watch_html() =
        runBlocking {
            val pin = enableYoutubeTls()
            server.toProxyAddress().let { proxy ->
                val repository =
                    LinkPreviewRepositoryImpl(
                        prefs,
                        { id -> route(id, proxy = proxy, host = "www.youtube.com", pin = pin) },
                        LinkPreviewFetchPolicy(),
                        this,
                        Dispatchers.IO,
                    )
                enqueueYoutubeResponse(MockResponse().setResponseCode(404))
                val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

                assertNull(repository.preview(url, NETWORK_ID))
                assertNotNull(repository.cachedPreview(url, NETWORK_ID))
                assertNull(repository.preview(url, NETWORK_ID))
                assertEquals(2, server.requestCount)
                assertYoutubeTunnel()
                assertTrue(server.takeRequest().path!!.startsWith("/oembed?"))
            }
        }

    @Test
    fun youtube_oembed_denial_and_outage_are_retryable_without_html_fallback() =
        runBlocking {
            val pin = enableYoutubeTls()
            server.toProxyAddress().let { proxy ->
                val repository =
                    LinkPreviewRepositoryImpl(
                        prefs,
                        { id -> route(id, proxy = proxy, host = "www.youtube.com", pin = pin) },
                        LinkPreviewFetchPolicy(),
                        this,
                        Dispatchers.IO,
                    )
                val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
                for (status in listOf(403, 429, 503)) {
                    enqueueYoutubeResponse(MockResponse().setResponseCode(status))
                    assertRetryable { repository.preview(url, NETWORK_ID) }
                    assertNull(repository.cachedPreview(url, NETWORK_ID))
                }
                enqueueYoutubeResponse(youtubeResponse())
                assertEquals("A real video", repository.preview(url, NETWORK_ID)?.title)
                assertEquals(8, server.requestCount)
                repeat(4) {
                    assertYoutubeTunnel()
                    val request = server.takeRequest()
                    assertTrue(request.path!!.startsWith("/oembed?"))
                    assertNull(request.getHeader("Authorization"))
                }
            }
        }

    @Test
    fun youtube_oembed_redirects_cannot_enter_private_destinations() =
        runBlocking {
            val pin = enableYoutubeTls()
            server.toProxyAddress().let { proxy ->
                val repository =
                    LinkPreviewRepositoryImpl(
                        prefs,
                        { id -> route(id, proxy = proxy, host = "www.youtube.com", pin = pin) },
                        LinkPreviewFetchPolicy(),
                        this,
                        Dispatchers.IO,
                    )
                enqueueYoutubeResponse(MockResponse().setResponseCode(302).setHeader("Location", "https://127.0.0.1/private"))

                assertNull(repository.preview("https://youtu.be/dQw4w9WgXcQ", NETWORK_ID))
                assertEquals(2, server.requestCount)
                assertYoutubeTunnel()
            }
        }

    @Test
    fun youtube_oembed_body_is_bounded_before_parsing() =
        runBlocking {
            val pin = enableYoutubeTls()
            server.toProxyAddress().let { proxy ->
                val repository =
                    LinkPreviewRepositoryImpl(
                        prefs,
                        { id -> route(id, proxy = proxy, host = "www.youtube.com", pin = pin) },
                        LinkPreviewFetchPolicy(),
                        this,
                        Dispatchers.IO,
                    )
                enqueueYoutubeResponse(
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"type\":\"video\",\"title\":\"" + "x".repeat(20 * 1024) + "\"}"),
                )

                assertNull(repository.preview("https://youtu.be/dQw4w9WgXcQ", NETWORK_ID))
                assertEquals(2, server.requestCount)
            }
        }

    @Test
    fun route_authorization_is_never_attached_to_a_preview_request() =
        runTest {
            val resolver = MediaRouteResolver { id -> route(id, authorizationHeader = "Basic c2VjcmV0") }
            val repository =
                LinkPreviewRepositoryImpl(
                    prefs,
                    resolver,
                    relaxed,
                    this,
                    StandardTestDispatcher(testScheduler),
                )
            server.enqueue(htmlResponse("NoAuth"))

            assertNotNull(repository.preview(server.url("/page").toString(), NETWORK_ID))
            assertNull(server.takeRequest().getHeader("Authorization"))
        }

    @Test
    fun negative_results_are_scoped_to_the_network_that_produced_them() =
        runTest {
            // Network 1's proxy is down; network 2 fetches directly. The failed network must not
            // poison the cache entry the healthy network reads.
            val resolver =
                MediaRouteResolver { id ->
                    if (id == 1L) route(id, proxyError = "proxy down") else route(id)
                }
            val repository =
                LinkPreviewRepositoryImpl(
                    prefs,
                    resolver,
                    relaxed,
                    this,
                    StandardTestDispatcher(testScheduler),
                )
            val url = server.url("/page").toString()
            server.enqueue(htmlResponse("Healthy"))

            assertRetryable { repository.preview(url, 1L) }
            assertEquals(0, server.requestCount)
            assertEquals("Healthy", repository.preview(url, 2L)?.title)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun overall_deadline_bounds_a_byte_dripping_server() =
        runBlocking {
            val policy = LinkPreviewFetchPolicy(enforceDestinationPolicy = false, fetchDeadlineMs = 700)
            val repository = LinkPreviewRepositoryImpl(prefs, { id -> route(id) }, policy, this, Dispatchers.IO)
            // 8 KB at ~2 KB/s: connect and per-read socket timeouts never fire, only the deadline can.
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/html")
                    .setBody("<title>Drip</title>" + "x".repeat(8 * 1024))
                    .throttleBody(1024, 500, TimeUnit.MILLISECONDS),
            )

            val startedAt = System.nanoTime()
            assertRetryable { repository.preview(server.url("/slow").toString(), NETWORK_ID) }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue("deadline did not bound the fetch (took ${elapsedMs}ms)", elapsedMs < 10_000)
        }

    @Test
    fun in_flight_fetches_are_bounded_by_the_permit_pool() =
        runBlocking {
            val policy = LinkPreviewFetchPolicy(enforceDestinationPolicy = false, maxConcurrentFetches = 1)
            val repository = LinkPreviewRepositoryImpl(prefs, { id -> route(id) }, policy, this, Dispatchers.IO)
            server.enqueue(htmlResponse("One").setHeadersDelay(1_500, TimeUnit.MILLISECONDS))
            server.enqueue(htmlResponse("Two"))

            val first = async { repository.preview(server.url("/one").toString(), NETWORK_ID) }
            withTimeout(5_000) {
                while (server.requestCount == 0) delay(10)
            }
            val second = async { repository.preview(server.url("/two").toString(), NETWORK_ID) }

            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            // While the single permit is held by the delayed first fetch, the second must not be sent.
            assertNull(server.takeRequest(600, TimeUnit.MILLISECONDS))
            assertEquals("One", first.await()?.title)
            assertEquals("Two", second.await()?.title)
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        }

    @Test
    fun diagnosticsContainOnlyClassificationStatusAndFingerprint() =
        runTest {
            val diagnostics = RecordingDiagnostics()
            val repository =
                LinkPreviewRepositoryImpl(
                    prefs,
                    { id -> route(id) },
                    relaxed,
                    this,
                    StandardTestDispatcher(testScheduler),
                    diagnostics,
                )
            val url = server.url("/private/path?token=secret").toString()
            server.enqueue(MockResponse().setResponseCode(503))

            assertRetryable { repository.preview(url, NETWORK_ID) }
            val event = diagnostics.events.single()
            assertEquals("http_status", event.fields["failure_class"])
            assertEquals(503, event.fields["http_status"])
            assertEquals(diagnostics.fingerprint(url), event.fields["url_fingerprint"])
            assertTrue(event.fields.values.none { it?.toString()?.contains("private") == true })
            assertTrue(event.fields.values.none { it?.toString()?.contains("secret") == true })
        }

    @Test
    fun palette_single_mode_uses_owning_tls_route_original_cache_key_and_no_auxiliary_requests() =
        runBlocking {
            val pin = enableYoutubeTls()
            val repository =
                LinkPreviewRepositoryImpl(
                    prefs,
                    { id -> route(id, proxy = server.toProxyAddress(), authorizationHeader = "Basic secret", host = "www.youtube.com", pin = pin) },
                    LinkPreviewFetchPolicy(),
                    this,
                    Dispatchers.IO,
                )
            val document = paletteFixture().copy(light = null)
            val url = "https://www.youtube.com/shared.MOTD-PALETTE.JSON?download=1#sample"
            enqueueYoutubeResponse(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(encodePalette(document).decodeToString()))
            val first = async { repository.preview(url, NETWORK_ID) }
            val joined = async { repository.preview(url, NETWORK_ID) }
            val preview = first.await()!!
            assertEquals(preview, joined.await())
            assertEquals(document, preview.palette)
            assertEquals(LinkPreviewKind.PALETTE, preview.kind)
            assertEquals(url, preview.url)
            assertNull(preview.description)
            assertNull(preview.imageUrl)
            assertEquals(preview, repository.preview(url, NETWORK_ID))
            assertNull(repository.cachedPreview(paletteLinkTarget(url)!!.fetchUrl, NETWORK_ID))
            prefs.setShowLinkPreviews(false)
            assertNull(repository.preview(url, NETWORK_ID))
            prefs.setShowLinkPreviews(true)
            enqueueYoutubeResponse(MockResponse().setBody(encodePalette(document).decodeToString()))
            assertEquals(document, repository.preview(url, NETWORK_ID + 1)?.palette)
            assertEquals(4, server.requestCount)
            repeat(2) {
                assertYoutubeTunnel()
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals("/shared.MOTD-PALETTE.JSON?download=1", request.path)
                assertNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("Proxy-Authorization"))
            }
        }

    @Test
    fun palette_invalid_oversize_gzip_and_unsupported_are_unavailable_without_metadata_fallback() =
        runBlocking {
            val pin = enableYoutubeTls()
            val repository =
                LinkPreviewRepositoryImpl(
                    prefs,
                    { id -> route(id, proxy = server.toProxyAddress(), host = "www.youtube.com", pin = pin) },
                    LinkPreviewFetchPolicy(),
                    this,
                    Dispatchers.IO,
                )
            val valid = encodePalette(paletteFixture()).decodeToString()
            val oversized = valid + " ".repeat(16 * 1024)
            val gzip = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(oversized.toByteArray()) } }.toByteArray()
            val responses =
                listOf(
                    MockResponse().setBody("{\"format\":\"motd-palette\"}"),
                    MockResponse().setBody(valid.replace("\"version\":1", "\"version\":2")),
                    MockResponse().setBody(oversized),
                    MockResponse().setHeader("Content-Encoding", "gzip").setBody(Buffer().write(gzip)),
                    MockResponse().setBody("<html><title>Not a palette</title></html>"),
                    MockResponse().setBody(Buffer().write(byteArrayOf(0xC3.toByte(), 0x28))),
                )
            responses.forEachIndexed { index, response ->
                val url = "https://www.youtube.com/$index.motd-palette.json"
                enqueueYoutubeResponse(response)
                assertNull(repository.preview(url, NETWORK_ID))
                assertNotNull(repository.cachedPreview(url, NETWORK_ID))
                assertNull(repository.preview(url, NETWORK_ID))
            }
            assertEquals(responses.size * 2, server.requestCount)
        }

    @Test
    fun palette_transient_transport_and_route_failures_remain_retryable() =
        runBlocking {
            val pin = enableYoutubeTls()
            var proxyDown = true
            val repository =
                LinkPreviewRepositoryImpl(
                    prefs,
                    { id -> route(id, proxy = server.toProxyAddress(), proxyError = "down".takeIf { proxyDown }, host = "www.youtube.com", pin = pin) },
                    LinkPreviewFetchPolicy(),
                    this,
                    Dispatchers.IO,
                )
            val url = "https://www.youtube.com/retry.motd-palette.json"
            assertRetryable { repository.preview(url, NETWORK_ID) }
            assertRetryable { repository.preview(url, null) }
            assertEquals(0, server.requestCount)
            proxyDown = false
            for (status in listOf(403, 429, 503)) {
                enqueueYoutubeResponse(MockResponse().setResponseCode(status))
                assertRetryable { repository.preview(url, NETWORK_ID) }
                assertNull(repository.cachedPreview(url, NETWORK_ID))
            }
            val compressed =
                ByteArrayOutputStream()
                    .also { out ->
                        GZIPOutputStream(out).use { it.write(encodePalette(paletteFixture())) }
                    }.toByteArray()
            enqueueYoutubeResponse(
                MockResponse()
                    .setHeader("Content-Encoding", "gzip")
                    .setBody(Buffer().write(compressed.copyOf(compressed.size - 4))),
            )
            assertRetryable { repository.preview(url, NETWORK_ID) }
            assertNull(repository.cachedPreview(url, NETWORK_ID))
            enqueueYoutubeResponse(MockResponse().setBody(encodePalette(paletteFixture()).decodeToString()))
            assertNotNull(repository.preview(url, NETWORK_ID)?.palette)
            assertEquals(10, server.requestCount)
        }

    @Test
    fun community_blob_and_raw_fetch_canonical_raw_paired_snapshot_and_reject_path_escape() =
        runBlocking {
            val pin = enableYoutubeTls()
            val repository =
                LinkPreviewRepositoryImpl(
                    prefs,
                    { id -> route(id, proxy = server.toProxyAddress(), host = "raw.githubusercontent.com", pin = pin) },
                    LinkPreviewFetchPolicy(),
                    this,
                    Dispatchers.IO,
                )
            val raw = "https://raw.githubusercontent.com/trevarj/motd/main/palettes/duplicate-fixture/1.motd-palette.json"
            val blob = raw.replace("raw.githubusercontent.com/trevarj/motd/", "github.com/trevarj/motd/blob/")
            for (url in listOf(blob, raw)) {
                enqueueYoutubeResponse(MockResponse().setBody(encodePalette(paletteFixture()).decodeToString()))
                assertEquals(paletteFixture(), repository.preview(url, NETWORK_ID)?.palette)
                assertEquals(url, repository.cachedPreview(url, NETWORK_ID)?.preview?.url)
                assertEquals("CONNECT raw.githubusercontent.com:443 HTTP/1.1", server.takeRequest().requestLine)
                assertEquals("/trevarj/motd/main/palettes/duplicate-fixture/1.motd-palette.json", server.takeRequest().path)
            }
            val single = raw.replace("/1.", "/2.")
            enqueueYoutubeResponse(MockResponse().setBody(encodePalette(paletteFixture().copy(light = null)).decodeToString()))
            assertNull(repository.preview(single, NETWORK_ID))
            server.takeRequest()
            server.takeRequest()
            for ((index, destination) in listOf(
                "https://example.org/escape.motd-palette.json",
                raw.replace("/main/", "/other/"),
                "$raw?escape=1",
                "https://127.0.0.1/private",
            ).withIndex()) {
                enqueueYoutubeResponse(MockResponse().setResponseCode(302).setHeader("Location", destination))
                assertNull(repository.preview(raw.replace("/1.", "/${index + 3}."), NETWORK_ID))
                server.takeRequest()
                server.takeRequest()
                assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            }
            assertEquals(14, server.requestCount)
        }

    @Test
    fun external_palette_redirects_keep_query_and_validate_each_hop_without_fallback() =
        runBlocking {
            val pin = enableYoutubeTls()
            val repository =
                LinkPreviewRepositoryImpl(
                    prefs,
                    { id -> route(id, proxy = server.toProxyAddress(), host = "www.youtube.com", pin = pin) },
                    LinkPreviewFetchPolicy(),
                    this,
                    Dispatchers.IO,
                )
            enqueueYoutubeResponse(MockResponse().setResponseCode(302).setHeader("Location", "/actual?download=1"))
            enqueueYoutubeResponse(MockResponse().setBody(encodePalette(paletteFixture()).decodeToString()))
            assertNotNull(repository.preview("https://www.youtube.com/redirect.motd-palette.json", NETWORK_ID)?.palette)
            assertYoutubeTunnel()
            server.takeRequest()
            assertYoutubeTunnel()
            assertEquals("/actual?download=1", server.takeRequest().path)
            for ((index, destination) in listOf("https://127.0.0.1/private", "http://www.youtube.com/cleartext", "https://user:secret@www.youtube.com/private").withIndex()) {
                enqueueYoutubeResponse(MockResponse().setResponseCode(302).setHeader("Location", destination))
                assertNull(repository.preview("https://www.youtube.com/unsafe$index.motd-palette.json", NETWORK_ID))
                assertYoutubeTunnel()
                server.takeRequest()
                assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            }
            assertEquals(10, server.requestCount)
        }

    @Test
    fun palette_gzip_valid_body_and_redirect_cap_use_one_bounded_request_pipeline() =
        runBlocking {
            val pin = enableYoutubeTls()
            val repository =
                LinkPreviewRepositoryImpl(
                    prefs,
                    { id -> route(id, proxy = server.toProxyAddress(), host = "www.youtube.com", pin = pin) },
                    LinkPreviewFetchPolicy(),
                    this,
                    Dispatchers.IO,
                )
            val gzip = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(encodePalette(paletteFixture())) } }.toByteArray()
            enqueueYoutubeResponse(MockResponse().setHeader("Content-Encoding", "gzip").setBody(Buffer().write(gzip)))
            assertEquals(paletteFixture(), repository.preview("https://www.youtube.com/gzip.motd-palette.json", NETWORK_ID)?.palette)
            assertYoutubeTunnel()
            server.takeRequest()
            repeat(5) { index ->
                enqueueYoutubeResponse(MockResponse().setResponseCode(302).setHeader("Location", "/hop${index + 1}"))
            }
            assertNull(repository.preview("https://www.youtube.com/loop.motd-palette.json", NETWORK_ID))
            repeat(5) {
                assertYoutubeTunnel()
                server.takeRequest()
            }
            assertEquals(12, server.requestCount)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }

    @Test
    fun palette_fetches_share_concurrency_permits_and_whole_operation_deadline() =
        runBlocking {
            val pin = enableYoutubeTls()
            val resolver = MediaRouteResolver { id -> route(id, proxy = server.toProxyAddress(), host = "www.youtube.com", pin = pin) }
            val repository =
                LinkPreviewRepositoryImpl(
                    prefs,
                    resolver,
                    LinkPreviewFetchPolicy(maxConcurrentFetches = 1),
                    this,
                    Dispatchers.IO,
                )
            enqueueYoutubeResponse(MockResponse().setBody(encodePalette(paletteFixture()).decodeToString()).setHeadersDelay(1_500, TimeUnit.MILLISECONDS))
            enqueueYoutubeResponse(MockResponse().setBody(encodePalette(paletteFixture()).decodeToString()))
            val first = async(Dispatchers.IO) { repository.preview("https://www.youtube.com/one.motd-palette.json", NETWORK_ID) }
            assertYoutubeTunnel()
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val second = async(Dispatchers.IO) { repository.preview("https://www.youtube.com/two.motd-palette.json", NETWORK_ID) }
            assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
            assertNotNull(first.await()?.palette)
            assertNotNull(second.await()?.palette)
            assertYoutubeTunnel()
            server.takeRequest()
            val deadlineRepository =
                LinkPreviewRepositoryImpl(
                    prefs,
                    resolver,
                    LinkPreviewFetchPolicy(fetchDeadlineMs = 700),
                    this,
                    Dispatchers.IO,
                )
            enqueueYoutubeResponse(MockResponse().setBody(encodePalette(paletteFixture()).decodeToString()).throttleBody(1, 200, TimeUnit.MILLISECONDS))
            val started = System.nanoTime()
            assertRetryable { deadlineRepository.preview("https://www.youtube.com/slow.motd-palette.json", NETWORK_ID) }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000)
            assertNull(deadlineRepository.cachedPreview("https://www.youtube.com/slow.motd-palette.json", NETWORK_ID))
        }

    private fun paletteFixture() =
        decodePalette(
            javaClass.getResourceAsStream("/palettes/duplicate-last-valid.motd-palette.json")!!.use { it.readBytes() },
        )

    private suspend fun assertRetryable(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("expected RetryableLinkPreviewException")
        } catch (_: RetryableLinkPreviewException) {
            // Expected.
        }
    }

    private fun enqueueYoutubeResponse(response: MockResponse) {
        // CONNECT carries the canonical host:443 to the proxy, followed by real TLS to the fixture.
        // Script the cleartext tunnel response before each HTTPS metadata response.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END))
        server.enqueue(response)
    }

    private fun assertYoutubeTunnel() {
        val connect = server.takeRequest()
        assertEquals("CONNECT www.youtube.com:443 HTTP/1.1", connect.requestLine)
        assertNull(connect.getHeader("Authorization"))
        assertNull(connect.getHeader("Proxy-Authorization"))
    }

    private fun enableYoutubeTls(): String {
        val password = "media-test".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/network-media.p12")!!.use { keyStore.load(it, password) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, password) }
        server.useHttps(SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }.socketFactory, false)
        return PinningTrustManager.sha256Hex(keyStore.getCertificate("media") as X509Certificate)
    }

    private fun youtubeResponse(): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {"type":"video","title":"A real video","author_name":"Video author",
                 "thumbnail_url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"}
                """.trimIndent(),
            )

    private fun htmlResponse(title: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/html")
            .setBody("<meta property=\"og:title\" content=\"$title\">")

    private fun route(
        networkId: Long,
        proxy: Proxy? = null,
        proxyError: String? = null,
        authorizationHeader: String? = null,
        host: String = "irc.example.test",
        pin: String? = null,
    ) = NetworkMediaRoute(
        networkId = networkId,
        endpoint =
            NetworkEntity(
                id = networkId,
                name = "test",
                role = NetworkRole.DIRECT,
                host = host,
                port = 6697,
                nick = "nick",
                username = "user",
                realname = "real",
            ),
        proxy = proxy,
        proxyError = proxyError,
        authorizationHeader = authorizationHeader,
        endpointPinnedSha256 = pin,
    )

    private class FakeContentPreviewPrefs : ContentPreviewPrefs {
        private val state = MutableStateFlow(ContentPreviewConfig())
        override val config: Flow<ContentPreviewConfig> = state

        override suspend fun setShowImages(show: Boolean) {
            state.value = state.value.copy(showImages = show)
        }

        override suspend fun setShowLinkPreviews(show: Boolean) {
            state.value = state.value.copy(showLinkPreviews = show)
        }

        override suspend fun setAutoLoadOnUnmetered(enabled: Boolean) = Unit

        override suspend fun setAutoLoadOnMetered(enabled: Boolean) = Unit
    }

    private companion object {
        const val NETWORK_ID = 7L
    }
}
