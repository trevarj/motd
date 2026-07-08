package io.github.trevarj.motd.push

import android.content.Context
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.DeliveryMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.unifiedpush.android.connector.MessagingReceiver

/**
 * UnifiedPush entry point (connector 2.5.0 `MessagingReceiver`). Distributors deliver endpoint
 * lifecycle callbacks and encrypted push bodies here.
 *
 * Callback signatures for connector 2.5.0 (verified against the artifact bytecode):
 *   onNewEndpoint(context, endpoint, instance)
 *   onMessage(context, message: ByteArray, instance)
 *   onRegistrationFailed(context, instance)
 *   onUnregistered(context, instance)
 *
 * `MessagingReceiver.onReceive` holds a wakelock for the duration of these callbacks, so each
 * callback runs its suspend work to completion with `runBlocking` on a background dispatcher —
 * decrypt + sink write are fast and the connector keeps the process alive for the window.
 */
class MotdPushReceiver : MessagingReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PushEntryPoint {
        fun registrar(): WebPushRegistrar
        fun eventHandler(): PushEventHandler
        fun connectionManager(): ConnectionManager
        fun settingsRepository(): SettingsRepository
    }

    private fun entry(context: Context): PushEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, PushEntryPoint::class.java)

    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        val e = entry(context)
        runOnWakelock {
            val sent = e.registrar().onNewEndpoint(endpoint)
            // Once REGISTER reaches at least one network, push mode is viable.
            if (sent > 0) e.settingsRepository().setDeliveryMode(DeliveryMode.UNIFIED_PUSH)
        }
    }

    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        val e = entry(context)
        runOnWakelock {
            val keys = e.registrar().loadOrCreateKeys()
            // `instance` is our per-network registration token; map it to a network id.
            val networkId = instance.toLongOrNull() ?: DEFAULT_NETWORK_ID
            e.eventHandler().handle(networkId, message, keys)
        }
    }

    override fun onRegistrationFailed(context: Context, instance: String) {
        // Delivery over UnifiedPush is unavailable; fall back to persistent-socket mode.
        val e = entry(context)
        runOnWakelock {
            e.settingsRepository().setDeliveryMode(DeliveryMode.PERSISTENT_SOCKET)
            e.connectionManager().startAll()
        }
    }

    override fun onUnregistered(context: Context, instance: String) {
        val e = entry(context)
        runOnWakelock {
            e.registrar().onUnregistered()
            e.settingsRepository().setDeliveryMode(DeliveryMode.PERSISTENT_SOCKET)
            e.connectionManager().startAll()
        }
    }

    /**
     * Run [block] to completion inside the connector's wakelock window. The suspend work is
     * short (decrypt + a Room write, or a few labeled REGISTER round-trips) so blocking the
     * receiver thread here is acceptable and keeps the callback correct without a bound scope.
     */
    private inline fun runOnWakelock(crossinline block: suspend () -> Unit) {
        runCatching { runBlocking(Dispatchers.Default) { block() } }
            .onFailure { Log.w(TAG, "push callback failed", it) }
    }

    private companion object {
        const val TAG = "MotdPushReceiver"

        /**
         * UnifiedPush `instance` strings carry our per-network registration id. Until WP5/WP10
         * assign real per-network instances, a non-numeric instance maps to the single default
         * connection.
         */
        const val DEFAULT_NETWORK_ID = 0L
    }
}
