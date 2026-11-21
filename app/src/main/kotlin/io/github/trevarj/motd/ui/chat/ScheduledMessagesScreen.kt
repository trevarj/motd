package io.github.trevarj.motd.ui.chat

import android.app.DatePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TimePickerDialogDefaults
import androidx.compose.material3.TimePickerDisplayMode
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.ScheduledMessageEntity
import io.github.trevarj.motd.data.db.ScheduledMessageState
import io.github.trevarj.motd.data.db.isSchedulableMessage
import io.github.trevarj.motd.irc.format.plainIrcText
import io.github.trevarj.motd.ui.components.EffectModalBottomSheet
import io.github.trevarj.motd.ui.components.mircFormattedText
import io.github.trevarj.motd.ui.theme.LocalMotdSemanticColors
import io.github.trevarj.motd.ui.theme.readableTextButtonColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal data class ScheduledConfirmation(
    override val message: String,
    override val actionLabel: String,
) : SnackbarVisuals {
    override val duration = SnackbarDuration.Long
    override val withDismissAction = false
}

@Composable
internal fun ScheduledConfirmationSnackbar(data: SnackbarData) {
    Snackbar(
        modifier = Modifier.padding(12.dp),
        action = {
            TextButton(
                onClick = data::performAction,
                modifier = Modifier.testTag("scheduled_view"),
                colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
            ) { Text(data.visuals.actionLabel.orEmpty()) }
        },
    ) { Text(data.visuals.message) }
}

