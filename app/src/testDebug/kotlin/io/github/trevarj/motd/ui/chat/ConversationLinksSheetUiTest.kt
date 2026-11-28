package io.github.trevarj.motd.ui.chat

import android.content.ClipboardManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import coil.Coil
import coil.EventListener
import coil.decode.DataSource
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.repo.CachedLinkPreview
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.ui.components.LocalAutomaticRemoteMedia
import io.github.trevarj.motd.ui.components.LocalOpenIrcChannelLink
import io.github.trevarj.motd.ui.components.RoutedInlineMediaFixture
import io.github.trevarj.motd.ui.components.routedRemoteMediaData
import io.github.trevarj.motd.ui.components.solidMediaResponse
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class ConversationLinksSheetUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun repeatedLinksHaveSeparateJumpsAndOpenOnlyThroughExplicitActions() {
        val url = "IRCS://chat.test/%23motd"
        val first = ConversationLinkOccurrence(42, 0, url, "alice", 1_760_000_000_000)
        val second = first.copy(urlIndex = 1)
        var links by mutableStateOf<ConversationLinksState?>(ConversationLinksState(listOf(first, second)))
        val jumped = mutableListOf<ConversationLinkOccurrence>()
        val opened = mutableListOf<IrcChannelLink>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalOpenIrcChannelLink provides opened::add) {
                    links?.let { state ->
                        ConversationLinksSheet(
                            state = state,
                            networkId = 1L,
                            showLinkPreviews = false,
                            showImages = false,
                            cachedPreview = { _, _ -> error("Preview setting is disabled") },
                            onLoadOlder = {},
                            onJump = {
                                jumped += it
                                links = null
                            },
                            onDismiss = { links = null },
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("chat_links_title").assertIsDisplayed()
        val link = compose.onNodeWithTag("chat_links_url_42_0")
        val jump = compose.onNodeWithTag("chat_links_jump_42_0")
        jump.assertIsDisplayed().assertContentDescriptionEquals("Jump to message")
        compose.onNodeWithText("Jump to message").assertDoesNotExist()
        val cardBounds = link.getUnclippedBoundsInRoot()
        val jumpBounds = jump.getUnclippedBoundsInRoot()
        assertTrue(jumpBounds.left > (cardBounds.left + cardBounds.right) / 2f && jumpBounds.right <= cardBounds.right)
        link.performTouchInput { click(Offset(width - 28.dp.toPx(), 8.dp.toPx())) }
        compose.onNodeWithTag("link_action_sheet").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(opened.isEmpty())
            assertTrue(jumped.isEmpty())
        }
        compose.onNodeWithTag("link_action_copy").performClick()
        compose.onNodeWithTag("link_action_sheet").assertDoesNotExist()
        compose.runOnIdle {
            val clipboard = RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
            val clip = clipboard.primaryClip?.getItemAt(0)
            assertEquals(url, clip?.text?.toString())
        }

        compose.onNodeWithTag("chat_links_url_42_1").assertIsDisplayed().performClick()
        compose.onNodeWithTag("link_action_join_channel").performClick()
        compose.runOnIdle { assertEquals(listOf(IrcChannelLink("chat.test", 6697, true, "#motd")), opened) }

        compose.onNodeWithTag("chat_links_url_42_0").performClick()
        compose.onNodeWithTag("link_action_sheet").assertIsDisplayed()
        compose.runOnIdle { links = ConversationLinksState(listOf(second)) }
        compose.onNodeWithTag("link_action_sheet").assertDoesNotExist()
        compose.onNodeWithTag("chat_links_url_42_0").assertDoesNotExist()
        compose.onNodeWithText("alice ·", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("chat_links_jump_42_1").performTouchInput { click() }
        compose.onNodeWithTag("chat_links_sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(second), jumped) }
    }

    @Test
    fun emptyPageLoadOlderAndRetryRemainInTheSheet() {
        var state by mutableStateOf(ConversationLinksState(loading = true))
        var loads = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ConversationLinksSheet(
                    state = state,
                    networkId = 1L,
                    showLinkPreviews = false,
                    showImages = false,
                    cachedPreview = { _, _ -> null },
                    onLoadOlder = {
                        loads++
                        state = if (loads == 1) ConversationLinksState(failed = true) else ConversationLinksState()
                    },
                    onJump = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag("chat_links_loading").assertIsDisplayed()
        compose.runOnIdle { state = ConversationLinksState(hasMore = true) }
        compose.onNodeWithText("No links found so far. You can check older saved messages.").assertIsDisplayed()
        compose.onNodeWithTag("chat_links_load_older").performClick()
        compose.onNodeWithTag("chat_links_retry").assertIsDisplayed().performClick()
        compose.onNodeWithText("No links found in saved messages.").assertIsDisplayed()
        compose.onNodeWithTag("chat_links_retry").assertDoesNotExist()
        compose.onNodeWithTag("chat_links_load_older").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, loads) }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun onlyCachedMetadataAndImagesRenderEvenWhenAutomaticMediaIsEnabled() {
        val warmUrl = "http://media.invalid/${UUID.randomUUID()}.png"
        val coldUrl = "http://media.invalid/${UUID.randomUUID()}.png"
        val results = ConcurrentHashMap<String, ImageResult>()
        val listener =
            object : EventListener {
                override fun onError(
                    request: ImageRequest,
                    result: ErrorResult,
                ) {
                    results.putIfAbsent(request.data.toString(), result)
                }

                override fun onSuccess(
                    request: ImageRequest,
                    result: SuccessResult,
                ) {
                    results.putIfAbsent(request.data.toString(), result)
                }
            }
        RoutedInlineMediaFixture(listener).use { fixture ->
            val image = solidMediaResponse()
            fixture.server.enqueue(image)
            val context = RuntimeEnvironment.getApplication()
            val warmRequest = ImageRequest.Builder(context).routedRemoteMediaData(warmUrl, fixture.networkId, networkAllowed = false).build()
            val diskCache = checkNotNull(Coil.imageLoader(context).diskCache)
            val entry = checkNotNull(diskCache.openEditor(checkNotNull(warmRequest.diskCacheKey)))
            // Seed the real Coil disk cache before composing. Empty metadata is Coil's format for
            // an entry explicitly written by the caller; the loader still has to find and decode it.
            diskCache.fileSystem.write(entry.metadata) {}
            val bytes = checkNotNull(image.getBody())
            diskCache.fileSystem.write(entry.data) { write(bytes, bytes.size) }
            entry.commit()
            val first = ConversationLinkOccurrence(3, 0, "https://example.test/warm", "alice", 1_760_000_000_000)
            val second = first.copy(urlIndex = 1, url = "https://example.test/cold")
            val third = first.copy(urlIndex = 2, url = "https://example.test/negative")
            val previews =
                mapOf(
                    first.url to CachedLinkPreview(LinkPreview(first.url, "Cached article", "Saved summary", warmUrl, "Example")),
                    second.url to CachedLinkPreview(LinkPreview(second.url, "Cached title, cold image", null, coldUrl, null)),
                    third.url to CachedLinkPreview(null),
                )
            val networks = mutableSetOf<Long?>()
            var showImages by mutableStateOf(true)
            var showPreviews by mutableStateOf(true)
            compose.setContent {
                MotdTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalAutomaticRemoteMedia provides true) {
                        ConversationLinksSheet(
                            state = ConversationLinksState(listOf(first, second, third)),
                            networkId = fixture.networkId,
                            showLinkPreviews = showPreviews,
                            showImages = showImages,
                            cachedPreview = { url, network ->
                                networks += network
                                previews[url].takeIf { network == fixture.networkId }
                            },
                            onLoadOlder = {},
                            onJump = {},
                            onDismiss = {},
                        )
                    }
                }
            }

            compose.onNodeWithText("Cached article", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("Saved summary", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("Cached title, cold image", useUnmergedTree = true).assertIsDisplayed()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("chat_links_cached_image", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(10_000) { coldUrl in results.keys }
            compose.onAllNodesWithTag("chat_links_cached_image", useUnmergedTree = true).assertCountEquals(1)
            compose.onNodeWithTag("chat_links_url_3_2").assertIsDisplayed()
            assertEquals(DataSource.DISK, (results[warmUrl] as? SuccessResult)?.dataSource)
            assertTrue(results[coldUrl] is ErrorResult)
            compose.onNodeWithTag("link_preview_awaiting").assertDoesNotExist()
            compose.onNodeWithTag("link_preview_loading").assertDoesNotExist()
            assertEquals(0, fixture.server.requestCount)
            assertTrue(fixture.selectedNetworks.isEmpty())
            compose.runOnIdle {
                assertEquals(setOf(fixture.networkId), networks)
                showImages = false
            }
            compose.onAllNodesWithTag("chat_links_cached_image", useUnmergedTree = true).assertCountEquals(0)
            compose.onNodeWithText("Cached article", useUnmergedTree = true).assertIsDisplayed()
            compose.runOnIdle { showPreviews = false }
            compose.onNodeWithText("Cached article", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("chat_links_url_3_0").assertIsDisplayed()
            compose.onNodeWithTag("link_action_sheet").assertDoesNotExist()
            assertEquals(0, fixture.server.requestCount)
        }
    }
}
