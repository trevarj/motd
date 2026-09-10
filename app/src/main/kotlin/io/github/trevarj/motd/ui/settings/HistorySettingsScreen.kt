package io.github.trevarj.motd.ui.settings

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.AUTO_COMPACT_MB_CHOICES
import io.github.trevarj.motd.data.prefs.HistoryRetention
import io.github.trevarj.motd.data.prefs.MIN_CUSTOM_RETENTION_ROWS
import io.github.trevarj.motd.data.prefs.QUERY_RETENTION_MULTIPLIER
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.channelRetentionRows
import io.github.trevarj.motd.data.sync.DatabaseProfile
import io.github.trevarj.motd.ui.nav.SettingsTarget
import io.github.trevarj.motd.ui.theme.MotdTheme
import java.text.NumberFormat

@Composable
fun HistorySettingsScreen(
    onBack: () -> Unit = {},
    target: SettingsTarget? = null,
    viewModel: HistorySettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val databaseSizeBytes by viewModel.databaseSizeBytes.collectAsStateWithLifecycle()
    val databaseProfile by viewModel.databaseProfile.collectAsStateWithLifecycle()
    val compacting by viewModel.compacting.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    // Resources rather than Context: the Compose lint (LocalContextGetResourceValueCall) is right
    // that a LocalContext read inside an effect would not follow a configuration change.
    val resources = LocalResources.current
    val compactFailed = stringResource(R.string.settings_database_compact_failed)
    LaunchedEffect(viewModel, resources, compactFailed) {
        viewModel.databaseCompactEvents.collect { event ->
            val message =
                when (event) {
                    is DatabaseCompactEvent.Compacted -> {
                        resources.getString(R.string.settings_database_compacted, Formatter.formatFileSize(context, event.freedBytes))
                    }

                    DatabaseCompactEvent.Failed -> {
                        compactFailed
                    }
                }
            snackbarHostState.showSnackbar(message)
        }
    }
    HistorySettingsContent(
        settings = settings,
        databaseSizeBytes = databaseSizeBytes,
        databaseProfile = databaseProfile,
        onBack = onBack,
        onHistoryRetention = viewModel::setHistoryRetention,
        onHistoryRetentionCustomRows = viewModel::setHistoryRetentionCustomRows,
        onAutoCompactMb = viewModel::setAutoCompactMb,
        onCompactDatabase = viewModel::compactDatabase,
        compacting = compacting,
        target = target,
        snackbarHostState = snackbarHostState,
    )
}

