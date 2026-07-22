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
class Migration14To15Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migrationPreservesEveryBufferAndMessageWithInheritedLayout() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(14) {
                    override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion14(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val db = helper!!.writableDatabase
        db.execSQL(
            """INSERT INTO networks(id, name, role, host, port, tls, nick, username, realname,
                saslMechanism, autoConnect, ordering)
               VALUES (1, 'net', 'DIRECT', 'irc.example', 6697, 1, 'me', 'me', 'Me', 'NONE', 1, 0)""",
        )
        listOf("CHANNEL" to "#motd", "QUERY" to "alice", "SERVER" to "server").forEachIndexed { index, (type, name) ->
            db.execSQL(
                """INSERT INTO buffers(id, networkId, name, displayName, type, joined, membershipCycle,
                    pinned, muted, ordering, historyComplete, dismissed)
                   VALUES (${index + 1}, 1, '$name', '$name', '$type', 0, 0, 0, 0, $index, 0, 0)""",
            )
            db.execSQL(
                """INSERT INTO messages(id, bufferId, serverTime, sender, normalizedActor, kind, text,
                    isSelf, hasMention, failed, dedupKey, serverTimeAuthoritative, notificationHandled,
                    notificationClaimed, soundHandled)
                   VALUES (${index + 1}, ${index + 1}, 1000, 'alice', 'alice', 'PRIVMSG', 'kept',
                    0, 0, 0, 'd$index', 1, 0, 0, 0)""",
            )
        }

        MIGRATION_14_15.migrate(db)

        db.query("SELECT COUNT(*) FROM buffers WHERE layoutDensityOverride IS NULL").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM messages").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3, cursor.getInt(0))
        }
        db.execSQL("UPDATE buffers SET layoutDensityOverride = 'COMPACT' WHERE id = 1")
        db.query("SELECT layoutDensityOverride FROM buffers WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("COMPACT", cursor.getString(0))
        }
    }

    private fun createExportedVersion14(db: SupportSQLiteDatabase) {
        val resource = "${MotdDatabase::class.java.canonicalName}/14.json"
        val schema = checkNotNull(javaClass.classLoader?.getResourceAsStream(resource))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val database = schema.getValue("database").jsonObject
        database.getValue("entities").jsonArray.forEach { element ->
            val entity = element.jsonObject
            val tableName = entity.getValue("tableName").jsonPrimitive.content
            fun executeTemplate(sql: String) = db.execSQL(sql.replace("\${TABLE_NAME}", tableName))
            executeTemplate(entity.getValue("createSql").jsonPrimitive.content)
            entity["indices"]?.jsonArray.orEmpty().forEach { index ->
                executeTemplate(index.jsonObject.getValue("createSql").jsonPrimitive.content)
            }
            entity["contentSyncTriggers"]?.jsonArray.orEmpty().forEach { trigger ->
                db.execSQL(trigger.jsonPrimitive.content)
            }
        }
        database.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
    }

    private companion object {
        const val DB_NAME = "migration-14-15-test.db"
    }
}
