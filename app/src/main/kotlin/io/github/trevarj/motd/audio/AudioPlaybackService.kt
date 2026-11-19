package io.github.trevarj.motd.audio
import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import dagger.hilt.android.AndroidEntryPoint
import io.github.trevarj.motd.R
import javax.inject.Inject

@AndroidEntryPoint
class AudioPlaybackService : MediaSessionService() {
    @Inject lateinit var audioMediaCache: AudioMediaCache
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(SystemAdaptiveMediaNotificationProvider(applicationContext))
        val mediaSourceFactory = DefaultMediaSourceFactory(audioMediaCache.dataSourceFactory())
        val loadControl =
            DefaultLoadControl
                .Builder()
                .setBufferDurationsMsForStreaming(
                    MIN_BUFFER_MS,
                    MAX_BUFFER_MS,
                    PLAYBACK_START_BUFFER_MS,
                    PLAYBACK_REBUFFER_MS,
                ).build()
        val exoPlayer =
            ExoPlayer
                .Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .build()
                .apply {
                    setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                            .build(),
                        true,
                    )
                }
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    private companion object {
        const val MIN_BUFFER_MS = 15_000
        const val MAX_BUFFER_MS = 60_000
        const val PLAYBACK_START_BUFFER_MS = 5_000
        const val PLAYBACK_REBUFFER_MS = 5_000
    }
}

@OptIn(UnstableApi::class)
internal class SystemAdaptiveMediaNotificationProvider(
    context: Context,
) : MediaNotification.Provider {
    private val context = context.applicationContext
    private val delegate =
        DefaultMediaNotificationProvider
            .Builder(this.context)
            .build()
            .apply { setSmallIcon(R.drawable.ic_notification_motd) }

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification =
        delegate
            .createNotification(
                mediaSession,
                mediaButtonPreferences,
                actionFactory,
            ) { changed ->
                onNotificationChangedCallback.onNotificationChanged(changed.withSystemAdaptiveColors(context))
            }.withSystemAdaptiveColors(context)

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle,
    ): Boolean = delegate.handleCustomCommand(session, action, extras)

    override fun getNotificationChannelInfo() = delegate.notificationChannelInfo
}

internal fun MediaNotification.withSystemAdaptiveColors(context: Context): MediaNotification =
    MediaNotification(
        notificationId,
        NotificationCompat
            .Builder(context, notification)
            .setColorized(false)
            .build(),
    )
