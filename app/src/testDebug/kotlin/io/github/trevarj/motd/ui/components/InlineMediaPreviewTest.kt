package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ui.theme.MotdTheme
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class InlineMediaPreviewTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cacheMissWaitsForTapThenLoadsOnceAndOpensOnSecondTap() {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(imageResponse())
            val url = server.url("/${UUID.randomUUID()}.png").toString()
            var grants = 0
            var opens = 0
            compose.setContent {
                var consent by remember { mutableStateOf(false) }
                MotdTheme(dynamicColor = false) {
                    CompositionLocalProvider(
                        LocalAutomaticRemoteMedia provides false,
                        LocalInlineMediaConsent provides
                            RemoteMediaConsent(consent) {
                                grants++
                                consent = true
                            },
                    ) {
                        InlineMediaPreview(
                            url = url,
                            modifier = Modifier.size(160.dp),
                            onImageClick = { opens++ },
                            onLongPress = {},
                        )
                    }
                }
            }

            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("inline_media_awaiting", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(0, server.requestCount)
            compose.onNodeWithTag("inline_media_awaiting", useUnmergedTree = true).performClick()
            compose.waitUntil(10_000) { server.requestCount == 1 }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("inline_media_loaded", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("inline_media_loaded", useUnmergedTree = true).performClick()

            compose.runOnIdle {
                assertEquals(1, grants)
                assertEquals(1, opens)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun failedLoadReturnsToOneTapRetry() {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(imageResponse())
            val url = server.url("/${UUID.randomUUID()}.png").toString()
            compose.setContent {
                var consent by remember { mutableStateOf(false) }
                MotdTheme(dynamicColor = false) {
                    CompositionLocalProvider(
                        LocalAutomaticRemoteMedia provides false,
                        LocalInlineMediaConsent provides RemoteMediaConsent(consent) { consent = true },
                    ) {
                        InlineMediaPreview(
                            url = url,
                            modifier = Modifier.size(160.dp),
                            onImageClick = {},
                            onLongPress = {},
                        )
                    }
                }
            }

            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("inline_media_awaiting", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("inline_media_awaiting", useUnmergedTree = true).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("inline_media_failed", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, server.requestCount)
            compose.onNodeWithTag("inline_media_failed", useUnmergedTree = true).performClick()
            compose.waitUntil(10_000) { server.requestCount == 2 }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("inline_media_loaded", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            server.shutdown()
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
