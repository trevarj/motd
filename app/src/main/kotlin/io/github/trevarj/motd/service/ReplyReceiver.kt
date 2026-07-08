package io.github.trevarj.motd.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// No-op shell so the manifest's class reference lints clean. WP5 handles the
// direct-reply RemoteInput and forwards to ConnectionManager.sendMessage.
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // WP5
    }
}
