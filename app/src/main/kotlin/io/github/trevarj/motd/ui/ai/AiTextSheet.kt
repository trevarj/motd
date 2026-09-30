package io.github.trevarj.motd.ui.ai

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BusinessCenter
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.SentimentSatisfiedAlt
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ai.AiCustomStyle
import io.github.trevarj.motd.ai.AiRuntimeFailure
import io.github.trevarj.motd.ai.AiTranslationTarget
import io.github.trevarj.motd.ai.aiTranslationTargets
import io.github.trevarj.motd.ai.isValid
import io.github.trevarj.motd.ai.text.TextOperation
import io.github.trevarj.motd.ai.text.TextTermination
import io.github.trevarj.motd.irc.format.plainIrcText
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

fun defaultTranslationTarget(locale: Locale): AiTranslationTarget {
    val code =
        if (locale.language == "zh") {
            if (locale.script.equals("Hant", true) || locale.country == "TW" || locale.country == "HK" || locale.country == "MO") "zh-Hant" else "zh-Hans"
        } else {
            locale.language
        }
    return aiTranslationTargets.firstOrNull { it.code == code } ?: aiTranslationTargets.first()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiTextSheet(
    state: AiTextUiState,
    styles: List<AiCustomStyle>,
    target: AiTranslationTarget?,
    onGenerate: (AiTextAction) -> Unit,
    onTargetSelected: (AiTranslationTarget) -> Unit,
    onDismiss: () -> Unit,
    onOpenSetup: () -> Unit,
    onManageStyles: () -> Unit,
    onApply: (() -> Unit)? = null,
) {
    if (state == AiTextUiState.Closed) return
    var picking by remember { mutableStateOf(false) }
    val effectiveTarget = target ?: defaultTranslationTarget(LocalLocale.current.platformLocale)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val source = state.source()
    val composer = source is AiTextSource.Composer
    val contentScroll = key(state::class) { rememberScrollState() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("ai_text_sheet"),
    ) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(if (composer) R.string.ai_text_tools else R.string.ai_text_translate_message),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(if (composer) R.string.ai_text_composer_context else R.string.ai_text_message_context),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(contentScroll)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (state) {
                    is AiTextUiState.Choosing -> {
                        AiTextChoices(state.source, styles, effectiveTarget, !state.isSavingTarget, onGenerate, { picking = true }, onManageStyles)
                        AiTextPreview(stringResource(R.string.ai_text_source), state.source.body(), "ai_text_source")
                    }

                    is AiTextUiState.Running -> {
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 128.dp).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.ai_text_working), style = MaterialTheme.typography.titleMedium)
                        }
                        AiTextPreview(stringResource(R.string.ai_text_source), state.source.body(), "ai_text_source")
                    }

                    is AiTextUiState.Result -> {
                        if (state.result.termination == TextTermination.OUTPUT_LIMIT) {
                            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                                Text(
                                    stringResource(R.string.ai_text_incomplete_notice),
                                    Modifier.fillMaxWidth().padding(16.dp).testTag("ai_text_incomplete"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                        AiTextPreview(stringResource(R.string.ai_text_result), state.result.text, "ai_text_result", emphasized = true)
                        AiTextPreview(stringResource(if (composer) R.string.ai_text_original else R.string.ai_text_source), state.original, "ai_text_source")
                    }

                    is AiTextUiState.Failed -> {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                            Text(
                                stringResource(state.failure.messageResource()),
                                Modifier.fillMaxWidth().padding(16.dp).testTag("ai_text_error"),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        state.source?.let {
                            AiTextChoices(it, styles, effectiveTarget, true, onGenerate, { picking = true }, null)
                        }
                        TextButton(onClick = onOpenSetup, modifier = Modifier.heightIn(min = 48.dp).testTag("ai_text_setup")) {
                            Text(stringResource(R.string.ai_text_open_setup))
                        }
                        state.source?.let {
                            AiTextPreview(stringResource(R.string.ai_text_source), it.body(), "ai_text_source")
                        }
                    }

                    AiTextUiState.Closed -> {}
                }
                Text(
                    stringResource(R.string.ai_text_quality_caveat),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("ai_text_close"),
                ) {
                    Text(stringResource(if (state is AiTextUiState.Running || composer) R.string.action_cancel else R.string.ai_text_close))
                }
                if (state is AiTextUiState.Result) {
                    val complete = state.result.termination == TextTermination.EOG
                    TextButton(
                        onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", state.result.text))) } },
                        enabled = complete,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("ai_text_copy"),
                    ) { Text(stringResource(R.string.ai_text_copy)) }
                    if (composer && onApply != null) {
                        Button(onClick = onApply, enabled = complete, modifier = Modifier.heightIn(min = 48.dp).testTag("ai_text_apply")) {
                            Text(stringResource(R.string.ai_text_apply))
                        }
                    }
                }
            }
        }
    }
    if (picking) {
        AiTranslationTargetPicker(effectiveTarget, {
            picking = false
            onTargetSelected(it)
        }, { picking = false })
    }
}

