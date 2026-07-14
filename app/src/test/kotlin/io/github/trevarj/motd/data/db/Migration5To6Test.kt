package io.github.trevarj.motd.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Typed-event persistence is additive and must not disturb the external-content FTS table. */
@RunWith(RobolectricTestRunner::class)
class Migration5To6Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test fun `migration preserves messages and FTS while enforcing typed event identity`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(CREATE_MESSAGES_V5)
                        db.execSQL(CREATE_DEDUP_INDEX)
                        db.execSQL(CREATE_MSGID_INDEX)
                        db.execSQL(CREATE_TIME_INDEX)
                        db.execSQL(CREATE_FTS)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                }).build(),
        )
        val db = helper!!.writableDatabase
        db.execSQL(INSERT_MESSAGE)
        db.execSQL("INSERT INTO messages_fts(docid, text, sender) VALUES (1, 'hello world', 'alice')")

        MIGRATION_5_6.migrate(db)

        db.query(
            "SELECT text, eventKey, eventPayload, inviteState FROM messages WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("hello world", cursor.getString(0))
            assertNull(cursor.getString(1))
            assertNull(cursor.getString(2))
            assertNull(cursor.getString(3))
        }
        db.query("SELECT text FROM messages_fts WHERE messages_fts MATCH 'hello*'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("hello world", cursor.getString(0))
        }

        db.execSQL(insertTyped(2, "invite:1"))
        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL(insertTyped(3, "invite:1"))
        }
        db.execSQL(insertTyped(4, null))
        db.execSQL(insertTyped(5, null))
        db.query("SELECT COUNT(*) FROM messages").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(4, cursor.getInt(0))
        }
    }

    private fun insertTyped(id: Long, eventKey: String?): String {
        val key = eventKey?.let { "'$it'" } ?: "NULL"
        return """INSERT INTO messages
            (id, bufferId, serverTime, sender, kind, text, isSelf, hasMention, failed, dedupKey,
             eventKey, eventPayload, inviteState)
            VALUES ($id, 1, $id, 'server', 'INVITE', 'invite', 0, 0, 0, 'typed:$id',
                    $key, 'v1', 'PENDING')"""
    }

    private companion object {
        const val DB_NAME = "migration-5-6-test.db"
        const val CREATE_MESSAGES_V5 = """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bufferId INTEGER NOT NULL,
                msgid TEXT, serverTime INTEGER NOT NULL, sender TEXT NOT NULL, senderAccount TEXT,
                kind TEXT NOT NULL, text TEXT NOT NULL, isSelf INTEGER NOT NULL,
                hasMention INTEGER NOT NULL, replyToMsgid TEXT, pendingLabel TEXT,
                failed INTEGER NOT NULL, dedupKey TEXT NOT NULL
            )
        """
        const val CREATE_DEDUP_INDEX =
            "CREATE UNIQUE INDEX index_messages_bufferId_dedupKey ON messages(bufferId, dedupKey)"
        const val CREATE_MSGID_INDEX =
            "CREATE UNIQUE INDEX index_messages_bufferId_msgid ON messages(bufferId, msgid)"
        const val CREATE_TIME_INDEX =
            "CREATE INDEX index_messages_bufferId_serverTime_id ON messages(bufferId, serverTime, id)"
        const val CREATE_FTS =
            "CREATE VIRTUAL TABLE messages_fts USING FTS4(text, sender, content=`messages`)"
        const val INSERT_MESSAGE = """
            INSERT INTO messages
                (id, bufferId, msgid, serverTime, sender, kind, text, isSelf, hasMention, failed, dedupKey)
            VALUES (1, 1, 'm1', 1000, 'alice', 'PRIVMSG', 'hello world', 0, 0, 0, 'm1')
        """
    }
}
