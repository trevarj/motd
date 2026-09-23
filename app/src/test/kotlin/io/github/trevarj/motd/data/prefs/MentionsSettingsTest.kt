package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MentionsSettingsTest {
    @Test
    fun freshAndAbsentPreferencesShowMentionsInTheDrawer() {
        assertTrue(Settings().mentionsEnabled)
        assertEquals(MentionsPlacement.DRAWER, Settings().mentionsPlacement)
        assertTrue(Json.decodeFromString<Settings>("{}").mentionsEnabled)
        assertEquals(MentionsPlacement.DRAWER, Json.decodeFromString<Settings>("{}").mentionsPlacement)
        assertTrue(mentionsEnabledFromPreference(null))
        assertTrue(mentionsEnabledFromPreference("not-a-boolean"))
        assertEquals(MentionsPlacement.DRAWER, mentionsPlacementFromPreference(null))
        assertEquals(MentionsPlacement.DRAWER, mentionsPlacementFromPreference("SIDEBAR"))
    }

    @Test
    fun enabledStateAndEveryPlacementRoundTripThroughDataStoreAndSettingsSerialization() =
        runTest {
            val repository = DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>())
            try {
                repository.setMentionsEnabled(false)
                assertEquals(false, repository.settings.first().mentionsEnabled)

                repository.setMentionsEnabled(true)
                MentionsPlacement.entries.forEach { placement ->
                    repository.setMentionsPlacement(placement)
                    val saved = repository.settings.first()
                    assertEquals(true, saved.mentionsEnabled)
                    assertEquals(placement, saved.mentionsPlacement)
                    assertEquals(placement, Json.decodeFromString<Settings>(Json.encodeToString(saved)).mentionsPlacement)
                }
            } finally {
                repository.setMentionsEnabled(true)
                repository.setMentionsPlacement(MentionsPlacement.DRAWER)
            }
        }
}
