package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.URL

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
        legacyHelper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(DB_NAME)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(1) {
                            override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion1(db)

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                old: Int,
                                new: Int,
                            ) = Unit
                        },
                    ).build(),
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
                    VALUES (1, 1, '#motd', '#motd', 'CHANNEL', 1, 1, 0, 0, 0)""",
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

        val migrated =
            Room
                .databaseBuilder(
                    context,
                    MotdDatabase::class.java,
                    DB_NAME,
                ).addMigrations(*ALL_MIGRATIONS)
                .build()
        try {
            val sqlite = migrated.openHelper.writableDatabase
            sqlite
                .query(
                    """SELECT name, host, port, nick FROM networks WHERE id = 1""",
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals("libera", cursor.getString(0))
                    assertEquals("irc.libera.chat", cursor.getString(1))
                    assertEquals(6697, cursor.getInt(2))
                    assertEquals("me", cursor.getString(3))
                }
            // The v1 row is ranked into the manual drawer order rather than left at the default.
            sqlite.query("SELECT ordering FROM networks WHERE id = 1").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
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
                "composer_drafts",
                "network_identity",
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

    @Test
    fun migrateVersion28To29_preservesNetworkAndDisablesNickServ() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        legacyHelper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(DB_NAME)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(28) {
                            override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion(db, 28)

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        legacyHelper!!.writableDatabase.execSQL(
            """INSERT INTO networks
                (id, name, role, host, port, tls, nick, username, realname, saslMechanism,
                 autoConnect, ordering, restoreAutoConnect)
                VALUES (1, 'libera', 'DIRECT', 'irc.libera.chat', 6697, 1, 'me', 'me', 'Me',
                        'NONE', 1, 0, 0)""",
        )
        legacyHelper!!.close()
        legacyHelper = null

        val migrated =
            Room
                .databaseBuilder(context, MotdDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39)
                .build()
        try {
            migrated.openHelper.writableDatabase
                .query(
                    """SELECT nickServPassword, nickServIdentifySyntax, nickServRecoveryEnabled,
                          nickServRecoverySequence FROM networks WHERE id = 1""",
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    assertNull(cursor.getString(0))
                    assertNull(cursor.getString(1))
                    assertEquals(0, cursor.getInt(2))
                    assertNull(cursor.getString(3))
                }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateVersion29To30_preservesBuffersAndMessagesAndAddsWritableAvatarOverride() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        legacyHelper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(DB_NAME)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(29) {
                            override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion(db, 29)

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        legacyHelper!!.writableDatabase.apply {
            execSQL(
                """INSERT INTO networks
                    (id, name, role, host, port, tls, nick, username, realname, saslMechanism,
                     autoConnect, ordering, restoreAutoConnect, nickServRecoveryEnabled)
                    VALUES (1, 'libera', 'DIRECT', 'irc.libera.chat', 6697, 1, 'me', 'me', 'Me',
                            'NONE', 1, 0, 0, 0)""",
            )
            execSQL(
                """INSERT INTO buffers
                    (id, networkId, name, displayName, type, joined, membershipCycle, pinned, muted,
                     archived, ordering, historyComplete, dismissed)
                    VALUES (1, 1, '#motd', '#motd', 'CHANNEL', 1, 0, 0, 0, 0, 0, 0, 0)""",
            )
            execSQL(
                """INSERT INTO messages
                    (id, bufferId, serverTime, sender, normalizedActor, kind, text, isSelf,
                     hasMention, failed, dedupKey, serverTimeAuthoritative, timelineOrder,
                     timelineOrderConfirmed, timeProvenance, notificationHandled,
                     notificationClaimed, soundHandled)
                    VALUES (1, 1, 1000, 'alice', 'alice', 'PRIVMSG', 'hello', 0, 0, 0, 'm1',
                            1, 1, 0, 'SERVER_TAG', 0, 0, 0)""",
            )
        }
        legacyHelper!!.close()
        legacyHelper = null

        val migrated =
            Room
                .databaseBuilder(context, MotdDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39)
                .build()
        try {
            val sqlite = migrated.openHelper.writableDatabase
            sqlite.query("SELECT avatarOverrideModel FROM buffers WHERE id = 1").use { cursor ->
                check(cursor.moveToFirst())
                assertNull(cursor.getString(0))
            }
            sqlite.query("SELECT COUNT(*) FROM messages WHERE bufferId = 1").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            sqlite.execSQL("UPDATE buffers SET avatarOverrideModel = 'https://example.com/a.png' WHERE id = 1")
            sqlite.execSQL("UPDATE buffers SET avatarOverrideModel = 'file:///owned/a.image' WHERE id = 1")
            sqlite.query("SELECT avatarOverrideModel FROM buffers WHERE id = 1").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("file:///owned/a.image", cursor.getString(0))
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateVersion30To31_preservesPlainRowsAndAddsFormattedPayload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        legacyHelper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(DB_NAME)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(30) {
                            override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion(db, 30)

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        legacyHelper!!.writableDatabase.apply {
            execSQL(
                """INSERT INTO networks
                    (id, name, role, host, port, tls, nick, username, realname, saslMechanism,
                     autoConnect, ordering, restoreAutoConnect, nickServRecoveryEnabled)
                    VALUES (1, 'libera', 'DIRECT', 'irc.libera.chat', 6697, 1, 'me', 'me', 'Me',
                            'NONE', 1, 0, 0, 0)""",
            )
            execSQL(
                """INSERT INTO buffers
                    (id, networkId, name, displayName, type, joined, membershipCycle, pinned, muted,
                     archived, ordering, historyComplete, dismissed)
                    VALUES (1, 1, '#motd', '#motd', 'CHANNEL', 1, 0, 0, 0, 0, 0, 0, 0)""",
            )
            execSQL(
                """INSERT INTO messages
                    (id, bufferId, serverTime, sender, normalizedActor, kind, text, isSelf,
                     hasMention, failed, dedupKey, serverTimeAuthoritative, timelineOrder,
                     timelineOrderConfirmed, timeProvenance, notificationHandled,
                     notificationClaimed, soundHandled)
                    VALUES (1, 1, 1000, 'alice', 'alice', 'PRIVMSG', 'hello', 0, 0, 0, 'm1',
                            1, 1, 0, 'SERVER_TAG', 0, 0, 0)""",
            )
        }
        legacyHelper!!.close()
        legacyHelper = null

        val migrated =
            Room
                .databaseBuilder(context, MotdDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39)
                .build()
        try {
            val sqlite = migrated.openHelper.writableDatabase
            sqlite.query("SELECT text, ircFormattedText FROM messages WHERE id = 1").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("hello", cursor.getString(0))
                assertNull(cursor.getString(1))
            }
            sqlite.execSQL("UPDATE messages SET ircFormattedText = char(2) || 'hello' || char(2) WHERE id = 1")
            sqlite.query("SELECT length(ircFormattedText) FROM messages WHERE id = 1").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(7, cursor.getInt(0))
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrateVersion31To32_backfillsOnlyQueryMonitorActivityAndPreservesHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        legacyHelper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(DB_NAME)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(31) {
                            override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion(db, 31)

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        legacyHelper!!.writableDatabase.apply {
            execSQL(
                """INSERT INTO networks
                    (id, name, role, host, port, tls, nick, username, realname, saslMechanism,
                     autoConnect, ordering, restoreAutoConnect, nickServRecoveryEnabled)
                    VALUES (1, 'libera', 'DIRECT', 'irc.libera.chat', 6697, 1, 'me', 'me', 'Me',
                            'NONE', 1, 0, 0, 0)""",
            )
            execSQL(
                """INSERT INTO buffers
                    (id, networkId, name, displayName, type, joined, membershipCycle, pinned, muted,
                     archived, ordering, historyComplete, dismissed)
                    VALUES (1, 1, 'alice', 'Alice', 'QUERY', 0, 0, 0, 0, 0, 0, 0, 0),
                           (2, 1, '#motd', '#motd', 'CHANNEL', 1, 0, 0, 0, 0, 0, 0, 0)""",
            )
            execSQL(
                """INSERT INTO messages
                    (id, bufferId, serverTime, sender, normalizedActor, kind, text, isSelf,
                     hasMention, failed, dedupKey, serverTimeAuthoritative, timelineOrder,
                     timelineOrderConfirmed, timeProvenance, notificationHandled,
                     notificationClaimed, soundHandled, ircFormattedText)
                    VALUES (1, 1, 1000, 'alice', 'alice', 'PRIVMSG', 'hello', 0, 0, 0, 'm1',
                            1, 1, 0, 'SERVER_TAG', 0, 0, 0, NULL),
                           (2, 1, 2000, 'alice', 'alice', 'JOIN', 'joined', 0, 0, 0, 'm2',
                            1, 2, 0, 'SERVER_TAG', 0, 0, 0, NULL),
                           (3, 2, 3000, 'bob', 'bob', 'PRIVMSG', 'channel', 0, 1, 0, 'm3',
                            1, 3, 0, 'SERVER_TAG', 0, 0, 0, NULL)""",
            )
            execSQL("INSERT INTO users(networkId, nick, away) VALUES (1, 'alice', 0)")
            execSQL("INSERT INTO members(bufferId, nick, prefixes) VALUES (2, 'alice', '')")
        }
        legacyHelper!!.close()
        legacyHelper = null

        val migrated =
            Room
                .databaseBuilder(context, MotdDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39)
                .build()
        try {
            val sqlite = migrated.openHelper.writableDatabase
            sqlite.query("SELECT serverIconUrl FROM networks WHERE id = 1").use { cursor ->
                check(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
            sqlite.query("SELECT monitorActivityTime FROM buffers WHERE id = 1").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(1_000L, cursor.getLong(0))
            }
            sqlite.query("SELECT monitorActivityTime FROM buffers WHERE id = 2").use { cursor ->
                check(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
            sqlite.query("SELECT COUNT(*), SUM(isBot), COUNT(channelContext) FROM messages").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(3, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
            }
            sqlite.query("SELECT isBot FROM users UNION ALL SELECT isBot FROM members").use { cursor ->
                while (cursor.moveToNext()) assertEquals(0, cursor.getInt(0))
            }
            sqlite.query("PRAGMA index_list(messages)").use { cursor ->
                val name = cursor.getColumnIndexOrThrow("name")
                val names = buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
                assertTrue("mention index missing", "index_messages_bufferId_hasMention_isSelf_kind_serverTime_timelineOrder_id" in names)
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * The registered array is what `DbModule` hands Room on a real device, so a hop missing from it
     * is a crash-on-upgrade. Room only proves the hops it is asked to walk; this pins the array to a
     * gap-free, in-order 1 -> [DECLARED_VERSION] chain so a stray duplicate, an out-of-order entry,
     * or a version bump with no matching migration fails here instead of in the field.
     */
    @Test
    fun registeredMigrationsCoverEveryHopToTheDeclaredVersion() {
        // Keeps DECLARED_VERSION honest: Room exports one schema per version, so a newer JSON means
        // @Database moved on without this test.
        assertNotNull(
            "no exported schema for v$DECLARED_VERSION",
            schemaResource(DECLARED_VERSION),
        )
        assertNull(
            "MotdDatabase declares a version above $DECLARED_VERSION; " +
                "bump DECLARED_VERSION and add the migration to ALL_MIGRATIONS",
            schemaResource(DECLARED_VERSION + 1),
        )
        assertEquals(
            (1 until DECLARED_VERSION).map { it to it + 1 },
            ALL_MIGRATIONS.map { it.startVersion to it.endVersion },
        )
    }

    private fun schemaResource(version: Int): URL? = javaClass.classLoader?.getResource("${MotdDatabase::class.java.canonicalName}/$version.json")

    /** Creates the real v1 tables, indices, FTS triggers, and Room identity from the tracked JSON. */
    private fun createExportedVersion1(db: SupportSQLiteDatabase) = createExportedVersion(db, 1)

    private companion object {
        const val DB_NAME = "all-migrations-test.db"

        /**
         * Mirrors `version = 39` on `@Database`. Room's annotation is CLASS-retained, so the
         * declared version cannot be read reflectively; the exported schema JSON is the runtime
         * witness for it instead.
         */
        const val DECLARED_VERSION = 39
    }
}