private fun AiTextSource.body(): String =
    when (this) {
        is AiTextSource.Composer -> plainIrcText(draft.text)
        is AiTextSource.StoredMessage -> text
        is AiTextSource.TransientMessage -> text
    }

@Composable
private fun AiTextPreview(
    label: String,
    text: String,
    tag: String,
    emphasized: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (emphasized) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (emphasized) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AiTextHeading(label)
            SelectionContainer(
                if (emphasized) Modifier else Modifier.heightIn(max = 128.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(text, Modifier.testTag(tag), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AiTextHeading(label: String) {
    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun AiTextChoices(
    source: AiTextSource,
    styles: List<AiCustomStyle>,
    target: AiTranslationTarget,
    enabled: Boolean,
    onGenerate: (AiTextAction) -> Unit,
    onChooseTarget: () -> Unit,
    onManageStyles: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (source is AiTextSource.Composer) {
            AiTextHeading(stringResource(R.string.ai_text_edit))
            AiTextToolRow(Icons.Outlined.Spellcheck, stringResource(R.string.ai_text_correct), stringResource(R.string.ai_text_correct_summary), "ai_text_correct", enabled) {
                onGenerate(AiTextAction(TextOperation.CORRECT))
            }
            AiTextHeading(stringResource(R.string.ai_text_writing_styles))
            AiTextToolRow(Icons.Outlined.Style, stringResource(R.string.ai_text_formal), stringResource(R.string.ai_text_formal_summary), "ai_text_formal", enabled) {
                onGenerate(AiTextAction(TextOperation.FORMAL))
            }
            AiTextToolRow(Icons.Outlined.BusinessCenter, stringResource(R.string.ai_text_business), stringResource(R.string.ai_text_business_summary), "ai_text_business", enabled) {
                onGenerate(AiTextAction(TextOperation.BUSINESS))
            }
            AiTextToolRow(Icons.Outlined.SentimentSatisfiedAlt, stringResource(R.string.ai_text_silly), stringResource(R.string.ai_text_silly_summary), "ai_text_silly", enabled) {
                onGenerate(AiTextAction(TextOperation.SILLY))
            }
            if (styles.isNotEmpty() || onManageStyles != null) {
                AiTextHeading(stringResource(R.string.ai_text_your_styles))
            }
            styles.forEach { style ->
                key(style.id) {
                    AiTextToolRow(Icons.Outlined.AutoFixHigh, style.name, null, "ai_text_style_${style.id}", enabled) {
                        onGenerate(AiTextAction(TextOperation.CUSTOM, customStyleId = style.id))
                    }
                }
            }
            if (onManageStyles != null) {
                TextButton(onClick = onManageStyles, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("ai_text_manage_styles")) {
                    Text(stringResource(R.string.ai_text_manage_styles))
                }
            }
            HorizontalDivider()
        }
        AiTextHeading(stringResource(R.string.ai_text_translate))
        AiTextToolRow(
            Icons.Outlined.Translate,
            target.name,
            stringResource(R.string.ai_text_choose_language),
            "ai_text_target",
            enabled,
            onChooseTarget,
        )
        Button(
            onClick = { onGenerate(AiTextAction(TextOperation.TRANSLATE, translationTarget = target)) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ai_text_translate"),
        ) {
            Text(stringResource(if (enabled) R.string.ai_text_translate else R.string.ai_text_saving_target))
        }
    }
}

@Composable
private fun AiTextToolRow(
    icon: ImageVector,
    title: String,
    supporting: String?,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
        colors =
            ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                headlineColor = color,
                supportingColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else color,
                leadingIconColor = color,
                trailingIconColor = color,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(tag)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiTranslationTargetPicker(
    selected: AiTranslationTarget?,
    onSelected: (AiTranslationTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    var other by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(if (selected?.code == "other") selected.name else "") }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("ai_translation_picker")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text(stringResource(R.string.ai_text_translation_language), style = MaterialTheme.typography.titleLarge)
            aiTranslationTargets.forEach { target ->
                TextButton(onClick = { onSelected(target) }, modifier = Modifier.fillMaxWidth().testTag("ai_language_${target.code}")) { Text(target.name + if (target == selected) " ✓" else "") }
            }
            TextButton(onClick = { other = true }, modifier = Modifier.testTag("ai_language_other")) { Text(stringResource(R.string.ai_text_other)) }
        }
    }
    if (other) {
        AlertDialog(
            onDismissRequest = { other = false },
            title = { Text(stringResource(R.string.ai_text_other_language)) },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.ai_text_language_name)) }, singleLine = true, modifier = Modifier.testTag("ai_language_other_name"))
                    Text(stringResource(R.string.ai_text_other_bounds))
                }
            },
            confirmButton = { TextButton(onClick = { onSelected(AiTranslationTarget("other", name.trim())) }, enabled = AiTranslationTarget("other", name.trim()).isValid(), modifier = Modifier.testTag("ai_language_other_save")) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { other = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiCustomStylesSheet(
    styles: List<AiCustomStyle>,
    onSave: (AiCustomStyle) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<AiCustomStyle?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("ai_custom_styles")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text(stringResource(R.string.ai_text_custom_styles), style = MaterialTheme.typography.titleLarge)
            styles.forEach { style ->
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { editing = style }, modifier = Modifier.weight(1f).testTag("ai_style_edit_${style.id}")) { Text(style.name) }
                    TextButton(onClick = { onDelete(style.id) }, modifier = Modifier.testTag("ai_style_delete_${style.id}")) { Text(stringResource(R.string.action_delete)) }
                }
            }
            TextButton(onClick = { editing = AiCustomStyle(UUID.randomUUID().toString(), "", "") }, enabled = styles.size < 20, modifier = Modifier.testTag("ai_style_add")) { Text(stringResource(R.string.ai_text_add_style)) }
        }
    }
    editing?.let { original ->
        var name by remember(original.id) { mutableStateOf(original.name) }
        var instruction by remember(original.id) { mutableStateOf(original.instruction) }
        val candidate = original.copy(name = name.trim(), instruction = instruction)
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.ai_text_writing_style)) }, text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.ai_text_style_name)) }, modifier = Modifier.testTag("ai_style_name"))
                OutlinedTextField(instruction, { instruction = it }, label = { Text(stringResource(R.string.ai_text_style_instruction)) }, modifier = Modifier.testTag("ai_style_instruction"))
            }
        }, confirmButton = {
            TextButton(onClick = {
                onSave(candidate)
                editing = null
            }, enabled = candidate.isValid(), modifier = Modifier.testTag("ai_style_save")) { Text(stringResource(R.string.action_save)) }
        }, dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
}

