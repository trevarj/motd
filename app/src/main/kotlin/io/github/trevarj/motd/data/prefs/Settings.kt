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
