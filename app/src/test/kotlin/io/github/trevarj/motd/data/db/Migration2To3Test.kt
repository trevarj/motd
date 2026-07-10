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

/**
 * Room 2 -> 3 migration: adds UNIQUE(bufferId, msgid) on `messages` so a self message can never
 * surface twice by msgid (goguma-style durable identity). Verifies the change is non-destructive —
 * any pre-existing duplicate-msgid rows collapse to the lowest id, NULL-msgid rows are preserved —
 * and that the new unique index actually rejects a subsequent duplicate-msgid insert.
 */
@RunWith(RobolectricTestRunner::class)
class Migration2To3Test {

    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun `migration collapses duplicate msgids, preserves nulls, and enforces uniqueness`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)

        val factory = FrameworkSQLiteOpenHelperFactory()
        val h = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(CREATE_MESSAGES_V2)
                        db.execSQL(CREATE_DEDUP_INDEX_V2)
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) {}
                })
                .build(),
        )
        helper = h
        val db = h.writableDatabase

        // Two rows sharing a msgid but with DIFFERENT dedupKeys — exactly the buggy double-send the
        // old single index let through. Plus a NULL-msgid pending row that must survive untouched.
        db.execSQL(insertMsg(id = 1, msgid = "srv-1", dedupKey = "srv-1", serverTime = 1000, text = "hi"))
        db.execSQL(insertMsg(id = 2, msgid = "srv-1", dedupKey = "local-sha1", serverTime = 2000, text = "hi"))
        db.execSQL(insertMsg(id = 3, msgid = null, dedupKey = "pending:l", serverTime = 3000, text = "pending"))

        MIGRATION_2_3.migrate(db)

        // Duplicate collapsed to the lowest id; NULL-msgid row preserved → 2 rows remain.
        db.query("SELECT COUNT(*) FROM messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0))
        }
        db.query("SELECT id FROM messages WHERE msgid = 'srv-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0)) // kept the lowest id
        }
        db.query("SELECT COUNT(*) FROM messages WHERE msgid IS NULL").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0)) // pending row survived
        }

        // The new unique index rejects a fresh duplicate-msgid insert (different dedupKey and all).
        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL(insertMsg(id = 9, msgid = "srv-1", dedupKey = "another", serverTime = 5000, text = "hi"))
        }
    }

    private fun insertMsg(id: Long, msgid: String?, dedupKey: String, serverTime: Long, text: String): String {
        val msgidSql = if (msgid == null) "NULL" else "'$msgid'"
        return "INSERT INTO messages (id, bufferId, msgid, serverTime, sender, kind, text, isSelf, " +
            "hasMention, failed, dedupKey) VALUES " +
            "($id, 1, $msgidSql, $serverTime, 'me', 'PRIVMSG', '$text', 1, 0, 0, '$dedupKey')"
    }

    private companion object {
        const val DB_NAME = "migration-2-3-test.db"

        // v2 `messages` schema subset: the columns the migration reads/writes plus NOT NULL columns
        // an INSERT must satisfy. Mirrors Room's real v2 messages table (minus FTS/FK, not needed here).
        const val CREATE_MESSAGES_V2 = """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                bufferId INTEGER NOT NULL,
                msgid TEXT,
                serverTime INTEGER NOT NULL,
                sender TEXT NOT NULL,
                senderAccount TEXT,
                kind TEXT NOT NULL,
                text TEXT NOT NULL,
                isSelf INTEGER NOT NULL,
                hasMention INTEGER NOT NULL,
                replyToMsgid TEXT,
                pendingLabel TEXT,
                failed INTEGER NOT NULL,
                dedupKey TEXT NOT NULL
            )
        """

        const val CREATE_DEDUP_INDEX_V2 =
            "CREATE UNIQUE INDEX index_messages_bufferId_dedupKey ON messages(bufferId, dedupKey)"
    }
}
