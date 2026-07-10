package io.github.trevarj.motd.di

import android.content.Context
import androidx.room.Room
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
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.data.db.ReactionDao
import io.github.trevarj.motd.data.db.UserDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DbModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MotdDatabase =
        // Upgrades stay non-destructive (plans/04): the release app holds real user history.
        Room.databaseBuilder(context, MotdDatabase::class.java, "motd.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
}
