package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val Context.historySyncDataStore by preferencesDataStore("history_sync")

@Singleton
class HistorySyncPrefsImpl @Inject constructor(
    @ApplicationContext context: Context,
) : HistorySyncPrefs {
    private val store = context.historySyncDataStore

    override suspend fun lastSuccessfulSync(networkId: Long): Long? =
        store.data.first()[key(networkId)]

    override suspend fun setLastSuccessfulSync(networkId: Long, timestamp: Long) {
        store.edit { it[key(networkId)] = timestamp }
    }

    override suspend fun clear(networkId: Long) {
        store.edit { it.remove(key(networkId)) }
    }

    private fun key(networkId: Long) = longPreferencesKey("network_${networkId}_last_successful_sync")
}
