package io.github.trevarj.motd.ui.chat

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.content.IntentCompat
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.trevarj.motd.R
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.audio.AudioMetadata
import io.github.trevarj.motd.audio.NetworkMediaHttp
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.repo.CachedLinkPreview
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.repo.RetryableLinkPreviewException
import io.github.trevarj.motd.ui.components.LinkActionsSheet
import io.github.trevarj.motd.ui.components.LocalAutomaticRemoteMedia
import io.github.trevarj.motd.ui.components.LocalNetworkMediaHttp
import io.github.trevarj.motd.ui.components.RoutedInlineMediaFixture
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
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
        compose.onNodeWithTag("link_preview_awaiting", useUnmergedTree = true).performTouchInput { longClick() }
        assertCopiedLink(url)
        assertEquals(0, loads)
        compose.onNodeWithTag("link_preview_awaiting", useUnmergedTree = true).performTouchInput { click() }
        compose.waitUntil(10_000) { loads == 1 }
        awaitTag("link_preview_loading")
        compose.onNodeWithTag("link_preview_loading", useUnmergedTree = true).performTouchInput { longClick() }
        assertCopiedLink(url)
        result.complete(PREVIEW.copy(url = url))
        compose.onNodeWithText("Example preview").assertIsDisplayed().performTouchInput { click() }

        compose.runOnIdle {
            assertEquals(1, loads)
            assertEquals(1, opens)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-h891dp")
    fun bodyAndCardHoldCopyTheirOwnExactUrlInEveryLayout() {
        val first = "https://example.test/article?ref=one%2Ftwo#details"
        val second = "https://github.com/owner/repo?tab=docs%2Fapi#readme"
        val text = "First $first and $second"
        val density = mutableStateOf(LayoutDensity.COMFORTABLE)
        val shortTaps = mutableListOf<String>()
        val rowHolds = mutableListOf<MessageEntity>()
        val uriHandler =
            object : UriHandler {
                override fun openUri(uri: String) {
                    shortTaps += uri
                }
            }
        render(
            automatic = false,
            text = text,
            layoutDensity = { density.value },
            cachedPreview = { _, _ -> CachedLinkPreview(PREVIEW.copy(url = "https://redirected.test/article")) },
            loadPreview = { _, _ -> null },
            onOpenLink = shortTaps::add,
            onLongPress = rowHolds::add,
            uriHandler = uriHandler,
        )

        for (layout in LayoutDensity.entries) {
            compose.runOnIdle { density.value = layout }
            val body = compose.onNodeWithText(text, substring = true, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            body.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val result = layouts.single()
            val bodyText = result.layoutInput.text.text
            val glyph = result.getBoundingBox(bodyText.indexOf(second) + 2)
            val point = Offset(glyph.left + glyph.width * 0.75f, glyph.center.y)
            val card = compose.onNodeWithText("Example preview")
            card.assertIsDisplayed()

            body.performTouchInput { longClick(point) }
            assertCopiedLink(second)
            card.performTouchInput { longClick() }
            assertCopiedLink(first)
            body.performTouchInput { click(point) }
            card.performTouchInput { click() }

            compose.runOnIdle {
                assertEquals(listOf(second, first), shortTaps)
                assertTrue(rowHolds.isEmpty())
                shortTaps.clear()
            }
        }
    }

    @Test
    fun openBrowserBypassesAppLinkAndNeverFallsBackToIt() {
        val url = "https://github.com/owner/repo?ref=docs%2Fapi#readme"
        val app = RuntimeEnvironment.getApplication()
        val packageShadow = shadowOf(app.packageManager)
        val appShadow = shadowOf(app)

        fun registerHandler(
            name: String,
            host: String? = null,
        ): ComponentName {
            val component = ComponentName(name, "$name.OpenLink")
            packageShadow.addActivityIfNotPresent(component)
            packageShadow.addIntentFilterForActivity(
                component,
                IntentFilter(Intent.ACTION_VIEW).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addDataScheme("https")
                    if (host != null) addDataAuthority(host, null)
                },
            )
            return component
        }

        val github = registerHandler("test.github", "github.com")
        val showing = mutableStateOf(true)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                if (showing.value) LinkActionsSheet(listOf(url)) { showing.value = false }
            }
        }

        compose.onNodeWithTag("link_action_open_default").performClick()
        val implicit = requireNotNull(appShadow.nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, implicit.action)
        assertEquals(url, implicit.dataString)
        assertNull(implicit.component)
        assertEquals(github.packageName, implicit.resolveActivity(app.packageManager)?.packageName)

        compose.runOnIdle { showing.value = true }
        compose.onNodeWithTag("link_action_open_browser").performClick()
        assertNull(appShadow.nextStartedActivity)
        assertEquals(app.getString(R.string.chat_link_no_browser), ShadowToast.getTextOfLatestToast())

        val browser = registerHandler("test.browser")
        compose.runOnIdle { showing.value = true }
        compose.onNodeWithTag("link_action_open_browser").performClick()
        val explicit = requireNotNull(appShadow.nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, explicit.action)
        assertEquals(url, explicit.dataString)
        assertEquals(browser, explicit.component)

        val otherBrowser = registerHandler("test.otherbrowser")
        packageShadow.setShouldShowActivityChooser(true)
        compose.runOnIdle { showing.value = true }
        compose.onNodeWithTag("link_action_open_browser").performClick()
        val chooser = requireNotNull(appShadow.nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val first = requireNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
        val alternatives = requireNotNull(IntentCompat.getParcelableArrayExtra(chooser, Intent.EXTRA_ALTERNATE_INTENTS, Intent::class.java))
        val candidates = listOf(first) + alternatives.filterIsInstance<Intent>()
        assertEquals(2, candidates.size)
        assertEquals(setOf(browser, otherBrowser), candidates.map { it.component }.toSet())
        for (candidate in candidates) {
            assertEquals(Intent.ACTION_VIEW, candidate.action)
            assertEquals(url, candidate.dataString)
        }
    }

    @Test
    fun extensionlessImagePreviewOpensViewerInsteadOfBrowser() {
        RoutedInlineMediaFixture().use { fixture ->
            fixture.server.enqueue(imageResponse())
            val original = "http://media.invalid/${UUID.randomUUID()}"
            val resolved = "$original/photo"
            val openedImages = mutableListOf<String>()
            val openedLinks = mutableListOf<String>()
            render(
                automatic = true,
                networkId = fixture.networkId,
                networkMediaHttp = fixture.http,
                text = original,
                loadPreview = { _, _ ->
                    PREVIEW.copy(url = resolved, imageUrl = resolved, description = "IMAGE/PNG")
                },
                onImageClick = openedImages::add,
                onOpenLink = openedLinks::add,
            )

            awaitTag("link_preview_thumbnail")
            compose.onNodeWithTag("link_preview_thumbnail", useUnmergedTree = true).performTouchInput { click() }
            compose.runOnIdle {
                assertEquals(listOf(resolved), openedImages)
                assertEquals(emptyList<String>(), openedLinks)
            }
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
    fun automaticImagesAfterArticleLoadBothAndOpenTheirOwnViewer() {
        RoutedInlineMediaFixture().use { fixture ->
            fixture.server.enqueue(imageResponse())
            fixture.server.enqueue(imageResponse())
            val prefix = "http://media.invalid/${UUID.randomUUID()}"
            val images = listOf("$prefix/one.png", "$prefix/two.webp")
            val openedImages = mutableListOf<String>()
            render(
                automatic = true,
                networkId = fixture.networkId,
                networkMediaHttp = fixture.http,
                text = "$LINK/article ${images.joinToString(" ")}",
                loadPreview = { _, _ -> null },
                onImageClick = openedImages::add,
            )

            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("inline_media_loaded", useUnmergedTree = true).fetchSemanticsNodes().size == 2
            }
            val loaded = compose.onAllNodesWithTag("inline_media_loaded", useUnmergedTree = true)
            images.indices.forEach { index ->
                loaded[index].assertIsDisplayed().performTouchInput { click() }
            }
            compose.runOnIdle {
                assertEquals(images, openedImages)
                assertEquals(2, fixture.server.requestCount)
                assertEquals(listOf(fixture.networkId, fixture.networkId), fixture.selectedNetworks.toList())
            }
            val requestedUrls = List(2) { checkNotNull(fixture.server.takeRequest().path) }
            assertEquals(images.toSet(), requestedUrls.toSet())
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-h891dp")
    fun mediaOriginPreservesTextAndPrivacyAcrossLayouts() {
        RoutedInlineMediaFixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(503))
            fixture.server.enqueue(imageResponse())
            fixture.server.enqueue(imageResponse())
            val url = "http://media.invalid/a-long-attachment-name-${UUID.randomUUID()}.png?token=keep%2Fexact#original"
            val second = "http://media.invalid/second-${UUID.randomUUID()}.webp"
            val images = listOf(url, second)
            val text = "Before the attachment $url then $second after the attachment"
            val density = mutableStateOf(LayoutDensity.COMFORTABLE)
            val kind = mutableStateOf(MessageKind.PRIVMSG)
            val showImages = mutableStateOf(true)
            val opened = mutableListOf<String>()
            val openedImages = mutableListOf<String>()
            val longPressed = mutableListOf<MessageEntity>()
            render(
                automatic = false,
                networkId = fixture.networkId,
                networkMediaHttp = fixture.http,
                text = text,
                showImages = { showImages.value },
                layoutDensity = { density.value },
                messageKind = { kind.value },
                onLongPress = longPressed::add,
                onImageClick = openedImages::add,
                uriHandler =
                    object : UriHandler {
                        override fun openUri(uri: String) {
                            opened.add(uri)
                        }
                    },
                loadPreview = { _, _ -> null },
            )

            for (layout in LayoutDensity.entries) {
                for (messageKind in listOf(MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION)) {
                    compose.runOnIdle {
                        density.value = layout
                        kind.value = messageKind
                        showImages.value = true
                    }
                    awaitTag("inline_media_awaiting", count = 2)
                    val media = compose.onAllNodesWithTag("inline_media_awaiting", useUnmergedTree = true)
                    val captions = compose.onAllNodesWithTag("media_origin_caption", useUnmergedTree = true)
                    val prose = compose.onNodeWithText("Before the attachment", substring = true, useUnmergedTree = true)
                    prose
                        .assertIsDisplayed()
                        .assertTextContains("after the attachment", substring = true)
                    for (index in images.indices) {
                        compose.onNodeWithText(images[index], substring = true, useUnmergedTree = true).assertDoesNotExist()
                        val preview = media[index].assertIsDisplayed()
                        val caption = captions[index].assertIsDisplayed().assertTextContains("media.invalid")
                        val preceding = if (index == 0) prose else captions[index - 1]
                        assertTrue(preceding.fetchSemanticsNode().boundsInRoot.bottom <= preview.fetchSemanticsNode().boundsInRoot.top)
                        assertTrue(preview.fetchSemanticsNode().boundsInRoot.bottom <= caption.fetchSemanticsNode().boundsInRoot.top)

                        caption.performTouchInput { click() }
                        compose.runOnIdle {
                            assertEquals(images[index], opened.last())
                            assertEquals(0, fixture.server.requestCount)
                            assertEquals(emptyList<Long>(), fixture.selectedNetworks.toList())
                            assertEquals(emptyList<String>(), openedImages)
                        }
                        if (messageKind == MessageKind.PRIVMSG) {
                            caption.performTouchInput { longClick() }
                            assertCopiedLink(images[index])
                        }
                        preview.assertIsDisplayed()
                    }
                    prose.performTouchInput { longClick() }
                    compose.runOnIdle { assertEquals(message(text).copy(kind = messageKind), longPressed.last()) }

                    compose.runOnIdle { showImages.value = false }
                    for (image in images) {
                        compose.onNodeWithText(image, substring = true, useUnmergedTree = true).assertIsDisplayed()
                    }
                    assertTrue(captions.fetchSemanticsNodes().isEmpty())
                    assertTrue(media.fetchSemanticsNodes().isEmpty())
                }
            }

            compose.runOnIdle { showImages.value = true }
            awaitTag("inline_media_awaiting", count = 2)
            compose.onAllNodesWithTag("inline_media_awaiting", useUnmergedTree = true)[0].performTouchInput { click() }
            awaitTag("inline_media_failed")
            compose.onNodeWithTag("inline_media_awaiting", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(1, fixture.server.requestCount)
            assertEquals(url, fixture.server.takeRequest().path)

            compose.onNodeWithTag("inline_media_failed", useUnmergedTree = true).performTouchInput { click() }
            awaitTag("inline_media_loaded")
            compose.onNodeWithTag("inline_media_awaiting", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(2, fixture.server.requestCount)
            assertEquals(url, fixture.server.takeRequest().path)
            compose.onNodeWithTag("inline_media_loaded", useUnmergedTree = true).performTouchInput { click() }
            compose.runOnIdle { assertEquals(listOf(url), openedImages) }

            compose.onNodeWithTag("inline_media_awaiting", useUnmergedTree = true).performTouchInput { click() }
            awaitTag("inline_media_loaded", count = 2)
            compose.onAllNodesWithTag("inline_media_loaded", useUnmergedTree = true)[1].performTouchInput { click() }
            compose.runOnIdle {
                assertEquals(images, openedImages)
                assertEquals(3, fixture.server.requestCount)
                assertEquals(List(3) { fixture.networkId }, fixture.selectedNetworks.toList())
            }
            assertEquals(second, fixture.server.takeRequest().path)
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

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-h891dp")
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun activePlayerViewLongHoldPreservesControllerChildren() {
        RoutedInlineMediaFixture().use { fixture ->
            fixture.server.enqueue(imageResponse())
            // Keep playback active without a decoder error replacing the AndroidView.
            fixture.server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val held = mutableListOf<MessageEntity>()
            render(
                automatic = true,
                networkId = fixture.networkId,
                networkMediaHttp = fixture.http,
                loadPreview = { _, _ -> null },
                text = "http://media.invalid/${UUID.randomUUID()}.mp4",
                onLongPress = held::add,
            )
            awaitTag("inline_media_loaded")
            compose.onNodeWithTag("inline_video_preview", useUnmergedTree = true).performTouchInput { click() }
            compose.runOnIdle {
                val activity =
                    androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                        .getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                        .single()

                fun find(view: android.view.View): androidx.media3.ui.PlayerView? {
                    if (view is androidx.media3.ui.PlayerView) return view
                    if (view is android.view.ViewGroup) {
                        for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                    }
                    return null
                }
                val playerView = checkNotNull(find(activity.window.decorView))
                assertTrue(playerView.player != null)
                assertTrue(playerView.performLongClick())
                assertEquals(1, held.size)
                assertEquals("http://media.invalid/", held.single().text.substringBeforeLast('/') + "/")
                val play = playerView.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)
                assertTrue("controller child remains clickable", play.hasOnClickListeners())
            }
        }
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
                    LocalNetworkMediaHttp provides networkMediaHttp,
                    LocalUriHandler provides (uriHandler ?: LocalUriHandler.current),
                ) {
                    MessageList(
                        onOpenPalette = { _, _ -> },
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

    private fun assertCopiedLink(expected: String) {
        compose.onNodeWithTag("link_action_sheet").assertIsDisplayed()
        compose.onNodeWithTag("link_action_copy").performClick()
        compose.onNodeWithTag("link_action_sheet").assertDoesNotExist()
        compose.runOnIdle {
            val clipboard = RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
            val clip = clipboard.primaryClip
            assertEquals(expected, clip?.getItemAt(0)?.text?.toString())
        }
    }

    private fun awaitTag(
        tag: String,
        count: Int = 1,
    ) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size >= count
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
