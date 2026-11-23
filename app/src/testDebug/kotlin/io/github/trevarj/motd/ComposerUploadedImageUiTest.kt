package io.github.trevarj.motd

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
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
import io.github.trevarj.motd.data.prefs.ComposerStyle
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.chat.AttachmentViewModel
import io.github.trevarj.motd.ui.chat.ChatContent
import io.github.trevarj.motd.ui.chat.ChatState
import io.github.trevarj.motd.ui.chat.ComposerDraftState
import io.github.trevarj.motd.ui.chat.EntryPositionState
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
    fun uploadedImageFollowsOnlyItsExactDraftUrlAndLiveHistory() {
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
        val uploader =
            object : AttachmentUploader {
                override fun upload(
                    source: AttachmentSource,
                    config: PasteBackendConfig,
                    context: AttachmentUploadContext,
                ) = flowOf(UploadProgress.Complete(records[uploadIndex++]))

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
        var room by mutableStateOf(home)
        var draft by mutableStateOf(ComposerDraftState("Look: ", hydrated = true, revision = 1))
        val submissions = mutableListOf<String>()
        val pages = flowOf(PagingData.empty<MessageEntity>())
        val restoration = StateRestorationTester(compose)
        val field = compose.onNodeWithTag("chat_composer_field")
        val card = compose.onNodeWithTag("chat_composer_upload_preview")
        val cardUrl = compose.onNodeWithTag("chat_composer_upload_url")

        fun upload(record: UploadRecord) {
            val cursor = compose.runOnIdle { draft.text.length }
            field.performSemanticsAction(SemanticsActions.SetSelection) { it(cursor, cursor, false) }
            compose.onNodeWithTag("chat_composer_attachment").performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.upload_file)).performScrollTo().performClick()
            compose.runOnIdle {
                pickerOwner.activityResultRegistry.dispatchResult(launchedRequestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
            }
            compose.onNodeWithTag("attachment_upload").assertIsDisplayed().performClick()
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
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerOwner) {
                    MotdTheme(dynamicColor = false) {
                        val items = pages.collectAsLazyPagingItems()
                        rememberSaveableStateHolder().SaveableStateProvider(room.id) {
                            ChatContent(
                                state = ChatState(buffer = room, connState = IrcClientState.Ready("me", emptySet(), emptyMap())),
                                items = items,
                                composerEnabled = true,
                                composerStyle = ComposerStyle.LARGE,
                                onBack = {},
                                onOpenChannelInfo = {},
                                onOpenSearch = {},
                                onOpenImage = { error("The draft preview is local-only") },
                                onOpenPalette = { _, _ -> },
                                nickNormalizer = String::lowercase,
                                onSubmit = { submissions += it },
                                onTyping = {},
                                onSetReply = {},
                                onReact = { _, _ -> },
                                onRetry = {},
                                loadPreview = { _, _ -> error("The draft preview must not request a network preview") },
                                composerDraft = draft,
                                onDraftChanged = { draft = draft.copy(text = it, revision = draft.revision + 1) },
                                attachmentViewModel = attachments,
                                entryState = EntryPositionState.Settled,
                            )
                        }
                    }
                }
            }

            val (first, second, third, fourth) = records
            upload(first)
            field.assertTextEquals("Look: ${first.url}")
            val caption = "Look (${first.url}), from today "
            field.performTextReplacement(caption)
            cardUrl.assertTextEquals(first.url)
            restoration.emulateSavedInstanceStateRestore()
            cardUrl.assertTextEquals(first.url)
            field.assertTextEquals(caption)

            compose.runOnIdle { room = home.copy(id = 2, name = "#two", displayName = "#two") }
            field.assertTextEquals(caption)
            card.assertDoesNotExist()
            compose.runOnIdle { room = home }
            cardUrl.assertTextEquals(first.url)

            upload(second)
            field.assertTextEquals(caption + second.url)
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
                assertEquals(outgoing, draft.text)
                // A rejected send republishes the durable text on a new revision.
                draft = draft.copy(revision = draft.revision + 1)
            }
            cardUrl.assertTextEquals(fourth.url)
            compose.onNodeWithTag("chat_composer_send").performClick()
            compose.runOnIdle {
                assertEquals(listOf(outgoing, outgoing), submissions)
                draft = draft.copy(text = "", revision = draft.revision + 1)
            }
            card.assertDoesNotExist()
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        } finally {
            compose.runOnIdle { attachments.viewModelScope.cancel() }
            file.delete()
        }
    }
}
