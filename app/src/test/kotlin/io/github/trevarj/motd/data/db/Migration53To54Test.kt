package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.ScheduledMessageState.QUEUED
import io.github.trevarj.motd.data.db.ScheduledMessageState.SENDING
import io.github.trevarj.motd.data.db.ScheduledMessageState.UNAVAILABLE
import io.github.trevarj.motd.data.db.ScheduledMessageState.UNCERTAIN
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration53To54Test {
    @After
    fun tearDown() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migrationPreservesExistingRoomsAndQueueSurvivesDestinationRemovalAndReopen() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            createLegacyDatabase(context)
            val scheduled =
                ScheduledMessageEntity(
                    bufferId = 1,
                    networkId = 1,
                    networkName = "test",
                    target = "#MiXeD",
                    membershipCycle = 7,
                    text = "\u0002hello\u000f\nworld",
                    dueAt = 1_000,
                    zoneId = "Europe/Paris",
                )
            val builder = Room.databaseBuilder(context, MotdDatabase::class.java, DB_NAME).addMigrations(*ALL_MIGRATIONS)
            val db = builder.build()
            val claimed: ScheduledMessageEntity
            val waiting: ScheduledMessageEntity
            try {
                val dao = db.scheduledMessageDao()
                assertEquals(ALL_MIGRATIONS.last().endVersion, db.openHelper.writableDatabase.version)
                assertEquals("test", db.networkDao().byId(1)?.name)
                assertEquals("#MiXeD", db.bufferDao().observeById(1)?.displayName)
                assertTrue(dao.observeAll().first().isEmpty())

                val id = dao.insert(message = scheduled)
                val onConnection = scheduled.copy(text = "later", dueAt = null, zoneId = null)
                waiting = onConnection.copy(id = dao.insert(message = onConnection))
                assertEquals(listOf(scheduled.copy(id = id), waiting), dao.observeAll().first())

                val revised = scheduled.copy(id = id, revision = 1, text = "edited", dueAt = 2_000, zoneId = "UTC")
                assertEquals(1, dao.transition(id = id, revision = 0, from = QUEUED, to = UNAVAILABLE))
                assertEquals(0, dao.revise(id = id, revision = 0, state = QUEUED, text = "ignored", dueAt = null, zoneId = null))
                assertEquals(
                    1,
                    dao.revise(
                        id = id,
                        revision = 0,
                        state = UNAVAILABLE,
                        text = revised.text,
                        dueAt = revised.dueAt,
                        zoneId = revised.zoneId,
                    ),
                )
                assertEquals(0, dao.revise(id = id, revision = 0, state = QUEUED, text = "stale", dueAt = null, zoneId = null))
                assertEquals(0, dao.transition(id = id, revision = 0, from = QUEUED, to = SENDING))
                assertEquals(0, dao.remove(id = id, revision = 0, state = QUEUED))
                assertEquals(1, dao.transition(id = id, revision = 1, from = QUEUED, to = SENDING))
                assertEquals(0, dao.transition(id = id, revision = 1, from = QUEUED, to = UNAVAILABLE))
                assertEquals(0, dao.remove(id = id, revision = 1, state = QUEUED))
                claimed = revised.copy(state = SENDING)

                db.networkDao().deleteLocalTree(1)
                assertNull(db.networkDao().byId(1))
                assertNull(db.bufferDao().observeById(1))
                assertEquals(listOf(claimed, waiting), dao.all())
            } finally {
                db.close()
            }

            val reopened = builder.build()
            try {
                val dao = reopened.scheduledMessageDao()
                assertEquals(listOf(claimed, waiting), dao.all())
                assertEquals(1, dao.recoverInterrupted())
                assertEquals(0, dao.recoverInterrupted())
                assertEquals(listOf(claimed.copy(state = UNCERTAIN), waiting), dao.observeAll().first())
                assertEquals(1, dao.remove(id = claimed.id, revision = claimed.revision, state = UNCERTAIN))
                assertEquals(listOf(waiting), dao.all())
            } finally {
                reopened.close()
            }
        }

    @Test
    fun onlyFormattedPlainMessagesAreSchedulable() {
        assertTrue(isSchedulableMessage("\u0002hello\u000f\r\nworld\t!"))
        assertTrue(isSchedulableMessage("hello\n/not a command here"))
        assertFalse(isSchedulableMessage("\u000304,02\u000f \n\t"))
        assertFalse(isSchedulableMessage(" \n\u0002/me waves"))
        assertFalse(isSchedulableMessage("hello\u0000world"))
        assertFalse(isSchedulableMessage("\u0001ACTION waves\u0001"))
    }

    private fun createLegacyDatabase(context: Context) {
        FrameworkSQLiteOpenHelperFactory()
            .create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(DB_NAME)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(53) {
                            override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion(db, 53)

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            ).use { helper ->
                helper.writableDatabase.execSQL(
                    """INSERT INTO networks(id, name, role, host, port, tls, nick, username, realname, saslMechanism, autoConnect, ordering, restoreAutoConnect)
                    VALUES (1, 'test', 'DIRECT', 'irc.example', 6697, 1, 'me', 'me', 'Me', 'NONE', 0, 0, 0)""",
                )
                helper.writableDatabase.execSQL(
                    """INSERT INTO buffers(id, networkId, name, displayName, type, joined, membershipCycle, pinned, muted, archived, ordering, historyComplete, dismissed)
                    VALUES (1, 1, '#mixed', '#MiXeD', 'CHANNEL', 1, 7, 0, 0, 0, 0, 0, 0)""",
                )
            }
    }

    private companion object {
        const val DB_NAME = "migration-53-54-test.db"
    }
}
