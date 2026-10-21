package io.github.trevarj.motd.ui.channelinfo

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.ui.components.MESSAGE_TEXT_CONTRAST
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.ensureContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ChannelInfoTopicEditUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun topicProseUsesNeutralInkWhileLinksIrcFormattingAndRosterStayDistinct() {
        val preset = mutableStateOf(ColorThemePreset.AYU_LIGHT)
        val topic = "Reading https://example.test \u000304red\u000f \u000399,00paper\u000f"
        var fill = Color.Unspecified
        var linkInk = Color.Unspecified
        var rosterInk = Color.Unspecified
        compose.setContent {
            MotdTheme(dynamicColor = false, themePreset = preset.value) {
                val scheme = MaterialTheme.colorScheme
                fill = scheme.background
                linkInk = ensureContrast(scheme.primary, listOf(fill), MESSAGE_TEXT_CONTRAST)
                rosterInk = scheme.onSurfaceVariant
                ChannelInfoContent(
                    state =
                        ChannelInfoUiState(
                            buffer = BufferEntity(1, 1, "#room", "#room", BufferType.CHANNEL, topic = topic),
                            memberCount = 3,
                        ),
                    onBack = {},
                    onSetPinned = {},
                    onSetMuted = {},
                    onLeave = {},
                )
            }
        }
        for (theme in listOf(ColorThemePreset.AYU_LIGHT, ColorThemePreset.AYU_DARK)) {
            compose.runOnIdle { preset.value = theme }
            val seed = if (theme == ColorThemePreset.AYU_LIGHT) Color(0xFF171717) else Color(0xFFF5F5F5)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("channelinfo_topic", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val text = layout.layoutInput.text
            assertEquals("$theme prose", ensureContrast(seed, listOf(fill), MESSAGE_TEXT_CONTRAST), layout.layoutInput.style.color)
            val link = text.getLinkAnnotations(0, text.length).single()
            val linkStyle = text.spanStyles.last { text.indexOf("https://") in it.start until it.end }.item
            assertEquals("$theme link ink", linkInk, linkStyle.color)
            assertEquals("$theme link underline", TextDecoration.Underline, linkStyle.textDecoration)
            assertEquals("$theme link target", "https://example.test", (link.item as androidx.compose.ui.text.LinkAnnotation.Url).url)
            val red = text.spanStyles.last { text.indexOf("red") in it.start until it.end && it.item.color != Color.Unspecified }.item
            assertEquals("$theme explicit IRC red", ensureContrast(Color.Red, listOf(fill), MESSAGE_TEXT_CONTRAST), red.color)
            val paper = text.spanStyles.last { text.indexOf("paper") in it.start until it.end }.item
            assertEquals("$theme explicit IRC background", Color.White, paper.background)
            assertEquals("$theme inherited ink on IRC background", ensureContrast(ensureContrast(seed, listOf(fill), MESSAGE_TEXT_CONTRAST), listOf(Color.White), MESSAGE_TEXT_CONTRAST), paper.color.takeUnless { it == Color.Unspecified } ?: layout.layoutInput.style.color)
            val rosterLayouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("channelinfo_roster_state").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(rosterLayouts) }
            assertEquals(
                "$theme roster metadata",
                rosterInk,
                rosterLayouts
                    .single()
                    .layoutInput.style.color,
            )
        }
    }

    @Test
    fun proposedTopicRendersFormattingWithoutLinksOrSubmittingBeforeSave() {
        val submitted = mutableListOf<String>()
        var began = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChannelInfoContent(
                    state = ChannelInfoUiState(buffer = BufferEntity(1, 1, "#room", "#room", BufferType.CHANNEL, topic = ""), memberCount = 3),
                    onBack = {},
                    onSetPinned = {},
                    onSetMuted = {},
                    onLeave = {},
                    onBeginTopicEdit = { began++ },
                    onSetTopic = { submitted += it },
                )
            }
        }
        compose.onNodeWithContentDescription("Edit topic").performClick()
        compose.runOnIdle {
            assertEquals(1, began)
            assertTrue(submitted.isEmpty())
        }
        val raw = "New \u0002garden\u000f https://topic.invalid\n" + List(8) { "Long topic line $it stays editable and readable." }.joinToString("\n")
        compose.onNodeWithTag("channelinfo_topic_edit_text").performTextReplacement(raw)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("channelinfo_topic_preview").performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val rendered = layouts.single().layoutInput.text
        assertEquals(raw.replace("\u0002", "").replace("\u000f", ""), rendered.text)
        val garden = rendered.text.indexOf("garden")
        assertTrue(rendered.spanStyles.any { garden in it.start until it.end && it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold })
        assertTrue(rendered.getLinkAnnotations(0, rendered.length).isEmpty())
        compose.runOnIdle { assertTrue(submitted.isEmpty()) }
        compose.onNodeWithTag("channelinfo_topic_edit_save").performClick()
        compose.runOnIdle { assertEquals(listOf(raw), submitted) }
    }

    @Test
    fun offlineSave_keepsRetryAvailableWithError() {
        assertTrue(topicEditShowsError(TopicMutationState.Failed))
        assertTrue(topicEditSaveEnabled(TopicMutationState.Failed))
    }

    @Test
    fun pendingSave_disablesDuplicateSubmission() {
        assertFalse(topicEditSaveEnabled(TopicMutationState.Submitting))
    }

    @Test
    fun acceptedSave_dismissesTheDialog() {
        assertTrue(topicEditAccepted(TopicMutationState.Accepted))
    }

    @Test
    fun confirmedOfflineLeave_keepsChannelInfoConfirmationOpenWithRetryFeedback() {
        var mutation by mutableStateOf<LeaveMutationState>(LeaveMutationState.Idle)
        var leaveCalls = 0
        compose.setContent {
            MotdTheme {
                ChannelInfoContent(
                    state =
                        ChannelInfoUiState(
                            buffer = BufferEntity(1, 1, "#internal-alias", "#room", BufferType.CHANNEL),
                        ),
                    onBack = {},
                    onSetPinned = {},
                    onSetMuted = {},
                    onLeave = {
                        leaveCalls += 1
                        mutation = LeaveMutationState.Failed
                    },
                    leaveMutation = mutation,
                )
            }
        }

        compose.onNodeWithContentDescription("Leave").performClick()
        compose.onNodeWithTag("channelinfo_leave_confirm").performClick()

        compose.onAllNodesWithText("Leave channel?").assertCountEquals(1)
        compose.onAllNodesWithTag("channelinfo_leave_error").assertCountEquals(1)
        compose.runOnIdle { assertEquals(1, leaveCalls) }
    }
}
