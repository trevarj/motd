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
class Migration9To10Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migration_preservesNetworkSecrets_andResetsAllIrcDerivedState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(CREATE_NETWORKS)
                        db.execSQL(CREATE_BUFFERS)
                        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY, bufferId INTEGER)")
                        db.execSQL("CREATE TABLE messages_fts(text TEXT)")
                        db.execSQL("CREATE TABLE reactions(id INTEGER PRIMARY KEY)")
                        db.execSQL("CREATE TABLE members(bufferId INTEGER, nick TEXT)")
                        db.execSQL("CREATE TABLE users(networkId INTEGER, nick TEXT)")
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
            """INSERT INTO networks(
                   id, name, role, host, port, tls, nick, username, realname, saslMechanism,
                   saslUser, saslPassword, serverPassword, clientCertAlias, autoConnect, ordering,
                   wsUrl, obfsMode, proxyHost, proxyPort, obfsLink
               ) VALUES (
                   7, 'saved', 'DIRECT', 'irc.example', 6697, 1, 'trev', 'trev', 'Trev', 'PLAIN',
                   'sasl-user', 'sasl-secret', 'server-secret', 'cert', 1, 3,
                   'wss://irc.example', 'SOCKS5', '127.0.0.1', 9050, 'vless://secret'
               )""",
        )
        db.execSQL(
            """INSERT INTO buffers(
                   id, networkId, name, displayName, type, joined, pinned, muted, ordering,
                   historyComplete
               ) VALUES (1, 7, '#old', '#old', 'CHANNEL', 1, 1, 0, 0, 0)""",
        )
        db.execSQL("INSERT INTO messages(id, bufferId) VALUES (1, 1)")

        MIGRATION_9_10.migrate(db)

        db.query(
            """SELECT saslUser, saslPassword, serverPassword, clientCertAlias, wsUrl,
                      obfsMode, proxyHost, proxyPort, obfsLink
               FROM networks WHERE id = 7""",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("sasl-user", cursor.getString(0))
            assertEquals("sasl-secret", cursor.getString(1))
            assertEquals("server-secret", cursor.getString(2))
            assertEquals("cert", cursor.getString(3))
            assertEquals("wss://irc.example", cursor.getString(4))
            assertEquals("SOCKS5", cursor.getString(5))
            assertEquals("127.0.0.1", cursor.getString(6))
            assertEquals(9050, cursor.getInt(7))
            assertEquals("vless://secret", cursor.getString(8))
        }
        listOf(
            "buffers",
            "messages",
            "reactions",
            "members",
            "users",
            "room_aliases",
            "event_aliases",
            "event_redirects",
            "event_observations",
            "history_cursors",
            "network_history_cursors",
            "connection_generations",
        ).forEach { table ->
            db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("$table must reset", 0, cursor.getInt(0))
            }
        }
        db.query(
            "SELECT COUNT(*) FROM app_state WHERE `key` = 'v10_notification_reset'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    private companion object {
        const val DB_NAME = "migration-9-10-test.db"
        const val CREATE_NETWORKS = """
            CREATE TABLE networks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL, role TEXT NOT NULL, parentId INTEGER, bouncerNetId TEXT,
                host TEXT NOT NULL, port INTEGER NOT NULL, tls INTEGER NOT NULL,
                nick TEXT NOT NULL, username TEXT NOT NULL, realname TEXT NOT NULL,
                saslMechanism TEXT NOT NULL, saslUser TEXT, saslPassword TEXT,
                serverPassword TEXT, clientCertAlias TEXT, autoConnect INTEGER NOT NULL,
                ordering INTEGER NOT NULL, wsUrl TEXT, obfsMode TEXT, proxyHost TEXT,
                proxyPort INTEGER, obfsLink TEXT
            )
        """
        const val CREATE_BUFFERS = """
            CREATE TABLE buffers (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                networkId INTEGER NOT NULL, name TEXT NOT NULL, displayName TEXT NOT NULL,
                type TEXT NOT NULL, topic TEXT, topicSetBy TEXT, joined INTEGER NOT NULL,
                pinned INTEGER NOT NULL, muted INTEGER NOT NULL, ordering INTEGER NOT NULL,
                readMarkerTime INTEGER, localUnreadFloorTime INTEGER, oldestFetchedTime INTEGER,
                historyComplete INTEGER NOT NULL, pendingCloseAt INTEGER
            )
        """
    }
}
