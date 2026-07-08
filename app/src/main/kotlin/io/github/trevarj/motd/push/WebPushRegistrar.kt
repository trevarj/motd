package io.github.trevarj.motd.push

import io.github.trevarj.motd.data.prefs.PushKeys
import io.github.trevarj.motd.data.prefs.PushPrefs
import io.github.trevarj.motd.service.ConnectionManager
import javax.inject.Inject

/**
 * Web Push endpoint + client keypair persistence and per-network REGISTER/UNREGISTER
 * orchestration against every connected network advertising `soju.im/webpush`.
 *
 * Consumes only WP1 contracts ([PushPrefs], [ConnectionManager]). The full connection wiring
 * (which network ids are the soju root connections) is provided by WP5/WP10; here we iterate
 * the manager's current connection states and register on any client that has the cap.
 */
class WebPushRegistrar @Inject constructor(
    private val pushPrefs: PushPrefs,
    private val connectionManager: ConnectionManager,
) {
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

    /** The current subscription endpoint, if any (set by UnifiedPush onNewEndpoint). */
    suspend fun currentEndpoint(): String? = pushPrefs.endpoint()

    /**
     * Handle a new/updated UnifiedPush endpoint: persist it, ensure key material exists, and
     * WEBPUSH REGISTER it on every currently connected network that supports the cap.
     * Returns the number of networks a registration was sent to.
     */
    suspend fun onNewEndpoint(endpoint: String): Int {
        pushPrefs.setEndpoint(endpoint)
        val keys = loadOrCreateKeys()
        return registerOnAll(endpoint, keys)
    }

    private suspend fun registerOnAll(endpoint: String, keys: WebPushCrypto.KeyMaterial): Int {
        var count = 0
        for (networkId in connectionManager.connectionStates.value.keys) {
            val client = connectionManager.clientFor(networkId) ?: continue
            if (!client.hasCap(WEBPUSH_CAP)) continue
            client.webpushRegister(endpoint, keys.publicUncompressed, keys.auth)
            count++
        }
        return count
    }

    /**
     * Handle UnifiedPush unregistration: WEBPUSH UNREGISTER on every supporting network and
     * clear the persisted endpoint. Key material is retained (re-usable for a future endpoint).
     */
    suspend fun onUnregistered() {
        val endpoint = pushPrefs.endpoint()
        if (endpoint != null) {
            for (networkId in connectionManager.connectionStates.value.keys) {
                val client = connectionManager.clientFor(networkId) ?: continue
                if (!client.hasCap(WEBPUSH_CAP)) continue
                client.webpushUnregister(endpoint)
            }
        }
        pushPrefs.setEndpoint(null)
    }
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
