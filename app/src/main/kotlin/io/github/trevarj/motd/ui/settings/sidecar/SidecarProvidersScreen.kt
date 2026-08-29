package io.github.trevarj.motd.ui.settings.sidecar

import android.app.Activity
import android.content.ComponentName
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.settings.SettingsGroup
import io.github.trevarj.motd.ui.settings.SettingsScaffold

@Composable
fun SidecarProvidersScreen(
    onBack: () -> Unit,
    onAdded: (Long) -> Unit,
    viewModel: SidecarProvidersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingPair by remember { mutableStateOf<ComponentName?>(null) }
    val pairing =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingPair?.let { viewModel.pairingFinished(it, result.resultCode == Activity.RESULT_OK) }
            pendingPair = null
        }
    LaunchedEffect(viewModel) {
        viewModel.launches.collect { launch ->
            pendingPair = launch.component
            pairing.launch(launch.intent)
        }
    }

    SidecarProvidersContent(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onPair = viewModel::pair,
        onUnpair = viewModel::unpair,
        onAdd = { component, account -> viewModel.addAccount(component, account, onAdded) },
    )
}

@Composable
private fun SidecarProvidersContent(
    state: SidecarProvidersUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPair: (ComponentName) -> Unit,
    onUnpair: (ComponentName) -> Unit,
    onAdd: (ComponentName, io.github.trevarj.motd.sidecar.SidecarAccount) -> Unit,
) {
    SettingsScaffold(
        title = stringResource(R.string.sidecar_providers_title),
        onBack = onBack,
        modifier = Modifier.testTag("screen_sidecar_providers"),
    ) {
        if (state.loading) CircularProgressIndicator()
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!state.loading && state.providers.isEmpty()) {
            Text(stringResource(R.string.sidecar_no_providers))
        }
        state.providers.forEach { row ->
            SettingsGroup(title = row.descriptor.label) {
                ListItem(
                    headlineContent = { Text(row.descriptor.component.packageName) },
                    supportingContent = {
                        Text(
                            if (row.paired) {
                                stringResource(R.string.sidecar_paired)
                            } else {
                                stringResource(R.string.sidecar_not_paired)
                            },
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier =
                        Modifier
                            .clickable {
                                if (row.paired) onUnpair(row.descriptor.component) else onPair(row.descriptor.component)
                            }.testTag("sidecar_provider_${row.descriptor.component.packageName}"),
                )
                row.accounts.forEachIndexed { index, account ->
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ListItem(
                        headlineContent = { Text(account.displayName) },
                        supportingContent = { account.detail?.let { Text(it) } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier =
                            Modifier
                                .clickable(enabled = account.enabled) { onAdd(row.descriptor.component, account) }
                                .testTag("sidecar_account_$index"),
                    )
                }
            }
        }
        IconButton(onClick = onRefresh, modifier = Modifier.testTag("sidecar_refresh")) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.sidecar_refresh))
        }
    }
}
