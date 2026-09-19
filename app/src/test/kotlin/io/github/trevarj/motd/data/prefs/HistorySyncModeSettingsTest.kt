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
class HistorySyncModeSettingsTest {
    private val repository: SettingsRepository =
        DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun missingAndInvalidValuesPreserveBalancedDefault() {
        assertEquals(HistorySyncMode.BALANCED, historySyncModeFromPreference(null))
        assertEquals(HistorySyncMode.BALANCED, historySyncModeFromPreference("FUTURE_MODE"))
        assertEquals(HistorySyncMode.BALANCED, Json.decodeFromString<Settings>("{}").historySyncMode)
    }

    @Test
    fun savedModeRoundTrips() =
        runTest {
            repository.setHistorySyncMode(HistorySyncMode.AGGRESSIVE)
            assertEquals(HistorySyncMode.AGGRESSIVE, repository.settings.first().historySyncMode)
            repository.setHistorySyncMode(HistorySyncMode.BALANCED)
        }
}
