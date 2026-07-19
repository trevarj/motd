package io.github.trevarj.motd.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.di.ApplicationScope
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/**
 * Direct-reply / mark-read notification actions (plans/05). The RemoteInput reply is forwarded to
 * [ConnectionManager.sendMessage]; the mark-read action to [ConnectionManager.markRead].
 */
@AndroidEntryPoint
class ReplyReceiver : BroadcastReceiver() {

    @Inject lateinit var connectionManager: ConnectionManager
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope
    @Inject lateinit var diagnostics: DiagnosticLogger

    override fun onReceive(context: Context, intent: Intent) {
        val bufferId = intent.getLongExtra(EXTRA_BUFFER_ID, -1L)
        if (bufferId < 0) return
        when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
                if (text.isNullOrBlank()) return
                launchAsync(applicationScope, TAG) {
                    val acceptance = connectionManager.sendMessage(bufferId, text)
                    if (acceptance is SendAcceptance.Rejected) {
                        Log.w(TAG, "notification reply rejected: ${acceptance.reason}")
                        diagnostics.record("notification_reply", "send_rejected") {
                            mapOf("buffer_id" to bufferId, "reason" to acceptance.reason.name)
                        }
                    }
                }
            }
            ACTION_MARK_READ -> {
                if (!intent.hasExtra(EXTRA_UP_TO_TIME)) return
                val upTo = intent.getLongExtra(EXTRA_UP_TO_TIME, 0L)
                val eventId = intent.getLongExtra(EXTRA_UP_TO_EVENT_ID, 0L)
                launchAsync(applicationScope, TAG) {
                    connectionManager.markRead(bufferId, TimelineAnchor(upTo, eventId))
                }
            }
            else -> Unit
        }
    }

    companion object {
        const val ACTION_REPLY = "io.github.trevarj.motd.service.REPLY"
        const val ACTION_MARK_READ = "io.github.trevarj.motd.service.MARK_READ"
        const val EXTRA_BUFFER_ID = "bufferId"
        const val EXTRA_UP_TO_TIME = "upToTime"
        const val EXTRA_UP_TO_EVENT_ID = "upToEventId"
        const val KEY_REPLY = "key_reply"
        private const val TAG = "ReplyReceiver"
    }
}
