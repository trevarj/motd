package io.github.trevarj.motd.agentwire

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.audio.AudioWaveform
import io.github.trevarj.motd.audio.VoiceSendProgress
import io.github.trevarj.motd.ui.chat.StagedVoiceMessage
import io.github.trevarj.motd.ui.chat.VOICE_PERMISSION_DENIED_ERROR
import io.github.trevarj.motd.ui.chat.VoiceComposerPanel
import io.github.trevarj.motd.ui.chat.VoiceMessageUiState
import io.github.trevarj.motd.ui.chat.VoiceRecordingUi
import io.github.trevarj.motd.ui.share.PendingShare
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class AgentwireVoiceUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun microphoneRequiresConnectedActiveBindingAndBlankUnstagedComposer() {
        val fixture = Fixture()
        show(fixture)
        compose
            .onNodeWithTag("chat_composer_voice")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()

        compose.runOnIdle { fixture.value = TextFieldValue("typed prompt") }
        compose.onNodeWithTag("chat_composer_voice").assertDoesNotExist()
        compose.runOnIdle {
            fixture.value = TextFieldValue("  ")
            fixture.agent = fixture.agent.copy(activeSid = null)
        }
        compose.onNodeWithTag("chat_composer_voice").assertDoesNotExist()
        compose.runOnIdle { fixture.agent = fixture.agent.copy(activeSid = "session", connected = false) }
        compose.onNodeWithTag("chat_composer_voice").assertDoesNotExist()
        compose.runOnIdle { fixture.agent = fixture.agent.copy(connected = true, gate = AgentwireGate.BLOCKED) }
        compose.onNodeWithTag("chat_composer_voice").assertDoesNotExist()
        compose.runOnIdle {
            fixture.agent = fixture.agent.copy(gate = AgentwireGate.ACTIVE)
            fixture.voice = fixture.voice.copy(staged = note())
        }
        compose.onNodeWithTag("voice_preview_panel").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_voice").assertDoesNotExist()
        compose.onNodeWithTag("voice_delete").performClick()
        compose.onNodeWithTag("chat_composer_voice").assertIsDisplayed()
    }

    @Test
    fun accessibleStartLocksAndExposesStopReviewCancelAndPermissionErrors() {
        val fixture = Fixture()
        show(fixture)
        compose.onNodeWithTag("chat_composer_voice").performSemanticsAction(SemanticsActions.OnClick)
        compose
            .onNodeWithTag("voice_recording_panel")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithContentDescription("Stop and review recording").performClick()
        compose.onNodeWithTag("voice_preview_panel").assertIsDisplayed()
        compose.onNodeWithTag("voice_delete").performClick()
        compose.onNodeWithTag("chat_composer_voice").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithContentDescription("Cancel recording").performClick()
        compose.onNodeWithTag("voice_recording_panel").assertDoesNotExist()
        compose.onNodeWithTag("voice_preview_panel").assertDoesNotExist()

        compose.runOnIdle { fixture.voice = fixture.voice.copy(error = VOICE_PERMISSION_DENIED_ERROR) }
        compose.onNodeWithText(VOICE_PERMISSION_DENIED_ERROR).assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText(VOICE_PERMISSION_DENIED_ERROR).assertDoesNotExist()
    }

    @Test
    fun holdingCanReleaseToReviewSwipeUpToLockAndSlideLeftToCancel() {
        val fixture = Fixture()
        show(fixture)
        holdMicrophone()
        compose.onNodeWithTag("voice_lock_hint").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_voice").performTouchInput { up() }
        compose.onNodeWithTag("voice_preview_panel").assertIsDisplayed()
        compose.onNodeWithTag("voice_delete").performClick()

        holdMicrophone()
        val slidePx = with(compose.density) { 96.dp.toPx() }
        compose.onNodeWithTag("chat_composer_voice").performTouchInput {
            moveTo(center - Offset(0f, slidePx))
            up()
        }
        compose.onNodeWithTag("voice_stop_locked").assertIsDisplayed().performClick()
        compose.onNodeWithTag("voice_preview_panel").assertIsDisplayed()
        compose.onNodeWithTag("voice_delete").performClick()

        holdMicrophone()
        compose.onNodeWithTag("chat_composer_voice").performTouchInput {
            moveTo(center - Offset(slidePx, 0f))
            up()
        }
        compose.onNodeWithTag("voice_recording_panel").assertDoesNotExist()
        compose.onNodeWithTag("voice_preview_panel").assertDoesNotExist()
        compose.runOnIdle { assertNull(fixture.voice.staged) }
    }

    @Test
    fun stagedAgentVoiceKeepsPreviewDestinationAndUploadControlsWithoutEncryptionSwitch() {
        val fixture = Fixture().apply { voice = VoiceMessageUiState(staged = note()) }
        show(fixture)
        compose.onNodeWithTag("voice_encryption_toggle").assertDoesNotExist()
        compose
            .onNodeWithText(
                "Agent voice notes are sent unencrypted so Agentwire can transcribe them on the workstation.",
            ).assertIsDisplayed()
        compose.onNodeWithTag("voice_preview_play").performClick()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onNodeWithTag("voice_preview_scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.onNodeWithTag("voice_preview_scrubber").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.5f, 0f..1f)),
        )
        compose.onNodeWithTag("voice_preview_play").performClick()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithTag("voice_destination").performClick()
        compose.onNodeWithText("Voice destination").assertIsDisplayed()
        compose.onNodeWithText("Use the file host advertised by this IRC network").performClick()
        compose.onNodeWithText("Voice destination").assertDoesNotExist()
        compose.onNodeWithTag("voice_send").performClick()
        compose.onNodeWithTag("voice_send").assertIsNotEnabled()
        compose.onNodeWithTag("voice_delete").assertIsNotEnabled()
        compose.onNodeWithTag("voice_destination").assertIsNotEnabled()
        compose.onNodeWithTag("voice_preview_play").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, fixture.sends)
            assertEquals(VoiceSendProgress.Uploading(5, 10), fixture.voice.progress)
            fixture.voice = VoiceMessageUiState()
        }
        compose.onNodeWithTag("voice_preview_panel").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_voice").assertIsDisplayed()
    }

    @Test
    fun unavailableDestinationDisablesStagedSendWithoutLosingReview() {
        val fixture = Fixture().apply { voice = VoiceMessageUiState(staged = note()) }
        show(fixture)
        for (unavailable in listOf(
            fixture.agent.copy(activeSid = null),
            fixture.agent.copy(connected = false),
            fixture.agent.copy(gate = AgentwireGate.BLOCKED),
        )) {
            compose.runOnIdle { fixture.agent = unavailable }
            compose.onNodeWithTag("voice_send").assertIsNotEnabled().performClick()
            compose.onNodeWithTag("voice_preview_panel").assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, fixture.sends) }
        }
        compose.runOnIdle {
            fixture.agent = fixture.agent.copy(gate = AgentwireGate.ACTIVE, connected = true, activeSid = "session")
        }
        compose.onNodeWithTag("voice_send").assertIsEnabled()
    }

    @Test
    fun losingRecordingEligibilityStagesTheNoteEvenWhenTheComposerDisappears() {
        val fixture = Fixture()
        var reviewing by mutableStateOf(false)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                agentwireRecordingGate(
                    state = fixture.agent,
                    composerText = fixture.value.text,
                    voiceState = fixture.voice,
                    contextReviewing = reviewing,
                    onStopRecording = { fixture.voice = fixture.voice.copy(recording = null, staged = note()) },
                )
                if (fixture.agent.gate == AgentwireGate.ACTIVE && !reviewing) fixture.Content()
            }
        }
        val transitions: List<() -> Unit> =
            listOf(
                { fixture.agent = fixture.agent.copy(gate = AgentwireGate.BLOCKED) },
                { fixture.agent = fixture.agent.copy(gate = AgentwireGate.INVALID_TOPIC) },
                { fixture.agent = fixture.agent.copy(connected = false) },
                { fixture.agent = fixture.agent.copy(activeSid = null) },
                { fixture.value = TextFieldValue("typed prompt") },
                { reviewing = true },
            )
        for (transition in transitions) {
            compose.runOnIdle {
                fixture.agent = AgentwireUiState(gate = AgentwireGate.ACTIVE, connected = true, activeSid = "session")
                fixture.value = TextFieldValue()
                fixture.voice = VoiceMessageUiState(recording = VoiceRecordingUi(1_000L, locked = true))
                reviewing = false
            }
            compose.onNodeWithTag("voice_stop_locked").assertIsDisplayed()
            compose.runOnIdle { transition() }
            compose.waitForIdle()
            compose.runOnIdle {
                assertNull(fixture.voice.recording)
                fixture.agent = AgentwireUiState(gate = AgentwireGate.ACTIVE, connected = true, activeSid = "session")
                fixture.value = TextFieldValue()
                reviewing = false
            }
            compose.onNodeWithTag("voice_preview_panel").assertIsDisplayed()
            compose.onNodeWithTag("voice_send").assertIsEnabled()
        }
    }

    @Test
    fun contextSharingNeverOffersMicrophoneEvenWithAnEmptyPrompt() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AgentwireContextComposer(
                    review = AgentwireContextReview(PendingShare.AgentContext(9, "#source", "", "Visible context")),
                    onEdit = { true },
                    onSend = {},
                    showComposerEmoji = false,
                    showComposerFormattingTools = false,
                )
            }
        }
        compose.onNodeWithTag("agentwire_context_composer").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_voice").assertDoesNotExist()
        compose.onNodeWithTag("voice_preview_panel").assertDoesNotExist()
    }

    @Test
    fun contextReviewCannotHideALockedRecorderAndPreservesTheStagedNote() {
        val fixture = Fixture()
        val review = AgentwireContextReview(PendingShare.AgentContext(9, "#source", "Context", "Visible context"))
        var reviewing by mutableStateOf(false)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Column {
                    AgentwireContextCard(
                        review = review,
                        state = fixture.agent,
                        sessionName = "session",
                        canReview = true,
                        voiceRecording = fixture.voice.recording != null,
                        onReview = { reviewing = true },
                        onKeep = {},
                        onDiscard = {},
                        onSessions = {},
                    )
                    if (reviewing) {
                        AgentwireContextComposer(
                            review = review,
                            onEdit = { true },
                            onSend = {},
                            showComposerEmoji = false,
                            showComposerFormattingTools = false,
                        )
                    } else {
                        fixture.Content()
                    }
                }
            }
        }

        compose.onNodeWithTag("chat_composer_voice").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("agentwire_context_review_start").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("voice_stop_locked").assertIsDisplayed().performClick()
        compose.onNodeWithTag("voice_preview_panel").assertIsDisplayed()
        compose.onNodeWithTag("agentwire_context_review_start").assertIsEnabled().performClick()
        compose.onNodeWithTag("agentwire_context_composer").assertIsDisplayed()
        compose.runOnIdle { reviewing = false }
        compose.onNodeWithTag("voice_preview_panel").assertIsDisplayed()
        compose.onNodeWithTag("voice_send").assertIsEnabled()
    }

    @Test
    fun ordinaryVoiceReviewStillAllowsChangingEncryption() {
        var state by mutableStateOf(VoiceMessageUiState(staged = note().copy(encrypted = true)))
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                VoiceComposerPanel(
                    state = state,
                    playbackState = AudioPlaybackState(),
                    onDelete = {},
                    onCancelRecording = {},
                    onStopRecording = {},
                    onSend = {},
                    onPreview = {},
                    onPreviewSeek = { _, _ -> },
                    onToggleEncryption = { state = state.copy(staged = state.staged?.copy(encrypted = false)) },
                    onDestinationSelected = {},
                    onErrorDismissed = {},
                )
            }
        }
        compose
            .onNodeWithTag("voice_encryption_toggle")
            .assertIsOn()
            .performClick()
            .assertIsOff()
        compose
            .onNodeWithText(
                "Agent voice notes are sent unencrypted so Agentwire can transcribe them on the workstation.",
            ).assertDoesNotExist()
    }

    private fun show(fixture: Fixture) {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    fixture.Content()
                }
            }
        }
    }

    private fun holdMicrophone() {
        compose.onNodeWithTag("chat_composer_voice").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("voice_recording_panel").assertIsDisplayed()
    }

    private class Fixture {
        var agent by mutableStateOf(AgentwireUiState(gate = AgentwireGate.ACTIVE, connected = true, activeSid = "session"))
        var value by mutableStateOf(TextFieldValue())
        var voice by mutableStateOf(VoiceMessageUiState())
        var playback by mutableStateOf(AudioPlaybackState())
        var sends = 0

        @Composable
        fun Content() {
            AgentwireComposer(
                value = value,
                state = agent,
                showComposerEmoji = false,
                showComposerFormattingTools = false,
                onValueChange = { value = it },
                onSend = {},
                onCancel = {},
                voiceState = voice,
                playbackState = playback,
                onVoiceHoldStart = { start(locked = false) },
                onVoiceAccessibilityStart = { start(locked = true) },
                onVoiceHoldStop = { voice = voice.copy(recording = null, staged = note()) },
                onVoiceHoldCancel = { voice = voice.copy(recording = null) },
                onVoiceLock = { voice = voice.copy(recording = voice.recording?.copy(locked = true)) },
                onVoiceDelete = { voice = voice.copy(staged = null) },
                onVoiceSend = {
                    sends++
                    voice = voice.copy(progress = VoiceSendProgress.Uploading(5, 10))
                },
                onVoicePreview = { attachment ->
                    playback = playback.copy(activeId = attachment.playbackId, playing = !playback.playing)
                },
                onVoicePreviewSeek = { _, position -> playback = playback.copy(positionMs = position) },
                onVoiceDestinationSelected = { voice = voice.copy(staged = voice.staged?.copy(destination = it)) },
                onVoiceErrorDismissed = { voice = voice.copy(error = null) },
            )
        }

        private fun start(locked: Boolean) {
            voice = voice.copy(recording = VoiceRecordingUi(elapsedMs = 1_000L, locked = locked))
        }
    }

    companion object {
        private fun note() =
            StagedVoiceMessage(
                file = File("voice-preview.ogg"),
                durationMs = 10_000L,
                mimeType = "audio/ogg",
                extension = "ogg",
                sizeBytes = 10L,
                encrypted = false,
                destination = null,
                waveform = AudioWaveform.EMPTY,
            )
    }
}
