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
class Migration40To41Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migrationPreservesHistoryContextAndResolvesOnlyNotifyCapableObservations() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteDatabase(DB_NAME)
            helper =
                FrameworkSQLiteOpenHelperFactory().create(
                    SupportSQLiteOpenHelper.Configuration
                        .builder(context)
                        .name(DB_NAME)
                        .callback(
                            object : SupportSQLiteOpenHelper.Callback(40) {
                                override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion(db, 40)

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
                        pinned, muted, archived, ordering, historyComplete, dismissed)
                       VALUES (1, 1, 'alice', 'Alice', 'QUERY', 0, 0, 0, 0, 0, 0, 0, 0),
                              (2, 1, '#chan', '#chan', 'CHANNEL', 0, 0, 0, 0, 0, 0, 0, 0)""",
                )
                insertChat(1, 1, "PRIVMSG", "HISTORY")
                insertChat(2, 2, "NOTICE", "HISTORY", mention = true)
                insertChat(3, 1, "ACTION", "LIVE")
                insertChat(4, 2, "NOTICE", "PUSH", mention = true)
                insertChat(5, 2, "ACTION", "LIVE", watched = true)
                insertChat(6, 2, "PRIVMSG", "LIVE")
                insertChat(7, 2, "PRIVMSG", "PUSH")
                insertChat(8, 2, "PRIVMSG", "HISTORY")
                insertChat(9, 1, "PRIVMSG", "LIVE", self = true)
                insertChat(10, 2, "PRIVMSG", "PUSH", mention = true, failed = true)
                insertChat(11, 1, "JOIN", "LIVE", mention = true, watched = true)
                insertChat(12, 2, "NOTICE", "HISTORY", watched = true)
                insertChat(13, 1, "NOTICE", "PUSH")
            }
            helper!!.close()
            helper = null

            val migrated =
                Room
                    .databaseBuilder(context, MotdDatabase::class.java, DB_NAME)
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
            try {
                val rows = (migrated.messageDao().historyRowsForMerge(1) + migrated.messageDao().historyRowsForMerge(2)).sortedBy { it.id }
                assertEquals((1L..13L).toList(), rows.map { it.id })
                assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 12L, 13L), rows.filter { it.notificationEligible }.map { it.id })
                assertEquals(listOf(3L, 4L, 5L, 6L, 7L, 9L, 10L, 11L, 13L), rows.filter { it.notificationEligibilityResolved }.map { it.id })
                assertEquals(listOf(5L, 11L, 12L), rows.filter { it.notificationWatched }.map { it.id })
                assertEquals(
                    listOf(3L, 4L, 5L, 13L),
                    migrated.canonicalTimelineDao().pendingNotifications(20, window = Long.MAX_VALUE / 2, maxRows = Int.MAX_VALUE).map { it.id },
                )
                assertEquals(
                    listOf(13L, 3L, 1L),
                    migrated.messageDao().recentNotifiable(1, Long.MIN_VALUE, Long.MIN_VALUE, -1, 20).map { it.id },
                )
                assertEquals(
                    listOf(12L, 5L, 4L, 2L),
                    migrated.messageDao().recentNotifiable(2, Long.MIN_VALUE, Long.MIN_VALUE, -1, 20).map { it.id },
                )
            } finally {
                migrated.close()
            }
        }

    private fun SupportSQLiteDatabase.insertChat(
        id: Long,
        bufferId: Long,
        kind: String,
        origin: String,
        mention: Boolean = false,
        watched: Boolean = false,
        self: Boolean = false,
        failed: Boolean = false,
    ) {
        execSQL(
            """INSERT INTO messages(id, bufferId, serverTime, sender, normalizedActor, kind, text,
                isSelf, hasMention, failed, dedupKey, serverTimeAuthoritative, timelineOrder,
                timelineOrderConfirmed, timeProvenance, notificationHandled, notificationClaimed,
                notificationWatched, soundHandled)
               VALUES (?, ?, ?, 'alice', 'alice', ?, ?, ?, ?, ?, ?, 1, ?, 1, 'SERVER_TAG', 0, 0, ?, 0)""",
            arrayOf<Any?>(id, bufferId, id * 1_000, kind, "message-$id", self, mention, failed, "m$id", id, watched),
        )
        execSQL(
            """INSERT INTO event_observations(networkId, timelineEventId, origin, receiveOrder,
                timeProvenance, semanticFingerprint, observedAt)
               VALUES (1, ?, ?, ?, 'SERVER_TAG', X'01', ?)""",
            arrayOf<Any?>(id, origin, id, id * 1_000),
        )
    }

    private companion object {
        const val DB_NAME = "migration-40-41-test.db"
    }
}
