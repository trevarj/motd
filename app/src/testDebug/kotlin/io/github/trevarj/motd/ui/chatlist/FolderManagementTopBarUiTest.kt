package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatFolderEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.FolderIconKind
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.repo.FolderIconRef
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderManagementTopBarUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w320dp-h1000dp", fontScale = 1.5f)
    fun autoGroupProposalKeepsNeutralPanelAndReadableFailureWithoutChangingActions() {
        val chats =
            (1L..2L).map { id ->
                ChatListRow(
                    bufferId = id,
                    networkId = 1,
                    networkName = "net",
                    displayName = "#dev.$id",
                    type = BufferType.CHANNEL,
                    pinned = false,
                    muted = false,
                    archived = false,
                    folderId = null,
                    lastMessageText = "hello",
                    lastMessageSender = "alice",
                    lastMessageTime = id,
                    unreadCount = 0,
                    mentionCount = 0,
                )
            }
        val proposal = AutoGroupProposal(1, "#dev.", "#dev.", "Development conversations", FolderIconRef(), chats)
        var fill = Color.Unspecified
        var headingInk = Color.Unspecified
        var attempts = 0
        var rejected = false
        compose.setContent {
            MotdTheme(dynamicColor = false, themePreset = ColorThemePreset.ONE_DARK, useThemeTextColors = true) {
                fill = MaterialTheme.colorScheme.surfaceContainerLow
                headingInk = MaterialTheme.colorScheme.primary
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AutoGroupProposalCard(
                        proposal = proposal,
                        folders = emptyList(),
                        onApprove = { actual, selected, destination, name, _, done ->
                            assertEquals(proposal, actual)
                            assertEquals(setOf(1L, 2L), selected.toSet())
                            assertEquals(null, destination)
                            assertEquals(proposal.suggestedName, name)
                            attempts++
                            done(attempts > 1)
                        },
                        onReject = { rejected = it == proposal },
                    )
                }
            }
        }
        val card = compose.onNodeWithTag("auto_group_1_#dev.")
        val pixels = card.captureToImage().toPixelMap()
        assertEquals(fill, pixels[pixels.width / 2, 2])
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(proposal.suggestedName) and isHeading(), useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(
            headingInk,
            layouts
                .single()
                .layoutInput.style.color,
        )
        compose.onNodeWithTag("auto_group_approve").performScrollTo().performClick()
        val error =
            org.robolectric.RuntimeEnvironment
                .getApplication()
                .getString(io.github.trevarj.motd.R.string.folders_operation_failed)
        val errorLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(error).performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(errorLayouts) }
        assertEquals(
            FontStyle.Normal,
            errorLayouts
                .single()
                .layoutInput.style.fontStyle ?: FontStyle.Normal,
        )
        compose.onNodeWithTag("auto_group_approve").performScrollTo().performClick()
        compose.onNodeWithText(error).assertDoesNotExist()
        compose.onNodeWithTag("auto_group_reject").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(2, attempts)
            assertTrue(rejected)
        }
    }

    @Test
    fun autoGroupPrecedesCreateAndUsesItsCallback() {
        var autoGrouped = false
        var created = false
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                FolderManagementTopBar(
                    onBack = {},
                    onAutoGroup = { autoGrouped = true },
                    onCreate = { created = true },
                )
            }
        }

        val autoGroup = compose.onNodeWithTag("folders_auto_group")
        val create = compose.onNodeWithTag("folders_create")
        assertTrue(autoGroup.getUnclippedBoundsInRoot().left < create.getUnclippedBoundsInRoot().left)
        autoGroup.performClick()

        compose.runOnIdle {
            assertTrue(autoGrouped)
            assertTrue(!created)
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun existingFolderDraftSurvivesChatEmissionAndRestoration() {
        val folder = ChatFolderEntity(id = 1, displayName = "Development", normalizedName = "development")
        val firstChat =
            ChatListRow(
                bufferId = 1,
                networkId = 1,
                networkName = "net",
                displayName = "#dev.one",
                type = BufferType.CHANNEL,
                pinned = false,
                muted = false,
                archived = false,
                folderId = folder.id,
                lastMessageText = "hello",
                lastMessageSender = "alice",
                lastMessageTime = 1,
                unreadCount = 0,
                mentionCount = 0,
            )
        val secondChat = firstChat.copy(bufferId = 2, displayName = "#dev.two", folderId = null)
        val unrelatedChat = firstChat.copy(bufferId = 3, displayName = "#unrelated", folderId = null)
        val state = MutableStateFlow<FolderManagementState?>(null)
        val restoration = StateRestorationTester(compose)
        var saved: Triple<String, FolderIconRef, Set<Long>>? = null
        lateinit var focusManager: FocusManager
        restoration.setContent {
            val editorState by state.collectAsState()
            focusManager = LocalFocusManager.current
            MotdTheme(dynamicColor = false) {
                FolderEditorContent(
                    folderId = folder.id,
                    state = editorState,
                    onBack = {},
                    onSave = { name, icon, members, done ->
                        saved = Triple(name, icon, members.toSet())
                        done(false)
                    },
                    onDelete = {},
                )
            }
        }

        val name = compose.onNodeWithTag("folder_name")
        val save = compose.onNodeWithTag("folder_save")
        name.assertDoesNotExist()
        save.assertDoesNotExist()
        compose.runOnIdle { state.value = FolderManagementState(listOf(folder), listOf(firstChat, secondChat, unrelatedChat)) }
        name.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(folder.displayName)))

        val checkbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)

        fun chatRow(label: String) = compose.onNode(hasText(label) and checkbox)
        chatRow(firstChat.displayName)
            .assertHeightIsAtLeast(48.dp)
            .assertIsOn()
            .performClick()
            .assertIsOff()
        chatRow(secondChat.displayName).assertIsOff().performClick().assertIsOn()
        chatRow(unrelatedChat.displayName).assertIsOff()
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(checkbox), useUnmergedTree = true).assertCountEquals(0)
        name.performTextClearance()
        val search = compose.onNodeWithTag("folder_search")
        search.performTextReplacement("#dev.")
        compose.runOnIdle { focusManager.clearFocus() }

        val updatedChat = secondChat.copy(displayName = "#dev.renamed")
        compose.runOnIdle {
            state.value = FolderManagementState(listOf(folder), listOf(firstChat, updatedChat, unrelatedChat))
        }
        val expected = Triple("", FolderIconRef(), setOf(2L))

        fun assertDraft() {
            name.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            search.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("#dev.")))
            chatRow(firstChat.displayName).assertIsOff()
            chatRow(updatedChat.displayName).assertIsOn()
            compose.onNodeWithText(unrelatedChat.displayName).assertDoesNotExist()
            save.performClick()
            compose.runOnIdle { assertEquals(expected, saved) }
        }
        assertDraft()
        compose.runOnIdle { saved = null }
        restoration.emulateSavedInstanceStateRestore()
        assertDraft()

        val dialog = compose.onNode(isDialog())
        val delete = compose.onNodeWithTag("folder_delete")
        delete.performClick()
        compose
            .onNode(hasText("Delete this folder? Chats return to their ungrouped list.") and hasAnyAncestor(isDialog()))
            .assertExists()
        compose.runOnIdle { state.value = FolderManagementState(emptyList(), listOf(firstChat, updatedChat, unrelatedChat)) }
        dialog.assertDoesNotExist()
        compose.onNodeWithText("This folder no longer exists").assertExists()
        save.assertDoesNotExist()
        delete.assertDoesNotExist()
    }

    @Test
    fun folderDraftSaverRoundTripsBlankNameAndNonDefaultIcon() {
        val draft =
            FolderDraft(
                name = "",
                icon = FolderIconRef(FolderIconKind.MATERIAL, "chat"),
                members = setOf(2L, 7L),
                query = "#dev.",
            )
        val saved = with(FolderDraftSaver) { requireNotNull(SaverScope { it is String || it is LongArray }.save(draft)) }

        assertEquals(draft, FolderDraftSaver.restore(saved))
    }

    @Test
    fun newFolderIsEditableBeforeChatsLoad() {
        var saved: String? = null
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                FolderEditorContent(
                    folderId = 0,
                    state = null,
                    onBack = {},
                    onSave = { name, _, _, done ->
                        saved = name
                        done(true)
                    },
                    onDelete = {},
                )
            }
        }
        compose.onNodeWithTag("folder_name").performTextReplacement("New folder")
        compose.onNodeWithTag("folder_save").performClick()
        compose.runOnIdle { assertEquals("New folder", saved) }
    }
}
