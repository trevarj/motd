package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.ui.theme.MotdTheme

/** Chat category: join/part/quit visibility, friends/fools management, and fools' message handling. */
@Composable
fun ChatSettingsScreen(
    onBack: () -> Unit = {},
    onOpenFriends: () -> Unit = {},
    onOpenFools: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    ChatSettingsContent(
        settings = state.settings,
        onBack = onBack,
        onOpenFriends = onOpenFriends,
        onOpenFools = onOpenFools,
        onShowJoinPartQuit = viewModel::setShowJoinPartQuit,
        onFoolsMode = viewModel::setFoolsMode,
        onShowComposerEmoji = viewModel::setShowComposerEmoji,
    )
}

@Composable
fun ChatSettingsContent(
    settings: Settings,
    onBack: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenFools: () -> Unit,
    onShowJoinPartQuit: (Boolean) -> Unit,
    onFoolsMode: (FoolsMode) -> Unit,
    onShowComposerEmoji: (Boolean) -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.settings_chat), onBack = onBack) {
        SwitchRow(
            title = stringResource(R.string.settings_show_jpq),
            subtitle = stringResource(R.string.settings_show_jpq_desc),
            checked = settings.showJoinPartQuit,
            onCheckedChange = onShowJoinPartQuit,
            switchTag = "settings_switch_show_jpq",
        )
        SwitchRow(
            title = stringResource(R.string.settings_composer_emoji),
            subtitle = stringResource(R.string.settings_composer_emoji_desc),
            checked = settings.showComposerEmoji,
            onCheckedChange = onShowComposerEmoji,
            switchTag = "settings_switch_composer_emoji",
        )

        HorizontalDivider()

        SectionHeader(stringResource(R.string.settings_people))
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_friends)) },
            supportingContent = countText(settings.friends.size),
            modifier = Modifier.clickable { onOpenFriends() },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_fools)) },
            supportingContent = countText(settings.fools.size),
            modifier = Modifier.clickable { onOpenFools() },
        )
        SubLabel(stringResource(R.string.settings_fools_mode))
        FoolsModeGroup(current = settings.foolsMode, onSelect = onFoolsMode)
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
        )
        RadioRow(
            label = stringResource(R.string.settings_fools_hide),
            subtitle = stringResource(R.string.settings_fools_hide_desc),
            selected = current == FoolsMode.HIDE,
            enabled = true,
            onClick = { onSelect(FoolsMode.HIDE) },
        )
    }
}

@Preview
@Composable
private fun ChatSettingsPreview() {
    MotdTheme {
        ChatSettingsContent(
            settings = Settings(friends = setOf("alice"), fools = setOf("bob", "carol")),
            onBack = {}, onOpenFriends = {}, onOpenFools = {},
            onShowJoinPartQuit = {}, onFoolsMode = {}, onShowComposerEmoji = {},
        )
    }
}
