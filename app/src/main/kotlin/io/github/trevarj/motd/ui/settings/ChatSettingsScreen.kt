package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ReplyConfig
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.avatar.AvatarConfig
import io.github.trevarj.motd.ui.theme.MotdTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.PersonOutline

/** Chat category: join/part/quit visibility, friends/fools management, and fools' message handling. */
@Composable
fun ChatSettingsScreen(
    onBack: () -> Unit = {},
    onOpenFriends: () -> Unit = {},
    onOpenFools: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChatSettingsContent(
        settings = state.settings,
        reply = state.reply,
        contentPreviews = state.contentPreviews,
        avatars = state.avatars,
        onBack = onBack,
        onOpenFriends = onOpenFriends,
        onOpenFools = onOpenFools,
        onShowJoinPartQuit = viewModel::setShowJoinPartQuit,
        onFoolsMode = viewModel::setFoolsMode,
        onShowComposerEmoji = viewModel::setShowComposerEmoji,
        onChatSoundsEnabled = viewModel::setChatSoundsEnabled,
        onVisibleReplyPrefix = viewModel::setVisibleReplyPrefix,
        onShowImages = viewModel::setShowImages,
        onShowLinkPreviews = viewModel::setShowLinkPreviews,
        onShowSharedAvatars = viewModel::setShowSharedAvatars,
    )
}

@Composable
fun ChatSettingsContent(
    settings: Settings,
    reply: ReplyConfig,
    contentPreviews: ContentPreviewConfig,
    avatars: AvatarConfig,
    onBack: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenFools: () -> Unit,
    onShowJoinPartQuit: (Boolean) -> Unit,
    onFoolsMode: (FoolsMode) -> Unit,
    onShowComposerEmoji: (Boolean) -> Unit,
    onChatSoundsEnabled: (Boolean) -> Unit,
    onVisibleReplyPrefix: (Boolean) -> Unit,
    onShowImages: (Boolean) -> Unit,
    onShowLinkPreviews: (Boolean) -> Unit,
    onShowSharedAvatars: (Boolean) -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.settings_chat), onBack = onBack) {
        SettingsGroup(title = stringResource(R.string.settings_messages_section)) {
            SwitchRow(
                title = stringResource(R.string.settings_show_jpq),
                subtitle = stringResource(R.string.settings_show_jpq_desc),
                checked = settings.showJoinPartQuit,
                onCheckedChange = onShowJoinPartQuit,
                switchTag = "settings_switch_show_jpq",
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_show_images),
                subtitle = stringResource(R.string.settings_show_images_desc),
                checked = contentPreviews.showImages,
                onCheckedChange = onShowImages,
                switchTag = "settings_switch_show_images",
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_show_link_previews),
                subtitle = stringResource(R.string.settings_show_link_previews_desc),
                checked = contentPreviews.showLinkPreviews,
                onCheckedChange = onShowLinkPreviews,
                switchTag = "settings_switch_show_link_previews",
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_show_shared_avatars),
                subtitle = stringResource(R.string.settings_show_shared_avatars_desc),
                checked = avatars.showSharedAvatars,
                onCheckedChange = onShowSharedAvatars,
                switchTag = "settings_switch_show_shared_avatars",
            )
        }
        SettingsGroup(title = stringResource(R.string.settings_composer_section)) {
            SwitchRow(
                title = stringResource(R.string.settings_chat_sounds),
                subtitle = stringResource(R.string.settings_chat_sounds_desc),
                checked = settings.chatSoundsEnabled,
                onCheckedChange = onChatSoundsEnabled,
                switchTag = "settings_switch_chat_sounds",
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_composer_emoji),
                subtitle = stringResource(R.string.settings_composer_emoji_desc),
                checked = settings.showComposerEmoji,
                onCheckedChange = onShowComposerEmoji,
                switchTag = "settings_switch_composer_emoji",
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_reply_prefix),
                subtitle = stringResource(R.string.settings_reply_prefix_desc),
                checked = reply.visibleChannelPrefix,
                onCheckedChange = onVisibleReplyPrefix,
                switchTag = "settings_switch_reply_prefix",
            )
        }
        SettingsGroup(title = stringResource(R.string.settings_people)) {
            SettingsNavigationRow(
                icon = Icons.Outlined.PersonOutline,
                title = stringResource(R.string.settings_friends),
                value = pluralStringResource(R.plurals.settings_nick_count, settings.friends.size, settings.friends.size),
                modifier = Modifier.testTag("settings_friends"),
                onClick = onOpenFriends,
            )
            SettingsNavigationRow(
                icon = Icons.Outlined.Block,
                title = stringResource(R.string.settings_fools),
                value = pluralStringResource(R.plurals.settings_nick_count, settings.fools.size, settings.fools.size),
                modifier = Modifier.testTag("settings_fools"),
                onClick = onOpenFools,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SubLabel(stringResource(R.string.settings_fools_mode))
            FoolsModeGroup(current = settings.foolsMode, onSelect = onFoolsMode)
        }
    }
}

@Composable
private fun FoolsModeGroup(current: FoolsMode, onSelect: (FoolsMode) -> Unit) {
    Column(Modifier.selectableGroup()) {
        RadioRow(
            label = stringResource(R.string.settings_fools_collapse),
            subtitle = stringResource(R.string.settings_fools_collapse_desc),
            selected = current == FoolsMode.COLLAPSE,
            enabled = true,
            onClick = { onSelect(FoolsMode.COLLAPSE) },
            modifier = Modifier.testTag("settings_fools_mode_collapse"),
        )
        RadioRow(
            label = stringResource(R.string.settings_fools_hide),
            subtitle = stringResource(R.string.settings_fools_hide_desc),
            selected = current == FoolsMode.HIDE,
            enabled = true,
            onClick = { onSelect(FoolsMode.HIDE) },
            modifier = Modifier.testTag("settings_fools_mode_hide"),
        )
    }
}

@Preview
@Composable
private fun ChatSettingsPreview() {
    MotdTheme {
        ChatSettingsContent(
            settings = Settings(friends = setOf("alice"), fools = setOf("bob", "carol")),
            reply = ReplyConfig(),
            contentPreviews = ContentPreviewConfig(),
            avatars = AvatarConfig(),
            onBack = {}, onOpenFriends = {}, onOpenFools = {},
            onShowJoinPartQuit = {}, onFoolsMode = {}, onShowComposerEmoji = {},
            onChatSoundsEnabled = {},
            onVisibleReplyPrefix = {},
            onShowImages = {}, onShowLinkPreviews = {}, onShowSharedAvatars = {},
        )
    }
}
