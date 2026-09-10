package io.github.trevarj.motd.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.trevarj.motd.avatar.AvatarDao
import io.github.trevarj.motd.avatar.AvatarDatabase
import io.github.trevarj.motd.data.db.ALL_MIGRATIONS
import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.ChatFolderDao
import io.github.trevarj.motd.data.db.DccTransferDao
import io.github.trevarj.motd.data.db.HistoryCursorDao
import io.github.trevarj.motd.data.db.HistoryGapDao
import io.github.trevarj.motd.data.db.HistoryPruneDao
import io.github.trevarj.motd.data.db.MemberDao
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.data.db.NetworkIdentityDao
import io.github.trevarj.motd.data.db.NetworkIgnoreDao
import io.github.trevarj.motd.data.db.ReactionDao
import io.github.trevarj.motd.data.db.UserDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DbModule {
    @Provides
    @Singleton
    fun provideAvatarDatabase(
        @ApplicationContext context: Context,
    ): AvatarDatabase = Room.databaseBuilder(context, AvatarDatabase::class.java, "avatars.db").build()

    @Provides fun provideAvatarDao(db: AvatarDatabase): AvatarDao = db.avatarDao()

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): MotdDatabase =
        // v10 intentionally resets IRC-derived cache state while preserving saved networks and
        // credentials; every other registered upgrade remains non-destructive.
        Room
            .databaseBuilder(context, MotdDatabase::class.java, "motd.db")
            // Shared with the migration tests so a newly added upgrade can never be registered in
            // one place and forgotten in the other.
            .addMigrations(*ALL_MIGRATIONS)
            // Downgrades only happen in dev when switching between branches with different schema
            // versions (e.g. the obfs branch's v3 vs main's v2); released builds only ever move the
            // version up. Wipe-and-recreate on downgrade instead of crashing on a missing migration.
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides fun provideNetworkDao(db: MotdDatabase): NetworkDao = db.networkDao()

    @Provides fun provideNetworkIdentityDao(db: MotdDatabase): NetworkIdentityDao = db.networkIdentityDao()

    @Provides fun provideNetworkIgnoreDao(db: MotdDatabase): NetworkIgnoreDao = db.networkIgnoreDao()

    @Provides fun provideChatFolderDao(db: MotdDatabase): ChatFolderDao = db.chatFolderDao()

    @Provides fun provideBufferDao(db: MotdDatabase): BufferDao = db.bufferDao()

    @Provides fun provideMessageDao(db: MotdDatabase): MessageDao = db.messageDao()

    @Provides fun provideMemberDao(db: MotdDatabase): MemberDao = db.memberDao()

    @Provides fun provideReactionDao(db: MotdDatabase): ReactionDao = db.reactionDao()

    @Provides fun provideUserDao(db: MotdDatabase): UserDao = db.userDao()

    @Provides fun provideDccTransferDao(db: MotdDatabase): DccTransferDao = db.dccTransferDao()

    @Provides fun provideHistoryCursorDao(db: MotdDatabase): HistoryCursorDao = db.historyCursorDao()

    @Provides fun provideHistoryGapDao(db: MotdDatabase): HistoryGapDao = db.historyGapDao()

    @Provides fun provideHistoryPruneDao(db: MotdDatabase): HistoryPruneDao = db.historyPruneDao()
}
