package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.KeywordHighlightRule
import io.github.trevarj.motd.data.prefs.KeywordRuleError
import io.github.trevarj.motd.data.prefs.MAX_KEYWORD_HIGHLIGHT_LENGTH
import io.github.trevarj.motd.data.prefs.MAX_KEYWORD_HIGHLIGHT_RULES
import io.github.trevarj.motd.data.prefs.keywordHighlightRuleError
import io.github.trevarj.motd.ui.theme.UiIcon
import io.github.trevarj.motd.ui.theme.readableOutlinedTextFieldColors
import io.github.trevarj.motd.ui.theme.readableTextButtonColors
import kotlinx.coroutines.launch
import java.io.IOException
import io.github.trevarj.motd.ui.components.EmojiOutlinedTextField as OutlinedTextField
import io.github.trevarj.motd.ui.components.EmojiText as Text

private val editedRuleSaver =
    listSaver<KeywordHighlightRule?, Any>(
        save = { rule -> if (rule == null) emptyList() else listOf(rule.phrase, rule.alerts) },
        restore = { KeywordHighlightRule(it[0] as String, it[1] as Boolean) },
    )

@Composable
internal fun KeywordHighlightSettings(
    rules: List<KeywordHighlightRule>,
    onBack: () -> Unit,
    onEdit: suspend (KeywordHighlightRule?, KeywordHighlightRule?) -> Boolean,
) {
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var previous by rememberSaveable(stateSaver = editedRuleSaver) { mutableStateOf<KeywordHighlightRule?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val writeError = stringResource(R.string.settings_keyword_highlight_save_failed)

    fun save(
        old: KeywordHighlightRule?,
        replacement: KeywordHighlightRule?,
    ) {
        if (saving) return
        saving = true
        saveFailed = false
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            val saved =
                try {
                    onEdit(old, replacement)
                } catch (_: IOException) {
                    false
                } finally {
                    saving = false
                }
            when {
                saved -> editorOpen = false
                replacement != null -> saveFailed = true
                else -> snackbar.showSnackbar(writeError, withDismissAction = true, duration = SnackbarDuration.Long)
            }
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_keyword_highlights),
        onBack = onBack,
        snackbarHostState = snackbar,
    ) {
        Text(stringResource(R.string.settings_keyword_highlights_help), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = {
                previous = null
                saveFailed = false
                editorOpen = true
            },
            enabled = !saving && rules.size < MAX_KEYWORD_HIGHLIGHT_RULES,
            modifier = Modifier.fillMaxWidth().testTag("keyword_highlight_add"),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                UiIcon(Icons.Outlined.Add, contentDescription = null)
                Text(stringResource(R.string.settings_keyword_highlight_add))
            }
        }
        if (rules.size >= MAX_KEYWORD_HIGHLIGHT_RULES) {
            Text(pluralStringResource(R.plurals.settings_keyword_highlight_limit, MAX_KEYWORD_HIGHLIGHT_RULES, MAX_KEYWORD_HIGHLIGHT_RULES))
        }
        if (rules.isEmpty()) {
            Text(stringResource(R.string.settings_keyword_highlights_empty))
        } else {
            SettingsGroup {
                rules.forEachIndexed { index, rule ->
                    if (index != 0) SettingsDivider()
                    ListItem(
                        headlineContent = { Text(rule.phrase) },
                        supportingContent = {
                            Text(stringResource(if (rule.alerts) R.string.settings_keyword_highlight_alerts_on else R.string.settings_keyword_highlight_alerts_off))
                        },
                        trailingContent = {
                            Row {
                                IconButton(
                                    onClick = {
                                        previous = rule
                                        saveFailed = false
                                        editorOpen = true
                                    },
                                    enabled = !saving,
                                    modifier = Modifier.testTag("keyword_highlight_edit_$index"),
                                ) {
                                    UiIcon(Icons.Outlined.Edit, stringResource(R.string.settings_keyword_highlight_edit_description, rule.phrase))
                                }
                                IconButton(
                                    onClick = { save(rule, null) },
                                    enabled = !saving,
                                    modifier = Modifier.testTag("keyword_highlight_delete_$index"),
                                ) {
                                    UiIcon(Icons.Outlined.Delete, stringResource(R.string.settings_keyword_highlight_delete_description, rule.phrase))
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }

    if (editorOpen) {
        val old = previous
        val otherRules = remember(rules, old) { if (old == null) rules else rules - old }
        KeywordHighlightDialog(
            initial = old,
            otherRules = otherRules,
            saving = saving,
            saveFailed = saveFailed,
            onSave = { save(old, it) },
            onDismiss = { if (!saving) editorOpen = false },
        )
    }
}

@Composable
private fun KeywordHighlightDialog(
    initial: KeywordHighlightRule?,
    otherRules: List<KeywordHighlightRule>,
    saving: Boolean,
    saveFailed: Boolean,
    onSave: (KeywordHighlightRule) -> Unit,
    onDismiss: () -> Unit,
) {
    var phrase by rememberSaveable(initial) { mutableStateOf(initial?.phrase.orEmpty()) }
    var alerts by rememberSaveable(initial) { mutableStateOf(initial?.alerts ?: false) }
    val term = phrase.trim()
    val error = keywordHighlightRuleError(term, otherRules)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.settings_keyword_highlight_add else R.string.settings_keyword_highlight_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = phrase,
                        onValueChange = { phrase = it },
                        colors = readableOutlinedTextFieldColors(),
                        label = { Text(stringResource(R.string.settings_keyword_highlight_phrase)) },
                        supportingText = {
                            Text(
                                when (error) {
                                    KeywordRuleError.DUPLICATE -> {
                                        stringResource(R.string.settings_keyword_highlight_duplicate)
                                    }

                                    KeywordRuleError.LIMIT -> {
                                        pluralStringResource(
                                            R.plurals.settings_keyword_highlight_limit,
                                            MAX_KEYWORD_HIGHLIGHT_RULES,
                                            MAX_KEYWORD_HIGHLIGHT_RULES,
                                        )
                                    }

                                    else -> {
                                        pluralStringResource(
                                            R.plurals.settings_keyword_highlight_phrase_limit,
                                            MAX_KEYWORD_HIGHLIGHT_LENGTH,
                                            MAX_KEYWORD_HIGHLIGHT_LENGTH,
                                        )
                                    }
                                },
                            )
                        },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        singleLine = true,
                        enabled = !saving,
                        isError = phrase.isNotEmpty() && error != null,
                        modifier = Modifier.fillMaxWidth().testTag("keyword_highlight_phrase"),
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_keyword_highlight_alerts),
                        subtitle = stringResource(R.string.settings_keyword_highlight_alerts_desc),
                        checked = alerts,
                        onCheckedChange = { alerts = it },
                        enabled = !saving,
                        modifier = Modifier.testTag("keyword_highlight_alerts"),
                    )
                }
                if (saveFailed) {
                    Text(
                        stringResource(R.string.settings_keyword_highlight_save_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                colors = readableTextButtonColors(),
                enabled = !saving && error == null,
                onClick = { onSave(KeywordHighlightRule(term, alerts)) },
                modifier = Modifier.testTag("keyword_highlight_save"),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(colors = readableTextButtonColors(), enabled = !saving, onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        modifier =
            Modifier
                .safeDrawingPadding()
                .padding(horizontal = 16.dp)
                .semantics { testTagsAsResourceId = true },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    )
}
