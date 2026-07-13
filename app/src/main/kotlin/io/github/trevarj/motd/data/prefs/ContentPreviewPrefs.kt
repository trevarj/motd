package io.github.trevarj.motd.data.prefs

import kotlinx.coroutines.flow.Flow

/** Independent network-content gates. Both default on for existing and fresh installs. */
data class ContentPreviewConfig(
    val showImages: Boolean = true,
    val showLinkPreviews: Boolean = true,
)

interface ContentPreviewPrefs {
    val config: Flow<ContentPreviewConfig>
    suspend fun setShowImages(show: Boolean)
    suspend fun setShowLinkPreviews(show: Boolean)
}
