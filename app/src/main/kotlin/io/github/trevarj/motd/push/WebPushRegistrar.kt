package io.github.trevarj.motd.push

import io.github.trevarj.motd.data.prefs.PushKeys
import io.github.trevarj.motd.data.prefs.PushPrefs
import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.client.IrcTimeoutException
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-network Web Push endpoint persistence and REGISTER/UNREGISTER orchestration against the
 * live client of a single network advertising `soju.im/webpush`.
 *
 * The UnifiedPush instance scheme is `instance = networkId.toString()`, so each network owns its
 * own endpoint. The client keypair is shared across networks — the server encrypts to our public
 * key regardless of endpoint, so one keypair covers all subscriptions.
 */
class WebPushRegistrar @Inject constructor(
    private val pushPrefs: PushPrefs,
    private val connectionManager: ConnectionManager,
    private val healthStore: PushHealthStore,
    private val networkDao: NetworkDao? = null,
) {
    private val registrationLocks = ConcurrentHashMap<Long, Mutex>()

    companion object {
        const val WEBPUSH_CAP = "soju.im/webpush"
    }

    /** Load persisted key material, generating and persisting a fresh set on first use. */
    suspend fun loadOrCreateKeys(): WebPushCrypto.KeyMaterial {
        pushPrefs.keys()?.let { return it.toKeyMaterial() }
        val fresh = WebPushCrypto.generateKeyMaterial()
        pushPrefs.setKeys(fresh.toPushKeys())
        return fresh
    }

    /**
     * Handle a new/updated UnifiedPush endpoint for [networkId]: persist it, ensure key material
     * exists, and WEBPUSH REGISTER it on that network's live client if the cap is present.
     * Returns true when a REGISTER was actually sent to the live client.
     */
    suspend fun onNewEndpoint(networkId: Long, endpoint: String): Boolean {
        pushPrefs.setEndpointFor(networkId, endpoint)
        healthStore.endpointReceived(networkId, endpoint)
        val keys = loadOrCreateKeys()
        return registerOn(networkId, endpoint, keys)
    }

    /**
     * Client for [networkId] reached Ready while we hold its endpoint: re-send WEBPUSH REGISTER
     * so a reconnected socket re-arms push. Returns true when a REGISTER was sent.
     */
    suspend fun reRegisterIfNeeded(networkId: Long): Boolean {
        val endpoint = pushPrefs.endpointFor(networkId) ?: return false
        // A new IRC connection has not proved that its old WEBPUSH subscription is still armed.
        // Keep the socket alive until this exact endpoint has been registered again.
        healthStore.waitingForServer(networkId)
        val keys = loadOrCreateKeys()
        return registerOn(networkId, endpoint, keys)
    }

    private suspend fun registerOn(
        networkId: Long,
        endpoint: String,
        keys: WebPushCrypto.KeyMaterial,
    ): Boolean = registrationLocks.getOrPut(networkId) { Mutex() }.withLock {
        val client = connectionManager.clientFor(networkId)
        if (client == null) {
            healthStore.waitingForServer(networkId)
            return@withLock false
        }
        if (!client.hasCap(WEBPUSH_CAP)) {
            if (connectionManager.clientFor(networkId) !== client) {
                healthStore.failed(networkId, "NETWORK_DISCONNECTED")
                return@withLock false
            }
            // Soju bound children become Ready before their post-BIND CAP ACK. The manager holds
            // a connection-scoped waiter and retries registration as soon as it arrives; never
            // turn that temporary state into an unsupported/fallback verdict here.
            if (networkDao?.byId(networkId)?.role == NetworkRole.BOUNCER_CHILD ||
                client.state.value !is IrcClientState.Ready
            ) {
                healthStore.waitingForServer(networkId)
            } else {
                healthStore.capability(networkId, supported = false)
            }
            return@withLock false
        }
        healthStore.verifying(networkId)
        return@withLock try {
            client.webpushRegister(endpoint, keys.publicUncompressed, keys.auth)
            // An endpoint may have changed while the round trip was in flight. Never let an ACK
            // for the old value arm the replacement endpoint.
            if (pushPrefs.endpointFor(networkId) != endpoint) return@withLock false
            healthStore.registered(networkId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            healthStore.failed(networkId, e.toHealthErrorCode())
            false
        }
    }

    /**
     * Per-network unregistration: best-effort WEBPUSH UNREGISTER on the live client and drop the
     * persisted endpoint for [networkId]. Key material is retained (re-usable for a future
     * endpoint on any network).
     */
    suspend fun onUnregisteredNetwork(networkId: Long) {
        val endpoint = pushPrefs.endpointFor(networkId)
        if (endpoint != null) {
            val client = connectionManager.clientFor(networkId)
            if (client != null && client.hasCap(WEBPUSH_CAP)) {
                runCatching { client.webpushUnregister(endpoint) }
            }
        }
        pushPrefs.setEndpointFor(networkId, null)
        healthStore.clear(networkId)
    }
}

private fun Exception.toHealthErrorCode(): String = when (this) {
    is IrcCommandException -> "SERVER_${code.uppercase().filter { it.isLetterOrDigit() || it == '_' }.take(40)}"
    is IrcTimeoutException -> "SERVER_TIMEOUT"
    is IrcDisconnectedException -> "NETWORK_DISCONNECTED"
    else -> "REGISTER_FAILED"
}

private fun PushKeys.toKeyMaterial() = WebPushCrypto.KeyMaterial(
    privateKey = WebPushCrypto.decodeB64Url(privateKey),
    publicUncompressed = WebPushCrypto.decodeB64Url(publicUncompressed),
    auth = WebPushCrypto.decodeB64Url(auth),
)

private fun WebPushCrypto.KeyMaterial.toPushKeys() = PushKeys(
    privateKey = WebPushCrypto.encodeB64Url(privateKey),
    publicUncompressed = WebPushCrypto.encodeB64Url(publicUncompressed),
    auth = WebPushCrypto.encodeB64Url(auth),
)
