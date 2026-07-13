package io.github.trevarj.motd

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import io.github.trevarj.motd.push.PushInstanceCoordinator
import io.github.trevarj.motd.service.ConnectionManager
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class MotdApplication : Application() {
    // THE UnifiedPush registration trigger: reconciles registered instances against the
    // delivery mode and connectable-network set for the process lifetime.
    @Inject lateinit var pushInstanceCoordinator: PushInstanceCoordinator

    // The connection subsystem (@Singleton). Foreground reconnect re-drives its wanted set.
    @Inject lateinit var connectionManager: ConnectionManager

    // Process-lifetime scope for the foreground reconnect trigger.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        pushInstanceCoordinator.start()
        // Canonical app-wide foreground signal (survives Activity recreation, single trigger per
        // process foreground). onStart re-drives the connection subsystem so an actor that died or
        // parked while backgrounded (Doze / network drop) is revived for a seamless reconnect. It
        // no-ops unless the subsystem is started, never disturbs healthy/manual-disconnect state, and
        // wakes a retrying actor through its conflated backoff signal rather than rebuilding it.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    appScope.launch { connectionManager.reconnectStale() }
                }
            },
        )
    }
}
