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
    // Per-network endpoints (keyed by network row id). Round 2: replaces the single-endpoint
    // API below, which WP-R2 deletes once callers migrate. Both coexist mid-wave to stay green.
    suspend fun endpoints(): Map<Long, String>
    suspend fun endpointFor(networkId: Long): String?
    suspend fun setEndpointFor(networkId: Long, endpoint: String?)  // null removes
    suspend fun clearEndpoints()

    // Legacy single-endpoint API (removed by WP-R2 — kept so v1 push callers still compile).
    suspend fun endpoint(): String?
    suspend fun setEndpoint(endpoint: String?)

    suspend fun keys(): PushKeys?                      // unchanged: one shared keypair
    suspend fun setKeys(keys: PushKeys)
}
