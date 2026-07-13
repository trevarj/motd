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

private val Context.contentPreviewDataStore by preferencesDataStore("content_previews")
private val SHOW_IMAGES = booleanPreferencesKey("show_images")
private val SHOW_LINK_PREVIEWS = booleanPreferencesKey("show_link_previews")

@Singleton
class ContentPreviewPrefsImpl @Inject constructor(
    @ApplicationContext context: Context,
) : ContentPreviewPrefs {
    private val store = context.contentPreviewDataStore

    override val config: Flow<ContentPreviewConfig> = store.data.map { prefs ->
        ContentPreviewConfig(
            showImages = prefs[SHOW_IMAGES] ?: true,
            showLinkPreviews = prefs[SHOW_LINK_PREVIEWS] ?: true,
        )
    }

    override suspend fun setShowImages(show: Boolean) {
        store.edit { it[SHOW_IMAGES] = show }
    }

    override suspend fun setShowLinkPreviews(show: Boolean) {
        store.edit { it[SHOW_LINK_PREVIEWS] = show }
    }
}
