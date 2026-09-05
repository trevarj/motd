package io.github.trevarj.motd

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.ui.chat.VoiceComposerPanel
import io.github.trevarj.motd.ui.chat.VoiceMessageUiState
import io.github.trevarj.motd.ui.chat.VoiceRecordingUi
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class VoiceRecordingPanelUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test fun locking_keeps_the_recording_panel_height_stable() {
        val locked = mutableStateOf(false)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                VoiceComposerPanel(
                    state =
                        VoiceMessageUiState(
                            recording = VoiceRecordingUi(elapsedMs = 1_000, locked = locked.value),
                        ),
                    playbackState = AudioPlaybackState(),
                    onDelete = {},
                    onCancelRecording = {},
                    onStopRecording = {},
                    onSend = {},
                    onPreview = { _ -> },
                    onPreviewSeek = { _, _ -> },
                    onToggleEncryption = {},
                    onDestinationSelected = { _ -> },
                    onErrorDismissed = {},
                )
            }
        }

        val unlockedHeight = panelHeight()
        compose.runOnIdle { locked.value = true }

        assertEquals(unlockedHeight, panelHeight())
    }

    @Test fun lockedRecording_announcesAndExposesCancelAndStopActions() {
        var cancelled = 0
        var stopped = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                VoiceComposerPanel(
                    state = VoiceMessageUiState(recording = VoiceRecordingUi(elapsedMs = 1_000, locked = true)),
                    playbackState = AudioPlaybackState(),
                    onDelete = {},
                    onCancelRecording = { cancelled++ },
                    onStopRecording = { stopped++ },
                    onSend = {},
                    onPreview = { _ -> },
                    onPreviewSeek = { _, _ -> },
                    onToggleEncryption = {},
                    onDestinationSelected = { _ -> },
                    onErrorDismissed = {},
                )
            }
        }

        compose
            .onNodeWithTag("voice_recording_panel")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Recording locked. Cancel recording or stop and review."),
                ),
            )
        compose.onNodeWithTag("voice_cancel_locked").assertHasClickAction().performClick()
        compose.onNodeWithTag("voice_stop_locked").assertHasClickAction().performClick()
        compose.runOnIdle {
            assertEquals(1, cancelled)
            assertEquals(1, stopped)
        }
    }

    private fun panelHeight() =
        compose
            .onNodeWithTag("voice_recording_panel")
            .getUnclippedBoundsInRoot()
            .let { it.bottom - it.top }
}
