package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatSoundsRepositoryTest {
    private val repository: SettingsRepository =
        DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun chat_sounds_default_to_enabled() {
        assertTrue(Settings().chatSoundsEnabled)
    }

    @Test
    fun chat_sounds_round_trip() = runTest {
        repository.setChatSoundsEnabled(false)
        assertFalse(repository.settings.first().chatSoundsEnabled)

        repository.setChatSoundsEnabled(true)
        assertTrue(repository.settings.first().chatSoundsEnabled)
    }
}
