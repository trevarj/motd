package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.ui.about.appVersion

@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenChat: () -> Unit = {},
    onOpenDelivery: () -> Unit = {},
    onOpenNetworks: () -> Unit = {},
    onOpenUploads: () -> Unit = {},
    onOpenBackupRestore: () -> Unit = {},
    onOpenLabs: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenSearchResult: (SettingsSearchDestination) -> Unit = {},
    viewModel: SettingsHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        onQueryChange = viewModel::setQuery,
        onBack = onBack,
        onOpenAppearance = onOpenAppearance,
        onOpenChat = onOpenChat,
        onOpenDelivery = onOpenDelivery,
        onOpenNetworks = onOpenNetworks,
        onOpenUploads = onOpenUploads,
        onOpenBackupRestore = onOpenBackupRestore,
        onOpenLabs = onOpenLabs,
        onOpenAbout = onOpenAbout,
        onOpenSearchResult = onOpenSearchResult,
    )
}

@Composable
fun SettingsContent(
    state: SettingsHomeUiState,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenDelivery: () -> Unit,
    onOpenNetworks: () -> Unit,
    onOpenUploads: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    onOpenLabs: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSearchResult: (SettingsSearchDestination) -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(state.query.isNotEmpty()) }
    val resources = LocalResources.current
    val entries =
        buildSettingsSearchEntries(
            networks = state.networks,
            uploads = state.uploads,
            resolve = resources::getString,
            networkTitle = { network, section -> resources.getString(R.string.settings_search_network_title, network, section) },
        )
    val results = searchSettings(state.query, entries)

    SettingsScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        modifier = Modifier.testTag("screen_settings"),
        topActions = {
            IconButton(
                onClick = {
                    searching = !searching
                    if (!searching) onQueryChange("")
                },
                modifier = Modifier.testTag("settings_search_action"),
            ) {
                Icon(
                    if (searching) Icons.Filled.Close else Icons.Outlined.Search,
                    stringResource(if (searching) R.string.settings_search_close else R.string.settings_search_open),
                )
            }
        },
    ) {
        if (searching) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                label = { Text(stringResource(R.string.settings_search_hint)) },
                modifier = Modifier.fillMaxWidth().testTag("settings_search_field"),
            )
            if (state.query.isBlank()) {
                Text(stringResource(R.string.settings_search_prompt), modifier = Modifier.testTag("settings_search_prompt"))
            } else {
                Text(
                    stringResource(R.string.settings_search_results),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                if (results.isEmpty()) {
                    Text(stringResource(R.string.settings_search_empty), modifier = Modifier.testTag("settings_search_empty"))
                } else {
                    SettingsGroup {
                        results.forEachIndexed { index, result ->
                            SettingsNavigationRow(
                                title = result.title,
                                summary = result.summary,
                                modifier =
                                    Modifier.testTag(
                                        when (val destination = result.destination) {
                                            is SettingsSearchDestination.Page -> "settings_search_result_page_${destination.page.name}_${destination.target.name}"
                                            is SettingsSearchDestination.Network -> "settings_search_result_network_${destination.networkId}_${destination.target.name}"
                                        },
                                    ),
                                onClick = { onOpenSearchResult(result.destination) },
                            )
                            if (index != results.lastIndex) SettingsDivider()
                        }
                    }
                }
            }
        } else {
            SettingsRoot(
                state = state,
                onOpenAppearance = onOpenAppearance,
                onOpenChat = onOpenChat,
                onOpenDelivery = onOpenDelivery,
                onOpenNetworks = onOpenNetworks,
                onOpenUploads = onOpenUploads,
                onOpenBackupRestore = onOpenBackupRestore,
                onOpenLabs = onOpenLabs,
                onOpenAbout = onOpenAbout,
            )
        }
    }
}

@Composable
private fun SettingsRoot(
    state: SettingsHomeUiState,
    onOpenAppearance: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenDelivery: () -> Unit,
    onOpenNetworks: () -> Unit,
    onOpenUploads: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    onOpenLabs: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    SettingsGroup(title = stringResource(R.string.settings_group_connections)) {
        SettingsNavigationRow(
            title = stringResource(R.string.settings_networks),
            summary = stringResource(R.string.settings_networks_summary),
            value = pluralStringResource(R.plurals.settings_network_count, state.networks.size, state.networks.size),
            modifier = Modifier.testTag("settings_category_networks"),
            onClick = onOpenNetworks,
        )
    }
    SettingsGroup(title = stringResource(R.string.settings_group_experience)) {
        SettingsNavigationRow(
            title = stringResource(R.string.settings_appearance),
            summary = stringResource(R.string.settings_appearance_summary),
            value = themePresetLabel(state.appearance.theme),
            modifier = Modifier.testTag("settings_category_appearance"),
            onClick = onOpenAppearance,
        )
        SettingsDivider()
        SettingsNavigationRow(
            title = stringResource(R.string.settings_chat),
            summary = stringResource(R.string.settings_chat_summary),
            modifier = Modifier.testTag("settings_category_chat"),
            onClick = onOpenChat,
        )
    }
    SettingsGroup(title = stringResource(R.string.settings_group_services)) {
        SettingsNavigationRow(
            title = stringResource(R.string.settings_delivery),
            summary = stringResource(R.string.settings_delivery_summary),
            value =
                stringResource(
                    if (state.settings.deliveryMode == DeliveryMode.UNIFIED_PUSH) {
                        R.string.settings_delivery_push
                    } else {
                        R.string.settings_delivery_socket
                    },
                ),
            modifier = Modifier.testTag("settings_category_delivery"),
            onClick = onOpenDelivery,
        )
        SettingsDivider()
        SettingsNavigationRow(
            title = stringResource(R.string.settings_uploads),
            summary = stringResource(R.string.settings_uploads_summary),
            value = attachmentBackendLabel(state.uploads.backend),
            modifier = Modifier.testTag("settings_category_uploads"),
            onClick = onOpenUploads,
        )
    }
    SettingsGroup(title = stringResource(R.string.settings_group_data_support)) {
        SettingsNavigationRow(
            title = stringResource(R.string.settings_backup_restore),
            summary = stringResource(R.string.settings_backup_restore_summary),
            modifier = Modifier.testTag("settings_category_backup_restore"),
            onClick = onOpenBackupRestore,
        )
        SettingsDivider()
        SettingsNavigationRow(
            title = stringResource(R.string.settings_labs),
            summary = stringResource(R.string.settings_labs_summary),
            modifier = Modifier.testTag("settings_category_labs"),
            onClick = onOpenLabs,
        )
        SettingsDivider()
        SettingsNavigationRow(
            title = stringResource(R.string.settings_about),
            summary = stringResource(R.string.settings_about_summary, appVersion(context)),
            modifier = Modifier.testTag("settings_category_about"),
            onClick = onOpenAbout,
        )
    }
}
