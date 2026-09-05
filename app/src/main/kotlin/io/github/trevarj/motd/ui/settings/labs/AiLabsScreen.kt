package io.github.trevarj.motd.ui.settings.labs

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ai.AiFeature
import io.github.trevarj.motd.ai.AiModelCapability
import io.github.trevarj.motd.ai.AiModelRecord
import io.github.trevarj.motd.ai.TranscriptionSettings
import io.github.trevarj.motd.ui.nav.SettingsTarget
import io.github.trevarj.motd.ui.settings.PersistentStatusNotice
import io.github.trevarj.motd.ui.settings.RadioRow
import io.github.trevarj.motd.ui.settings.SettingsActionRow
import io.github.trevarj.motd.ui.settings.SettingsDivider
import io.github.trevarj.motd.ui.settings.SettingsGroup
import io.github.trevarj.motd.ui.settings.SettingsNavigationRow
import io.github.trevarj.motd.ui.settings.SettingsScaffold
import io.github.trevarj.motd.ui.settings.SwitchRow
import io.github.trevarj.motd.ui.settings.SettingsTarget as SettingsTargetAnchor

@Composable
fun AiLabsScreen(
    onBack: () -> Unit = {},
    onOpenModelLibrary: () -> Unit = {},
    target: SettingsTarget? = null,
    viewModel: AiLabsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AiLabsContent(
        state = state,
        onBack = onBack,
        onOpenModelLibrary = onOpenModelLibrary,
        onFeatureEnabled = viewModel::setFeatureEnabled,
        onAssignModel = viewModel::assignModel,
        onUpdateSettings = viewModel::updateSettings,
        onClearCaches = viewModel::clearCaches,
        onDismissStatus = viewModel::dismissStatus,
        target = target,
    )
}

@Composable
internal fun AiLabsContent(
    state: AiLabsUiState,
    onBack: () -> Unit,
    onOpenModelLibrary: () -> Unit,
    onFeatureEnabled: (AiFeature, Boolean) -> Unit,
    onAssignModel: (AiFeature, String) -> Unit,
    onUpdateSettings: (String, AiModelCapability, TranscriptionSettings) -> Unit,
    onClearCaches: () -> Unit,
    onDismissStatus: () -> Unit = {},
    target: SettingsTarget? = null,
) {
    var selectingFeature by rememberSaveable { mutableStateOf<AiFeature?>(null) }
    val requestedTarget = if (target == SettingsTarget.AI) SettingsTarget.AI_MODELS else target
    val statusContent: (@Composable () -> Unit)? =
        state.status?.let { status ->
            {
                PersistentStatusNotice(
                    text = stringResource(status.message),
                    error = status.error,
                    onDismiss = onDismissStatus,
                    modifier = Modifier.testTag("ai_status"),
                )
            }
        }

    SettingsScaffold(
        title = stringResource(R.string.ai_labs_title),
        onBack = onBack,
        modifier = Modifier.testTag("screen_ai_labs"),
        status = statusContent,
    ) {
        PersistentStatusNotice(
            text = stringResource(R.string.ai_local_disclosure),
            modifier = Modifier.testTag("ai_local_disclosure"),
        )
        SettingsTargetAnchor(requestedTarget?.name, SettingsTarget.AI_MODELS.name) { targetModifier ->
            SettingsGroup(modifier = targetModifier) {
                SettingsNavigationRow(
                    title = stringResource(R.string.ai_model_library),
                    summary = stringResource(R.string.ai_model_library_summary),
                    value = pluralStringResource(R.plurals.ai_model_count, state.models.size, state.models.size),
                    modifier = Modifier.testTag("ai_model_library"),
                    onClick = onOpenModelLibrary,
                )
            }
        }
        AiFeatureSection(
            featureState = state.feature(AiFeature.TRANSCRIPTION),
            title = stringResource(R.string.ai_transcription),
            summary = stringResource(R.string.ai_transcription_summary),
            target = SettingsTarget.AI_TRANSCRIPTION,
            requestedTarget = requestedTarget,
            onSelectModel = { selectingFeature = AiFeature.TRANSCRIPTION },
            onOpenModelLibrary = onOpenModelLibrary,
            onFeatureEnabled = onFeatureEnabled,
            onUpdateSettings = onUpdateSettings,
        )
        SettingsGroup(title = stringResource(R.string.ai_storage_section)) {
            SettingsActionRow(
                title = if (state.clearingCaches) stringResource(R.string.ai_clearing_caches) else stringResource(R.string.ai_clear_caches),
                summary = stringResource(R.string.ai_clear_caches_summary),
                enabled = !state.clearingCaches,
                modifier = Modifier.testTag("ai_clear_caches"),
                onClick = onClearCaches,
            )
        }
    }

    selectingFeature?.let { feature ->
        ModelSelectionSheet(
            featureState = state.feature(feature),
            onSelect = { modelId -> onAssignModel(feature, modelId) },
            onOpenModelLibrary = onOpenModelLibrary,
            onDismiss = { selectingFeature = null },
        )
    }
}

