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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
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
    val PUSH_PROVIDER = stringPreferencesKey("push_provider")
    val FCM_SUBSCRIPTIONS = stringPreferencesKey("fcm_subscriptions")
    val STS_POLICIES = stringPreferencesKey("sts_policies")
    val CERT_PINS = stringPreferencesKey("cert_pins")
    // Round 4 (plans/13)
    val LAYOUT_DENSITY = stringPreferencesKey("layout_density")
    val NICK_COLORS_ENABLED = stringPreferencesKey("nick_colors_enabled")
    val NICK_COLOR_PALETTE = stringPreferencesKey("nick_color_palette")
    val NICK_COLOR_OVERRIDES = stringPreferencesKey("nick_color_overrides")
    val FRIEND_NICKS = stringPreferencesKey("friend_nicks")
    val FOOL_NICKS = stringPreferencesKey("fool_nicks")
    val FOOLS_MODE = stringPreferencesKey("fools_mode")
    val SHOW_JOIN_PART_QUIT = stringPreferencesKey("show_join_part_quit")
    val AVATAR_STYLE = stringPreferencesKey("avatar_style")
    val CHAT_WALLPAPER = stringPreferencesKey("chat_wallpaper")
    val SHOW_COMPOSER_EMOJI = stringPreferencesKey("show_composer_emoji")
}

// -- Round 4 nick-set / hue-override JSON codecs (top-level + internal so they are unit-testable
// without a DataStore instance). Same manual-JSON style as the endpoint/cert-pin helpers. --

private val nickJson = Json { ignoreUnknownKeys = true }

/** Decode a JSON array of nicks into a set; garbage/absent -> empty. */
internal fun decodeNickSet(raw: String?): Set<String> {
    if (raw == null) return emptySet()
    return runCatching {
        val arr = nickJson.parseToJsonElement(raw) as JsonArray
        arr.map { (it as JsonPrimitive).content }.toSet()
    }.getOrDefault(emptySet())
}

/** Encode a set of nicks as a JSON array (insertion order). */
internal fun encodeNickSet(nicks: Set<String>): String =
    buildJsonArray { for (n in nicks) add(JsonPrimitive(n)) }.toString()

/** Decode a JSON object {"nick": hue} into a map; hues coerced into 0..359, garbage -> empty. */
internal fun decodeHueOverrides(raw: String?): Map<String, Int> {
    if (raw == null) return emptyMap()
    return runCatching {
        val obj = nickJson.parseToJsonElement(raw) as JsonObject
        obj.entries.associate { (k, v) -> k to v.jsonPrimitive.int.coerceIn(0, 359) }
    }.getOrDefault(emptyMap())
}

/** Encode a nick->hue map as a JSON object (hues coerced into 0..359). */
internal fun encodeHueOverrides(map: Map<String, Int>): String =
    buildJsonObject { for ((k, v) in map) put(k, v.coerceIn(0, 359)) }.toString()

