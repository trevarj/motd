package io.github.trevarj.motd.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// WP4 owns the real database wiring (MotdDatabase + DAO providers). WP1 leaves this empty:
// the WP1 stub repositories in AppModule don't touch Room, so no DAO/database provider is
// needed yet. Kept as a placeholder module so WP4/WP10 have a home for @Provides bindings.
@Module
@InstallIn(SingletonComponent::class)
internal object DbModule
