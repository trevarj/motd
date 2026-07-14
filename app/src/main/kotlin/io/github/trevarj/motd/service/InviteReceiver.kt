package io.github.trevarj.motd.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Notification Dismiss/swipe action, routed through [ConnectionManager]. */
@AndroidEntryPoint
class InviteReceiver : BroadcastReceiver() {
    @Inject lateinit var connectionManager: ConnectionManager

    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        if (messageId < 0) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_DISMISS -> connectionManager.dismissInvite(messageId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DISMISS = "io.github.trevarj.motd.service.INVITE_DISMISS"
        const val EXTRA_MESSAGE_ID = "inviteMessageId"
    }
}
