package io.github.trevarj.motd.ui.chat

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class PhotoPickerSheetUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun cameraPlaceholderAndGalleryPhotoRemainSelectable() {
        val photo = GalleryPhoto(Uri.parse("content://media/external/images/media/42"), "photo.jpg", "image/jpeg", 100)
        var cameraClicks = 0
        var selected: GalleryPhoto? = null

        compose.setContent {
            MotdTheme {
                PhotoPickerSheet(
                    photos = listOf(photo),
                    cameraPermissionGranted = false,
                    photoAccessGranted = true,
                    onCamera = { cameraClicks++ },
                    onRequestPhotoAccess = {},
                    onBrowse = {},
                    onPhoto = { selected = it },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag("attachment_camera_tile").assertIsDisplayed().performClick()
        compose.onNodeWithTag("attachment_photo_${photo.uri}").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, cameraClicks)
            assertEquals(photo, selected)
        }
    }
}
