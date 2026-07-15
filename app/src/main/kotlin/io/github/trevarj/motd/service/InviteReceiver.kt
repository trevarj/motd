package io.github.trevarj.motd.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import io.github.trevarj.motd.di.ApplicationScope
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/** Notification Dismiss/swipe action, routed through [ConnectionManager]. */
@AndroidEntryPoint
class InviteReceiver : BroadcastReceiver() {
    @Inject lateinit var connectionManager: ConnectionManager
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        if (messageId < 0 || intent.action != ACTION_DISMISS) return
        launchAsync(applicationScope, TAG) {
            connectionManager.dismissInvite(messageId)
        }
    }

    companion object {
        const val ACTION_DISMISS = "io.github.trevarj.motd.service.INVITE_DISMISS"
        const val EXTRA_MESSAGE_ID = "inviteMessageId"
        private const val TAG = "InviteReceiver"
    }
}
