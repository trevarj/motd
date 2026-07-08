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
    val PUSH_ENDPOINTS = stringPreferencesKey("push_endpoints")
    val PUSH_KEYS = stringPreferencesKey("push_keys")
    val STS_POLICIES = stringPreferencesKey("sts_policies")
    val CERT_PINS = stringPreferencesKey("cert_pins")
}

// Implements SettingsRepository, PushPrefs, and exposes STS-policy JSON storage (internal, for
// WP5) all over the one DataStore. Constructor-injectable so WP10 can rebind the interfaces.
@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository, PushPrefs, CertTrustStore {
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

    // Per-network endpoints persisted as a JSON object {"<networkId>": "<url>"} under one key,
    // using the same manual-JSON approach as push_keys.
    override suspend fun endpoints(): Map<Long, String> =
        decodeEndpoints(store.data.first()[PrefKeys.PUSH_ENDPOINTS])

    override suspend fun endpointFor(networkId: Long): String? =
        endpoints()[networkId]

    override suspend fun setEndpointFor(networkId: Long, endpoint: String?) {
        store.edit { prefs ->
            val current = decodeEndpoints(prefs[PrefKeys.PUSH_ENDPOINTS]).toMutableMap()
            if (endpoint == null) current.remove(networkId) else current[networkId] = endpoint
            if (current.isEmpty()) prefs.remove(PrefKeys.PUSH_ENDPOINTS)
            else prefs[PrefKeys.PUSH_ENDPOINTS] = encodeEndpoints(current)
        }
    }

    override suspend fun clearEndpoints() {
        store.edit { it.remove(PrefKeys.PUSH_ENDPOINTS) }
    }

    private fun decodeEndpoints(raw: String?): Map<Long, String> {
        if (raw == null) return emptyMap()
        return runCatching {
            val obj = json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
            obj.entries.mapNotNull { (k, v) ->
                val id = k.toLongOrNull() ?: return@mapNotNull null
                id to v.jsonPrimitive.content
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun encodeEndpoints(map: Map<Long, String>): String =
        buildJsonObject {
            for ((id, url) in map) put(id.toString(), url)
        }.toString()

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

    // -- CertTrustStore (TOFU cert pins; JSON {"host:port":"<sha256 hex>"} under one key) --

    override suspend fun pinnedFor(host: String, port: Int): String? =
        decodeCertPins(store.data.first()[PrefKeys.CERT_PINS])[pinKey(host, port)]

    override suspend fun isPinned(host: String, port: Int, sha256: String): Boolean =
        pinnedFor(host, port)?.equals(sha256, ignoreCase = true) == true

    override suspend fun pin(host: String, port: Int, sha256: String) {
        store.edit { prefs ->
            val current = decodeCertPins(prefs[PrefKeys.CERT_PINS]).toMutableMap()
            current[pinKey(host, port)] = sha256.lowercase()
            prefs[PrefKeys.CERT_PINS] = encodeCertPins(current)
        }
    }

    override suspend fun unpin(host: String, port: Int) {
        store.edit { prefs ->
            val current = decodeCertPins(prefs[PrefKeys.CERT_PINS]).toMutableMap()
            current.remove(pinKey(host, port))
            if (current.isEmpty()) prefs.remove(PrefKeys.CERT_PINS)
            else prefs[PrefKeys.CERT_PINS] = encodeCertPins(current)
        }
    }

    // host:port composite key; host lowercased so pins are case-insensitive on the hostname.
    private fun pinKey(host: String, port: Int): String = "${host.lowercase()}:$port"

    private fun decodeCertPins(raw: String?): Map<String, String> {
        if (raw == null) return emptyMap()
        return runCatching {
            val obj = json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
            obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
    }

    private fun encodeCertPins(map: Map<String, String>): String =
        buildJsonObject {
            for ((k, v) in map) put(k, v)
        }.toString()

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
