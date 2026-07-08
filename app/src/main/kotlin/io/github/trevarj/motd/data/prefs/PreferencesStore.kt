package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.service.DeliveryMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Single Preferences DataStore named "settings" shared by SettingsRepository, PushPrefs, and the
// internal STS-policy accessor. Property delegate scopes one instance per process.
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

internal object PrefKeys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val DYNAMIC_COLOR = stringPreferencesKey("dynamic_color")
    val DELIVERY_MODE = stringPreferencesKey("delivery_mode")
    val PUSH_ENDPOINT = stringPreferencesKey("push_endpoint")
    val PUSH_KEYS = stringPreferencesKey("push_keys")
    val STS_POLICIES = stringPreferencesKey("sts_policies")
}

// Implements SettingsRepository, PushPrefs, and exposes STS-policy JSON storage (internal, for
// WP5) all over the one DataStore. Constructor-injectable so WP10 can rebind the interfaces.
@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository, PushPrefs {
    private val store get() = context.settingsDataStore
    private val json = Json { ignoreUnknownKeys = true }

    // -- SettingsRepository --

    override val settings: Flow<Settings> = store.data.map { prefs ->
        Settings(
            themeMode = prefs[PrefKeys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[PrefKeys.DYNAMIC_COLOR]?.toBooleanStrictOrNull() ?: true,
            deliveryMode = prefs[PrefKeys.DELIVERY_MODE]?.let { runCatching { DeliveryMode.valueOf(it) }.getOrNull() }
                ?: DeliveryMode.PERSISTENT_SOCKET,
        )
    }

    override suspend fun setThemeMode(m: ThemeMode) {
        store.edit { it[PrefKeys.THEME_MODE] = m.name }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        store.edit { it[PrefKeys.DYNAMIC_COLOR] = enabled.toString() }
    }

    override suspend fun setDeliveryMode(m: DeliveryMode) {
        store.edit { it[PrefKeys.DELIVERY_MODE] = m.name }
    }

    // -- PushPrefs (values base64url; keys stored as JSON) --

    override suspend fun endpoint(): String? =
        store.data.first()[PrefKeys.PUSH_ENDPOINT]

    override suspend fun setEndpoint(endpoint: String?) {
        store.edit {
            if (endpoint == null) it.remove(PrefKeys.PUSH_ENDPOINT)
            else it[PrefKeys.PUSH_ENDPOINT] = endpoint
        }
    }

    // PushKeys is a frozen contract data class (no @Serializable), so (de)serialize it manually
    // as a plain JSON object.
    override suspend fun keys(): PushKeys? =
        store.data.first()[PrefKeys.PUSH_KEYS]?.let { raw ->
            runCatching {
                val obj = json.parseToJsonElement(raw)
                    .let { it as kotlinx.serialization.json.JsonObject }
                PushKeys(
                    privateKey = obj.getValue("privateKey").jsonPrimitive.content,
                    publicUncompressed = obj.getValue("publicUncompressed").jsonPrimitive.content,
                    auth = obj.getValue("auth").jsonPrimitive.content,
                )
            }.getOrNull()
        }

    override suspend fun setKeys(keys: PushKeys) {
        val encoded = buildJsonObject {
            put("privateKey", keys.privateKey)
            put("publicUncompressed", keys.publicUncompressed)
            put("auth", keys.auth)
        }.toString()
        store.edit { it[PrefKeys.PUSH_KEYS] = encoded }
    }

    // -- STS policy storage (internal accessor for WP5) --

    /** Raw STS-policy JSON blob (opaque to this layer); WP5 owns the schema. */
    internal suspend fun stsPolicies(): String? =
        store.data.first()[PrefKeys.STS_POLICIES]

    internal suspend fun setStsPolicies(jsonBlob: String?) {
        store.edit {
            if (jsonBlob == null) it.remove(PrefKeys.STS_POLICIES)
            else it[PrefKeys.STS_POLICIES] = jsonBlob
        }
    }
}
