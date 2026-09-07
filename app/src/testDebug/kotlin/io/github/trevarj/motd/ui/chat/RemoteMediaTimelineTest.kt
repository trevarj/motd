package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.audio.AudioMetadata
import io.github.trevarj.motd.audio.NetworkMediaHttp
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.repo.CachedLinkPreview
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.repo.RetryableLinkPreviewException
import io.github.trevarj.motd.ui.components.LocalAutomaticRemoteMedia
import io.github.trevarj.motd.ui.components.LocalDirectRemoteMediaAllowed
import io.github.trevarj.motd.ui.components.LocalNetworkMediaHttp
import io.github.trevarj.motd.ui.components.RoutedInlineMediaFixture
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Base64
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class RemoteMediaTimelineTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun manualLinkPreviewWaitsForTapLoadsOnceThenOpens() {
        val result = CompletableDeferred<LinkPreview?>()
        val url = "$LINK/${UUID.randomUUID()}"
        var loads = 0
        var opens = 0
        render(
            automatic = false,
            text = url,
            loadPreview = { _, _ ->
                loads++
                result.await()
            },
            onOpenLink = { opens++ },
        )

        awaitTag("link_preview_awaiting")
        assertEquals(0, loads)
        compose.onNodeWithTag("link_preview_awaiting", useUnmergedTree = true).performTouchInput { click() }
        compose.waitUntil(10_000) { loads == 1 }
        awaitTag("link_preview_loading")
        result.complete(PREVIEW.copy(url = url))
        compose.onNodeWithText("Example preview").assertIsDisplayed().performTouchInput { click() }

        compose.runOnIdle {
            assertEquals(1, loads)
            assertEquals(1, opens)
        }
    }

    @Test
    fun automaticLinkPreviewLoadsOnce() {
        var loads = 0
        render(
            automatic = true,
            loadPreview = { _, _ ->
                loads++
                PREVIEW
            },
        )
        compose.waitUntil(10_000) { loads == 1 }
        compose.onNodeWithText("Example preview").assertIsDisplayed()
        assertEquals(1, loads)
    }

    @Test
    fun cachedLinkPreviewDoesNotFetch() {
        var loads = 0
        render(
            automatic = false,
            cachedPreview = { _, _ -> CachedLinkPreview(PREVIEW) },
            loadPreview = { _, _ ->
                loads++
                PREVIEW
            },
        )
        compose.onNodeWithText("Example preview").assertIsDisplayed()
        assertEquals(0, loads)
    }

    @Test
    fun cachedNegativePreviewDoesNotFetchAndOpensOriginalLink() {
        var loads = 0
        var opens = 0
        render(
            automatic = false,
            cachedPreview = { _, _ -> CachedLinkPreview(null) },
            loadPreview = { _, _ ->
                loads++
                PREVIEW
            },
            onOpenLink = { opens++ },
        )

        awaitTag("link_preview_unavailable")
        assertEquals(0, loads)
        compose.onNodeWithTag("link_preview_unavailable", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun failedPreviewTapRetriesAndThenRendersSuccess() {
        var loads = 0
        render(
            automatic = true,
            loadPreview = { _, _ ->
                loads++
                if (loads == 1) throw RetryableLinkPreviewException("http_status", 503)
                PREVIEW
            },
        )

        awaitTag("link_preview_failed")
        assertEquals(1, loads)
        compose.onNodeWithTag("link_preview_failed", useUnmergedTree = true).performTouchInput { click() }
        compose.waitUntil(10_000) { loads == 2 }
        compose.onNodeWithText("Example preview").assertIsDisplayed()
    }

    @Test
    fun coldImageUsesSelectedRouteWithoutDirectPermissionOrGrantingSiblingLink() {
        RoutedInlineMediaFixture().use { fixture ->
            fixture.server.enqueue(imageResponse())
            val url = "http://media.invalid/${UUID.randomUUID()}.png"
            val showImages = mutableStateOf(false)
            var linkLoads = 0
            val openedImages = mutableListOf<String>()
            render(
                automatic = false,
                networkId = fixture.networkId,
                networkMediaHttp = fixture.http,
                text = "$url $LINK/${UUID.randomUUID()}",
                showImages = { showImages.value },
                onImageClick = openedImages::add,
                loadPreview = { _, _ ->
                    linkLoads++
                    PREVIEW
                },
            )

            // The sibling card proves the cold URL discovery completed while images were gated.
            awaitTag("link_preview_awaiting")
            compose.onNodeWithTag("inline_media_awaiting", useUnmergedTree = true).assertDoesNotExist()
            assertEquals(0, fixture.server.requestCount)
            compose.runOnIdle { showImages.value = true }
            awaitTag("inline_media_awaiting")
            assertEquals(0, fixture.server.requestCount)
            assertEquals(emptyList<Long>(), fixture.selectedNetworks.toList())
            compose.onNodeWithTag("inline_media_awaiting", useUnmergedTree = true).assertIsDisplayed().performTouchInput { click() }
            awaitTag("inline_media_loaded")
            compose.onNodeWithTag("media_origin_caption", useUnmergedTree = true).assertIsDisplayed().assertTextContains("media.invalid")
            compose.runOnIdle { assertEquals(emptyList<String>(), openedImages) }
            compose.onNodeWithTag("inline_media_loaded", useUnmergedTree = true).assertIsDisplayed().performTouchInput { click() }

            compose.runOnIdle {
                assertEquals(listOf(url), openedImages)
                assertEquals(1, fixture.server.requestCount)
                assertEquals(listOf(fixture.networkId), fixture.selectedNetworks.toList())
                assertEquals(0, linkLoads)
            }
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-h891dp")
    fun mediaOriginPreservesTextAndPrivacyAcrossLayouts() {
        RoutedInlineMediaFixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(503))
            val url = "http://media.invalid/a-long-attachment-name-${UUID.randomUUID()}.png?token=keep%2Fexact#original"
            val text = "Before the attachment $url after the attachment"
            val density = mutableStateOf(LayoutDensity.COMFORTABLE)
            val kind = mutableStateOf(MessageKind.PRIVMSG)
            val showImages = mutableStateOf(true)
            val opened = mutableListOf<String>()
            val longPressed = mutableListOf<MessageEntity>()
            var linkLoads = 0
            render(
                automatic = false,
                networkId = fixture.networkId,
                networkMediaHttp = fixture.http,
                text = text,
                showImages = { showImages.value },
                layoutDensity = { density.value },
                messageKind = { kind.value },
                onLongPress = longPressed::add,
                uriHandler =
                    object : UriHandler {
                        override fun openUri(uri: String) {
                            opened.add(uri)
                        }
                    },
                loadPreview = { _, _ ->
                    linkLoads++
                    null
                },
            )

            for (layout in LayoutDensity.entries) {
                for (messageKind in listOf(MessageKind.PRIVMSG, MessageKind.ACTION)) {
                    compose.runOnIdle {
                        density.value = layout
                        kind.value = messageKind
                        showImages.value = true
                    }
                    awaitTag("inline_media_awaiting")
                    val media = compose.onNodeWithTag("inline_media_awaiting", useUnmergedTree = true).assertIsDisplayed()
                    val caption = compose.onNodeWithTag("media_origin_caption", useUnmergedTree = true)
                    caption.assertIsDisplayed().assertTextContains("media.invalid")
                    val prose = compose.onNodeWithText("Before the attachment", substring = true, useUnmergedTree = true)
                    prose.assertIsDisplayed().assertTextContains("after the attachment", substring = true)
                    compose.onNodeWithText(url, substring = true, useUnmergedTree = true).assertDoesNotExist()
                    assertTrue(prose.fetchSemanticsNode().boundsInRoot.bottom <= media.fetchSemanticsNode().boundsInRoot.top)
                    assertTrue(media.fetchSemanticsNode().boundsInRoot.bottom <= caption.fetchSemanticsNode().boundsInRoot.top)

                    caption.performTouchInput { click() }
                    compose.runOnIdle {
                        assertEquals(url, opened.last())
                        assertEquals(0, fixture.server.requestCount)
                        assertEquals(emptyList<Long>(), fixture.selectedNetworks.toList())
                        assertEquals(0, linkLoads)
                    }
                    media.assertIsDisplayed()
                    prose.performTouchInput { longClick() }
                    compose.runOnIdle { assertEquals(message(text).copy(kind = messageKind), longPressed.last()) }

                    compose.runOnIdle { showImages.value = false }
                    compose.onNodeWithText(url, substring = true, useUnmergedTree = true).assertIsDisplayed()
                    caption.assertDoesNotExist()
                    media.assertDoesNotExist()
                }
            }

            compose.runOnIdle { showImages.value = true }
            awaitTag("inline_media_awaiting")
            compose.onNodeWithTag("inline_media_awaiting", useUnmergedTree = true).performTouchInput { click() }
            awaitTag("inline_media_failed")
            compose.onNodeWithTag("media_origin_caption", useUnmergedTree = true).assertIsDisplayed().assertTextContains("media.invalid")
            compose.onNodeWithText(url, substring = true, useUnmergedTree = true).assertDoesNotExist()
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test
    fun linkThumbnailWaitsForConsentThenUsesTheSelectedRoute() {
        RoutedInlineMediaFixture().use { fixture ->
            fixture.server.enqueue(imageResponse())
            val thumbnail = "http://media.invalid/${UUID.randomUUID()}.png"
            var linkLoads = 0
            render(
                automatic = false,
                networkId = fixture.networkId,
                networkMediaHttp = fixture.http,
                loadPreview = { _, networkId ->
                    assertEquals(fixture.networkId, networkId)
                    linkLoads++
                    PREVIEW.copy(imageUrl = thumbnail)
                },
            )

            awaitTag("link_preview_awaiting")
            assertEquals(0, linkLoads)
            assertEquals(0, fixture.server.requestCount)
            compose.onNodeWithTag("link_preview_awaiting", useUnmergedTree = true).performTouchInput { click() }
            awaitTag("link_preview_thumbnail")
            compose.waitUntil(10_000) { fixture.server.requestCount == 1 }
            assertEquals(1, linkLoads)
            assertEquals(listOf(fixture.networkId), fixture.selectedNetworks.toList())
        }
    }

    @Test
    fun extensionlessAudioProbeWaitsForAutomaticPolicyOrLinkTap() {
        var probes = 0
        render(
            automatic = false,
            loadPreview = { _, _ -> null },
            loadAudioMetadata = { url, _ ->
                probes++
                AudioMetadata(url, "audio/ogg", 12)
            },
        )

        awaitTag("link_preview_awaiting")
        assertEquals(0, probes)
        compose.onNodeWithTag("link_preview_awaiting", useUnmergedTree = true).performTouchInput { click() }
        compose.waitUntil(10_000) { probes == 1 }
        assertEquals(1, probes)
    }

    private fun render(
        automatic: Boolean,
        networkId: Long = 1L,
        networkMediaHttp: NetworkMediaHttp? = null,
        cachedPreview: (String, Long?) -> CachedLinkPreview? = { _, _ -> null },
        loadPreview: suspend (String, Long?) -> LinkPreview?,
        loadAudioMetadata: suspend (String, Long?) -> AudioMetadata? = { _, _ -> null },
        onOpenLink: (String) -> Unit = {},
        text: String = LINK,
        showImages: () -> Boolean = { true },
        onImageClick: (String) -> Unit = {},
        layoutDensity: () -> LayoutDensity = { LayoutDensity.COMFORTABLE },
        messageKind: () -> MessageKind = { MessageKind.PRIVMSG },
        onLongPress: (MessageEntity) -> Unit = {},
        uriHandler: UriHandler? = null,
    ) {
        val initialMessage = message(text)
        compose.setContent {
            val kind = messageKind()
            val pages = remember(kind) { flowOf(PagingData.from(listOf(initialMessage.copy(kind = kind)))) }
            MotdTheme(dynamicColor = false, layoutDensity = layoutDensity()) {
                CompositionLocalProvider(
                    LocalAutomaticRemoteMedia provides automatic,
                    LocalDirectRemoteMediaAllowed provides { false },
                    LocalNetworkMediaHttp provides networkMediaHttp,
                    LocalUriHandler provides (uriHandler ?: LocalUriHandler.current),
                ) {
                    MessageList(
                        items = pages.collectAsLazyPagingItems(),
                        listState = rememberLazyListState(),
                        networkId = networkId,
                        readMarkerTime = null,
                        onLongPress = onLongPress,
                        onReply = {},
                        onReact = { _, _ -> },
                        onImageClick = onImageClick,
                        onRetry = {},
                        loadPreview = loadPreview,
                        richContentReady = true,
                        showImages = showImages(),
                        showLinkPreviews = true,
                        onOpenLink = onOpenLink,
                        cachedPreview = cachedPreview,
                        loadAudioMetadata = loadAudioMetadata,
                    )
                }
            }
        }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun imageResponse(): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "image/png")
            .setBody(
                Buffer().write(
                    Base64.getDecoder().decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
                    ),
                ),
            )

    private fun message(text: String) =
        MessageEntity(
            id = 1,
            bufferId = 1,
            msgid = "m1",
            serverTime = 1,
            sender = "alice",
            kind = MessageKind.PRIVMSG,
            text = text,
            isSelf = false,
            dedupKey = "remote-media-test",
            timelineOrder = 1,
        )

    private companion object {
        const val LINK = "https://example.test/audio"
        val PREVIEW = LinkPreview(LINK, "Example preview", null, null, "example.test")
    }
}
