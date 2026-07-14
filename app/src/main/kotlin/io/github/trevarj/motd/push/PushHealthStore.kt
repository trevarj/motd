package io.github.trevarj.motd.push

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private val Context.pushHealthDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "push_health")

enum class PushCapability { UNKNOWN, SUPPORTED, UNSUPPORTED }
enum class PushRegistrationState { WAITING_FOR_ENDPOINT, VERIFYING, ACTIVE, FALLBACK }

/** Durable, redacted state used to decide whether a network may safely drop its IRC socket. */
data class NetworkPushHealth(
    val endpointFingerprint: String? = null,
    val capability: PushCapability = PushCapability.UNKNOWN,
    val registrationState: PushRegistrationState = PushRegistrationState.WAITING_FOR_ENDPOINT,
    val registeredAt: Long? = null,
    val probeAt: Long? = null,
    val lastDeliveryAt: Long? = null,
    val errorCode: String? = null,
    val errorAt: Long? = null,
) {
    fun protects(endpoint: String?): Boolean =
        endpoint != null && registrationState == PushRegistrationState.ACTIVE &&
            endpointFingerprint == fingerprintEndpoint(endpoint)
}

interface PushHealthStore {
    val health: Flow<Map<Long, NetworkPushHealth>>
    suspend fun snapshot(): Map<Long, NetworkPushHealth>
    suspend fun endpointReceived(networkId: Long, endpoint: String)
    suspend fun capability(networkId: Long, supported: Boolean)
    suspend fun verifying(networkId: Long)
    suspend fun registered(networkId: Long)
    suspend fun probeDelivered(networkId: Long)
    suspend fun messageDelivered(networkId: Long)
    suspend fun failed(networkId: Long, code: String)
    suspend fun warning(networkId: Long, code: String)
    suspend fun clear(networkId: Long)
    suspend fun retain(networkIds: Set<Long>)
}

internal object NoopPushHealthStore : PushHealthStore {
    override val health: Flow<Map<Long, NetworkPushHealth>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    override suspend fun snapshot() = emptyMap<Long, NetworkPushHealth>()
    override suspend fun endpointReceived(networkId: Long, endpoint: String) = Unit
    override suspend fun capability(networkId: Long, supported: Boolean) = Unit
    override suspend fun verifying(networkId: Long) = Unit
    override suspend fun registered(networkId: Long) = Unit
    override suspend fun probeDelivered(networkId: Long) = Unit
    override suspend fun messageDelivered(networkId: Long) = Unit
    override suspend fun failed(networkId: Long, code: String) = Unit
    override suspend fun warning(networkId: Long, code: String) = Unit
    override suspend fun clear(networkId: Long) = Unit
    override suspend fun retain(networkIds: Set<Long>) = Unit
}

@Singleton
class DataStorePushHealthStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : PushHealthStore {
    private val key = stringPreferencesKey("network_health")
    private val json = Json { ignoreUnknownKeys = true }
    private val store get() = context.pushHealthDataStore

    override val health: Flow<Map<Long, NetworkPushHealth>> = store.data.map { decode(it[key]) }

    override suspend fun snapshot(): Map<Long, NetworkPushHealth> = health.first()

    override suspend fun endpointReceived(networkId: Long, endpoint: String) = update(networkId) { old ->
        val fingerprint = fingerprintEndpoint(endpoint)
        if (old.endpointFingerprint == fingerprint) {
            old.copy(errorCode = null, errorAt = null)
        } else {
            NetworkPushHealth(endpointFingerprint = fingerprint)
        }
    }

    override suspend fun capability(networkId: Long, supported: Boolean) = update(networkId) { old ->
        old.copy(
            capability = if (supported) PushCapability.SUPPORTED else PushCapability.UNSUPPORTED,
            registrationState = if (supported) old.registrationState else PushRegistrationState.FALLBACK,
            errorCode = if (supported) null else "WEBPUSH_UNSUPPORTED",
            errorAt = if (supported) null else System.currentTimeMillis(),
        )
    }

    override suspend fun verifying(networkId: Long) = update(networkId) { old ->
        old.copy(
            capability = PushCapability.SUPPORTED,
            registrationState = PushRegistrationState.VERIFYING,
            errorCode = null,
            errorAt = null,
        )
    }

    override suspend fun registered(networkId: Long) = update(networkId) { old ->
        old.copy(
            capability = PushCapability.SUPPORTED,
            registrationState = PushRegistrationState.ACTIVE,
            registeredAt = System.currentTimeMillis(),
            errorCode = null,
            errorAt = null,
        )
    }

    override suspend fun probeDelivered(networkId: Long) = update(networkId) { old ->
        old.copy(probeAt = System.currentTimeMillis(), errorCode = null, errorAt = null)
    }

