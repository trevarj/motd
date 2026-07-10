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
    version = 3,
    exportSchema = false,
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
