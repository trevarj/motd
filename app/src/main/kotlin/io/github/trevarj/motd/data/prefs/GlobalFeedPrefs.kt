package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Own store, isolated from Settings exports so restoring configuration cannot enable this lab
// (same rule as `GesturePrefs`).
private val Context.globalFeedDataStore by preferencesDataStore("global_feed_labs")
private val ENABLED = booleanPreferencesKey("enabled_v1")

/** Preferences for the experimental Global Feed lab (the merged cross-buffer stream). */
interface GlobalFeedPrefs {
    /** Whether the Global Feed lab is switched on. Defaults to false, so its entry points hide. */
    val enabled: Flow<Boolean>

    suspend fun setEnabled(enabled: Boolean)
}

@Singleton
class GlobalFeedPrefsImpl
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : GlobalFeedPrefs {
        private val store = context.globalFeedDataStore

        override val enabled: Flow<Boolean> = store.data.map { it[ENABLED] ?: false }

        override suspend fun setEnabled(enabled: Boolean) {
            store.edit { it[ENABLED] = enabled }
        }
    }
