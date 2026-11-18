package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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

    @Test
    fun placementDefaultsAndPersistsIndependentlyOfTypingVisibility() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val original = context.settingsDataStore.data.first()
            try {
                assertEquals(TypingIndicatorPlacement.HEADER, Settings().typingIndicatorPlacement)
                assertEquals(TypingIndicatorPlacement.HEADER, Json.decodeFromString<Settings>("{}").typingIndicatorPlacement)
                context.settingsDataStore.edit { it.remove(PrefKeys.TYPING_INDICATOR_PLACEMENT) }
                assertEquals(TypingIndicatorPlacement.HEADER, repository.settings.first().typingIndicatorPlacement)
                context.settingsDataStore.edit { it[PrefKeys.TYPING_INDICATOR_PLACEMENT] = "INVALID" }
                assertEquals(TypingIndicatorPlacement.HEADER, repository.settings.first().typingIndicatorPlacement)
                repository.setSendTypingIndicators(false)
                repository.setShowTypingIndicators(false)
                for (placement in TypingIndicatorPlacement.entries) {
                    repository.setTypingIndicatorPlacement(placement)
                    val restored = DataStoreSettingsRepository(context).settings.first()
                    assertEquals(placement, restored.typingIndicatorPlacement)
                    assertFalse(restored.sendTypingIndicators)
                    assertFalse(restored.showTypingIndicators)
                }
            } finally {
                context.settingsDataStore.edit { prefs ->
                    for (key in listOf(PrefKeys.TYPING_INDICATOR_PLACEMENT, PrefKeys.SEND_TYPING_INDICATORS, PrefKeys.SHOW_TYPING_INDICATORS)) {
                        val value = original[key]
                        if (value == null) prefs.remove(key) else prefs[key] = value
                    }
                }
            }
        }
}
