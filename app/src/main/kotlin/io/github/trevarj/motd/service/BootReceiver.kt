package io.github.trevarj.motd.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.prefs.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts the foreground service on BOOT_COMPLETED when delivery mode is PERSISTENT_SOCKET and at
 * least one network exists (plans/05). API 34+ permits FGS start from BOOT_COMPLETED for
 * specialUse; a [ForegroundServiceStartNotAllowedException] is swallowed (app connects on open).
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var db: MotdDatabase

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val deliveryPersistent = settings.settings.first().deliveryMode == DeliveryMode.PERSISTENT_SOCKET
                val hasNetworks = db.networkDao().connectable().isNotEmpty()
                if (ConnectionManagerImpl.shouldRunService(deliveryPersistent, hasNetworks)) {
                    startService(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun startService(context: Context) {
        val svc = Intent(context, IrcForegroundService::class.java)
        try {
            ContextCompat.startForegroundService(context, svc)
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                // Swallow: the app will connect on next open.
            } else {
                throw e
            }
        }
    }
}
