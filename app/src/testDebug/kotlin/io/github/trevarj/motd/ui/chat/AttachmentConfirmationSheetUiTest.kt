package io.github.trevarj.motd.ui.chat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.attachment.AVAILABLE_ATTACHMENT_BACKENDS
import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.AttachmentPrefsImpl
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.AttachmentUploadContext
import io.github.trevarj.motd.attachment.AttachmentUploader
import io.github.trevarj.motd.attachment.AttachmentUploaderImpl
import io.github.trevarj.motd.attachment.LocalUploadPreview
import io.github.trevarj.motd.attachment.OffHostUploadException
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.SOJU_FILEHOST_TOKEN
import io.github.trevarj.motd.attachment.UploadMetadataSanitizer
import io.github.trevarj.motd.attachment.UploadProgress
import io.github.trevarj.motd.attachment.UploadRecord
import io.github.trevarj.motd.attachment.advertisedSojuFileHostUrl
import io.github.trevarj.motd.audio.AudioWaveform
import io.github.trevarj.motd.audio.MediaRouteResolver
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.testing.NoopConnectionManager
import io.github.trevarj.motd.ui.components.Composer
import io.github.trevarj.motd.ui.imageviewer.IMAGE_VIEWER_COPY_BUTTON_TAG
import io.github.trevarj.motd.ui.imageviewer.IMAGE_VIEWER_IMAGE_TAG
import io.github.trevarj.motd.ui.imageviewer.IMAGE_VIEWER_SAVE_BUTTON_TAG
import io.github.trevarj.motd.ui.imageviewer.ImageViewerTransformKey
import io.github.trevarj.motd.ui.settings.AttachmentSettingsViewModel
import io.github.trevarj.motd.ui.settings.UploadsSettingsContent
import io.github.trevarj.motd.ui.share.PendingShare
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class AttachmentConfirmationSheetUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun attachClipboardProviderForThisSandbox() {
        val authority = "${context.packageName}.camera"
        val info = requireNotNull(context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA))
        val provider = FileProvider()
        provider.attachInfo(context, info)
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

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
    fun sharedMetadataPreferenceControlsRealRequestsDespiteRememberedDestinationAndRetry() {
        val prefs = AttachmentPrefsImpl(context)
        val password = "media-test".toCharArray()
        val store = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/network-media.p12")!!.use { store.load(it, password) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        val previousTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        val previousVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        val server = MockWebServer()
        server.useHttps(tls.socketFactory, false)
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
        HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        val file =
            context.cacheDir.resolve("image-clipboard/metadata-ui-private.pdf").apply {
                parentFile!!.mkdirs()
                writeText("%PDF-1.7 /Author(private-location)")
            }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
        val settings = AttachmentSettingsViewModel(prefs)
        val attachments =
            AttachmentViewModel(
                prefs,
                AttachmentUploaderImpl(context, NoopConnectionManager(), MediaRouteResolver { null }, UploadMetadataSanitizer(context), prefs),
            )
        var settingsVisible by mutableStateOf(true)
        try {
            server.start()
            runBlocking {
                prefs.setConfig(PasteBackendConfig(backend = AttachmentBackend.CUSTOM_0X0, customEndpoint = server.url("/upload").toString()))
            }
            compose.setContent {
                DisposableEffect(Unit) {
                    onDispose {
                        settings.viewModelScope.cancel()
                        attachments.viewModelScope.cancel()
                    }
                }
                MotdTheme {
                    if (settingsVisible) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            UploadsSettingsContent(viewModel = settings)
                        }
                    } else {
                        AttachmentSheets(
                            open = true,
                            currentDraft = "",
                            networkId = null,
                            sojuFileHostUrl = null,
                            sharedFile = PendingShare.File(uri, "application/pdf"),
                            onDismiss = {},
                            onInsertUrl = {},
                            onReplaceDraft = {},
                            onOpenNetworkSettings = {},
                            viewModel = attachments,
                        )
                    }
                }
            }
            compose.onNodeWithTag("settings_upload_strip_metadata_row").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(context.getString(R.string.upload_strip_metadata_off_warning)).fetchSemanticsNodes().isNotEmpty()
            }
            assertFalse(runBlocking { AttachmentPrefsImpl(context).config.first().stripMetadata })
            compose.onNodeWithText(context.getString(R.string.upload_strip_metadata_off_warning)).assertIsDisplayed()
            compose.runOnIdle { settingsVisible = false }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("attachment_strip_metadata_row").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("attachment_strip_metadata_row").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.upload_destination_change)).performScrollTo().performClick()
            compose.onNodeWithText(AttachmentBackend.CUSTOM_0X0.label).performScrollTo().performClick()
            runBlocking { prefs.updateConfig { it.copy(backend = AttachmentBackend.UGUU, stripMetadata = true) } }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(context.getString(R.string.upload_strip_metadata_description)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(AttachmentBackend.CUSTOM_0X0.label).assertIsDisplayed()
            compose.onNodeWithTag("attachment_strip_metadata_row").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(context.getString(R.string.upload_strip_metadata_off_warning)).fetchSemanticsNodes().isNotEmpty()
            }
            assertFalse(runBlocking { prefs.config.first().stripMetadata })
            assertEquals(AttachmentBackend.UGUU, runBlocking { prefs.config.first().backend })
            server.enqueue(MockResponse().setResponseCode(500).setBody("Rejected"))
            compose.onNodeWithTag("attachment_upload").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(context.getString(R.string.upload_retry)).fetchSemanticsNodes().isNotEmpty()
            }
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/upload", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains(file.readText()))
            assertTrue(body.contains("filename=\"${file.name}\""))
            runBlocking { prefs.updateConfig { it.copy(stripMetadata = true) } }
            compose.onNodeWithText(context.getString(R.string.upload_retry)).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(context.getString(R.string.upload_send_original)).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(500).setBody("Rejected again"))
            compose.onNodeWithText(context.getString(R.string.upload_send_original)).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(context.getString(R.string.upload_retry)).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(
                server
                    .takeRequest(5, TimeUnit.SECONDS)!!
                    .body
                    .readUtf8()
                    .contains(file.readText()),
            )
            assertTrue(runBlocking { prefs.config.first().stripMetadata })
            compose.onNodeWithText(context.getString(R.string.upload_retry)).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(context.getString(R.string.upload_send_original)).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(2, server.requestCount)

            val newEndpoint = server.url("/new-upload").toString()
            runBlocking { prefs.updateConfig { it.copy(customEndpoint = newEndpoint) } }
            assertEquals(newEndpoint, runBlocking { prefs.config.first().customEndpoint })
            compose.onNodeWithText(context.getString(R.string.upload_send_original)).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(newEndpoint).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(newEndpoint).assertIsDisplayed()
            assertEquals(2, server.requestCount)

            compose.onNodeWithTag("attachment_upload").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(context.getString(R.string.upload_send_original)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(context.getString(R.string.upload_send_original)).assertIsDisplayed()
            assertEquals(2, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(500).setBody("Rejected after renewed consent"))
            compose.onNodeWithText(context.getString(R.string.upload_send_original)).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(context.getString(R.string.upload_retry)).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals("/new-upload", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        } finally {
            attachments.viewModelScope.cancel()
            settings.viewModelScope.cancel()
            server.shutdown()
            HttpsURLConnection.setDefaultSSLSocketFactory(previousTls)
            HttpsURLConnection.setDefaultHostnameVerifier(previousVerifier)
            file.delete()
        }
    }

    @Test
    fun staleUploadActionRequiresConfirmationForNewEndpointStorageOrBackend() {
        val oldEndpoint = "https://old.example/upload"
        val newEndpoint = "https://new.example/upload"
        val initial = PasteBackendConfig(backend = AttachmentBackend.CUSTOM_0X0, endpoint = oldEndpoint, customEndpoint = oldEndpoint)
        val prefs = AttachmentPrefsImpl(context)
        runBlocking { prefs.setConfig(initial) }
        var uploadAttempts = 0
        showConditionalAttachments(
            currentDraft = "A text attachment",
            startWithCurrentDraft = true,
            prefs = prefs,
            uploadFailure = { IOException("Rejected") },
            onUploadAttempt = { uploadAttempts++ },
            expectedUploadBackend = AttachmentBackend.UGUU,
        )
        val upload = compose.onNodeWithTag("attachment_upload")
        compose.onNodeWithText(oldEndpoint).assertIsDisplayed()

        fun changeAndClickStaleAction(config: PasteBackendConfig) {
            val staleUpload = upload.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.mainClock.autoAdvance = false
            try {
                runBlocking { prefs.setConfig(config) }
                assertEquals(config, runBlocking { prefs.config.first() })
                compose.runOnUiThread { assertTrue(staleUpload()) }
            } finally {
                compose.mainClock.autoAdvance = true
            }
            compose.runOnIdle { assertEquals(0, uploadAttempts) }
        }

        val newHost = initial.copy(endpoint = newEndpoint, customEndpoint = newEndpoint)
        changeAndClickStaleAction(newHost)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(newEndpoint).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(newEndpoint).assertIsDisplayed()
        val newStorage = newHost.copy(expiry = "24", secretUrl = false)
        changeAndClickStaleAction(newStorage)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Public link").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Public link").assertIsDisplayed()
        changeAndClickStaleAction(newStorage.copy(backend = AttachmentBackend.UGUU, endpoint = AttachmentBackend.UGUU.endpoint!!))
        compose.waitUntil(5_000) { compose.onAllNodesWithText(AttachmentBackend.UGUU.label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(AttachmentBackend.UGUU.label).assertIsDisplayed()
        upload.assertIsEnabled().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Rejected").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Rejected").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, uploadAttempts) }
    }

    @Test
    fun clearHistoryRequiresConfirmationAndClearsPersistedRecentsWithoutRemoteDeletion() {
        val prefs = AttachmentPrefsImpl(context)
        val records =
            listOf(
                UploadRecord("https://files.example/photo", AttachmentBackend.CRAFTERBIN, "photo.png", "image/png", deletionToken = "token"),
                UploadRecord("https://files.example/text", AttachmentBackend.X0_AT, "text.txt", "text/plain"),
            )
        runBlocking {
            prefs.clearUploadHistory()
            records.forEach { prefs.addUpload(it) }
        }
        showConditionalAttachments(prefs = prefs)

        compose.onNodeWithTag("attachment_clear_history").performScrollTo().performClick()
        compose.onNodeWithTag("attachment_clear_history_confirm").assertIsDisplayed()
        assertEquals(records.reversed(), runBlocking { prefs.recentUploads.first() })
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        compose.onNodeWithTag("attachment_clear_history_confirm").assertDoesNotExist()
        assertEquals(records.reversed(), runBlocking { AttachmentPrefsImpl(context).recentUploads.first() })

        compose.onNodeWithTag("attachment_clear_history").performScrollTo().performClick()
        compose.onNodeWithTag("attachment_clear_history_confirm").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("attachment_clear_history").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("photo.png").assertDoesNotExist()
        compose.onNodeWithText("text.txt").assertDoesNotExist()
        assertEquals(emptyList<UploadRecord>(), runBlocking { AttachmentPrefsImpl(context).recentUploads.first() })
    }

    @Test
    fun recentUploadsRenderLocalMediaTextAndLegacyMetadata() {
        val thumbnail =
            ByteArrayOutputStream().use { out ->
                Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(Color.RED)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    bitmap.recycle()
                }
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }

        fun upload(
            name: String,
            mime: String,
            preview: LocalUploadPreview? = null,
        ) = UploadRecord("https://preview.invalid/$name", AttachmentBackend.CRAFTERBIN, name, mime, 2048L, preview = preview)

        val image = upload("red.png", "image/png", LocalUploadPreview(thumbnailBase64 = thumbnail))
        val video = upload("clip.mp4", "video/mp4", LocalUploadPreview(thumbnailBase64 = thumbnail, durationMillis = 12_000))
        val audio = upload("voice.ogg", "audio/ogg", LocalUploadPreview(durationMillis = 65_000))
        val text = upload("paste.txt", "text/plain", LocalUploadPreview(textSnippet = "Local preview ✓"))
        val pdf = upload("notes.pdf", "application/pdf", LocalUploadPreview(thumbnailBase64 = thumbnail))
        val legacy = upload("old.zip", "application/zip")
        val prefs = AttachmentPrefsImpl(context)
        runBlocking {
            prefs.clearUploadHistory()
            listOf(image, video, audio, text, pdf, legacy).forEach { prefs.addUpload(it) }
        }
        var insertedUrl: String? = null
        var completed: UploadRecord? = null
        showConditionalAttachments(prefs = prefs, onInsertUrl = { insertedUrl = it }, onUploaded = { completed = it })

        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("attachment_record_thumbnail", useUnmergedTree = true).fetchSemanticsNodes().size == 3
        }

        fun row(record: UploadRecord) = hasTestTag("attachment_recent_row") and hasAnyDescendant(hasText(record.displayName))

        fun view(
            record: UploadRecord,
            tag: String,
        ) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(row(record)), useUnmergedTree = true)

        listOf(image, video, audio, text, pdf, legacy).forEach { record ->
            compose.onNode(row(record), useUnmergedTree = true).performScrollTo()
            compose.onNodeWithText("${record.mimeType} • 2.0 KiB", useUnmergedTree = true).assertIsDisplayed()
            when (record) {
                image, video, pdf -> view(record, "attachment_record_thumbnail").assertIsDisplayed()
                else -> view(record, "attachment_record_fallback").assertIsDisplayed()
            }
        }
        view(video, "attachment_recent_duration").performScrollTo().assertTextEquals("0:12")
        view(audio, "attachment_recent_duration").performScrollTo().assertTextEquals("1:05")
        view(text, "attachment_recent_text").performScrollTo().assertTextEquals("Local preview ✓")
        view(text, "attachment_recent_more").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.upload_copy_link)).performClick()
        compose.runOnIdle {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val clip = requireNotNull(clipboard.primaryClip)
            assertEquals(text.url, clip.getItemAt(0).text.toString())
            assertNull(insertedUrl)
        }
        compose.onNode(row(pdf), useUnmergedTree = true).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(pdf.url, insertedUrl)
            assertNull(completed)
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
        compose.onNodeWithTag("attachment_strip_metadata_row").assertDoesNotExist()
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
        compose.onNodeWithTag("attachment_strip_metadata_row").performScrollTo().assertIsDisplayed()
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
    fun dickordPhotoUsesLateFileHostAdvertisementAndPreservesExplicitDestination() {
        val file = context.cacheDir.resolve("image-clipboard/dickord-photo.jpg")
        file.parentFile!!.mkdirs()
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            bitmap.recycle()
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
        try {
            var connection by mutableStateOf(IrcClientState.Ready("me", emptySet(), emptyMap()))
            showConditionalAttachments(
                sojuFileHostUrl = { advertisedSojuFileHostUrl(connection.isupport) },
                preferSojuFileHost = true,
            )
            compose.onNodeWithText(context.getString(R.string.upload_photo)).performScrollTo().performClick()
            compose.onNodeWithTag("attachment_photos_browse").performClick()
            returnFromFilePicker(uri)
            compose.onNodeWithText(AttachmentBackend.CRAFTERBIN.label).assertIsDisplayed()

            compose.runOnIdle {
                connection = connection.copy(isupport = mapOf(SOJU_FILEHOST_TOKEN to "https://irc.example/uploads"))
            }
            compose.onNodeWithText(AttachmentBackend.SOJU_FILEHOST.label).assertIsDisplayed()
            compose.onNodeWithTag("attachment_upload").assertIsEnabled()

            compose.onNodeWithText(context.getString(R.string.upload_destination_change)).performClick()
            compose.onNodeWithText(AttachmentBackend.CRAFTERBIN.label).performScrollTo().performClick()
            compose.runOnIdle { connection = connection.copy(isupport = emptyMap()) }
            compose.onNodeWithText(AttachmentBackend.CRAFTERBIN.label).assertIsDisplayed()
            compose.runOnIdle {
                connection = connection.copy(isupport = mapOf(SOJU_FILEHOST_TOKEN to "https://irc.example/uploads"))
            }
            compose.onNodeWithText(AttachmentBackend.CRAFTERBIN.label).assertIsDisplayed()

            compose.onNodeWithText(context.getString(R.string.upload_destination_change)).performClick()
            compose.onNodeWithText(AttachmentBackend.SOJU_FILEHOST.label).performScrollTo().performClick()
            compose.onNodeWithText(AttachmentBackend.SOJU_FILEHOST.label).assertIsDisplayed()
            compose.onNodeWithTag("attachment_upload").assertIsEnabled()
            compose.onNodeWithText(AttachmentBackend.TERMBIN.label).assertDoesNotExist()
        } finally {
            file.delete()
        }
    }

    @Test
    fun offHostUploadOffersNetworkSettingsButCannotRetryAChangedHost() {
        val networkId = 42L
        var advertisedUrl by mutableStateOf("https://old.example/uploads")
        var uploadAttempts = 0
        var failure: Throwable = IOException("Server rejected upload")
        var openedNetwork: Long? = null
        showConditionalAttachments(
            currentDraft = "A text attachment",
            networkId = networkId,
            startWithCurrentDraft = true,
            sojuFileHostUrl = { advertisedUrl },
            uploadFailure = { failure },
            onUploadAttempt = { uploadAttempts++ },
            onOpenNetworkSettings = { openedNetwork = it },
        )
        compose.onNodeWithText(context.getString(R.string.upload_destination_change)).performClick()
        compose.onNodeWithText(AttachmentBackend.SOJU_FILEHOST.label).performScrollTo().performClick()

        compose.onNodeWithTag("attachment_upload").performClick()
        compose.onNodeWithText("Server rejected upload").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.upload_soju_open_network_settings)).assertDoesNotExist()

        compose.runOnIdle {
            failure = OffHostUploadException("File host differs from network host", networkId)
        }
        compose.onNodeWithText(context.getString(R.string.upload_retry)).performClick()
        compose.onNodeWithText("File host differs from network host").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.upload_soju_off_host_override)).assertIsDisplayed()
        compose.runOnIdle { advertisedUrl = "https://new.example/uploads" }
        compose.onNodeWithText(context.getString(R.string.upload_retry)).performClick()
        compose.onNodeWithText(advertisedUrl).assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, uploadAttempts) }
        compose.onNodeWithTag("attachment_upload").performClick()
        compose.onNodeWithText(context.getString(R.string.upload_soju_open_network_settings)).performClick()
        compose.runOnIdle { assertEquals(networkId, openedNetwork) }
        compose.onNodeWithTag("attachment_owner").assertDoesNotExist()
    }

    @Test
    fun voiceDestinationsOfferEveryBinaryBackendOnceAndSelectDefaultSoju() {
        val config = PasteBackendConfig()
        var selected: PasteBackendConfig? = config
        compose.setContent {
            MotdTheme {
                VoiceDestinationSheet(
                    staged =
                        StagedVoiceMessage(
                            file = File("voice.ogg"),
                            durationMs = 1_000,
                            mimeType = "audio/ogg",
                            extension = "ogg",
                            sizeBytes = 1,
                            encrypted = false,
                            destination = config,
                            waveform = AudioWaveform.EMPTY,
                        ),
                    config = config,
                    onSelect = { selected = it },
                    onDismiss = {},
                )
            }
        }

        val offered =
            compose
                .onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("voice_destination_sheet")))
                .fetchSemanticsNodes()
                .map { it.config[SemanticsProperties.Text].first().text }
        assertEquals(
            AVAILABLE_ATTACHMENT_BACKENDS.filter { it.acceptsBinary }.associate { it.label to 1 },
            offered.groupingBy { it }.eachCount(),
        )
        compose
            .onNodeWithText(AttachmentBackend.SOJU_FILEHOST.label)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertNull(selected) }
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
                    sojuFileHostUrl = null,
                    onChangeDestination = {},
                    onDismiss = {},
                    onUpload = {},
                )
            }
        }

        compose.onNodeWithTag("attachment_thumbnail").assertIsDisplayed()
        compose.onNodeWithTag("attachment_upload").assertIsDisplayed()
        compose.onNodeWithTag("attachment_strip_metadata_row").performScrollTo().assertIsDisplayed()
        file.delete()
    }

    @Test
    fun localImageReviewFitsThenZoomsAndPansWithoutChangingConfirmation() {
        val file = context.cacheDir.resolve("image-clipboard/attachment-review.png")
        file.parentFile!!.mkdirs()
        Bitmap.createBitmap(800, 160, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.RED)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val prefs = AttachmentPrefsImpl(context)
        runBlocking { prefs.setConfig(PasteBackendConfig()) }
        var uploadAttempts = 0
        try {
            showConditionalAttachments(prefs = prefs, onUploadAttempt = { uploadAttempts++ })
            compose.onNodeWithText(context.getString(R.string.upload_file)).performScrollTo().performClick()
            returnFromFilePicker(FileProvider.getUriForFile(context, "${context.packageName}.camera", file))
            compose.onNodeWithTag("attachment_change_destination").performScrollTo().performClick()
            compose.onNodeWithText(AttachmentBackend.X0_AT.label).performScrollTo().performClick()
            compose.onNodeWithTag("attachment_strip_metadata_row").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(context.getString(R.string.upload_strip_metadata_off_warning)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("attachment_thumbnail").performScrollTo().performClick()
            compose.onNodeWithTag("attachment_review_dialog").assertIsDisplayed()
            val image = compose.onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG)

            fun transform() = image.fetchSemanticsNode().config[ImageViewerTransformKey]

            compose.waitUntil(5_000) { transform().contentBounds.width > 0f }
            val fitted = transform()
            val viewport = image.fetchSemanticsNode().layoutInfo
            assertEquals(1f, fitted.scale, 0.01f)
            assertEquals(viewport.width.toFloat(), fitted.contentBounds.width, 1f)
            assertTrue(fitted.contentBounds.top > 0f)
            assertTrue(fitted.contentBounds.bottom < viewport.height)

            image.performTouchInput { doubleClick() }
            compose.waitUntil(5_000) { transform().scale > 2f }
            val zoomed = transform()
            image.performTouchInput { swipe(center, center - Offset(width / 4f, 0f), durationMillis = 400L) }
            assertTrue(transform().offset.x < zoomed.offset.x)
            compose.onNodeWithTag(IMAGE_VIEWER_SAVE_BUTTON_TAG).assertDoesNotExist()
            compose.onNodeWithTag(IMAGE_VIEWER_COPY_BUTTON_TAG).assertDoesNotExist()
            compose.onNodeWithContentDescription(context.getString(R.string.image_viewer_share)).assertDoesNotExist()
            compose.onNodeWithTag("attachment_review_close").performClick()

            compose.onNodeWithTag("attachment_review_dialog").assertDoesNotExist()
            compose.onNodeWithTag("attachment_thumbnail").assertIsDisplayed()
            compose.onNodeWithText(file.name).assertExists()
            compose.onNodeWithText(AttachmentBackend.X0_AT.label).assertExists()
            compose.onNodeWithTag("attachment_strip_metadata_row").performScrollTo().assertIsOff()
            compose.onNodeWithTag("attachment_upload").assertIsEnabled()
            compose.runOnIdle { assertEquals(0, uploadAttempts) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun oversizedAttachmentSwitchesToLargerDestinationAndRetryChecksLiveCap() {
        val mib = 1024L * 1024
        val file = context.cacheDir.resolve("attachment-oversized.bin")
        RandomAccessFile(file, "rw").use { it.setLength(32 * mib) }
        val prefs = AttachmentPrefsImpl(context)
        runBlocking {
            prefs.setConfig(PasteBackendConfig(backend = AttachmentBackend.X0_AT, sizeLimitBytes = 64 * mib))
        }
        var uploadAttempts = 0

        fun limitError(cap: String) = context.getString(R.string.upload_over_size_limit, "32.0 MiB", cap)

        try {
            val attachments =
                showConditionalAttachments(
                    prefs = prefs,
                    uploadFailure = { IOException("Rejected") },
                    onUploadAttempt = { uploadAttempts++ },
                    expectedUploadBackend = AttachmentBackend.X0_AT,
                )
            compose.onNodeWithText(context.getString(R.string.upload_file)).performClick()
            returnFromFilePicker(Uri.fromFile(file))
            compose.onNodeWithTag("attachment_change_destination").performScrollTo().performClick()
            compose.onNodeWithText(AttachmentBackend.CRAFTERBIN.label).performScrollTo().performClick()
            compose.onNodeWithTag("attachment_size_limit_error").assertIsDisplayed().assertTextEquals(limitError("25.0 MiB"))
            compose.onNodeWithTag("attachment_upload").assertIsNotEnabled()
            compose.runOnIdle { assertEquals(0, uploadAttempts) }

            compose.onNodeWithTag("attachment_change_destination").performScrollTo().performClick()
            compose.onNodeWithText(AttachmentBackend.X0_AT.label).performScrollTo().performClick()
            compose.onNodeWithTag("attachment_size_limit_error").assertDoesNotExist()
            compose.onNodeWithTag("attachment_upload").assertIsEnabled().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Rejected").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Rejected").assertIsDisplayed()

            runBlocking { prefs.updateConfig { it.copy(sizeLimitBytes = 30 * mib) } }
            compose.waitUntil(5_000) { compose.runOnIdle { attachments.config.value.sizeLimitBytes == 30 * mib } }
            compose.onNodeWithText(context.getString(R.string.upload_retry)).performClick()
            compose.onNodeWithTag("attachment_size_limit_error").assertIsDisplayed().assertTextEquals(limitError("30.0 MiB"))
            compose.onNodeWithTag("attachment_upload").assertIsNotEnabled()
            compose.onNodeWithText(AttachmentBackend.X0_AT.label).assertIsDisplayed()
            compose.runOnIdle { assertEquals(1, uploadAttempts) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun imageDocumentThumbnailTracksMimeWithoutStartingUpload() {
        val file = context.cacheDir.resolve("document-preview.png")
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val image = AttachmentSource.Document(Uri.fromFile(file), file.name, "image/png", file.length())
        val source = mutableStateOf<AttachmentSource>(image)
        var uploads = 0
        try {
            compose.setContent {
                MotdTheme {
                    ConfirmationSheet(source.value, PasteBackendConfig(), null, {}, {}, { uploads++ })
                }
            }
            compose.onNodeWithTag("attachment_thumbnail").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(0, uploads)
                source.value = image.copy(mimeType = "text/plain")
            }
            compose.onNodeWithTag("attachment_thumbnail").assertDoesNotExist()
            compose.runOnIdle { source.value = image.copy(mimeType = null) }
            compose.onNodeWithTag("attachment_thumbnail").assertDoesNotExist()
            compose.runOnIdle { source.value = image.copy(uri = Uri.parse("https://media.invalid/not-local.png")) }
            compose.onNodeWithTag("attachment_thumbnail").assertDoesNotExist()
            compose.onNodeWithTag("attachment_thumbnail_error").assertIsDisplayed()
            compose.onNodeWithTag("attachment_upload").assertIsNotEnabled()
            compose.runOnIdle { assertEquals(0, uploads) }
            compose.runOnIdle {
                assertEquals(0, uploads)
                source.value = image
            }
            compose.onNodeWithTag("attachment_thumbnail").assertIsDisplayed()
            compose.onNodeWithTag("attachment_upload").performClick()
            compose.runOnIdle { assertEquals(1, uploads) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun nativeImagePasteOpensConfirmationAndCancelPreservesDraftWithoutUploading() {
        val file = context.cacheDir.resolve("image-clipboard/confirmation.png")
        file.parentFile!!.mkdirs()
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
        var uploads = 0
        showConditionalAttachments(currentDraft = "keep this draft", pasteIntoComposer = true, onUploadAttempt = { uploads++ })
        compose.onNodeWithTag("attachment_upload").assertDoesNotExist()
        val field = compose.onNodeWithTag("chat_composer_field")
        field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        field.performSemanticsAction(SemanticsActions.SetSelection) { it(5, 9, false) }
        compose.runOnIdle {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newUri(context.contentResolver, "Image", uri),
            )
        }
        field.performSemanticsAction(SemanticsActions.PasteText) { it() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("attachment_upload").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("attachment_thumbnail").assertIsDisplayed()
        compose.onNodeWithTag("attachment_upload").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        compose.onNodeWithTag("chat_composer_field").assertTextEquals("keep this draft")
        assertEquals(TextRange(5, 9), compose.onNodeWithTag("chat_composer_field").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        compose.runOnIdle { assertEquals(0, uploads) }
        file.delete()
    }

    private fun showConditionalAttachments(
        currentDraft: String = "",
        directFileTransferAvailable: Boolean = false,
        sojuFileHostUrl: () -> String? = { null },
        preferSojuFileHost: Boolean = false,
        networkId: Long? = null,
        startWithCurrentDraft: Boolean = false,
        uploadFailure: (() -> Throwable)? = null,
        onOpenNetworkSettings: (Long) -> Unit = {},
        pasteIntoComposer: Boolean = false,
        onUploadAttempt: () -> Unit = {},
        prefs: AttachmentPrefs? = null,
        expectedUploadBackend: AttachmentBackend = AttachmentBackend.SOJU_FILEHOST,
        onInsertUrl: (String) -> Unit = {},
        onUploaded: ((UploadRecord) -> Unit)? = null,
    ): AttachmentViewModel {
        val viewModel =
            AttachmentViewModel(
                prefs =
                    prefs ?: object : AttachmentPrefs {
                        override val config = flowOf(PasteBackendConfig())
                        override val recentUploads = flowOf(emptyList<UploadRecord>())

                        override suspend fun setConfig(config: PasteBackendConfig) = Unit

                        override suspend fun updateConfig(transform: (PasteBackendConfig) -> PasteBackendConfig) = Unit

                        override suspend fun addUpload(record: UploadRecord) = Unit

                        override suspend fun updateUploadPreview(
                            record: UploadRecord,
                            preview: LocalUploadPreview,
                        ) = Unit

                        override suspend fun removeUpload(url: String) = Unit

                        override suspend fun clearUploadHistory() = Unit
                    },
                uploader =
                    object : AttachmentUploader {
                        override fun upload(
                            source: AttachmentSource,
                            config: PasteBackendConfig,
                            context: AttachmentUploadContext,
                        ): Flow<UploadProgress> {
                            onUploadAttempt()
                            return if (uploadFailure == null) {
                                error("Source selection must not upload")
                            } else {
                                flow {
                                    assertEquals(networkId, context.networkId)
                                    assertEquals(expectedUploadBackend, config.backend)
                                    throw uploadFailure()
                                }
                            }
                        }

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
                    var open by remember { mutableStateOf(!pasteIntoComposer) }
                    var pastedFile by remember { mutableStateOf<PendingShare.File?>(null) }
                    var draft by remember { mutableStateOf(TextFieldValue(currentDraft, TextRange(currentDraft.length))) }
                    if (pasteIntoComposer) {
                        Composer(
                            value = draft,
                            onValueChange = { draft = it },
                            onSend = { error("Paste must not send") },
                            enabled = true,
                            onImageContent = { uri, mime ->
                                pastedFile = PendingShare.File(uri, mime)
                                open = true
                                true
                            },
                        )
                    }
                    var directFile by remember { mutableStateOf<Uri?>(null) }
                    if (open) {
                        Box(Modifier.testTag("attachment_owner")) {
                            AttachmentSheets(
                                open = open,
                                currentDraft = currentDraft,
                                networkId = networkId,
                                sojuFileHostUrl = sojuFileHostUrl(),
                                startWithCurrentDraft = startWithCurrentDraft,
                                preferSojuFileHost = preferSojuFileHost,
                                sharedFile = pastedFile,
                                directFileTransferAvailable = directFileTransferAvailable,
                                onDismiss = { open = false },
                                onInsertUrl = onInsertUrl,
                                onReplaceDraft = {},
                                onOpenNetworkSettings = onOpenNetworkSettings,
                                onDirectFile = { directFile = it },
                                viewModel = viewModel,
                                onUploaded = onUploaded,
                            )
                        }
                    }
                    directFile?.let { Text(it.toString()) }
                }
            }
        }
        return viewModel
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
