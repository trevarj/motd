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

/** Independent network-content gates. Both display gates default on for existing and fresh installs. */
@Serializable
data class ContentPreviewConfig(
    val showImages: Boolean = true,
    val showLinkPreviews: Boolean = true,
    val autoLoadOnUnmetered: Boolean = true,
    val autoLoadOnMetered: Boolean = true,
)

interface ContentPreviewPrefs {
    val config: Flow<ContentPreviewConfig>

    suspend fun setShowImages(show: Boolean)

    suspend fun setShowLinkPreviews(show: Boolean)

    suspend fun setAutoLoadOnUnmetered(enabled: Boolean)

    suspend fun setAutoLoadOnMetered(enabled: Boolean)
}

private val Context.contentPreviewDataStore by preferencesDataStore("content_previews")
private val SHOW_IMAGES = booleanPreferencesKey("show_images")
private val SHOW_LINK_PREVIEWS = booleanPreferencesKey("show_link_previews")
private val AUTO_LOAD_ON_UNMETERED = booleanPreferencesKey("auto_load_on_unmetered")
private val AUTO_LOAD_ON_METERED = booleanPreferencesKey("auto_load_on_metered")

@Singleton
class ContentPreviewPrefsImpl
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : ContentPreviewPrefs {
        private val store = context.contentPreviewDataStore

        override val config: Flow<ContentPreviewConfig> =
            store.data.map { prefs ->
                ContentPreviewConfig(
                    showImages = prefs[SHOW_IMAGES] ?: true,
                    showLinkPreviews = prefs[SHOW_LINK_PREVIEWS] ?: true,
                    autoLoadOnUnmetered = prefs[AUTO_LOAD_ON_UNMETERED] ?: true,
                    autoLoadOnMetered = prefs[AUTO_LOAD_ON_METERED] ?: true,
                )
            }

        override suspend fun setShowImages(show: Boolean) {
            store.edit { it[SHOW_IMAGES] = show }
        }

        override suspend fun setShowLinkPreviews(show: Boolean) {
            store.edit { it[SHOW_LINK_PREVIEWS] = show }
        }

        override suspend fun setAutoLoadOnUnmetered(enabled: Boolean) {
            store.edit { it[AUTO_LOAD_ON_UNMETERED] = enabled }
        }

        override suspend fun setAutoLoadOnMetered(enabled: Boolean) {
            store.edit { it[AUTO_LOAD_ON_METERED] = enabled }
        }
    }