private fun AiRuntimeFailure.messageResource(): Int =
    when (this) {
        AiRuntimeFailure.MODEL_OPEN -> R.string.ai_error_source_open
        AiRuntimeFailure.INVALID_FORMAT -> R.string.ai_error_invalid_format
        AiRuntimeFailure.TRUNCATED_MODEL -> R.string.ai_error_truncated_model
        AiRuntimeFailure.CORRUPT_MODEL -> R.string.ai_error_corrupt_model
        AiRuntimeFailure.UNSUPPORTED_ARCHITECTURE -> R.string.ai_error_unsupported_architecture
        AiRuntimeFailure.UNSUPPORTED_TEMPLATE -> R.string.ai_error_unsupported_template
        AiRuntimeFailure.INPUT_TOO_LONG -> R.string.ai_error_input_too_long
        AiRuntimeFailure.INVALID_OUTPUT -> R.string.ai_error_invalid_output
        AiRuntimeFailure.NO_TEXT -> R.string.ai_error_no_text
        AiRuntimeFailure.INVALID_REQUEST -> R.string.ai_error_invalid_settings
        AiRuntimeFailure.OUT_OF_MEMORY -> R.string.ai_error_native_out_of_memory
        AiRuntimeFailure.NO_MODEL_LOADED -> R.string.ai_error_model_not_ready
        AiRuntimeFailure.INVALID_AUDIO, AiRuntimeFailure.INFERENCE, AiRuntimeFailure.NATIVE -> R.string.ai_error_runtime
    }
