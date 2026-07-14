package io.github.trevarj.motd.push

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
    private val connectionManager: ConnectionManagerImpl,
) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        scope.launch { connectionManager.onAppForegrounded() }
    }

    override fun onStop(owner: LifecycleOwner) {
        connectionManager.onAppBackgrounded()
    }
}
