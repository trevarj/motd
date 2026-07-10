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

/** The Phase 1 transport migration is additive and leaves prior networks direct. */
@RunWith(RobolectricTestRunner::class)
class Migration3To4Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test fun `migration adds proxy columns and preserves existing row`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) = db.execSQL(CREATE_NETWORKS_V3)
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                }).build(),
        )
        val db = helper!!.writableDatabase
        db.execSQL(INSERT_NETWORK_V3)
        MIGRATION_3_4.migrate(db)
        db.query("SELECT name, obfsMode, proxyHost, proxyPort FROM networks WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("libera", c.getString(0))
            assertNull(c.getString(1)); assertNull(c.getString(2)); assertTrue(c.isNull(3))
        }
        db.execSQL("UPDATE networks SET obfsMode = 'SOCKS5', proxyHost = '127.0.0.1', proxyPort = 1080 WHERE id = 1")
        db.query("SELECT obfsMode, proxyHost, proxyPort FROM networks WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("SOCKS5", c.getString(0)); assertEquals("127.0.0.1", c.getString(1)); assertEquals(1080, c.getInt(2))
        }
    }

    @Test fun `migration repairs unreleased v3 reality schema then v4 to v5 remains valid`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) = db.execSQL(CREATE_NETWORKS_LEGACY_REALITY_V3)
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                }).build(),
        )
        val db = helper!!.writableDatabase
        db.execSQL(INSERT_NETWORK_LEGACY_REALITY_V3)

        MIGRATION_3_4.migrate(db)
        MIGRATION_4_5.migrate(db)

        db.query("SELECT obfsMode, proxyHost, proxyPort, obfsLink FROM networks WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertNull(c.getString(0)); assertNull(c.getString(1)); assertTrue(c.isNull(2))
            assertEquals("vless://legacy@example.test:443?security=reality", c.getString(3))
        }
    }

    private companion object {
        const val DB_NAME = "migration-3-4-test.db"
        const val CREATE_NETWORKS_V3 = """
            CREATE TABLE networks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, role TEXT NOT NULL,
                parentId INTEGER, bouncerNetId TEXT, host TEXT NOT NULL, port INTEGER NOT NULL, tls INTEGER NOT NULL,
                nick TEXT NOT NULL, username TEXT NOT NULL, realname TEXT NOT NULL, saslMechanism TEXT NOT NULL,
                saslUser TEXT, saslPassword TEXT, clientCertAlias TEXT, autoConnect INTEGER NOT NULL,
                ordering INTEGER NOT NULL, wsUrl TEXT
            )
        """
        const val INSERT_NETWORK_V3 = """
            INSERT INTO networks (id, name, role, host, port, tls, nick, username, realname, saslMechanism, autoConnect, ordering, wsUrl)
            VALUES (1, 'libera', 'DIRECT', 'irc.libera.chat', 6697, 1, 'me', 'me', 'Me', 'NONE', 1, 0, NULL)
        """
        const val CREATE_NETWORKS_LEGACY_REALITY_V3 = """
            CREATE TABLE networks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, role TEXT NOT NULL,
                parentId INTEGER, bouncerNetId TEXT, host TEXT NOT NULL, port INTEGER NOT NULL, tls INTEGER NOT NULL,
                nick TEXT NOT NULL, username TEXT NOT NULL, realname TEXT NOT NULL, saslMechanism TEXT NOT NULL,
                saslUser TEXT, saslPassword TEXT, clientCertAlias TEXT, autoConnect INTEGER NOT NULL,
                ordering INTEGER NOT NULL, wsUrl TEXT, obfsLink TEXT
            )
        """
        const val INSERT_NETWORK_LEGACY_REALITY_V3 = """
            INSERT INTO networks (id, name, role, host, port, tls, nick, username, realname, saslMechanism, autoConnect, ordering, wsUrl, obfsLink)
            VALUES (1, 'legacy', 'DIRECT', 'irc.example.test', 6697, 1, 'me', 'me', 'Me', 'NONE', 1, 0, NULL, 'vless://legacy@example.test:443?security=reality')
        """
    }
}
