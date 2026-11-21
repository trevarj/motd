package io.github.trevarj.motd.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.SCHEDULE_OVERDUE_MS
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScheduledWakeupTest {
    @Test
    fun timedWakeSurvivesRestartRequiresNetworkAndFollowsChangedDeadline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val wakes = ScheduledWakeup(context, DiagnosticLogger.Noop)
        val now = 100_000L
        val first = now + 120_000L
        wakes.plan(first, now)
        val job = scheduler.allPendingJobs.single()
        assertEquals(120_000L, job.minLatencyMillis)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertEquals(0L, job.maxExecutionDelayMillis)
        assertTrue(job.isPersisted)

        val earlier = now + 30_000L
        wakes.plan(earlier, now)
        assertEquals(30_000L, scheduler.allPendingJobs.single().minLatencyMillis)
        wakes.invalidateDelay()
        wakes.plan(earlier, now + 40_000L)
        assertEquals(0L, scheduler.allPendingJobs.single().minLatencyMillis)
        wakes.plan(null, now + 40_000L)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }

    @Test
    fun earlierFutureMessageGetsItsOwnPersistedWakeBeforeTheCurrentJobFinishes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val wakes = ScheduledWakeup(context, DiagnosticLogger.Noop)
        val now = 100_000L
        val overdueByOneSecond = now - 1_000L
        wakes.plan(null, now, firstDueAt = overdueByOneSecond)
        val running = scheduler.allPendingJobs.single()
        val token = Any()
        wakes.begin(running.id, token)

        assertTrue(wakes.plan(now + 30_000L, now, firstDueAt = overdueByOneSecond, finishingWake = true))
        val successor = scheduler.allPendingJobs.single { it.id != running.id }
        assertTrue(successor.isPersisted)
        assertEquals(30_000L, successor.minLatencyMillis)
        assertTrue(scheduler.allPendingJobs.any { it.id == running.id })
    }

    @Test
    fun timedMessagesCanSendOnlyWithinTheDayAfterTheirScheduledInstant() {
        val due = 1_000_000L
        assertEquals(ScheduledTimeState.FUTURE, scheduledTimeState(due, due - 1L))
        assertEquals(ScheduledTimeState.ELIGIBLE, scheduledTimeState(due, due))
        assertEquals(ScheduledTimeState.ELIGIBLE, scheduledTimeState(due, due + SCHEDULE_OVERDUE_MS - 1L))
        assertEquals(ScheduledTimeState.OVERDUE, scheduledTimeState(due, due + SCHEDULE_OVERDUE_MS))
        assertEquals(ScheduledTimeState.ELIGIBLE, scheduledTimeState(null, Long.MAX_VALUE))
    }
}
