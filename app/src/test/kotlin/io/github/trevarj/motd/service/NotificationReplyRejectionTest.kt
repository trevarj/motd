package io.github.trevarj.motd.service

import io.github.trevarj.motd.ui.chat.mergeRejectedReply
import io.github.trevarj.motd.ui.chat.withoutRetriedReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Notification replies preserve rejected/uncertain text without treating durable failure as success. */
class NotificationReplyRejectionTest {
    private class Recorder {
        var preserved = 0
        var released = 0
        var failedWith: SendRejectionReason? = null
        var uncertainWith: ImmediateWireAcceptance? = null
        var resolved = 0
    }

    private suspend fun deliver(
        acceptance: SendAcceptance,
        retry: Boolean,
        recorder: Recorder,
    ) = deliverNotificationReply(
        retry = retry,
        send = { acceptance },
        preserveDraft = { recorder.preserved++ },
        releaseDraft = { recorder.released++ },
        notifyFailed = { reason -> recorder.failedWith = reason },
        notifyUncertain = { recorder.uncertainWith = it },
        notifyResolved = { recorder.resolved++ },
    )

    private val accepted = SendAcceptance.Accepted(eventIds = listOf(7L))

    @Test
    fun unavailableConnectionPreservesTheReplyAndSurfacesTheFailure() =
        runTest {
            val recorder = Recorder()

            deliver(SendAcceptance.Rejected(SendRejectionReason.CONNECTION_UNAVAILABLE), retry = false, recorder)

            assertEquals(1, recorder.preserved)
            assertEquals(SendRejectionReason.CONNECTION_UNAVAILABLE, recorder.failedWith)
            assertEquals(0, recorder.released)
            assertEquals(0, recorder.resolved)
            assertNull(recorder.uncertainWith)
        }

    @Test
    fun acceptedReplyTouchesNothing() =
        runTest {
            val recorder = Recorder()

            deliver(accepted, retry = false, recorder)

            assertEquals(0, recorder.preserved)
            assertEquals(0, recorder.released)
            assertEquals(0, recorder.resolved)
            assertNull(recorder.failedWith)
            assertNull(recorder.uncertainWith)
        }

    @Test
    fun acceptedRetryReleasesThePreservedDraftAndRetiresTheNotice() =
        runTest {
            val recorder = Recorder()

            deliver(accepted, retry = true, recorder)

            assertEquals(1, recorder.released)
            assertEquals(1, recorder.resolved)
            assertEquals(0, recorder.preserved)
            assertNull(recorder.failedWith)
            assertNull(recorder.uncertainWith)
        }

    @Test
    fun rejectedRetryReportsAgainWithoutDuplicatingThePreservedDraft() =
        runTest {
            val recorder = Recorder()

            deliver(SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND), retry = true, recorder)