// Implements SettingsRepository, PushPrefs, and exposes STS-policy JSON storage (internal, for
// WP5) all over the one DataStore. Constructor-injectable so WP10 can rebind the interfaces.
@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository, PushPrefs, PushProviderPrefs, CertTrustStore {
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
            // Round 4: invalid enum strings fall back to defaults.
            layoutDensity = prefs[PrefKeys.LAYOUT_DENSITY]?.let { runCatching { LayoutDensity.valueOf(it) }.getOrNull() }
                ?: LayoutDensity.COMFORTABLE,
            nickColorsEnabled = prefs[PrefKeys.NICK_COLORS_ENABLED]?.toBooleanStrictOrNull() ?: true,
            nickColorPalette = prefs[PrefKeys.NICK_COLOR_PALETTE]?.let { runCatching { NickColorPalette.valueOf(it) }.getOrNull() }
                ?: NickColorPalette.DEFAULT,
            nickColorOverrides = decodeHueOverrides(prefs[PrefKeys.NICK_COLOR_OVERRIDES]),
            friends = decodeNickSet(prefs[PrefKeys.FRIEND_NICKS]),
            fools = decodeNickSet(prefs[PrefKeys.FOOL_NICKS]),
            foolsMode = prefs[PrefKeys.FOOLS_MODE]?.let { runCatching { FoolsMode.valueOf(it) }.getOrNull() }
                ?: FoolsMode.COLLAPSE,
            showJoinPartQuit = prefs[PrefKeys.SHOW_JOIN_PART_QUIT]?.toBooleanStrictOrNull() ?: true,
            avatarStyle = avatarStyleFromPreference(prefs[PrefKeys.AVATAR_STYLE]),
            chatWallpaper = prefs[PrefKeys.CHAT_WALLPAPER]?.let { runCatching { ChatWallpaper.valueOf(it) }.getOrNull() }
                ?: ChatWallpaper.NONE,
            showComposerEmoji = prefs[PrefKeys.SHOW_COMPOSER_EMOJI]?.toBooleanStrictOrNull() ?: true,
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

    // -- Round 4 (plans/13): appearance/behavior settings --

    override suspend fun setLayoutDensity(d: LayoutDensity) {
        store.edit { it[PrefKeys.LAYOUT_DENSITY] = d.name }
    }

    override suspend fun setNickColorsEnabled(enabled: Boolean) {
        store.edit { it[PrefKeys.NICK_COLORS_ENABLED] = enabled.toString() }
    }

    override suspend fun setNickColorPalette(p: NickColorPalette) {
        store.edit { it[PrefKeys.NICK_COLOR_PALETTE] = p.name }
    }

    override suspend fun setNickColorOverride(nick: String, hue: Int?) {
        val key = normalizeNick(nick)
        store.edit { prefs ->
            val current = decodeHueOverrides(prefs[PrefKeys.NICK_COLOR_OVERRIDES]).toMutableMap()
            if (hue == null) current.remove(key) else current[key] = hue.coerceIn(0, 359)
            if (current.isEmpty()) prefs.remove(PrefKeys.NICK_COLOR_OVERRIDES)
            else prefs[PrefKeys.NICK_COLOR_OVERRIDES] = encodeHueOverrides(current)
        }
    }

    override suspend fun setFriend(nick: String, isFriend: Boolean) {
        val key = normalizeNick(nick)
        // One transaction keeps friends/fools disjoint: adding a friend drops it from fools.
        store.edit { prefs ->
            val friends = decodeNickSet(prefs[PrefKeys.FRIEND_NICKS]).toMutableSet()
            val fools = decodeNickSet(prefs[PrefKeys.FOOL_NICKS]).toMutableSet()
            if (isFriend) {
                friends.add(key)
                fools.remove(key)
            } else {
                friends.remove(key)
            }
            writeNickSet(prefs, PrefKeys.FRIEND_NICKS, friends)
            writeNickSet(prefs, PrefKeys.FOOL_NICKS, fools)
        }
    }

    override suspend fun setFool(nick: String, isFool: Boolean) {
        val key = normalizeNick(nick)
        store.edit { prefs ->
            val friends = decodeNickSet(prefs[PrefKeys.FRIEND_NICKS]).toMutableSet()
            val fools = decodeNickSet(prefs[PrefKeys.FOOL_NICKS]).toMutableSet()
            if (isFool) {
                fools.add(key)
                friends.remove(key)
            } else {
                fools.remove(key)
            }
            writeNickSet(prefs, PrefKeys.FRIEND_NICKS, friends)
            writeNickSet(prefs, PrefKeys.FOOL_NICKS, fools)
        }
    }

    override suspend fun setFoolsMode(m: FoolsMode) {
        store.edit { it[PrefKeys.FOOLS_MODE] = m.name }
    }

    override suspend fun setShowJoinPartQuit(show: Boolean) {
        store.edit { it[PrefKeys.SHOW_JOIN_PART_QUIT] = show.toString() }
    }

    override suspend fun setAvatarStyle(style: AvatarStyle) {
        store.edit { it[PrefKeys.AVATAR_STYLE] = style.name }
    }

    override suspend fun setChatWallpaper(w: ChatWallpaper) {
        store.edit { it[PrefKeys.CHAT_WALLPAPER] = w.name }
    }

    override suspend fun setShowComposerEmoji(show: Boolean) {
        store.edit { it[PrefKeys.SHOW_COMPOSER_EMOJI] = show.toString() }
    }

    // Empty set removes its key (mirrors setEndpointFor); non-empty writes the JSON array.
    private fun writeNickSet(prefs: androidx.datastore.preferences.core.MutablePreferences, key: Preferences.Key<String>, nicks: Set<String>) {
        if (nicks.isEmpty()) prefs.remove(key) else prefs[key] = encodeNickSet(nicks)
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

    // -- PushProviderPrefs --

    override val provider: Flow<PushProvider> = store.data.map { prefs ->
        prefs[PrefKeys.PUSH_PROVIDER]
            ?.let { runCatching { PushProvider.valueOf(it) }.getOrNull() }
            ?: PushProvider.UNIFIED_PUSH
    }

    override suspend fun setProvider(provider: PushProvider) {
        store.edit { it[PrefKeys.PUSH_PROVIDER] = provider.name }
    }

    override suspend fun fcmSubscriptions(): Map<Long, FcmSubscription> =
        decodeFcmSubscriptions(store.data.first()[PrefKeys.FCM_SUBSCRIPTIONS])

    override suspend fun setFcmSubscription(networkId: Long, subscription: FcmSubscription?) {
        store.edit { prefs ->
            val current = decodeFcmSubscriptions(prefs[PrefKeys.FCM_SUBSCRIPTIONS]).toMutableMap()
            if (subscription == null) current.remove(networkId) else current[networkId] = subscription
            if (current.isEmpty()) prefs.remove(PrefKeys.FCM_SUBSCRIPTIONS)
            else prefs[PrefKeys.FCM_SUBSCRIPTIONS] = encodeFcmSubscriptions(current)
        }
    }

    private fun decodeFcmSubscriptions(raw: String?): Map<Long, FcmSubscription> {
        if (raw == null) return emptyMap()
        return runCatching {
            val root = json.parseToJsonElement(raw) as JsonObject
            root.entries.mapNotNull { (key, value) ->
                val id = key.toLongOrNull() ?: return@mapNotNull null
                val obj = value as? JsonObject ?: return@mapNotNull null
                id to FcmSubscription(
                    networkId = id,
                    endpoint = obj.getValue("endpoint").jsonPrimitive.content,
                    subscriptionId = obj.getValue("subscriptionId").jsonPrimitive.content,
                    managementSecret = obj.getValue("managementSecret").jsonPrimitive.content,
                    token = obj.getValue("token").jsonPrimitive.content,
                )
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun encodeFcmSubscriptions(map: Map<Long, FcmSubscription>): String =
        buildJsonObject {
            for ((id, subscription) in map) {
                put(id.toString(), buildJsonObject {
                    put("endpoint", subscription.endpoint)
                    put("subscriptionId", subscription.subscriptionId)
                    put("managementSecret", subscription.managementSecret)
                    put("token", subscription.token)
                })
            }
        }.toString()

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
