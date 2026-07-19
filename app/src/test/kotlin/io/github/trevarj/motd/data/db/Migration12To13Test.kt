package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration12To13Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun `migration preserves network room message aliases reactions and roster rows`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(12) {
                    override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion12(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val db = helper!!.writableDatabase
        db.execSQL(
            """INSERT INTO networks(
                   id, name, role, host, port, tls, nick, username, realname, saslMechanism,
                   autoConnect, ordering
               ) VALUES (1, 'net', 'DIRECT', 'irc.example', 6697, 1, 'me', 'me', 'Me',
                         'NONE', 1, 0)""",
        )
        db.execSQL(
            """INSERT INTO buffers(
                   id, networkId, name, displayName, type, joined, membershipCycle, pinned, muted,
                   ordering, historyComplete
               ) VALUES (2, 1, '#room', '#Room', 'CHANNEL', 1, 3, 1, 0, 4, 0)""",
        )
        db.execSQL(
            """INSERT INTO messages(
                   id, bufferId, msgid, serverTime, sender, normalizedActor, kind, text, isSelf,
                   hasMention, failed, dedupKey, serverTimeAuthoritative, notificationHandled,
                   notificationClaimed, soundHandled
               ) VALUES (3, 2, 'Opaque-MsgID', 1000, 'Alice', 'alice', 'PRIVMSG', 'kept', 0,
                         0, 0, 'dedup', 1, 0, 0, 0)""",
        )
        db.execSQL(
            """INSERT INTO room_aliases(id, networkId, namespace, value, roomId, verified)
               VALUES (4, 1, 'CHANNEL', '#room', 2, 1)""",
        )
        db.execSQL(
            """INSERT INTO reactions(
                   id, bufferId, targetMsgid, actorKey, sender, emoji, serverTime, targetEventId
               ) VALUES (5, 2, 'Opaque-MsgID', 'nick:bob', 'Bob', '+1', 1001, 3)""",
        )
        db.execSQL("INSERT INTO members(bufferId, nick, prefixes) VALUES (2, 'Alice', '@')")
        db.execSQL(
            "INSERT INTO users(networkId, nick, username, account, away) " +
                "VALUES (1, 'alice', 'alice', 'account', 0)",
        )

        MIGRATION_12_13.migrate(db)

        listOf("networks", "buffers", "messages", "room_aliases", "reactions", "members", "users")
            .forEach { table ->
                db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("$table row count", 1, cursor.getInt(0))
                }
            }
        db.query("SELECT msgid FROM messages WHERE id = 3").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Opaque-MsgID", cursor.getString(0))
        }
        db.execSQL(
            "INSERT INTO network_identity(networkId, caseMapping, chanTypes, selfNick) " +
                "VALUES (1, NULL, '', NULL)",
        )
        db.query("SELECT caseMapping, chanTypes, selfNick FROM network_identity WHERE networkId = 1")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertEquals("", cursor.getString(1))
                assertTrue(cursor.isNull(2))
            }
    }

    private fun createExportedVersion12(db: SupportSQLiteDatabase) {
        val resource = "${MotdDatabase::class.java.canonicalName}/12.json"
        val schema = checkNotNull(javaClass.classLoader?.getResourceAsStream(resource))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val database = schema.getValue("database").jsonObject
        database.getValue("entities").jsonArray.forEach { element ->
            val entity = element.jsonObject
            val tableName = entity.getValue("tableName").jsonPrimitive.content
            fun executeTemplate(sql: String) {
                db.execSQL(sql.replace("\${TABLE_NAME}", tableName))
            }
            executeTemplate(entity.getValue("createSql").jsonPrimitive.content)
            entity["indices"]?.jsonArray.orEmpty().forEach { index ->
                executeTemplate(index.jsonObject.getValue("createSql").jsonPrimitive.content)
            }
            entity["contentSyncTriggers"]?.jsonArray.orEmpty().forEach { trigger ->
                db.execSQL(trigger.jsonPrimitive.content)
            }
        }
        database.getValue("setupQueries").jsonArray.forEach { query ->
            db.execSQL(query.jsonPrimitive.content)
        }
    }

    private companion object {
        const val DB_NAME = "migration-12-13-test.db"
    }
}