            assertEquals(0, recorder.preserved)
            assertEquals(SendRejectionReason.BUFFER_NOT_FOUND, recorder.failedWith)
            assertEquals(0, recorder.resolved)
            assertNull(recorder.uncertainWith)
        }

    @Test
    fun durableDisconnectedReplyRequiresReviewWithoutRestoringDraft() =
        runTest {
            val recorder = Recorder()

            deliver(accepted.copy(wireOutcome = CompletableDeferred(ImmediateWireAcceptance.DISCONNECTED)), retry = false, recorder)

            assertEquals(0, recorder.preserved)
            assertEquals(ImmediateWireAcceptance.DISCONNECTED, recorder.uncertainWith)
            assertEquals(0, recorder.released)
            assertEquals(0, recorder.resolved)
            assertNull(recorder.failedWith)
        }

    @Test
    fun durableFailedReplyRequiresReviewWithoutRestoringDraft() =
        runTest {
            val recorder = Recorder()

            deliver(accepted.copy(wireOutcome = CompletableDeferred(ImmediateWireAcceptance.FAILED)), retry = false, recorder)

            assertEquals(0, recorder.preserved)
            assertEquals(ImmediateWireAcceptance.FAILED, recorder.uncertainWith)
            assertEquals(0, recorder.released)
            assertEquals(0, recorder.resolved)
            assertNull(recorder.failedWith)
        }

    @Test
    fun uncertainManualRetryDoesNotAppendAgainOrResolveTheFailure() =
        runTest {
            val recorder = Recorder()

            deliver(SendAcceptance.Rejected(SendRejectionReason.CONNECTION_UNAVAILABLE), retry = false, recorder)
            for ((index, wireAcceptance) in listOf(ImmediateWireAcceptance.DISCONNECTED, ImmediateWireAcceptance.FAILED).withIndex()) {
                deliver(accepted.copy(wireOutcome = CompletableDeferred(wireAcceptance)), retry = true, recorder)
                assertEquals(1, recorder.preserved)
                assertEquals(index + 1, recorder.released)
                assertEquals(0, recorder.resolved)
                assertEquals(wireAcceptance, recorder.uncertainWith)
            }
        }

    @Test
    fun acceptedRetryReleasesDraftBeforeWireOutcomeWithoutRestoringItOnFailure() =
        runTest {
            val outcome = CompletableDeferred<ImmediateWireAcceptance>()
            val released = CompletableDeferred<Unit>()
            val recorder = Recorder()
            val delivery =
                launch {
                    deliverNotificationReply(
                        retry = true,
                        send = { accepted.copy(wireOutcome = outcome) },
                        preserveDraft = { recorder.preserved++ },
                        releaseDraft = {
                            recorder.released++
                            released.complete(Unit)
                        },
                        notifyFailed = { recorder.failedWith = it },
                        notifyUncertain = { recorder.uncertainWith = it },
                        notifyResolved = { recorder.resolved++ },
                    )
                }
            released.await()
            assertEquals(1, recorder.released)
            assertEquals(0, recorder.preserved)
            outcome.complete(ImmediateWireAcceptance.FAILED)
            delivery.join()
            assertEquals(0, recorder.preserved)
            assertEquals(ImmediateWireAcceptance.FAILED, recorder.uncertainWith)
            assertEquals(0, recorder.resolved)
        }

    @Test
    fun cancellationDuringPreparationDoesNotPreserveOrReportAnUnsubmittedReply() =
        runTest {
            val preparing = CompletableDeferred<Unit>()
            val recorder = Recorder()
            val delivery =
                launch {
                    deliverNotificationReply(
                        retry = false,
                        send = {
                            preparing.complete(Unit)
                            awaitCancellation()
                        },
                        preserveDraft = { recorder.preserved++ },
                        releaseDraft = { recorder.released++ },
                        notifyFailed = { recorder.failedWith = it },
                        notifyUncertain = { recorder.uncertainWith = it },
                        notifyResolved = { recorder.resolved++ },
                    )
                }
            preparing.await()
            delivery.cancel()
            delivery.join()

            assertEquals(0, recorder.preserved)
            assertEquals(0, recorder.released)
            assertEquals(0, recorder.resolved)
            assertNull(recorder.failedWith)
            assertNull(recorder.uncertainWith)
        }

    @Test
    fun preservedTextIsAppendedToAnInProgressDraft() {
        assertEquals("rejected", mergeRejectedReply(null, "rejected"))
        assertEquals("rejected", mergeRejectedReply("", "rejected"))
        assertEquals("rejected", mergeRejectedReply("   ", "rejected"))
        assertEquals("typing\nrejected", mergeRejectedReply("typing", "rejected"))
    }

    @Test
    fun retriedTextIsRemovedOnlyWhenTheDraftStillHoldsIt() {
        assertEquals("", withoutRetriedReply("rejected", "rejected"))
        assertEquals("typing", withoutRetriedReply("typing\nrejected", "rejected"))
        // Edited or replaced by the user: leave the draft alone.
        assertNull(withoutRetriedReply("typing\nrejected and more", "rejected"))
        assertNull(withoutRetriedReply("something else", "rejected"))
        assertNull(withoutRetriedReply(null, "rejected"))
    }
}
