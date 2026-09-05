package io.github.trevarj.motd.ui.channelinfo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.ui.theme.MotdTheme
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
