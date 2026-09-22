package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    fun chat_sounds_round_trip() =
        runTest {
            repository.setChatSoundsEnabled(false)
            assertFalse(repository.settings.first().chatSoundsEnabled)

            repository.setChatSoundsEnabled(true)
            assertTrue(repository.settings.first().chatSoundsEnabled)
        }

    @Test
    fun configurable_chat_sounds_are_normalized_and_persisted_separately() =
        runTest {
            val prefs = ChatSoundPrefs(ApplicationProvider.getApplicationContext<Context>())
            repository.setChatSoundsEnabled(false)
            prefs.replace(ChatSoundConfig(masterVolume = 170, send = ChatSoundCueConfig(volume = -1, pitch = -8)))
            prefs.update { it.copy(masterVolume = 41) }
            prefs.update { it.copy(receive = it.receive.copy(pitch = 3)) }

            val restored = prefs.config.first()
            assertEquals(41, restored.masterVolume)
            assertEquals(0, restored.send.volume)
            assertEquals(-4, restored.send.pitch)
            assertEquals(3, restored.receive.pitch)
            assertFalse(repository.settings.first().chatSoundsEnabled)
        }
}
