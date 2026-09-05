package io.github.trevarj.motd.ui.imageviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CompletableDeferred
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val IMAGE_LOAD_WAIT_MS = 5_000L

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ImageViewerGestureUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    /** Coil decodes an in-memory bitmap without touching the network, so no fixture server. */
    private fun bitmap(
        width: Int,
        height: Int,
    ): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

    @Test fun zoomed_wide_image_cannot_pan_into_its_vertical_letterbox() {
        // Built once: a fresh bitmap per composition would restart the image request every frame.
        val wideImage = bitmap(400, 100)
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
                        state = state,
                    )
                }
            }
        }

        // Telephoto ignores gestures until it has measured the loaded image.
        compose.waitUntil(IMAGE_LOAD_WAIT_MS) { state.isImageDisplayed }

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
        val squareImage = bitmap(200, 200)
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
}
