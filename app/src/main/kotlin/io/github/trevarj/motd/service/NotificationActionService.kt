package io.github.trevarj.motd.service

import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.ui.chat.ComposerDraftStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Started service for reply, retry, and mark-read notification actions. A RemoteInput reply is forwarded to
 * [ConnectionManager.sendNotificationReply]; the mark-read action to [ConnectionManager.markRead].
 */
@AndroidEntryPoint
class NotificationActionService : LifecycleService() {
    @Inject lateinit var connectionManager: ConnectionManager

    @Inject lateinit var diagnostics: DiagnosticLogger

    @Inject lateinit var notifications: MotdNotifications

    @Inject lateinit var draftStore: ComposerDraftStore

    private var latestStartId = 0
    private var inFlight = 0

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        latestStartId = startId
        inFlight++
        lifecycleScope.launch {
            try {
                if (intent != null) dispatch(intent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "notification action failed: ${failure.javaClass.simpleName}")
            } finally {
                // Both the starts and their completions are confined to the main thread.
                if (--inFlight == 0) stopSelf(latestStartId)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun dispatch(intent: Intent) {
        val bufferId = intent.getLongExtra(EXTRA_BUFFER_ID, -1L)
        if (bufferId < 0) return
        when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
                if (text.isNullOrBlank()) return
                send(bufferId, text, retry = false)
            }

            ACTION_RETRY_REPLY -> {
                val text = intent.getStringExtra(EXTRA_REPLY_TEXT)
                if (text.isNullOrBlank()) return
                send(bufferId, text, retry = true)
            }

            ACTION_MARK_READ -> {
                if (!intent.hasExtra(EXTRA_UP_TO_TIME)) return
                val upTo = intent.getLongExtra(EXTRA_UP_TO_TIME, 0L)
                val eventId = intent.getLongExtra(EXTRA_UP_TO_EVENT_ID, 0L)
                connectionManager.markRead(bufferId, TimelineAnchor(upTo, eventId))
            }

            else -> {}
        }
    }

    private suspend fun send(
        bufferId: Long,
        text: String,
        retry: Boolean,
    ) = deliverNotificationReply(
        retry = retry,
        send = { connectionManager.sendNotificationReply(bufferId, text) },
        preserveDraft = { preserveRejectedDraft(bufferId, text) },
        releaseDraft = { clearRetriedDraft(bufferId, text) },
        notifyFailed = { reason ->
            Log.w(TAG, "notification reply rejected: $reason")
            diagnostics.record("notification_reply", "send_rejected") {
                mapOf("buffer_id" to bufferId, "reason" to reason.name, "retry" to retry)
            }
            notifications.onReplyFailed(bufferId, text, reason)
        },
        notifyUncertain = { wireAcceptance ->
            Log.w(TAG, "notification reply delivery uncertain: $wireAcceptance")
            diagnostics.record("notification_reply", "send_uncertain") {
                mapOf("buffer_id" to bufferId, "wire_acceptance" to wireAcceptance.name, "retry" to retry)
            }
            notifications.onReplyDeliveryUncertain(bufferId, text)
        },
        notifyResolved = { notifications.onReplyFailureResolved(bufferId) },
    )

    /** Never clobber an in-progress composer draft; the unsent text is appended to it. */
    private suspend fun preserveRejectedDraft(
        bufferId: Long,
        text: String,
    ) {
        try {
            draftStore.appendNotificationReply(bufferId, text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "preserving notification reply failed: ${failure.javaClass.simpleName}")
        }
    }

    /** Drop the preserved copy once the retry is durable, unless the user has since edited the draft. */
    private suspend fun clearRetriedDraft(
        bufferId: Long,
        text: String,
    ) {
        try {
            draftStore.removeNotificationReply(bufferId, text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "clearing retried reply draft failed: ${failure.javaClass.simpleName}")
        }
    }

    companion object {
        const val ACTION_REPLY = "io.github.trevarj.motd.service.REPLY"
        const val ACTION_RETRY_REPLY = "io.github.trevarj.motd.service.RETRY_REPLY"
        const val ACTION_MARK_READ = "io.github.trevarj.motd.service.MARK_READ"
        const val EXTRA_BUFFER_ID = "bufferId"
        const val EXTRA_REPLY_TEXT = "replyText"
        const val EXTRA_UP_TO_TIME = "upToTime"
        const val EXTRA_UP_TO_EVENT_ID = "upToEventId"
        const val KEY_REPLY = "key_reply"
        private const val TAG = "NotificationAction"
    }
}

/** Preserve rejected text only; accepted rows own the text even while their wire outcome is pending. */
internal suspend fun deliverNotificationReply(
    retry: Boolean,
    send: suspend () -> SendAcceptance,
    preserveDraft: suspend () -> Unit,
    releaseDraft: suspend () -> Unit,
    notifyFailed: suspend (SendRejectionReason) -> Unit,
    notifyUncertain: suspend (ImmediateWireAcceptance) -> Unit,
    notifyResolved: suspend () -> Unit,
) {
    when (val acceptance = send()) {
        is SendAcceptance.Accepted -> {
            withContext(NonCancellable) {
                if (retry) releaseDraft()
                when (val outcome = acceptance.wireOutcome.await()) {
                    ImmediateWireAcceptance.ACCEPTED -> {
                        if (retry) {
                            notifyResolved()
                        }
                    }

                    ImmediateWireAcceptance.DISCONNECTED, ImmediateWireAcceptance.FAILED -> {
                        notifyUncertain(outcome)
                    }
                }
            }
        }

        is SendAcceptance.Rejected -> {
            // The retry path already preserved the text on the original rejection.
            if (!retry) preserveDraft()
            notifyFailed(acceptance.reason)
        }
    }
}
