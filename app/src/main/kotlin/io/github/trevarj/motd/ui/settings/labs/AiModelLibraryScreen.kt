package io.github.trevarj.motd.ui.settings.labs

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ai.AiModelCapability
import io.github.trevarj.motd.ai.AiModelRecord
import io.github.trevarj.motd.ai.TranscriptionSettings
import io.github.trevarj.motd.ui.settings.PersistentStatusNotice
import io.github.trevarj.motd.ui.settings.SettingsActionRow
import io.github.trevarj.motd.ui.settings.SettingsDivider
import io.github.trevarj.motd.ui.settings.SettingsGroup
import io.github.trevarj.motd.ui.settings.SettingsNavigationRow
import io.github.trevarj.motd.ui.settings.SettingsScaffold
import java.text.DateFormat
import java.util.Date

private val MODEL_MIME_TYPES = arrayOf("application/octet-stream", "*/*")

@Composable
fun AiModelLibraryScreen(
    onBack: () -> Unit = {},
    viewModel: AiLabsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AiModelLibraryContent(
        state = state,
        onBack = onBack,
        onImport = viewModel::importModel,
        onUpdateSettings = viewModel::updateSettings,
        onDelete = viewModel::deleteModel,
        onDismissStatus = viewModel::dismissStatus,
    )
}

