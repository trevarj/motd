package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.BubbleCornerStyle
import io.github.trevarj.motd.data.prefs.CompactNickBrackets
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.MessageSpacing
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.ui.components.ChatLayoutPreview
import io.github.trevarj.motd.ui.components.densityDescription
import io.github.trevarj.motd.ui.components.densityLabel
import io.github.trevarj.motd.ui.settings.ChoiceOption
import io.github.trevarj.motd.ui.settings.MessageBackgroundBlurSlider
import io.github.trevarj.motd.ui.settings.MessageBackgroundOpacitySlider
import io.github.trevarj.motd.ui.settings.RadioRow
import io.github.trevarj.motd.ui.settings.SingleChoiceSheet
import io.github.trevarj.motd.ui.settings.SwitchRow
import io.github.trevarj.motd.ui.theme.LocalMessageBackgroundBlur
import io.github.trevarj.motd.ui.theme.LocalMessageBackgroundOpacityPercent

@Composable
fun ConversationLayoutSheet(
    state: ConversationLayoutState,
    onSelect: (LayoutDensity?) -> Unit,
    onOpacity: (Int?) -> Unit,
    globalOpacity: Int,
    onBlur: (Int?) -> Unit,
    globalBlur: Int,
    globalCompactNickBrackets: CompactNickBrackets,
    onCompactNickBracketsOverride: (CompactNickBrackets?) -> Unit,
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
                    fontStyle = if (state.override == null) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                )
                CompositionLocalProvider(
                    LocalMessageBackgroundOpacityPercent provides state.effectiveMessageBackgroundOpacity(globalOpacity),
                    LocalMessageBackgroundBlur provides state.effectiveMessageBackgroundBlur(globalBlur),
                ) {
                    ChatLayoutPreview(
                        density = state.effective,
                        compactNickBrackets = state.effectiveCompactNickBrackets(globalCompactNickBrackets),
                        messageSpacing = messageSpacing,
                        bubbleCorners = bubbleCorners,
                        wallpaper = wallpaper,
                        conversationFontScalePercent = conversationFontScalePercent,
                        tagPrefix = "chat_layout_preview",
                    )
                }
            }
        },
        footer = {
            if (state.effective == LayoutDensity.COMPACT) {
                Text(stringResource(R.string.settings_compact_nick_brackets), style = MaterialTheme.typography.titleSmall)
                RadioRow(
                    label = stringResource(R.string.chat_layout_global_brackets),
                    selected = state.compactNickBracketsOverride == null,
                    enabled = true,
                    onClick = { onCompactNickBracketsOverride(null) },
                    modifier = Modifier.testTag("chat_compact_nick_brackets_global"),
                )
                CompactNickBrackets.entries.forEach { brackets ->
                    RadioRow(
                        label =
                            if (brackets == CompactNickBrackets.NONE) {
                                stringResource(R.string.settings_compact_nick_brackets_none)
                            } else {
                                "${brackets.opening}nick${brackets.closing}"
                            },
                        selected = state.compactNickBracketsOverride == brackets,
                        enabled = true,
                        onClick = { onCompactNickBracketsOverride(brackets) },
                        modifier = Modifier.testTag("chat_compact_nick_brackets_${brackets.name.lowercase()}"),
                    )
                }
            }
            SwitchRow(
                title = stringResource(R.string.chat_layout_global_opacity),
                subtitle = stringResource(R.string.chat_layout_global_opacity_desc),
                checked = state.messageBackgroundOpacityOverride == null,
                onCheckedChange = { inherit -> onOpacity(if (inherit) null else state.effectiveMessageBackgroundOpacity(globalOpacity)) },
                switchTag = "chat_layout_global_opacity",
            )
            MessageBackgroundOpacitySlider(
                value = state.effectiveMessageBackgroundOpacity(globalOpacity),
                onValue = onOpacity,
                tag = "chat_message_background_opacity",
            )
            SwitchRow(
                title = stringResource(R.string.chat_layout_global_blur),
                subtitle = stringResource(R.string.chat_layout_global_blur_desc),
                checked = state.messageBackgroundBlurOverride == null,
                onCheckedChange = { inherit -> onBlur(if (inherit) null else state.effectiveMessageBackgroundBlur(globalBlur)) },
                switchTag = "chat_layout_global_blur",
            )
            MessageBackgroundBlurSlider(
                value = state.effectiveMessageBackgroundBlur(globalBlur),
                onValue = onBlur,
                tag = "chat_message_background_blur",
            )
        },
    )
}
