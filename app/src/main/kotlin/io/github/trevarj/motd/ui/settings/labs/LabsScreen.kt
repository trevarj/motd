package io.github.trevarj.motd.ui.settings.labs

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.nav.SettingsTarget
import io.github.trevarj.motd.ui.settings.SettingsGroup
import io.github.trevarj.motd.ui.settings.SettingsNavigationRow
import io.github.trevarj.motd.ui.settings.SettingsScaffold
import io.github.trevarj.motd.ui.settings.SwitchRow
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.settings.SettingsTarget as SettingsTargetAnchor

/**
 * Labs category: experimental features, each behind its own switch. Lab flags live in their own
 * stores and are excluded from configuration backup, so enabling a lab is always a local decision.
 */
@Composable
fun LabsScreen(
    onBack: () -> Unit = {},
    onOpenGestureMenu: () -> Unit = {},
    onOpenAi: () -> Unit = {},
    target: SettingsTarget? = null,
    viewModel: LabsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LabsContent(
        state = state,
        onBack = onBack,
        onGesturesChanged = viewModel::setGesturesEnabled,
        onAgentwireChanged = viewModel::setAgentwireEnabled,
        onGlobalFeedChanged = viewModel::setGlobalFeedEnabled,
        onOpenGestureMenu = onOpenGestureMenu,
        onOpenAi = onOpenAi,
        target = target,
    )
}

@Composable
fun LabsContent(
    state: LabsUiState,
    onBack: () -> Unit,
    onGesturesChanged: (Boolean) -> Unit,
    onAgentwireChanged: (Boolean) -> Unit,
    onGlobalFeedChanged: (Boolean) -> Unit = {},
    onOpenGestureMenu: () -> Unit = {},
    onOpenAi: () -> Unit = {},
    target: SettingsTarget? = null,
) {
    val context = LocalContext.current
    val agentwireUrl = stringResource(R.string.labs_agentwire_url)
    SettingsScaffold(
        title = stringResource(R.string.settings_labs),
        onBack = onBack,
        modifier = Modifier.testTag("screen_labs"),
    ) {
        Text(
            text = stringResource(R.string.labs_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        SettingsTargetAnchor(target?.name, SettingsTarget.AI.name) { targetModifier ->
            SettingsGroup(modifier = targetModifier) {
                SettingsNavigationRow(
                    title = stringResource(R.string.labs_ai),
                    summary = stringResource(R.string.labs_ai_desc),
                    modifier = Modifier.testTag("labs_ai"),
                    onClick = onOpenAi,
                )
            }
        }
        SettingsTargetAnchor(
            if (target == SettingsTarget.LABS) SettingsTarget.GESTURES.name else target?.name,
            SettingsTarget.GESTURES.name,
        ) { targetModifier ->
            SettingsGroup(title = stringResource(R.string.labs_gestures_section), modifier = targetModifier) {
                SwitchRow(
                    title = stringResource(R.string.labs_gestures),
                    subtitle = stringResource(R.string.labs_gestures_desc),
                    checked = state.gesturesEnabled,
                    onCheckedChange = onGesturesChanged,
                    switchTag = "labs_gestures_switch",
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                // The menu graph is authored work that survives the lab being off, so the editor stays
                // reachable whatever the switch says.
                ListItem(
                    headlineContent = { Text(stringResource(R.string.labs_gestures_configure)) },
                    supportingContent = { Text(stringResource(R.string.labs_gestures_configure_desc)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier =
                        Modifier
                            .clickable(onClick = onOpenGestureMenu)
                            .testTag("labs_gestures_configure"),
                )
            }
        }
        SettingsTargetAnchor(target?.name, SettingsTarget.AGENTWIRE.name) { targetModifier ->
            SettingsGroup(title = stringResource(R.string.labs_agentwire_section), modifier = targetModifier) {
                SwitchRow(
                    title = stringResource(R.string.labs_agentwire),
                    subtitle = stringResource(R.string.labs_agentwire_desc),
                    checked = state.agentwireEnabled,
                    onCheckedChange = onAgentwireChanged,
                    switchTag = "labs_agentwire_switch",
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                ListItem(
                    headlineContent = { Text(stringResource(R.string.labs_agentwire_repo)) },
                    supportingContent = { Text(agentwireUrl) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier =
                        Modifier
                            .clickable {
                                context.startActivity(Intent(Intent.ACTION_VIEW, agentwireUrl.toUri()))
                            }.testTag("labs_agentwire_repo"),
                )
            }
        }
        SettingsTargetAnchor(target?.name, SettingsTarget.GLOBAL_FEED.name) { targetModifier ->
            SettingsGroup(title = stringResource(R.string.labs_feed_section), modifier = targetModifier) {
                SwitchRow(
                    title = stringResource(R.string.labs_global_feed),
                    subtitle = stringResource(R.string.labs_global_feed_desc),
                    checked = state.globalFeedEnabled,
                    onCheckedChange = onGlobalFeedChanged,
                    switchTag = "labs_global_feed_switch",
                )
            }
        }
    }
}

@Preview
@Composable
private fun LabsScreenPreview() {
    MotdTheme {
        LabsContent(
            state = LabsUiState(gesturesEnabled = true),
            onBack = {},
            onGesturesChanged = {},
            onAgentwireChanged = {},
            onGlobalFeedChanged = {},
        )
    }
}
