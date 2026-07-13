package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.replyDataStore by preferencesDataStore("replies")
private val VISIBLE_CHANNEL_PREFIX = booleanPreferencesKey("visible_channel_prefix_v1")

@Singleton
class ReplyPrefsImpl @Inject constructor(
    @ApplicationContext context: Context,
) : ReplyPrefs {
    private val store = context.replyDataStore

    override val config: Flow<ReplyConfig> = store.data.map { prefs ->
        ReplyConfig(visibleChannelPrefix = prefs[VISIBLE_CHANNEL_PREFIX] ?: false)
    }

    override suspend fun setVisibleChannelPrefix(enabled: Boolean) {
        store.edit { it[VISIBLE_CHANNEL_PREFIX] = enabled }
    }
}