private enum class SchedulePicker { Date, Time }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleMessageSheet(
    target: String,
    initialText: String,
    onDismiss: () -> Unit,
    onSave: suspend (String, ScheduledTiming) -> Int?,
    onSaved: () -> Unit,
    editing: ScheduledMessageEntity? = null,
    onOpenList: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val zoneId by rememberSaveable { mutableStateOf(editing?.zoneId ?: ZoneId.systemDefault().id) }
    val zone = remember(zoneId) { ZoneId.of(zoneId) }
    val initial =
        remember(zone, editing) {
            editing?.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone) }
                ?: ZonedDateTime.now(zone).plusHours(1)
        }
    var timed by rememberSaveable { mutableStateOf(editing?.dueAt != null) }
    var epochDay by rememberSaveable { mutableLongStateOf(initial.toLocalDate().toEpochDay()) }
    var minuteOfDay by rememberSaveable { mutableIntStateOf(initial.hour * 60 + initial.minute) }
    var text by rememberSaveable { mutableStateOf(initialText) }
    var duplicateAcknowledged by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf<SchedulePicker?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val duplicate = editing?.state == ScheduledMessageState.UNCERTAIN
    val day = LocalDate.ofEpochDay(epochDay)
    val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
    val localDateTime = day.atTime(time)
    val offsets = zone.rules.getValidOffsets(localDateTime)
    val offset = offsets.singleOrNull()
    val dueAt = offset?.let { localDateTime.toInstant(it).toEpochMilli() }
    val dateError =
        when {
            offsets.isEmpty() -> R.string.scheduled_time_gap
            offset == null -> R.string.scheduled_time_overlap
            dueAt == null || dueAt <= System.currentTimeMillis() -> R.string.scheduled_time_past
            else -> null
        }
    val textError =
        when {
            plainIrcText(text).isBlank() -> R.string.scheduled_text_required
            !isSchedulableMessage(text) -> R.string.scheduled_text_unsupported
            else -> null
        }
    when (picker) {
        SchedulePicker.Date -> {
            DisposableEffect(Unit) {
                val dialog =
                    DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth ->
                            epochDay = LocalDate.of(year, month + 1, dayOfMonth).toEpochDay()
                            picker = null
                            error = null
                        },
                        day.year,
                        day.monthValue - 1,
                        day.dayOfMonth,
                    )
                dialog.setOnDismissListener { picker = null }
                dialog.show()
                onDispose {
                    dialog.setOnDismissListener(null)
                    dialog.dismiss()
                }
            }
        }

        SchedulePicker.Time -> {
            ScheduledTimeDialog(time, onDismiss = { picker = null }, onConfirm = { hour, minute ->
                minuteOfDay = hour * 60 + minute
                picker = null
                error = null
            })
        }

        null -> {
            Unit
        }
    }
    EffectModalBottomSheet(
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("scheduled_sheet"),
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = !saving,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(if (editing == null) R.string.scheduled_open else R.string.scheduled_edit), style = MaterialTheme.typography.titleLarge)
            Text(target, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (editing == null) {
                Text(mircFormattedText(text), maxLines = 4, overflow = TextOverflow.Ellipsis)
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().testTag("scheduled_edit_text"),
                    label = { Text(stringResource(R.string.scheduled_text_label)) },
                    minLines = 2,
                    maxLines = 5,
                    visualTransformation = VisibleFormattingControls,
                )
                if (plainIrcText(text) != text) {
                    Text(stringResource(R.string.scheduled_formatting_help), style = MaterialTheme.typography.bodySmall)
                }
            }
            Column(Modifier.selectableGroup()) {
                ScheduleChoice(R.string.scheduled_when_connected, "scheduled_when_connected", !timed, !saving) {
                    timed = false
                    error = null
                }
                ScheduleChoice(R.string.scheduled_at_time, "scheduled_at_time", timed, !saving) {
                    timed = true
                    error = null
                }
            }
            if (timed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { picker = SchedulePicker.Date },
                        enabled = !saving,
                        modifier = Modifier.weight(1f).testTag("scheduled_date"),
                    ) { Text(stringResource(R.string.scheduled_date, day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)))) }
                    OutlinedButton(
                        onClick = { picker = SchedulePicker.Time },
                        enabled = !saving,
                        modifier = Modifier.weight(1f).testTag("scheduled_time"),
                    ) { Text(stringResource(R.string.scheduled_time, time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)))) }
                }
                Text(stringResource(R.string.scheduled_zone, offset?.let { zoneLabel(zone, it) } ?: zone.id))
                if (offset != null && dateError == null) {
                    Text(stringResource(R.string.scheduled_not_before, localDateTime.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)), zoneLabel(zone, offset)), modifier = Modifier.testTag("scheduled_not_before"))
                }
                Text(stringResource(R.string.scheduled_timing_help), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(R.string.scheduled_when_connected_help), style = MaterialTheme.typography.bodySmall)
            }
            if (duplicate) {
                Text(stringResource(R.string.scheduled_duplicate_warning), color = MaterialTheme.colorScheme.error)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .testTag("scheduled_acknowledge_duplicate")
                        .toggleable(
                            value = duplicateAcknowledged,
                            enabled = !saving,
                            role = Role.Checkbox,
                            onValueChange = { duplicateAcknowledged = it },
                        ).padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(duplicateAcknowledged, onCheckedChange = null)
                    Text(stringResource(R.string.scheduled_duplicate_acknowledge))
                }
            }
            (error ?: textError ?: dateError.takeIf { timed })?.let { reason ->
                Text(stringResource(reason), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Button(
                enabled = !saving && textError == null && (!timed || dateError == null) && (!duplicate || duplicateAcknowledged),
                modifier = Modifier.fillMaxWidth().testTag("scheduled_confirm"),
                onClick = {
                    if (!saving) {
                        saving = true
                        val timing = if (timed) ScheduledTiming(dueAt, zone.id) else ScheduledTiming()
                        scope.launch {
                            error = onSave(text, timing)
                            saving = false
                            if (error == null) onSaved()
                        }
                    }
                },
            ) {
                Text(
                    stringResource(
                        when {
                            duplicate -> R.string.scheduled_requeue
                            editing != null -> R.string.scheduled_save_changes
                            else -> R.string.scheduled_confirm
                        },
                    ),
                )
            }
            if (onOpenList != null) {
                TextButton(
                    onClick = onOpenList,
                    enabled = !saving,
                    modifier = Modifier.align(Alignment.CenterHorizontally).testTag("scheduled_sheet_open_list"),
                    colors = readableTextButtonColors(),
                ) { Text(stringResource(R.string.scheduled_title)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduledTimeDialog(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val state = rememberTimePickerState(initial.hour, initial.minute, DateFormat.is24HourFormat(LocalContext.current))
    val canShowDial = LocalConfiguration.current.screenHeightDp >= TimePickerDialogDefaults.MinHeightForTimePicker.value
    var typeTime by rememberSaveable { mutableStateOf(false) }
    val typed = typeTime || !canShowDial
    val mode = if (typed) TimePickerDisplayMode.Input else TimePickerDisplayMode.Picker
    TimePickerDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("scheduled_time_dialog"),
        title = { TimePickerDialogDefaults.Title(mode) },
        modeToggleButton =
            if (canShowDial) {
                {
                    TimePickerDialogDefaults.DisplayModeToggle(
                        onDisplayModeChange = { typeTime = !typeTime },
                        displayMode = mode,
                        modifier = Modifier.testTag("scheduled_time_mode"),
                    )
                }
            } else {
                null
            },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = readableTextButtonColors()) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(state.hour, state.minute) },
                colors = readableTextButtonColors(),
                modifier = Modifier.testTag("scheduled_time_ok"),
            ) { Text(stringResource(R.string.action_ok)) }
        },
    ) {
        if (typed) {
            TimeInput(state, Modifier.testTag("scheduled_time_input"))
        } else {
            TimePicker(state, Modifier.testTag("scheduled_time_dial"))
        }
    }
}

