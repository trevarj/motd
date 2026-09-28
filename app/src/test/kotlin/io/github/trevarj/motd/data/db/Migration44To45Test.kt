package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration44To45Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migrationPreservesExistingNetworkAndAddsEmptyScript() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteDatabase(DB_NAME)
            helper =
                FrameworkSQLiteOpenHelperFactory().create(
                    SupportSQLiteOpenHelper.Configuration
                        .builder(context)
                        .name(DB_NAME)
                        .callback(
                            object : SupportSQLiteOpenHelper.Callback(44) {
                                override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion(db, 44)

                                override fun onUpgrade(
                                    db: SupportSQLiteDatabase,
                                    oldVersion: Int,
                                    newVersion: Int,
                                ) = Unit
                            },
                        ).build(),
                )
            helper!!.writableDatabase.execSQL(
                """INSERT INTO networks(id, name, role, host, port, tls, nick, username, realname,
                    saslMechanism, autoConnect, ordering, restoreAutoConnect, trustedFileHost)
                   VALUES (1, 'libera', 'DIRECT', 'irc.example', 6697, 1, 'me', 'me', 'Me', 'NONE', 1, 0, 1, 'files.example')""",
            )
            helper!!.close()
            helper = null

            val migrated =
                Room
                    .databaseBuilder(context, MotdDatabase::class.java, DB_NAME)
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
            try {
                val network = requireNotNull(migrated.networkDao().byId(1))
                assertEquals("libera", network.name)
                assertEquals("irc.example", network.host)
                assertEquals("files.example", network.trustedFileHost)
                assertEquals("", network.onConnectCommands)
            } finally {
                migrated.close()
            }
        }

    private companion object {
        const val DB_NAME = "migration-44-45-test.db"
    }
}
