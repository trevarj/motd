package io.github.trevarj.motd.service

import android.content.Intent
import androidx.lifecycle.LifecycleService

// No-op shell so the manifest's class reference lints clean. WP5 fills in the
// foreground-service lifecycle (startForeground + ConnectionManager.startAll/stopAll).
class IrcForegroundService : LifecycleService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }
}
