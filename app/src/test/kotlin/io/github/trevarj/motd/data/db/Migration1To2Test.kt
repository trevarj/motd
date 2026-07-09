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

/**
 * Room 1 -> 2 migration (plans/19): adds the nullable `wsUrl` column on `networks`. Verifies the
 * change is non-destructive — pre-existing rows survive and the new column reads NULL — and that a
 * subsequent write can set/read the column.
 */
@RunWith(RobolectricTestRunner::class)
class Migration1To2Test {

    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun `migration adds wsUrl column and preserves existing rows`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)

        // Open a fresh support DB and hand-build the v1 `networks` schema, then insert a row that
        // predates the migration (wsUrl does not exist yet).
        val factory = FrameworkSQLiteOpenHelperFactory()
        val h = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(CREATE_NETWORKS_V1)
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) {}
                })
                .build(),
        )
        helper = h
        val db = h.writableDatabase
        db.execSQL(
            "INSERT INTO networks (id, name, role, host, port, tls, nick, username, realname, " +
                "saslMechanism, autoConnect, ordering) VALUES " +
                "(1, 'libera', 'DIRECT', 'irc.libera.chat', 6697, 1, 'me', 'me', 'Me', 'NONE', 1, 0)",
        )

        // Run the migration under test directly against the live support DB.
        MIGRATION_1_2.migrate(db)

        // Column exists and defaults to NULL for the pre-existing row (data preserved).
        db.query("SELECT id, name, wsUrl FROM networks WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("libera", c.getString(1))
            assertNull(c.getString(2))
        }

        // The new column is writable/readable after the migration.
        db.execSQL("UPDATE networks SET wsUrl = 'wss://bnc.example.com:443/' WHERE id = 1")
        db.query("SELECT wsUrl FROM networks WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("wss://bnc.example.com:443/", c.getString(0))
        }
    }

    private companion object {
        const val DB_NAME = "migration-1-2-test.db"

        // v1 `networks` schema (subset sufficient for the migration; Room's real v1 had the same
        // columns minus wsUrl). Only the columns the migration touches/reads need to be present.
        const val CREATE_NETWORKS_V1 = """
            CREATE TABLE networks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                role TEXT NOT NULL,
                parentId INTEGER,
                bouncerNetId TEXT,
                host TEXT NOT NULL,
                port INTEGER NOT NULL,
                tls INTEGER NOT NULL,
                nick TEXT NOT NULL,
                username TEXT NOT NULL,
                realname TEXT NOT NULL,
                saslMechanism TEXT NOT NULL,
                saslUser TEXT,
                saslPassword TEXT,
                clientCertAlias TEXT,
                autoConnect INTEGER NOT NULL,
                ordering INTEGER NOT NULL
            )
        """
    }
}
