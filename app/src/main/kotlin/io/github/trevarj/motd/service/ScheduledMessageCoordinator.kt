package io.github.trevarj.motd.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.SCHEDULE_OVERDUE_MS
import io.github.trevarj.motd.data.db.ScheduledMessageEntity
import io.github.trevarj.motd.data.db.ScheduledMessageState
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private val SCHEDULED_SEND_JOB_IDS = intArrayOf(29103, 29104)
private const val WAKE_AT = "wake_at"
private const val RETRY_AT = "retry_at"
private const val RECONNECT_WINDOW_MS = 20_000L
private const val BACKGROUND_RETRY_MS = 60_000L
private const val TIMED_RECONNECT_RETRY_MS = 300_000L

internal enum class ScheduledTimeState { FUTURE, ELIGIBLE, OVERDUE }

internal fun scheduledTimeState(
    dueAt: Long?,
    now: Long,
): ScheduledTimeState =
    when {
        dueAt == null -> ScheduledTimeState.ELIGIBLE
        now < dueAt -> ScheduledTimeState.FUTURE
        now - dueAt >= SCHEDULE_OVERDUE_MS -> ScheduledTimeState.OVERDUE
        else -> ScheduledTimeState.ELIGIBLE
    }

@Singleton
class ScheduledMessageCoordinator
    @Inject
    constructor(
        @ApplicationContext context: Context,
        db: MotdDatabase,
        private val connections: ConnectionManagerImpl,
        private val clock: AppClock,
        private val diagnostics: DiagnosticLogger,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val dao = db.scheduledMessageDao()
        private val changes = Channel<Unit>(Channel.CONFLATED)
        private val dispatchLock = Mutex()
        private val wakes = ScheduledWakeup(context, diagnostics)
        private val recoveryLock = Mutex()
        private val timedWakeLock = Mutex()
        private var recovered = false
        private val connectionChanges = connections.scheduledConnectionChanges.onStart { emit(Unit) }

        @OptIn(ExperimentalCoroutinesApi::class)
        internal fun start() {
            scope.launch {
                dao
                    .observeAll()
                    .onEach { changes.trySend(Unit) }
                    .map { messages -> messages.any { it.state == ScheduledMessageState.QUEUED } }
                    .distinctUntilChanged()
                    .flatMapLatest { queued -> if (queued) connectionChanges else emptyFlow() }
                    .collect { changes.trySend(Unit) }
            }
            scope.launch {
                while (true) {
                    val nextCheck =
                        try {
                            recoverInterrupted()
                            dao.all().forEach { process(it) }
                            planNextCheck()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            reportScheduleError(diagnostics, "queue_scan_failed", error)
                            clock.nowMillis() + BACKGROUND_RETRY_MS
                        }
                    if (nextCheck == null) {
                        changes.receive()
                    } else {
                        withTimeoutOrNull((nextCheck - clock.nowMillis()).coerceAtLeast(1L)) { changes.receive() }
                    }
                }
            }
        }

        private suspend fun recoverInterrupted() {
            recoveryLock.withLock {
                if (!recovered) {
                    dao.recoverInterrupted()
                    recovered = true
                }
            }
        }

        internal suspend fun clockChanged() {
            wakes.invalidateDelay()
            try {
                planNextCheck()
            } finally {
                changes.trySend(Unit)
            }
        }

        private suspend fun planNextCheck(attempted: Set<Long>? = null): Long? {
            var nextCheck = Long.MAX_VALUE
            var nextFuture = Long.MAX_VALUE
            var firstDue = Long.MAX_VALUE
            val now = clock.nowMillis()
            for (message in dao.all()) {
                val due = message.dueAt ?: continue
                if (message.state != ScheduledMessageState.QUEUED) continue
                val checkAt = if (due > now) due else due + SCHEDULE_OVERDUE_MS
                nextCheck = minOf(nextCheck, checkAt)
                if (scheduledTimeState(due, now) == ScheduledTimeState.OVERDUE) continue
                when (connections.scheduledReadiness(message)) {
                    ScheduledReadiness.READY, ScheduledReadiness.WAITING -> {
                        if (due > now || (attempted != null && message.id !in attempted)) {
                            nextFuture = minOf(nextFuture, due)
                        } else {
                            firstDue = minOf(firstDue, due)
                        }
                    }

                    ScheduledReadiness.PAUSED, ScheduledReadiness.UNAVAILABLE -> {
                        Unit
                    }
                }
            }
            val planned =
                wakes.plan(
                    nextFuture.takeUnless { it == Long.MAX_VALUE },
                    now,
                    firstDue.takeUnless { it == Long.MAX_VALUE },
                    finishingWake = attempted != null,
                )
            if (attempted != null) check(planned) { "Could not persist the next scheduled wake" }
            if (!planned) nextCheck = minOf(nextCheck, now + BACKGROUND_RETRY_MS)
            return nextCheck.takeUnless { it == Long.MAX_VALUE }
        }

        private suspend fun process(message: ScheduledMessageEntity) {
            if (message.state != ScheduledMessageState.QUEUED) return
            dispatchLock.withLock {
                val now = clock.nowMillis()
                val time = scheduledTimeState(message.dueAt, now)
                if (time == ScheduledTimeState.OVERDUE) {
                    markQueued(message, ScheduledMessageState.OVERDUE)
                    return
                }
                when (connections.scheduledReadiness(message)) {
                    ScheduledReadiness.UNAVAILABLE -> markQueued(message, ScheduledMessageState.UNAVAILABLE)
                    ScheduledReadiness.READY -> if (time == ScheduledTimeState.ELIGIBLE) dispatch(message)
                    ScheduledReadiness.WAITING, ScheduledReadiness.PAUSED -> Unit
                }
            }
        }

        private suspend fun markQueued(
            message: ScheduledMessageEntity,
            state: ScheduledMessageState,
        ) {
            dao.transition(message.id, message.revision, ScheduledMessageState.QUEUED, state)
        }

        private suspend fun dispatch(message: ScheduledMessageEntity) {
            var claimed = false
            var result = ScheduledDispatch.UNCERTAIN
            try {
                withContext(NonCancellable) {
                    claimed = dao.transition(message.id, message.revision, ScheduledMessageState.QUEUED, ScheduledMessageState.SENDING) != 0
                }
                if (claimed) result = connections.sendScheduled(message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportScheduleError(diagnostics, "dispatch_failed", error)
            } finally {
                if (claimed) withContext(NonCancellable) { finishDispatch(message, result) }
            }
        }

        private suspend fun finishDispatch(
            message: ScheduledMessageEntity,
            result: ScheduledDispatch,
        ) {
            val state =
                when (result) {
                    ScheduledDispatch.SENT -> null
                    ScheduledDispatch.WAITING -> ScheduledMessageState.QUEUED
                    ScheduledDispatch.OVERDUE -> ScheduledMessageState.OVERDUE
                    ScheduledDispatch.UNAVAILABLE -> ScheduledMessageState.UNAVAILABLE
                    ScheduledDispatch.UNCERTAIN -> ScheduledMessageState.UNCERTAIN
                }
            if (state == null) {
                dao.remove(message.id, message.revision, ScheduledMessageState.SENDING)
            } else {
                dao.transition(message.id, message.revision, ScheduledMessageState.SENDING, state)
            }
        }

        internal fun beginWake(parameters: JobParameters) = wakes.begin(parameters.jobId, parameters)

        internal fun endWake(parameters: JobParameters) {
            wakes.end(parameters.jobId, parameters)
            changes.trySend(Unit)
        }

        internal suspend fun runTimedWake(): Unit =
            timedWakeLock.withLock {
                recoverInterrupted()
                val dueAtStart = timedDue(dao.all())
                val networkIds = dueAtStart.mapTo(linkedSetOf()) { it.networkId }
                for (networkId in networkIds) {
                    connections.withScheduledConnection(networkId) {
                        withTimeoutOrNull(RECONNECT_WINDOW_MS) {
                            combine(dao.observeAll(), connectionChanges) { rows, _ -> rows }
                                .first { rows ->
                                    val due = timedDue(rows).filter { it.networkId == networkId }
                                    due.forEach { process(it) }
                                    timedDue(dao.all()).none {
                                        it.networkId == networkId && connections.scheduledReadiness(it) == ScheduledReadiness.WAITING
                                    }
                                }
                        }
                    }
                }
                planNextCheck(attempted = dueAtStart.mapTo(hashSetOf()) { it.id })
            }

        private fun timedDue(messages: List<ScheduledMessageEntity>): List<ScheduledMessageEntity> {
            val now = clock.nowMillis()
            return messages.filter {
                it.state == ScheduledMessageState.QUEUED && it.dueAt != null &&
                    scheduledTimeState(it.dueAt, now) == ScheduledTimeState.ELIGIBLE
            }
        }
    }

internal class ScheduledWakeup(
    context: Context,
    private val diagnostics: DiagnosticLogger,
) {
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val service = ComponentName(context, ScheduledMessageJobService::class.java)
    private val running = mutableMapOf<Int, Any>()
    private var delayInvalidated = false

    @Synchronized
    fun invalidateDelay() {
        delayInvalidated = true
    }

    @Synchronized
    fun begin(
        id: Int,
        token: Any,
    ) {
        running[id] = token
    }

    @Synchronized
    fun end(
        id: Int,
        token: Any,
    ) {
        if (running[id] === token) running.remove(id)
    }

    @Synchronized
    fun plan(
        futureAt: Long?,
        now: Long,
        firstDueAt: Long? = null,
        finishingWake: Boolean = false,
    ): Boolean {
        if (!finishingWake && running.isNotEmpty()) return true
        val available = SCHEDULED_SEND_JOB_IDS.filterNot(running::containsKey)
        val pending = available.mapNotNull(scheduler::getPendingJob)
        val retryAt =
            when {
                firstDueAt == null -> null
                finishingWake -> now + TIMED_RECONNECT_RETRY_MS
                delayInvalidated -> null
                else -> pending.firstNotNullOfOrNull { it.extras.getLong(RETRY_AT).takeIf { time -> time != 0L } }
            }
        val dueWake = retryAt ?: firstDueAt
        val at = if (futureAt == null) dueWake else dueWake?.let { minOf(futureAt, it) } ?: futureAt
        if (at == null) {
            pending.forEach { scheduler.cancel(it.id) }
        } else {
            val existing = pending.firstOrNull { it.extras.getLong(WAKE_AT) == at && it.extras.getLong(RETRY_AT) == (retryAt ?: 0L) }
            val id = existing?.id ?: pending.firstOrNull()?.id ?: available.firstOrNull() ?: return false
            if (existing == null || delayInvalidated) {
                val job =
                    JobInfo
                        .Builder(id, service)
                        .setPersisted(true)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setMinimumLatency((at - now).coerceAtLeast(0L))
                        .setBackoffCriteria(BACKGROUND_RETRY_MS, JobInfo.BACKOFF_POLICY_LINEAR)
                        .setExtras(
                            PersistableBundle().apply {
                                putLong(WAKE_AT, at)
                                putLong(RETRY_AT, retryAt ?: 0L)
                            },
                        ).build()
                if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                    diagnostics.record("scheduled_send", "wake_rejected") { emptyMap() }
                    return false
                }
            }
            pending.forEach { if (it.id != id) scheduler.cancel(it.id) }
        }
        delayInvalidated = false
        return true
    }
}

