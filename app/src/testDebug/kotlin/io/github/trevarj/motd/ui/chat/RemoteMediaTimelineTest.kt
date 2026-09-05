package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.audio.AudioMetadata
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.repo.CachedLinkPreview
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.repo.RetryableLinkPreviewException
import io.github.trevarj.motd.ui.components.LocalAutomaticRemoteMedia
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteMediaTimelineTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun manualLinkPreviewWaitsForTapLoadsOnceThenOpens() {
        val result = CompletableDeferred<LinkPreview?>()
        var loads = 0
        var opens = 0
        render(
            automatic = false,
            loadPreview = { _, _ ->
                loads++
                result.await()
            },
            onOpenLink = { opens++ },
        )

        awaitTag("link_preview_awaiting")
        assertEquals(0, loads)
        compose.onNodeWithTag("link_preview_awaiting", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { loads == 1 }
        awaitTag("link_preview_loading")
        result.complete(PREVIEW)
        compose.onNodeWithText("Example preview").assertIsDisplayed().performClick()

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
        compose.onNodeWithTag("link_preview_failed", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { loads == 2 }
        compose.onNodeWithText("Example preview").assertIsDisplayed()
    }

    @Test
    fun inlineImageConsentDoesNotStartSiblingLinkFetch() {
        var linkLoads = 0
        render(
            automatic = false,
            text = "https://example.test/image.png $LINK",
            loadPreview = { _, _ ->
                linkLoads++
                PREVIEW
            },
        )

        awaitTag("inline_media_awaiting")
        awaitTag("link_preview_awaiting")
        compose.onNodeWithTag("inline_media_awaiting", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(0, linkLoads)
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
        compose.onNodeWithTag("link_preview_awaiting", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { probes == 1 }
        assertEquals(1, probes)
    }

    private fun render(
        automatic: Boolean,
        cachedPreview: (String, Long?) -> CachedLinkPreview? = { _, _ -> null },
        loadPreview: suspend (String, Long?) -> LinkPreview?,
        loadAudioMetadata: suspend (String, Long?) -> AudioMetadata? = { _, _ -> null },
        onOpenLink: (String) -> Unit = {},
        text: String = LINK,
    ) {
        val pages = flowOf(PagingData.from(listOf(message(text))))
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalAutomaticRemoteMedia provides automatic) {
                    MessageList(
                        items = pages.collectAsLazyPagingItems(),
                        listState = rememberLazyListState(),
                        networkId = 1,
                        readMarkerTime = null,
                        onLongPress = {},
                        onReply = {},
                        onReact = { _, _ -> },
                        onImageClick = {},
                        onRetry = {},
                        loadPreview = loadPreview,
                        richContentReady = true,
                        showImages = true,
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