    override suspend fun messageDelivered(networkId: Long) = update(networkId) { old ->
        old.copy(lastDeliveryAt = System.currentTimeMillis(), errorCode = null, errorAt = null)
    }

    override suspend fun failed(networkId: Long, code: String) = update(networkId) { old ->
        old.copy(
            registrationState = PushRegistrationState.FALLBACK,
            errorCode = code.take(64),
            errorAt = System.currentTimeMillis(),
        )
    }

    override suspend fun warning(networkId: Long, code: String) = update(networkId) { old ->
        old.copy(errorCode = code.take(64), errorAt = System.currentTimeMillis())
    }

    override suspend fun clear(networkId: Long) {
        store.edit { prefs ->
            val current = decode(prefs[key]).toMutableMap()
            current.remove(networkId)
            write(prefs, current)
        }
    }

    override suspend fun retain(networkIds: Set<Long>) {
        store.edit { prefs ->
            write(prefs, decode(prefs[key]).filterKeys { it in networkIds })
        }
    }

    private suspend fun update(networkId: Long, transform: (NetworkPushHealth) -> NetworkPushHealth) {
        store.edit { prefs ->
            val current = decode(prefs[key]).toMutableMap()
            current[networkId] = transform(current[networkId] ?: NetworkPushHealth())
            write(prefs, current)
        }
    }

    private fun write(prefs: androidx.datastore.preferences.core.MutablePreferences, value: Map<Long, NetworkPushHealth>) {
        if (value.isEmpty()) prefs.remove(key) else prefs[key] = encode(value)
    }

    private fun decode(raw: String?): Map<Long, NetworkPushHealth> {
        if (raw == null) return emptyMap()
        return runCatching {
            (json.parseToJsonElement(raw) as JsonObject).mapNotNull { (idText, element) ->
                val id = idText.toLongOrNull() ?: return@mapNotNull null
                val obj = element as? JsonObject ?: return@mapNotNull null
                id to NetworkPushHealth(
                    endpointFingerprint = obj.string("endpointFingerprint"),
                    capability = obj.string("capability")?.let { runCatching { PushCapability.valueOf(it) }.getOrNull() }
                        ?: PushCapability.UNKNOWN,
                    registrationState = obj.string("registrationState")
                        ?.let { runCatching { PushRegistrationState.valueOf(it) }.getOrNull() }
                        ?: PushRegistrationState.WAITING_FOR_ENDPOINT,
                    registeredAt = obj.long("registeredAt"),
                    probeAt = obj.long("probeAt"),
                    lastDeliveryAt = obj.long("lastDeliveryAt"),
                    errorCode = obj.string("errorCode"),
                    errorAt = obj.long("errorAt"),
                )
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun encode(value: Map<Long, NetworkPushHealth>): String = buildJsonObject {
        value.forEach { (id, health) ->
            put(id.toString(), buildJsonObject {
                health.endpointFingerprint?.let { put("endpointFingerprint", it) }
                put("capability", health.capability.name)
                put("registrationState", health.registrationState.name)
                health.registeredAt?.let { put("registeredAt", it) }
                health.probeAt?.let { put("probeAt", it) }
                health.lastDeliveryAt?.let { put("lastDeliveryAt", it) }
                health.errorCode?.let { put("errorCode", it) }
                health.errorAt?.let { put("errorAt", it) }
            })
        }
    }.toString()
}

internal fun fingerprintEndpoint(endpoint: String): String =
    MessageDigest.getInstance("SHA-256").digest(endpoint.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun JsonObject.string(name: String): String? = get(name)?.jsonPrimitive?.content
private fun JsonObject.long(name: String): Long? = get(name)?.jsonPrimitive?.longOrNull

internal fun socketFallbackNetworkIds(
    networks: List<NetworkEntity>,
    endpoints: Map<Long, String>,
    health: Map<Long, NetworkPushHealth>,
): Set<Long> = networks.asSequence()
    .filter { it.autoConnect && it.role != NetworkRole.BOUNCER_ROOT }
    .filterNot { health[it.id]?.protects(endpoints[it.id]) == true }
    .map { it.id }
    .toSet()

internal fun pushSuspendedNetworkIds(
    networks: List<NetworkEntity>,
    wantedIds: Set<Long>,
    endpoints: Map<Long, String>,
    health: Map<Long, NetworkPushHealth>,
): Set<Long> = networks.asSequence()
    .filter { it.id in wantedIds }
    .filter {
        it.role == NetworkRole.BOUNCER_ROOT || health[it.id]?.protects(endpoints[it.id]) == true
    }
    .map { it.id }
    .toSet()
