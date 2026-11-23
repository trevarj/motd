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
                val otherRoom = buffers.insert(buffer(networkId, "#other"))
                messages.insertAll(
                    listOf(
                        message(room, "hello", "bob", 1_000, "chat", hasMention = true),
                        message(room, "mode change", "op", 2_000, "mode", kind = MessageKind.MODE),
                        message(otherRoom, "newer message", "alice", 1_500, "other"),
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
                        val initial = awaitItem()
                        assertEquals(listOf(otherRoom, room), initial.map { it.bufferId })
                        val preview = initial.last()
                        assertEquals("hello", preview.lastMessageText)
                        assertEquals("bob", preview.lastMessageSender)
                        assertEquals(1_000L, preview.previewTime)
                        assertEquals(2_000L, preview.lastMessageTime)
                        assertEquals(1, preview.unreadCount)
                        assertEquals(1, preview.mentionCount)

                        settings.setChatListMessagesOnly(false)
                        assertEquals(
                            listOf(
                                preview.copy(lastMessageText = "mode change", lastMessageSender = "op", previewTime = 2_000L),
                                initial.first(),
                            ),
                            awaitItem(),
                        )
                        assertEquals("false", context.settingsDataStore.data.first()[PrefKeys.CHAT_LIST_MESSAGES_ONLY])
                        assertFalse(DataStoreSettingsRepository(context).settings.first().chatListMessagesOnly)
                        settings.setChatListMessagesOnly(true)
                        assertEquals(initial, awaitItem())
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
