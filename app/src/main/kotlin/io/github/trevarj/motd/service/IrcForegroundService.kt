package io.github.trevarj.motd.service

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.trevarj.motd.irc.event.IrcClientState
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Foreground-service keeper for the connection subsystem (plans/05). Thin [LifecycleService]:
 * onStartCommand → startForeground(status) + connectionManager.startAll(); onDestroy → stopAll().
 * START_STICKY so Android restarts it after a kill while PERSISTENT_SOCKET is in effect.
 */
@AndroidEntryPoint
class IrcForegroundService : LifecycleService() {

    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var notifications: MotdNotifications

    override fun onCreate() {
        super.onCreate()
        // Reflect live connection state in the status notification.
        lifecycleScope.launch {
            (connectionManager as? ConnectionManagerImpl)?.connectionStates?.collect { states ->
                updateStatus(states)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        lifecycleScope.launch { connectionManager.startAll() }
        return START_STICKY
    }

    // The merged manifest for every flavor declares specialUse. AGP lint loses that declaration
    // when analyzing the shared service against the Google flavor's manifest overlay.
    @SuppressLint("ForegroundServiceType")
    private fun startAsForeground() {
        val notification = notifications.statusNotification(connectedCount = 0, reconnecting = true)
        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE is an API 34 constant; only pass the type on 34+.
        // On 29-33 use the 2-arg overload (the manifest still declares foregroundServiceType).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(STATUS_ID, notification)
        }
    }

    private fun updateStatus(states: Map<Long, IrcClientState>) {
        val connected = states.values.count { it is IrcClientState.Ready }
        val reconnecting = states.isEmpty() || states.values.any {
            it is IrcClientState.Connecting || it is IrcClientState.Registering
        }
        val notification = notifications.statusNotification(connected, reconnecting && connected == 0)
        // POST_NOTIFICATIONS is only a runtime permission on API 33+; guard so lint's flow
        // analysis is satisfied and we don't attempt to post the status update without it.
        val canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (canPost) {
            runCatching {
                androidx.core.app.NotificationManagerCompat.from(this).notify(STATUS_ID, notification)
            }
        }
    }

    override fun onDestroy() {
        lifecycleScope.launch { connectionManager.stopAll() }
        super.onDestroy()
    }

    companion object {
        const val STATUS_ID = 1
        const val ACTION_STOP = "io.github.trevarj.motd.service.STOP"
    }
}
