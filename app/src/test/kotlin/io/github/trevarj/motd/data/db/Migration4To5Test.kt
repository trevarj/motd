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

/** The Phase 2 migration persists the VLESS+REALITY link without changing Phase 1 proxy values. */
@RunWith(RobolectricTestRunner::class)
class Migration4To5Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test fun `migration adds nullable reality link and preserves proxy settings`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) = db.execSQL(CREATE_NETWORKS_V4)
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                }).build(),
        )
        val db = helper!!.writableDatabase
        db.execSQL(INSERT_NETWORK_V4)
        MIGRATION_4_5.migrate(db)
        db.query("SELECT proxyHost, proxyPort, obfsLink FROM networks WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("127.0.0.1", c.getString(0)); assertEquals(1080, c.getInt(1)); assertNull(c.getString(2))
        }
        db.execSQL("UPDATE networks SET obfsLink = 'vless://uuid@example.test:443?security=reality' WHERE id = 1")
        db.query("SELECT obfsLink FROM networks WHERE id = 1").use { c -> assertTrue(c.moveToFirst()); assertEquals("vless://uuid@example.test:443?security=reality", c.getString(0)) }
    }

    private companion object {
        const val DB_NAME = "migration-4-5-test.db"
        const val CREATE_NETWORKS_V4 = """
            CREATE TABLE networks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, role TEXT NOT NULL,
                parentId INTEGER, bouncerNetId TEXT, host TEXT NOT NULL, port INTEGER NOT NULL, tls INTEGER NOT NULL,
                nick TEXT NOT NULL, username TEXT NOT NULL, realname TEXT NOT NULL, saslMechanism TEXT NOT NULL,
                saslUser TEXT, saslPassword TEXT, clientCertAlias TEXT, autoConnect INTEGER NOT NULL,
                ordering INTEGER NOT NULL, wsUrl TEXT, obfsMode TEXT, proxyHost TEXT, proxyPort INTEGER
            )
        """
        const val INSERT_NETWORK_V4 = """
            INSERT INTO networks (id, name, role, host, port, tls, nick, username, realname, saslMechanism, autoConnect, ordering, wsUrl, obfsMode, proxyHost, proxyPort)
            VALUES (1, 'libera', 'DIRECT', 'irc.libera.chat', 6697, 1, 'me', 'me', 'Me', 'NONE', 1, 0, NULL, 'SOCKS5', '127.0.0.1', 1080)
        """
    }
}
