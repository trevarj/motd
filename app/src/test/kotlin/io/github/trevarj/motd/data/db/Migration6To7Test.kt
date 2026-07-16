package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** IRC PASS persistence is additive and preserves every existing network credential. */
@RunWith(RobolectricTestRunner::class)
class Migration6To7Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test fun `migration adds nullable server password and preserves the network`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(CREATE_NETWORKS_V6)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                }).build(),
        )
        val db = helper!!.writableDatabase
        db.execSQL(INSERT_NETWORK)

        MIGRATION_6_7.migrate(db)

        db.query("SELECT host, saslPassword, serverPassword FROM networks WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("irc.example.org", cursor.getString(0))
            assertEquals("sasl-secret", cursor.getString(1))
            assertNull(cursor.getString(2))
        }
        db.execSQL("UPDATE networks SET serverPassword = 'server-secret' WHERE id = 1")
        db.query("SELECT serverPassword FROM networks WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("server-secret", cursor.getString(0))
        }
    }

    private companion object {
        const val DB_NAME = "migration-6-7-test.db"
        const val CREATE_NETWORKS_V6 = """
            CREATE TABLE networks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL,
                role TEXT NOT NULL, parentId INTEGER, bouncerNetId TEXT, host TEXT NOT NULL,
                port INTEGER NOT NULL, tls INTEGER NOT NULL, nick TEXT NOT NULL,
                username TEXT NOT NULL, realname TEXT NOT NULL, saslMechanism TEXT NOT NULL,
                saslUser TEXT, saslPassword TEXT, clientCertAlias TEXT,
                autoConnect INTEGER NOT NULL, ordering INTEGER NOT NULL, wsUrl TEXT,
                obfsMode TEXT, proxyHost TEXT, proxyPort INTEGER, obfsLink TEXT
            )
        """
        const val INSERT_NETWORK = """
            INSERT INTO networks (
                id, name, role, host, port, tls, nick, username, realname, saslMechanism,
                saslPassword, autoConnect, ordering
            ) VALUES (
                1, 'example', 'DIRECT', 'irc.example.org', 6697, 1, 'motd', 'motd', 'MOTD',
                'PLAIN', 'sasl-secret', 1, 0
            )
        """
    }
}