@Composable
fun HistorySettingsContent(
    settings: Settings,
    databaseSizeBytes: Long,
    databaseProfile: DatabaseProfile?,
    onBack: () -> Unit,
    onHistoryRetention: (HistoryRetention) -> Unit,
    onHistoryRetentionCustomRows: (Int) -> Unit,
    onCompactDatabase: () -> Unit,
    onAutoCompactMb: (Int) -> Unit = {},
    compacting: Boolean = false,
    target: SettingsTarget? = null,
    snackbarHostState: SnackbarHostState? = null,
) {
    val context = LocalContext.current
    var retentionSheetOpen by remember { mutableStateOf(false) }
    var autoCompactSheetOpen by remember { mutableStateOf(false) }
    val effectiveRows = settings.channelRetentionRows
    SettingsScaffold(
        title = stringResource(R.string.settings_history),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    ) {
        SettingsGroup(title = stringResource(R.string.settings_history_overview_section)) {
            SettingsValueRow(
                title = stringResource(R.string.settings_history_db_size),
                value = Formatter.formatFileSize(context, databaseSizeBytes),
                modifier = Modifier.testTag("settings_history_db_size"),
            )
            SettingsValueRow(
                title = stringResource(R.string.settings_history_messages),
                value = databaseProfile?.let { formatRows(it.totalRows) } ?: "…",
                modifier = Modifier.testTag("settings_history_messages"),
            )
            SettingsValueRow(
                title = stringResource(R.string.settings_history_projected),
                value =
                    when {
                        effectiveRows == null -> stringResource(R.string.settings_history_projected_off)
                        databaseProfile == null -> "…"
                        else -> Formatter.formatFileSize(context, databaseProfile.projectedBytes(effectiveRows))
                    },
                modifier = Modifier.testTag("settings_history_projected"),
            )
        }
        SettingsGroup(title = stringResource(R.string.settings_local_history_section)) {
            SettingsNavigationRow(
                title = stringResource(R.string.settings_history_retention),
                value = historyRetentionValue(settings),
                summary = stringResource(R.string.settings_history_retention_desc),
                modifier = Modifier.testTag("settings_history_retention"),
                requestedTarget = target?.name,
                targetName = SettingsTarget.HISTORY_RETENTION.name,
                onClick = { retentionSheetOpen = true },
            )
            RetentionPlanner(
                profile = databaseProfile,
                savedRows = effectiveRows,
                onApply = onHistoryRetentionCustomRows,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsNavigationRow(
                title = stringResource(R.string.settings_auto_compact),
                value = autoCompactLabel(settings.autoCompactMb),
                summary = stringResource(R.string.settings_auto_compact_desc),
                modifier = Modifier.testTag("settings_auto_compact"),
                requestedTarget = target?.name,
                targetName = SettingsTarget.AUTO_COMPACT.name,
                onClick = { autoCompactSheetOpen = true },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsTarget(target?.name, SettingsTarget.COMPACT_DATABASE.name) { targetModifier ->
                SettingsActionRow(
                    title = stringResource(if (compacting) R.string.settings_compact_database_running else R.string.settings_compact_database),
                    summary =
                        if (compacting) {
                            stringResource(R.string.settings_compact_database_running_desc)
                        } else {
                            stringResource(R.string.settings_compact_database_desc, Formatter.formatFileSize(context, databaseSizeBytes))
                        },
                    modifier = targetModifier.testTag("settings_compact_database"),
                    enabled = !compacting,
                    onClick = onCompactDatabase,
                )
            }
        }
    }
    if (autoCompactSheetOpen) {
        SingleChoiceSheet(
            title = stringResource(R.string.settings_auto_compact),
            selected = settings.autoCompactMb,
            options = AUTO_COMPACT_MB_CHOICES.map { mb -> ChoiceOption(mb, autoCompactLabel(mb), tag = "settings_auto_compact_$mb") },
            onSelect = onAutoCompactMb,
            onDismiss = { autoCompactSheetOpen = false },
            tag = "settings_auto_compact_sheet",
        )
    }
    if (retentionSheetOpen) {
        SingleChoiceSheet(
            title = stringResource(R.string.settings_history_retention),
            selected = settings.historyRetention,
            options =
                HistoryRetention.entries.map { retention ->
                    ChoiceOption(
                        retention,
                        stringResource(historyRetentionLabel(retention)),
                        summary = stringResource(historyRetentionDescription(retention)),
                        tag = "settings_history_retention_${retention.name.lowercase()}",
                    )
                },
            onSelect = onHistoryRetention,
            onDismiss = { retentionSheetOpen = false },
            tag = "settings_history_retention_sheet",
        )
    }
}

/**
 * Inline planner: either field drives the other through [DatabaseProfile] — a cap projects a
 * size, a size target solves for the cap — and Apply saves the cap as a custom retention. Nothing
 * is written while typing, so a half-entered number can never trigger a prune.
 */
@Composable
private fun RetentionPlanner(
    profile: DatabaseProfile?,
    savedRows: Int?,
    onApply: (Int) -> Unit,
) {
    var rowsText by remember { mutableStateOf("") }
    var sizeText by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    // A saved cap (Apply, a preset, Off) always re-syncs the fields; a new measurement only does so
    // while the user is not mid-edit.
    LaunchedEffect(savedRows) { dirty = false }
    LaunchedEffect(profile, savedRows, dirty) {
        if (!dirty) {
            rowsText = savedRows?.toString().orEmpty()
            sizeText = if (profile != null && savedRows != null) megabytes(profile.projectedBytes(savedRows)) else ""
        }
    }
    val rows = rowsText.toIntOrNull()?.coerceAtLeast(MIN_CUSTOM_RETENTION_ROWS)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = rowsText,
            onValueChange = { text ->
                dirty = true
                rowsText = text.filter(Char::isDigit)
                val cap = rowsText.toIntOrNull()
                if (profile != null && cap != null) sizeText = megabytes(profile.projectedBytes(cap.coerceAtLeast(MIN_CUSTOM_RETENTION_ROWS)))
            },
            label = { Text(stringResource(R.string.settings_retention_rows_label)) },
            singleLine = true,
            enabled = profile != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("settings_retention_rows_field"),
        )
        OutlinedTextField(
            value = sizeText,
            onValueChange = { text ->
                dirty = true
                sizeText = text
                val target = text.toDoubleOrNull()
                if (profile != null && target != null) {
                    rowsText = profile.channelRowsFor((target * MEGABYTE).toLong()).coerceAtLeast(MIN_CUSTOM_RETENTION_ROWS).toString()
                }
            },
            label = { Text(stringResource(R.string.settings_retention_size_label)) },
            singleLine = true,
            enabled = profile != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag("settings_retention_size_field"),
        )
        Text(
            if (profile == null) {
                stringResource(R.string.settings_retention_planner_analyzing)
            } else {
                stringResource(R.string.settings_retention_planner_note, QUERY_RETENTION_MULTIPLIER)
            },
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            onClick = {
                rows?.let(onApply)
                dirty = false
            },
            enabled = dirty && rows != null && rows != savedRows,
            modifier = Modifier.align(Alignment.End).testTag("settings_retention_apply"),
        ) {
            Text(stringResource(R.string.settings_history_apply))
        }
    }
}

