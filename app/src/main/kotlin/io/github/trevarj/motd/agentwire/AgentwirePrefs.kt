package io.github.trevarj.motd.agentwire

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.agentwireDataStore by preferencesDataStore("agentwire_labs")
private val ENABLED = booleanPreferencesKey("enabled_v1")
private val DEVICE = stringPreferencesKey("device_v1")

// Recents are per channel: one channel binds one session, and the drawer only offers this
// channel's history.
private fun recentSessionsKey(channel: String) = stringPreferencesKey("recent_sessions_v1:$channel")

/** Isolated from Settings exports so restoring normal configuration cannot enable this lab. */
@Singleton
class AgentwirePrefs @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.agentwireDataStore
    val enabled: Flow<Boolean> = store.data.map { it[ENABLED] ?: false }

    suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[ENABLED] = enabled }
    }

    fun recentSessions(channel: String): Flow<List<AgentwireRecentSession>> = store.data.map {
        decodeAgentwireRecents(it[recentSessionsKey(channel)])
    }

    suspend fun addRecentSession(channel: String, sid: String, title: String, cwd: String?, backend: String?) {
        if (channel.isBlank()) return
        val key = recentSessionsKey(channel)
        store.edit { preferences ->
            preferences[key] = encodeAgentwireRecents(
                agentwireRecentsWith(
                    decodeAgentwireRecents(preferences[key]),
                    AgentwireRecentSession(sid, title, cwd, backend),
                ),
            )
        }
    }

    suspend fun deviceId(): String {
        store.data.first()[DEVICE]?.let { return it }
        val created = UUID.randomUUID().toString()
        store.edit { preferences ->
            if (preferences[DEVICE] == null) preferences[DEVICE] = created
        }
        return store.data.first()[DEVICE] ?: created
    }
}
