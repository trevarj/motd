package io.github.trevarj.motd.ui.components

import android.text.format.DateFormat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TimePickerDialogDefaults
import androidx.compose.material3.TimePickerDisplayMode
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.readableTextButtonColors
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalTimeDialog(
    initial: LocalTime,
    tagPrefix: String,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val state = rememberTimePickerState(initial.hour, initial.minute, DateFormat.is24HourFormat(LocalContext.current))
    val heightPx = LocalWindowInfo.current.containerSize.height
    val windowHeight = with(LocalDensity.current) { heightPx.toDp() }
    val canShowDial = windowHeight >= TimePickerDialogDefaults.MinHeightForTimePicker
    var typeTime by rememberSaveable { mutableStateOf(false) }
    val typed = typeTime || !canShowDial
    val mode = if (typed) TimePickerDisplayMode.Input else TimePickerDisplayMode.Picker
    TimePickerDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("${tagPrefix}_dialog"),
        title = { TimePickerDialogDefaults.Title(mode) },
        modeToggleButton =
            if (canShowDial) {
                {
                    TimePickerDialogDefaults.DisplayModeToggle(
                        onDisplayModeChange = { typeTime = !typeTime },
                        displayMode = mode,
                        modifier = Modifier.testTag("${tagPrefix}_mode"),
                    )
                }
            } else {
                null
            },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = readableTextButtonColors(),
                modifier = Modifier.testTag("${tagPrefix}_cancel"),
            ) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(state.hour, state.minute) },
                colors = readableTextButtonColors(),
                modifier = Modifier.testTag("${tagPrefix}_ok"),
            ) { Text(stringResource(R.string.action_ok)) }
        },
    ) {
        if (typed) {
            TimeInput(state, Modifier.testTag("${tagPrefix}_input"))
        } else {
            TimePicker(state, Modifier.testTag("${tagPrefix}_dial"))
        }
    }
}
