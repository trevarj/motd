package io.github.trevarj.motd.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// No-op shell so the manifest's class reference lints clean. WP5 starts the
// foreground service on BOOT_COMPLETED when delivery mode is PERSISTENT_SOCKET.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // WP5
    }
}
