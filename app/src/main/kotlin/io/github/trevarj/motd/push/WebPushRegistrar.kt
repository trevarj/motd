package io.github.trevarj.motd.push

import io.github.trevarj.motd.data.prefs.PushKeys
import io.github.trevarj.motd.data.prefs.PushPrefs
import io.github.trevarj.motd.service.ConnectionManager
import javax.inject.Inject

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

    /**
     * Handle a new/updated UnifiedPush endpoint for [networkId]: persist it, ensure key material
     * exists, and WEBPUSH REGISTER it on that network's live client if the cap is present.
     * Returns true when a REGISTER was actually sent to the live client.
     */
    suspend fun onNewEndpoint(networkId: Long, endpoint: String): Boolean {
        pushPrefs.setEndpointFor(networkId, endpoint)
        val keys = loadOrCreateKeys()
        return registerOn(networkId, endpoint, keys)
    }

    /**
     * Client for [networkId] reached Ready while we hold its endpoint: re-send WEBPUSH REGISTER
     * so a reconnected socket re-arms push. Returns true when a REGISTER was sent.
     */
    suspend fun reRegisterIfNeeded(networkId: Long): Boolean {
        val endpoint = pushPrefs.endpointFor(networkId) ?: return false
        val keys = loadOrCreateKeys()
        return registerOn(networkId, endpoint, keys)
    }

    private suspend fun registerOn(networkId: Long, endpoint: String, keys: WebPushCrypto.KeyMaterial): Boolean {
        val client = connectionManager.clientFor(networkId) ?: return false
        if (!client.hasCap(WEBPUSH_CAP)) return false
        client.webpushRegister(endpoint, keys.publicUncompressed, keys.auth)
        return true
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
