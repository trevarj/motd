package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompactNickBracketsSettingsTest {
    @Test
    fun missingInvalidAndExplicitPreferencesRoundTrip() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = DataStoreSettingsRepository(context)
            val original = context.settingsDataStore.data.first()[PrefKeys.COMPACT_NICK_BRACKETS]
            try {
                assertEquals(CompactNickBrackets.ANGLE, Settings().compactNickBrackets)
                assertEquals(CompactNickBrackets.ANGLE, Json.decodeFromString<Settings>("{}").compactNickBrackets)
                context.settingsDataStore.edit { it.remove(PrefKeys.COMPACT_NICK_BRACKETS) }
                assertEquals(CompactNickBrackets.ANGLE, repository.settings.first().compactNickBrackets)
                context.settingsDataStore.edit { it[PrefKeys.COMPACT_NICK_BRACKETS] = "INVALID" }
                assertEquals(CompactNickBrackets.ANGLE, repository.settings.first().compactNickBrackets)
                for (brackets in CompactNickBrackets.entries) {
                    repository.setCompactNickBrackets(brackets)
                    assertEquals(brackets.name, context.settingsDataStore.data.first()[PrefKeys.COMPACT_NICK_BRACKETS])
                    assertEquals(brackets, DataStoreSettingsRepository(context).settings.first().compactNickBrackets)
                }
            } finally {
                context.settingsDataStore.edit {
                    if (original == null) it.remove(PrefKeys.COMPACT_NICK_BRACKETS) else it[PrefKeys.COMPACT_NICK_BRACKETS] = original
                }
            }
        }
}
