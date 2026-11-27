package io.github.trevarj.motd

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.room.Room
import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.AttachmentPrefsImpl
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.AttachmentUploadContext
import io.github.trevarj.motd.attachment.AttachmentUploader
import io.github.trevarj.motd.attachment.LocalUploadPreview
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.UploadProgress
import io.github.trevarj.motd.attachment.UploadRecord
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.ComposerStyle
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.chat.AttachmentViewModel
import io.github.trevarj.motd.ui.chat.ChatContent
import io.github.trevarj.motd.ui.chat.ChatState
import io.github.trevarj.motd.ui.chat.ChatViewModel
import io.github.trevarj.motd.ui.chat.ChatViewModelTest
import io.github.trevarj.motd.ui.chat.ComposerDraftStore
import io.github.trevarj.motd.ui.chat.EntryPositionState
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComposerUploadedImageUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun inFlightImageSurvivesComposerRecreationAndFollowsOnlyItsExactDraftUrl() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AttachmentPrefsImpl(context)
        val file = File.createTempFile("composer-upload", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val thumbnail = LocalUploadPreview(thumbnailBase64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
        val records =
            (1L..4L).map {
                UploadRecord("https://files.example/$it.png", AttachmentBackend.CRAFTERBIN, file.name, "image/png", uploadedAt = it, preview = thumbnail)
            }
        var uploadIndex = 0
        val firstTransferStarted = CompletableDeferred<Unit>()
        val releaseFirstTransfer = CompletableDeferred<Unit>()
        val uploader =
            object : AttachmentUploader {
                override fun upload(
                    source: AttachmentSource,
                    config: PasteBackendConfig,
                    context: AttachmentUploadContext,
                ) = flow {
                    val index = uploadIndex++
                    if (index == 0) {
                        emit(UploadProgress.Transferring(1, 2))
                        firstTransferStarted.complete(Unit)
                        releaseFirstTransfer.await()
                    }
                    val record = records[index]
                    prefs.addUpload(record)
                    emit(UploadProgress.Complete(record))
                }

                override suspend fun delete(record: UploadRecord): Unit = error("No remote deletion expected")
            }
        val attachments = AttachmentViewModel(prefs, uploader)
        var launchedRequestCode = -1
        val pickerOwner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry =
                    object : ActivityResultRegistry() {
                        override fun <I, O> onLaunch(
                            requestCode: Int,
                            contract: ActivityResultContract<I, O>,
                            input: I,
                            options: ActivityOptionsCompat?,
                        ) {
                            launchedRequestCode = requestCode
                        }
                    }
            }
        val home = BufferEntity(id = 1, networkId = 1, name = "#one", displayName = "#one", type = BufferType.CHANNEL, joined = true)
        val other = home.copy(id = 2, name = "#two", displayName = "#two")
        val db =
            Room
                .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        val draftStore = ComposerDraftStore(db)
        runBlocking {
            db.networkDao().insert(NetworkEntity(id = 1, name = "Test network", role = NetworkRole.DIRECT, host = "irc.example", port = 6697, nick = "me", username = "me", realname = "Me"))
            db.bufferDao().insert(home)
            db.bufferDao().insert(other)
            draftStore.saveDraft(home.id, "Look: ", null)
            draftStore.saveDraft(other.id, records.first().url, null)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = ViewModelStore()
        lateinit var homeOwner: ChatViewModel
        lateinit var otherOwner: ChatViewModel
        compose.runOnIdle {
            homeOwner = ChatViewModelTest.externalTranslationFixture(db, home, scope)
            otherOwner = ChatViewModelTest.externalTranslationFixture(db, other, scope)
            store.put("home", homeOwner)
            store.put("other", otherOwner)
        }
        // StateRestorationTester recreates Compose, not the Activity that reports a configuration change.
        val rotatingActivity =
            object : Activity() {
                override fun isChangingConfigurations(): Boolean = true
            }
        var room by mutableStateOf(home)
        var acceptSend = false
        val submissions = mutableListOf<String>()
        val pages = flowOf(PagingData.empty<MessageEntity>())
        val restoration = StateRestorationTester(compose)
        val field = compose.onNodeWithTag("chat_composer_field")
        val card = compose.onNodeWithTag("chat_composer_upload_preview")
        val cardUrl = compose.onNodeWithTag("chat_composer_upload_url")

        fun upload(
            record: UploadRecord,
            whileTransferring: () -> Unit = {},
        ) {
            val cursor = compose.runOnIdle { homeOwner.composerDraft.value.text.length }
            field.performSemanticsAction(SemanticsActions.SetSelection) { it(cursor, cursor, false) }
            compose.onNodeWithTag("chat_composer_attachment").performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.upload_file)).performScrollTo().performClick()
            compose.runOnIdle {
                pickerOwner.activityResultRegistry.dispatchResult(launchedRequestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
            }
            // The fixed footer starts offscreen while the confirmation sheet opens after image preparation.
            val confirm = compose.onNodeWithTag("attachment_upload")
            compose.waitUntil(5_000) { confirm.isDisplayed() }
            confirm.assertIsEnabled().performClick()
            whileTransferring()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasTestTag("chat_composer_upload_url") and hasText(record.url)).fetchSemanticsNodes().isNotEmpty()
            }
            card.assertIsDisplayed().assertHasNoClickAction()
            compose.onNodeWithTag("chat_composer_upload_thumbnail").assertIsDisplayed()
            val thumbnailNode =
                hasTestTag("attachment_record_thumbnail") and hasAnyAncestor(hasTestTag("chat_composer_upload_thumbnail"))
            compose.waitUntil(5_000) { compose.onAllNodes(thumbnailNode).fetchSemanticsNodes().isNotEmpty() }
        }

        try {
            restoration.setContent {
                CompositionLocalProvider(
                    LocalActivityResultRegistryOwner provides pickerOwner,
                    LocalActivity provides rotatingActivity,
                ) {
                    MotdTheme(dynamicColor = false) {
                        val items = pages.collectAsLazyPagingItems()
                        rememberSaveableStateHolder().SaveableStateProvider(room.id) {
                            val owner = if (room == home) homeOwner else otherOwner
                            val draft by owner.composerDraft.collectAsState()
                            val upload by owner.composerUpload.collectAsState()
                            ChatContent(
                                state = ChatState(buffer = room, connState = IrcClientState.Ready("me", emptySet(), emptyMap())),
                                items = items,
                                composerEnabled = true,
                                composerStyle = ComposerStyle.LARGE,
                                onBack = {},
                                onOpenChannelInfo = {},
                                onOpenSearch = {},
                                onOpenMedia = { _, _ -> error("The draft preview is local-only") },
                                onOpenPalette = { _, _ -> },
                                nickNormalizer = String::lowercase,
                                onSubmit = { text, followNewest ->
                                    submissions += text
                                    if (acceptSend) owner.submit(text, onOpenBuffer = {}, followNewest = followNewest)
                                },
                                onTyping = {},
                                onSetReply = {},
                                onReact = { _, _ -> },
                                onRetry = {},
                                loadPreview = { _, _ -> error("The draft preview must not request a network preview") },
                                composerDraft = draft,
                                composerUpload = upload,
                                onBeginComposerUpload = owner::beginComposerUpload,
                                onDraftChanged = owner::saveDraft,
                                attachmentViewModel = attachments,
                                entryState = EntryPositionState.Settled,
                            )
                        }
                    }
                }
            }

            val (first, second, third, fourth) = records
            field.assertTextEquals("Look: ")
            val uploading = compose.onNodeWithText(context.getString(R.string.upload_uploading))
            upload(first) {
                compose.waitUntil(5_000) { firstTransferStarted.isCompleted && uploading.isDisplayed() }
                card.assertDoesNotExist()
                restoration.emulateSavedInstanceStateRestore()
                compose.waitUntil(5_000) { uploading.isDisplayed() }
                compose.runOnIdle {
                    homeOwner.saveDraft("Edited during recreation")
                    releaseFirstTransfer.complete(Unit)
                }
            }
            uploading.assertDoesNotExist()
            field.assertTextEquals("Edited during recreation ${first.url}")
            val caption = "Look (${first.url}), from today "
            field.performTextReplacement(caption)
            cardUrl.assertTextEquals(first.url)
            restoration.emulateSavedInstanceStateRestore()
            cardUrl.assertTextEquals(first.url)
            field.assertTextEquals(caption)

            compose.runOnIdle { room = other }
            field.assertTextEquals(first.url)
            card.assertDoesNotExist()
            compose.runOnIdle { room = home }
            cardUrl.assertTextEquals(first.url)

            val superseded = compose.runOnIdle { homeOwner.beginComposerUpload(home.id, null) }
            upload(second)
            field.assertTextEquals(caption + second.url)
            compose.runOnIdle {
                assertNull(superseded.complete(first, TextFieldValue(caption)))
            }
            cardUrl.assertTextEquals(second.url)
            runBlocking { prefs.removeUpload(second.url) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("chat_composer_upload_preview").fetchSemanticsNodes().isEmpty() }
            field.assertTextEquals(caption + second.url)

            field.performTextReplacement(caption)
            upload(third)
            field.performTextReplacement("$caption${third.url}?other=1")
            card.assertDoesNotExist()
            field.performTextReplacement(caption + third.url)
            card.assertDoesNotExist()

            field.performTextReplacement(caption)
            upload(fourth)
            val outgoing = caption + fourth.url
            compose.onNodeWithTag("chat_composer_send").performClick()
            card.assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(listOf(outgoing), submissions)
                assertEquals(outgoing, homeOwner.composerDraft.value.text)
                // A rejected send republishes the same durable text on a new revision.
                homeOwner.saveDraft(outgoing)
                acceptSend = true
            }
            cardUrl.assertTextEquals(fourth.url)
            compose.onNodeWithTag("chat_composer_send").performClick()
            compose.waitUntil(5_000) {
                // The durable send goes through the Room writer on Robolectric's paused main looper.
                compose.waitForIdle()
                homeOwner.composerDraft.value.text == ""
            }
            compose.runOnIdle { assertEquals(listOf(outgoing, outgoing), submissions) }
            card.assertDoesNotExist()
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))

            val paste = fourth.copy(url = "https://files.example/draft.txt", mimeType = "text/plain")
            compose.runOnIdle {
                val reviewed = "Reviewed text"
                homeOwner.saveDraft(reviewed)
                homeOwner.beginComposerUpload(home.id, reviewed).complete(paste, TextFieldValue(reviewed))
            }
            field.assertTextEquals(paste.url)
            card.assertDoesNotExist()
            compose.runOnIdle {
                val updated = "Updated text"
                val review = homeOwner.beginComposerUpload(home.id, "Original text")
                homeOwner.saveDraft(updated)
                review.complete(paste, TextFieldValue("Original text"))
            }
            field.assertTextEquals("Updated text ${paste.url}")
        } finally {
            val jobs = listOfNotNull(homeOwner.viewModelScope.coroutineContext[Job], otherOwner.viewModelScope.coroutineContext[Job], scope.coroutineContext[Job])
            compose.runOnIdle {
                attachments.viewModelScope.cancel()
                store.clear()
                scope.cancel()
            }
            compose.waitForIdle()
            runBlocking { jobs.forEach { it.join() } }
            db.close()
            file.delete()
        }
    }
}
