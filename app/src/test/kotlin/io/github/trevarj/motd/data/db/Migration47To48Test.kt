package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.PresenceMode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration47To48Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migrationAddsInheritedOpacityWithoutChangingExistingOverrides() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteDatabase(DB_NAME)
            helper =
                FrameworkSQLiteOpenHelperFactory().create(
                    SupportSQLiteOpenHelper.Configuration
                        .builder(context)
                        .name(DB_NAME)
                        .callback(
                            object : SupportSQLiteOpenHelper.Callback(47) {
                                override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion(db, 47)

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
                execSQL("INSERT INTO buffers(id, networkId, name, displayName, type, joined, membershipCycle, pinned, muted, archived, ordering, historyComplete, dismissed, layoutDensityOverride, presenceModeOverride, presenceCustomKindsOverride) VALUES (1, 1, '#c', '#c', 'CHANNEL', 1, 1, 0, 0, 0, 0, 0, 0, 'COMPACT', 'SMART', 'JOIN')")
            }
            helper!!.close()
            helper = null

            val migrated = Room.databaseBuilder(context, MotdDatabase::class.java, DB_NAME).addMigrations(*ALL_MIGRATIONS).build()
            try {
                val room = requireNotNull(migrated.bufferDao().observeById(1))
                assertEquals(LayoutDensity.COMPACT, room.layoutDensityOverride)
                assertEquals(PresenceMode.SMART, room.presenceModeOverride)
                assertEquals(setOf(MessageKind.JOIN), room.presenceCustomKindsOverride)
                assertNull(room.messageBackgroundOpacityOverride)
                assertEquals(1, migrated.bufferDao().setMessageBackgroundOpacityOverride(1, 37))
                assertEquals(37, migrated.bufferDao().observeById(1)?.messageBackgroundOpacityOverride)
                assertEquals(1, migrated.bufferDao().setMessageBackgroundOpacityOverride(1, null))
                assertNull(migrated.bufferDao().observeById(1)?.messageBackgroundOpacityOverride)
            } finally {
                migrated.close()
            }
        }

    @Test
    fun migration48To49AddsInheritedBlurAndPreservesExplicitOpacityAndLayout() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteDatabase(DB_NAME)
            helper =
                FrameworkSQLiteOpenHelperFactory().create(
                    SupportSQLiteOpenHelper.Configuration
                        .builder(context)
                        .name(DB_NAME)
                        .callback(
                            object : SupportSQLiteOpenHelper.Callback(48) {
                                override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion(db, 48)

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
                execSQL("INSERT INTO buffers(id, networkId, name, displayName, type, joined, membershipCycle, pinned, muted, archived, ordering, historyComplete, dismissed, layoutDensityOverride, messageBackgroundOpacityOverride) VALUES (1, 1, '#c', '#c', 'CHANNEL', 1, 1, 0, 0, 0, 0, 0, 0, 'COMPACT', 0)")
            }
            helper!!.close()
            helper = null
            val migrated = Room.databaseBuilder(context, MotdDatabase::class.java, DB_NAME).addMigrations(*ALL_MIGRATIONS).build()
            try {
                val room = requireNotNull(migrated.bufferDao().observeById(1))
                assertNull(room.messageBackgroundBlurOverride)
                assertEquals(0, room.messageBackgroundOpacityOverride)
                assertEquals(LayoutDensity.COMPACT, room.layoutDensityOverride)
                assertEquals(1, migrated.bufferDao().setMessageBackgroundBlurOverride(1, 63))
                assertEquals(63, migrated.bufferDao().observeById(1)?.messageBackgroundBlurOverride)
                assertEquals(1, migrated.bufferDao().setMessageBackgroundBlurOverride(1, 0))
                assertEquals(0, migrated.bufferDao().observeById(1)?.messageBackgroundBlurOverride)
                assertEquals(1, migrated.bufferDao().setMessageBackgroundBlurOverride(1, null))
                val reset = requireNotNull(migrated.bufferDao().observeById(1))
                assertNull(reset.messageBackgroundBlurOverride)
                assertEquals(0, reset.messageBackgroundOpacityOverride)
                assertEquals(LayoutDensity.COMPACT, reset.layoutDensityOverride)
            } finally {
                migrated.close()
            }
        }

    private companion object {
        const val DB_NAME = "migration-47-48-test.db"
    }
}
