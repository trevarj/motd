package io.github.trevarj.motd.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Single app database. Destructive fallback is deliberately NOT configured (see plans/04) so schema
// drift surfaces in review instead of wiping real user data/history; schema changes ship a proper
// Migration instead.
@Database(
    entities = [
        NetworkEntity::class,
        BufferEntity::class,
        MessageEntity::class,
        MessageFtsEntity::class,
        ReactionEntity::class,
        UserEntity::class,
        MemberEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MotdDatabase : RoomDatabase() {
    abstract fun networkDao(): NetworkDao
    abstract fun bufferDao(): BufferDao
    abstract fun messageDao(): MessageDao
    abstract fun memberDao(): MemberDao
    abstract fun reactionDao(): ReactionDao
    abstract fun userDao(): UserDao
}

/**
 * v1 -> v2: add the nullable `wsUrl` column on `networks` for the opt-in IRC-over-WebSocket
 * transport (plans/19 §3.3). Non-destructive additive change: TEXT, nullable, no default, so all
 * existing rows keep `wsUrl = NULL` (TCP/TLS) and every message/buffer/history row is preserved.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE networks ADD COLUMN wsUrl TEXT")
    }
}

/**
 * v2 -> v3: add the UNIQUE(bufferId, msgid) index on `messages` so a self message can never surface
 * twice (the durable msgid becomes the dedup identity, goguma-style). Non-destructive: first collapse
 * any pre-existing duplicate-msgid rows a buggy build may have written — keep the lowest row id per
 * (bufferId, msgid) and delete the rest (their content is identical) — then create the unique index.
 * NULL msgids are untouched: SQLite treats them as distinct, so still-pending / msgid-less rows keep
 * coexisting. Room's FTS sync triggers cascade the dropped rows out of messages_fts automatically.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """DELETE FROM messages WHERE msgid IS NOT NULL AND id NOT IN (
                 SELECT MIN(id) FROM messages WHERE msgid IS NOT NULL GROUP BY bufferId, msgid
               )""",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_messages_bufferId_msgid ON messages(bufferId, msgid)",
        )
    }
}

/**
 * v3 -> v4: add the nullable SOCKS5/Tor transport settings on `networks`. Existing rows remain
 * direct connections until the user explicitly enables an obfuscation mode.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addNetworkColumnsIfMissing(
            "obfsMode" to "TEXT",
            "proxyHost" to "TEXT",
            "proxyPort" to "INTEGER",
        )
    }
}

/**
 * v4 -> v5: add the nullable VLESS+REALITY share link. The embedded core owns its loopback SOCKS
 * endpoint, so this deliberately remains separate from the user-editable proxy host and port.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // An unreleased development build used schema version 3 for this column. MIGRATION_3_4
        // leaves it intact when upgrading that database, so this must be safe in both paths.
        db.addNetworkColumnsIfMissing("obfsLink" to "TEXT")
    }
}

/**
 * v5 -> v6: add versioned typed-event persistence for invitations and collapsed network batches.
 * All columns are nullable so existing chat/history/FTS rows retain their exact semantics. The
 * partial-in-practice UNIQUE index relies on SQLite's distinct NULL handling: ordinary messages
 * remain unrestricted while a typed event key can occur only once per buffer.
 *
 * Downgrading a development database still follows DbModule's explicitly destructive dev-only
 * policy; released databases only move forward through this additive migration.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN eventKey TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN eventPayload TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN inviteState TEXT")
        db.execSQL("ALTER TABLE users ADD COLUMN username TEXT")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_messages_bufferId_eventKey " +
                "ON messages(bufferId, eventKey)",
        )
    }
}

/**
 * v6 -> v7: add a nullable IRC server password. Existing connections continue without PASS;
 * the value is deliberately separate from SASL credentials because IRC servers may require both.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE networks ADD COLUMN serverPassword TEXT")
    }
}

private fun SupportSQLiteDatabase.addNetworkColumnsIfMissing(vararg columns: Pair<String, String>) {
    val existing = buildSet {
        query("PRAGMA table_info(`networks`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) add(cursor.getString(nameColumn))
        }
    }
    columns.forEach { (name, type) ->
        if (name !in existing) execSQL("ALTER TABLE networks ADD COLUMN $name $type")
    }
}
