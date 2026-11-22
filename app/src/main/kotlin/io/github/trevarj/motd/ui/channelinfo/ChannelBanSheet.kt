package io.github.trevarj.motd.ui.channelinfo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.components.EffectModalBottomSheet
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import io.github.trevarj.motd.ui.theme.readableTextButtonColors
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun ChannelBanSheet(
    state: ChannelBanSheetState,
    connected: Boolean,
    canModerate: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val ready = state as? ChannelBanSheetState.Ready
    val idle = state != ChannelBanSheetState.Loading && ready?.removingMask == null
    val mayRemove = canModerate && connected && idle
    var selectedMask by remember(ready?.entries, connected) { mutableStateOf<String?>(null) }
    val status =
        when (state) {
            ChannelBanSheetState.Closed -> return
            ChannelBanSheetState.Loading -> R.string.channelinfo_ban_list_loading
            ChannelBanSheetState.Denied -> R.string.channelinfo_ban_list_denied
            ChannelBanSheetState.Incomplete -> R.string.channelinfo_ban_list_incomplete
            ChannelBanSheetState.Unavailable -> R.string.channelinfo_ban_list_unavailable
            is ChannelBanSheetState.Ready -> if (state.entries.isEmpty()) R.string.channelinfo_ban_list_empty else null
        }
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }

    EffectModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("channelinfo_ban_list_sheet"),
    ) {
        SheetSystemBars()
        Text(
            text = stringResource(R.string.channelinfo_ban_list),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = onRefresh,
                enabled = connected && idle,
                colors = readableTextButtonColors(),
                modifier = Modifier.testTag("channelinfo_ban_list_refresh"),
            ) { Text(stringResource(R.string.action_refresh)) }
            TextButton(
                onClick = onDismiss,
                colors = readableTextButtonColors(),
                modifier = Modifier.testTag("channelinfo_ban_list_close"),
            ) { Text(stringResource(R.string.action_dismiss)) }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
            if (status != null) {
                item {
                    Text(
                        text = stringResource(status),
                        modifier =
                            Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag("channelinfo_ban_list_status"),
                    )
                }
            }
            if (ready != null) {
                if (ready.removalFailed) {
                    item {
                        Text(
                            text = stringResource(R.string.channelinfo_ban_list_remove_failed),
                            color = MaterialTheme.colorScheme.error,
                            modifier =
                                Modifier
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite }
                                    .testTag("channelinfo_ban_list_remove_failed"),
                        )
                    }
                }
                items(ready.entries) { entry ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        BanMaskText(entry.mask, Modifier.testTag("channelinfo_ban_list_mask"))
                        entry.setter?.let { Text(stringResource(R.string.channelinfo_ban_list_setter, it)) }
                        entry.setAtEpochSeconds?.let { seconds ->
                            if (seconds in Long.MIN_VALUE / 1000..Long.MAX_VALUE / 1000) {
                                val date = remember(seconds, dateFormat) { dateFormat.format(Date(seconds * 1000)) }
                                Text(stringResource(R.string.channelinfo_ban_list_time, date))
                            }
                        }
                        if (ready.removingMask == entry.mask) {
                            Text(
                                text = stringResource(R.string.channelinfo_ban_list_removing),
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        if (canModerate) {
                            val removeLabel = stringResource(R.string.channelinfo_ban_list_remove_mask, entry.mask)
                            TextButton(
                                onClick = { selectedMask = entry.mask },
                                enabled = mayRemove,
                                colors = readableTextButtonColors(),
                                modifier =
                                    Modifier
                                        .semantics { contentDescription = removeLabel }
                                        .testTag("channelinfo_ban_list_remove"),
                            ) { Text(stringResource(R.string.channelinfo_ban_list_remove)) }
                        }
                    }
                }
            }
        }
    }

    selectedMask?.let { mask ->
        AlertDialog(
            onDismissRequest = { selectedMask = null },
            modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("channelinfo_ban_list_confirm_dialog"),
            title = { Text(stringResource(R.string.channelinfo_ban_list_remove_title)) },
            text = {
                BanMaskText(
                    mask,
                    Modifier.verticalScroll(rememberScrollState()).testTag("channelinfo_ban_list_confirm_mask"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedMask = null
                        onRemove(mask)
                    },
                    enabled = mayRemove,
                    colors = readableTextButtonColors(colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)),
                    modifier = Modifier.testTag("channelinfo_ban_list_confirm"),
                ) { Text(stringResource(R.string.channelinfo_ban_list_remove)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { selectedMask = null },
                    colors = readableTextButtonColors(),
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun BanMaskText(
    mask: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = mask,
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
    )
}
