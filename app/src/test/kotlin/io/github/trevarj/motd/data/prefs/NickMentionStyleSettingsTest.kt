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
class NickMentionStyleSettingsTest {
    @Test
    fun missingInvalidAndExplicitPreferencesRoundTrip() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = DataStoreSettingsRepository(context)
            val original = context.settingsDataStore.data.first()[PrefKeys.NICK_MENTION_STYLE]
            try {
                assertEquals(NickMentionStyle.BOLD, Settings().nickMentionStyle)
                assertEquals(NickMentionStyle.BOLD, Json.decodeFromString<Settings>("{}").nickMentionStyle)
                context.settingsDataStore.edit { it.remove(PrefKeys.NICK_MENTION_STYLE) }
                assertEquals(NickMentionStyle.BOLD, repository.settings.first().nickMentionStyle)
                context.settingsDataStore.edit { it[PrefKeys.NICK_MENTION_STYLE] = "INVALID" }
                assertEquals(NickMentionStyle.BOLD, repository.settings.first().nickMentionStyle)
                for (style in NickMentionStyle.entries) {
                    repository.setNickMentionStyle(style)
                    assertEquals(style, DataStoreSettingsRepository(context).settings.first().nickMentionStyle)
                }
            } finally {
                context.settingsDataStore.edit {
                    if (original == null) it.remove(PrefKeys.NICK_MENTION_STYLE) else it[PrefKeys.NICK_MENTION_STYLE] = original
                }
            }
        }
}
