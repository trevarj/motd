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

private val Context.ebooksLabsDataStore by preferencesDataStore("ebooks_labs")
private val ENABLED = booleanPreferencesKey("enabled_v1")

@Singleton
open class EbooksLabsPrefs
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        open val enabled: Flow<Boolean>
            get() = context.ebooksLabsDataStore.data.map { it[ENABLED] ?: false }

        open suspend fun setEnabled(enabled: Boolean) {
            context.ebooksLabsDataStore.edit { it[ENABLED] = enabled }
        }
    }
