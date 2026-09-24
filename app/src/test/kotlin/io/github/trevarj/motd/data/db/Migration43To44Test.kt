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
class Migration43To44Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migrationAddsNullableTrustedFileHostWithoutChangingExistingNetworks() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteDatabase(DB_NAME)
            helper =
                FrameworkSQLiteOpenHelperFactory().create(
                    SupportSQLiteOpenHelper.Configuration
                        .builder(context)
                        .name(DB_NAME)
                        .callback(
                            object : SupportSQLiteOpenHelper.Callback(43) {
                                override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion(db, 43)

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
                    saslMechanism, autoConnect, ordering, restoreAutoConnect)
                   VALUES (1, 'libera', 'DIRECT', 'irc.example', 6697, 1, 'me', 'me', 'Me', 'NONE', 1, 0, 1)""",
            )
            helper!!.close()
            helper = null

            val migrated =
                Room
                    .databaseBuilder(context, MotdDatabase::class.java, DB_NAME)
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
            try {
                assertEquals(null, requireNotNull(migrated.networkDao().byId(1)).trustedFileHost)
            } finally {
                migrated.close()
            }
        }

    private companion object {
        const val DB_NAME = "migration-43-44-test.db"
    }
}
