package io.github.trevarj.motd.audio

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.Voice
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkIdentityEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObservationOrigin
import io.github.trevarj.motd.data.db.TimeProvenance
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.sync.CanonicalTimelineStore
import io.github.trevarj.motd.data.sync.TimelineObservation
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Locale
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReadAloudControllerTest {
    private fun readerTest(
        queryExecutor: Executor = Executor { it.run() },
        block: suspend TestScope.(MotdDatabase, BufferEntity, ReadAloudHarness) -> Unit,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db =
            Room
                .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor(queryExecutor)
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

    @Test fun selectingQueuedMessageReplaysVisibleSnapshotWithoutDuplicatesAndResumesPause() =
        readerTest { _, room, f ->
            f.controller.onIncoming(message(room, 1))
            f.controller.onIncoming(message(room, 2))
            runCurrent()
            val first = f.output.played.single()
            f.controller.togglePaused()
            f.controller.readMessage(message(room, 2, "updated visible body"))
            runCurrent()
            assertFalse(first.exists())
            assertFalse(f.controller.state.value.paused)
            assertFalse(f.output.paused)
            assertEquals(ReadAloudStatus.PLAYING, f.controller.state.value.status)
            assertEquals(
                2L,
                f.controller.state.value.current
                    ?.id,
            )
            assertEquals(2, f.controller.state.value.total)
            assertEquals(listOf("trev says, message 1", "trev says, updated visible body"), f.synth.utterances)
            val replayed = f.output.played.last()
            f.controller.readMessage(message(room, 2, "updated visible body"))
            runCurrent()
            assertFalse(replayed.exists())
            assertEquals(2, f.controller.state.value.total)
            assertEquals(3, f.output.played.size)
            assertEquals("trev says, updated visible body", f.synth.utterances.last())
            f.controller.previous()
            runCurrent()
            assertEquals(
                1L,
                f.controller.state.value.current
                    ?.id,
            )
        }

    @Test fun selectedEarlierNoticeAndOwnPendingJoinOnlyTheRetainedTimelineAndWakeWaiting() =
        readerTest { _, room, f ->
            val own = message(room, 15, "my pending message").copy(isSelf = true, sender = "me", normalizedActor = "me", msgid = null, pendingLabel = "own-pending")
            f.controller.onIncoming(own)
            f.controller.onIncoming(message(room, 1).copy(kind = MessageKind.NOTICE))
            assertEquals(0, f.controller.state.value.total)
            f.controller.onIncoming(message(room, 10))
            f.controller.onIncoming(message(room, 20))
            runCurrent()
            val earlier = message(room, 1, "earlier https://example.org/?token=secret").copy(kind = MessageKind.NOTICE)
            f.controller.readMessage(earlier)
            runCurrent()
            assertEquals("trev says, earlier link", f.synth.utterances.last())
            assertEquals(3, f.controller.state.value.total)
            assertEquals(2, f.controller.state.value.pending)
            assertFalse(f.controller.state.value.canPrevious)
            f.output.complete()
            runCurrent()
            advanceTimeBy(350)
            runCurrent()
            assertEquals(
                10L,
                f.controller.state.value.current
                    ?.id,
            )
            f.controller.readMessage(own)
            runCurrent()
            assertEquals("me says, my pending message", f.synth.utterances.last())
            assertEquals(4, f.controller.state.value.total)
            assertEquals(1, f.controller.state.value.pending)
            f.controller.previous()
            runCurrent()
            assertEquals(
                10L,
                f.controller.state.value.current
                    ?.id,
            )
            f.controller.latest()
            runCurrent()
            assertEquals(
                20L,
                f.controller.state.value.current
                    ?.id,
            )
            f.output.complete()
            runCurrent()
            advanceTimeBy(350)
            runCurrent()
            assertEquals(ReadAloudStatus.WAITING, f.controller.state.value.status)
            f.controller.readMessage(earlier)
            runCurrent()
            assertEquals(ReadAloudStatus.PLAYING, f.controller.state.value.status)
            assertEquals(
                1L,
                f.controller.state.value.current
                    ?.id,
            )
            assertEquals(4, f.controller.state.value.total)
        }

    @Test fun manualSelectionAtCapacityProtectsSelectedHistoryAndKeepsPendingAndLatest() =
        readerTest { _, room, f ->
            (10L..59L).forEach { f.controller.onIncoming(message(room, it)) }
            runCurrent()
            f.controller.readMessage(message(room, 1))
            runCurrent()
            assertEquals(50, f.controller.state.value.total)
            assertEquals(
                1L,
                f.controller.state.value.current
                    ?.id,
            )
            assertEquals(49, f.controller.state.value.pending)
            assertEquals(1, f.controller.state.value.skipped)
            f.controller.skip()
            runCurrent()
            assertEquals(
                11L,
                f.controller.state.value.current
                    ?.id,
            )
            // The evicted visible row is selectable again, but selection itself cannot be evicted.
            f.controller.readMessage(message(room, 10))
            runCurrent()
            assertEquals(50, f.controller.state.value.total)
            assertEquals(
                10L,
                f.controller.state.value.current
                    ?.id,
            )
            assertEquals(1, f.controller.state.value.position)
            f.controller.latest()
            runCurrent()
            assertEquals(
                59L,
                f.controller.state.value.current
                    ?.id,
            )
            val own = message(room, 60).copy(isSelf = true, msgid = null)
            f.controller.readMessage(own)
            runCurrent()
            assertEquals(50, f.controller.state.value.total)
            assertEquals(
                60L,
                f.controller.state.value.current
                    ?.id,
            )
            assertEquals(50, f.controller.state.value.position)
            f.controller.previous()
            runCurrent()
            assertEquals(
                59L,
                f.controller.state.value.current
                    ?.id,
            )
        }

    @Test fun selectingCoalescedVisibleWinnerReplaysCanonicalEntryWithoutGrowingOrEvictingQueue() =
        readerTest { db, room, f ->
            val store = CanonicalTimelineStore(db)
            val push =
                TimelineObservation(
                    networkId = room.networkId,
                    event = message(room, 0, "server variant").copy(msgid = "merged-reader", serverTime = 80_000),
                    origin = ObservationOrigin.PUSH,
                    connectionGeneration = 1,
                    batchId = null,
                    timeProvenance = TimeProvenance.SERVER_TAG,
                )
            val older = store.ingest(push).event
            val live =
                store
                    .ingest(
                        TimelineObservation(
                            networkId = room.networkId,
                            event = message(room, 0, "final presentation").copy(serverTime = 79_000, serverTimeAuthoritative = false),
                            origin = ObservationOrigin.LIVE,
                            connectionGeneration = 1,
                            batchId = null,
                            timeProvenance = TimeProvenance.LOCAL_CLOCK,
                        ),
                    ).event
            assertNotEquals(older.id, live.id)
            val next = message(room, 100, "next retained message").copy(serverTime = 90_000)
            db.canonicalTimelineDao().insertEvent(next)
            f.controller.onIncoming(live)
            f.controller.onIncoming(next)
            runCurrent()
            assertEquals(2, f.controller.state.value.total)
            assertEquals("trev says, final presentation", f.synth.utterances.last())
            val winner =
                store
                    .ingest(
                        TimelineObservation(
                            networkId = room.networkId,
                            event = push.event.copy(text = "final presentation"),
                            origin = ObservationOrigin.HISTORY,
                            connectionGeneration = 1,
                            batchId = "reader-history",
                            timeProvenance = TimeProvenance.SERVER_TAG,
                        ),
                    ).event
            assertEquals(older.id, winner.id)
            assertEquals(winner.id, db.canonicalTimelineDao().canonicalEventId(live.id))
            val visibleWinner = checkNotNull(db.messageDao().byCanonicalId(winner.id))
            assertNotEquals(live.text, visibleWinner.text)
            f.controller.readMessage(visibleWinner)
            runCurrent()
            assertEquals(
                winner.id,
                f.controller.state.value.current
                    ?.id,
            )
            assertEquals(2, f.controller.state.value.total)
            assertFalse(f.controller.state.value.canPrevious)
            assertEquals("trev says, ${visibleWinner.text}", f.synth.utterances.last())
            f.controller.readMessage(visibleWinner)
            runCurrent()
            assertEquals(2, f.controller.state.value.total)
            assertEquals(3, f.output.played.size)
            f.controller.latest()
            runCurrent()
            assertEquals(
                next.id,
                f.controller.state.value.current
                    ?.id,
            )
            f.controller.previous()
            runCurrent()
            assertEquals(
                winner.id,
                f.controller.state.value.current
                    ?.id,
            )
            assertEquals("trev says, ${visibleWinner.text}", f.synth.utterances.last())
        }

    @Test fun selectedHistoryUsesSettledSameTimeAnchorsWithoutReorderingRetainedNeighbors() =
        readerTest { db, room, f ->
            val first = message(room, 10, "first live").copy(serverTime = 1_000)
            val last = message(room, 11, "last live").copy(serverTime = 1_000)
            val history = message(room, 12, "selected history").copy(serverTime = 1_000)
            listOf(first, last, history).forEach { db.canonicalTimelineDao().insertEvent(it) }
            f.controller.onIncoming(first)
            f.controller.onIncoming(last)
            runCurrent()
            CanonicalTimelineStore(db).reconcilePlaybackOrder(
                orderedEventIds = listOf(first.id, history.id, last.id),
                insertedEventIds = setOf(history.id),
                prependUnanchored = false,
            )
            val visible = checkNotNull(db.messageDao().byCanonicalId(history.id))
            f.controller.readMessage(visible)
            runCurrent()
            assertEquals(2, f.controller.state.value.position)
            assertEquals(1, f.controller.state.value.pending)
            assertEquals("trev says, selected history", f.synth.utterances.last())
            f.controller.previous()
            runCurrent()
            assertEquals(
                first.id,
                f.controller.state.value.current
                    ?.id,
            )
            f.controller.latest()
            runCurrent()
            assertEquals(
                last.id,
                f.controller.state.value.current
                    ?.id,
            )
            f.controller.previous()
            runCurrent()
            assertEquals(
                history.id,
                f.controller.state.value.current
                    ?.id,
            )
            f.output.complete()
            runCurrent()
            advanceTimeBy(350)
            runCurrent()
            assertEquals(
                last.id,
                f.controller.state.value.current
                    ?.id,
            )
            assertEquals("trev says, last live", f.synth.utterances.last())
            assertEquals(3, f.controller.state.value.total)
        }

    @Test fun pendingSelectionLookupKeepsNewArrivalsAndCannotReviveAfterNavigation() {
        var holdQueries = false
        val queries = mutableListOf<Runnable>()
        val executor = Executor { query -> if (holdQueries) queries += query else query.run() }
        readerTest(queryExecutor = executor) { db, room, f ->
            val first = message(room, 1)
            val selected = message(room, 2)
            val arriving = message(room, 3)
            listOf(first, selected, arriving).forEach { db.canonicalTimelineDao().insertEvent(it) }
            f.controller.onIncoming(first)
            f.controller.onIncoming(selected)
            runCurrent()
            val initial = f.output.played.single()
            holdQueries = true
            f.controller.readMessage(selected)
            runCurrent()
            assertFalse(initial.exists())
            assertTrue(queries.isNotEmpty())
            assertEquals(1, f.output.played.size)
            f.controller.onIncoming(arriving)
            holdQueries = false
            val waiting = queries.toList()
            queries.clear()
            waiting.forEach(Runnable::run)
            runCurrent()
            assertEquals(
                selected.id,
                f.controller.state.value.current
                    ?.id,
            )
            assertEquals(3, f.controller.state.value.total)
            f.controller.latest()
            runCurrent()
            assertEquals(
                arriving.id,
                f.controller.state.value.current
                    ?.id,
            )
            val spoken = f.synth.utterances.toList()
            holdQueries = true
            f.controller.readMessage(first)
            runCurrent()
            f.selected.set(null)
            runCurrent()
            holdQueries = false
            val cancelled = queries.toList()
            queries.clear()
            cancelled.forEach(Runnable::run)
            runCurrent()
            assertFalse(f.controller.state.value.enabled)
            assertEquals(0, f.controller.state.value.total)
            assertEquals(spoken, f.synth.utterances)
            assertTrue(f.output.played.none { it.exists() })
        }
    }

    @Test fun tiedManualRowsUseTimelineOrderThenCanonicalIdWithoutReorderingIncomingQueue() =
        readerTest { _, room, f ->
            val first = message(room, 30).copy(serverTime = 100, timelineOrder = 1)
            val last = message(room, 40).copy(serverTime = 100, timelineOrder = 3)
            f.controller.onIncoming(first)
            f.controller.onIncoming(last)
            runCurrent()
            f.controller.readMessage(message(room, 20).copy(serverTime = 100, timelineOrder = 2))
            runCurrent()
            f.controller.readMessage(message(room, 10).copy(serverTime = 100, timelineOrder = 2))
            runCurrent()
            assertEquals(2, f.controller.state.value.position)
            f.controller.skip()
            runCurrent()
            assertEquals(
                20L,
                f.controller.state.value.current
                    ?.id,
            )
            f.controller.skip()
            runCurrent()
            assertEquals(
                40L,
                f.controller.state.value.current
                    ?.id,
            )
        }

    @Test fun rapidSelectionsDrainLateWriterAndNavigationCannotReviveReplacement() =
        readerTest { _, room, f ->
            var release = CompletableDeferred<Unit>()
            var stale: java.io.File? = null
            f.synth.synthesize = { _, file ->
                if (stale == null) {
                    stale = file
                    withContext(NonCancellable) {
                        release.await()
                        file.writeText("late audio")
                    }
                } else {
                    file.writeText("current audio")
                }
            }
            try {
                f.controller.readMessage(message(room, 1))
                runCurrent()
                f.controller.readMessage(message(room, 2))
                f.controller.readMessage(message(room, 3))
                runCurrent()
                assertEquals(listOf("trev says, message 1"), f.synth.utterances)
                assertTrue(f.output.played.isEmpty())
                release.complete(Unit)
                runCurrent()
                assertFalse(checkNotNull(stale).exists())
                assertEquals(listOf("trev says, message 1", "trev says, message 3"), f.synth.utterances)
                assertEquals(
                    "current audio",
                    f.output.played
                        .single()
                        .readText(),
                )
                val playing = f.output.played.single()
                release = CompletableDeferred()
                stale = null
                f.controller.readMessage(message(room, 1))
                runCurrent()
                assertFalse(playing.exists())
                f.controller.readMessage(message(room, 2))
                f.controller.readMessage(message(room, 3))
                f.selected.set(null)
                runCurrent()
                release.complete(Unit)
                runCurrent()
                assertFalse(checkNotNull(stale).exists())
                assertFalse(f.controller.state.value.enabled)
                assertEquals(0, f.controller.state.value.total)
                assertEquals(1, f.output.played.size)
                assertEquals("trev says, message 1", f.synth.utterances.last())
            } finally {
                release.complete(Unit)
            }
        }

    @Test fun selectionCannotOptInOrBypassRoomSenderAndAudioPolicy() =
        readerTest { db, room, f ->
            val selected = message(room, 1)
            f.controller.readMessage(selected.copy(bufferId = room.id + 1))
            f.controller.readMessage(selected.copy(kind = MessageKind.JOIN))
            f.controller.readMessage(selected.copy(text = "\u0001 \n"))
            f.social.value = Settings(fools = setOf("trev"))
            runCurrent()
            f.controller.readMessage(selected)
            runCurrent()
            assertEquals(0, f.controller.state.value.total)
            assertTrue(f.synth.utterances.isEmpty())
            f.social.value = Settings()
            runCurrent()
            f.controller.stop()
            f.controller.readMessage(selected)
            assertFalse(f.controller.state.value.enabled)
            assertEquals(0, f.controller.state.value.total)
            f.controller.preview(room.id, f.controller.config.value)
            runCurrent()
            f.controller.readMessage(selected)
            runCurrent()
            assertTrue(f.controller.state.value.previewing)
            assertFalse(f.controller.state.value.enabled)
            assertEquals(listOf("Hello. This is your selected reading voice."), f.synth.utterances)
            f.controller.stopPreview()
            runCurrent()
            f.controller.setEnabled(room.id, true)
            runCurrent()
            // Change foreground before the stopping collector executes.
            f.selected.set(room.id + 1)
            f.controller.readMessage(selected)
            runCurrent()
            assertFalse(f.controller.state.value.enabled)
            f.selected.set(room.id)
            runCurrent()
            f.controller.setEnabled(room.id, true)
            runCurrent()
            f.activity.setRecording(true)
            f.controller.readMessage(selected)
            runCurrent()
            assertFalse(f.controller.state.value.enabled)
            f.activity.setRecording(false)
            runCurrent()
            f.controller.setEnabled(room.id, true)
            runCurrent()
            f.audio.state.value = AudioPlaybackState(loading = true)
            f.controller.readMessage(selected)
            runCurrent()
            assertFalse(f.controller.state.value.enabled)
            f.audio.state.value = AudioPlaybackState()
            runCurrent()
            f.controller.setEnabled(room.id, true)
            runCurrent()
            db.bufferDao().update(room.copy(muted = true))
            f.controller.readMessage(selected)
            runCurrent()
            assertFalse(f.controller.state.value.enabled)
            assertEquals(listOf("Hello. This is your selected reading voice."), f.synth.utterances)
            assertTrue(f.output.played.none { it.exists() })
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

    @Test fun settingsNeverStartReadingAndStaleSheetCannotSaveOrPreview() =
        readerTest { _, room, f ->
            f.controller.stop()
            val old = f.controller.config.value
            f.prefs.replaceSystem(ReadAloudConfig(voice = "named-offline", rate = 1.2f, pitch = .9f, gapMs = 650))
            f.controller.config.first { it.options.voice == "named-offline" }
            assertTrue(runCatching { f.prefs.replaceSystem(old.options, old.savedOptions) }.exceptionOrNull() is IllegalStateException)
            f.controller.preview(room.id, old)
            f.controller.saveVoiceOptions(old)
            runCurrent()
            assertFalse(f.controller.state.value.enabled)
            assertFalse(f.controller.state.value.previewing)
            assertTrue(f.synth.utterances.isEmpty())
            assertEquals("named-offline", f.controller.config.value.options.voice)
        }

    @Test fun roomlessSettingsPreviewStopsForBackgroundAndMediaWithoutOptIn() =
        readerTest { _, _, f ->
            f.controller.stop()
            f.selected.set(null)
            val selection = f.controller.config.value
            f.controller.preview(null, selection)
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
            f.controller.preview(null, selection)
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
