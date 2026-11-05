package io.github.trevarj.motd.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.backup.BackupExportMode
import io.github.trevarj.motd.data.backup.BackupImportMode
import io.github.trevarj.motd.data.backup.ConfigurationImportPreview
import io.github.trevarj.motd.ui.nav.SettingsTarget
import java.text.DateFormat
import java.util.Date

@Composable
fun BackupRestoreScreen(
    onBack: () -> Unit,
    onReviewNetworks: () -> Unit = {},
    target: SettingsTarget? = null,
    viewModel: BackupRestoreViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var exportMode by rememberSaveable { mutableStateOf(BackupExportMode.CREDENTIALS_EXCLUDED) }
    var exportPassword by rememberSaveable { mutableStateOf("") }
    var importPassword by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.importPhase) {
        if (state.importPhase == BackupImportPhase.COMPLETE) importPassword = ""
    }
    LaunchedEffect(exportMode, state.exportOutcome) {
        exportPassword = retainedExportPassword(exportMode, state.exportOutcome, exportPassword)
    }
    val createDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            exportDestinationSelected(uri) { viewModel.export(it, exportMode, exportPassword) }
        }
    val openDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                importPassword = ""
                viewModel.loadImport(it)
            }
        }

    BackupRestoreContent(
        state = state,
        exportMode = exportMode,
        exportPassword = exportPassword,
        importPassword = importPassword,
        target = target,
        onBack = onBack,
        onExportMode = { exportMode = it },
        onExportPassword = { exportPassword = it },
        onChooseExport = { createDocument.launch("motd-${System.currentTimeMillis()}.motdconfig") },
        onDismissExport = viewModel::dismissExportOutcome,
        onChooseImport = { openDocument.launch(arrayOf("application/json", "text/*", "*/*")) },
        onImportPassword = { importPassword = it },
        onPreview = { viewModel.previewImport(importPassword) },
        onImportMode = viewModel::setImportMode,
        onApply = { viewModel.requestApply(importPassword) },
        onConfirmReplace = { viewModel.confirmReplace(importPassword) },
        onCancelReplace = viewModel::cancelReplace,
        onDismissImport = viewModel::clearImportOutcome,
        onReviewNetworks = onReviewNetworks,
    )
}

internal fun exportDestinationSelected(
    uri: android.net.Uri?,
    export: (android.net.Uri) -> Unit,
) {
    uri?.let(export)
}

internal fun retainedExportPassword(
    mode: BackupExportMode,
    outcome: BackupExportOutcome?,
    password: String,
): String = if (mode == BackupExportMode.CREDENTIALS_EXCLUDED || outcome == BackupExportOutcome.Success) "" else password