@Composable
internal fun AiModelLibraryContent(
    state: AiLabsUiState,
    onBack: () -> Unit,
    onImport: (Uri) -> Unit,
    onUpdateSettings: (String, AiModelCapability, TranscriptionSettings) -> Unit,
    onDelete: (String) -> Unit,
    onDismissStatus: () -> Unit = {},
) {
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onImport(uri)
        }

    SettingsScaffold(
        title = stringResource(R.string.ai_model_library),
        onBack = onBack,
        modifier = Modifier.testTag("screen_ai_model_library"),
        status = {
            state.status?.let { status ->
                PersistentStatusNotice(
                    text = stringResource(status.message),
                    error = status.error,
                    onDismiss = onDismissStatus,
                    modifier = Modifier.testTag("ai_library_status"),
                )
            }
        },
    ) {
        PersistentStatusNotice(
            text = stringResource(R.string.ai_model_responsibility),
            modifier = Modifier.testTag("ai_model_responsibility"),
        )
        SettingsGroup {
            SettingsActionRow(
                title = stringResource(R.string.ai_import_model),
                summary = stringResource(R.string.ai_import_model_summary),
                enabled = !state.importing,
                modifier = Modifier.testTag("ai_import_model"),
                onClick = { picker.launch(MODEL_MIME_TYPES) },
            )
            state.importProgress?.let { progress ->
                SettingsDivider()
                Column(Modifier.fillMaxWidth().padding(16.dp).testTag("ai_import_progress")) {
                    progress.fraction?.let {
                        LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                    } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text =
                            progress.totalBytes?.let {
                                stringResource(R.string.ai_import_progress_of, formatModelBytes(progress.bytesCopied), formatModelBytes(it))
                            } ?: stringResource(R.string.ai_import_progress_unknown, formatModelBytes(progress.bytesCopied)),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        if (state.models.isEmpty()) {
            SettingsGroup {
                Text(
                    text = stringResource(R.string.ai_model_library_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp).testTag("ai_model_library_empty"),
                )
            }
        } else {
            state.models.forEach { model ->
                AiModelCard(
                    modelState = model,
                    onUpdateSettings = onUpdateSettings,
                    onDelete = { pendingDelete = model.record.id },
                )
            }
        }
        RecommendedModels()
    }

    pendingDelete?.let { modelId ->
        val model = state.models.firstOrNull { it.record.id == modelId }?.record
        if (model != null) {
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("ai_delete_dialog"),
                title = { Text(stringResource(R.string.ai_delete_model_title)) },
                text = { Text(stringResource(R.string.ai_delete_model_message, model.displayName)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDelete = null
                            onDelete(modelId)
                        },
                        modifier = Modifier.testTag("ai_delete_confirm"),
                    ) { Text(stringResource(R.string.action_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }, modifier = Modifier.testTag("ai_delete_cancel")) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun AiModelCard(
    modelState: AiModelUiState,
    onUpdateSettings: (String, AiModelCapability, TranscriptionSettings) -> Unit,
    onDelete: () -> Unit,
) {
    val model = modelState.record
    var expanded by rememberSaveable(model.id) { mutableStateOf(false) }
    val imported = rememberSaveable(model.importedAtEpochMillis) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(model.importedAtEpochMillis)) }
    SettingsGroup(title = model.displayName, modifier = Modifier.testTag("ai_model_${model.id}")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(modelMetadataSummary(model), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.ai_model_imported_at, imported), style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Text(stringResource(R.string.ai_model_sha256, model.id), style = MaterialTheme.typography.bodySmall)
            }
            Text(detailedMetadata(model), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CapabilityBadges(model)
            Text(
                text =
                    if (modelState.assignments.isEmpty()) {
                        stringResource(R.string.ai_model_not_assigned)
                    } else {
                        stringResource(R.string.ai_model_assigned_to, stringResource(R.string.ai_transcription))
                    },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("ai_model_${model.id}_assignments"),
            )
        }
        modelState.settings[AiModelCapability.TRANSCRIPTION]?.let { settings ->
            SettingsDivider()
            SettingsNavigationRow(
                title = stringResource(R.string.ai_transcription_settings),
                summary = stringResource(R.string.ai_transcription_settings_summary, settings.language, settings.cpuThreads),
                value = if (expanded) stringResource(R.string.ai_hide) else stringResource(R.string.ai_show),
                modifier = Modifier.testTag("ai_model_${model.id}_transcription_advanced"),
                onClick = { expanded = !expanded },
            )
            AnimatedVisibility(expanded) {
                TranscriptionSettingsEditor(
                    model = model,
                    settings = settings,
                    tag = "ai_model_${model.id}_transcription",
                    onSave = { onUpdateSettings(model.id, AiModelCapability.TRANSCRIPTION, it) },
                )
            }
        }
        SettingsDivider()
        SettingsActionRow(
            title = stringResource(R.string.ai_delete_model),
            summary = stringResource(R.string.ai_delete_model_summary),
            destructive = true,
            modifier = Modifier.testTag("ai_model_${model.id}_delete"),
            onClick = onDelete,
        )
    }
}

@Composable
private fun CapabilityBadges(model: AiModelRecord) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("ai_model_${model.id}_capabilities")) {
        model.capabilities.forEach { capability ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.testTag("ai_model_${model.id}_capability_${capability.tag()}")) {
                Text(stringResource(R.string.ai_role_transcription), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun RecommendedModels() {
    val context = LocalContext.current
    SettingsGroup(title = stringResource(R.string.ai_recommended_transcription)) {
        ExternalModelLink(R.string.ai_link_whisper_cpp, R.string.ai_link_whisper_cpp_summary, R.string.ai_url_whisper_cpp_models, "ai_link_whisper_cpp") { context.startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) }
        SettingsDivider()
        ExternalModelLink(R.string.ai_link_openai_whisper, R.string.ai_link_openai_whisper_summary, R.string.ai_url_openai_whisper, "ai_link_openai_whisper") { context.startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) }
    }
}

@Composable
private fun ExternalModelLink(
    @StringRes title: Int,
    @StringRes summary: Int,
    @StringRes url: Int,
    tag: String,
    open: (String) -> Unit,
) {
    val address = stringResource(url)
    SettingsNavigationRow(
        title = stringResource(title),
        summary = stringResource(summary),
        modifier = Modifier.testTag(tag),
        onClick = { open(address) },
    )
}

@Composable
private fun detailedMetadata(model: AiModelRecord): String {
    val parts = mutableListOf<String>()
    val audioSeconds = model.metadata.maximumAudioSeconds
    val cpuThreads = model.metadata.maximumCpuThreads
    if (audioSeconds != null) parts += pluralStringResource(R.plurals.ai_metadata_audio_seconds, audioSeconds, audioSeconds)
    if (cpuThreads != null) parts += stringResource(R.string.ai_metadata_cpu_threads, cpuThreads)
    val multilingual = model.metadata.isMultilingual
    if (multilingual != null) parts += stringResource(if (multilingual) R.string.ai_metadata_multilingual else R.string.ai_metadata_single_language)
    return parts.joinToString(" · ")
}

private fun AiModelCapability.tag(): String = name.lowercase()
