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
class ChatListSwipeActionSettingsTest {
    @Test
    fun absentAndInvalidPreferencesKeepArchiveBehavior() {
        assertEquals(ChatListSwipeAction.ARCHIVE, Settings().chatListSwipeAction)
        assertEquals(ChatListSwipeAction.ARCHIVE, Json.decodeFromString<Settings>("{}").chatListSwipeAction)
        assertEquals(ChatListSwipeAction.ARCHIVE, chatListSwipeActionFromPreference(null))
        assertEquals(ChatListSwipeAction.ARCHIVE, chatListSwipeActionFromPreference("MARK_UNREAD"))
    }

    @Test
    fun everyActionRoundTripsThroughDataStoreAndSerializedSettings() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = DataStoreSettingsRepository(context)
            try {
                ChatListSwipeAction.entries.forEach { action ->
                    repository.setChatListSwipeAction(action)
                    val saved = DataStoreSettingsRepository(context).settings.first()
                    assertEquals(action, saved.chatListSwipeAction)
                    assertEquals(action, Json.decodeFromString<Settings>(Json.encodeToString(saved)).chatListSwipeAction)
                }
            } finally {
                repository.setChatListSwipeAction(ChatListSwipeAction.ARCHIVE)
            }
        }
}
