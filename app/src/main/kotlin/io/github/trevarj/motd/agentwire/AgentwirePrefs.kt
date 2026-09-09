package io.github.trevarj.motd.agentwire

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.agentwireDataStore by preferencesDataStore("agentwire_labs")
private val ENABLED = booleanPreferencesKey("enabled_v1")
private val DEVICE = stringPreferencesKey("device_v1")
private val RECEIPTS = stringPreferencesKey("action_receipts_v1")
private const val ACTION_RECEIPT_LIMIT = 1_000
private const val ACTION_RECEIPT_AGE_MS = 30L * 24 * 60 * 60 * 1_000

@Serializable
data class AgentwireActionReceipt(
    val id: String,
    val kind: String,
    val channel: String,
    val sid: String? = null,
    val sentAt: Long,
    val outcome: String,
    /** SHA-256 of network/controller/backend identity; never raw trust data. */
    val scope: String,
)

private val receiptJson = Json { ignoreUnknownKeys = true }

internal fun decodeAgentwireReceipts(raw: String?): List<AgentwireActionReceipt> =
    raw
        ?.let { runCatching { receiptJson.decodeFromString<List<AgentwireActionReceipt>>(it) }.getOrNull() }
        .orEmpty()

internal fun encodeAgentwireReceipts(receipts: List<AgentwireActionReceipt>): String = receiptJson.encodeToString(receipts)

internal fun pruneAgentwireReceipts(
    receipts: List<AgentwireActionReceipt>,
    now: Long,
): List<AgentwireActionReceipt> = receipts.filter { now - it.sentAt <= ACTION_RECEIPT_AGE_MS }

/** Retention is pure so the durable-store invariant can be tested independently of Android IO. */
internal fun agentwireReceiptsWith(
    existing: List<AgentwireActionReceipt>,
    receipt: AgentwireActionReceipt,
    now: Long,
): List<AgentwireActionReceipt> {
    val retained = pruneAgentwireReceipts(existing, now).filterNot { it.id == receipt.id && it.scope == receipt.scope }
    val bounded = (retained + receipt).sortedByDescending { it.sentAt }.toMutableList()
    while (bounded.size > ACTION_RECEIPT_LIMIT) {
        // Unknown actions still need recovery. Only a final outcome may make room.
        val terminal = bounded.indexOfLast { it.outcome in setOf("succeeded", "failed", "uncertain") }
        if (terminal < 0) break
        bounded.removeAt(terminal)
    }
    check(bounded.size <= ACTION_RECEIPT_LIMIT) { "Too many unresolved Agentwire actions" }
    return bounded
}

// Recents are per channel: one channel binds one session, and the drawer only offers this
// channel's history.
private fun recentSessionsKey(channel: String) = stringPreferencesKey("recent_sessions_v1:$channel")

/** Isolated from Settings exports so restoring normal configuration cannot enable this lab. */
@Singleton
open class AgentwirePrefs
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val store = context.agentwireDataStore
        open val enabled: Flow<Boolean> = store.data.map { it[ENABLED] ?: false }

        open suspend fun setEnabled(enabled: Boolean) {
            store.edit { it[ENABLED] = enabled }
        }

        open fun recentSessions(channel: String): Flow<List<AgentwireRecentSession>> = store.data.map { decodeAgentwireRecents(it[recentSessionsKey(channel)]) }

        open suspend fun addRecentSession(
            channel: String,
            sid: String,
            title: String,
            cwd: String?,
            backend: String?,
        ) {
            if (channel.isBlank()) return
            val key = recentSessionsKey(channel)
            store.edit { preferences ->
                preferences[key] =
                    encodeAgentwireRecents(
                        agentwireRecentsWith(
                            decodeAgentwireRecents(preferences[key]),
                            AgentwireRecentSession(sid, title, cwd, backend),
                        ),
                    )
            }
        }

        open fun actionReceipts(): Flow<List<AgentwireActionReceipt>> = store.data.map { pruneAgentwireReceipts(decodeAgentwireReceipts(it[RECEIPTS]), System.currentTimeMillis()) }

        /** Atomically writes the receipt before an action can leave the device. */
        open suspend fun recordAction(receipt: AgentwireActionReceipt) {
            store.edit { preferences ->
                val now = System.currentTimeMillis()
                preferences[RECEIPTS] = encodeAgentwireReceipts(agentwireReceiptsWith(decodeAgentwireReceipts(preferences[RECEIPTS]), receipt, now))
            }
        }

        open suspend fun updateAction(
            id: String,
            scope: String,
            outcome: String,
        ) {
            store.edit { preferences ->
                preferences[RECEIPTS] =
                    encodeAgentwireReceipts(
                        pruneAgentwireReceipts(decodeAgentwireReceipts(preferences[RECEIPTS]), System.currentTimeMillis()).map {
                            if (it.id == id && it.scope == scope && receiptRank(outcome) >= receiptRank(it.outcome)) {
                                it.copy(outcome = outcome)
                            } else {
                                it
                            }
                        },
                    )
            }
        }

        /** A disconnected delivery may have reached the bridge, so retain it for recovery. */
        open suspend fun markUnresolvedActionsUnknown(scope: String) {
            store.edit { preferences ->
                preferences[RECEIPTS] =
                    encodeAgentwireReceipts(
                        pruneAgentwireReceipts(decodeAgentwireReceipts(preferences[RECEIPTS]), System.currentTimeMillis()).map {
                            if (it.scope == scope && it.outcome in setOf("sent", "accepted")) it.copy(outcome = "unknown") else it
                        },
                    )
            }
        }

        open suspend fun deviceId(): String {
            store.data.first()[DEVICE]?.let { return it }
            val created = UUID.randomUUID().toString()
            store.edit { preferences ->
                if (preferences[DEVICE] == null) preferences[DEVICE] = created
            }
            return store.data.first()[DEVICE] ?: created
        }
    }

private fun receiptRank(outcome: String): Int =
    when (outcome) {
        "unknown" -> 0
        "sent" -> 1
        "accepted" -> 2
        "succeeded", "failed", "uncertain" -> 3
        else -> -1
    }
