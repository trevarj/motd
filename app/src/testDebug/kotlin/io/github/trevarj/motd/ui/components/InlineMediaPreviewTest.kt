package io.github.trevarj.motd.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.audio.MediaRouteResolver
import io.github.trevarj.motd.audio.NetworkMediaHttp
import io.github.trevarj.motd.audio.NetworkMediaRoute
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.repo.LinkPreviewFetchPolicy
import io.github.trevarj.motd.ui.theme.MotdTheme
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
class InlineMediaPreviewTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cacheMissWaitsForTapThenUsesSelectedRouteAndOpens() {
        RoutedInlineMediaFixture().use { fixture ->
            fixture.server.enqueue(imageResponse())
            val url = "http://media.invalid/${UUID.randomUUID()}.png"
            var opens = 0
            compose.setContent {
                MotdTheme(dynamicColor = false) {
                    CompositionLocalProvider(
                        LocalAutomaticRemoteMedia provides false,
                        LocalNetworkMediaHttp provides fixture.http,
                    ) {
                        InlineMediaPreview(
                            url = url,
                            networkId = fixture.networkId,
                            modifier = Modifier.size(160.dp),
                            onImageClick = { opens++ },
                            onLongPress = {},
                        )
                    }
                }
            }

            awaitTag("inline_media_awaiting")
            assertEquals(0, fixture.server.requestCount)
            assertEquals(emptyList<Long>(), fixture.selectedNetworks.toList())
            compose
                .onNodeWithTag("inline_media_awaiting")
                .assertHasClickAction()
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
                .performTouchInput { click() }
            awaitTag("inline_media_loaded")
            compose.runOnIdle { assertEquals(0, opens) }
            compose.onNodeWithTag("inline_media_loaded", useUnmergedTree = true).performTouchInput { click() }

            compose.runOnIdle {
                assertEquals(1, opens)
                assertEquals(1, fixture.server.requestCount)
                assertEquals(listOf(fixture.networkId), fixture.selectedNetworks.toList())
            }
        }
    }

    @Test
    fun failedLoadReturnsToOneTapRetryOnTheSelectedRoute() {
        RoutedInlineMediaFixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(500))
            fixture.server.enqueue(imageResponse())
            val url = "http://media.invalid/${UUID.randomUUID()}.png"
            compose.setContent {
                MotdTheme(dynamicColor = false) {
                    CompositionLocalProvider(
                        LocalAutomaticRemoteMedia provides false,
                        LocalNetworkMediaHttp provides fixture.http,
                    ) {
                        InlineMediaPreview(
                            url = url,
                            networkId = fixture.networkId,
                            modifier = Modifier.size(160.dp),
                            onImageClick = {},
                            onLongPress = {},
                        )
                    }
                }
            }

            awaitTag("inline_media_awaiting")
            compose.onNodeWithTag("inline_media_awaiting", useUnmergedTree = true).performTouchInput { click() }
            awaitTag("inline_media_failed")
            assertEquals(1, fixture.server.requestCount)
            compose.onNodeWithTag("inline_media_failed", useUnmergedTree = true).performTouchInput { click() }
            awaitTag("inline_media_loaded")
            assertEquals(2, fixture.server.requestCount)
            assertEquals(listOf(fixture.networkId, fixture.networkId), fixture.selectedNetworks.toList())
        }
    }

    @Test
    fun videoPlaybackFailureOffersRoutedRetry() {
        RoutedInlineMediaFixture().use { fixture ->
            fixture.server.enqueue(imageResponse())
            fixture.server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody("invalid video"))
            fixture.server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody("invalid video"))
            val url = "http://media.invalid/${UUID.randomUUID()}.mp4"
            compose.setContent {
                MotdTheme(dynamicColor = false) {
                    CompositionLocalProvider(
                        LocalAutomaticRemoteMedia provides true,
                        LocalNetworkMediaHttp provides fixture.http,
                    ) {
                        InlineMediaPreview(
                            url = url,
                            networkId = fixture.networkId,
                            modifier = Modifier.size(160.dp),
                            onImageClick = {},
                            onLongPress = {},
                        )
                    }
                }
            }

            awaitTag("inline_media_loaded")
            compose.onNodeWithTag("inline_video_preview", useUnmergedTree = true).performTouchInput { click() }
            awaitTag("inline_video_failed")
            assertEquals(2, fixture.server.requestCount)
            compose.onNodeWithTag("inline_video_failed", useUnmergedTree = true).performTouchInput { click() }
            compose.waitUntil(10_000) { fixture.server.requestCount == 3 }
            awaitTag("inline_video_failed")
            assertEquals(List(3) { fixture.networkId }, fixture.selectedNetworks.toList())
        }
    }

    @Test
    fun missingVideoClientFailsWithoutStartingAnotherRequest() {
        RoutedInlineMediaFixture().use { fixture ->
            fixture.server.enqueue(imageResponse())
            val url = "http://media.invalid/${UUID.randomUUID()}.mp4"
            compose.setContent {
                MotdTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalAutomaticRemoteMedia provides true) {
                        InlineMediaPreview(
                            url = url,
                            networkId = fixture.networkId,
                            modifier = Modifier.size(160.dp),
                            onImageClick = {},
                            onLongPress = {},
                        )
                    }
                }
            }

            awaitTag("inline_media_loaded")
            compose.onNodeWithTag("inline_video_preview", useUnmergedTree = true).performTouchInput { click() }
            awaitTag("inline_video_failed")
            compose.onNodeWithTag("inline_video_failed", useUnmergedTree = true).performTouchInput { click() }
            compose.runOnIdle { assertEquals(1, fixture.server.requestCount) }
        }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun imageResponse(): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "image/png")
            .setBody(Buffer().write(PNG))

    private companion object {
        val PNG: ByteArray =
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            )
    }
}

