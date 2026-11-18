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
class ComposerToolsSettingsTest {
    private val repository: SettingsRepository =
        DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun composerToolsDefaultOnForPreferencesAndOldSerializedSettings() =
        runTest {
            val defaults = Settings()
            assertTrue(defaults.showComposerEmoji)
            assertTrue(defaults.showComposerFormattingTools)
            assertTrue(defaults.showVoiceMessageButton)
            assertEquals(NicknameTapAction.MENTION, defaults.nicknameTapAction)

            val storedDefaults = repository.settings.first()
            assertTrue(storedDefaults.showComposerEmoji)
            assertTrue(storedDefaults.showComposerFormattingTools)
            assertTrue(storedDefaults.showVoiceMessageButton)
            assertEquals(NicknameTapAction.MENTION, storedDefaults.nicknameTapAction)

            val oldSettings = Json.decodeFromString<Settings>("{}")
            assertTrue(oldSettings.showComposerEmoji)
            assertTrue(oldSettings.showComposerFormattingTools)
            assertTrue(oldSettings.showVoiceMessageButton)
            assertEquals(NicknameTapAction.MENTION, oldSettings.nicknameTapAction)
        }

    @Test
    fun formattingToolsPreferenceRoundTrips() =
        runTest {
            repository.setShowComposerFormattingTools(false)
            assertFalse(repository.settings.first().showComposerFormattingTools)

            repository.setShowComposerFormattingTools(true)
            assertTrue(repository.settings.first().showComposerFormattingTools)
        }

    @Test
    fun voiceButtonAndNicknameTapPreferencesPersistAcrossRepositoryInstances() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            try {
                repository.setShowVoiceMessageButton(false)
                repository.setNicknameTapAction(NicknameTapAction.USER_MENU)
                val restored = DataStoreSettingsRepository(context).settings.first()
                assertFalse(restored.showVoiceMessageButton)
                assertEquals(NicknameTapAction.USER_MENU, restored.nicknameTapAction)
                repository.setShowVoiceMessageButton(true)
                repository.setNicknameTapAction(NicknameTapAction.MENTION)
                assertTrue(repository.settings.first().showVoiceMessageButton)
                assertEquals(NicknameTapAction.MENTION, repository.settings.first().nicknameTapAction)
            } finally {
                repository.setShowVoiceMessageButton(true)
                repository.setNicknameTapAction(NicknameTapAction.MENTION)
            }
        }

    @Test
    fun invalidNicknameTapActionFallsBackToMention() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            try {
                context.settingsDataStore.edit { it[PrefKeys.NICKNAME_TAP_ACTION] = "FUTURE_ACTION" }
                assertEquals(NicknameTapAction.MENTION, repository.settings.first().nicknameTapAction)
            } finally {
                context.settingsDataStore.edit { it.remove(PrefKeys.NICKNAME_TAP_ACTION) }
            }
        }
}
