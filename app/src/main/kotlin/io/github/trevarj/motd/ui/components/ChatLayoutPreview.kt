package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.BubbleCornerStyle
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.MessageSpacing
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.ui.chat.ChatWallpaperBackground
import io.github.trevarj.motd.ui.theme.ConversationTypography
import io.github.trevarj.motd.ui.theme.LocalChatShadowsEnabled
import io.github.trevarj.motd.ui.theme.LocalSpacing
import io.github.trevarj.motd.ui.theme.LocalTimestampConfig
import io.github.trevarj.motd.ui.theme.MotdShapes
import io.github.trevarj.motd.ui.theme.spacingFor

/** A local, display-only conversation using the same message renderers as the timeline. */
@Composable
internal fun ChatLayoutPreview(
    density: LayoutDensity,
    messageSpacing: MessageSpacing,
    bubbleCorners: BubbleCornerStyle,
    wallpaper: WallpaperSelection,
    conversationFontScalePercent: Int,
    tagPrefix: String,
    modifier: Modifier = Modifier,
    chatShadowsEnabled: Boolean = LocalChatShadowsEnabled.current,
) {
    val spacing = remember(density, messageSpacing, bubbleCorners) { spacingFor(density, messageSpacing, bubbleCorners) }
    val avatars = remember { RemoteAvatarState() }
    CompositionLocalProvider(
        LocalSpacing provides spacing,
        LocalChatShadowsEnabled provides chatShadowsEnabled,
        LocalRemoteAvatars provides avatars,
        LocalAutomaticRemoteMedia provides false,
        LocalMessageActionsEnabled provides false,
    ) {
        val timestamps = LocalTimestampConfig.current
        Column(modifier.testTag("${tagPrefix}_configuration")) {
            Text(
                stringResource(R.string.settings_chat_preview, densityLabel(density)),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}_${density.name.lowercase()}_${messageSpacing.name.lowercase()}_${bubbleCorners.name.lowercase()}"),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MotdShapes.card,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                val wallpaperKind = if (wallpaper.localImageName == null) wallpaper.preset.name.lowercase() else "custom"
                Box(Modifier.testTag("${tagPrefix}_wallpaper_${wallpaperKind}_${wallpaper.intensity}")) {
                    ChatWallpaperBackground(wallpaper, Modifier.matchParentSize())
                    ConversationTypography(conversationFontScalePercent) {
                        Column(
                            Modifier
                                .padding(vertical = 8.dp)
                                .testTag("${tagPrefix}_timestamps_${timestamps.show}_${timestamps.format.name.lowercase()}"),
                        ) {
                            Column(Modifier.testTag("${tagPrefix}_shadows_$chatShadowsEnabled")) {
                                MessageBubble(
                                    sender = stringResource(R.string.settings_chat_preview_sender),
                                    text = stringResource(R.string.settings_chat_preview_message),
                                    timeMs = PREVIEW_MESSAGE_TIME_MILLIS,
                                    isSelf = false,
                                    kind = MessageKind.PRIVMSG,
                                    showSender = true,
                                )
                                Spacer(Modifier.height(spacing.bubbleBurstGap))
                                MessageBubble(
                                    sender = stringResource(R.string.settings_chat_preview_sender),
                                    text = stringResource(R.string.settings_chat_preview_followup),
                                    timeMs = PREVIEW_MESSAGE_TIME_MILLIS + 60_000,
                                    isSelf = false,
                                    kind = MessageKind.PRIVMSG,
                                    showSender = false,
                                )
                                Spacer(Modifier.height(spacing.bubbleBreakGap))
                                MessageBubble(
                                    sender = stringResource(R.string.settings_chat_preview_self),
                                    text = stringResource(R.string.settings_chat_preview_reply),
                                    timeMs = PREVIEW_MESSAGE_TIME_MILLIS + 120_000,
                                    isSelf = true,
                                    kind = MessageKind.PRIVMSG,
                                    showSender = true,
                                )
                                Spacer(Modifier.height(spacing.bubbleBreakGap))
                                MessageBubble(
                                    sender = stringResource(R.string.settings_chat_preview_sender),
                                    text = stringResource(R.string.settings_chat_preview_action),
                                    timeMs = PREVIEW_MESSAGE_TIME_MILLIS + 180_000,
                                    isSelf = false,
                                    kind = MessageKind.ACTION,
                                    showSender = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun densityLabel(value: LayoutDensity): String =
    stringResource(
        when (value) {
            LayoutDensity.COMPACT -> R.string.settings_density_compact
            LayoutDensity.COMFORTABLE -> R.string.settings_density_comfortable
            LayoutDensity.TWO_LINE -> R.string.settings_density_two_line
        },
    )

@Composable
internal fun densityDescription(value: LayoutDensity): String =
    stringResource(
        when (value) {
            LayoutDensity.COMPACT -> R.string.settings_density_compact_desc
            LayoutDensity.COMFORTABLE -> R.string.settings_density_comfortable_desc
            LayoutDensity.TWO_LINE -> R.string.settings_density_two_line_desc
        },
    )

private const val PREVIEW_MESSAGE_TIME_MILLIS = 1_704_110_040_000L