@Composable
internal fun BackupRestoreContent(
    state: BackupRestoreUiState,
    exportMode: BackupExportMode,
    exportPassword: String,
    importPassword: String,
    target: SettingsTarget? = null,
    onBack: () -> Unit,
    onExportMode: (BackupExportMode) -> Unit,
    onExportPassword: (String) -> Unit,
    onChooseExport: () -> Unit,
    onDismissExport: () -> Unit,
    onChooseImport: () -> Unit,
    onImportPassword: (String) -> Unit,
    onPreview: () -> Unit,
    onImportMode: (BackupImportMode) -> Unit,
    onApply: () -> Unit,
    onConfirmReplace: () -> Unit,
    onCancelReplace: () -> Unit,
    onDismissImport: () -> Unit,
    onReviewNetworks: () -> Unit,
) {
    SettingsScaffold(
        title = stringResource(R.string.settings_backup_restore),
        onBack = onBack,
        modifier = Modifier.testTag("screen_backup_restore"),
    ) {
        SettingsTarget(
            if (target == SettingsTarget.BACKUP) SettingsTarget.EXPORT_BACKUP.name else target?.name,
            SettingsTarget.EXPORT_BACKUP.name,
        ) { targetModifier ->
            SettingsGroup(title = stringResource(R.string.backup_export_title)) {
                Column(targetModifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.backup_export_guidance))
                    Column(Modifier.selectableGroup()) {
                        RadioRow(
                            label = stringResource(R.string.backup_export_without_credentials),
                            subtitle = stringResource(R.string.backup_export_without_credentials_desc),
                            selected = exportMode == BackupExportMode.CREDENTIALS_EXCLUDED,
                            enabled = state.exportPhase != BackupExportPhase.EXPORTING,
                            onClick = { onExportMode(BackupExportMode.CREDENTIALS_EXCLUDED) },
                            modifier = Modifier.testTag("backup_export_without_credentials"),
                        )
                        RadioRow(
                            label = stringResource(R.string.backup_export_with_credentials),
                            subtitle = stringResource(R.string.backup_export_with_credentials_desc),
                            selected = exportMode == BackupExportMode.ENCRYPTED_WITH_CREDENTIALS,
                            enabled = state.exportPhase != BackupExportPhase.EXPORTING,
                            onClick = { onExportMode(BackupExportMode.ENCRYPTED_WITH_CREDENTIALS) },
                            modifier = Modifier.testTag("backup_export_with_credentials"),
                        )
                    }
                    if (exportMode == BackupExportMode.ENCRYPTED_WITH_CREDENTIALS) {
                        PasswordField(
                            value = exportPassword,
                            onValueChange = onExportPassword,
                            label = stringResource(R.string.backup_export_password),
                            supportingText = stringResource(R.string.backup_password_requirements),
                            isError = exportPassword.isNotEmpty() && exportPassword.length !in 12..128,
                            modifier = Modifier.testTag("backup_export_password"),
                        )
                    }
                    Button(
                        onClick = onChooseExport,
                        enabled =
                            !state.busy &&
                                (exportMode == BackupExportMode.CREDENTIALS_EXCLUDED || exportPassword.length in 12..128),
                        modifier = Modifier.testTag("backup_export"),
                    ) { Text(stringResource(R.string.backup_choose_export_destination)) }
                    if (state.exportPhase == BackupExportPhase.EXPORTING) {
                        ProgressRow(stringResource(R.string.backup_export_progress), "backup_export_progress")
                    }
                    state.exportOutcome?.let { outcome ->
                        PersistentStatusNotice(
                            text =
                                when (outcome) {
                                    BackupExportOutcome.Success -> stringResource(R.string.backup_export_success)
                                    is BackupExportOutcome.Failure -> backupFailureText(outcome.category)
                                },
                            error = outcome is BackupExportOutcome.Failure,
                            modifier = Modifier.testTag("backup_export_result"),
                            onDismiss = onDismissExport,
                        )
                    }
                }
            }
        }

        SettingsTarget(target?.name, SettingsTarget.IMPORT_BACKUP.name) { targetModifier ->
            SettingsGroup(title = stringResource(R.string.backup_import_title)) {
                Column(targetModifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.backup_import_guidance))
                    OutlinedButton(
                        onClick = onChooseImport,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("backup_import_choose"),
                    ) { Text(stringResource(R.string.backup_choose_import_file)) }

                    val completedImport = state.importOutcome as? BackupImportOutcome.Success
                    val displayedFilename = state.selectedFilename ?: completedImport?.sourceFilename
                    displayedFilename?.let { filename ->
                        SettingsValueRow(
                            title = stringResource(R.string.backup_selected_file),
                            value = filename,
                            compactValue = false,
                            summary =
                                stringResource(
                                    if (completedImport?.encrypted ?: state.importEncrypted) {
                                        R.string.backup_encrypted
                                    } else {
                                        R.string.backup_plain
                                    },
                                ),
                            modifier = Modifier.testTag("backup_selected_document"),
                        )
                    }

                    if (state.importPhase == BackupImportPhase.PASSWORD_REQUIRED) {
                        PasswordField(
                            value = importPassword,
                            onValueChange = onImportPassword,
                            label = stringResource(R.string.backup_import_password),
                            modifier = Modifier.testTag("backup_import_password"),
                        )
                        Button(
                            onClick = onPreview,
                            enabled = importPassword.isNotBlank(),
                            modifier = Modifier.testTag("backup_import_preview_encrypted"),
                        ) { Text(stringResource(R.string.backup_preview_action)) }
                    }

                    if (state.selectedFilename != null && state.importPhase in setOf(BackupImportPhase.PREVIEW, BackupImportPhase.APPLYING)) {
                        ImportModeRow(state.importMode, onImportMode, enabled = state.importPhase == BackupImportPhase.PREVIEW)
                    } else if (completedImport != null) {
                        SettingsValueRow(
                            title = stringResource(R.string.backup_import_mode),
                            value =
                                stringResource(
                                    if (completedImport.mode == BackupImportMode.MERGE) {
                                        R.string.backup_import_merge
                                    } else {
                                        R.string.backup_import_replace
                                    },
                                ),
                            modifier = Modifier.testTag("backup_completed_mode"),
                        )
                    }

                    state.preview?.let { preview ->
                        ImportPreview(preview)
                        Button(
                            onClick = onApply,
                            enabled = state.importPhase == BackupImportPhase.PREVIEW,
                            modifier = Modifier.testTag("backup_import_apply"),
                        ) {
                            Text(
                                stringResource(
                                    if (state.importMode == BackupImportMode.REPLACE) R.string.backup_replace_action else R.string.backup_apply_action,
                                ),
                            )
                        }
                    }
                    if (state.importPhase == BackupImportPhase.READING) {
                        ProgressRow(stringResource(R.string.backup_preview_progress), "backup_import_progress")
                    }
                    if (state.importPhase == BackupImportPhase.APPLYING) {
                        ProgressRow(stringResource(R.string.backup_apply_progress), "backup_apply_progress")
                    }

                    state.importOutcome?.let { outcome ->
                        when (outcome) {
                            is BackupImportOutcome.Failure -> {
                                PersistentStatusNotice(
                                    text = backupFailureText(outcome.category),
                                    error = true,
                                    modifier = Modifier.testTag("backup_import_result"),
                                    onDismiss = onDismissImport,
                                )
                            }

                            is BackupImportOutcome.Success -> {
                                val result = outcome.result
                                PersistentStatusNotice(
                                    text =
                                        stringResource(
                                            R.string.backup_import_success,
                                            result.addedNetworks,
                                            result.updatedNetworks,
                                            result.removedNetworks,
                                            result.missingCredentialNetworks,
                                        ),
                                    modifier = Modifier.testTag("backup_import_result"),
                                    actionLabel =
                                        if (result.missingCredentialNetworks > 0) {
                                            stringResource(R.string.backup_review_networks)
                                        } else {
                                            null
                                        },
                                    onAction = if (result.missingCredentialNetworks > 0) onReviewNetworks else null,
                                    onDismiss = onDismissImport,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.confirmReplace) {
        val removed = state.preview?.removedNetworks ?: 0
        AlertDialog(
            onDismissRequest = onCancelReplace,
            modifier = Modifier.testTag("backup_replace_confirm"),
            title = { Text(stringResource(R.string.backup_replace_confirm_title)) },
            text = { Text(pluralStringResource(R.plurals.backup_replace_confirm_message, removed, removed)) },
            confirmButton = {
                TextButton(onClick = onConfirmReplace, modifier = Modifier.testTag("backup_replace_confirm_action")) {
                    Text(stringResource(R.string.backup_replace_confirm_action))
                }
            },
            dismissButton = { TextButton(onClick = onCancelReplace) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
internal fun ImportModeRow(
    selected: BackupImportMode,
    onSelected: (BackupImportMode) -> Unit,
    enabled: Boolean = true,
) {
    Column(Modifier.selectableGroup()) {
        SectionHeader(stringResource(R.string.backup_import_mode))
        RadioRow(
            label = stringResource(R.string.backup_import_merge),
            subtitle = stringResource(R.string.backup_import_merge_desc),
            selected = selected == BackupImportMode.MERGE,
            enabled = enabled,
            onClick = { onSelected(BackupImportMode.MERGE) },
            modifier = Modifier.testTag("backup_import_mode_merge"),
        )
        RadioRow(
            label = stringResource(R.string.backup_import_replace),
            subtitle = stringResource(R.string.backup_import_replace_desc),
            selected = selected == BackupImportMode.REPLACE,
            enabled = enabled,
            onClick = { onSelected(BackupImportMode.REPLACE) },
            modifier = Modifier.testTag("backup_import_mode_replace"),
        )
    }
}

@Composable
private fun ImportPreview(preview: ConfigurationImportPreview) {
    Column(Modifier.fillMaxWidth().testTag("backup_import_preview"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader(stringResource(R.string.backup_preview_title))
        Text(stringResource(R.string.backup_preview_source, preview.appVersion, DateFormat.getDateTimeInstance().format(Date(preview.exportedAtEpochMillis))), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        Text(pluralStringResource(R.plurals.backup_preview_networks, preview.networkCount, preview.networkCount))
        Text(stringResource(R.string.backup_preview_changes, preview.addedNetworks, preview.updatedNetworks, preview.removedNetworks))
        Text(stringResource(R.string.backup_preview_folders, preview.folderCount, preview.folderAssignmentCount))
        val settingGroups = preview.settingGroups.map { backupSettingGroupLabel(it) }.distinct()
        val settingGroupText = settingGroups.joinToString(stringResource(R.string.backup_settings_separator)).ifBlank { stringResource(R.string.backup_none) }
        Text(stringResource(R.string.backup_preview_settings, settingGroupText))
        if (preview.containsSecrets) Text(stringResource(R.string.backup_preview_credentials_included))
        if (preview.retainedLocalCredentials > 0) {
            Text(pluralStringResource(R.plurals.backup_preview_credentials_retained, preview.retainedLocalCredentials, preview.retainedLocalCredentials))
        }
        if (preview.missingCredentialNetworks > 0) {
            Text(pluralStringResource(R.plurals.backup_preview_credentials_missing, preview.missingCredentialNetworks, preview.missingCredentialNetworks))
        }
    }
}

@Composable
private fun backupSettingGroupLabel(key: String): String =
    stringResource(
        when (key) {
            "general" -> R.string.backup_settings_general
            "appearance" -> R.string.backup_settings_appearance
            "content previews" -> R.string.backup_settings_content_previews
            "replies" -> R.string.backup_settings_replies
            "uploads" -> R.string.backup_settings_uploads
            "voice" -> R.string.backup_settings_voice
            "avatars" -> R.string.backup_settings_avatars
            "gesture menu" -> R.string.backup_settings_gesture_menu
            else -> R.string.backup_settings_other
        },
    )

@Composable
private fun ProgressRow(
    label: String,
    tag: String,
) {
    Row(
        modifier = Modifier.testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Text(label)
    }
}

@Composable
private fun backupFailureText(category: BackupFailure): String =
    stringResource(
        when (category) {
            BackupFailure.WRONG_PASSWORD_OR_CORRUPT -> R.string.backup_error_password_corrupt
            BackupFailure.UNSUPPORTED_OR_INVALID -> R.string.backup_error_invalid
            BackupFailure.OVERSIZED -> R.string.backup_error_oversized
            BackupFailure.READ -> R.string.backup_error_read
            BackupFailure.WRITE -> R.string.backup_error_write
            BackupFailure.EXPORT -> R.string.backup_error_export
            BackupFailure.IMPORT -> R.string.backup_error_import
        },
    )
