package io.github.trevarj.motd

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioCacheStatus
import io.github.trevarj.motd.audio.AudioPlaybackOrigin
import io.github.trevarj.motd.audio.AudioPlaybackRequest
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.audio.AudioWaveform
import io.github.trevarj.motd.ui.chat.VoiceTranscriptFailureKind
import io.github.trevarj.motd.ui.chat.VoiceTranscriptState
import io.github.trevarj.motd.ui.components.AudioAttachmentPlayers
import io.github.trevarj.motd.ui.components.AudioMiniPlayer
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class AudioPlayerUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test fun uncached_audio_uses_download_action() {
        val attachment = audio()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = AudioPlaybackState(),
                    cacheStatuses = mapOf(attachment.playbackId to AudioCacheStatus.NOT_CACHED),
                    networkId = null,
                    isSelf = false,
                    onToggle = { _, _ -> },
                    onSeek = { _, _ -> },
                )
            }
        }

        compose.onNodeWithContentDescription("Download audio").assertIsDisplayed()
    }

    @Test fun cached_audio_uses_play_action() {
        val attachment = audio()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = AudioPlaybackState(),
                    cacheStatuses = mapOf(attachment.playbackId to AudioCacheStatus.CACHED),
                    networkId = null,
                    isSelf = false,
                    onToggle = { _, _ -> },
                    onSeek = { _, _ -> },
                )
            }
        }

        compose.onNodeWithContentDescription("Play audio").assertIsDisplayed()
    }

    @Test fun encrypted_audio_uses_lock_icon_and_keeps_full_timestamp() {
        val attachment = audio().copy(encrypted = true)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = AudioPlaybackState(),
                    cacheStatuses = mapOf(attachment.playbackId to AudioCacheStatus.CACHED),
                    networkId = null,
                    isSelf = false,
                    formattedTime = "12:34 PM",
                    onToggle = { _, _ -> },
                    onSeek = { _, _ -> },
                )
            }
        }

        compose.onNodeWithContentDescription("Encrypted audio").assertIsDisplayed()
        compose.onNodeWithText("12:34 PM").assertIsDisplayed()
    }

    @Test fun voice_audio_player_remains_compact() {
        val attachment =
            audio().copy(
                voice = true,
                durationMs = 60_000,
                waveform = AudioWaveform(listOf(0, 31, 0)),
            )
        val state =
            AudioPlaybackState(
                activeId = attachment.playbackId,
                attachment = attachment,
                durationMs = 60_000,
                positionMs = 30_000,
                playing = true,
                waveform = attachment.waveform,
            )
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = state,
                    cacheStatuses = mapOf(attachment.playbackId to AudioCacheStatus.CACHED),
                    networkId = null,
                    isSelf = false,
                    onToggle = { _, _ -> },
                    onSeek = { _, _ -> },
                )
            }
        }

        val bounds = compose.onNodeWithTag("audio_player").getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("voice player height was $height", height <= 84.dp)
        compose.onNodeWithTag("audio_player_radial_wave", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun mini_player_scrubber_uses_the_full_player_height() {
        val attachment = audio().copy(voice = true, waveform = AudioWaveform(listOf(0, 31, 0)))
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioMiniPlayer(
                    state =
                        AudioPlaybackState(
                            activeId = attachment.playbackId,
                            attachment = attachment,
                            durationMs = 60_000,
                            positionMs = 30_000,
                            playing = true,
                            waveform = attachment.waveform,
                        ),
                    onToggle = {},
                    onCancelLoading = {},
                    onRetry = {},
                    onDismiss = {},
                    onSeek = {},
                    onOpenOrigin = {},
                )
            }
        }

        val scrubberBounds = compose.onNodeWithTag("audio_mini_scrubber").getUnclippedBoundsInRoot()
        val scrubberHeight = scrubberBounds.bottom - scrubberBounds.top
        assertTrue("scrubber touch height was $scrubberHeight", scrubberHeight >= 32.dp)
        val bannerBounds = compose.onNodeWithTag("audio_mini_player").getUnclippedBoundsInRoot()
        val bannerHeight = bannerBounds.bottom - bannerBounds.top
        assertTrue("mini player height was $bannerHeight", bannerHeight >= 48.dp)
        compose.onNodeWithTag("audio_mini_radial_wave", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun voice_speed_is_only_available_in_the_mini_player() {
        val attachment = audio().copy(voice = true, waveform = AudioWaveform(listOf(0, 31, 0)))
        var requestedSpeed: Float? = null
        val state =
            AudioPlaybackState(
                activeId = attachment.playbackId,
                attachment = attachment,
                durationMs = 60_000,
                waveform = attachment.waveform,
            )
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = state,
                    networkId = null,
                    isSelf = false,
                    onToggle = { _, _ -> },
                    onSeek = { _, _ -> },
                )
                AudioMiniPlayer(
                    state = state,
                    onToggle = {},
                    onCancelLoading = {},
                    onRetry = {},
                    onDismiss = {},
                    onSeek = {},
                    onOpenOrigin = {},
                    onSpeed = { requestedSpeed = it },
                )
            }
        }

        compose.onAllNodesWithTag("audio_mini_radial_wave", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("audio_player_radial_wave", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("audio_speed").assertCountEquals(0)
        compose.onNodeWithTag("audio_mini_speed").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(requestedSpeed == 1.5f) }
    }

    @Test
    fun transcription_is_hidden_when_lab_is_off() {
        val attachment = voiceAudio()
        showDetails(attachment, transcriptionEnabled = false, transcriptionReady = true)

        compose.onNodeWithTag("audio_transcription_section").assertDoesNotExist()
    }

    @Test
    fun transcription_is_hidden_for_non_voice_audio() {
        showDetails(audio(), transcriptionEnabled = true, transcriptionReady = true)

        compose.onNodeWithTag("audio_transcription_section").assertDoesNotExist()
    }

    @Test
    fun enabled_transcription_without_assignment_shows_setup_guidance() {
        showDetails(voiceAudio(), transcriptionEnabled = true, transcriptionReady = false)

        compose.onNodeWithTag("audio_transcription_setup").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun preparing_waiting_and_transcribing_show_cancel() {
        var transcript by mutableStateOf<VoiceTranscriptState>(VoiceTranscriptState.Preparing(25))
        var cancelled = 0
        showDetails(
            attachment = voiceAudio(),
            transcript = { transcript },
            onCancel = { cancelled++ },
        )

        compose.onNodeWithTag("audio_transcription_cancel").performScrollTo().performClick()
        compose.runOnIdle { transcript = VoiceTranscriptState.Waiting }
        compose.onNodeWithTag("audio_transcription_cancel").performScrollTo().performClick()
        compose.runOnIdle { transcript = VoiceTranscriptState.Transcribing(60) }
        compose.onNodeWithTag("audio_transcription_cancel").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(3, cancelled) }
    }

    @Test
    fun ready_transcript_is_selectable_and_self_rerun_keeps_request_identity() {
        val attachment = voiceAudio("https://files.example/self.opus#motd-key=stored")
        val origin = audioOrigin(isSelf = true)
        var transcript by mutableStateOf<VoiceTranscriptState>(
            VoiceTranscriptState.Ready("hello from the model", cached = true),
        )
        var request: AudioPlaybackRequest? = null
        var force = false
        showDetails(
            attachment = attachment,
            origin = origin,
            transcript = { transcript },
            onTranscribe = { value, rerun ->
                request = value
                force = rerun
            },
        )

        compose.onNodeWithTag("audio_transcription_text").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Loaded from the local transcript cache.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("audio_transcription_again").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(AudioPlaybackRequest(attachment, 7, origin), request)
            assertTrue(force)
            transcript = VoiceTranscriptState.Ready("", cached = false)
        }
        compose.onNodeWithText("No speech detected.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun failed_transcription_has_concrete_retry() {
        val attachment = voiceAudio()
        var request: AudioPlaybackRequest? = null
        var force = true
        showDetails(
            attachment = attachment,
            transcript = {
                VoiceTranscriptState.Failed(VoiceTranscriptFailureKind.AUTHENTICATION_FAILED)
            },
            onTranscribe = { value, rerun ->
                request = value
                force = rerun
            },
        )

        compose
            .onNodeWithText("The encrypted audio could not be authenticated.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("audio_transcription_retry").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(attachment, request?.attachment)
            assertFalse(force)
        }
    }

    @Test
    fun cleartext_confirmation_precedes_received_encrypted_request() {
        val attachment =
            voiceAudio("http://files.example/received.opus#motd-key=secret")
                .copy(encrypted = true)
        val origin = audioOrigin(isSelf = false)
        var request: AudioPlaybackRequest? = null
        showDetails(
            attachment = attachment,
            origin = origin,
            onTranscribe = { value, _ -> request = value },
        )

        compose.onNodeWithTag("audio_transcription_start").performClick()
        compose.onNodeWithTag("audio_transcription_http_confirm").assertIsDisplayed()
        compose.runOnIdle { assertNull(request) }
        compose.onNodeWithText("Transcribe").performClick()
        compose.runOnIdle {
            assertEquals(AudioPlaybackRequest(attachment, 7, origin), request)
        }
    }

    private fun showDetails(
        attachment: AudioAttachment,
        origin: AudioPlaybackOrigin = audioOrigin(isSelf = false),
        transcriptionEnabled: Boolean = true,
        transcriptionReady: Boolean = true,
        transcript: () -> VoiceTranscriptState? = { null },
        onTranscribe: (AudioPlaybackRequest, Boolean) -> Unit = { _, _ -> },
        onCancel: () -> Unit = {},
    ) {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = AudioPlaybackState(),
                    networkId = 7,
                    isSelf = origin.isSelf,
                    onToggle = { _, _ -> },
                    onSeek = { _, _ -> },
                    origin = origin,
                    transcripts =
                        transcript()?.let { mapOf(attachment.playbackId to it) }.orEmpty(),
                    transcriptionEnabled = transcriptionEnabled,
                    transcriptionReady = transcriptionReady,
                    onTranscribe = onTranscribe,
                    onCancelTranscription = { onCancel() },
                )
            }
        }
        compose.onNodeWithTag("audio_player_details").performClick()
    }

    private fun voiceAudio(url: String = "https://files.example/voice.opus") =
        AudioAttachment(
            url = url,
            title = "voice.opus",
            mimeType = "audio/ogg",
            voice = true,
        )

    private fun audioOrigin(isSelf: Boolean) =
        AudioPlaybackOrigin(
            bufferId = 9,
            networkId = 7,
            conversation = "#voice",
            sender = if (isSelf) "me" else "alice",
            isSelf = isSelf,
            directMessage = false,
            eventId = if (isSelf) 2 else 1,
            msgid = if (isSelf) "self" else "received",
            serverTime = 10,
        )

    private fun audio() =
        AudioAttachment(
            url = "https://files.example/song.ogg",
            title = "song.ogg",
            mimeType = "audio/ogg",
        )
}
