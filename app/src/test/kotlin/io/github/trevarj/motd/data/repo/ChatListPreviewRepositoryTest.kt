package io.github.trevarj.motd.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import io.github.trevarj.motd.avatar.LocalAvatarStore
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.PrefKeys
import io.github.trevarj.motd.data.prefs.settingsDataStore
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.data.visibility.MessageVisibilityReader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatListPreviewRepositoryTest {
    @Test
    fun messagesOnlyDefaultsOnAndUpdatesBothPreviewPathsWithoutDatabaseWrites() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val settings = DataStoreSettingsRepository(context)
            val original = context.settingsDataStore.data.first()
            val db = inMemoryDb()
            try {
                val buffers = db.bufferDao()
                val messages = db.messageDao()
                val repository =
                    BufferRepositoryImpl(
                        buffers,
                        db.memberDao(),
                        messages,
                        settings,
                        MessageVisibilityReader(db),
                        LocalAvatarStore(context),
                        bufferStore = BufferStore(db),
                    )
                val networkId = db.networkDao().insert(network())
                val room = buffers.insert(buffer(networkId, "#chat"))
                messages.insertAll(
                    listOf(
                        message(room, "hello", "bob", 1_000, "chat"),
                        message(room, "mode change", "op", 2_000, "mode", kind = MessageKind.MODE),
                    ),
                )
                context.settingsDataStore.edit {
                    it.remove(PrefKeys.CHAT_LIST_MESSAGES_ONLY)
                    it.remove(PrefKeys.FOOL_NICKS)
                }
                assertTrue(settings.settings.first().chatListMessagesOnly)
                for (showRedacted in listOf(true, false)) {
                    settings.setShowRedactedMessages(showRedacted)
                    repository.observeChatList().test {
                        val initial = awaitItem().single()
                        assertEquals("hello", initial.lastMessageText)
                        assertEquals("bob", initial.lastMessageSender)
                        assertEquals(1_000L, initial.previewTime)
                        assertEquals(2_000L, initial.lastMessageTime)

                        settings.setChatListMessagesOnly(false)
                        val legacy = awaitItem().single()
                        assertEquals(
                            initial.copy(lastMessageText = "mode change", lastMessageSender = "op", previewTime = 2_000L),
                            legacy,
                        )
                        assertEquals("false", context.settingsDataStore.data.first()[PrefKeys.CHAT_LIST_MESSAGES_ONLY])
                        assertFalse(DataStoreSettingsRepository(context).settings.first().chatListMessagesOnly)
                        settings.setChatListMessagesOnly(true)
                        assertEquals(initial, awaitItem().single())
                    }
                }
            } finally {
                context.settingsDataStore.edit {
                    for (key in listOf(PrefKeys.CHAT_LIST_MESSAGES_ONLY, PrefKeys.SHOW_REDACTED_MESSAGES, PrefKeys.FOOL_NICKS)) {
                        val value = original[key]
                        if (value == null) it.remove(key) else it[key] = value
                    }
                }
                db.close()
            }
        }
}
