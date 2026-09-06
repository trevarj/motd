package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.audio.MediaRouteResolver
import io.github.trevarj.motd.audio.NetworkMediaRoute
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
class LinkPreviewRequestGateTest {
    private lateinit var server: MockWebServer
    private lateinit var prefs: FakeContentPreviewPrefs

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        prefs = FakeContentPreviewPrefs()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // MockWebServer serves cleartext on loopback, which production destination policy forbids;
    // these tests cover the request pipeline, so the policy alone is relaxed.
    private fun repository(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
    ) = LinkPreviewRepositoryImpl(
        prefs,
        directResolver,
        LinkPreviewFetchPolicy(enforceDestinationPolicy = false),
        scope,
        dispatcher,
    )

    @Test
    fun disabled_gate_skips_network_and_cached_metadata() =
        runTest {
            val repository = repository(this, StandardTestDispatcher(testScheduler))
            val url = server.url("/article").toString()
            prefs.setShowLinkPreviews(false)

            assertNull(repository.preview(url, NETWORK_ID))
            assertEquals(0, server.requestCount)

            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/html")
                    .setBody("<meta property=\"og:title\" content=\"Example\">"),
            )
            prefs.setShowLinkPreviews(true)
            assertNotNull(repository.preview(url, NETWORK_ID))
            assertNotNull(repository.cachedPreview(url, NETWORK_ID)?.preview)
            assertEquals(1, server.requestCount)
            assertEquals(
                "motd-Android (https://github.com/trevarj/motd)",
                server.takeRequest().getHeader("User-Agent"),
            )

            prefs.setShowLinkPreviews(false)
            assertNull(repository.preview(url, NETWORK_ID))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun completedGenericTextResultIsDistinctFromCacheMiss() =
        runTest {
            val repository = repository(this, StandardTestDispatcher(testScheduler))
            val url = server.url("/binary").toString()
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody("not html"),
            )

            assertNull(repository.cachedPreview(url, NETWORK_ID))
            assertEquals(LinkPreviewKind.TEXT, repository.preview(url, NETWORK_ID)?.kind)
            assertNotNull(repository.cachedPreview(url, NETWORK_ID))
            assertEquals(LinkPreviewKind.TEXT, repository.cachedPreview(url, NETWORK_ID)?.preview?.kind)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun cancellation_interrupts_an_active_http_request() =
        runBlocking {
            val repository = repository(this, Dispatchers.IO)
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/html")
                    .setBody("<title>Slow</title>xx")
                    .throttleBody(1, 200, TimeUnit.MILLISECONDS),
            )

            val request = launch { repository.preview(server.url("/slow").toString(), NETWORK_ID) }
            withTimeout(2_000) {
                while (server.requestCount == 0) delay(10)
            }

