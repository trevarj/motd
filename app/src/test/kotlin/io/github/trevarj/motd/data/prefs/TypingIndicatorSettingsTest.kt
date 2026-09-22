package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TypingIndicatorSettingsTest {
    private val repository: SettingsRepository =
        DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun typingIndicatorsDefaultOnAndRoundTripIndependently() =
        runTest {
            assertTrue(Settings().sendTypingIndicators)
            assertTrue(Settings().showTypingIndicators)
            assertTrue(Json.decodeFromString<Settings>("{}").sendTypingIndicators)
            assertTrue(Json.decodeFromString<Settings>("{}").showTypingIndicators)

            repository.setSendTypingIndicators(true)
            repository.setShowTypingIndicators(true)
            repository.setSendTypingIndicators(false)
            assertFalse(repository.settings.first().sendTypingIndicators)
            assertTrue(repository.settings.first().showTypingIndicators)

            repository.setShowTypingIndicators(false)
            assertFalse(repository.settings.first().sendTypingIndicators)
            assertFalse(repository.settings.first().showTypingIndicators)

            repository.setSendTypingIndicators(true)
            repository.setShowTypingIndicators(true)
        }
}
