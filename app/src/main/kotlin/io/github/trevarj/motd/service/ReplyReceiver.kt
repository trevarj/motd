package io.github.trevarj.motd.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import io.github.trevarj.motd.di.AppClock
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
    @Inject lateinit var clock: AppClock

    override fun onReceive(context: Context, intent: Intent) {
        val bufferId = intent.getLongExtra(EXTRA_BUFFER_ID, -1L)
        if (bufferId < 0) return
        when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
                if (text.isNullOrBlank()) return
                launchAsync(applicationScope, TAG) {
                    connectionManager.sendMessage(bufferId, text)
                }
            }
            ACTION_MARK_READ -> {
                val upTo = intent.getLongExtra(EXTRA_UP_TO_TIME, clock.nowMillis())
                launchAsync(applicationScope, TAG) {
                    connectionManager.markRead(bufferId, upTo)
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
        const val KEY_REPLY = "key_reply"
        private const val TAG = "ReplyReceiver"
    }
}
