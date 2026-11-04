package io.github.trevarj.motd.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.data.prefs.AvatarShape
import io.github.trevarj.motd.data.prefs.UiCornerStyle

internal fun UiCornerStyle.cornerRadius(
    defaultRadius: Dp,
    roundedRadius: Dp = 24.dp,
): Dp =
    when (this) {
        UiCornerStyle.DEFAULT -> defaultRadius
        UiCornerStyle.SQUARE -> 0.dp
        UiCornerStyle.SOFT -> 8.dp
        UiCornerStyle.ROUNDED -> roundedRadius
    }

internal fun AvatarShape.cornerPercent(isChannel: Boolean): Int =
    when (this) {
        AvatarShape.DEFAULT -> if (isChannel) 30 else 50
        AvatarShape.SQUARE -> 0
        AvatarShape.SOFT -> 10
        AvatarShape.ROUNDED -> 30
        AvatarShape.CIRCLE -> 50
    }

internal fun AvatarShape.shape(isChannel: Boolean): RoundedCornerShape = RoundedCornerShape(percent = cornerPercent(isChannel))

/** Shared geometry keeps tags compact, content soft, and capsules intentional. */
object MotdShapes {
    val tag = RoundedCornerShape(8.dp)
    val compact = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(16.dp)
    val bubble = RoundedCornerShape(20.dp)
    val groupedBubble = RoundedCornerShape(6.dp)
    val composer = RoundedCornerShape(28.dp)
    val pill = RoundedCornerShape(percent = 50)

    // Channels and drawer networks share rounded squares; queries/people stay circles. Percent
    // radius keeps the corner proportional from the 20dp action line to the 88dp channel header.
    val channelAvatar = RoundedCornerShape(percent = 30)
}

/** Stable component sizes; visible artwork remains separate from the 48dp touch-target floor. */
object MotdSizes {
    val messageAvatar = 32.dp
    val headerAvatar = 40.dp
    val chatListAvatar = 48.dp
    val icon = 24.dp
    val touchTarget = 48.dp
    val floatingActionButton = 56.dp
}

val MotdMaterialShapes =
    Shapes(
        extraSmall = MotdShapes.tag,
        small = MotdShapes.compact,
        medium = MotdShapes.card,
        large = MotdShapes.bubble,
        extraLarge = MotdShapes.composer,
    )