@AndroidEntryPoint
class ScheduledMessageJobService : JobService() {
    @Inject lateinit var coordinator: ScheduledMessageCoordinator

    @Inject lateinit var diagnostics: DiagnosticLogger

    @ApplicationScope
    @Inject
    lateinit var scope: CoroutineScope

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workers = mutableMapOf<Int, Pair<JobParameters, Job>>()

    override fun onStartJob(params: JobParameters): Boolean {
        coordinator.beginWake(params)
        val worker =
            scope.launch {
                val retry =
                    try {
                        coordinator.runTimedWake()
                        false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        reportScheduleError(diagnostics, "wake_failed", error)
                        true
                    }
                mainHandler.post {
                    if (workers[params.jobId]?.first === params) {
                        workers.remove(params.jobId)
                        jobFinished(params, retry)
                        mainHandler.post { coordinator.endWake(params) }
                    }
                }
            }
        workers[params.jobId] = params to worker
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        workers.remove(params.jobId)?.let { (started, worker) ->
            worker.cancel()
            mainHandler.post { coordinator.endWake(started) }
        }
        return true
    }
}

@AndroidEntryPoint
class ScheduledMessageClockReceiver : BroadcastReceiver() {
    @Inject lateinit var coordinator: ScheduledMessageCoordinator

    @Inject lateinit var diagnostics: DiagnosticLogger

    @ApplicationScope
    @Inject
    lateinit var scope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_TIME_CHANGED) return
        val pending = goAsync()
        scope.launch {
            try {
                coordinator.clockChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportScheduleError(diagnostics, "clock_replan_failed", error)
            } finally {
                pending.finish()
            }
        }
    }
}

private fun reportScheduleError(
    diagnostics: DiagnosticLogger,
    event: String,
    error: Exception,
) {
    diagnostics.record("scheduled_send", event) { mapOf("error" to error::class.simpleName) }
}
