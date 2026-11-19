package io.github.trevarj.motd.audio

import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSessionService
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioPlaybackServiceManifestTest {
    @Test fun mediaSessionServiceIsResolvableByMedia3() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val services =
            context.packageManager.queryIntentServices(
                Intent(MediaSessionService.SERVICE_INTERFACE).setPackage(context.packageName),
                0,
            )

        assertTrue(
            services.any { it.serviceInfo.name == AudioPlaybackService::class.java.name },
        )
    }

    @OptIn(UnstableApi::class)
    @Test
    fun playbackNotificationPreservesStatusGlyphAndUsesSystemSurfaceColors() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source =
            NotificationCompat
                .Builder(context, "playback")
                .setSmallIcon(R.drawable.ic_notification_motd)
                .setContentTitle("Voice message")
                .setContentText("#motd")
                .setColorized(true)
                .setOngoing(true)
                .build()

        val adapted = MediaNotification(42, source).withSystemAdaptiveColors(context)

        assertEquals(42, adapted.notificationId)
        assertEquals("playback", adapted.notification.channelId)
        assertEquals(R.drawable.ic_notification_motd, adapted.notification.smallIcon.resId)
        assertEquals("Voice message", adapted.notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("#motd", adapted.notification.extras.getString(Notification.EXTRA_TEXT))
        assertTrue(adapted.notification.extras.containsKey(Notification.EXTRA_COLORIZED))
        assertFalse(adapted.notification.extras.getBoolean(Notification.EXTRA_COLORIZED))
        assertTrue(adapted.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }
}
