package io.github.trevarj.motd.ui.imageviewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import coil.request.ImageRequest
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ui.components.LocalNetworkMediaHttp
import io.github.trevarj.motd.ui.components.RoutedInlineMediaFixture
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val IMAGE_LOAD_WAIT_MS = 5_000L

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ImageViewerGestureUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Before
    fun attachClipboardProviderForThisSandbox() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = "${context.packageName}.camera"
        val info = requireNotNull(context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA))
        val provider = FileProvider().apply { attachInfo(context, info) }
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    /** Coil decodes an in-memory bitmap without touching the network, so no fixture server. */
    private fun bitmapRequest(
        width: Int,
        height: Int,
    ): ImageRequest =
        ImageRequest
            .Builder(ApplicationProvider.getApplicationContext<Context>())
            .data(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) })
            .build()

    @Test fun zoomed_wide_image_cannot_pan_into_its_vertical_letterbox() {
        // Built once: a fresh bitmap per composition would restart the image request every frame.
        val wideImage = bitmapRequest(400, 100)
        lateinit var state: ZoomableImageState
        compose.setContent {
            state =
                rememberZoomableImageState(
                    rememberZoomableState(ZoomSpec(maxZoomFactor = MAX_IMAGE_SCALE)),
                )
            MotdTheme(dynamicColor = false) {
                Box(Modifier.size(200.dp, 400.dp)) {
                    ImageViewerContent(
                        model = wideImage,
                        onBack = {},
                        onShare = {},
                        onSave = { ImageSaveFeedback.SAVED },
                        onCopy = { ImageSaveFeedback.SAVED },
                        state = state,
                    )
                }
            }
        }

        // Telephoto ignores gestures until it has measured the loaded image.
        compose.waitUntil(IMAGE_LOAD_WAIT_MS) { compose.runOnIdle { state.isImageDisplayed } }

        compose.onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG).performTouchInput {
            down(0, Offset(75f, 200f))
            down(1, Offset(125f, 200f))
            moveTo(0, Offset(50f, 100f))
            moveTo(1, Offset(150f, 300f))
            up(0)
            up(1)
        }
        compose.onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG).performTouchInput {
            down(Offset(100f, 200f))
            moveTo(Offset(100f, 350f))
            up()
        }
        compose.waitForIdle()

        val afterVerticalPan = currentTransform()
        val viewport = compose.onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG).fetchSemanticsNode().layoutInfo

        assertTrue("pinch should zoom the fitted image", afterVerticalPan.scale > 1f)
        assertEquals(
            "wide content remains vertically centered until it covers the viewport",
            viewport.height / 2f,
            afterVerticalPan.contentBounds.center.y,
            1f,
        )

        // The axis that *does* overflow must stay clamped: no black gap may open at either edge.
        compose.onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG).performTouchInput {
            down(Offset(190f, 200f))
            moveTo(Offset(10f, 200f))
            up()
        }
        compose.waitForIdle()

        val afterHorizontalPan = currentTransform()
        assertTrue(
            "zoomed content cannot be flung past the left viewport edge",
            afterHorizontalPan.contentBounds.left <= 1f,
        )
        assertTrue(
            "zoomed content cannot be flung past the right viewport edge",
            afterHorizontalPan.contentBounds.right >= viewport.width - 1f,
        )
    }

    private fun currentTransform(): ImageViewerTransform =
        compose
            .onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG)
            .fetchSemanticsNode()
            .config[ImageViewerTransformKey]

    @Test fun save_feedback_waits_for_completion_and_allows_retry() {
        val firstResult = CompletableDeferred<ImageSaveFeedback>()
        val squareImage = bitmapRequest(200, 200)
        var saveCalls = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Box(Modifier.size(200.dp, 400.dp)) {
                    ImageViewerContent(
                        model = squareImage,
                        onBack = {},
                        onShare = {},
                        onSave = {
                            saveCalls += 1
                            if (saveCalls == 1) firstResult.await() else ImageSaveFeedback.SAVED
                        },
                        onCopy = { ImageSaveFeedback.SAVED },
                    )
                }
            }
        }

        compose.onNodeWithTag(IMAGE_VIEWER_SAVE_BUTTON_TAG).performClick()
        compose.onNodeWithTag(IMAGE_VIEWER_SAVE_FEEDBACK_TAG).assertDoesNotExist()
        compose.runOnIdle { firstResult.complete(ImageSaveFeedback.FAILED) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose
            .onNodeWithTag(IMAGE_VIEWER_SAVE_FEEDBACK_TAG)
            .assertTextEquals(context.getString(io.github.trevarj.motd.R.string.image_viewer_save_failed))

        compose.onNodeWithTag(IMAGE_VIEWER_SAVE_BUTTON_TAG).performClick()
        compose
            .onNodeWithTag(IMAGE_VIEWER_SAVE_FEEDBACK_TAG)
            .assertTextEquals(context.getString(io.github.trevarj.motd.R.string.image_viewer_saved))
        assertEquals(2, saveCalls)
    }

    @Test fun copy_action_publishes_readable_image_bytes_and_failure_keeps_the_previous_clip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val bytes =
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            )
        val showViewer = mutableStateOf(true)
        RoutedInlineMediaFixture().use { fixture ->
            val url = "http://media.invalid/viewer-copy.png"
            repeat(2) {
                fixture.server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)))
            }
            compose.runOnUiThread { clipboard.setPrimaryClip(ClipData.newPlainText("Previous", "unchanged")) }
            compose.setContent {
                MotdTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalNetworkMediaHttp provides fixture.http) {
                        if (showViewer.value) {
                            ImageViewerScreen(url = url, networkId = fixture.networkId)
                        } else {
                            Text("Viewer closed")
                        }
                    }
                }
            }
            compose.waitUntil(IMAGE_LOAD_WAIT_MS) { fixture.server.requestCount == 1 }
            compose.onNodeWithTag(IMAGE_VIEWER_COPY_BUTTON_TAG).performClick()
            compose.waitUntil(IMAGE_LOAD_WAIT_MS) { clipboard.primaryClip?.getItemAt(0)?.uri != null }
            val clip = requireNotNull(clipboard.primaryClip)
            val uri = requireNotNull(clip.getItemAt(0).uri)
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.camera", uri.authority)
            assertNull(clip.getItemAt(0).text)
            assertTrue(clip.description.hasMimeType("image/png"))
            assertEquals("image/png", context.contentResolver.getType(uri))
            assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertTrue(fixture.selectedNetworks.all { it == fixture.networkId })
            repeat(2) {
                val request = requireNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("GET", request.method)
                assertNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("Proxy-Authorization"))
            }

            fixture.server.enqueue(MockResponse().setResponseCode(500))
            compose.onNodeWithTag(IMAGE_VIEWER_COPY_BUTTON_TAG).performClick()
            compose.waitUntil(IMAGE_LOAD_WAIT_MS) {
                compose.onAllNodesWithTag(IMAGE_VIEWER_COPY_FEEDBACK_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(IMAGE_VIEWER_COPY_FEEDBACK_TAG).assertTextEquals(context.getString(io.github.trevarj.motd.R.string.image_viewer_copy_failed))
            assertEquals(uri, clipboard.primaryClip!!.getItemAt(0).uri)
            compose.runOnIdle { showViewer.value = false }
            compose.onNodeWithText("Viewer closed").assertExists()
            assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun clipboard_destination_cleans_failed_and_cancelled_streams_without_replacing_the_clip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val bytes = byteArrayOf(2, 4, 6, 8)
        val store = ClipboardImageSaveStore(context)
        val first = ImageSaveOperation(ImageSaveConnectionFactory { imageConnection(ByteArrayInputStream(bytes)) }, store)
        assertEquals(ImageSaveResult.Saved, runBlocking { first.save("http://media.invalid/image") })
        val uri = requireNotNull(clipboard.primaryClip!!.getItemAt(0).uri)
        val directory = context.cacheDir.resolve("image-clipboard")
        val completedFiles = directory.listFiles()!!.map { it.name }.toSet()
        try {
            val oversized =
                ImageSaveOperation(
                    ImageSaveConnectionFactory { imageConnection(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))) },
                    store,
                    maxBytes = 4,
                )
            assertEquals(ImageSaveResult.Failed, runBlocking { oversized.save("http://media.invalid/too-large") })
            assertEquals(completedFiles, directory.listFiles()!!.map { it.name }.toSet())
            assertEquals(uri, clipboard.primaryClip!!.getItemAt(0).uri)

            val cancellation = Job()
            val input =
                object : InputStream() {
                    var reads = 0

                    override fun read(): Int = error("Bulk reads only")

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (reads++ > 0) cancellation.cancel()
                        buffer[offset] = 1
                        return 1
                    }
                }
            val cancelled = ImageSaveOperation(ImageSaveConnectionFactory { imageConnection(input) }, store)
            assertThrows(CancellationException::class.java) {
                runBlocking(cancellation) { cancelled.save("http://media.invalid/cancelled") }
            }
            assertEquals(completedFiles, directory.listFiles()!!.map { it.name }.toSet())
            assertEquals(uri, clipboard.primaryClip!!.getItemAt(0).uri)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, "${context.packageName}.camera", context.cacheDir.resolve("private.txt"))
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun copy_button_waits_for_completion_before_allowing_another_copy() {
        val result = CompletableDeferred<ImageSaveFeedback>()
        val image = bitmapRequest(200, 200)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ImageViewerContent(
                    model = image,
                    onBack = {},
                    onShare = {},
                    onSave = { ImageSaveFeedback.SAVED },
                    onCopy = { result.await() },
                )
            }
        }
        compose.onNodeWithTag(IMAGE_VIEWER_COPY_BUTTON_TAG).performClick()
        compose.onNodeWithTag(IMAGE_VIEWER_COPY_BUTTON_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(IMAGE_VIEWER_SAVE_BUTTON_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(IMAGE_VIEWER_COPY_FEEDBACK_TAG).assertDoesNotExist()
        compose.runOnIdle { result.complete(ImageSaveFeedback.FAILED) }
        compose.onNodeWithTag(IMAGE_VIEWER_COPY_FEEDBACK_TAG).assertTextEquals(
            ApplicationProvider.getApplicationContext<Context>().getString(io.github.trevarj.motd.R.string.image_viewer_copy_failed),
        )
    }

    private fun imageConnection(input: InputStream): ImageSaveConnection =
        object : ImageSaveConnection {
            override val responseCode = 200
            override val contentLength = -1L
            override val contentType = "image/png"

            override fun header(name: String): String? = null

            override fun openInputStream(): InputStream = input

            override fun disconnect() = input.close()
        }
}
