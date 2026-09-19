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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration41To42Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migrationPreservesHistoryAndExistingOverridesWhileNewPolicyInherits() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteDatabase(DB_NAME)
            helper =
                FrameworkSQLiteOpenHelperFactory().create(
                    SupportSQLiteOpenHelper.Configuration
                        .builder(context)
                        .name(DB_NAME)
                        .callback(
                            object : SupportSQLiteOpenHelper.Callback(41) {
                                override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion(db, 41)

                                override fun onUpgrade(
                                    db: SupportSQLiteDatabase,
                                    oldVersion: Int,
                                    newVersion: Int,
                                ) = Unit
                            },
                        ).build(),
                )
            helper!!.writableDatabase.apply {
                execSQL(
                    """INSERT INTO networks(id, name, role, host, port, tls, nick, username, realname,
                        saslMechanism, autoConnect, ordering, restoreAutoConnect)
                       VALUES (1, 'libera', 'DIRECT', 'irc.example', 6697, 1, 'me', 'me', 'Me', 'NONE', 1, 0, 1)""",
                )
                execSQL(
                    """INSERT INTO buffers(id, networkId, name, displayName, type, joined, membershipCycle,
                        pinned, muted, archived, ordering, localReadAnchorTime, localReadAnchorEventId,
                        historyComplete, dismissed, layoutDensityOverride, presenceModeOverride)
                       VALUES (1, 1, '#chan', '#chan', 'CHANNEL', 1, 2, 1, 0, 0, 0, 1000, 7, 0, 0, 'COMPACT', 'ALL')""",
                )
                execSQL(
                    """INSERT INTO messages(id, bufferId, serverTime, sender, normalizedActor, kind, text,
                        isSelf, hasMention, failed, dedupKey, serverTimeAuthoritative, timelineOrder,
                        timelineOrderConfirmed, timeProvenance, notificationHandled, notificationClaimed,
                        notificationWatched, soundHandled)
                       VALUES (7, 1, 1000, 'alice', 'alice', 'PRIVMSG', 'kept', 0, 0, 0, 'm7', 1, 7, 1,
                               'SERVER_TAG', 0, 0, 0, 0)""",
                )
            }
            helper!!.close()
            helper = null

            val migrated =
                Room
                    .databaseBuilder(context, MotdDatabase::class.java, DB_NAME)
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
            try {
                val room = requireNotNull(migrated.bufferDao().observeById(1))
                assertEquals(1_000L, room.localReadAnchorTime)
                assertEquals(7L, room.localReadAnchorEventId)
                assertEquals(LayoutDensity.COMPACT, room.layoutDensityOverride)
                assertEquals(PresenceMode.ALL, room.presenceModeOverride)
                assertNull(room.historySyncModeOverride)
                assertNotNull(migrated.messageDao().byId(7))
            } finally {
                migrated.close()
            }
        }

    private companion object {
        const val DB_NAME = "migration-41-42-test.db"
    }
}
