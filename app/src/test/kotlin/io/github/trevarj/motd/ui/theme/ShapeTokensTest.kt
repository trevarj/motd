package io.github.trevarj.motd.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.data.prefs.AvatarShape
import io.github.trevarj.motd.data.prefs.UiCornerStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class ShapeTokensTest {
    @Test fun sharedShapes_followTheExpressiveScale() {
        assertEquals(RoundedCornerShape(8.dp), MotdShapes.tag)
        assertEquals(RoundedCornerShape(12.dp), MotdShapes.compact)
        assertEquals(RoundedCornerShape(16.dp), MotdShapes.card)
        assertEquals(RoundedCornerShape(20.dp), MotdShapes.bubble)
        assertEquals(RoundedCornerShape(6.dp), MotdShapes.groupedBubble)
        assertEquals(RoundedCornerShape(28.dp), MotdShapes.composer)
        assertEquals(RoundedCornerShape(percent = 50), MotdShapes.pill)
        assertEquals(RoundedCornerShape(percent = 30), MotdShapes.channelAvatar)
    }

    @Test fun defaultCorners_preserveEachSurfaceBaseline() {
        assertEquals(8.dp, UiCornerStyle.DEFAULT.cornerRadius(8.dp))
        assertEquals(10.dp, UiCornerStyle.DEFAULT.cornerRadius(10.dp))
        assertEquals(12.dp, UiCornerStyle.DEFAULT.cornerRadius(12.dp))
        assertEquals(16.dp, UiCornerStyle.DEFAULT.cornerRadius(16.dp))
        assertEquals(28.dp, UiCornerStyle.DEFAULT.cornerRadius(28.dp, roundedRadius = 28.dp))
        assertEquals(16.dp, UiCornerStyle.DEFAULT.cornerRadius(16.dp, roundedRadius = 28.dp))
    }

    @Test fun cornerPresets_resolveToLiteralRadii() {
        for (baseline in listOf(8.dp, 10.dp, 12.dp, 16.dp, 28.dp)) {
            assertEquals(0.dp, UiCornerStyle.SQUARE.cornerRadius(baseline))
            assertEquals(8.dp, UiCornerStyle.SOFT.cornerRadius(baseline))
            assertEquals(24.dp, UiCornerStyle.ROUNDED.cornerRadius(baseline))
            assertEquals(0.dp, UiCornerStyle.SQUARE.cornerRadius(baseline, roundedRadius = 28.dp))
            assertEquals(8.dp, UiCornerStyle.SOFT.cornerRadius(baseline, roundedRadius = 28.dp))
            assertEquals(28.dp, UiCornerStyle.ROUNDED.cornerRadius(baseline, roundedRadius = 28.dp))
        }
    }

    @Test fun defaultAvatarShape_preservesPeopleAndChannelGeometry() {
        assertEquals(50, AvatarShape.DEFAULT.cornerPercent(isChannel = false))
        assertEquals(30, AvatarShape.DEFAULT.cornerPercent(isChannel = true))
        assertEquals(RoundedCornerShape(percent = 50), AvatarShape.DEFAULT.shape(isChannel = false))
        assertEquals(RoundedCornerShape(percent = 30), AvatarShape.DEFAULT.shape(isChannel = true))
    }

    @Test fun avatarPresets_resolveToLiteralProportionalShapes() {
        for (isChannel in listOf(false, true)) {
            assertEquals(0, AvatarShape.SQUARE.cornerPercent(isChannel))
            assertEquals(10, AvatarShape.SOFT.cornerPercent(isChannel))
            assertEquals(30, AvatarShape.ROUNDED.cornerPercent(isChannel))
            assertEquals(50, AvatarShape.CIRCLE.cornerPercent(isChannel))
            assertEquals(RoundedCornerShape(percent = 0), AvatarShape.SQUARE.shape(isChannel))
            assertEquals(RoundedCornerShape(percent = 10), AvatarShape.SOFT.shape(isChannel))
            assertEquals(RoundedCornerShape(percent = 30), AvatarShape.ROUNDED.shape(isChannel))
            assertEquals(RoundedCornerShape(percent = 50), AvatarShape.CIRCLE.shape(isChannel))
        }
    }

    @Test fun componentSizes_shareAConsistentHierarchy() {
        assertEquals(32.dp, MotdSizes.messageAvatar)
        assertEquals(40.dp, MotdSizes.headerAvatar)
        assertEquals(48.dp, MotdSizes.chatListAvatar)
        assertEquals(24.dp, MotdSizes.icon)
        assertEquals(48.dp, MotdSizes.touchTarget)
        assertEquals(56.dp, MotdSizes.floatingActionButton)
    }
}
