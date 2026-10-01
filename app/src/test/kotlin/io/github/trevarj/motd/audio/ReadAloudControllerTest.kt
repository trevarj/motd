package io.github.trevarj.motd.audio

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.Voice
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.ai.AiFeature
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkIdentityEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.testing.ReadAloudHarness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReadAloudControllerTest {
    private fun readerTest(block: suspend TestScope.(MotdDatabase, BufferEntity, ReadAloudHarness) -> Unit) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db =
                Room
                    .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                    .allowMainThreadQueries()
                    .setQueryExecutor { it.run() }
                    .setTransactionExecutor { it.run() }
                    .build()
            var activeReader: ReadAloudHarness? = null
            try {
                val network = db.networkDao().insert(NetworkEntity(name = "speech", role = NetworkRole.DIRECT, host = "irc.example", port = 6697, nick = "me", username = "me", realname = "Me"))
                val seed = BufferEntity(networkId = network, name = "#room", displayName = "#room", type = BufferType.CHANNEL, joined = true)
                val room = seed.copy(id = db.bufferDao().insert(seed))
                val fixture = ReadAloudHarness(context, db, backgroundScope)
                activeReader = fixture
                fixture.prefs.replaceSystem(ReadAloudConfig())
                fixture.selected.set(room.id)
                runCurrent()
                fixture.controller.setEnabled(room.id, true)
                runCurrent()
                val ready =
                    fixture.controller.state.first {
                        it.status == ReadAloudStatus.WAITING || it.error != null || !it.enabled
                    }
                check(ready.enabled && ready.status == ReadAloudStatus.WAITING) { "Reader startup failed: $ready" }
                block(db, room, fixture)
            } finally {
                activeReader?.controller?.stop()
                runCurrent()
                db.close()
                Dispatchers.resetMain()
            }
        }

    private fun message(
        room: BufferEntity,
        id: Long,
        text: String = "message $id",
    ) = MessageEntity(
        id = id,
        bufferId = room.id,
        sender = "trev",
        normalizedActor = "trev",
        text = text,
        kind = MessageKind.PRIVMSG,
        serverTime = id,
        dedupKey = "reader-$id",
    )

    @Test fun completionThenConfiguredGapPreservesIncomingOrderAndPausePosition() =
        readerTest { _, room, f ->
            f.controller.onIncoming(message(room, 1, "hello how are you"))
            f.controller.onIncoming(message(room, 2, "waves").copy(kind = MessageKind.ACTION))
            runCurrent()
            assertEquals(listOf("trev says, hello how are you"), f.synth.utterances)
            assertEquals(1, f.controller.state.value.position)
            f.controller.togglePaused()
            assertTrue(f.output.paused)
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(1, f.output.played.size)
            f.controller.togglePaused()
            assertFalse(f.output.paused)
            assertEquals(1, f.synth.utterances.size)
            f.output.complete()
            runCurrent()
            advanceTimeBy(349)
            runCurrent()
            assertEquals(1, f.synth.utterances.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("trev says, hello how are you", "trev waves"), f.synth.utterances)
            assertEquals(2, f.controller.state.value.position)
            assertFalse(
                f.output.played
                    .first()
                    .exists(),
            )
        }

    @Test fun arrivalAtTheWaitingTransitionStartsWithoutAnotherArrivalOrManualControl() =
        readerTest { _, room, f ->
            f.controller.onIncoming(message(room, 1))
            runCurrent()
            val arrival =
                backgroundScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    f.controller.state.first { it.status == ReadAloudStatus.WAITING }
                    f.controller.onIncoming(message(room, 2))
                }
            f.output.complete()
            runCurrent()
            advanceTimeBy(350)
            runCurrent()
            arrival.join()
            runCurrent()
            assertEquals(listOf("trev says, message 1", "trev says, message 2"), f.synth.utterances)
            assertEquals(
                2L,
                f.controller.state.value.current
                    ?.id,
            )
            assertEquals(ReadAloudStatus.PLAYING, f.controller.state.value.status)
        }

    @Test fun changedVoiceOptionsDrainLateSynthesisAndReplacePlayingAudioWithoutAdvancing() =
        readerTest { _, room, f ->
            val release = CompletableDeferred<Unit>()
            var stale: java.io.File? = null
            f.synth.synthesize = { selected, file ->
                if (selected.options.voice == null) {
                    stale = file
                    withContext(NonCancellable) {
                        release.await()
                        file.writeText("old voice audio")
                    }
                } else {
                    file.writeText("selected voice audio at ${selected.options.rate}/${selected.options.pitch}")
                }
            }
            f.controller.onIncoming(message(room, 1))
            runCurrent()
            assertTrue(stale != null)
            val selected =
                f.controller.config.value
                    .copy(options = ReadAloudConfig(voice = "named-offline-voice", rate = 1.2f, pitch = .8f))
            val saved = CompletableDeferred<Unit>()
            f.synth.onCancel = { saved.complete(Unit) }
            f.controller.saveVoiceOptions(selected)
            saved.await()
            f.controller.config.first { it.options == selected.options }
            runCurrent()
            assertTrue(f.output.played.isEmpty())
            release.complete(Unit)
            runCurrent()
            assertFalse(checkNotNull(stale).exists())
            assertEquals(1, f.output.played.size)
            assertEquals(
                "selected voice audio at 1.2/0.8",
                f.output.played
                    .single()
                    .readText(),
            )
            assertEquals(
                1L,
                f.controller.state.value.current
                    ?.id,
            )
            val replaced = f.output.played.single()
            val faster =
                f.controller.config.value
                    .copy(options = selected.options.copy(rate = 1.3f, pitch = 1.1f))
            val savedAgain = CompletableDeferred<Unit>()
            f.synth.onCancel = { savedAgain.complete(Unit) }
            f.controller.saveVoiceOptions(faster)
            savedAgain.await()
            f.controller.config.first { it.options == faster.options }
            runCurrent()
            assertFalse(replaced.exists())
            assertEquals(2, f.output.played.size)
            assertEquals(
                "selected voice audio at 1.3/1.1",
                f.output.played
                    .last()
                    .readText(),
            )
            assertEquals(
                1L,
                f.controller.state.value.current
                    ?.id,
            )
            assertEquals(ReadAloudStatus.PLAYING, f.controller.state.value.status)
        }

    @Test fun rapidVoiceCutoversDrainAllPredecessorsAndKeepThePausedCursorWithoutRestartingDisabledReading() =
        readerTest { _, room, f ->
            val release = CompletableDeferred<Unit>()
            var stale: java.io.File? = null
            f.synth.synthesize = { selected, file ->
                val voice = selected.options.voice
                if (voice == null) {
                    stale = file
                    withContext(NonCancellable) {
                        release.await()
                        file.writeText("stale audio")
                    }
                } else {
                    file.writeText(voice)
                }
            }
            try {
                f.controller.onIncoming(message(room, 1))
                f.controller.onIncoming(message(room, 2))
                runCurrent()
                assertTrue(stale != null)
                f.controller.saveVoiceOptions(
                    f.controller.config.value
                        .copy(options = ReadAloudConfig(voice = "first")),
                )
                f.controller.config.first { it.options.voice == "first" }
                runCurrent()
                f.controller.saveVoiceOptions(
                    f.controller.config.value
                        .copy(options = ReadAloudConfig(voice = "latest", rate = 1.2f)),
                )
                f.controller.config.first { it.options.voice == "latest" }
                runCurrent()
                // Neither replacement is dispatched before the second navigation cancels the first.
                f.controller.skip()
                f.controller.previous()
                runCurrent()
                assertEquals(1, f.synth.selections.size)
                assertTrue(f.output.played.isEmpty())
                f.controller.togglePaused()
                release.complete(Unit)
                runCurrent()
                assertFalse(checkNotNull(stale).exists())
                assertTrue(f.controller.state.value.paused)
                assertEquals(
                    1L,
                    f.controller.state.value.current
                        ?.id,
                )
                assertEquals(1, f.controller.state.value.pending)
                assertTrue(f.output.played.isEmpty())
                f.controller.togglePaused()
                runCurrent()
                assertEquals(
                    "latest",
                    f.output.played
                        .single()
                        .readText(),
                )
                assertEquals(listOf("trev says, message 1", "trev says, message 1"), f.synth.utterances)
                assertEquals(
                    1L,
                    f.controller.state.value.current
                        ?.id,
                )
                f.controller.stop()
                f.controller.saveVoiceOptions(
                    f.controller.config.value
                        .copy(options = ReadAloudConfig(voice = "disabled")),
                )
                f.controller.config.first { it.options.voice == "disabled" }
                runCurrent()
                assertFalse(f.controller.state.value.enabled)
                assertEquals(2, f.synth.utterances.size)
            } finally {
                release.complete(Unit)
            }
        }

    @Test fun undispatchedPreviewReplacementsDrainTheOriginalWriterBeforePlayingAndNeverEnableReading() =
        readerTest { _, room, f ->
            val release = CompletableDeferred<Unit>()
            var stale: java.io.File? = null
            f.synth.synthesize = { _, file ->
                if (stale == null) {
                    stale = file
                    withContext(NonCancellable) {
                        release.await()
                        file.writeText("stale audio")
                    }
                } else {
                    file.writeText("preview audio")
                }
            }
            try {
                f.controller.onIncoming(message(room, 1))
                runCurrent()
                assertTrue(stale != null)
                val selection = f.controller.config.value
                f.controller.preview(room.id, selection)
                f.controller.preview(room.id, selection)
                runCurrent()
                assertEquals(1, f.synth.selections.size)
                assertTrue(f.output.played.isEmpty())
                release.complete(Unit)
                runCurrent()
                assertFalse(checkNotNull(stale).exists())
                val preview = f.output.played.single()
                assertTrue(preview != stale && preview.exists())
                assertTrue(f.controller.state.value.previewing)
                assertFalse(f.controller.state.value.enabled)
                assertEquals(null, f.controller.state.value.current)
                f.controller.stopPreview()
                runCurrent()
                assertFalse(preview.exists())
                assertFalse(f.controller.state.value.previewing)
                f.controller.onIncoming(message(room, 2))
                runCurrent()
                assertFalse(f.controller.state.value.enabled)
                assertEquals(2, f.synth.selections.size)
            } finally {
                release.complete(Unit)
            }
        }

    @Test fun persistedGapBoundsChangeActualPacingWithoutPersistingTheOptInSession() =
        readerTest { _, room, f ->
            f.prefs.replaceSystem(ReadAloudConfig(rate = 1.2f, pitch = .8f, gapMs = 5_000))
            f.controller.config.first { it.options.gapMs == 1_000 }
            f.controller.onIncoming(message(room, 1))
            f.controller.onIncoming(message(room, 2))
            runCurrent()
            f.output.complete()
            runCurrent()
            advanceTimeBy(999)
            runCurrent()
            assertEquals(listOf("trev says, message 1"), f.synth.utterances)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("trev says, message 1", "trev says, message 2"), f.synth.utterances)
            f.controller.stop()
            val saved = ReadAloudPrefs(ApplicationProvider.getApplicationContext()).systemConfig.first()
            assertEquals(1.2f, saved.rate, 0f)
            assertEquals(.8f, saved.pitch, 0f)
            assertEquals(1_000, saved.gapMs)
            assertFalse(f.controller.state.value.enabled)
            f.prefs.replaceSystem(saved.copy(gapMs = -50))
            f.controller.config.first { it.options.gapMs == 0 }
            f.controller.setEnabled(room.id, true)
            f.controller.onIncoming(message(room, 3))
            f.controller.onIncoming(message(room, 4))
            runCurrent()
            f.output.complete()
            runCurrent()
            assertEquals("trev says, message 4", f.synth.utterances.last())
        }

    @Test fun previousSkipAndLatestUseOnlyBoundedSessionAndStopClearsIt() =
        readerTest { _, room, f ->
            (1L..3L).forEach { f.controller.onIncoming(message(room, it)) }
            runCurrent()
            f.controller.skip()
            runCurrent()
            assertEquals(
                2L,
                f.controller.state.value.current
                    ?.id,
            )
            f.controller.previous()
            runCurrent()
            assertEquals(
                1L,
                f.controller.state.value.current
                    ?.id,
            )
            f.controller.latest()
            runCurrent()
            assertEquals(
                3L,
                f.controller.state.value.current
                    ?.id,
            )
            assertFalse(f.controller.state.value.canLatest)
            f.controller.skip()
            runCurrent()
            assertEquals(ReadAloudStatus.WAITING, f.controller.state.value.status)
            assertFalse(f.controller.state.value.canSkip)
            f.controller.previous()
            runCurrent()
            assertEquals(
                3L,
                f.controller.state.value.current
                    ?.id,
            )
            assertEquals(listOf("trev says, message 1", "trev says, message 2", "trev says, message 1", "trev says, message 3", "trev says, message 3"), f.synth.utterances)
            f.controller.stop()
            runCurrent()
            assertFalse(f.controller.state.value.enabled)
            assertEquals(0, f.controller.state.value.total)
            assertTrue(f.output.played.none { it.exists() })
        }

    @Test fun overflowKeepsCurrentDropsOldestPendingAndPrunesCompletedHistory() =
        readerTest { _, room, f ->
            f.controller.onIncoming(message(room, 1))
            runCurrent()
            (2L..55L).forEach { f.controller.onIncoming(message(room, it)) }
            assertEquals(50, f.controller.state.value.total)
            assertEquals(5, f.controller.state.value.skipped)
            assertEquals(
                1L,
                f.controller.state.value.current
                    ?.id,
            )
            f.controller.skip()
            runCurrent()
            assertEquals(
                7L,
                f.controller.state.value.current
                    ?.id,
            )
            f.controller.onIncoming(message(room, 56))
            assertEquals(50, f.controller.state.value.total)
            assertEquals(5, f.controller.state.value.skipped)
            assertFalse(f.controller.state.value.canPrevious)
            assertEquals(
                7L,
                f.controller.state.value.current
                    ?.id,
            )
            f.controller.latest()
            runCurrent()
            assertEquals(
                56L,
                f.controller.state.value.current
                    ?.id,
            )
        }

    @Test fun navigationBackgroundRecordingAndAttachmentIntentNeverAutoResume() =
        readerTest { _, room, f ->
            val stopCauses: List<() -> Unit> =
                listOf(
                    { f.selected.set(null) },
                    { f.visible.value = false },
                    { f.activity.stopReadingBeforeRecording() },
                    { f.activity.setRecording(true) },
                    { f.audio.state.value = AudioPlaybackState(loading = true) },
                    { f.audio.state.value = AudioPlaybackState(playing = true) },
                    { f.output.onInterrupted?.invoke("headphones disconnected") },
                )
            stopCauses.forEachIndexed { index, stop ->
                f.selected.set(room.id)
                f.visible.value = true
                f.activity.setRecording(false)
                f.audio.state.value = AudioPlaybackState()
                runCurrent()
                f.controller.setEnabled(room.id, true)
                f.controller.onIncoming(message(room, index.toLong() + 1))
                runCurrent()
                stop()
                runCurrent()
                assertFalse(f.controller.state.value.enabled)
                assertEquals(0, f.controller.state.value.total)
                f.selected.set(room.id)
                f.visible.value = true
                f.activity.setRecording(false)
                f.audio.state.value = AudioPlaybackState()
                runCurrent()
                assertFalse(f.controller.state.value.enabled)
            }
        }

    @Test fun pauseDuringSynthesisKeepsTheSameUtteranceUntilExplicitResume() =
        readerTest { _, room, f ->
            f.synth.gate = CompletableDeferred()
            f.controller.onIncoming(message(room, 1))
            runCurrent()
            f.controller.togglePaused()
            f.synth.gate?.complete(Unit)
            runCurrent()
            assertTrue(f.output.played.isEmpty())
            assertEquals(1, f.synth.utterances.size)
            f.controller.togglePaused()
            runCurrent()
            assertEquals(1, f.output.played.size)
            assertEquals(1, f.synth.utterances.size)
            assertEquals(ReadAloudStatus.PLAYING, f.controller.state.value.status)
        }

    @Test fun headphoneRemovalDuringSynthesisClearsSessionBeforeAnySpeakerOutput() =
        readerTest { _, room, f ->
            f.synth.gate = CompletableDeferred()
            f.controller.onIncoming(message(room, 1))
            runCurrent()
            ApplicationProvider.getApplicationContext<Context>().sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            f.synth.gate?.complete(Unit)
            runCurrent()
            assertTrue(f.output.played.isEmpty())
            assertFalse(f.controller.state.value.enabled)
            assertEquals(0, f.controller.state.value.total)
            assertEquals(ReadAloudStatus.ERROR, f.controller.state.value.status)
        }

    @Test fun dismissedAndMissingRoomsCancelQueuedSpeech() =
        readerTest { db, room, f ->
            f.synth.gate = CompletableDeferred()
            f.controller.onIncoming(message(room, 1))
            runCurrent()
            db.bufferDao().update(room.copy(dismissed = true))
            runCurrent()
            f.synth.gate?.complete(Unit)
            runCurrent()
            assertFalse(f.controller.state.value.enabled)
            assertTrue(f.output.played.isEmpty())
            db.bufferDao().update(room)
            runCurrent()
            f.controller.setEnabled(room.id, true)
            f.synth.gate = CompletableDeferred()
            f.controller.onIncoming(message(room, 2))
            runCurrent()
            db.bufferDao().deleteBufferRow(room.id)
            f.synth.gate?.complete(Unit)
            runCurrent()
            assertFalse(f.controller.state.value.enabled)
            assertTrue(f.output.played.isEmpty())
        }

    @Test fun stopDuringSynthesisDiscardsLateCompletionAndTemporaryAudio() =
        readerTest { _, room, f ->
            val release = CompletableDeferred<Unit>()
            var path: java.io.File? = null
            f.synth.synthesize = { _, output ->
                path = output
                withContext(NonCancellable) {
                    release.await()
                    output.writeText("late audio")
                }
            }
            f.controller.onIncoming(message(room, 1))
            runCurrent()
            f.controller.stop()
            release.complete(Unit)
            runCurrent()
            assertTrue(f.output.played.isEmpty())
            assertFalse(checkNotNull(path).exists())
            assertFalse(f.controller.state.value.enabled)
        }

    @Test fun immediateAsciiArrivalIsNotDroppedUsingPreviouslyLoadedRfc1459FoolRules() =
        readerTest { db, room, f ->
            db.networkIdentityDao().upsert(NetworkIdentityEntity(room.networkId, caseMapping = "ascii"))
            f.social.value = Settings(fools = setOf("[alice]"))
            runCurrent()
            f.controller.stop()
            f.controller.setEnabled(room.id, true)
            f.controller.onIncoming(message(room, 1, "distinct ASCII sender").copy(sender = "{alice}", normalizedActor = "{alice}"))
            f.controller.onIncoming(message(room, 2, "hidden configured sender").copy(sender = "[alice]", normalizedActor = "[alice]"))
            runCurrent()
            assertEquals(listOf("{alice} says, distinct ASCII sender"), f.synth.utterances)
            assertEquals(1, f.output.played.size)
            f.output.complete()
            runCurrent()
            advanceTimeBy(350)
            runCurrent()
            assertEquals(listOf("{alice} says, distinct ASCII sender"), f.synth.utterances)
            assertEquals(ReadAloudStatus.WAITING, f.controller.state.value.status)
        }

    @Test fun foolMutedAndClosedRoomAreRecheckedBeforeOutput() =
        readerTest { db, room, f ->
            f.social.value = Settings(fools = setOf("trev"))
            runCurrent()
            f.controller.onIncoming(message(room, 1))
            runCurrent()
            assertTrue(f.synth.utterances.isEmpty())
            f.social.value = Settings()
            f.synth.gate = CompletableDeferred()
            f.controller.onIncoming(message(room, 2))
            runCurrent()
            db.bufferDao().update(room.copy(muted = true))
            f.synth.gate?.complete(Unit)
            runCurrent()
            assertTrue(f.output.played.isEmpty())
            assertFalse(f.controller.state.value.enabled)
            db.bufferDao().update(room.copy(pendingCloseAt = 123))
            f.controller.setEnabled(room.id, true)
            runCurrent()
            assertTrue(f.output.played.isEmpty())
            assertTrue(f.controller.state.value.error != null)
        }

    @Test fun installedVoiceFailureIsVisibleAndCannotBeMistakenForPlaying() =
        readerTest { _, room, f ->
            f.synth.failure = "No installed offline voice"
            f.controller.onIncoming(message(room, 1))
            runCurrent()
            assertEquals(ReadAloudStatus.ERROR, f.controller.state.value.status)
            assertTrue(f.controller.state.value.paused)
            assertTrue(f.output.played.isEmpty())
            f.controller.stop()
            assertEquals(null, f.controller.state.value.error)
        }

    @Test fun localCutoverDrainsOldWritesKeepsPausedCursorAndIndependentProfiles() =
        readerTest { _, room, f ->
            f.prefs.replaceSystem(ReadAloudConfig(voice = "system", rate = .8f, pitch = 1.2f, gapMs = 250))
            f.controller.config.first { it.options.voice == "system" }
            val drained = CompletableDeferred<Unit>()
            var stale: java.io.File? = null
            f.synth.synthesize = { selection, file ->
                if (!selection.localEnabled) {
                    stale = file
                    withContext(NonCancellable) {
                        drained.await()
                        file.writeText("stale system")
                    }
                } else {
                    file.writeText("local ${selection.options.voice}")
                }
            }
            f.controller.onIncoming(message(room, 1))
            runCurrent()
            f.controller.togglePaused()
            f.labs.downloadKokoroAndUse().getOrThrow()
            val local = f.controller.config.first { it.localEnabled }
            f.labs.updateReadAloudConfig(ReadAloudConfig(voice = "16", rate = 1.3f, pitch = .7f, gapMs = 700), local.localVersion).getOrThrow()
            f.controller.config.first { it.options.voice == "16" }
            runCurrent()
            assertTrue(f.output.played.isEmpty())
            assertTrue(f.controller.state.value.paused)
            drained.complete(Unit)
            runCurrent()
            assertFalse(checkNotNull(stale).exists())
            f.controller.togglePaused()
            runCurrent()
            assertEquals(
                "local 16",
                f.output.played
                    .single()
                    .readText(),
            )
            assertEquals(
                1L,
                f.controller.state.value.current
                    ?.id,
            )
            assertEquals(1f, f.controller.config.value.options.pitch, 0f)
            f.labs.setFeatureEnabled(AiFeature.READ_ALOUD, false).getOrThrow()
            assertEquals(
                "system",
                f.controller.config
                    .first { !it.localEnabled }
                    .options.voice,
            )
            f.labs.setFeatureEnabled(AiFeature.READ_ALOUD, true).getOrThrow()
            assertEquals(
                "16",
                f.controller.config
                    .first { it.localEnabled }
                    .options.voice,
            )
            assertEquals(
                .8f,
                f.prefs.systemConfig
                    .first()
                    .rate,
                0f,
            )
        }

    @Test fun startupCorruptionKeepsLocalIntentAndNeverInvokesSystemThenExplicitDeleteDisables() =
        readerTest { _, room, f ->
            val installed = f.labs.downloadKokoroAndUse().getOrThrow()
            f.controller.config.first { it.localEnabled }
            f.labs
                .modelFile(installed.id)
                .resolve("model.int8.onnx")
                .writeText("broken")
            f.labs.reconcile().getOrThrow()
            f.controller.config.first { it.localEnabled && !it.localReady }
            f.controller.onIncoming(message(room, 1))
            runCurrent()
            assertEquals(ReadAloudStatus.ERROR, f.controller.state.value.status)
            assertTrue(f.synth.selections.none { !it.localEnabled })
            assertTrue(f.output.played.isEmpty())
            f.controller.stop()
            f.labs.downloadKokoroAndUse().getOrThrow()
            f.controller.config.first { it.localEnabled && it.localReady }
            f.labs.deleteModel(installed.id).getOrThrow()
            f.controller.config.first { !it.localEnabled }
            assertFalse(f.controller.state.value.enabled)
        }

    @Test fun concreteSelectorNeverUsesInstalledSpeechForMissingOrCorruptEnabledLocalModel() =
        readerTest { _, _, f ->
            f.controller.stop()
            val coordinator =
                io.github.trevarj.motd.ai.AiExecutionCoordinator(
                    io.github.trevarj.motd.ai
                        .WhisperSpeechModelRuntime(),
                    io.github.trevarj.motd.ai
                        .LlamaTextModelRuntime(),
                    io.github.trevarj.motd.ai
                        .NativeKokoroModelRuntime(),
                    object : io.github.trevarj.motd.service.AppVisibility {
                        override val onScreen = f.visible
                    },
                    StandardTestDispatcher(testScheduler),
                    backgroundScope,
                )
            coordinator.start()
            runCurrent()
            val installed = ReadAloudHarness.Synthesizer()
            val selector =
                io.github.trevarj.motd.ai
                    .AiReadAloudSynthesizer(installed, f.labs, coordinator)
            val output = java.io.File.createTempFile("strict-selector", ".wav", ApplicationProvider.getApplicationContext<Context>().cacheDir)
            try {
                val missing =
                    f.controller.config.value
                        .copy(localEnabled = true, localReady = false, modelId = "a".repeat(64))
                assertTrue(runCatching { selector.synthesize("Never sent to installed speech", missing, output) }.isFailure)
                f.labs.downloadKokoroAndUse().getOrThrow()
                val local = f.labs.readAloudConfiguration()!!
                assertTrue(local.localReady)
                // The fixture's metadata cannot authorize unsafe native loading of its non-pinned bytes.
                val failure = runCatching { selector.synthesize("Still never sent to installed speech", local, output) }.exceptionOrNull()
                assertTrue(failure is io.github.trevarj.motd.ai.AiLabsException)
                assertTrue(installed.utterances.isEmpty())
                assertEquals(0L, output.length())
                assertTrue(selector.voices.value.error != null)
                f.labs.setFeatureEnabled(AiFeature.READ_ALOUD, false).getOrThrow()
                selector.synthesize("Explicit installed speech", f.labs.readAloudConfiguration()!!, output)
                assertEquals(listOf("Explicit installed speech"), installed.utterances)
            } finally {
                output.delete()
            }
        }

    @Test fun pausedLocalPlaybackConfigurationCutoverDropsOldFileAndUsesCompletionGapWithoutAdvancing() =
        readerTest { _, room, f ->
            f.labs.downloadKokoroAndUse().getOrThrow()
            val selection = f.controller.config.first { it.localEnabled }
            f.controller.onIncoming(message(room, 1))
            f.controller.onIncoming(message(room, 2))
            runCurrent()
            val old = f.output.played.single()
            f.controller.togglePaused()
            f.labs.updateReadAloudConfig(ReadAloudConfig(voice = "26", gapMs = 900), selection.localVersion).getOrThrow()
            f.controller.config.first { it.options.voice == "26" }
            runCurrent()
            assertTrue(f.controller.state.value.paused)
            assertEquals(
                1L,
                f.controller.state.value.current
                    ?.id,
            )
            assertFalse(old.exists())
            assertEquals(1, f.output.played.size)
            f.controller.togglePaused()
            runCurrent()
            assertEquals(2, f.output.played.size)
            assertEquals(
                1L,
                f.controller.state.value.current
                    ?.id,
            )
            f.output.complete()
            runCurrent()
            advanceTimeBy(899)
            runCurrent()
            assertEquals(2, f.output.played.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(
                2L,
                f.controller.state.value.current
                    ?.id,
            )
            assertEquals(listOf("trev says, message 1", "trev says, message 1", "trev says, message 2"), f.synth.utterances)
        }

    @Test fun dormantSetupAndSettingsNeverStartReadingAndStaleSheetCannotSaveOrPreview() =
        readerTest { _, room, f ->
            f.controller.stop()
            val old = f.controller.config.value
            f.labs.downloadKokoroAndUse().getOrThrow()
            val local = f.controller.config.first { it.localEnabled }
            f.labs.updateReadAloudConfig(ReadAloudConfig(voice = "26", gapMs = 650), local.localVersion).getOrThrow()
            f.controller.config.first { it.options.voice == "26" }
            f.controller.preview(room.id, old)
            f.controller.saveVoiceOptions(old)
            runCurrent()
            assertFalse(f.controller.state.value.enabled)
            assertFalse(f.controller.state.value.previewing)
            assertTrue(f.synth.utterances.isEmpty())
            assertEquals("26", f.controller.config.value.options.voice)
        }

    @Test fun roomlessLocalPreviewStopsForBackgroundAndMediaWithoutOptIn() =
        readerTest { _, _, f ->
            f.controller.stop()
            f.selected.set(null)
            f.labs.downloadKokoroAndUse().getOrThrow()
            val local = f.controller.config.first { it.localEnabled && it.localReady }
            f.controller.previewLocal(local)
            runCurrent()
            assertTrue(f.controller.state.value.previewing)
            assertFalse(f.controller.state.value.enabled)
            assertEquals(listOf("Hello. This is your selected reading voice."), f.synth.utterances)
            f.visible.value = false
            runCurrent()
            assertFalse(f.controller.state.value.previewing)
            assertFalse(
                f.output.played
                    .single()
                    .exists(),
            )
            f.visible.value = true
            runCurrent()
            assertFalse(f.controller.state.value.previewing)
            f.controller.previewLocal(local)
            runCurrent()
            f.audio.state.value = AudioPlaybackState(loading = true)
            runCurrent()
            assertFalse(f.controller.state.value.previewing)
        }

    @Test fun speechSanitizesLinksVoiceMetadataAndControlsWithoutRewriting() {
        val normal =
            MessageEntity(
                bufferId = 1,
                serverTime = 1,
                sender = "trev",
                kind = MessageKind.PRIVMSG,
                text = "hello\n how\u0001 are you? https://user:password@example.com/a?token=secret#key",
                dedupKey = "normal",
            )
        assertEquals("trev says, hello how are you? link", readAloudUtterance(normal))
        assertEquals("trev waves", readAloudUtterance(normal.copy(kind = MessageKind.ACTION, text = "waves")))
        assertEquals("voice message", readAloudBody("[voice encrypted 0:12 audio/ogg expires=2026-01-01T00:00:00Z] https://example.com/file#key=secret"))
        assertTrue(usableOfflineVoice(Voice("actual-name", Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet())))
        assertFalse(usableOfflineVoice(Voice("network", Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, true, emptySet())))
        assertFalse(usableOfflineVoice(Voice("missing", Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, setOf("notInstalled"))))
    }
}
