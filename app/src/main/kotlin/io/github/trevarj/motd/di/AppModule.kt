package io.github.trevarj.motd.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.trevarj.motd.data.prefs.PushPrefs
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.ChatHistoryMediatorFactory
import io.github.trevarj.motd.data.repo.LinkPreviewRepository
import io.github.trevarj.motd.data.repo.MessageRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.data.repo.SearchRepository
import io.github.trevarj.motd.service.ForegroundBufferTracker
import io.github.trevarj.motd.service.TypingTracker
import javax.inject.Singleton

// Binds every app-level interface to its WP1 stub impl. WP10 rebinds to real impls.
@Suppress("DEPRECATION")
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AppModule {
    @Binds @Singleton
    abstract fun networkRepository(impl: StubNetworkRepository): NetworkRepository

    @Binds @Singleton
    abstract fun bufferRepository(impl: StubBufferRepository): BufferRepository

    @Binds @Singleton
    abstract fun messageRepository(impl: StubMessageRepository): MessageRepository

    @Binds @Singleton
    abstract fun searchRepository(impl: StubSearchRepository): SearchRepository

    @Binds @Singleton
    abstract fun linkPreviewRepository(impl: StubLinkPreviewRepository): LinkPreviewRepository

    @Binds @Singleton
    abstract fun settingsRepository(impl: StubSettingsRepository): SettingsRepository

    @Binds @Singleton
    abstract fun pushPrefs(impl: StubPushPrefs): PushPrefs

    @Binds @Singleton
    abstract fun chatHistoryMediatorFactory(impl: StubChatHistoryMediatorFactory): ChatHistoryMediatorFactory

    @Binds @Singleton
    abstract fun typingTracker(impl: StubTypingTracker): TypingTracker

    @Binds @Singleton
    abstract fun foregroundBufferTracker(impl: StubForegroundBufferTracker): ForegroundBufferTracker
}
