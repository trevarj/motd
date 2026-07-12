package io.github.trevarj.motd.push

import android.util.Base64
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@Suppress("OVERRIDE_DEPRECATION")
class MotdFirebaseMessagingService : FirebaseMessagingService() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface FcmEntryPoint {
        fun fcmPushApi(): FcmPushApi
        fun registrar(): WebPushRegistrar
        fun eventHandler(): PushEventHandler
        fun networkDao(): NetworkDao
        fun connectionManager(): ConnectionManager
    }

    private fun entry(): FcmEntryPoint =
        EntryPointAccessors.fromApplication(applicationContext, FcmEntryPoint::class.java)

    override fun onMessageReceived(message: RemoteMessage) {
        val instance = message.data["instance"]
        val payload = message.data["payload"]
        if (instance == null || payload == null) return
        runCatching {
            runBlocking(Dispatchers.Default) {
                val id = instance.toLongOrNull() ?: return@runBlocking
                val e = entry()
                if (e.networkDao().byId(id) == null) return@runBlocking
                val body = Base64.decode(payload, Base64.NO_WRAP)
                e.eventHandler().handle(id, body, e.registrar().loadOrCreateKeys())
            }
        }.onFailure { Log.w(TAG, "FCM message handling failed", it) }
    }

    // Paired with token-based Admin SDK delivery until FID support stabilizes.
    override fun onNewToken(token: String) {
        runCatching { runBlocking(Dispatchers.IO) { entry().fcmPushApi().onTokenChanged(token) } }
            .onFailure { Log.w(TAG, "FCM token update failed", it) }
    }

    override fun onDeletedMessages() {
        runCatching { runBlocking(Dispatchers.Default) { entry().connectionManager().reconnectStale() } }
            .onFailure { Log.w(TAG, "FCM catch-up reconnect failed", it) }
    }

    private companion object { const val TAG = "MotdFcmService" }
}
