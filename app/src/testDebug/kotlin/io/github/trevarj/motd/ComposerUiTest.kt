package io.github.trevarj.motd

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.prefs.ComposerStyle
import io.github.trevarj.motd.irc.format.IRC_BOLD
import io.github.trevarj.motd.irc.format.IrcColor
import io.github.trevarj.motd.irc.format.IrcTextStyle
import io.github.trevarj.motd.irc.format.parseIrcFormatting
import io.github.trevarj.motd.ui.components.AutocompletePanel
import io.github.trevarj.motd.ui.components.Composer
import io.github.trevarj.motd.ui.components.ComposerReply
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComposerUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose: ComposeContentTestRule = createComposeRule()

    @Before
    fun attachClipboardProviderForThisSandbox() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = "${context.packageName}.camera"
        val info = requireNotNull(context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA))
        // Real attachment refreshes FileProvider's static roots for Robolectric's per-test cache.
        val provider = FileProvider().apply { attachInfo(context, info) }
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    @Test
    fun emojiPicker_opensAlongsideTheComposerInput() {
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("draft"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("chat_composer_input_row").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
    }

    @Test
    fun emojiPicker_toggle_keepsTheComposerInputAvailable() {
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("draft"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_input_row").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()

        // Tools swaps the picker for the compact strip and restores the keyboard.
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("chat_composer_input_row").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        // The picker stays inflated but reports no height, so closing it frees the space without
        // throwing away the populated emoji grid.
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsNotDisplayed()
        assertEquals(
            0,
            compose
                .onNodeWithTag("chat_composer_emoji_panel")
                .fetchSemanticsNode()
                .size.height,
        )
    }

    @Test
    fun emojiPicker_reopensWithTheRetainedPicker() {
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("draft"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_emoji_grid").assertExists()

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.waitForIdle()
        // Retained across the close: reopening reveals the same view instead of re-inflating it and
        // re-running the async category load that made the picker flash blank.
        compose.onNodeWithTag("chat_composer_emoji_grid").assertExists()

        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_emoji_grid").assertExists()
    }

    @Test
    fun emojiPicker_panelHeightComplementsTheImeThroughoutTheAnimation() {
        val imeHeightPx = mutableStateOf(200)
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("draft"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    imeHeightPx = imeHeightPx.value,
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()

        // Simulated keyboard fall and rise. The panel is measured from the same inset value the
        // ancestor imePadding() consumes, so their sum — the space below the input row — never moves.
        var expectedSumPx = -1
        listOf(200, 150, 100, 40, 0, 80, 160, 200).forEach { currentImeHeightPx ->
            compose.runOnIdle { imeHeightPx.value = currentImeHeightPx }
            compose.waitForIdle()
            val panelHeightPx =
                compose
                    .onNodeWithTag("chat_composer_emoji_panel")
                    .fetchSemanticsNode()
                    .size
                    .height
            val sumPx = panelHeightPx + currentImeHeightPx
            if (expectedSumPx < 0) expectedSumPx = sumPx else assertEquals(expectedSumPx, sumPx)
        }
    }

    @Test
    fun autocompletePopup_rowIsClickableOutsideComposerBounds() {
        var picked: String? = null
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("ali"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    autocomplete = {
                        AutocompletePanel(
                            candidates = listOf("alice"),
                            onPick = { picked = it },
                        )
                    },
                )
            }
        }

        compose.onNodeWithText("alice").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("alice", picked)
        }
    }

    @Test
    fun commandAutocomplete_describesCommandsWithoutChangingNickRowsOrPicks() {
        val command = mutableStateOf(true)
        var picked: String? = null
        compose.setContent {
            MotdTheme {
                AutocompletePanel(
                    candidates = if (command.value) listOf("/join", "/away") else listOf("alice"),
                    onPick = { picked = it },
                    isCommand = command.value,
                )
            }
        }

        compose.onNodeWithText("Join a channel", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Set or clear your away status", useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText("/join", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(FontFamily.Monospace, layout.layoutInput.style.fontFamily)
        compose.onNodeWithTag("autocomplete_item_1").performClick()
        compose.runOnIdle { assertEquals("/away", picked) }

        compose.runOnIdle { command.value = false }
        compose.onNodeWithText("alice", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Join a channel", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Set or clear your away status", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("autocomplete_item_0").performClick()
        compose.runOnIdle { assertEquals("alice", picked) }
    }

    @Test
    fun replyWarning_isAboveReplyContentAndInput() {
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("reply"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    reply = ComposerReply("alice", "original"),
                    replyWarning = "alice isn’t currently in this channel",
                )
            }
        }

        val warning =
            compose
                .onNodeWithTag("chat_composer_reply_warning")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val reply =
            compose
                .onNodeWithText("original")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val input = compose.onNodeWithTag("chat_composer_input_row").fetchSemanticsNode().boundsInRoot
        assertTrue(warning.bottom <= reply.top)
        assertTrue(warning.bottom <= input.top)
    }

    @Test
    fun replyBanner_keepsItsContentWhileSendExitRuns() {
        val replyVisible = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("reply"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    reply = ComposerReply("alice", "original"),
                    replyVisible = replyVisible.value,
                )
            }
        }
        compose.onNodeWithText("original").assertIsDisplayed()

        compose.runOnUiThread { replyVisible.value = false }
        compose.mainClock.advanceTimeByFrame()

        // Send starts the exit immediately, while the old quote remains mounted for the fade.
        compose.onNodeWithText("original").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("original").assertDoesNotExist()
    }

    @Test
    fun attachmentBecomesExpandActionAndDraftUploadStaysDirectAfterTyping() {
        val draft = mutableStateOf(TextFieldValue())
        var uploads = 0
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    showEmojiTool = false,
                    ircFormattingEnabled = true,
                    onAttachment = {},
                    onUploadDraft = { uploads++ },
                )
            }
        }
        compose.onNodeWithTag("chat_composer_attachment").assertIsDisplayed()
        compose.onAllNodesWithTag("chat_composer_format_expand").assertCountEquals(0)

        compose.runOnIdle { draft.value = TextFieldValue("x", TextRange(1)) }
        compose.waitForIdle()
        compose.onAllNodesWithTag("chat_composer_attachment").assertCountEquals(0)
        compose.onNodeWithTag("chat_composer_format_expand").assertIsDisplayed().performClick()
        compose
            .onNodeWithTag("chat_composer_upload_draft")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle {
            assertEquals(1, uploads)
            draft.value = TextFieldValue()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_format_expand").assertIsDisplayed()
        compose.onAllNodesWithTag("chat_composer_attachment").assertCountEquals(0)
    }

    @Test
    fun toolbarLongPressShowsHelpWithoutRunningMarkdownOrUpload() {
        val draft = mutableStateOf(TextFieldValue("**hello**", TextRange(2, 7)))
        var edits = 0
        var uploads = 0
        var attachments = 0
        var aiOpens = 0
        var sends = 0
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = {
                        edits++
                        draft.value = it
                    },
                    onSend = { sends++ },
                    enabled = true,
                    composerStyle = ComposerStyle.LARGE,
                    ircFormattingEnabled = true,
                    onAttachment = { attachments++ },
                    onAi = { aiOpens++ },
                    onUploadDraft = { uploads++ },
                )
            }
        }
        val field = compose.onNodeWithTag("chat_composer_field")
        field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        field.performSemanticsAction(SemanticsActions.SetSelection) { it(2, 7, false) }
        compose.onNodeWithTag("chat_format_markdown").assertIsNotDisplayed()
        compose.onNodeWithTag("chat_composer_upload_draft").assertIsNotDisplayed()

        for (tag in listOf("chat_format_markdown", "chat_composer_upload_draft")) {
            val action = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            val before = compose.runOnIdle { draft.value to edits }
            val uploadsBefore = compose.runOnIdle { uploads }
            compose.mainClock.autoAdvance = false
            action.performTouchInput { longClick() }
            compose.mainClock.advanceTimeBy(200)
            val help = compose.onNodeWithTag("${tag}_tooltip").assertIsDisplayed()
            val helpNode = help.fetchSemanticsNode()
            assertTrue(!helpNode.config.contains(SemanticsActions.OnClick))
            compose.runOnIdle {
                assertEquals(before.first, draft.value)
                assertEquals(before.second, edits)
                assertEquals(uploadsBefore, uploads)
                assertEquals(0, attachments)
                assertEquals(0, aiOpens)
                assertEquals(0, sends)
            }

            compose.mainClock.advanceTimeBy(2_000)
            compose.onNodeWithTag("${tag}_tooltip").assertDoesNotExist()
            compose.mainClock.autoAdvance = true
            action.performTouchInput { click() }
            compose.runOnIdle {
                if (tag == "chat_format_markdown") {
                    val formatted = parseIrcFormatting(draft.value.text)
                    assertEquals("hello", formatted.visibleText)
                    assertTrue(formatted.runs.any { it.start == 0 && it.end == 5 && it.state.enabled(IrcTextStyle.BOLD) })
                    assertEquals(0, uploads)
                } else {
                    assertEquals(1, uploads)
                    assertEquals(before.first, draft.value)
                    assertEquals(before.second, edits)
                }
                assertEquals(0, attachments)
                assertEquals(0, aiOpens)
                assertEquals(0, sends)
            }
        }
        field.assertTextEquals("hello")
    }

    @Test
    fun nativeImagePastePreservesDraftAndSelectionWhileTextPasteReplacesSelection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val image = context.cacheDir.resolve("image-clipboard/paste.png")
        image.parentFile!!.mkdirs()
        image.writeBytes(byteArrayOf(1, 2, 3))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.camera", image)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val original = TextFieldValue("before selected after", TextRange(7, 15))
        val draft = mutableStateOf(original)
        val received = mutableListOf<Pair<android.net.Uri, String>>()
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    onImageContent = { imageUri, mime ->
                        received += imageUri to mime
                        true
                    },
                )
            }
        }
        val field = compose.onNodeWithTag("chat_composer_field")
        field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        field.performSemanticsAction(SemanticsActions.SetSelection) { it(7, 15, false) }
        compose.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Image", uri))
        }
        field.performSemanticsAction(SemanticsActions.PasteText) { it() }
        compose.runOnIdle {
            assertEquals("Draft after image paste: ${draft.value}", listOf(uri to "image/png"), received)
            assertEquals(original, draft.value)
            clipboard.setPrimaryClip(ClipData.newPlainText("Text", "replacement"))
        }
        field.performSemanticsAction(SemanticsActions.PasteText) { it() }
        compose.runOnIdle {
            assertEquals(TextFieldValue("before replacement after", TextRange(18)), draft.value)
            assertEquals(listOf(uri to "image/png"), received)
        }
        image.delete()
    }

    @Test
    fun mixedClipboardChecksEachUriAndPastesRemainingText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = context.cacheDir.resolve("image-clipboard").also { it.mkdirs() }
        val image = directory.resolve("mixed.gif").also { it.writeBytes(byteArrayOf(1)) }
        val document = directory.resolve("mixed.txt").also { it.writeText("document text") }
        val imageUri = FileProvider.getUriForFile(context, "${context.packageName}.camera", image)
        val documentUri = FileProvider.getUriForFile(context, "${context.packageName}.camera", document)
        val draft = mutableStateOf(TextFieldValue("draft", TextRange(5)))
        val received = mutableListOf<android.net.Uri>()
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    onImageContent = { uri, _ ->
                        received += uri
                        true
                    },
                )
            }
        }
        val field = compose.onNodeWithTag("chat_composer_field")
        field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        field.performSemanticsAction(SemanticsActions.SetSelection) { it(5, 5, false) }
        compose.runOnIdle {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData("Mixed", arrayOf("image/gif", "text/plain"), ClipData.Item("document text", null, documentUri)).apply {
                    addItem(ClipData.Item(imageUri))
                    addItem(ClipData.Item(" plus text"))
                },
            )
        }
        field.performSemanticsAction(SemanticsActions.PasteText) { it() }
        compose.runOnIdle {
            assertEquals("Draft after mixed paste: ${draft.value}", listOf(imageUri), received)
            assertTrue(draft.value.text.startsWith("draftdocument text"))
            assertTrue(draft.value.text.endsWith(" plus text"))
        }
        image.delete()
        document.delete()
    }

    @Test
    fun collapsedCursorOnBlankLineCanEnableFormattingBeforeTyping() {
        val draft = mutableStateOf(TextFieldValue("first\n", TextRange(6)))
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_format_bold").performClick()
        compose.onNodeWithTag("chat_composer_field").performTextInput("x")
        compose.runOnIdle {
            val formatted = parseIrcFormatting(draft.value.text)
            assertEquals("first\nx", formatted.visibleText)
            assertTrue(formatted.stateAtVisible(6).bold)
        }
    }

    @Test
    fun colorActionOpensAtCollapsedCursorOnBlankLine() {
        val draft = mutableStateOf(TextFieldValue("first\n", TextRange(6)))
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_format_color").performScrollTo().performClick()
        compose.onNodeWithTag("chat_composer_color_sheet").assertIsDisplayed()
        compose.onNodeWithTag("chat_color_4").performClick()
        compose.onNodeWithTag("chat_composer_color_apply").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat_composer_field").performTextInput("x")
        compose.runOnIdle {
            val formatted = parseIrcFormatting(draft.value.text)
            assertEquals("first\nx", formatted.visibleText)
            assertEquals(IrcColor.Numeric(4), formatted.stateAtVisible(6).foreground)
        }
    }

    @Test
    fun clearFormattingIsTheOnlyStrikeoutToolbarAction() {
        val initial = "${IRC_BOLD}hello\nthere$IRC_BOLD"
        val draft = mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onAllNodesWithTag("chat_format_strike").assertCountEquals(0)
        compose.onNodeWithTag("chat_format_clear").performScrollTo().performClick()
        compose.runOnIdle {
            val formatted = parseIrcFormatting(draft.value.text)
            assertEquals("hello\nthere", formatted.visibleText)
            assertTrue(formatted.runs.all { it.state.isDefault })
        }
    }

    @Test
    fun richComposer_expandsAndFormatsSelectionWhilePlainComposerStaysPlain() {
        val draft = mutableStateOf(TextFieldValue("hello\nthere", TextRange(0, 11)))
        val formattingEnabled = mutableStateOf(true)
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = formattingEnabled.value,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.onNodeWithTag("chat_format_bold").performClick()
        compose.runOnIdle {
            val formatted = parseIrcFormatting(draft.value.text)
            assertEquals("hello\nthere", formatted.visibleText)
            assertTrue(formatted.runs.all { it.state.bold })
        }
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("hello\nthere", parseIrcFormatting(draft.value.text).visibleText)
            formattingEnabled.value = false
            draft.value = TextFieldValue("plain")
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("chat_composer_format_expand").assertCountEquals(0)
    }

    @Test
    fun compactAndExpandedModesShareOneToolbar() {
        val draft = mutableStateOf(TextFieldValue("hello", TextRange(5)))
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
    }

    @Test
    fun emojiReplacesExpandedToolsAndReturnsClosed() {
        val draft = mutableStateOf(TextFieldValue("hello", TextRange(5)))
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand rich editor").assertIsDisplayed()

        compose.onNodeWithContentDescription("Close emoji picker").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsNotDisplayed()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
    }

    @Test
    fun completedSlashCommandRendersChipWithoutChangingEditableDraftOrSend() {
        val draft = mutableStateOf(TextFieldValue())
        val formattingEnabled = mutableStateOf(true)
        var sent: String? = null
        var chipColor = Color.Unspecified
        compose.setContent {
            MotdTheme {
                chipColor = MaterialTheme.colorScheme.primary
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = { sent = draft.value.text },
                    enabled = true,
                    ircFormattingEnabled = formattingEnabled.value,
                )
            }
        }

        val field = compose.onNodeWithTag("chat_composer_field")
        val chip = compose.onNodeWithTag("chat_composer_command_chip", useUnmergedTree = true)
        field.performTextInput("/jo")
        chip.assertDoesNotExist()
        field.performTextInput("in")
        chip.assertIsDisplayed()
        val pixels = chip.captureToImage().asAndroidBitmap()
        assertEquals(chipColor.toArgb(), pixels.getPixel(1, pixels.height / 2))
        field.performTextInput(" ")
        chip.assertIsDisplayed()
        field.performTextInput("#motd")
        field.assertTextEquals("/join #motd")
        compose.runOnIdle {
            assertEquals("/join #motd", draft.value.text)
            assertEquals(TextRange(11), draft.value.selection)
        }
        compose.onNodeWithTag("chat_composer_send").performClick()
        compose.runOnIdle { assertEquals("/join #motd", sent) }

        field.performTextReplacement("//join #motd")
        field.assertTextEquals("//join #motd")
        chip.assertDoesNotExist()
        compose.runOnIdle { assertEquals("//join #motd", draft.value.text) }

        compose.runOnIdle {
            formattingEnabled.value = false
            draft.value = TextFieldValue("/join #motd")
        }
        field.assertTextEquals("/join #motd")
        chip.assertDoesNotExist()
    }

    @Test
    fun sendDismissesComposerTools() {
        var sends = 0
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("hello", TextRange(5)),
                    onValueChange = {},
                    onSend = { sends++ },
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_send").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, sends) }
    }

    @Test
    fun styleToolbarsKeepActionsReachable() {
        val draft = mutableStateOf(TextFieldValue("hello", TextRange(5)))
        val style = mutableStateOf(ComposerStyle.COMPACT)
        val formatting = mutableStateOf(false)
        val showEmoji = mutableStateOf(false)
        val showFormatting = mutableStateOf(false)
        val attachmentEnabled = mutableStateOf(true)
        val aiEnabled = mutableStateOf(false)
        val sendEnabled = mutableStateOf(false)
        val imeHeight = mutableStateOf(200)
        var attachments = 0
        var aiOpens = 0
        var sends = 0
        var backs = 0
        var keyboardShows = 0
        var keyboardHides = 0
        var dispatcher: OnBackPressedDispatcher? = null
        var focusManager: FocusManager? = null
        val keyboard =
            object : SoftwareKeyboardController {
                override fun show() {
                    keyboardShows++
                }

                override fun hide() {
                    keyboardHides++
                }
            }
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            focusManager = LocalFocusManager.current
            BackHandler { backs++ }
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                MotdTheme(dynamicColor = false) {
                    Composer(
                        value = draft.value,
                        onValueChange = { draft.value = it },
                        onSend = {
                            sends++
                            draft.value = TextFieldValue()
                        },
                        enabled = true,
                        sendEnabled = sendEnabled.value,
                        composerStyle = style.value,
                        currentNick = "alex",
                        showEmojiTool = showEmoji.value,
                        showFormattingTools = showFormatting.value,
                        ircFormattingEnabled = formatting.value,
                        onAttachment = if (attachmentEnabled.value) ({ attachments++ }) else null,
                        onAi = if (aiEnabled.value) ({ aiOpens++ }) else null,
                        imeHeightPx = imeHeight.value,
                    )
                }
            }
        }
        val field = compose.onNodeWithTag("chat_composer_field")
        compose.onNodeWithTag("chat_composer_send").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, sends) }
        compose.onNodeWithTag("chat_composer_attachment").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_tools").performClick().assertIsSelected()
        compose.onNodeWithTag("chat_composer_emoji").assertDoesNotExist()
        compose.onNodeWithTag("chat_format_bold").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_attachment").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, attachments)
            assertEquals("hello", draft.value.text)
            assertTrue(keyboardHides > 0)
            formatting.value = true
            aiEnabled.value = true
        }
        for (tag in listOf("chat_composer_attachment", "chat_composer_ai", "chat_composer_format_expand")) {
            compose.onAllNodesWithTag(tag).assertCountEquals(1)
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("chat_composer_ai").performScrollTo().performClick()
        compose
            .onNodeWithTag("chat_composer_format_expand")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        field.performSemanticsAction(SemanticsActions.SetSelection) { it(1, 4, false) }
        compose.onNodeWithTag("chat_format_bold").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, aiOpens)
            val parsed = parseIrcFormatting(draft.value.text)
            assertEquals("hello", parsed.visibleText)
            assertTrue(parsed.runs.any { it.start == 1 && it.end == 4 && it.state.enabled(IrcTextStyle.BOLD) })
            assertTrue(parsed.runs.none { (it.start < 1 || it.end > 4) && it.state.enabled(IrcTextStyle.BOLD) })
        }
        compose.runOnIdle { checkNotNull(dispatcher).onBackPressed() }
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, backs)
            style.value = ComposerStyle.LARGE
            showEmoji.value = true
            showFormatting.value = true
            focusManager?.clearFocus(force = true)
        }
        field.assertIsNotFocused()
        compose.onNodeWithTag("chat_composer_tools").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        for (tag in listOf("chat_composer_attachment", "chat_composer_ai", "chat_composer_format_expand")) {
            compose.onAllNodesWithTag(tag).assertCountEquals(1)
        }
        compose.onNodeWithTag("chat_composer_attachment").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(2, attachments)
            checkNotNull(dispatcher).onBackPressed()
        }
        compose.runOnIdle {
            assertEquals(1, backs)
            sendEnabled.value = true
        }
        compose.onNodeWithTag("chat_composer_send").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_send").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, sends)
            assertEquals("", draft.value.text)
            checkNotNull(dispatcher).onBackPressed()
        }
        compose.runOnIdle { assertEquals(2, backs) }
        compose.onNodeWithTag("chat_format_bold").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()

        field.performClick()
        compose.onNodeWithTag("chat_composer_emoji").performScrollTo().performClick()
        compose.runOnIdle { imeHeight.value = 0 }
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.runOnIdle { checkNotNull(dispatcher).onBackPressed() }
        compose.runOnIdle { imeHeight.value = 200 }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsNotDisplayed()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(keyboardShows > 0)
            assertEquals(2, backs)
            style.value = ComposerStyle.COMPACT
        }
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performScrollTo().performClick()
        compose.runOnIdle { imeHeight.value = 0 }
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.runOnIdle { imeHeight.value = 200 }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsNotDisplayed()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.runOnIdle {
            attachmentEnabled.value = false
            aiEnabled.value = false
            formatting.value = false
            showEmoji.value = false
            showFormatting.value = false
        }
        compose.onNodeWithTag("chat_composer_tools").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
        compose.runOnIdle { style.value = ComposerStyle.LARGE }
        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
    }

    @Test
    fun colorSheet_appliesForegroundAndBackgroundAndFormattingOnlyCannotSend() {
        val draft = mutableStateOf(TextFieldValue("hello\nthere", TextRange(0, 11)))
        compose.setContent {
            MotdTheme {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }
        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_format_color").performScrollTo().performClick()
        compose.onNodeWithTag("chat_color_4").performClick()
        compose.onNodeWithText("Background").performClick()
        compose.onNodeWithTag("chat_color_1").performClick()
        compose.onNodeWithTag("chat_composer_color_apply").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val state = parseIrcFormatting(draft.value.text).runs.single().state
            assertEquals(IrcColor.Numeric(4), state.foreground)
            assertEquals(IrcColor.Numeric(1), state.background)
        }

        compose.runOnIdle { draft.value = TextFieldValue("$IRC_BOLD$IRC_BOLD") }
        compose.onNodeWithTag("chat_composer_send").assertIsNotEnabled()
    }

    @Test
    fun semanticVoiceActivation_startsOneLockedRecordingAndStopsWhenActive() {
        var starts = 0
        var stops = 0
        val recording = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        val voiceEnabled = mutableStateOf(true)
        val style = mutableStateOf(ComposerStyle.COMFORTABLE)
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue(),
                    onValueChange = {},
                    onSend = {},
                    enabled = enabled.value,
                    voiceEnabled = voiceEnabled.value,
                    voiceRecording = recording.value,
                    composerStyle = style.value,
                    onVoiceAccessibilityStart = {
                        starts++
                        recording.value = true
                    },
                    onVoiceHoldStop = {
                        stops++
                        recording.value = false
                    },
                )
            }
        }
        for (selectedStyle in listOf(ComposerStyle.COMFORTABLE, ComposerStyle.COMPACT)) {
            compose.runOnIdle { style.value = selectedStyle }
            val pill = compose.onNodeWithTag("chat_composer_pill").fetchSemanticsNode().boundsInRoot
            val voice =
                compose
                    .onNodeWithTag("chat_composer_voice")
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            val field = compose.onNodeWithTag("chat_composer_field").fetchSemanticsNode().boundsInRoot
            val tools = compose.onNodeWithTag("chat_composer_tools").fetchSemanticsNode().boundsInRoot
            assertTrue(voice.left >= pill.left && voice.right < pill.right)
            assertTrue(voice.top > pill.top && voice.bottom < pill.bottom)
            assertTrue(!voice.overlaps(field) && !voice.overlaps(tools))
        }
        compose.onNodeWithTag("chat_composer_voice").performTouchInput { click() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, starts) }

        compose
            .onNodeWithTag("chat_composer_voice")
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, starts) }

        compose.onNodeWithTag("chat_composer_voice").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1, stops)
        }

        compose.runOnIdle { enabled.value = false }
        compose
            .onNodeWithTag("chat_composer_voice")
            .assertIsNotEnabled()

        compose.runOnIdle {
            enabled.value = true
            voiceEnabled.value = false
        }
        compose.onAllNodesWithTag("chat_composer_voice").assertCountEquals(0)
        compose.runOnIdle { assertEquals(1, starts) }
    }
}
