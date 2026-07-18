package io.github.trevarj.motd.avatar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.prefs.AvatarStyle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationAvatarTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun notificationAvatar_createsABitmapForEachStyle() {
        val monogram = notificationAvatarBitmap(context, "alice", AvatarStyle.MONOGRAM)
        val initials = notificationAvatarBitmap(context, "alice", AvatarStyle.INITIALS)
        val sprite = notificationAvatarBitmap(context, "alice", AvatarStyle.IRC_SPRITE)

        assertEquals(64, monogram.width)
        assertEquals(64, initials.width)
        assertEquals(64, sprite.width)
    }
}
