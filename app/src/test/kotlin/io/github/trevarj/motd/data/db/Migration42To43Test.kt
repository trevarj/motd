package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration42To43Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migrationPreservesHistoryAndCreatesTheCrossBufferMentionIndex() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteDatabase(DB_NAME)
            helper =
                FrameworkSQLiteOpenHelperFactory().create(
                    SupportSQLiteOpenHelper.Configuration
                        .builder(context)
                        .name(DB_NAME)
                        .callback(
                            object : SupportSQLiteOpenHelper.Callback(42) {
                                override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion(db, 42)

                                override fun onUpgrade(
                                    db: SupportSQLiteDatabase,
                                    oldVersion: Int,
                                    newVersion: Int,
                                ) = Unit
                            },
                        ).build(),
                )
            helper!!.writableDatabase.apply {
                execSQL(
                    """INSERT INTO networks(id, name, role, host, port, tls, nick, username, realname,
                        saslMechanism, autoConnect, ordering, restoreAutoConnect)
                       VALUES (1, 'libera', 'DIRECT', 'irc.example', 6697, 1, 'me', 'me', 'Me', 'NONE', 1, 0, 1)""",
                )
                execSQL(
                    """INSERT INTO buffers(id, networkId, name, displayName, type, joined, membershipCycle,
                        pinned, muted, archived, ordering, localReadAnchorTime, localReadAnchorEventId,
                        historyComplete, dismissed)
                       VALUES (1, 1, '#chan', '#chan', 'CHANNEL', 1, 1, 0, 0, 0, 0, 1000, 7, 0, 0)""",
                )
                execSQL(
                    """INSERT INTO messages(id, bufferId, serverTime, sender, normalizedActor, kind, text,
                        isSelf, hasMention, failed, dedupKey, serverTimeAuthoritative, timelineOrder,
                        timelineOrderConfirmed, timeProvenance, notificationHandled, notificationClaimed,
                        notificationWatched, soundHandled)
                       VALUES (7, 1, 1000, 'alice', 'alice', 'PRIVMSG', 'kept', 0, 1, 0, 'm7', 1, 7, 1,
                               'SERVER_TAG', 0, 0, 0, 0)""",
                )
            }
            helper!!.close()
            helper = null

            val migrated = Room.databaseBuilder(context, MotdDatabase::class.java, DB_NAME).addMigrations(*ALL_MIGRATIONS).build()
            try {
                assertEquals("kept", requireNotNull(migrated.messageDao().byId(7)).text)
                migrated.openHelper.writableDatabase.query("PRAGMA index_list(messages)").use { cursor ->
                    val name = cursor.getColumnIndexOrThrow("name")
                    val names = buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
                    assertTrue("mention feed index missing", "index_messages_hasMention_serverTime_id" in names)
                }
            } finally {
                migrated.close()
            }
        }

    private companion object {
        const val DB_NAME = "migration-42-43-test.db"
    }
}
