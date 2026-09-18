package io.github.trevarj.motd.ui.chat

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.ai.AiExecutionUnavailableException
import io.github.trevarj.motd.ai.AiFeature
import io.github.trevarj.motd.ai.AiFeatureAssignment
import io.github.trevarj.motd.ai.AiLabsState
import io.github.trevarj.motd.ai.AiModelCapability
import io.github.trevarj.motd.ai.AiModelFormat
import io.github.trevarj.motd.ai.AiModelMetadata
import io.github.trevarj.motd.ai.AiModelRecord
import io.github.trevarj.motd.ai.AiRuntimeException
import io.github.trevarj.motd.ai.AiRuntimeFailure
import io.github.trevarj.motd.ai.AiTranscriptionRequest
import io.github.trevarj.motd.ai.AiTranscriptionSettingsRecord
import io.github.trevarj.motd.ai.MAX_TRANSCRIPT_BYTES
import io.github.trevarj.motd.ai.TranscriptionSettings
import io.github.trevarj.motd.audio.ActiveVoiceRecording
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioCacheStatus
import io.github.trevarj.motd.audio.AudioInputException
import io.github.trevarj.motd.audio.AudioInputFailureKind
import io.github.trevarj.motd.audio.AudioPlaybackController
import io.github.trevarj.motd.audio.AudioPlaybackOrigin
import io.github.trevarj.motd.audio.AudioPlaybackRequest
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.audio.AudioWaveform
import io.github.trevarj.motd.audio.CompletedVoiceRecording
import io.github.trevarj.motd.audio.LocalAudioLease
import io.github.trevarj.motd.audio.LocalPcmAudioLease
import io.github.trevarj.motd.audio.PcmAudioException
import io.github.trevarj.motd.audio.PcmAudioFailureKind
import io.github.trevarj.motd.audio.VoiceConfig
import io.github.trevarj.motd.audio.VoiceMessageSender
import io.github.trevarj.motd.audio.VoicePrefs
import io.github.trevarj.motd.audio.VoiceRecorder
import io.github.trevarj.motd.audio.VoiceRecordingProfile
import io.github.trevarj.motd.audio.VoiceSendProgress
import io.github.trevarj.motd.audio.VoiceSendRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VoiceMessageViewModelTest {
    @Test
    fun `Agentwire sends a fifteen minute wire duration unencrypted without changing ordinary chat defaults`() =
        voiceTest {
            val sent = mutableListOf<VoiceSendRequest>()
            val sender =
                object : VoiceMessageSender {
                    override fun send(request: VoiceSendRequest): Flow<VoiceSendProgress> =
                        flow {
                            sent += request
                            emit(VoiceSendProgress.Complete("https://files.test/voice.ogg"))
                        }
                }
            val vm =
                fixture(
                    Harness(),
                    recorder = RecordingRecorder().apply { completedDurationMs = 900_999L },
                    sender = sender,
                    config = VoiceConfig(encryptionDefault = true),
                )

            vm.startAgentwireRecording(locked = false)
            assertEquals(
                false,
                vm.state.value.recording
                    ?.locked,
            )
            vm.lockRecording()
            assertEquals(
                true,
                vm.state.value.recording
                    ?.locked,
            )
            vm.stopRecording()
            val agentNote = requireNotNull(vm.state.value.staged)
            assertFalse(agentNote.encrypted)
            vm.sendAgentwire(destinationAvailable = false)
            runCurrent()
            assertNotNull(vm.state.value.error)
            assertEquals(agentNote, vm.state.value.staged)
            assertTrue(agentNote.file.exists())
            assertTrue(sent.isEmpty())
            vm.sendAgentwire(destinationAvailable = true)
            runCurrent()

            assertFalse(sent.single().encrypt)
            assertNull(vm.state.value.staged)
            assertFalse(agentNote.file.exists())
            vm.startRecording(locked = true)
            vm.stopRecording()
            assertEquals(
                true,
                vm.state.value.staged
                    ?.encrypted,
            )
            vm.deleteStaged()
        }

    @Test
    fun `Agentwire preserves incompatible ordinary notes for recovery through the IRC transcript`() =
        voiceTest {
            for ((encrypted, durationMs) in listOf(true to 1_000L, false to 901_000L)) {
                val sent = mutableListOf<VoiceSendRequest>()
                val sender =
                    object : VoiceMessageSender {
                        override fun send(request: VoiceSendRequest): Flow<VoiceSendProgress> =
                            flow {
                                sent += request
                                emit(VoiceSendProgress.Complete("https://files.test/voice.ogg"))
                            }
                    }
                val vm =
                    fixture(
                        Harness(),
                        recorder = RecordingRecorder().apply { completedDurationMs = durationMs },
                        sender = sender,
                        config = VoiceConfig(encryptionDefault = encrypted),
                    )
                vm.startRecording(locked = true)
                vm.stopRecording()
                val original = requireNotNull(vm.state.value.staged)

                vm.sendAgentwire(destinationAvailable = true)
                runCurrent()
                assertNotNull(vm.state.value.error)
                assertNull(vm.state.value.progress)
                assertEquals(original, vm.state.value.staged)
                assertTrue(original.file.exists())
                assertTrue(sent.isEmpty())

                vm.clearError()
                vm.send()
                runCurrent()
                assertNull(vm.state.value.error)
                assertNull(vm.state.value.staged)
                assertEquals(1, sent.size)
                assertFalse(original.file.exists())
            }
        }

    @Test
    fun `Agentwire auto stops at fifteen minutes while ordinary chat retains thirty`() =
        voiceTest {
            val recorder = RecordingRecorder()
            val vm = fixture(Harness(), recorder = recorder, config = VoiceConfig(encryptionDefault = true))

            recorder.ageMs = 14 * 60_000L
            vm.startAgentwireRecording(locked = true)
            recordingTick()
            assertNotNull(vm.state.value.recording)
            vm.cancelRecording()

            recorder.ageMs = 15 * 60_000L
            vm.startAgentwireRecording(locked = true)
            recordingTick()
            assertNull(vm.state.value.recording)
            assertEquals(
                false,
                vm.state.value.staged
                    ?.encrypted,
            )
            vm.deleteStaged()

            recorder.ageMs = 29 * 60_000L
            vm.startRecording(locked = true)
            recordingTick()
            assertNotNull(vm.state.value.recording)
            vm.cancelRecording()

            recorder.ageMs = 30 * 60_000L
            vm.startRecording(locked = true)
            recordingTick()
            assertNull(vm.state.value.recording)
            assertEquals(
                true,
                vm.state.value.staged
                    ?.encrypted,
            )
            vm.deleteStaged()
        }

    @Test
    fun `failed Agentwire start leaves neither encryption nor duration overrides`() =
        voiceTest {
            val recorder = RecordingRecorder().apply { startFailure = IOException("Microphone unavailable") }
            val vm = fixture(Harness(), recorder = recorder, config = VoiceConfig(encryptionDefault = true))
            vm.startAgentwireRecording(locked = true)
            assertNull(vm.state.value.recording)
            assertNotNull(vm.state.value.error)

            recorder.startFailure = null
            recorder.ageMs = 15 * 60_000L
            vm.startRecording(locked = true)
            recordingTick()
            assertNotNull(vm.state.value.recording)
            vm.stopRecording()
            assertEquals(
                true,
                vm.state.value.staged
                    ?.encrypted,
            )
            vm.deleteStaged()
        }

    @Test
    fun `cancel and too short stop clear both Agentwire overrides`() =
        voiceTest {
            val recorder = RecordingRecorder()
            val vm = fixture(Harness(), recorder = recorder, config = VoiceConfig(encryptionDefault = true))
            for (cancel in listOf(true, false)) {
                recorder.ageMs = 0L
                vm.startAgentwireRecording(locked = true)
                if (cancel) {
                    vm.cancelRecording()
                } else {
                    recorder.tooShort = true
                    vm.stopRecording()
                    recorder.tooShort = false
                }
                assertNull(vm.state.value.recording)
                assertNull(vm.state.value.staged)

                recorder.ageMs = 15 * 60_000L
                vm.startRecording(locked = true)
                recordingTick()
                assertNotNull(vm.state.value.recording)
                vm.stopRecording()
                assertEquals(
                    true,
                    vm.state.value.staged
                        ?.encrypted,
                )
                vm.deleteStaged()
            }
        }

    @Test
    fun `duplicate starts cannot change an active recording policy or replace a staged note`() =
        voiceTest {
            val recorder = RecordingRecorder().apply { ageMs = 15 * 60_000L }
            val vm = fixture(Harness(), recorder = recorder, config = VoiceConfig(encryptionDefault = true))
            vm.startRecording(locked = false)
            vm.startAgentwireRecording(locked = true)
            recordingTick()
            assertEquals(
                false,
                vm.state.value.recording
                    ?.locked,
            )
            vm.stopRecording()
            val original = requireNotNull(vm.state.value.staged)
            assertTrue(original.encrypted)

            vm.startAgentwireRecording(locked = true)
            assertNull(vm.state.value.recording)
            assertEquals(original, vm.state.value.staged)
            assertTrue(original.file.exists())
            vm.deleteStaged()

            vm.startAgentwireRecording(locked = false)
            vm.startRecording(locked = true)
            recordingTick()
            assertNull(vm.state.value.recording)
            assertEquals(
                false,
                vm.state.value.staged
                    ?.encrypted,
            )
            vm.deleteStaged()
        }

    @Test
    fun `permission denial stays visible and granted Agentwire recording stages on destination pause`() =
        voiceTest {
            val vm = fixture(Harness(), recorder = RecordingRecorder(), config = VoiceConfig(encryptionDefault = true))
            val permission =
                VoiceRecordingPermissionGate(
                    permissionGranted = { false },
                    onStart = vm::startAgentwireRecording,
                    onDenied = vm::recordingPermissionDenied,
                )
            assertTrue(permission.start(locked = true))
            assertNull(vm.state.value.recording)
            permission.onPermissionResult(false)
            assertEquals(VOICE_PERMISSION_DENIED_ERROR, vm.state.value.error)
            assertNull(vm.state.value.recording)
            vm.clearError()

            assertTrue(permission.start(locked = true))
            permission.onPermissionResult(true)
            assertEquals(
                true,
                vm.state.value.recording
                    ?.locked,
            )
            val lifecycle =
                ChatForegroundLifecycleGate(
                    onResume = {},
                    onPause = vm::stopForBackground,
                )
            lifecycle.sync(true)
            lifecycle.onEvent(Lifecycle.Event.ON_PAUSE)
            val staged = requireNotNull(vm.state.value.staged)
            assertNull(vm.state.value.recording)
            assertFalse(staged.encrypted)
            lifecycle.onEvent(Lifecycle.Event.ON_STOP)
            lifecycle.dispose()
            assertEquals(staged, vm.state.value.staged)
            vm.deleteStaged()

            vm.startRecording(locked = true)
            vm.stopRecording()
            assertEquals(
                true,
                vm.state.value.staged
                    ?.encrypted,
            )
            vm.deleteStaged()
        }

    @Test
    fun `staged preview follows playback state and rejects a deleted recordings controls`() =
        voiceTest {
            val playback = FakePlaybackController()
            val vm = fixture(Harness(), recorder = RecordingRecorder(), playback = playback)
            vm.startAgentwireRecording(locked = true)
            vm.stopRecording()
            val oldPreview = preview(requireNotNull(vm.state.value.staged))
            vm.toggleStagedPreview(oldPreview)
            assertTrue(vm.playbackState.value.playing)
            vm.seekStagedPreview(oldPreview, 500L)
            assertEquals(500L, vm.playbackState.value.positionMs)
            vm.deleteStaged()
            assertNull(vm.playbackState.value.activeId)

            vm.startAgentwireRecording(locked = true)
            vm.stopRecording()
            val newPreview = preview(requireNotNull(vm.state.value.staged))
            vm.toggleStagedPreview(newPreview)
            vm.toggleStagedPreview(oldPreview)
            vm.seekStagedPreview(oldPreview, 900L)
            assertEquals(newPreview.playbackId, vm.playbackState.value.activeId)
            assertTrue(vm.playbackState.value.playing)
            assertEquals(0L, vm.playbackState.value.positionMs)
            vm.toggleStagedPreview(newPreview)
            assertFalse(vm.playbackState.value.playing)
            vm.deleteStaged()
        }

    @Test
    fun `linked OGG reaches transcription without voice metadata`() =
        voiceTest {
            val harness = Harness()
            val vm = fixture(harness)
            val attachment = AudioAttachment(url = "https://files.test/song.ogg")
            val request = request(attachment.url).copy(attachment = attachment)

            vm.transcribe(request)
            advanceUntilIdle()

            assertEquals(listOf(request), harness.materializedRequests)
            assertEquals(1, harness.nativeCalls)
            assertEquals(
                VoiceTranscriptState.Ready("transcribed", cached = false),
                vm.state.value.transcripts[attachment.playbackId],
            )
            assertTrue(harness.createdFiles.none(File::exists))
        }

    @Test
    fun `ordinary non OGG audio is rejected before materialization`() =
        voiceTest {
            val harness = Harness()
            val vm = fixture(harness)
            val attachment = AudioAttachment(url = "https://files.test/song.mp3")

            vm.transcribe(request(attachment.url).copy(attachment = attachment))
            advanceUntilIdle()

            assertEquals(
                VoiceTranscriptState.Failed(VoiceTranscriptFailureKind.INVALID_AUDIO),
                vm.state.value.transcripts[attachment.playbackId],
            )
            assertTrue(harness.materializedRequests.isEmpty())
            assertEquals(0, harness.nativeCalls)
        }

    @Test
    fun `cache hits preserve received and self stored URL request identity`() =
        voiceTest {
            val harness = Harness().apply { cachedText = "already local" }
            val vm = fixture(harness)
            val received = request("https://files.test/received.opus", eventId = 11, isSelf = false)
            val self = request("https://files.test/sent.opus#motd-key=stored", eventId = 12, isSelf = true)

            vm.transcribe(received)
            vm.transcribe(self)
            advanceUntilIdle()

            assertEquals(listOf(received, self), harness.materializedRequests)
            assertEquals(0, harness.nativeCalls)
            assertEquals(
                VoiceTranscriptState.Ready("already local", cached = true),
                vm.state.value.transcripts
                    .getValue(received.attachment.playbackId),
            )
            assertEquals(
                VoiceTranscriptState.Ready("already local", cached = true),
                vm.state.value.transcripts
                    .getValue(self.attachment.playbackId),
            )
            assertTrue(harness.createdFiles.none(File::exists))
        }

    @Test
    fun `preparing waiting and transcribing can each cancel and close owned files`() =
        voiceTest {
            for (phase in Phase.entries) {
                val harness = Harness()
                var nativeCancelled = false
                when (phase) {
                    Phase.PREPARING -> {
                        harness.decoder = { awaitCancellation() }
                    }

                    Phase.WAITING -> {
                        harness.transcriber = { _, _, _, _, _ ->
                            try {
                                awaitCancellation()
                            } finally {
                                nativeCancelled = true
                            }
                        }
                    }

                    Phase.TRANSCRIBING -> {
                        harness.transcriber = { _, _, _, _, progress ->
                            progress(41)
                            try {
                                awaitCancellation()
                            } finally {
                                nativeCancelled = true
                            }
                        }
                    }
                }
                val vm = fixture(harness)
                val request = request("https://files.test/${phase.name.lowercase()}.opus")

                vm.transcribe(request)
                runCurrent()

                val state =
                    vm.state.value.transcripts
                        .getValue(request.attachment.playbackId)
                when (phase) {
                    Phase.PREPARING -> assertTrue(state is VoiceTranscriptState.Preparing)
                    Phase.WAITING -> assertEquals(VoiceTranscriptState.Waiting, state)
                    Phase.TRANSCRIBING -> assertEquals(VoiceTranscriptState.Transcribing(41), state)
                }
                vm.cancelTranscription(request.attachment.playbackId)
                runCurrent()

                assertNull(vm.state.value.transcripts[request.attachment.playbackId])
                assertTrue(harness.createdFiles.none(File::exists))
                if (phase != Phase.PREPARING) assertTrue(nativeCancelled)
            }
        }

    @Test
    fun `forced blank success removes the prior cached transcript`() =
        voiceTest {
            val harness = Harness().apply { cachedText = "stale" }
            harness.transcriber = { _, _, _, _, progress ->
                progress(100)
                ""
            }
            val vm = fixture(harness)
            val request = request("https://files.test/retry.opus")

            vm.transcribe(request, force = true)
            advanceUntilIdle()

            assertEquals(0, harness.cacheGets)
            assertEquals(1, harness.removedCacheKeys.size)
            assertNull(harness.cachedText)
            assertEquals(
                VoiceTranscriptState.Ready("", cached = false),
                vm.state.value.transcripts[request.attachment.playbackId],
            )
            assertTrue(harness.writtenTranscripts.isEmpty())
            assertTrue(harness.createdFiles.none(File::exists))
        }

    @Test
    fun `transcript limit accepts exact bytes and rejects multibyte overflow before cache or Ready`() =
        voiceTest {
            val exact = "a".repeat(MAX_TRANSCRIPT_BYTES)
            val exactHarness = Harness().apply { transcriber = { _, _, _, _, _ -> exact } }
            val exactVm = fixture(exactHarness)
            val exactRequest = request("https://files.test/exact.opus")

            exactVm.transcribe(exactRequest)
            advanceUntilIdle()

            assertEquals(
                VoiceTranscriptState.Ready(exact, cached = false),
                exactVm.state.value.transcripts[exactRequest.attachment.playbackId],
            )
            assertEquals(exact, exactHarness.cachedText)

            val overflow = "€".repeat(MAX_TRANSCRIPT_BYTES / 3) + "é"
            assertEquals(MAX_TRANSCRIPT_BYTES + 1, overflow.toByteArray(Charsets.UTF_8).size)
            assertTrue(overflow.length < MAX_TRANSCRIPT_BYTES)
            val overflowHarness = Harness().apply { transcriber = { _, _, _, _, _ -> overflow } }
            val overflowVm = fixture(overflowHarness)
            val overflowRequest = request("https://files.test/overflow.opus")

            overflowVm.transcribe(overflowRequest)
            advanceUntilIdle()

            assertEquals(
                VoiceTranscriptState.Failed(VoiceTranscriptFailureKind.INFERENCE_FAILED),
                overflowVm.state.value.transcripts[overflowRequest.attachment.playbackId],
            )
            assertNull(overflowHarness.cachedText)
            assertTrue(overflowHarness.writtenTranscripts.isEmpty())
            assertTrue(overflowHarness.removedCacheKeys.isEmpty())
            assertTrue((exactHarness.createdFiles + overflowHarness.createdFiles).none(File::exists))
        }

    @Test
    fun `forced nonblank success keeps prior cache while running then replaces it`() =
        voiceTest {
            val release = CompletableDeferred<Unit>()
            val harness = Harness().apply { cachedText = "prior" }
            harness.transcriber = { _, _, _, _, _ ->
                release.await()
                "replacement"
            }
            val vm = fixture(harness)
            val request = request("https://files.test/replace.opus")

            vm.transcribe(request, force = true)
            runCurrent()

            assertEquals(
                VoiceTranscriptState.Waiting,
                vm.state.value.transcripts[request.attachment.playbackId],
            )
            assertEquals("prior", harness.cachedText)
            assertEquals(0, harness.cacheGets)
            assertTrue(harness.removedCacheKeys.isEmpty())

            release.complete(Unit)
            advanceUntilIdle()

            assertEquals("replacement", harness.cachedText)
            assertEquals(
                VoiceTranscriptState.Ready("replacement", cached = false),
                vm.state.value.transcripts[request.attachment.playbackId],
            )
            assertTrue(harness.createdFiles.none(File::exists))
        }

    @Test
    fun `forced failure and cancellation preserve the prior cached transcript`() =
        voiceTest {
            val failedHarness = Harness().apply { cachedText = "prior failure" }
            failedHarness.transcriber = { _, _, _, _, _ ->
                throw AiRuntimeException(AiRuntimeFailure.INFERENCE)
            }
            val failedVm = fixture(failedHarness)
            val failedRequest = request("https://files.test/forced-failure.opus")

            failedVm.transcribe(failedRequest, force = true)
            advanceUntilIdle()

            assertEquals("prior failure", failedHarness.cachedText)
            assertEquals(0, failedHarness.cacheGets)
            assertTrue(failedHarness.removedCacheKeys.isEmpty())
            assertEquals(
                VoiceTranscriptState.Failed(VoiceTranscriptFailureKind.INFERENCE_FAILED),
                failedVm.state.value.transcripts[failedRequest.attachment.playbackId],
            )

            failedVm.transcribe(failedRequest)
            advanceUntilIdle()
            assertEquals(
                VoiceTranscriptState.Ready("prior failure", cached = true),
                failedVm.state.value.transcripts[failedRequest.attachment.playbackId],
            )
            assertEquals(1, failedHarness.cacheGets)

            val cancelledHarness = Harness().apply { cachedText = "prior cancellation" }
            cancelledHarness.transcriber = { _, _, _, _, progress ->
                progress(20)
                awaitCancellation()
            }
            val cancelledVm = fixture(cancelledHarness)
            val cancelledRequest = request("https://files.test/forced-cancel.opus")

            cancelledVm.transcribe(cancelledRequest, force = true)
            runCurrent()
            assertEquals(
                VoiceTranscriptState.Transcribing(20),
                cancelledVm.state.value.transcripts[cancelledRequest.attachment.playbackId],
            )
            assertEquals("prior cancellation", cancelledHarness.cachedText)

            cancelledVm.cancelTranscription(cancelledRequest.attachment.playbackId)
            runCurrent()

            assertNull(cancelledVm.state.value.transcripts[cancelledRequest.attachment.playbackId])
            assertEquals("prior cancellation", cancelledHarness.cachedText)
            assertEquals(0, cancelledHarness.cacheGets)
            assertTrue(cancelledHarness.removedCacheKeys.isEmpty())

            cancelledVm.transcribe(cancelledRequest)
            advanceUntilIdle()
            assertEquals(
                VoiceTranscriptState.Ready("prior cancellation", cached = true),
                cancelledVm.state.value.transcripts[cancelledRequest.attachment.playbackId],
            )
            assertEquals(1, cancelledHarness.cacheGets)
            assertTrue((failedHarness.createdFiles + cancelledHarness.createdFiles).none(File::exists))
        }

    @Test
    fun `cache failure is typed and closes leases`() =
        voiceTest {
            val harness = Harness().apply { cacheFailure = IOException("private path must not escape") }
            val vm = fixture(harness)
            val request = request("https://files.test/cache.opus")

            vm.transcribe(request)
            advanceUntilIdle()

            assertEquals(
                VoiceTranscriptState.Failed(VoiceTranscriptFailureKind.CACHE_FAILED),
                vm.state.value.transcripts[request.attachment.playbackId],
            )
            assertTrue(harness.createdFiles.none(File::exists))
        }

    @Test
    fun `superseded non cooperative completion cannot replace newer result`() =
        voiceTest {
            val harness = Harness()
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            harness.transcriber = { _, _, _, _, _ ->
                if (harness.nativeCalls == 1) {
                    firstStarted.complete(Unit)
                    withContext(NonCancellable) { releaseFirst.await() }
                    "obsolete"
                } else {
                    "newest"
                }
            }
            val vm = fixture(harness)
            val request = request("https://files.test/same.opus")

            vm.transcribe(request)
            runCurrent()
            assertTrue(firstStarted.isCompleted)
            vm.transcribe(request)
            runCurrent()
            assertEquals(
                VoiceTranscriptState.Ready("newest", cached = false),
                vm.state.value.transcripts[request.attachment.playbackId],
            )

            releaseFirst.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                VoiceTranscriptState.Ready("newest", cached = false),
                vm.state.value.transcripts[request.attachment.playbackId],
            )
            assertTrue(harness.createdFiles.none(File::exists))
        }

    @Test
    fun `background feature model and settings changes cancel all independent requests`() =
        voiceTest {
            val mutations: List<(Harness, VoiceMessageViewModel) -> Unit> =
                listOf(
                    { _, vm -> vm.stopForBackground() },
                    { harness, _ -> harness.labs.value = readyLabs(enabled = false) },
                    { harness, _ -> harness.labs.value = readyLabs(modelId = "b".repeat(64)) },
                    {
                        harness,
                        _,
                        ->
                        harness.labs.value = readyLabs(settings = TranscriptionSettings(language = "en", cpuThreads = 1))
                    },
                )
            mutations.forEachIndexed { index, mutate ->
                val harness = Harness()
                var cancelled = 0
                harness.transcriber = { _, _, _, _, progress ->
                    progress(20)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled++
                    }
                }
                val vm = fixture(harness)
                val first = request("https://files.test/$index-a.opus", eventId = index * 2L)
                val second = request("https://files.test/$index-b.opus", eventId = index * 2L + 1, isSelf = true)
                vm.transcribe(first)
                vm.transcribe(second)
                runCurrent()
                assertEquals(2, vm.state.value.transcripts.size)

                mutate(harness, vm)
                runCurrent()

                assertTrue(
                    vm.state.value.transcripts
                        .isEmpty(),
                )
                assertEquals(2, cancelled)
                assertTrue(harness.createdFiles.none(File::exists))
            }
        }

    @Test
    fun `all materializer decoder and runtime failures map to content free kinds`() {
        val inputExpected =
            listOf(
                VoiceTranscriptFailureKind.UNSUPPORTED_SCHEME,
                VoiceTranscriptFailureKind.FILE_UNAVAILABLE,
                VoiceTranscriptFailureKind.EXPIRED,
                VoiceTranscriptFailureKind.MISSING_ENCRYPTION_KEY,
                VoiceTranscriptFailureKind.INVALID_ENCRYPTION_KEY,
                VoiceTranscriptFailureKind.AUTHENTICATION_FAILED,
                VoiceTranscriptFailureKind.ROUTE_UNAVAILABLE,
                VoiceTranscriptFailureKind.TLS_FAILED,
                VoiceTranscriptFailureKind.HTTP_AUTHENTICATION_FAILED,
                VoiceTranscriptFailureKind.HTTP_FAILED,
                VoiceTranscriptFailureKind.READ_FAILED,
                VoiceTranscriptFailureKind.INPUT_TOO_LARGE,
            )
        assertEquals(inputExpected, AudioInputFailureKind.entries.map { voiceTranscriptFailure(AudioInputException(it)) })
        assertEquals(
            listOf(
                VoiceTranscriptFailureKind.NO_AUDIO,
                VoiceTranscriptFailureKind.UNSUPPORTED_CODEC,
                VoiceTranscriptFailureKind.AUDIO_TOO_LONG,
                VoiceTranscriptFailureKind.DECODE_FAILED,
            ),
            PcmAudioFailureKind.entries.map { voiceTranscriptFailure(PcmAudioException(it)) },
        )
        assertEquals(
            listOf(
                VoiceTranscriptFailureKind.MODEL_OPEN,
                VoiceTranscriptFailureKind.INVALID_MODEL_FORMAT,
                VoiceTranscriptFailureKind.TRUNCATED_MODEL,
                VoiceTranscriptFailureKind.CORRUPT_MODEL,
                VoiceTranscriptFailureKind.UNSUPPORTED_MODEL_ARCHITECTURE,
                VoiceTranscriptFailureKind.INVALID_REQUEST,
                VoiceTranscriptFailureKind.OUT_OF_MEMORY,
                VoiceTranscriptFailureKind.INVALID_AUDIO,
                VoiceTranscriptFailureKind.NO_MODEL_LOADED,
                VoiceTranscriptFailureKind.INFERENCE_FAILED,
                VoiceTranscriptFailureKind.NATIVE_FAILURE,
            ),
            AiRuntimeFailure.entries.map { voiceTranscriptFailure(AiRuntimeException(it)) },
        )
        assertEquals(
            VoiceTranscriptFailureKind.FEATURE_UNAVAILABLE,
            voiceTranscriptFailure(AiExecutionUnavailableException()),
        )
        val mappedKinds =
            AudioInputFailureKind.entries.map {
                voiceTranscriptFailure(AudioInputException(it))
            } +
                PcmAudioFailureKind.entries.map {
                    voiceTranscriptFailure(PcmAudioException(it))
                } +
                AiRuntimeFailure.entries.map {
                    voiceTranscriptFailure(AiRuntimeException(it))
                } +
                VoiceTranscriptFailureKind.FEATURE_UNAVAILABLE +
                VoiceTranscriptFailureKind.CACHE_FAILED
        assertEquals(VoiceTranscriptFailureKind.entries.toSet(), mappedKinds.toSet())
        assertEquals(50, preparationProgress(5, 10))
        assertEquals(0, preparationProgress(5, null))
    }

    private fun voiceTest(block: suspend TestScope.() -> Unit) {
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                block()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    private suspend fun TestScope.fixture(
        harness: Harness,
        recorder: VoiceRecorder = FakeRecorder,
        sender: VoiceMessageSender = FakeSender,
        playback: AudioPlaybackController = FakePlaybackController(),
        config: VoiceConfig = VoiceConfig(),
    ): VoiceMessageViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = VoicePrefs(context)
        prefs.replace(config)
        val vm =
            VoiceMessageViewModel(
                savedStateHandle = SavedStateHandle(mapOf("bufferId" to 9L)),
                recorder = recorder,
                sender = sender,
                prefs = prefs,
                activityTracker =
                    io.github.trevarj.motd.audio
                        .AudioActivityTracker(),
                playbackController = playback,
                transcriptionCalls = harness.calls(),
            )
        runCurrent()
        vm.state.first { it.config == config }
        return vm
    }

    private fun TestScope.recordingTick() {
        runCurrent()
        advanceTimeBy(250L)
        runCurrent()
    }

    private fun preview(staged: StagedVoiceMessage) =
        AudioAttachment(
            url = staged.file.toURI().toString(),
            title = "Voice message",
            mimeType = staged.mimeType,
            durationMs = staged.durationMs,
            voice = true,
        )

    private fun request(
        url: String,
        eventId: Long = 1,
        isSelf: Boolean = false,
    ): AudioPlaybackRequest =
        AudioPlaybackRequest(
            attachment =
                AudioAttachment(
                    url = url,
                    title = "voice.opus",
                    mimeType = "audio/ogg",
                    voice = true,
                    encrypted = "motd-key=" in url,
                ),
            networkId = 7,
            origin =
                AudioPlaybackOrigin(
                    bufferId = 9,
                    networkId = 7,
                    conversation = "#voice",
                    sender = if (isSelf) "me" else "alice",
                    isSelf = isSelf,
                    directMessage = false,
                    eventId = eventId,
                    msgid = "msg-$eventId",
                    serverTime = eventId,
                ),
        )

    private class Harness {
        val root =
            File(
                ApplicationProvider.getApplicationContext<Context>().cacheDir,
                "voice-vm-${UUID.randomUUID()}",
            ).apply { mkdirs() }
        val labs = MutableStateFlow(readyLabs())
        val materializedRequests = mutableListOf<AudioPlaybackRequest>()
        val createdFiles = mutableListOf<File>()
        val removedCacheKeys = mutableListOf<String>()
        val writtenTranscripts = mutableListOf<Pair<String, String>>()
        var cacheGets = 0
        var cachedText: String? = null
        var cacheFailure: Exception? = null
        var nativeCalls = 0
        var decoder: suspend (File) -> LocalPcmAudioLease = { newPcmLease() }
        var transcriber:
            suspend (String, File, File, AiTranscriptionRequest, (Int) -> Unit) -> String =
            { _, _, _, _, progress ->
                progress(100)
                "transcribed"
            }

        fun calls() =
            VoiceTranscriptionCalls(
                labsState = labs,
                modelFile = { File(root, "$it.model") },
                materialize = { request, progress ->
                    materializedRequests += request
                    progress(50, 100)
                    newAudioLease()
                },
                decode = { decoder(it) },
                cacheGet = {
                    cacheGets++
                    cacheFailure?.let { throw it }
                    cachedText
                },
                cachePut = { key, text ->
                    writtenTranscripts += key to text
                    cachedText = text
                },
                cacheRemove = { key ->
                    removedCacheKeys += key
                    cachedText = null
                },
                transcribe = { modelId, modelFile, pcmWav, request, progress ->
                    nativeCalls++
                    transcriber(modelId, modelFile, pcmWav, request, progress)
                },
            )

        private fun newAudioLease(): LocalAudioLease {
            val file = File.createTempFile("input-", ".opus", root).apply { writeText("audio") }
            createdFiles += file
            return LocalAudioLease(file, owned = true)
        }

        private fun newPcmLease(): LocalPcmAudioLease {
            val file = File.createTempFile("pcm-", ".wav", root).apply { writeText("pcm") }
            createdFiles += file
            return LocalPcmAudioLease(file, "c".repeat(64), durationMillis = 1_000)
        }
    }

    private enum class Phase { PREPARING, WAITING, TRANSCRIBING }

    private class RecordingRecorder : VoiceRecorder {
        var ageMs = 0L
        var startFailure: Exception? = null
        var tooShort = false
        var completedDurationMs: Long? = null
        private var active: ActiveVoiceRecording? = null

        override fun start(
            profile: VoiceRecordingProfile,
            nowMs: Long,
        ): ActiveVoiceRecording {
            startFailure?.let { throw it }
            return ActiveVoiceRecording(
                file =
                    File
                        .createTempFile(
                            "voice-recording-",
                            ".ogg",
                            ApplicationProvider.getApplicationContext<Context>().cacheDir,
                        ).apply { writeText("audio") },
                startedAtMs = nowMs - ageMs,
                mimeType = "audio/ogg",
                extension = "ogg",
            ).also { active = it }
        }

        override fun currentAmplitude(): Int = 10

        override fun stop(nowMs: Long): CompletedVoiceRecording? {
            val recording = active ?: return null
            active = null
            if (tooShort) {
                recording.file.delete()
                return null
            }
            return CompletedVoiceRecording(
                file = recording.file,
                durationMs = completedDurationMs ?: (nowMs - recording.startedAtMs).coerceAtLeast(1_000L),
                mimeType = recording.mimeType,
                extension = recording.extension,
                sizeBytes = recording.file.length(),
            )
        }

        override fun cancel() {
            active?.file?.delete()
            active = null
        }
    }

    private object FakeRecorder : VoiceRecorder {
        override fun start(
            profile: VoiceRecordingProfile,
            nowMs: Long,
        ): ActiveVoiceRecording = error("unused")

        override fun currentAmplitude(): Int? = null

        override fun stop(nowMs: Long): CompletedVoiceRecording? = null

        override fun cancel() = Unit
    }

    private object FakeSender : VoiceMessageSender {
        override fun send(request: VoiceSendRequest): Flow<VoiceSendProgress> = emptyFlow()
    }

    private class FakePlaybackController : AudioPlaybackController {
        override val state = MutableStateFlow(AudioPlaybackState())
        override val waveforms: StateFlow<Map<String, AudioWaveform>> = MutableStateFlow(emptyMap())
        override val cacheStatuses: StateFlow<Map<String, AudioCacheStatus>> = MutableStateFlow(emptyMap())

        override fun play(
            request: AudioPlaybackRequest,
            speed: Float,
        ) = Unit

        override fun toggle(request: AudioPlaybackRequest) {
            val attachment = request.attachment
            state.value =
                if (state.value.activeId == attachment.playbackId) {
                    state.value.copy(playing = !state.value.playing)
                } else {
                    AudioPlaybackState(activeId = attachment.playbackId, attachment = attachment, playing = true)
                }
        }

        override fun inspectCache(attachment: AudioAttachment) = Unit

        override fun toggleActive() = Unit

        override fun pause() {
            state.value = state.value.copy(playing = false)
        }

        override fun dismiss(itemId: String) {
            if (state.value.activeId == itemId) state.value = AudioPlaybackState()
        }

        override fun cancelLoading() = Unit

        override fun retryActive() = Unit

        override fun seekTo(
            itemId: String,
            positionMs: Long,
        ) {
            if (state.value.activeId == itemId) state.value = state.value.copy(positionMs = positionMs)
        }

        override fun setSpeed(
            itemId: String,
            speed: Float,
        ) = Unit
    }

    companion object {
        private fun readyLabs(
            enabled: Boolean = true,
            modelId: String = "a".repeat(64),
            settings: TranscriptionSettings = TranscriptionSettings(cpuThreads = 1),
        ): AiLabsState {
            val model =
                AiModelRecord(
                    id = modelId,
                    displayName = "Whisper",
                    sizeBytes = 1,
                    format = AiModelFormat.WHISPER_GGML,
                    capabilities = setOf(AiModelCapability.TRANSCRIPTION),
                    metadata =
                        AiModelMetadata(
                            architecture = "whisper",
                            quantization = "q5",
                            maximumAudioSeconds = 900,
                            maximumCpuThreads = 4,
                        ),
                    importedAtEpochMillis = 0,
                )
            return AiLabsState(
                enabledFeatures = if (enabled) setOf(AiFeature.TRANSCRIPTION) else emptySet(),
                models = listOf(model),
                assignments = listOf(AiFeatureAssignment(AiFeature.TRANSCRIPTION, modelId)),
                transcriptionSettings = listOf(AiTranscriptionSettingsRecord(modelId, settings)),
            )
        }
    }
}
