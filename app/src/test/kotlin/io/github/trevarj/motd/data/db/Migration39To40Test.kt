package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration39To40Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migrationPreservesHistoryAndAddsNullableDickordDescriptor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(DB_NAME)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(39) {
                            override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion(db, 39)

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        val db = helper!!.writableDatabase
        db.execSQL(
            """INSERT INTO networks(id, name, role, host, port, tls, nick, username, realname,
                saslMechanism, autoConnect, ordering, restoreAutoConnect)
               VALUES (1, 'libera', 'DIRECT', 'irc.example', 6697, 1, 'me', 'me', 'Me', 'NONE', 1, 0, 1)""",
        )
        db.execSQL(
            """INSERT INTO buffers(id, networkId, name, displayName, type, joined, membershipCycle,
                pinned, muted, archived, ordering, historyComplete, dismissed)
               VALUES (1, 1, '#discord.guild.general', '#discord.guild.general', 'CHANNEL', 0, 0, 0, 0, 0, 0, 0, 0)""",
        )
        db.execSQL(
            """INSERT INTO messages(id, bufferId, serverTime, sender, normalizedActor, kind, text,
                isSelf, hasMention, failed, dedupKey, serverTimeAuthoritative, timelineOrder,
                timelineOrderConfirmed, timeProvenance, notificationHandled, notificationClaimed,
                notificationWatched, soundHandled)
               VALUES (1, 1, 1000, 'alice', 'alice', 'PRIVMSG', 'kept', 0, 0, 0, 'd1', 1, 1, 1,
                'SERVER_TAG', 0, 0, 0, 0)""",
        )

        MIGRATION_39_40.migrate(db)

        db.query("SELECT dickordChannelJson FROM buffers WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
        assertEquals("kept", value(db, "SELECT text FROM messages WHERE id = 1"))

        val json =
            """{"v":1,"guild_id":"123","guild_name":"Guild","channel_id":"456","channel_type":0,"parent_id":null}"""
        db.execSQL("UPDATE buffers SET dickordChannelJson = ? WHERE id = 1", arrayOf(json))
        assertEquals(json, value(db, "SELECT dickordChannelJson FROM buffers WHERE id = 1"))
    }

    private fun value(
        db: SupportSQLiteDatabase,
        sql: String,
    ): String =
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }

    private companion object {
        const val DB_NAME = "migration-39-40-test.db"
    }
}
