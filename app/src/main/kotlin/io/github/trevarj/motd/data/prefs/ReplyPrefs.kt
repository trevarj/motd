package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/** App-owned reply preferences kept outside the frozen settings contract. */
@Serializable
data class ReplyConfig(
    val visibleChannelPrefix: Boolean = false,
    val swipeToReplyEnabled: Boolean = true,
)

interface ReplyPrefs {
    val config: Flow<ReplyConfig>

    suspend fun setVisibleChannelPrefix(enabled: Boolean)

    suspend fun setSwipeToReplyEnabled(enabled: Boolean)
}

private val Context.replyDataStore by preferencesDataStore("replies")
private val VISIBLE_CHANNEL_PREFIX = booleanPreferencesKey("visible_channel_prefix_v1")
private val SWIPE_TO_REPLY_ENABLED = booleanPreferencesKey("swipe_to_reply_enabled_v1")

@Singleton
class ReplyPrefsImpl
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : ReplyPrefs {
        private val store = context.replyDataStore

        override val config: Flow<ReplyConfig> =
            store.data.map { prefs ->
                ReplyConfig(
                    visibleChannelPrefix = prefs[VISIBLE_CHANNEL_PREFIX] ?: false,
                    swipeToReplyEnabled = prefs[SWIPE_TO_REPLY_ENABLED] ?: true,
                )
            }

        override suspend fun setVisibleChannelPrefix(enabled: Boolean) {
            store.edit { it[VISIBLE_CHANNEL_PREFIX] = enabled }
        }

        override suspend fun setSwipeToReplyEnabled(enabled: Boolean) {
            store.edit { it[SWIPE_TO_REPLY_ENABLED] = enabled }
        }
    }
