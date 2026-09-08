package io.github.trevarj.motd.avatar

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.ui.theme.paletteNickColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationAvatarTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun notificationAvatar_createsABitmapForEachStyle() {
        val monogram = notificationAvatarBitmap(context, "alice", AvatarStyle.MONOGRAM)
        val initials = notificationAvatarBitmap(context, "alice", AvatarStyle.INITIALS)
        val sprite = notificationAvatarBitmap(context, "alice", AvatarStyle.IRC_SPRITE)
        val spriteV2 = notificationAvatarBitmap(context, "alice", AvatarStyle.IRC_SPRITE_V2)

        assertEquals(64, monogram.width)
        assertEquals(64, initials.width)
        assertEquals(64, sprite.width)
        assertEquals(64, spriteV2.width)
    }

    /** Hiding avatars is an in-app choice; system UI still needs a person icon to post. */
    @Test fun notificationAvatar_fallsBackToInitialsWhenAvatarsAreHidden() {
        val hidden = notificationAvatarBitmap(context, "alice", AvatarStyle.NONE)
        val initials = notificationAvatarBitmap(context, "alice", AvatarStyle.INITIALS)

        assertEquals(64, hidden.width)
        assertTrue(hidden.sameAs(initials))
    }

    @Test fun notificationV2_usesTheSharedRasterTraitMapping() {
        val name = "alice"
        val dark =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        val accent = paletteNickColor(canonicalAvatarNick(name), dark, NickColorPalette.CLASSIC).toArgb()
        val expected =
            IrcSpriteV2Renderer.render(
                context,
                name,
                accent,
                64,
                if (dark) Color.rgb(54, 52, 59) else Color.rgb(243, 241, 248),
                ColorUtils.setAlphaComponent(accent, 133),
            )

        assertTrue(expected != null)
        assertTrue(notificationAvatarBitmap(context, name, AvatarStyle.IRC_SPRITE_V2).sameAs(expected))
    }
}
