package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FolderDisplayModeSettingsTest {
    private val repository: SettingsRepository =
        DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun freshAndOldSettingsPreserveFolderDefaults() =
        runTest {
            repository.setFolderDisplayMode(FolderDisplayMode.INLINE)
            repository.setShowFolderChatsInAll(true)

            assertEquals(FolderDisplayMode.INLINE, Settings().folderDisplayMode)
            assertEquals(FolderDisplayMode.INLINE, repository.settings.first().folderDisplayMode)
            assertEquals(FolderDisplayMode.INLINE, Json.decodeFromString<Settings>("{}").folderDisplayMode)
            assertEquals(true, Settings().showFolderChatsInAll)
            assertEquals(true, repository.settings.first().showFolderChatsInAll)
            assertEquals(true, Json.decodeFromString<Settings>("{}").showFolderChatsInAll)
        }

    @Test
    fun tabsPreferenceRoundTrips() =
        runTest {
            try {
                repository.setFolderDisplayMode(FolderDisplayMode.TABS)
                assertEquals(FolderDisplayMode.TABS, repository.settings.first().folderDisplayMode)
            } finally {
                repository.setFolderDisplayMode(FolderDisplayMode.INLINE)
            }
        }

    @Test
    fun showFolderChatsInAllPreferenceRoundTrips() =
        runTest {
            try {
                repository.setShowFolderChatsInAll(false)
                assertEquals(false, repository.settings.first().showFolderChatsInAll)
            } finally {
                repository.setShowFolderChatsInAll(true)
            }
        }

    @Test
    fun seasonalWordmarksPreferenceDefaultsOnAndRoundTrips() =
        runTest {
            assertEquals(true, repository.settings.first().seasonalWordmarksEnabled)
            try {
                repository.setSeasonalWordmarksEnabled(false)
                assertEquals(false, repository.settings.first().seasonalWordmarksEnabled)
            } finally {
                repository.setSeasonalWordmarksEnabled(true)
            }
            assertEquals(true, repository.settings.first().seasonalWordmarksEnabled)
        }

    @Test
    fun invalidPreferenceDefaultsInline() {
        assertEquals(FolderDisplayMode.INLINE, folderDisplayModeFromPreference("GRID"))
        assertEquals(FolderDisplayMode.INLINE, folderDisplayModeFromPreference(null))
    }
}
