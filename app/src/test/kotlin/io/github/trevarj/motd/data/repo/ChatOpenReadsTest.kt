package io.github.trevarj.motd.data.repo

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.keepObservedTablesTracked
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.visibility.MessageVisibilityReader
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.ui.chat.ChatViewModelTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Reads behind opening and browsing chats must never queue behind a history write. */
@RunWith(RobolectricTestRunner::class)
class ChatOpenReadsTest {
    @Test
    fun entryRoomAndUnreadReadsDoNotWaitForAWriterTransaction() =
        whileWriterIsHeld { db, roomId ->
            val buffers = ChatViewModelTest.roomBuffers(db)
            val reader = MessageVisibilityReader(db)
            // A configured fool also runs the chat list's visibility pass.
            val spec = MessageVisibilitySpec(fools = setOf("troll"))
            buffers.buffer(roomId) to reader.chatListRow(roomId, spec)
        }

    @Test
    fun chatListAndNickSuggestionFlowsEmitWithoutTheWriter() =
        whileWriterIsHeld(trackAllTables = true) { db, _ ->
            db.bufferDao().observeChatList().first() to
                db.memberDao().observeNickSuggestions(1L, "m%", "me", "rfc1459", 10).first()
        }

    private fun whileWriterIsHeld(
        trackAllTables: Boolean = false,
        reads: suspend (MotdDatabase, Long) -> Pair<Any?, Any?>,
    ) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("chat-open-reads-", ".db", context.cacheDir)
        val db =
            Room
                .databaseBuilder(context, MotdDatabase::class.java, file.absolutePath)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        try {
            val roomId = db.bufferDao().insert(buffer(db.networkDao().insert(network()), "#motd"))
            // As the app does at startup, so no Flow has to install triggers.
            if (trackAllTables) db.keepObservedTablesTracked()
            val holding = CountDownLatch(1)
            val release = CountDownLatch(1)
            // A long history write holds the writer, as a 500-row CHATHISTORY page does during sync.
            val writer =
                Thread {
                    db.runInTransaction {
                        db.openHelper.writableDatabase.execSQL("UPDATE buffers SET pinned = pinned")
                        holding.countDown()
                        release.await()
                    }
                }.apply { start() }
            try {
                assertTrue(holding.await(10, TimeUnit.SECONDS))
                // A read stuck on the writer blocks uninterruptibly; time the wait, not the read.
                val pending = async(Dispatchers.IO) { reads(db, roomId) }
                val result = withTimeoutOrNull(5_000) { pending.await() }
                assertNotNull("reading must not queue behind the writer", result)
                assertNotNull(result?.first)
                assertNotNull(result?.second)
            } finally {
                release.countDown()
                writer.join()
            }
        } finally {
            db.close()
            file.delete()
        }
    }
}
