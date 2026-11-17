package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChannelPrefixesSettingsTest {
    @Test
    fun missingInvalidAndExplicitPreferencesRoundTrip() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = DataStoreSettingsRepository(context)
            val original = context.settingsDataStore.data.first()[PrefKeys.SHOW_CHANNEL_PREFIXES]
            try {
                assertFalse(Settings().showChannelPrefixes)
                assertFalse(Json.decodeFromString<Settings>("{}").showChannelPrefixes)
                context.settingsDataStore.edit { it.remove(PrefKeys.SHOW_CHANNEL_PREFIXES) }
                assertFalse(repository.settings.first().showChannelPrefixes)
                context.settingsDataStore.edit { it[PrefKeys.SHOW_CHANNEL_PREFIXES] = "INVALID" }
                assertFalse(repository.settings.first().showChannelPrefixes)
                for (enabled in listOf(true, false)) {
                    repository.setShowChannelPrefixes(enabled)
                    assertEquals(enabled.toString(), context.settingsDataStore.data.first()[PrefKeys.SHOW_CHANNEL_PREFIXES])
                    assertEquals(enabled, DataStoreSettingsRepository(context).settings.first().showChannelPrefixes)
                }
            } finally {
                context.settingsDataStore.edit {
                    if (original == null) it.remove(PrefKeys.SHOW_CHANNEL_PREFIXES) else it[PrefKeys.SHOW_CHANNEL_PREFIXES] = original
                }
            }
        }
}
