package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Durable classification for ZNC rows, which intentionally remain DIRECT at the IRC layer. */
interface BouncerKindPrefs {
    val zncNetworkIds: Flow<Set<Long>>
    suspend fun markZnc(networkId: Long)
    suspend fun clear(networkId: Long)
}

private val Context.bouncerKindDataStore by preferencesDataStore("bouncer_kinds")
private val ZNC_NETWORK_IDS = stringSetPreferencesKey("znc_network_ids")

@Singleton
class BouncerKindPrefsImpl @Inject constructor(
    @ApplicationContext context: Context,
) : BouncerKindPrefs {
    private val store = context.bouncerKindDataStore

    override val zncNetworkIds: Flow<Set<Long>> = store.data.map { prefs ->
        prefs[ZNC_NETWORK_IDS].orEmpty().mapNotNullTo(mutableSetOf(), String::toLongOrNull)
    }

    override suspend fun markZnc(networkId: Long) {
        store.edit { prefs ->
            prefs[ZNC_NETWORK_IDS] = prefs[ZNC_NETWORK_IDS].orEmpty() + networkId.toString()
        }
    }

    override suspend fun clear(networkId: Long) {
        store.edit { prefs ->
            prefs[ZNC_NETWORK_IDS] = prefs[ZNC_NETWORK_IDS].orEmpty() - networkId.toString()
        }
    }
}

/** Test/default collaborator for repositories constructed outside Hilt. */
object NoopBouncerKindPrefs : BouncerKindPrefs {
    override val zncNetworkIds: Flow<Set<Long>> = flowOf(emptySet())
    override suspend fun markZnc(networkId: Long) = Unit
    override suspend fun clear(networkId: Long) = Unit
}