private const val MEGABYTE = 1_000_000.0

private fun megabytes(bytes: Long): String = "%.1f".format(bytes / MEGABYTE)

private fun formatRows(rows: Number): String = NumberFormat.getIntegerInstance().format(rows)

@Composable
private fun autoCompactLabel(mb: Int): String = if (mb == 0) stringResource(R.string.settings_auto_compact_off) else stringResource(R.string.settings_auto_compact_threshold, mb)

@Composable
internal fun historyRetentionValue(settings: Settings): String =
    if (settings.historyRetention == HistoryRetention.CUSTOM) {
        stringResource(R.string.settings_history_retention_custom_value, formatRows(settings.historyRetentionCustomRows))
    } else {
        stringResource(historyRetentionLabel(settings.historyRetention))
    }

@StringRes
internal fun historyRetentionLabel(retention: HistoryRetention): Int =
    when (retention) {
        HistoryRetention.OFF -> R.string.settings_history_retention_off
        HistoryRetention.COMPACT -> R.string.settings_history_retention_compact
        HistoryRetention.BALANCED -> R.string.settings_history_retention_balanced
        HistoryRetention.GENEROUS -> R.string.settings_history_retention_generous
        HistoryRetention.CUSTOM -> R.string.settings_history_retention_custom
    }

@StringRes
internal fun historyRetentionDescription(retention: HistoryRetention): Int =
    when (retention) {
        HistoryRetention.OFF -> R.string.settings_history_retention_off_desc
        HistoryRetention.COMPACT -> R.string.settings_history_retention_compact_desc
        HistoryRetention.BALANCED -> R.string.settings_history_retention_balanced_desc
        HistoryRetention.GENEROUS -> R.string.settings_history_retention_generous_desc
        HistoryRetention.CUSTOM -> R.string.settings_history_retention_custom_desc
    }

@Preview
@Composable
private fun HistorySettingsPreview() {
    MotdTheme {
        HistorySettingsContent(
            settings = Settings(),
            databaseSizeBytes = 14_356_480L,
            databaseProfile = null,
            onBack = {},
            onHistoryRetention = {},
            onHistoryRetentionCustomRows = {},
            onCompactDatabase = {},
        )
    }
}
