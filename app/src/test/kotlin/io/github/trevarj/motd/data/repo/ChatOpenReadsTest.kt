package io.github.trevarj.motd.data.repo

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.createExportedVersion
import io.github.trevarj.motd.data.db.keepObservedTablesTracked
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.visibility.MessageVisibilityReader
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.di.DbModule
import io.github.trevarj.motd.ui.chat.ChatViewModelTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
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

    @Test
    fun productionCallbackInstallsTrackingOnExistingDatabaseAndReopen() =
        productionDatabaseSurvivesReopen { context ->
            Room.databaseBuilder(context, MotdDatabase::class.java, "motd.db").build().let { db ->
                try {
                    db.openHelper.writableDatabase
                } finally {
                    db.close()
                }
            }
        }

    @Test
    fun productionCallbackInstallsTrackingAfterMigrationAndReopen() =
        productionDatabaseSurvivesReopen { context ->
            FrameworkSQLiteOpenHelperFactory()
                .create(
                    SupportSQLiteOpenHelper.Configuration
                        .builder(context)
                        .name("motd.db")
                        .callback(
                            object : SupportSQLiteOpenHelper.Callback(48) {
                                override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion(db, 48)

                                override fun onUpgrade(
                                    db: SupportSQLiteDatabase,
                                    oldVersion: Int,
                                    newVersion: Int,
                                ) = Unit
                            },
                        ).build(),
                ).use { it.writableDatabase }
        }

    @Test
    fun productionCallbackRecreatesTrackingAfterDestructiveDowngradeAndReopen() =
        productionDatabaseSurvivesReopen { context ->
            val db = DbModule.provideDatabase(context)
            try {
                // A development branch with a newer schema takes the production downgrade path.
                db.openHelper.writableDatabase.execSQL("PRAGMA user_version = 50")
            } finally {
                db.close()
            }
        }

    private fun productionDatabaseSurvivesReopen(prepare: (Context) -> Unit) =
        runBlocking(Dispatchers.IO) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteDatabase("motd.db")
            var db: MotdDatabase? = null
            try {
                prepare(context)
                val installed = DbModule.provideDatabase(context)
                db = installed
                val roomId = installed.bufferDao().insert(buffer(installed.networkDao().insert(network()), "#motd"))
                val initial = checkNotNull(installed.messageDao().roomRevision(roomId))
                assertEquals(0L, initial.revision)
                assertEquals(ChatRoomRevisions.objectCount, initial.trackingObjects)
                installed.messageDao().insertAll(listOf(message(roomId, "committed", serverTime = 1, dedupKey = "committed")))
                val committed = checkNotNull(installed.messageDao().roomRevision(roomId))
                assertEquals(initial.session, committed.session)
                assertTrue(committed.revision > initial.revision)
                installed.close()

                val reopened = DbModule.provideDatabase(context)
                db = reopened
                // Open before holding the writer: onOpen must not reset existing counters or the session.
                val room = checkNotNull(reopened.bufferDao().observeById(roomId))
                assertEquals(committed, reopened.messageDao().roomRevision(roomId))
                assertReadsWhileWriterHeld(reopened) {
                    assertEquals(committed, reopened.messageDao().roomRevision(roomId))
                    assertEquals("committed", reopened.messageDao().newestMessage(roomId)?.text)
                    ChatViewModelTest.roomBuffers(reopened).buffer(roomId) to
                        MessageVisibilityReader(reopened).chatListRow(room.id, MessageVisibilitySpec(fools = setOf("troll")))
                }
            } finally {
                db?.close()
                context.deleteDatabase("motd.db")
            }
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
            assertReadsWhileWriterHeld(db) { reads(db, roomId) }
        } finally {
            db.close()
            file.delete()
        }
    }

    private suspend fun assertReadsWhileWriterHeld(
        db: MotdDatabase,
        reads: suspend () -> Pair<Any?, Any?>,
    ) = kotlinx.coroutines.coroutineScope {
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Change actual rows without committing; readers must still see the previous revision/page.
        val writer =
            Thread {
                db.runInTransaction {
                    db.openHelper.writableDatabase.execSQL("UPDATE messages SET text = 'pending'")
                    holding.countDown()
                    release.await()
                }
            }.apply { start() }
        try {
            assertTrue(holding.await(10, TimeUnit.SECONDS))
            // A read stuck on the writer blocks uninterruptibly; time the wait, not the read.
            val pending = async(Dispatchers.IO) { reads() }
            val result = withTimeoutOrNull(5_000) { pending.await() }
            assertNotNull("reading must not queue behind the writer", result)
            assertNotNull(result?.first)
            assertNotNull(result?.second)
        } finally {
            release.countDown()
            writer.join()
        }
    }
}
