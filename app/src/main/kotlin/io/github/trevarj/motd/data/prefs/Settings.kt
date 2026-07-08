package io.github.trevarj.motd.data.prefs

import io.github.trevarj.motd.service.DeliveryMode
import kotlinx.coroutines.flow.Flow

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val deliveryMode: DeliveryMode = DeliveryMode.PERSISTENT_SOCKET,
)

interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun setThemeMode(m: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setDeliveryMode(m: DeliveryMode)
}

/** Webpush endpoint + client keypair persistence (DataStore). Implemented by WP4 alongside
 *  SettingsRepository; consumed by WP9. All values base64url. */
data class PushKeys(val privateKey: String, val publicUncompressed: String, val auth: String)

interface PushPrefs {
    // Per-network endpoints (keyed by network row id).
    suspend fun endpoints(): Map<Long, String>
    suspend fun endpointFor(networkId: Long): String?
    suspend fun setEndpointFor(networkId: Long, endpoint: String?)  // null removes
    suspend fun clearEndpoints()

    suspend fun keys(): PushKeys?                      // one shared keypair
    suspend fun setKeys(keys: PushKeys)
}

/**
 * TOFU cert pins for self-signed / bare-IP TLS bouncers (plans/12). Persists the accepted leaf
 * SHA-256 (lowercase hex) per host:port. A pinned host skips CA/hostname validation; a pin mismatch
 * later triggers a change warning. DataStore key `cert_pins` = JSON `{"host:port":"<sha256 hex>"}`.
 */
interface CertTrustStore {
    /** Pinned lowercase-hex SHA-256 for [host]:[port], or null when unpinned. */
    suspend fun pinnedFor(host: String, port: Int): String?

    /** True when [sha256] (case-insensitive) matches the pin for [host]:[port]. */
    suspend fun isPinned(host: String, port: Int, sha256: String): Boolean

    /** Pin (or re-pin) [sha256] for [host]:[port]; stored lowercase. */
    suspend fun pin(host: String, port: Int, sha256: String)

    /** Remove any pin for [host]:[port]. */
    suspend fun unpin(host: String, port: Int)
}