/** Real routed HTTP through an explicit proxy; only the fixture destination policy is relaxed. */
internal class RoutedInlineMediaFixture : AutoCloseable {
    val networkId = 41L
    val selectedNetworks = ConcurrentLinkedQueue<Long>()
    val server = MockWebServer().also { it.start() }
    val http =
        NetworkMediaHttp(
            routes =
                MediaRouteResolver { selectedNetworkId ->
                    selectedNetworks += selectedNetworkId
                    if (selectedNetworkId != networkId) {
                        null
                    } else {
                        NetworkMediaRoute(
                            networkId = selectedNetworkId,
                            endpoint =
                                NetworkEntity(
                                    id = selectedNetworkId,
                                    name = "Routed media",
                                    role = NetworkRole.DIRECT,
                                    host = "media.invalid",
                                    port = 6697,
                                    nick = "test",
                                    username = "test",
                                    realname = "test",
                                ),
                            proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", server.port)),
                            proxyError = null,
                            authorizationHeader = "Basic must-not-leak",
                        )
                    }
                },
            fetchPolicy = LinkPreviewFetchPolicy(enforceDestinationPolicy = false),
        )
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val previousImageLoader = Coil.imageLoader(context)
    private val imageLoader = ImageLoader.Builder(context).okHttpClient(http.client).build()

    init {
        Coil.setImageLoader(imageLoader)
    }

    override fun close() {
        Coil.setImageLoader(previousImageLoader)
        imageLoader.shutdown()
        server.shutdown()
    }
}

internal fun solidMediaResponse(): MockResponse {
    val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.Magenta.toArgb())
    val bytes =
        ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
    bitmap.recycle()
    return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))
}

/** Compare native paint with literal circle geometry, away from antialiased boundary pixels. */
internal fun assertMediaCornerPixels(
    image: Bitmap,
    radiusPx: Float,
    fill: Color,
    pixelsPerDp: Float,
    label: String,
    bounds: IntRect = IntRect(0, 0, image.width, image.height),
) {
    val radius = minOf(radiusPx, bounds.width / 2f, bounds.height / 2f)
    val sampleSize = (10 * pixelsPerDp).roundToInt()
    val step = (2 * pixelsPerDp).roundToInt().coerceAtLeast(1)
    for (y in 0 until sampleSize step step) {
        for (x in 0 until sampleSize step step) {
            val distance = hypot((radius - x - .5f).coerceAtLeast(0f), (radius - y - .5f).coerceAtLeast(0f))
            if (radius > 0 && abs(distance - radius) < 2f) continue
            val actual = image.getPixel(bounds.right - 1 - x, bounds.top + y)
            val painted = listOf(24, 16, 8, 0).all { abs(((actual ushr it) and 255) - ((fill.toArgb() ushr it) and 255)) <= 2 }
            if (radius == 0f || distance < radius) {
                assertTrue("$label must paint ($x,$y), radius=$radius actual=${actual.toUInt().toString(16)}", painted)
            } else {
                assertFalse("$label must clip ($x,$y), radius=$radius", painted)
            }
        }
    }
}