            withTimeout(2_000) {
                request.cancel()
                request.join()
            }
        }

    @Test
    fun text_preview_honors_declared_charset_and_16kib_cap() =
        runTest {
            val repository = repository(this, StandardTestDispatcher(testScheduler))
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/plain; charset=ISO-8859-1")
                    .setBody(Buffer().write("caf\u00e9".toByteArray(Charsets.ISO_8859_1))),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/plain")
                    .setBody("a".repeat(16 * 1024) + "ignored"),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/plain")
                    .setBody("\u0000".repeat(16 * 1024) + "must-not-be-read"),
            )

            assertEquals("caf\u00e9", repository.preview(server.url("/latin1").toString(), NETWORK_ID)?.description)
            assertEquals(2_048, repository.preview(server.url("/large").toString(), NETWORK_ID)?.description?.length)
            assertNull(repository.preview(server.url("/beyond-cap").toString(), NETWORK_ID))
        }

    @Test
    fun retryableHttpFailureIsNotCached_andNextRequestSucceeds() =
        runTest {
            val repository = repository(this, StandardTestDispatcher(testScheduler))
            val url = server.url("/retry").toString()
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(htmlResponse("Recovered"))

            assertRetryable { repository.preview(url, NETWORK_ID) }
            assertNull(repository.cachedPreview(url, NETWORK_ID))
            assertEquals("Recovered", repository.preview(url, NETWORK_ID)?.title)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun temporaryForbiddenIsNotCached_andCanRecover() =
        runTest {
            val repository = repository(this, StandardTestDispatcher(testScheduler))
            val url = server.url("/forbidden").toString()
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(htmlResponse("Allowed later"))

            assertRetryable { repository.preview(url, NETWORK_ID) }
            assertNull(repository.cachedPreview(url, NETWORK_ID))
            assertEquals("Allowed later", repository.preview(url, NETWORK_ID)?.title)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun permanentNotFoundIsNegativeCached() =
        runTest {
            val repository = repository(this, StandardTestDispatcher(testScheduler))
            val url = server.url("/missing").toString()
            server.enqueue(MockResponse().setResponseCode(404))

            assertNull(repository.preview(url, NETWORK_ID))
            assertNotNull(repository.cachedPreview(url, NETWORK_ID))
            assertNull(repository.preview(url, NETWORK_ID))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun htmlMimeSniffing_xhtmlAndFullHeadScanWork() =
        runTest {
            val repository = repository(this, StandardTestDispatcher(testScheduler))
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/xhtml+xml")
                    .setBody("<html><head><title>XHTML</title></head><body/></html>"),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody("<html><head>" + " ".repeat(80 * 1024) + "<meta property=og:title content=Late></head></html>"),
            )
            server.enqueue(
                MockResponse()
                    .setBody("<!doctype html><html><head><title>Sniffed</title></head></html>"),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/html")
                    .setBody("<html><head>" + " ".repeat(513 * 1024) + "<title>Beyond cap</title></head></html>"),
            )

            assertEquals("XHTML", repository.preview(server.url("/xhtml").toString(), NETWORK_ID)?.title)
            assertEquals("Late", repository.preview(server.url("/generic").toString(), NETWORK_ID)?.title)
            assertEquals("Sniffed", repository.preview(server.url("/absent").toString(), NETWORK_ID)?.title)
            assertEquals("beyond", repository.preview(server.url("/beyond").toString(), NETWORK_ID)?.title)
        }

    @Test
    fun githubStyleResponseProducesRepositoryPreview() =
        runTest {
            val repository = repository(this, StandardTestDispatcher(testScheduler))
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(fixture("github-head.html")),
            )

            val preview = repository.preview(server.url("/owner/project").toString(), NETWORK_ID)

            assertEquals("owner/project", preview?.title)
            assertEquals("GitHub repository", preview?.description)
            assertEquals(LinkPreviewKind.WEB, preview?.kind)
        }

    @Test
    fun bodyDecodingHonorsBomHeaderAndEarlyMetaCharset() =
        runTest {
            val repository = repository(this, StandardTestDispatcher(testScheduler))
            val utf16 = "<html><head><title>Snowman ☃</title></head></html>".toByteArray(Charsets.UTF_16LE)
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/html")
                    .setBody(Buffer().write(byteArrayOf(0xFF.toByte(), 0xFE.toByte())).write(utf16)),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/html")
                    .setBody(
                        Buffer().write(
                            "<meta charset=ISO-8859-1><title>café</title>"
                                .toByteArray(Charsets.ISO_8859_1),
                        ),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/xhtml+xml")
                    .setBody(
                        Buffer().write(
                            "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><html><head><title>café XHTML</title></head></html>"
                                .toByteArray(Charsets.ISO_8859_1),
                        ),
                    ),
            )

            assertEquals("Snowman ☃", repository.preview(server.url("/bom").toString(), NETWORK_ID)?.title)
            assertEquals("café", repository.preview(server.url("/meta-charset").toString(), NETWORK_ID)?.title)
            assertEquals("café XHTML", repository.preview(server.url("/xml-charset").toString(), NETWORK_ID)?.title)
        }

    @Test
    fun gzip_metadata_is_decoded_with_the_existing_decompressed_body_cap() =
        runTest {
            val repository = repository(this, StandardTestDispatcher(testScheduler))
            val compressed = Buffer()
            GZIPOutputStream(compressed.outputStream()).use {
                it.write(
                    ("<title>Gzipped</title>" + " ".repeat(512 * 1024) + "<meta property=og:title content=Beyond>")
                        .toByteArray(),
                )
            }
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/html")
                    .setHeader("Content-Encoding", "gzip")
                    .setBody(compressed),
            )

            assertEquals("Gzipped", repository.preview(server.url("/compressed").toString(), NETWORK_ID)?.title)
            assertEquals("identity", server.takeRequest().getHeader("Accept-Encoding"))
        }

    @Test
    fun unsupportedContentEncodingIsDefinitive_andIdentityIsRequested() =
        runTest {
            val repository = repository(this, StandardTestDispatcher(testScheduler))
            val url = server.url("/encoded").toString()
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/html")
                    .setHeader("Content-Encoding", "br")
                    .setBody("not-decompressed"),
            )

            assertNull(repository.preview(url, NETWORK_ID))
            assertNotNull(repository.cachedPreview(url, NETWORK_ID))
            assertEquals("identity", server.takeRequest().getHeader("Accept-Encoding"))
        }

    @Test
    fun txtJsonAndProgrammingExtensionsRenderBoundedTextPreviews() =
        runTest {
            val repository = repository(this, StandardTestDispatcher(testScheduler))
            server.enqueue(MockResponse().setBody("plain notes"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("{\"ok\":true}"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("fun main() = println(1)"))

            val text = repository.preview(server.url("/notes.txt").toString(), NETWORK_ID)
            val json = repository.preview(server.url("/data.json").toString(), NETWORK_ID)
            val kotlin = repository.preview(server.url("/Main.kt").toString(), NETWORK_ID)

            assertEquals(LinkPreviewKind.TEXT, text?.kind)
            assertEquals("plain notes", text?.description)
            assertEquals(LinkPreviewKind.TEXT, json?.kind)
            assertEquals("{\"ok\":true}", json?.description)
            assertEquals(LinkPreviewKind.TEXT, kotlin?.kind)
        }

    @Test
    fun codeMimeAndExtensionRenderText_butBinaryDoesNot() =
        runTest {
            val repository = repository(this, StandardTestDispatcher(testScheduler))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/javascript").setBody("const ok = true;"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("fun main() = println(1)"))
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody(Buffer().write(byteArrayOf(0, 1, 2, 3))),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/plain")
                    .setBody(Buffer().write(byteArrayOf(0xC3.toByte(), 0x28))),
            )

            assertEquals(LinkPreviewKind.TEXT, repository.preview(server.url("/app.js").toString(), NETWORK_ID)?.kind)
            assertEquals(LinkPreviewKind.TEXT, repository.preview(server.url("/Main.kt").toString(), NETWORK_ID)?.kind)
            assertEquals(LinkPreviewKind.FILE, repository.preview(server.url("/blob.bin").toString(), NETWORK_ID)?.kind)
            assertNull(repository.preview(server.url("/invalid.txt").toString(), NETWORK_ID))
            assertEquals("identity", server.takeRequest().getHeader("Accept-Encoding"))
        }

    private suspend fun assertRetryable(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("expected RetryableLinkPreviewException")
        } catch (_: RetryableLinkPreviewException) {
            // Expected.
        }
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/link-preview/$name")).readText()

    private fun htmlResponse(title: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/html")
            .setBody("<meta property=\"og:title\" content=\"$title\">")

    // A resolver whose routes are direct (no proxy, no proxy error) for any requested network.
    private val directResolver =
        MediaRouteResolver { networkId ->
            NetworkMediaRoute(
                networkId = networkId,
                endpoint = testNetworkEntity(networkId),
                proxy = null,
                proxyError = null,
                authorizationHeader = null,
            )
        }

    private companion object {
        const val NETWORK_ID = 7L

        fun testNetworkEntity(networkId: Long) =
            NetworkEntity(
                id = networkId,
                name = "test",
                role = NetworkRole.DIRECT,
                host = "irc.example.test",
                port = 6697,
                nick = "nick",
                username = "user",
                realname = "real",
            )
    }

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

        override suspend fun setDirectMediaOnProxiedNetworks(enabled: Boolean) {
            state.value = state.value.copy(directMediaOnProxiedNetworks = enabled)
        }
    }
}
