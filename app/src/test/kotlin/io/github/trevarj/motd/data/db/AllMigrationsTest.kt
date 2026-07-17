package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.room.Room
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Validates the complete released schema path and the intentional v10 timeline reset. */
@RunWith(RobolectricTestRunner::class)
class AllMigrationsTest {
    private var legacyHelper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        legacyHelper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migrateVersion1ToCurrent_preservesNetworkAndResetsIrcDerivedState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        legacyHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion1(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build(),
        )
        legacyHelper!!.writableDatabase.apply {
            execSQL(
                """INSERT INTO networks
                    (id, name, role, host, port, tls, nick, username, realname, saslMechanism,
                     autoConnect, ordering)
                    VALUES (1, 'libera', 'DIRECT', 'irc.libera.chat', 6697, 1, 'me', 'me', 'Me',
                            'NONE', 1, 0)""",
            )
            execSQL(
                """INSERT INTO buffers
                    (id, networkId, name, displayName, type, joined, pinned, muted, ordering,
                     historyComplete)
                    VALUES (1, 1, '#motd', '#MOTD', 'CHANNEL', 1, 1, 0, 0, 0)""",
            )
            execSQL(
                """INSERT INTO messages
                    (id, bufferId, msgid, serverTime, sender, kind, text, isSelf, hasMention,
                     failed, dedupKey)
                    VALUES (1, 1, 'm1', 1000, 'alice', 'PRIVMSG', 'hello', 0, 0, 0, 'm1')""",
            )
            execSQL(
                "INSERT INTO users(networkId, nick, account, away) " +
                    "VALUES (1, 'alice', 'alice-account', 0)",
            )
            execSQL("INSERT INTO members(bufferId, nick, prefixes) VALUES (1, 'alice', '@')")
            execSQL(
                """INSERT INTO reactions
                    (id, bufferId, targetMsgid, sender, emoji, serverTime)
                    VALUES (1, 1, 'm1', 'bob', '+1', 1001)""",
            )
        }
        legacyHelper!!.close()
        legacyHelper = null

        val migrated = Room.databaseBuilder(
            context,
            MotdDatabase::class.java,
            DB_NAME,
        ).addMigrations(*ALL_MIGRATIONS).build()
        try {
            val sqlite = migrated.openHelper.writableDatabase
            sqlite.query(
                """SELECT name, host, port, nick FROM networks WHERE id = 1""",
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("libera", cursor.getString(0))
                assertEquals("irc.libera.chat", cursor.getString(1))
                assertEquals(6697, cursor.getInt(2))
                assertEquals("me", cursor.getString(3))
            }
            listOf(
                "buffers",
                "messages",
                "users",
                "members",
                "reactions",
                "room_aliases",
                "event_aliases",
                "event_observations",
                "history_cursors",
                "network_history_cursors",
                "connection_generations",
            ).forEach { table ->
                sqlite.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals("$table must reset", 0, cursor.getInt(0))
                }
            }
        } finally {
            migrated.close()
        }
    }

    /** Creates the real v1 tables, indices, FTS triggers, and Room identity from the tracked JSON. */
    private fun createExportedVersion1(db: SupportSQLiteDatabase) {
        val resource = "${MotdDatabase::class.java.canonicalName}/1.json"
        val schema = checkNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
            "missing checked-in Room schema resource $resource"
        }.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
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
        const val DB_NAME = "all-migrations-test.db"
        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
        )
    }
}
