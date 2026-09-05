package io.github.trevarj.motd.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.AttachmentPrefsImpl
import io.github.trevarj.motd.attachment.AttachmentUploader
import io.github.trevarj.motd.attachment.AttachmentUploaderImpl
import io.github.trevarj.motd.audio.AndroidVoiceRecorder
import io.github.trevarj.motd.audio.AudioMetadataRepository
import io.github.trevarj.motd.audio.AudioMetadataRepositoryImpl
import io.github.trevarj.motd.audio.AudioPlaybackController
import io.github.trevarj.motd.audio.AudioPlaybackControllerImpl
import io.github.trevarj.motd.audio.DirectMediaPolicy
import io.github.trevarj.motd.audio.MediaRouteResolver
import io.github.trevarj.motd.audio.NetworkMediaRouteProvider
import io.github.trevarj.motd.audio.VoiceMessageSender
import io.github.trevarj.motd.audio.VoiceMessageSenderImpl
import io.github.trevarj.motd.audio.VoicePrefs
import io.github.trevarj.motd.audio.VoiceRecorder
import io.github.trevarj.motd.avatar.AvatarController
import io.github.trevarj.motd.avatar.AvatarCoordinator
import io.github.trevarj.motd.avatar.AvatarPrefs
import io.github.trevarj.motd.avatar.AvatarPrefsImpl
import io.github.trevarj.motd.avatar.AvatarStore
import io.github.trevarj.motd.avatar.AvatarStoreImpl
import io.github.trevarj.motd.bouncer.BouncerServClient
import io.github.trevarj.motd.bouncer.BouncerServClientImpl
import io.github.trevarj.motd.bouncer.BouncerServSessionProvider
import io.github.trevarj.motd.bouncer.ConnectionBouncerServSessionProvider
import io.github.trevarj.motd.data.backup.ConfigurationBackupRepository
import io.github.trevarj.motd.data.backup.ConfigurationBackupRepositoryImpl
import io.github.trevarj.motd.data.prefs.AccountReminderStore
import io.github.trevarj.motd.data.prefs.AppearancePrefs
import io.github.trevarj.motd.data.prefs.AppearancePrefsImpl
import io.github.trevarj.motd.data.prefs.BouncerKindPrefs
import io.github.trevarj.motd.data.prefs.BouncerKindPrefsImpl
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefsImpl
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.GlobalFeedPrefs
import io.github.trevarj.motd.data.prefs.GlobalFeedPrefsImpl
import io.github.trevarj.motd.data.prefs.HistorySyncPrefs
import io.github.trevarj.motd.data.prefs.HistorySyncPrefsImpl
import io.github.trevarj.motd.data.prefs.InviteEnrollmentCleanup
import io.github.trevarj.motd.data.prefs.InviteEnrollmentStore
import io.github.trevarj.motd.data.prefs.OnboardingPrefs
import io.github.trevarj.motd.data.prefs.OnboardingPrefsImpl
import io.github.trevarj.motd.data.prefs.PresetEnrollmentPrefs
import io.github.trevarj.motd.data.prefs.PresetEnrollmentPrefsImpl
import io.github.trevarj.motd.data.prefs.PushPrefs
import io.github.trevarj.motd.data.prefs.ReplyPrefs
import io.github.trevarj.motd.data.prefs.ReplyPrefsImpl
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.BufferRepositoryImpl
import io.github.trevarj.motd.data.repo.ChatHistoryMediatorFactory
import io.github.trevarj.motd.data.repo.GlobalFeedRepository
import io.github.trevarj.motd.data.repo.GlobalFeedRepositoryImpl
import io.github.trevarj.motd.data.repo.LinkPreviewFetchPolicy
import io.github.trevarj.motd.data.repo.LinkPreviewRepository
import io.github.trevarj.motd.data.repo.LinkPreviewRepositoryImpl
import io.github.trevarj.motd.data.repo.MessageRepository
import io.github.trevarj.motd.data.repo.MessageRepositoryImpl
import io.github.trevarj.motd.data.repo.NetworkIgnoreRepository
import io.github.trevarj.motd.data.repo.NetworkIgnoreRepositoryImpl
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.data.repo.NetworkRepositoryImpl
import io.github.trevarj.motd.data.repo.SearchRepository
import io.github.trevarj.motd.data.repo.SearchRepositoryImpl
import io.github.trevarj.motd.data.sync.ChatHistoryMediatorFactoryImpl
import io.github.trevarj.motd.data.sync.ChatSoundPlayer
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.HistoryGapFillCoordinator
import io.github.trevarj.motd.data.sync.HistoryGapFiller
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.dcc.DccTransferController
import io.github.trevarj.motd.dcc.DccTransferControllerImpl
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.diagnostics.FileDiagnosticLogger
import io.github.trevarj.motd.gesture.GesturePrefs
import io.github.trevarj.motd.gesture.GesturePrefsImpl
import io.github.trevarj.motd.push.DataStorePushHealthStore
import io.github.trevarj.motd.push.PushEventHandler
import io.github.trevarj.motd.push.PushHealthStore
import io.github.trevarj.motd.push.UnifiedPushApi
import io.github.trevarj.motd.push.UnifiedPushApiImpl
import io.github.trevarj.motd.push.WebPushCryptoFacade
import io.github.trevarj.motd.service.AndroidChatSoundPlayer
import io.github.trevarj.motd.service.AppVisibility
import io.github.trevarj.motd.service.ChannelCloseCoordinator
import io.github.trevarj.motd.service.ChannelWatch
import io.github.trevarj.motd.service.ChannelWatchImpl
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.ForegroundBufferTracker
import io.github.trevarj.motd.service.HistoryResyncController
import io.github.trevarj.motd.service.HistoryResyncCoordinator
import io.github.trevarj.motd.service.IrcEventSink
import io.github.trevarj.motd.service.MotdNotifications
import io.github.trevarj.motd.service.PendingChannelCloseCoordinator
import io.github.trevarj.motd.service.ReadMarkerRepository
import io.github.trevarj.motd.service.ReadMarkerSnapshotter
import io.github.trevarj.motd.service.TypingTracker
import io.github.trevarj.motd.ui.onboarding.ConnectionManagerOnboardingBouncerOperations
import io.github.trevarj.motd.ui.onboarding.OnboardingBouncerOperations
import io.github.trevarj.motd.ui.settings.PushAvailabilityProvider
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
    abstract fun networkIgnoreRepository(impl: NetworkIgnoreRepositoryImpl): NetworkIgnoreRepository

    @Binds @Singleton
    abstract fun bufferRepository(impl: BufferRepositoryImpl): BufferRepository

    @Binds @Singleton
    abstract fun messageRepository(impl: MessageRepositoryImpl): MessageRepository

    @Binds @Singleton
    abstract fun searchRepository(impl: SearchRepositoryImpl): SearchRepository

    @Binds @Singleton
    abstract fun globalFeedRepository(impl: GlobalFeedRepositoryImpl): GlobalFeedRepository

    @Binds @Singleton
    abstract fun linkPreviewRepository(impl: LinkPreviewRepositoryImpl): LinkPreviewRepository

    @Binds @Singleton
    abstract fun configurationBackupRepository(
        impl: ConfigurationBackupRepositoryImpl,
    ): ConfigurationBackupRepository

    // -- data/prefs (WP4): DataStoreSettingsRepository implements both seams --
    @Binds @Singleton
    abstract fun settingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds @Singleton
    abstract fun appearancePrefs(impl: AppearancePrefsImpl): AppearancePrefs

    @Binds @Singleton
    abstract fun contentPreviewPrefs(impl: ContentPreviewPrefsImpl): ContentPreviewPrefs

    @Binds @Singleton
    abstract fun bouncerKindPrefs(impl: BouncerKindPrefsImpl): BouncerKindPrefs

    @Binds @Singleton
    abstract fun presetEnrollmentPrefs(impl: PresetEnrollmentPrefsImpl): PresetEnrollmentPrefs

    @Binds @Singleton
    abstract fun historySyncPrefs(impl: HistorySyncPrefsImpl): HistorySyncPrefs

    @Binds @Singleton
    abstract fun onboardingPrefs(impl: OnboardingPrefsImpl): OnboardingPrefs

    @Binds @Singleton
    abstract fun inviteEnrollmentCleanup(impl: InviteEnrollmentStore): InviteEnrollmentCleanup

    @Binds @Singleton
    abstract fun accountReminderStore(impl: InviteEnrollmentStore): AccountReminderStore

    @Binds @Singleton
    abstract fun historyResyncController(impl: HistoryResyncCoordinator): HistoryResyncController

    @Binds @Singleton
    abstract fun replyPrefs(impl: ReplyPrefsImpl): ReplyPrefs

    @Binds @Singleton
    abstract fun pushPrefs(impl: DataStoreSettingsRepository): PushPrefs

    /** TOFU cert-pin store; same DataStore-backed impl. */
    @Binds @Singleton
    abstract fun certTrustStore(impl: DataStoreSettingsRepository): CertTrustStore

    @Binds @Singleton
    abstract fun attachmentPrefs(impl: AttachmentPrefsImpl): AttachmentPrefs

    @Binds @Singleton
    abstract fun attachmentUploader(impl: AttachmentUploaderImpl): AttachmentUploader

    /** Gesture lab store; kept out of configuration backup like the Agentwire lab flag. */
    @Binds @Singleton
    abstract fun gesturePrefs(impl: GesturePrefsImpl): GesturePrefs

    /** Global Feed lab store; same backup-excluded rule as the other lab flags. */
    @Binds @Singleton
    abstract fun globalFeedPrefs(impl: GlobalFeedPrefsImpl): GlobalFeedPrefs

    @Binds @Singleton
    abstract fun voiceRecorder(impl: AndroidVoiceRecorder): VoiceRecorder

    @Binds @Singleton
    abstract fun voiceMessageSender(impl: VoiceMessageSenderImpl): VoiceMessageSender

    @Binds @Singleton
    abstract fun dccTransferController(impl: DccTransferControllerImpl): DccTransferController

    @Binds @Singleton
    abstract fun audioMetadataRepository(impl: AudioMetadataRepositoryImpl): AudioMetadataRepository

    /** Proxy-aware route lookup for HTTP fetch repositories (link previews, audio metadata). */
    @Binds @Singleton
    abstract fun mediaRouteResolver(impl: NetworkMediaRouteProvider): MediaRouteResolver

    /** Whether the global Coil/ExoPlayer stacks may fetch directly for one network's content. */
    @Binds @Singleton
    abstract fun directMediaPolicy(impl: NetworkMediaRouteProvider): DirectMediaPolicy

    @Binds @Singleton
    abstract fun audioPlaybackController(impl: AudioPlaybackControllerImpl): AudioPlaybackController

    @Binds @Singleton
    abstract fun avatarPrefs(impl: AvatarPrefsImpl): AvatarPrefs

    @Binds @Singleton
    abstract fun avatarStore(impl: AvatarStoreImpl): AvatarStore

    @Binds @Singleton
    abstract fun avatarController(impl: AvatarCoordinator): AvatarController

    @Binds @Singleton
    abstract fun bouncerServClient(impl: BouncerServClientImpl): BouncerServClient

    @Binds @Singleton
    abstract fun onboardingBouncerOperations(
        impl: ConnectionManagerOnboardingBouncerOperations,
    ): OnboardingBouncerOperations

    @Binds @Singleton
    abstract fun bouncerServSessionProvider(
        impl: ConnectionBouncerServSessionProvider,
    ): BouncerServSessionProvider

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

    @Binds @Singleton
    abstract fun chatSoundPlayer(impl: AndroidChatSoundPlayer): ChatSoundPlayer

    @Binds @Singleton
    abstract fun channelWatch(impl: ChannelWatchImpl): ChannelWatch

    @Binds @Singleton
    abstract fun diagnosticLogger(impl: FileDiagnosticLogger): DiagnosticLogger

    // -- service (WP5 / WP1 trivial) --
    @Binds @Singleton
    abstract fun foregroundBufferTracker(impl: ForegroundBufferTrackerImpl): ForegroundBufferTracker

    @Binds @Singleton
    abstract fun appVisibility(impl: AppVisibilityImpl): AppVisibility

    /** Durable channel PART retry worker; started when the chat list ViewModel is used. */
    @Binds @Singleton
    abstract fun channelCloseCoordinator(impl: PendingChannelCloseCoordinator): ChannelCloseCoordinator

    @Binds @Singleton
    abstract fun readMarkerSnapshotter(
        impl: ReadMarkerRepository,
    ): ReadMarkerSnapshotter

    // -- push (WP9 / WP-R2) --

    /** UnifiedPush static-connector seam → real impl (WP-R2). */
    @Binds @Singleton
    abstract fun unifiedPushApi(impl: UnifiedPushApiImpl): UnifiedPushApi

    @Binds @Singleton
    abstract fun pushHealthStore(impl: DataStorePushHealthStore): PushHealthStore

    companion object {
        /** Production link-preview fetch limits; tests construct relaxed policies directly. */
        @Provides
        @Singleton
        fun linkPreviewFetchPolicy(): LinkPreviewFetchPolicy = LinkPreviewFetchPolicy()

        /**
         * The timeline's narrow view of the gap-fill coordinator. Adapted rather than bound,
         * because the coordinator is a concrete collaborator of the sync layer and stays that way;
         * only the tap-a-seam action, the autopilot's arm, and the in-flight ids reach the UI.
         */
        @Provides
        @Singleton
        fun historyGapFiller(coordinator: HistoryGapFillCoordinator): HistoryGapFiller =
            object : HistoryGapFiller {
                override val fillsInFlight = coordinator.fillsInFlight

                override suspend fun fillGap(
                    roomId: Long,
                    gapId: Long,
                ) = coordinator.fillGap(roomId, gapId).progress
            }

        /** Provide the real crypto/health collaborators; EventProcessor owns notification policy. */
        @Provides
        @Singleton
        fun pushEventHandler(
            sink: IrcEventSink,
            healthStore: PushHealthStore,
            diagnosticLogger: DiagnosticLogger,
        ): PushEventHandler =
            PushEventHandler(
                WebPushCryptoFacade.Default,
                sink,
                healthStore,
                diagnosticLogger,
            )

        /**
         * Real UnifiedPush availability check: an installed distributor AND a connected client
         * advertising `soju.im/webpush`. Replaces WP1's conservative default.
         */
        @Provides
        @Singleton
        fun pushAvailabilityProvider(
            @ApplicationContext context: Context,
            connectionManager: ConnectionManager,
            db: io.github.trevarj.motd.data.db.MotdDatabase,
            healthStore: PushHealthStore,
            unifiedPush: UnifiedPushApi,
            notificationPermission: NotificationPermissionStatus,
        ): PushAvailabilityProvider =
            RealPushAvailabilityProvider(
                context,
                connectionManager,
                db.networkDao(),
                healthStore,
                unifiedPush,
                notificationPermission,
            )
    }
}
