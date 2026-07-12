package io.github.trevarj.motd.push

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.trevarj.motd.BuildConfig
import io.github.trevarj.motd.data.prefs.FcmSubscription
import io.github.trevarj.motd.data.prefs.PushProviderPrefs
import io.github.trevarj.motd.service.ConnectionManager
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Singleton
class GoogleFcmPushApi @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PushProviderPrefs,
    private val registrar: WebPushRegistrar,
    private val connectionManager: ConnectionManager,
) : FcmPushApi {
    override val available: Boolean get() = configured()
    private val relay by lazy { FcmRelayClient(BuildConfig.FCM_RELAY_URL) }

    override fun start() {
        if (!configured() || FirebaseApp.getApps(context).isNotEmpty()) return
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build(),
        )
    }

    @Suppress("DEPRECATION") // FCM 25.1 token API remains required by Admin SDK token delivery.
    override suspend fun reconcile(connectable: Set<Long>) {
        if (!configured()) return
        start()
        val token = FirebaseMessaging.getInstance().token.await()
        val subscriptions = prefs.fcmSubscriptions()
        for (id in subscriptions.keys - connectable) remove(id, subscriptions.getValue(id))
        for (id in connectable) {
            val existing = prefs.fcmSubscriptions()[id]
            when {
                existing == null -> create(id, token)
                existing.token != token -> update(existing, token)
                else -> registrar.reRegisterIfNeeded(id)
            }
        }
        connectionManager.evaluatePushMode()
    }

    override suspend fun onTokenChanged(token: String) {
        if (!configured()) return
        for (subscription in prefs.fcmSubscriptions().values) update(subscription, token)
    }

    private suspend fun create(networkId: Long, token: String) {
        // Remove a previous provider's endpoint from soju before replacing it.
        registrar.onUnregisteredNetwork(networkId)
        val created = withContext(Dispatchers.IO) { relay.create(token, networkId) }
        prefs.setFcmSubscription(networkId, created)
        registrar.onNewEndpoint(networkId, created.endpoint)
    }

    private suspend fun update(subscription: FcmSubscription, token: String) {
        withContext(Dispatchers.IO) { relay.update(subscription, token) }
        prefs.setFcmSubscription(subscription.networkId, subscription.copy(token = token))
    }

    private suspend fun remove(networkId: Long, subscription: FcmSubscription) {
        registrar.onUnregisteredNetwork(networkId)
        runCatching { withContext(Dispatchers.IO) { relay.delete(subscription) } }
        prefs.setFcmSubscription(networkId, null)
    }

    private fun configured() = listOf(
        BuildConfig.FIREBASE_API_KEY,
        BuildConfig.FIREBASE_APP_ID,
        BuildConfig.FIREBASE_PROJECT_ID,
        BuildConfig.FIREBASE_SENDER_ID,
        BuildConfig.FCM_RELAY_URL,
    ).all { it.isNotBlank() }
}

private class FcmRelayClient(private val baseUrl: String) {
    private val json = Json { ignoreUnknownKeys = true }

    fun create(token: String, networkId: Long): FcmSubscription {
        val response = request(
            method = "POST",
            path = "/v1/subscriptions",
            body = buildJsonObject {
                put("token", token)
                put("instance", networkId.toString())
            }.toString(),
        )
        val obj = json.parseToJsonElement(response).jsonObject
        return FcmSubscription(
            networkId = networkId,
            endpoint = obj.getValue("endpoint").jsonPrimitive.content,
            subscriptionId = obj.getValue("subscriptionId").jsonPrimitive.content,
            managementSecret = obj.getValue("managementSecret").jsonPrimitive.content,
            token = token,
        )
    }

    fun update(subscription: FcmSubscription, token: String) {
        request(
            method = "PUT",
            path = "/v1/subscriptions/${subscription.subscriptionId}",
            secret = subscription.managementSecret,
            body = buildJsonObject { put("token", token) }.toString(),
        )
    }

    fun delete(subscription: FcmSubscription) {
        request(
            method = "DELETE",
            path = "/v1/subscriptions/${subscription.subscriptionId}",
            secret = subscription.managementSecret,
        )
    }

    private fun request(method: String, path: String, secret: String? = null, body: String? = null): String {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            if (secret != null) connection.setRequestProperty("Authorization", "Bearer $secret")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "FCM relay $method $path failed ($code): $response" }
            return response
        } finally {
            connection.disconnect()
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class GoogleFcmModule {
    @Binds @Singleton
    abstract fun bindFcmPushApi(impl: GoogleFcmPushApi): FcmPushApi
}
