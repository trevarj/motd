package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.BubbleCornerStyle
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.MessageSpacing
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.ui.components.ChatLayoutPreview
import io.github.trevarj.motd.ui.components.densityDescription
import io.github.trevarj.motd.ui.components.densityLabel
import io.github.trevarj.motd.ui.settings.ChoiceOption
import io.github.trevarj.motd.ui.settings.SingleChoiceSheet

@Composable
fun ConversationLayoutSheet(
    state: ConversationLayoutState,
    onSelect: (LayoutDensity?) -> Unit,
    onDismiss: () -> Unit,
    wallpaper: WallpaperSelection,
    conversationFontScalePercent: Int,
    messageSpacing: MessageSpacing,
    bubbleCorners: BubbleCornerStyle,
) {
    val inherited = state.bufferDefault ?: state.global
    SingleChoiceSheet(
        title = stringResource(R.string.chat_layout_title),
        selected = state.override,
        options =
            listOf(
                ChoiceOption<LayoutDensity?>(
                    null,
                    stringResource(if (state.bufferDefault != null) R.string.chat_layout_use_server_default else R.string.chat_layout_use_global),
                    densityDescription(inherited),
                    "chat_layout_global",
                ),
            ) +
                LayoutDensity.entries.map { density ->
                    ChoiceOption<LayoutDensity?>(density, densityLabel(density), densityDescription(density), "chat_layout_${density.name.lowercase()}")
                },
        onSelect = onSelect,
        onDismiss = onDismiss,
        tag = "chat_layout_sheet",
        dismissOnSelect = false,
        preview = {
            Column {
                Text(
                    stringResource(
                        when {
                            state.override != null -> R.string.chat_layout_source_override
                            state.bufferDefault != null -> R.string.chat_layout_source_server
                            else -> R.string.chat_layout_source_global
                        },
                        densityLabel(state.effective),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ChatLayoutPreview(
                    density = state.effective,
                    messageSpacing = messageSpacing,
                    bubbleCorners = bubbleCorners,
                    wallpaper = wallpaper,
                    conversationFontScalePercent = conversationFontScalePercent,
                    tagPrefix = "chat_layout_preview",
                )
            }
        },
    )
}
