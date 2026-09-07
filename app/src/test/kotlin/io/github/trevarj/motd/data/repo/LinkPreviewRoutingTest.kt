package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.audio.MediaRouteResolver
import io.github.trevarj.motd.audio.NetworkMediaRoute
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.diagnostics.RecordingDiagnostics
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

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

    private suspend fun assertRetryable(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("expected RetryableLinkPreviewException")
        } catch (_: RetryableLinkPreviewException) {
            // Expected.
        }
    }

    private fun htmlResponse(title: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/html")
            .setBody("<meta property=\"og:title\" content=\"$title\">")

    private fun route(
        networkId: Long,
        proxy: Proxy? = null,
        proxyError: String? = null,
        authorizationHeader: String? = null,
    ) = NetworkMediaRoute(
        networkId = networkId,
        endpoint =
            NetworkEntity(
                id = networkId,
                name = "test",
                role = NetworkRole.DIRECT,
                host = "irc.example.test",
                port = 6697,
                nick = "nick",
                username = "user",
                realname = "real",
            ),
        proxy = proxy,
        proxyError = proxyError,
        authorizationHeader = authorizationHeader,
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
