package io.github.trevarj.motd.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.PushPrefs
import io.github.trevarj.motd.data.prefs.PushProviderPrefs
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.BufferRepositoryImpl
import io.github.trevarj.motd.data.repo.ChatHistoryMediatorFactory
import io.github.trevarj.motd.data.repo.LinkPreviewRepository
import io.github.trevarj.motd.data.repo.LinkPreviewRepositoryImpl
import io.github.trevarj.motd.data.repo.MessageRepository
import io.github.trevarj.motd.data.repo.MessageRepositoryImpl
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.data.repo.NetworkRepositoryImpl
import io.github.trevarj.motd.data.repo.SearchRepository
import io.github.trevarj.motd.data.repo.SearchRepositoryImpl
import io.github.trevarj.motd.data.sync.ChatHistoryMediatorFactoryImpl
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.push.PushEventHandler
import io.github.trevarj.motd.push.PushNotifier
import io.github.trevarj.motd.push.UnifiedPushApi
import io.github.trevarj.motd.push.UnifiedPushApiImpl
import io.github.trevarj.motd.push.WebPushCryptoFacade
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.ForegroundBufferTracker
import io.github.trevarj.motd.service.IrcEventSink
import io.github.trevarj.motd.service.MotdNotifications
import io.github.trevarj.motd.service.PushNotifierImpl
import io.github.trevarj.motd.service.TypingTracker
import io.github.trevarj.motd.ui.settings.PushAvailabilityProvider
import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.AttachmentPrefsImpl
import io.github.trevarj.motd.attachment.AttachmentUploader
import io.github.trevarj.motd.attachment.AttachmentUploaderImpl
import javax.inject.Singleton

/**
 * App-level Hilt graph: every WP1 contract interface bound to the real WP4/WP5/WP9 implementation.
 * All impls are `@Inject constructor` `@Singleton` classes, so binding is a one-line `@Binds`.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AppModule {

    // -- data/repo (WP4) --
    @Binds @Singleton
    abstract fun networkRepository(impl: NetworkRepositoryImpl): NetworkRepository

    @Binds @Singleton
    abstract fun bufferRepository(impl: BufferRepositoryImpl): BufferRepository

    @Binds @Singleton
    abstract fun messageRepository(impl: MessageRepositoryImpl): MessageRepository

    @Binds @Singleton
    abstract fun searchRepository(impl: SearchRepositoryImpl): SearchRepository

    @Binds @Singleton
    abstract fun linkPreviewRepository(impl: LinkPreviewRepositoryImpl): LinkPreviewRepository

    // -- data/prefs (WP4): DataStoreSettingsRepository implements both seams --
    @Binds @Singleton
    abstract fun settingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds @Singleton
    abstract fun pushPrefs(impl: DataStoreSettingsRepository): PushPrefs

    @Binds @Singleton
    abstract fun pushProviderPrefs(impl: DataStoreSettingsRepository): PushProviderPrefs

    /** TOFU cert-pin store (plans/12); same DataStore-backed impl. */
    @Binds @Singleton
    abstract fun certTrustStore(impl: DataStoreSettingsRepository): CertTrustStore

    @Binds @Singleton
    abstract fun attachmentPrefs(impl: AttachmentPrefsImpl): AttachmentPrefs

    @Binds @Singleton
    abstract fun attachmentUploader(impl: AttachmentUploaderImpl): AttachmentUploader

    // -- data/sync (WP5) --
    @Binds @Singleton
    abstract fun chatHistoryMediatorFactory(impl: ChatHistoryMediatorFactoryImpl): ChatHistoryMediatorFactory

    @Binds @Singleton
    abstract fun typingTracker(impl: TypingTrackerImpl): TypingTracker

    /** EventProcessor is the sole IRC→Room writer (WP5). */
    @Binds @Singleton
    abstract fun ircEventSink(impl: EventProcessor): IrcEventSink

    /** Message/mention notification hook consumed by EventProcessor (WP5 seam → MotdNotifications). */
    @Binds @Singleton
    abstract fun messageNotifier(impl: MotdNotifications): MessageNotifier

    // -- service (WP5 / WP1 trivial) --
    @Binds @Singleton
    abstract fun foregroundBufferTracker(impl: ForegroundBufferTrackerImpl): ForegroundBufferTracker

    // -- push (WP9 / WP-R2) --
    @Binds @Singleton
    abstract fun pushNotifier(impl: PushNotifierImpl): PushNotifier

    /** UnifiedPush static-connector seam → real impl (WP-R2). */
    @Binds @Singleton
    abstract fun unifiedPushApi(impl: UnifiedPushApiImpl): UnifiedPushApi

    companion object {
        /**
         * WP9's [PushEventHandler] `@Inject` constructor hardcodes a no-op notifier; provide it
         * explicitly with the real [PushNotifier] so pushed messages post notifications.
         */
        @Provides
        @Singleton
        fun pushEventHandler(sink: IrcEventSink, notifier: PushNotifier): PushEventHandler =
            PushEventHandler(WebPushCryptoFacade.Default, sink, notifier)

        /**
         * Real UnifiedPush availability check: an installed distributor AND a connected client
         * advertising `soju.im/webpush`. Replaces WP1's conservative default.
         */
        @Provides
        @Singleton
        fun pushAvailabilityProvider(
            @ApplicationContext context: Context,
            connectionManager: ConnectionManager,
        ): PushAvailabilityProvider = RealPushAvailabilityProvider(context, connectionManager)
    }
}
