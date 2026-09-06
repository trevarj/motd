package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.audio.MediaRouteResolver
import io.github.trevarj.motd.audio.NetworkMediaRoute
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.net.InetAddress
import java.net.URL

/**
 * Finding-2 regressions: preview destinations are validated on every hop — HTTPS only, no
 * loopback/private/link-local/multicast/ULA targets — and redirects are followed manually with a
 * bounded hop count.
 */
@RunWith(RobolectricTestRunner::class)
class LinkPreviewSsrfTest {
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
    fun destination_policy_requires_https() {
        assertFalse(LinkPreviewRepositoryImpl.isAllowedDestination(URL("http://example.com/"), resolveDns = true))
        assertFalse(LinkPreviewRepositoryImpl.isAllowedDestination(URL("http://example.com/"), resolveDns = false))
    }

    @Test
    fun restricted_address_literals_are_blocked_with_and_without_dns() {
        val blocked =
            listOf(
                "https://127.0.0.1/",
                "https://2130706433/",
                "https://0x7f000001/",
                "https://0.0.0.0/",
                "https://10.1.2.3/",
                "https://172.16.0.1/",
                "https://192.168.1.1/",
                "https://169.254.9.9/",
                "https://224.0.0.1/",
                "https://[::1]/",
                "https://[fe80::1]/",
                "https://[fc00::1]/",
                "https://[fd12:3456::1]/",
                "https://[::ffff:127.0.0.1]/",
            )
        for (url in blocked) {
            assertFalse(url, LinkPreviewRepositoryImpl.isAllowedDestination(URL(url), resolveDns = true))
            // Even when a proxy resolves hostnames remotely, literal addresses are still refused.
            assertFalse(url, LinkPreviewRepositoryImpl.isAllowedDestination(URL(url), resolveDns = false))
        }
        assertTrue(LinkPreviewRepositoryImpl.isAllowedDestination(URL("https://93.184.216.34/"), resolveDns = true))
        assertTrue(LinkPreviewRepositoryImpl.isAllowedDestination(URL("https://[2606:2800:220:1::1]/"), resolveDns = true))
    }

    @Test
    fun hostnames_are_left_to_the_proxy_but_fail_closed_when_unresolvable_directly() {
        val unresolvable = URL("https://this-host-does-not-exist.invalid/")
        // Via a proxy the hostname must not be resolved locally — that lookup is the leak.
        assertTrue(LinkPreviewRepositoryImpl.isAllowedDestination(unresolvable, resolveDns = false))
        // Directly, an unclassifiable destination is never connected to.
        assertFalse(LinkPreviewRepositoryImpl.isAllowedDestination(unresolvable, resolveDns = true))
    }

    @Test
    fun ipv6_unique_local_range_is_classified_as_disallowed() {
        assertTrue(LinkPreviewRepositoryImpl.isDisallowedAddress(InetAddress.getByName("fc00::1")))
        assertTrue(LinkPreviewRepositoryImpl.isDisallowedAddress(InetAddress.getByName("fdff::1")))
        assertFalse(LinkPreviewRepositoryImpl.isDisallowedAddress(InetAddress.getByName("2606:2800:220:1::1")))
    }

    @Test
    fun production_policy_refuses_cleartext_and_loopback_before_connecting() =
        runTest {
            // Default (production) policy: the MockWebServer is cleartext loopback, so no request may
            // ever reach it, in either scheme form.
            val repository =
                LinkPreviewRepositoryImpl(
                    prefs,
                    directResolver,
                    LinkPreviewFetchPolicy(),
                    this,
                    StandardTestDispatcher(testScheduler),
                )
            server.enqueue(htmlResponse("Leak"))

            assertNull(repository.preview(server.url("/x").toString(), NETWORK_ID))
            assertNull(repository.preview("https://127.0.0.1:${server.port}/x", NETWORK_ID))
            assertEquals(0, server.requestCount)
        }

    @Test
    fun redirects_are_followed_manually_to_the_final_destination() =
        runTest {
            val repository =
                LinkPreviewRepositoryImpl(
                    prefs,
                    directResolver,
                    LinkPreviewFetchPolicy(enforceDestinationPolicy = false),
                    this,
                    StandardTestDispatcher(testScheduler),
                )
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
            server.enqueue(htmlResponse("Landed"))

            val preview = repository.preview(server.url("/start").toString(), NETWORK_ID)

            assertEquals(2, server.requestCount)
            assertEquals("Landed", preview?.title)
            // The preview reports the post-redirect URL, so provenance reflects the real destination.
            assertTrue(preview!!.url.endsWith("/final"))
        }

    @Test
    fun redirect_to_extensionless_image_uses_headers_and_caches_final_image_url() =
        runTest {
            val repository =
                LinkPreviewRepositoryImpl(
                    prefs,
                    directResolver,
                    LinkPreviewFetchPolicy(enforceDestinationPolicy = false),
                    this,
                    StandardTestDispatcher(testScheduler),
                )
            val url = server.url("/start").toString()
            val finalUrl = server.url("/image?id=42").toString()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", finalUrl))
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "image/png; charset=binary")
                    // Deliberately absent body: metadata must complete without downloading the image.
                    .setHeader("Content-Length", 1024 * 1024),
            )

            val preview = repository.preview(url, NETWORK_ID)

            assertEquals(LinkPreviewKind.WEB, preview?.kind)
            assertEquals(finalUrl, preview?.url)
            assertEquals(finalUrl, preview?.imageUrl)
            assertEquals("image", preview?.title)
            assertEquals("image/png", preview?.description)
            assertEquals(server.url("/").host, preview?.siteName)
            assertEquals(preview, repository.cachedPreview(url, NETWORK_ID)?.preview)
            assertEquals(preview, repository.preview(url, NETWORK_ID))
            assertEquals(2, server.requestCount)
        }

    @Test
    fun redirect_chains_are_capped() =
        runTest {
            val repository =
                LinkPreviewRepositoryImpl(
                    prefs,
                    directResolver,
                    LinkPreviewFetchPolicy(enforceDestinationPolicy = false, maxRedirects = 2),
                    this,
                    StandardTestDispatcher(testScheduler),
                )
            repeat(5) { hop ->
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/hop$hop"))
            }

            assertNull(repository.preview(server.url("/start").toString(), NETWORK_ID))
            // The original request plus at most maxRedirects follow-ups.
            assertEquals(3, server.requestCount)
        }

    @Test
    fun a_redirect_without_a_location_is_a_negative_result() =
        runTest {
            val repository =
                LinkPreviewRepositoryImpl(
                    prefs,
                    directResolver,
                    LinkPreviewFetchPolicy(enforceDestinationPolicy = false),
                    this,
                    StandardTestDispatcher(testScheduler),
                )
            server.enqueue(MockResponse().setResponseCode(301))

            assertNull(repository.preview(server.url("/start").toString(), NETWORK_ID))
            assertEquals(1, server.requestCount)
            assertNotNull(repository.cachedPreview(server.url("/start").toString(), NETWORK_ID))
        }

    private fun htmlResponse(title: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/html")
            .setBody("<meta property=\"og:title\" content=\"$title\">")

    private val directResolver =
        MediaRouteResolver { networkId ->
            NetworkMediaRoute(
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
                proxy = null,
                proxyError = null,
                authorizationHeader = null,
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

    private companion object {
        const val NETWORK_ID = 7L
    }
}
