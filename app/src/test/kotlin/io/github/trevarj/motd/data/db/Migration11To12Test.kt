package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration11To12Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun `migration derives reconnect anchor from authoritative chat and preserves all rows`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(11) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE buffers(" +
                                "id INTEGER PRIMARY KEY, networkId INTEGER NOT NULL, readMarkerTime INTEGER)",
                        )
                        db.execSQL(
                            """CREATE TABLE messages(
                                id INTEGER PRIMARY KEY, bufferId INTEGER NOT NULL,
                                serverTime INTEGER NOT NULL, pendingLabel TEXT, text TEXT NOT NULL,
                                msgid TEXT, failed INTEGER NOT NULL, kind TEXT NOT NULL,
                                serverTimeAuthoritative INTEGER NOT NULL
                            )""",
                        )
                        db.execSQL(
                            """CREATE TABLE event_aliases(
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                networkId INTEGER NOT NULL, namespace TEXT NOT NULL,
                                value BLOB NOT NULL, timelineEventId INTEGER NOT NULL
                            )""",
                        )
                        db.execSQL(
                            "CREATE UNIQUE INDEX index_event_aliases_networkId_namespace_value " +
                                "ON event_aliases(networkId, namespace, value)",
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
        db.execSQL(
            "INSERT INTO buffers(id, networkId, readMarkerTime) VALUES " +
                "(1, 42, 10000), (2, 42, 500)",
        )
        db.execSQL(
            """INSERT INTO messages(
                   id, bufferId, serverTime, pendingLabel, text, msgid, failed,
                   kind, serverTimeAuthoritative
               ) VALUES
               (7, 1, 1000, NULL, 'safe reconnect candidate', 'kept-msgid', 0, 'PRIVMSG', 1),
               (8, 1, 10000, 'motd-upgrade-attempt', 'future pending', NULL, 0, 'PRIVMSG', 0),
               (9, 2, 400, NULL, 'non-chat is not a marker', NULL, 0, 'JOIN', 1)""",
        )
        db.execSQL(
            "INSERT INTO event_aliases(networkId, namespace, value, timelineEventId) VALUES (?, ?, ?, ?)",
            arrayOf(
                42L,
                "LABEL",
                "11\u0000motd-upgrade-attempt".toByteArray(Charsets.UTF_8),
                8L,
            ),
        )

        MIGRATION_11_12.migrate(db)

        db.query(
            "SELECT readMarkerTime, localReadAnchorTime, localReadAnchorEventId FROM buffers WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1000L, cursor.getLong(0))
            assertEquals(1000L, cursor.getLong(1))
            assertEquals(7L, cursor.getLong(2))
        }
        db.query(
            "SELECT readMarkerTime, localReadAnchorTime, localReadAnchorEventId " +
                "FROM buffers WHERE id = 2",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
        }
        db.query("SELECT COUNT(*) FROM messages").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3, cursor.getInt(0))
        }
        db.query("PRAGMA index_list(`messages`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            val names = buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
            assertTrue("index_messages_bufferId_pendingLabel" in names)
        }
        db.query(
            "SELECT timelineEventId FROM event_aliases " +
                "WHERE networkId = 42 AND namespace = 'LABEL' " +
                "AND value = CAST('motd-upgrade-attempt' AS BLOB)",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(8L, cursor.getLong(0))
        }
        db.query("SELECT COUNT(*) FROM event_aliases WHERE timelineEventId = 8").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        db.execSQL(
            "INSERT INTO composer_drafts(roomId, text, replyToEventId, updatedAt) " +
                "VALUES (1, 'draft', 999, 1234)",
        )
        db.query("SELECT text, replyToEventId, updatedAt FROM composer_drafts WHERE roomId = 1")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("draft", cursor.getString(0))
                assertEquals(999L, cursor.getLong(1))
                assertEquals(1234L, cursor.getLong(2))
            }
    }

    private companion object {
        const val DB_NAME = "migration-11-12-test.db"
    }
}