@Composable
private fun AiFeatureSection(
    featureState: AiFeatureUiState,
    title: String,
    summary: String,
    target: SettingsTarget,
    requestedTarget: SettingsTarget?,
    onSelectModel: () -> Unit,
    onOpenModelLibrary: () -> Unit,
    onFeatureEnabled: (AiFeature, Boolean) -> Unit,
    onUpdateSettings: (String, AiModelCapability, TranscriptionSettings) -> Unit,
) {
    val tag = featureState.feature.tag()
    var expanded by rememberSaveable(featureState.feature.name) { mutableStateOf(false) }
    val chooseModel = {
        if (featureState.selectableModels.isEmpty()) onOpenModelLibrary() else onSelectModel()
    }
    SettingsTargetAnchor(requestedTarget?.name, target.name) { targetModifier ->
        Column(modifier = targetModifier) {
            SettingsGroup(title = title, modifier = Modifier.testTag("${tag}_group")) {
                SettingsNavigationRow(
                    title = stringResource(R.string.ai_active_model),
                    summary = if (featureState.ready) stringResource(R.string.ai_active_model_ready) else stringResource(R.string.ai_model_setup_required),
                    value = featureState.assignedModel?.displayName ?: stringResource(R.string.ai_no_model),
                    modifier = Modifier.testTag("${tag}_model"),
                    onClick = chooseModel,
                )
                SettingsDivider()
                SwitchRow(
                    title = stringResource(R.string.ai_enable_feature, title),
                    subtitle = if (featureState.ready) summary else "$summary ${stringResource(R.string.ai_model_setup_required)}",
                    checked = featureState.enabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !featureState.ready) chooseModel() else onFeatureEnabled(featureState.feature, enabled)
                    },
                    switchTag = "${tag}_switch",
                )
                SettingsDivider()
                SettingsNavigationRow(
                    title = stringResource(R.string.ai_advanced_settings),
                    summary = if (featureState.assignedModel == null) stringResource(R.string.ai_choose_model_first) else stringResource(R.string.ai_advanced_settings_summary),
                    value = if (expanded) stringResource(R.string.ai_hide) else stringResource(R.string.ai_show),
                    modifier = Modifier.testTag("${tag}_advanced"),
                    onClick = {
                        if (featureState.assignedModel == null) chooseModel() else expanded = !expanded
                    },
                )
                AnimatedVisibility(expanded && featureState.assignedModel != null && featureState.settings != null) {
                    val model = featureState.assignedModel
                    val settings = featureState.settings
                    if (model != null && settings != null) {
                        TranscriptionSettingsEditor(
                            model = model,
                            settings = settings,
                            tag = tag,
                            onSave = { onUpdateSettings(model.id, featureState.capability, it) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelectionSheet(
    featureState: AiFeatureUiState,
    onSelect: (String) -> Unit,
    onOpenModelLibrary: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("${featureState.feature.tag()}_model_sheet")) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.ai_choose_active_model),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            featureState.selectableModels.forEach { model ->
                RadioRow(
                    label = model.displayName,
                    subtitle = modelMetadataSummary(model),
                    selected = featureState.assignedModel?.id == model.id,
                    enabled = true,
                    onClick = {
                        onSelect(model.id)
                        onDismiss()
                    },
                    modifier = Modifier.testTag("${featureState.feature.tag()}_model_option_${model.id}"),
                )
            }
            SettingsDivider()
            SettingsActionRow(
                title = stringResource(R.string.ai_open_model_library),
                summary = stringResource(R.string.ai_open_model_library_summary),
                modifier = Modifier.testTag("${featureState.feature.tag()}_model_setup"),
                onClick = {
                    onDismiss()
                    onOpenModelLibrary()
                },
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
internal fun TranscriptionSettingsEditor(
    model: AiModelRecord,
    settings: TranscriptionSettings,
    tag: String,
    onSave: (TranscriptionSettings) -> Unit,
) {
    var language by rememberSaveable(model.id, settings) { mutableStateOf(settings.language) }
    var prompt by rememberSaveable(model.id, settings) { mutableStateOf(settings.initialPrompt) }
    var threads by rememberSaveable(model.id, settings) { mutableStateOf(settings.cpuThreads.toString()) }
    var error by rememberSaveable(model.id, settings) { mutableStateOf<Int?>(null) }
    val maximumThreads = model.maximumThreads()

    SettingsEditorColumn(tag, error) {
        TextSettingField(language, { language = it }, R.string.ai_transcription_language, R.string.ai_transcription_language_summary, "${tag}_language")
        TextSettingField(prompt, { prompt = it }, R.string.ai_initial_prompt, R.string.ai_initial_prompt_summary, "${tag}_initial_prompt", singleLine = false)
        NumberSettingField(threads, { threads = it }, R.string.ai_cpu_threads, "${tag}_cpu_threads")
        SettingsActionRow(
            title = stringResource(R.string.action_save),
            modifier = Modifier.testTag("${tag}_settings_save"),
            onClick = {
                val threadsValue = threads.toIntOrNull()
                error =
                    when {
                        language.isBlank() -> R.string.ai_error_language
                        threadsValue == null || threadsValue !in 1..maximumThreads -> R.string.ai_error_threads_range
                        else -> null
                    }
                if (error == null) onSave(TranscriptionSettings(language.trim(), prompt, threadsValue!!))
            },
        )
    }
}

@Composable
private fun SettingsEditorColumn(
    tag: String,
    @StringRes error: Int?,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("${tag}_settings"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        error?.let {
            PersistentStatusNotice(text = stringResource(it), error = true, modifier = Modifier.testTag("${tag}_settings_error"))
        }
        content()
    }
}

@Composable
private fun NumberSettingField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes label: Int,
    tag: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun TextSettingField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes label: Int,
    @StringRes summary: Int,
    tag: String,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        supportingText = { Text(stringResource(summary)) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

internal fun AiFeature.tag(): String =
    when (this) {
        AiFeature.TRANSCRIPTION -> "ai_transcription"
    }

internal fun modelMetadataSummary(model: AiModelRecord): String = listOf(model.format.name, model.metadata.architecture, model.metadata.quantization, formatModelBytes(model.sizeBytes)).joinToString(" · ")

internal fun formatModelBytes(bytes: Long): String =
    when {
        bytes < 1_024L -> "$bytes B"
        bytes < 1_024L * 1_024L -> "${bytes / 1_024L} KiB"
        bytes < 1_024L * 1_024L * 1_024L -> "${bytes / (1_024L * 1_024L)} MiB"
        else -> "${bytes / (1_024L * 1_024L * 1_024L)} GiB"
    }

private fun AiModelRecord.maximumThreads(): Int = minOf(Runtime.getRuntime().availableProcessors().coerceAtLeast(1), metadata.maximumCpuThreads ?: Int.MAX_VALUE)
