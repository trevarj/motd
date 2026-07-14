package io.github.trevarj.motd.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.service.ConnectionManagerImpl
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Owns the foreground catch-up/background push hand-off for the process lifetime. */
@Singleton
class PushLifecycleCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: ConnectionManagerImpl,
) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false
    private val powerManager: PowerManager?
        get() = context.getSystemService(PowerManager::class.java)
    private val idleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) return
            connectionManager.onDeviceIdleModeChanged(powerManager?.isDeviceIdleMode == true)
        }
    }

    fun start() {
        if (started) return
        started = true
        ContextCompat.registerReceiver(
            context,
            idleReceiver,
            IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        connectionManager.onDeviceIdleModeChanged(powerManager?.isDeviceIdleMode == true)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        scope.launch { connectionManager.onAppForegrounded() }
    }

    override fun onStop(owner: LifecycleOwner) {
        connectionManager.onAppBackgrounded()
    }
}
