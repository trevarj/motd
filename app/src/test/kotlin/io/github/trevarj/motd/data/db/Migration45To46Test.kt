package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.visibility.MessageVisibilityReader
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration45To46Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migrationPreservesHistoryReadAnchorAndPendingSendWithKindBoundedSpeechIndex() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteDatabase(DB_NAME)
            helper =
                FrameworkSQLiteOpenHelperFactory().create(
                    SupportSQLiteOpenHelper.Configuration
                        .builder(context)
                        .name(DB_NAME)
                        .callback(
                            object : SupportSQLiteOpenHelper.Callback(45) {
                                override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion(db, 45)

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
                        historyComplete, dismissed)
                       VALUES (1, 1, '#chan', '#chan', 'CHANNEL', 1, 1, 0, 0, 0, 0, 1000, 7, 0, 0)""",
                )
                execSQL(
                    """INSERT INTO messages(id, bufferId, serverTime, sender, normalizedActor, kind, text,
                        isSelf, hasMention, failed, dedupKey, pendingLabel, serverTimeAuthoritative, timelineOrder,
                        timelineOrderConfirmed, timeProvenance, notificationHandled, notificationClaimed, soundHandled)
                       VALUES (7, 1, 1000, 'me', 'me', 'PRIVMSG', 'pending', 1, 0, 0, 'm7', 'send-label', 1, 17, 1,
                               'SERVER_TAG', 0, 0, 0),
                              (8, 1, 2000, 'lurker', 'lurker', 'JOIN', 'hidden', 0, 0, 0, 'm8', NULL, 1, 18, 1,
                               'SERVER_TAG', 0, 0, 0)""",
                )
            }
            helper!!.close()
            helper = null

            val migrated = Room.databaseBuilder(context, MotdDatabase::class.java, DB_NAME).addMigrations(*ALL_MIGRATIONS).build()
            try {
                val pending = requireNotNull(migrated.messageDao().byId(7))
                assertEquals("pending", pending.text)
                assertEquals("send-label", pending.pendingLabel)
                assertEquals(17L, pending.timelineOrder)
                assertEquals("hidden", requireNotNull(migrated.messageDao().byId(8)).text)
                val room = requireNotNull(migrated.bufferDao().observeById(1))
                val reader = MessageVisibilityReader(migrated)
                assertEquals(TimelineAnchor(1000, 7, 17), reader.effectiveLocalReadAnchor(room))
                assertEquals(7L, reader.latestEffectiveAnchor(1, MessageVisibilitySpec())?.id)
                val sqlite = migrated.openHelper.writableDatabase
                sqlite.query("PRAGMA index_list(messages)").use { cursor ->
                    val name = cursor.getColumnIndexOrThrow("name")
                    val names = buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
                    assertTrue("index_messages_bufferId_normalizedActor_kind_serverTime" in names)
                    assertFalse("index_messages_bufferId_normalizedActor_serverTime" in names)
                }
                sqlite.query("PRAGMA index_info(index_messages_bufferId_normalizedActor_kind_serverTime)").use { cursor ->
                    val name = cursor.getColumnIndexOrThrow("name")
                    val columns = buildList { while (cursor.moveToNext()) add(cursor.getString(name)) }
                    assertEquals(listOf("bufferId", "normalizedActor", "kind", "serverTime"), columns)
                }
            } finally {
                migrated.close()
            }
        }

    private companion object {
        const val DB_NAME = "migration-45-46-test.db"
    }
}
