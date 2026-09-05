package io.github.trevarj.motd.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class AttachmentConfirmationSheetUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun photoKeepsUploadActionVisible() {
        val file =
            ApplicationProvider
                .getApplicationContext<Context>()
                .cacheDir
                .resolve("attachment-confirmation.png")
        Bitmap.createBitmap(800, 450, Bitmap.Config.ARGB_8888).also { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }

        compose.setContent {
            MotdTheme {
                ConfirmationSheet(
                    source = AttachmentSource.Photo(Uri.fromFile(file), file.name, "image/png", file.length()),
                    config = PasteBackendConfig(),
                    sojuFileHostAvailable = false,
                    onChangeDestination = {},
                    onDismiss = {},
                    onUpload = {},
                )
            }
        }

        compose.onNodeWithTag("attachment_thumbnail").assertIsDisplayed()
        compose.onNodeWithTag("attachment_upload").assertIsDisplayed()
        file.delete()
    }
}
