package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

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

    @Test
    fun disabled_gate_skips_network_and_cached_metadata() = runTest {
        val repository = LinkPreviewRepositoryImpl(prefs, this, StandardTestDispatcher(testScheduler))
        val url = server.url("/article").toString()
        prefs.setShowLinkPreviews(false)

        assertNull(repository.preview(url))
        assertEquals(0, server.requestCount)

        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody("<meta property=\"og:title\" content=\"Example\">")
        )
        prefs.setShowLinkPreviews(true)
        assertNotNull(repository.preview(url))
        assertNotNull(repository.cachedPreview(url)?.preview)
        assertEquals(1, server.requestCount)

        prefs.setShowLinkPreviews(false)
        assertNull(repository.preview(url))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun completed_negative_result_is_distinct_from_a_cache_miss() = runTest {
        val repository = LinkPreviewRepositoryImpl(prefs, this, StandardTestDispatcher(testScheduler))
        val url = server.url("/binary").toString()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody("not html"),
        )

        assertNull(repository.cachedPreview(url))
        assertNull(repository.preview(url))
        assertNotNull(repository.cachedPreview(url))
        assertNull(repository.cachedPreview(url)?.preview)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cancellation_interrupts_an_active_http_request() = runBlocking {
        val repository = LinkPreviewRepositoryImpl(prefs, this, Dispatchers.IO)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody("<title>Slow</title>xx")
                .throttleBody(1, 200, TimeUnit.MILLISECONDS),
        )

        val request = launch { repository.preview(server.url("/slow").toString()) }
        withTimeout(2_000) {
            while (server.requestCount == 0) delay(10)
        }

        withTimeout(2_000) {
            request.cancel()
            request.join()
        }
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
    }
}
