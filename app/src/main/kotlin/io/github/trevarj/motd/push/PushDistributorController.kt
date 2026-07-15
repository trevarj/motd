package io.github.trevarj.motd.push

import android.content.Context
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.PushPrefs
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.unifiedpush.android.connector.UnifiedPush

/** Serialized user-facing distributor selection and retry operations. */
@Singleton
class PushDistributorController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val unifiedPush: UnifiedPushApi,
    private val networkDao: NetworkDao,
    private val pushPrefs: PushPrefs,
    private val healthStore: PushHealthStore,
    private val connectionManager: ConnectionManager,
    private val webPushRegistrar: Lazy<WebPushRegistrar>,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val pendingUnregistrations =
        java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<Unit>>()

    suspend fun select(packageName: String) = mutex.withLock {
        require(packageName in unifiedPush.getDistributors()) { "UnifiedPush distributor is not installed" }
        if (unifiedPush.getAckDistributor() == packageName) {
            retryLocked()
            return@withLock
        }

        // Settings is foreground, so ensure sockets are available for best-effort server cleanup.
        connectionManager.startAll()
        val endpoints = pushPrefs.endpoints()
        for (id in endpoints.keys) {
            pendingUnregistrations[id] = CompletableDeferred()
            unifiedPush.unregisterApp(id.toString())
        }

        // The connector removes its local instance only after invoking our onUnregistered callback.
        // Wait for every old callback before changing the saved distributor so a late old callback
        // cannot delete a freshly-created instance with the same network id.
        val removed = withTimeoutOrNull(UNREGISTER_TIMEOUT_MS) {
            endpoints.keys.mapNotNull { pendingUnregistrations[it] }.awaitAll()
            true
        } == true
        if (!removed) {
            endpoints.keys.forEach { id ->
                pendingUnregistrations.remove(id)?.cancel()
                healthStore.failed(id, "DISTRIBUTOR_CHANGE_TIMEOUT")
            }
            throw IllegalStateException("old UnifiedPush distributor did not unregister")
        }
        UnifiedPush.safeRemoveDistributor(context)
        unifiedPush.saveDistributor(packageName)
        retryLocked()
    }

    suspend fun retry() = mutex.withLock { retryLocked() }

    fun onUnregistered(networkId: Long) {
        // Complete after MessagingReceiver returns from the callback; its own Store.removeInstance
        // runs immediately afterward inside the connector's onReceive implementation.
        scope.launch {
            delay(CONNECTOR_STORE_SETTLE_MS)
            pendingUnregistrations.remove(networkId)?.complete(Unit)
        }
    }

    private suspend fun retryLocked() {
        connectionManager.startAll()
        networkDao.allNow()
            .filter { it.autoConnect && it.role != NetworkRole.BOUNCER_ROOT }
            .forEach { network ->
                val endpoint = pushPrefs.endpointFor(network.id)
                if (endpoint == null) {
                    healthStore.requestingEndpoint(network.id)
                } else {
                    // Existing distributor state does not imply that soju retained the server-side
                    // registration. Re-arm it now when possible; the Ready watcher retries after
                    // a late bouncer CAP ACK if this attempt is too early.
                    healthStore.waitingForServer(network.id)
                }
                unifiedPush.registerApp(network.id.toString())
                if (endpoint != null) webPushRegistrar.get().reRegisterIfNeeded(network.id)
            }
        connectionManager.evaluatePushMode()
    }

    private companion object {
        const val UNREGISTER_TIMEOUT_MS = 15_000L
        const val CONNECTOR_STORE_SETTLE_MS = 250L
    }
}
