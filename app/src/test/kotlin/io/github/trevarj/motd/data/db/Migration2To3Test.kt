package io.github.trevarj.motd.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Ensures the mainline v2 -> v3 durable-msgid dedup migration remains intact. */
@RunWith(RobolectricTestRunner::class)
class Migration2To3Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test fun `migration collapses duplicate msgids, preserves nulls, and enforces uniqueness`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        val h = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(CREATE_MESSAGES_V2)
                        db.execSQL(CREATE_DEDUP_INDEX_V2)
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                }).build(),
        )
        helper = h
        val db = h.writableDatabase
        db.execSQL(insertMsg(1, "srv-1", "srv-1", 1000, "hi"))
        db.execSQL(insertMsg(2, "srv-1", "local-sha1", 2000, "hi"))
        db.execSQL(insertMsg(3, null, "pending:l", 3000, "pending"))

        MIGRATION_2_3.migrate(db)

        db.query("SELECT COUNT(*) FROM messages").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0))
        }
        db.query("SELECT id FROM messages WHERE msgid = 'srv-1'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(1L, c.getLong(0))
        }
        db.query("SELECT COUNT(*) FROM messages WHERE msgid IS NULL").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
        }
        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL(insertMsg(9, "srv-1", "another", 5000, "hi"))
        }
    }

    private fun insertMsg(id: Long, msgid: String?, dedupKey: String, serverTime: Long, text: String): String {
        val msgidSql = if (msgid == null) "NULL" else "'$msgid'"
        return "INSERT INTO messages (id, bufferId, msgid, serverTime, sender, kind, text, isSelf, hasMention, failed, dedupKey) VALUES ($id, 1, $msgidSql, $serverTime, 'me', 'PRIVMSG', '$text', 1, 0, 0, '$dedupKey')"
    }

    private companion object {
        const val DB_NAME = "migration-2-3-test.db"
        const val CREATE_MESSAGES_V2 = """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bufferId INTEGER NOT NULL, msgid TEXT,
                serverTime INTEGER NOT NULL, sender TEXT NOT NULL, senderAccount TEXT, kind TEXT NOT NULL,
                text TEXT NOT NULL, isSelf INTEGER NOT NULL, hasMention INTEGER NOT NULL, replyToMsgid TEXT,
                pendingLabel TEXT, failed INTEGER NOT NULL, dedupKey TEXT NOT NULL
            )
        """
        const val CREATE_DEDUP_INDEX_V2 =
            "CREATE UNIQUE INDEX index_messages_bufferId_dedupKey ON messages(bufferId, dedupKey)"
    }
}
