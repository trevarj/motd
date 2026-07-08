package io.github.trevarj.motd.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Direct-reply / mark-read notification actions (plans/05). The RemoteInput reply is forwarded to
 * [ConnectionManager.sendMessage]; the mark-read action to [ConnectionManager.markRead].
 */
@AndroidEntryPoint
class ReplyReceiver : BroadcastReceiver() {

    @Inject lateinit var connectionManager: ConnectionManager

    override fun onReceive(context: Context, intent: Intent) {
        val bufferId = intent.getLongExtra(EXTRA_BUFFER_ID, -1L)
        if (bufferId < 0) return
        val pending = goAsync()
        val scope = CoroutineScope(Dispatchers.Default)
        when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
                if (text.isNullOrBlank()) { pending.finish(); return }
                scope.launch {
                    try { connectionManager.sendMessage(bufferId, text) } finally { pending.finish() }
                }
            }
            ACTION_MARK_READ -> {
                val upTo = intent.getLongExtra(EXTRA_UP_TO_TIME, System.currentTimeMillis())
                scope.launch {
                    try { connectionManager.markRead(bufferId, upTo) } finally { pending.finish() }
                }
            }
            else -> pending.finish()
        }
    }

    companion object {
        const val ACTION_REPLY = "io.github.trevarj.motd.service.REPLY"
        const val ACTION_MARK_READ = "io.github.trevarj.motd.service.MARK_READ"
        const val EXTRA_BUFFER_ID = "bufferId"
        const val EXTRA_UP_TO_TIME = "upToTime"
        const val KEY_REPLY = "key_reply"
    }
}
