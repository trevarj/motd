package io.github.trevarj.motd.di

import android.content.Context
import androidx.room.Room
import io.github.trevarj.motd.avatar.AvatarDao
import io.github.trevarj.motd.avatar.AvatarDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.MemberDao
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.MIGRATION_1_2
import io.github.trevarj.motd.data.db.MIGRATION_2_3
import io.github.trevarj.motd.data.db.MIGRATION_3_4
import io.github.trevarj.motd.data.db.MIGRATION_4_5
import io.github.trevarj.motd.data.db.MIGRATION_5_6
import io.github.trevarj.motd.data.db.MIGRATION_6_7
import io.github.trevarj.motd.data.db.MIGRATION_7_8
import io.github.trevarj.motd.data.db.MIGRATION_8_9
import io.github.trevarj.motd.data.db.MIGRATION_9_10
import io.github.trevarj.motd.data.db.MIGRATION_10_11
import io.github.trevarj.motd.data.db.MIGRATION_11_12
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.data.db.ReactionDao
import io.github.trevarj.motd.data.db.UserDao
import io.github.trevarj.motd.data.db.HistoryCursorDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DbModule {

    @Provides
    @Singleton
    fun provideAvatarDatabase(@ApplicationContext context: Context): AvatarDatabase =
        Room.databaseBuilder(context, AvatarDatabase::class.java, "avatars.db").build()

    @Provides fun provideAvatarDao(db: AvatarDatabase): AvatarDao = db.avatarDao()

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MotdDatabase =
        // v10 intentionally resets IRC-derived cache state while preserving saved networks and
        // credentials; every other registered upgrade remains non-destructive.
        Room.databaseBuilder(context, MotdDatabase::class.java, "motd.db")
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
            )
            // Downgrades only happen in dev when switching between branches with different schema
            // versions (e.g. the obfs branch's v3 vs main's v2); released builds only ever move the
            // version up. Wipe-and-recreate on downgrade instead of crashing on a missing migration.
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides fun provideNetworkDao(db: MotdDatabase): NetworkDao = db.networkDao()
    @Provides fun provideBufferDao(db: MotdDatabase): BufferDao = db.bufferDao()
    @Provides fun provideMessageDao(db: MotdDatabase): MessageDao = db.messageDao()
    @Provides fun provideMemberDao(db: MotdDatabase): MemberDao = db.memberDao()
    @Provides fun provideReactionDao(db: MotdDatabase): ReactionDao = db.reactionDao()
    @Provides fun provideUserDao(db: MotdDatabase): UserDao = db.userDao()
    @Provides fun provideHistoryCursorDao(db: MotdDatabase): HistoryCursorDao = db.historyCursorDao()
}
