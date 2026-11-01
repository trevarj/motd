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
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration46To47Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migrationAddsNullCustomKindsWithoutChangingExistingPresenceOverride() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteDatabase(DB_NAME)
            helper =
                FrameworkSQLiteOpenHelperFactory().create(
                    SupportSQLiteOpenHelper.Configuration
                        .builder(context)
                        .name(DB_NAME)
                        .callback(
                            object : SupportSQLiteOpenHelper.Callback(46) {
                                override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion(db, 46)

                                override fun onUpgrade(
                                    db: SupportSQLiteDatabase,
                                    oldVersion: Int,
                                    newVersion: Int,
                                ) = Unit
                            },
                        ).build(),
                )
            helper!!.writableDatabase.apply {
                execSQL("INSERT INTO networks(id, name, role, host, port, tls, nick, username, realname, saslMechanism, autoConnect, ordering, restoreAutoConnect) VALUES (1, 'n', 'DIRECT', 'irc.example', 6697, 1, 'me', 'me', 'Me', 'NONE', 1, 0, 1)")
                execSQL("INSERT INTO buffers(id, networkId, name, displayName, type, joined, membershipCycle, pinned, muted, archived, ordering, historyComplete, dismissed, presenceModeOverride) VALUES (1, 1, '#c', '#c', 'CHANNEL', 1, 1, 0, 0, 0, 0, 0, 0, 'SMART')")
            }
            helper!!.close()
            helper = null

            val migrated = Room.databaseBuilder(context, MotdDatabase::class.java, DB_NAME).addMigrations(*ALL_MIGRATIONS).build()
            try {
                val room = requireNotNull(migrated.bufferDao().observeById(1))
                assertEquals(io.github.trevarj.motd.data.prefs.PresenceMode.SMART, room.presenceModeOverride)
                assertNull(room.presenceCustomKindsOverride)
            } finally {
                migrated.close()
            }
        }

    private companion object {
        const val DB_NAME = "migration-46-47-test.db"
    }
}
