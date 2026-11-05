package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.repo.FolderIconRef
import io.github.trevarj.motd.ui.theme.MotdTheme
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
}
