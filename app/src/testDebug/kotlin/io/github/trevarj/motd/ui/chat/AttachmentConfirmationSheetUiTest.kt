package io.github.trevarj.motd.ui.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.AttachmentUploadContext
import io.github.trevarj.motd.attachment.AttachmentUploader
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.UploadProgress
import io.github.trevarj.motd.attachment.UploadRecord
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var pickerRequestCode = 0
    private val pickerRegistry =
        object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                pickerRequestCode = requestCode
            }
        }

    @Test
    fun newTextOpensEditorThenConfirmation() {
        showConditionalAttachments()
        val text = "A new text attachment"

        compose.onNodeWithText(context.getString(R.string.upload_text)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.upload_text_title)).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput(text)
        compose.onNodeWithText(context.getString(R.string.action_continue)).assertIsEnabled().performClick()

        compose.onNodeWithText(context.getString(R.string.upload_text_title)).assertDoesNotExist()
        compose
            .onNodeWithText(context.resources.getQuantityString(R.plurals.upload_character_count, text.length, text.length))
            .assertIsDisplayed()
        compose.onNodeWithTag("attachment_upload").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun currentDraftOpensConfirmation() {
        val draft = "The current draft becomes a text attachment"
        showConditionalAttachments(currentDraft = draft)

        compose.onNodeWithText(context.getString(R.string.upload_draft)).performScrollTo().performClick()

        compose
            .onNodeWithText(context.resources.getQuantityString(R.plurals.upload_character_count, draft.length, draft.length))
            .assertIsDisplayed()
        compose.onNodeWithTag("attachment_upload").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun fileResultAfterPickerIdleOpensConfirmation() {
        showConditionalAttachments()
        val uri = Uri.parse("content://attachments/selected-document.txt")

        compose.onNodeWithText(context.getString(R.string.upload_file)).performScrollTo().performClick()
        compose.onNodeWithTag("attachment_source_sheet").assertDoesNotExist()
        compose.onNodeWithTag("attachment_owner").assertExists()
        returnFromFilePicker(uri)

        compose.onNodeWithText("selected-document.txt").assertIsDisplayed()
        compose.onNodeWithTag("attachment_upload").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun cancelledFilePickerReturnsChooserAndCanSelectAgain() {
        showConditionalAttachments()

        compose.onNodeWithText(context.getString(R.string.upload_file)).performScrollTo().performClick()
        returnFromFilePicker(null)
        compose.onNodeWithTag("attachment_source_sheet").assertIsDisplayed()
        compose.onNodeWithTag("attachment_upload").assertDoesNotExist()

        compose.onNodeWithText(context.getString(R.string.upload_file)).performScrollTo().performClick()
        compose.onNodeWithTag("attachment_source_sheet").assertDoesNotExist()
        returnFromFilePicker(Uri.parse("content://attachments/reselected-document.txt"))

        compose.onNodeWithText("reselected-document.txt").assertIsDisplayed()
        compose.onNodeWithTag("attachment_upload").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun directFilePickerCanCancelThenDeliverAfterIdleAndCloseOwner() {
        showConditionalAttachments(directFileTransferAvailable = true)
        val uri = Uri.parse("content://attachments/direct-document.txt")

        compose.onNodeWithText(context.getString(R.string.dcc_send_file)).performScrollTo().performClick()
        returnFromFilePicker(null)
        compose.onNodeWithTag("attachment_source_sheet").assertIsDisplayed()
        compose.onNodeWithTag("attachment_upload").assertDoesNotExist()

        compose.onNodeWithText(context.getString(R.string.dcc_send_file)).performScrollTo().performClick()
        compose.onNodeWithTag("attachment_source_sheet").assertDoesNotExist()
        compose.onNodeWithTag("attachment_owner").assertExists()
        returnFromFilePicker(uri)

        compose.onNodeWithText(uri.toString()).assertIsDisplayed()
        compose.onNodeWithTag("attachment_owner").assertDoesNotExist()
        compose.onNodeWithTag("attachment_upload").assertDoesNotExist()
    }

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

    private fun showConditionalAttachments(
        currentDraft: String = "",
        directFileTransferAvailable: Boolean = false,
    ) {
        val viewModel =
            AttachmentViewModel(
                prefs =
                    object : AttachmentPrefs {
                        override val config = flowOf(PasteBackendConfig())
                        override val recentUploads = flowOf(emptyList<UploadRecord>())

                        override suspend fun setConfig(config: PasteBackendConfig) = Unit

                        override suspend fun updateConfig(transform: (PasteBackendConfig) -> PasteBackendConfig) = Unit

                        override suspend fun addUpload(record: UploadRecord) = Unit

                        override suspend fun removeUpload(url: String) = Unit
                    },
                uploader =
                    object : AttachmentUploader {
                        override fun upload(
                            source: AttachmentSource,
                            config: PasteBackendConfig,
                            context: AttachmentUploadContext,
                        ): Flow<UploadProgress> = error("Source selection must not upload")

                        override suspend fun delete(record: UploadRecord): Unit = error("Source selection must not delete uploads")
                    },
            )
        val registryOwner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry = pickerRegistry
            }
        compose.setContent {
            DisposableEffect(viewModel) {
                onDispose { viewModel.viewModelScope.cancel() }
            }
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                MotdTheme {
                    var open by remember { mutableStateOf(true) }
                    var directFile by remember { mutableStateOf<Uri?>(null) }
                    if (open) {
                        Box(Modifier.testTag("attachment_owner")) {
                            AttachmentSheets(
                                open = open,
                                currentDraft = currentDraft,
                                networkId = null,
                                sojuFileHostAvailable = false,
                                directFileTransferAvailable = directFileTransferAvailable,
                                onDismiss = { open = false },
                                onInsertUrl = {},
                                onReplaceDraft = {},
                                onDirectFile = { directFile = it },
                                viewModel = viewModel,
                            )
                        }
                    }
                    directFile?.let { Text(it.toString()) }
                }
            }
        }
    }

    private fun returnFromFilePicker(uri: Uri?) {
        compose.runOnIdle {
            pickerRegistry.dispatchResult(
                pickerRequestCode,
                if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                uri?.let { Intent().setData(it) },
            )
        }
    }
}