private val VisibleFormattingControls =
    VisualTransformation { text ->
        val visible =
            buildString(text.length) {
                for (c in text.text) append(if (c < ' ' && c != '\n' && c != '\r' && c != '\t') '\u2400' + c.code else c)
            }
        TransformedText(AnnotatedString(visible), OffsetMapping.Identity)
    }

@Composable
private fun ScheduleChoice(
    label: Int,
    tag: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(tag)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(stringResource(label))
    }
}

private fun zoneLabel(
    zone: ZoneId,
    offset: ZoneOffset,
): String = "${zone.id}, UTC${if (offset == ZoneOffset.UTC) "+00:00" else offset.id}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduledMessagesScreen(
    bufferId: Long?,
    onBack: () -> Unit,
    viewModel: ScheduledMessagesViewModel = hiltViewModel(),
) {
    val all by viewModel.messages.collectAsStateWithLifecycle(initialValue = null)
    val messages = remember(all, bufferId) { all?.let { if (bufferId == null) it else it.filter { message -> message.bufferId == bufferId } } }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ScheduledMessageEntity?>(null) }
    var removing by remember { mutableStateOf<ScheduledMessageEntity?>(null) }
    var cancelling by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val now by produceState(System.currentTimeMillis(), messages) {
        val times = messages.orEmpty().mapNotNull { if (it.state == ScheduledMessageState.QUEUED) it.dueAt else null }
        while (true) {
            value = System.currentTimeMillis()
            val next = times.asSequence().filter { it > value }.minOrNull() ?: break
            delay(next - value)
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("scheduled_list"),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                title = {
                    Column {
                        Text(stringResource(R.string.scheduled_title))
                        if (bufferId != null) {
                            Text(stringResource(R.string.scheduled_chat_filter), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (messages?.isEmpty() == true) {
                item { Text(stringResource(R.string.scheduled_empty), modifier = Modifier.padding(24.dp)) }
            }
            error?.let { reason ->
                item { Text(stringResource(reason), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
            }
            items(messages.orEmpty(), key = { it.id }) { message ->
                ScheduledMessageRow(message, now, {
                    editing = message
                    error = null
                }, {
                    removing = message
                    error = null
                })
            }
        }
    }
    editing?.let { message ->
        ScheduleMessageSheet(
            target = stringResource(R.string.scheduled_destination, message.target, message.networkName),
            initialText = message.text,
            editing = message,
            onDismiss = { editing = null },
            onSave = { text, timing -> viewModel.revise(message, text, timing) },
            onSaved = { editing = null },
        )
    }
    removing?.let { message ->
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("scheduled_cancel_dialog"),
            onDismissRequest = { if (!cancelling) removing = null },
            title = { Text(stringResource(R.string.scheduled_cancel_message)) },
            text = { Text(stringResource(if (message.state == ScheduledMessageState.UNCERTAIN) R.string.scheduled_cancel_uncertain else R.string.scheduled_cancel_prompt)) },
            confirmButton = {
                TextButton(
                    enabled = !cancelling,
                    modifier = Modifier.testTag("scheduled_cancel_confirm"),
                    onClick = {
                        if (!cancelling) {
                            cancelling = true
                            scope.launch {
                                error = viewModel.remove(message)
                                cancelling = false
                                removing = null
                            }
                        }
                    },
                ) { Text(stringResource(R.string.scheduled_cancel_message)) }
            },
            dismissButton = {
                TextButton(enabled = !cancelling, onClick = { removing = null }) { Text(stringResource(R.string.scheduled_keep)) }
            },
        )
    }
}

@Composable
private fun ScheduledMessageRow(
    message: ScheduledMessageEntity,
    now: Long,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val semantic = LocalMotdSemanticColors.current
    val locale = LocalConfiguration.current.locales[0]
    val dueAt = message.dueAt
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("scheduled_message_${message.id}"),
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(message.target, style = MaterialTheme.typography.titleMedium)
                        Text(message.networkName, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    if (message.state != ScheduledMessageState.SENDING) {
                        IconButton(onClick = onEdit, modifier = Modifier.testTag("scheduled_edit_${message.id}")) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.scheduled_edit))
                        }
                        IconButton(onClick = onCancel, modifier = Modifier.testTag("scheduled_cancel_${message.id}")) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.scheduled_cancel_message))
                        }
                    }
                }
                Text(mircFormattedText(message.text), maxLines = 5, overflow = TextOverflow.Ellipsis)
                if (dueAt != null) {
                    val at = Instant.ofEpochMilli(dueAt).atZone(message.zoneId?.let(ZoneId::of) ?: ZoneOffset.UTC)
                    val atLabel = at.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))
                    Text(
                        stringResource(R.string.scheduled_time_display, atLabel, zoneLabel(at.zone, at.offset)),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            when (message.state) {
                ScheduledMessageState.QUEUED -> {
                    if (dueAt != null && dueAt > now) {
                        ScheduledStatusBand(R.string.scheduled_status_queued, Icons.Outlined.DateRange, scheme.secondaryContainer, scheme.onSecondaryContainer)
                    } else {
                        ScheduledStatusBand(
                            if (dueAt == null) R.string.scheduled_status_waiting else R.string.scheduled_status_due,
                            Icons.Outlined.CloudOff,
                            scheme.surfaceContainer,
                            scheme.onSurfaceVariant,
                        )
                    }
                }

                ScheduledMessageState.SENDING -> {
                    ScheduledStatusBand(R.string.scheduled_status_sending, Icons.Outlined.Sync, scheme.primaryContainer, scheme.onPrimaryContainer)
                }

                ScheduledMessageState.OVERDUE -> {
                    ScheduledStatusBand(R.string.scheduled_status_overdue, Icons.Outlined.Warning, semantic.warningContainer, semantic.onWarningContainer)
                }

                ScheduledMessageState.UNAVAILABLE -> {
                    ScheduledStatusBand(R.string.scheduled_status_unavailable, Icons.Outlined.Close, scheme.errorContainer, scheme.onErrorContainer)
                }

                ScheduledMessageState.UNCERTAIN -> {
                    ScheduledStatusBand(R.string.scheduled_status_uncertain, Icons.Outlined.Info, semantic.warningContainer, semantic.onWarningContainer)
                }
            }
        }
    }
}

@Composable
private fun ScheduledStatusBand(
    label: Int,
    icon: ImageVector,
    background: Color,
    ink: Color,
) {
    Surface(color = background, contentColor = ink, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        }
    }
}
