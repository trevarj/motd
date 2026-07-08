package io.github.trevarj.motd.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

// Single app database. Version 1, no migrations in v1; destructive fallback is deliberately
// NOT configured (see plans/04) so schema drift surfaces in review instead of wiping data.
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
    version = 1,
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
