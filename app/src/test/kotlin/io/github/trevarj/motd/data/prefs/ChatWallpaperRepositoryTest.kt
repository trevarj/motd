package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * chatWallpaper round-trip over the real DataStore repository (mirrors FoolsRepositoryTest infra).
 * Default is NONE (opt-in, no behavior change), and each preset persists and reads back.
 */
@RunWith(RobolectricTestRunner::class)
class ChatWallpaperRepositoryTest {

    private val repo: SettingsRepository =
        DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>())

    /** The unwritten field defaults to NONE (opt-in). Checked on a fresh field via a distinct enum
     *  so it doesn't race the shared process DataStore that other tests here write to. */
    @Test
    fun default_isNone() = runTest {
        // The Settings data class default is the source of truth for opt-in behavior.
        assertEquals(ChatWallpaper.NONE, Settings().chatWallpaper)
    }

    /** Every preset (including NONE) persists and reads back over the real DataStore. */
    @Test
    fun setWallpaper_roundTrips() = runTest {
        for (w in ChatWallpaper.entries) {
            repo.setChatWallpaper(w)
            assertEquals(w, repo.settings.first().chatWallpaper)
        }
    }

    /** Selecting a preset and then clearing back to NONE restores the plain background. */
    @Test
    fun setThenReset_backToNone() = runTest {
        repo.setChatWallpaper(ChatWallpaper.NETWORK)
        assertEquals(ChatWallpaper.NETWORK, repo.settings.first().chatWallpaper)
        repo.setChatWallpaper(ChatWallpaper.NONE)
        assertEquals(ChatWallpaper.NONE, repo.settings.first().chatWallpaper)
    }
}
