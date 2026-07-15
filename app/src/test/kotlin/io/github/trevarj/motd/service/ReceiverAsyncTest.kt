package io.github.trevarj.motd.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverAsyncTest {
    @Test
    fun `successful work finishes exactly once`() = runTest {
        var finishes = 0
        runBoundedReceiverWork(1_000, {}, {}, { finishes++ }) {}
        assertEquals(1, finishes)
    }

    @Test
    fun `failure is reported and finishes exactly once`() = runTest {
        val failures = mutableListOf<Throwable>()
        var finishes = 0
        runBoundedReceiverWork(1_000, {}, failures::add, { finishes++ }) {
            error("boom")
        }
        assertTrue(failures.single() is IllegalStateException)
        assertEquals(1, finishes)
    }

    @Test
    fun `timeout cancels work and finishes exactly once under virtual time`() = runTest {
        var timedOut = 0
        var finishes = 0
        val job = launch {
            runBoundedReceiverWork(1_000, { timedOut++ }, {}, { finishes++ }) {
                delay(10_000)
            }
        }
        advanceTimeBy(1_000)
        runCurrent()
        job.join()
        assertEquals(1, timedOut)
        assertEquals(1, finishes)
    }

    @Test
    fun `parent cancellation still finishes and propagates`() = runTest {
        var finishes = 0
        var propagated = false
        val job = launch {
            try {
                runBoundedReceiverWork(10_000, {}, {}, { finishes++ }) { awaitCancellation() }
            } catch (cancelled: CancellationException) {
                propagated = true
                throw cancelled
            }
        }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(1, finishes)
        assertTrue(propagated)
    }
}
