package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration10To11Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migrationPreservesHistoryStateButQuarantinesLegacyCursorsAndCompletion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE networks(
                                id INTEGER NOT NULL PRIMARY KEY
                            )""",
                        )
                        db.execSQL(
                            """CREATE TABLE buffers(
                                id INTEGER PRIMARY KEY, networkId INTEGER NOT NULL,
                                historyComplete INTEGER NOT NULL,
                                FOREIGN KEY(networkId) REFERENCES networks(id) ON DELETE CASCADE
                            )""",
                        )
                        db.execSQL(
                            """CREATE TABLE messages(
                                id INTEGER PRIMARY KEY, bufferId INTEGER NOT NULL, msgid TEXT,
                                replyToMsgid TEXT, replyToEventId INTEGER
                            )""",
                        )
                        db.execSQL(
                            """CREATE TABLE history_cursors(
                                roomId INTEGER NOT NULL PRIMARY KEY,
                                newestMsgid TEXT, newestServerTime INTEGER,
                                oldestMsgid TEXT, oldestServerTime INTEGER,
                                historyComplete INTEGER NOT NULL,
                                FOREIGN KEY(roomId) REFERENCES buffers(id) ON DELETE CASCADE
                            )""",
                        )
                        db.execSQL(
                            """CREATE TABLE network_history_cursors(
                                networkId INTEGER NOT NULL PRIMARY KEY,
                                lastSuccessfulSync INTEGER NOT NULL,
                                FOREIGN KEY(networkId) REFERENCES networks(id) ON DELETE CASCADE
                            )""",
                        )
                        db.execSQL(
                            """CREATE TABLE users(
                                networkId INTEGER NOT NULL, nick TEXT NOT NULL, account TEXT,
                                PRIMARY KEY(networkId, nick)
                            )""",
                        )
                        db.execSQL(
                            """CREATE TABLE reactions(
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                bufferId INTEGER NOT NULL, targetMsgid TEXT NOT NULL,
                                sender TEXT NOT NULL, emoji TEXT NOT NULL,
                                serverTime INTEGER NOT NULL, targetEventId INTEGER
                            )""",
                        )
                        db.execSQL(
                            """CREATE UNIQUE INDEX index_reactions_bufferId_targetMsgid_sender
                               ON reactions(bufferId, targetMsgid, sender)""",
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )
        val db = helper!!.writableDatabase
        db.execSQL("INSERT INTO networks(id) VALUES (7)")
        db.execSQL("INSERT INTO buffers(id, networkId, historyComplete) VALUES (1, 7, 1)")
        db.execSQL(
            """INSERT INTO history_cursors(
                   roomId, newestMsgid, newestServerTime, oldestMsgid, oldestServerTime,
                   historyComplete
               ) VALUES (1, 'newest', 200, 'oldest', 100, 1)""",
        )
        db.execSQL(
            "INSERT INTO network_history_cursors(networkId, lastSuccessfulSync) VALUES (7, 1234)",
        )
        db.execSQL("INSERT INTO users(networkId, nick, account) VALUES (7, 'nick{}', 'alice')")
        db.execSQL(
            """INSERT INTO messages(id, bufferId, msgid, replyToMsgid, replyToEventId)
               VALUES (1, 1, 'same', 'parent', NULL), (2, 1, 'same', NULL, NULL)""",
        )
        db.execSQL(
            """INSERT INTO reactions(
                   id, bufferId, targetMsgid, sender, emoji, serverTime, targetEventId
               ) VALUES
                   (1, 1, 'parent', 'Nick[]', 'same-emoji', 10, NULL),
                   (2, 1, 'parent', 'nick{}', 'same-emoji', 11, NULL),
                   (3, 1, 'parent', 'NICK{}', 'other-emoji', 12, 1)""",
        )

        MIGRATION_10_11.migrate(db)

        db.query(
            "SELECT id, actorKey, sender, emoji, serverTime, targetEventId FROM reactions ORDER BY id",
        ).use { cursor ->
            assertEquals(3, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals("nick:nick{}", cursor.getString(1))
            assertEquals("Nick[]", cursor.getString(2))
            assertTrue(cursor.moveToNext())
            assertEquals(2L, cursor.getLong(0))
            assertEquals("nick:nick{}\u0000legacy:2", cursor.getString(1))
            assertEquals("nick{}", cursor.getString(2))
            assertTrue(cursor.moveToNext())
            assertEquals(3L, cursor.getLong(0))
            assertEquals("nick:nick{}", cursor.getString(1))
            assertEquals("other-emoji", cursor.getString(3))
            assertEquals(12L, cursor.getLong(4))
            assertEquals(1L, cursor.getLong(5))
        }
        db.query("SELECT COUNT(*) FROM messages").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        db.query("SELECT historyComplete FROM buffers WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.query(
            """SELECT newestMsgid, newestServerTime, oldestMsgid, oldestServerTime,
                      historyComplete FROM history_cursors WHERE roomId = 1""",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("newest", cursor.getString(0))
            assertEquals(200L, cursor.getLong(1))
            assertEquals("oldest", cursor.getString(2))
            assertEquals(100L, cursor.getLong(3))
            assertEquals(0, cursor.getInt(4))
        }
        db.query(
            "SELECT lastSuccessfulSync, serverDerived FROM network_history_cursors WHERE networkId = 7",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1234L, cursor.getLong(0))
            assertEquals(0, cursor.getInt(1))
        }
        db.query("PRAGMA index_list(`messages`)").use { cursor ->
            val names = buildSet {
                val name = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(name))
            }
            assertTrue("index_messages_bufferId_msgid" in names)
            assertTrue("index_messages_bufferId_replyToMsgid_replyToEventId" in names)
        }
        db.query("PRAGMA index_list(`reactions`)").use { cursor ->
            val names = mutableMapOf<String, Boolean>()
            val name = cursor.getColumnIndexOrThrow("name")
            val unique = cursor.getColumnIndexOrThrow("unique")
            while (cursor.moveToNext()) names[cursor.getString(name)] = cursor.getInt(unique) != 0
            assertEquals(true, names["index_reactions_bufferId_targetMsgid_actorKey_emoji"])
            assertEquals(false, names["index_reactions_bufferId_targetMsgid_targetEventId"])
            assertFalse("index_reactions_bufferId_targetMsgid_sender" in names)
        }
    }

    private companion object {
        const val DB_NAME = "migration-10-11-test.db"
    }
}
