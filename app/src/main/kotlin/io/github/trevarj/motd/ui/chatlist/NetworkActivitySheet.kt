package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.ui.theme.MotdShapes
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import java.text.DateFormat
import java.util.Date

/** Inspectable banner with independent visibility control; errors never replace engine progress. */
@Composable
fun NetworkActivityBanner(
    activity: NetworkActivityState,
    chrome: ChatListSyncChrome,
    connectionNoticeVisible: Boolean,
    includeHistory: Boolean,
    onInspect: () -> Unit,
    onHide: () -> Unit,
) {
    val issues = activity.active.filter { !it.acknowledged && (includeHistory || it.bufferId == null) }
    val headlineIssue = issues.filter { it.bufferId != null || connectionNoticeVisible || it.fatal }.minWithOrNull(compareByDescending<NetworkActivityIssue> { it.severity }.thenBy { it.episodeId })
    val connecting = activity.networks.firstOrNull { it.connection == IrcClientState.Connecting || it.connection == IrcClientState.Registering }?.takeIf { connectionNoticeVisible }
    val sync = chrome.takeIf { includeHistory } ?: ChatListSyncChrome.Hidden
    val headline =
        when {
            headlineIssue != null -> R.string.network_activity_attention
            connecting != null -> R.string.network_activity_connecting
            sync is ChatListSyncChrome.Syncing -> R.string.network_activity_syncing
            else -> return
        }
    val inspect = stringResource(R.string.network_activity_open)
    val dismissState = rememberSwipeToDismissBoxState()
    var hideRequested by rememberSaveable { mutableStateOf(false) }
    val hide = {
        if (!hideRequested) {
            hideRequested = true
            onHide()
        }
    }
    val hideLabel = stringResource(R.string.network_activity_hide_banner)
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {},
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        onDismiss = { hide() },
    ) {
        Surface(
            shape = MotdShapes.card,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("chatlist_status_banner")
                    .clickable(role = Role.Button, onClickLabel = inspect, onClick = onInspect)
                    .semantics {
                        contentDescription = inspect
                        dismiss(hideLabel) {
                            hide()
                            true
                        }
                    },
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    when (headline) {
                        R.string.network_activity_attention -> Icons.Outlined.Warning
                        R.string.network_activity_connecting -> Icons.Outlined.Lan
                        else -> Icons.Outlined.History
                    },
                    contentDescription = null,
                    modifier = Modifier.size(24.dp).testTag("chatlist_status_glyph"),
                    tint = if (headlineIssue != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.network_activity_title),
                        modifier = Modifier.testTag("chatlist_status_title"),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Keep changing status separate from the static title, badge and progress.
                    Text(
                        stringResource(headline),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("chatlist_status_label")
                                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (sync is ChatListSyncChrome.Syncing) {
                    val progressLabel = stringResource(R.string.network_activity_history_sync_progress)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Outlined.History,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp).testTag("chatlist_status_progress_history"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(
                            // Preserve the engine contract: a pass with no known total starts at zero.
                            progress = { if (sync.total > 0) (sync.done.toFloat() / sync.total).coerceIn(0f, 1f) else 0f },
                            modifier =
                                Modifier
                                    .width(28.dp)
                                    .height(2.dp)
                                    .testTag("chatlist_status_progress")
                                    .semantics { contentDescription = progressLabel },
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                            strokeCap = StrokeCap.Round,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                        )
                    }
                }
                if (issues.isNotEmpty()) {
                    val issueCount = pluralStringResource(R.plurals.network_activity_issue_count, issues.size, issues.size)
                    Surface(
                        shape = MotdShapes.pill,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier =
                            Modifier
                                .testTag("chatlist_status_issue_count")
                                .clearAndSetSemantics { contentDescription = issueCount },
                    ) {
                        Text(
                            issues.size.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkActivitySheet(
    activity: NetworkActivityState,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onIssueAction: (NetworkActivityIssue, NetworkActivityAction) -> Unit,
    onConnect: (Long) -> Unit,
    onSettings: (Long) -> Unit,
    onServerMessages: (Long) -> Unit,
    onClearRecent: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = Modifier.testTag("network_activity_sheet")) {
        SheetSystemBars()
        NetworkActivitySheetContent(activity, onDismiss, onIssueAction, onConnect, onSettings, onServerMessages, onClearRecent)
    }
}

@Composable
internal fun NetworkActivitySheetContent(
    activity: NetworkActivityState,
    onDismiss: () -> Unit,
    onIssueAction: (NetworkActivityIssue, NetworkActivityAction) -> Unit,
    onConnect: (Long) -> Unit,
    onSettings: (Long) -> Unit,
    onServerMessages: (Long) -> Unit,
    onClearRecent: () -> Unit,
) {
    var recentExpanded by rememberSaveable { mutableStateOf(false) }
    val recentToggleLabel = stringResource(if (recentExpanded) R.string.network_activity_hide_recent else R.string.network_activity_show_recent)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.network_activity_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("network_activity_close")) { Text(stringResource(R.string.network_activity_close)) }
    }
    LazyColumn(Modifier.fillMaxWidth().testTag("network_activity_list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ActivityHeading(stringResource(R.string.network_activity_active, activity.unacknowledgedCount)) }
        if (activity.unacknowledgedCount == 0) item { Text(stringResource(R.string.network_activity_no_issues), modifier = Modifier.padding(horizontal = 16.dp)) }
        items(activity.active.filterNot { it.acknowledged }.sortedWith(compareByDescending<NetworkActivityIssue> { it.severity }.thenBy { it.episodeId }), key = { "issue_${it.episodeId}" }) { issue ->
            ActivityIssue(issue, activity.networks.firstOrNull { it.id == issue.networkId }, onIssueAction)
        }
        val acknowledged = activity.active.size - activity.unacknowledgedCount
        if (acknowledged > 0) {
            item {
                Text(pluralStringResource(R.plurals.network_activity_acknowledged_count, acknowledged, acknowledged), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp).testTag("network_activity_acknowledged_summary"))
            }
        }
        item { ActivityHeading(stringResource(R.string.network_activity_current)) }
        items(activity.networks, key = { "network_${it.id}" }) { network ->
            ActivityNetwork(network, onConnect, onSettings, onServerMessages)
        }
        if (activity.recent.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("network_activity_recent_heading"), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.network_activity_recent, activity.recent.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                    TextButton(onClick = { recentExpanded = !recentExpanded }, modifier = Modifier.heightIn(min = 48.dp).testTag("network_activity_recent_toggle").semantics { contentDescription = recentToggleLabel }) {
                        Text(stringResource(if (recentExpanded) R.string.network_activity_hide else R.string.network_activity_show))
                    }
                    ActivityAction(stringResource(R.string.network_activity_clear), stringResource(R.string.network_activity_clear_recent), "network_activity_recent_clear", onClick = onClearRecent)
                }
            }
            if (recentExpanded) {
                items(activity.recent, key = { "recent_${it.episodeId}" }) { issue -> ActivityIssue(issue, activity.networks.firstOrNull { it.id == issue.networkId }, onIssueAction) }
            }
        }
        item { Text(stringResource(R.string.network_activity_session_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
    }
}

@Composable
private fun ActivityNetwork(
    network: NetworkActivityNetwork,
    onConnect: (Long) -> Unit,
    onSettings: (Long) -> Unit,
    onServerMessages: (Long) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val connectLabel = stringResource(R.string.network_activity_connect_target, network.name)
    val settingsLabel = stringResource(R.string.network_activity_settings_target, network.name)
    val serverLabel = stringResource(R.string.network_activity_server_target, network.name)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("network_activity_network_${network.id}"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(network.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(connectionLabel(network.connection), style = MaterialTheme.typography.bodySmall)
            if (network.history.any { it.status == HistorySyncStatus.AwaitingConnection }) Text(stringResource(R.string.chatlist_sync_waiting), style = MaterialTheme.typography.bodySmall)
            if (network.history.any { it.status == HistorySyncStatus.Queued }) Text(stringResource(R.string.chatlist_sync_queued), style = MaterialTheme.typography.bodySmall)
            if (network.certificatePending) Text(stringResource(R.string.network_activity_certificate_pending), style = MaterialTheme.typography.bodySmall)
        }
        Box {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.testTag("network_activity_network_${network.id}_more")) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.network_activity_more_target, network.name))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, modifier = Modifier.testTag("network_activity_network_${network.id}_menu")) {
                if (network.connection !is IrcClientState.Ready && network.connection != IrcClientState.Connecting && network.connection != IrcClientState.Registering) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.network_activity_connect)) }, enabled = !network.certificatePending, onClick = {
                        menuExpanded = false
                        onConnect(network.id)
                    }, modifier = Modifier.testTag("network_activity_network_${network.id}_connect").semantics { contentDescription = connectLabel })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.network_activity_settings)) }, onClick = {
                    menuExpanded = false
                    onSettings(network.id)
                }, modifier = Modifier.testTag("network_activity_network_${network.id}_settings").semantics { contentDescription = settingsLabel })
                DropdownMenuItem(text = { Text(stringResource(R.string.network_activity_server_messages)) }, onClick = {
                    menuExpanded = false
                    onServerMessages(network.id)
                }, modifier = Modifier.testTag("network_activity_network_${network.id}_server").semantics { contentDescription = serverLabel })
            }
        }
    }
}

@Composable
private fun ActivityHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() })
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ActivityIssue(
    issue: NetworkActivityIssue,
    network: NetworkActivityNetwork?,
    onAction: (NetworkActivityIssue, NetworkActivityAction) -> Unit,
) {
    val active = issue.disposition == null
    var detailsExpanded by rememberSaveable(issue.episodeId) { mutableStateOf(false) }
    val tag = "network_activity_${if (active) "issue" else "recent"}_${issue.episodeId}"
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (issue.chatName == null) issue.networkName else stringResource(R.string.network_activity_chat_target, issue.chatName, issue.networkName), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(
                when (issue.kind) {
                    NetworkActivityKind.CONNECTION -> R.string.network_activity_connection_issue
                    NetworkActivityKind.HISTORY_FAILED -> R.string.network_activity_history_failed
                    NetworkActivityKind.HISTORY_PARTIAL -> R.string.network_activity_history_partial
                },
            ),
            style = MaterialTheme.typography.labelMedium,
        )
        if (detailsExpanded) {
            SelectionContainer { Text(issue.reason, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("${tag}_reason")) }
            val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
            Text(stringResource(R.string.network_activity_times, dateFormat.format(Date(issue.firstSeen)), dateFormat.format(Date(issue.lastSeen)), issue.occurrences), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("${tag}_times"))
        } else {
            Text(issue.reason, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("${tag}_reason"))
        }
        when {
            issue.disposition != null -> Text(dispositionLabel(issue.disposition), style = MaterialTheme.typography.labelMedium)
            issue.retrying -> Text(stringResource(R.string.network_activity_retrying), style = MaterialTheme.typography.labelMedium)
            !issue.settled -> Text(stringResource(R.string.network_activity_unresolved), style = MaterialTheme.typography.labelMedium)
        }
        if (!issue.targetAvailable && issue.disposition != NetworkActivityDisposition.REMOVED) Text(stringResource(R.string.network_activity_removed), style = MaterialTheme.typography.labelMedium)
        val target = if (issue.chatName == null) issue.networkName else stringResource(R.string.network_activity_chat_target, issue.chatName, issue.networkName)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ActivityAction(stringResource(if (detailsExpanded) R.string.network_activity_hide_details else R.string.network_activity_details), stringResource(if (detailsExpanded) R.string.network_activity_hide_details_target else R.string.network_activity_details_target, target), "${tag}_details") { detailsExpanded = !detailsExpanded }
            if (active || issue.targetAvailable) {
                if (issue.bufferId == null) {
                    if (active) ActivityAction(stringResource(R.string.network_activity_connect), stringResource(R.string.network_activity_connect_target, issue.networkName), "${tag}_connect", network != null && !network.certificatePending && network.connection !is IrcClientState.Ready && network.connection != IrcClientState.Connecting && network.connection != IrcClientState.Registering) { onAction(issue, NetworkActivityAction.CONNECT) }
                    ActivityAction(stringResource(R.string.network_activity_settings), stringResource(R.string.network_activity_settings_target, issue.networkName), "${tag}_settings", network != null) { onAction(issue, NetworkActivityAction.SETTINGS) }
                    ActivityAction(stringResource(R.string.network_activity_server_messages), stringResource(R.string.network_activity_server_target, issue.networkName), "${tag}_server", network != null) { onAction(issue, NetworkActivityAction.SERVER_MESSAGES) }
                } else {
                    ActivityAction(stringResource(R.string.network_activity_open_chat), stringResource(R.string.network_activity_open_chat_target, target), "${tag}_open", network != null) { onAction(issue, NetworkActivityAction.OPEN_CHAT) }
                    if (active && network?.connection is IrcClientState.Ready && issue.settled) ActivityAction(stringResource(R.string.network_activity_retry_history), stringResource(R.string.network_activity_retry_target, target), "${tag}_retry") { onAction(issue, NetworkActivityAction.RETRY_HISTORY) }
                }
                if (active && !issue.acknowledged) ActivityAction(stringResource(R.string.network_activity_acknowledge), stringResource(R.string.network_activity_acknowledge_target, target), "${tag}_acknowledge") { onAction(issue, NetworkActivityAction.ACKNOWLEDGE) }
            }
        }
    }
}

@Composable
private fun ActivityAction(
    text: String,
    description: String,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag(tag).semantics { contentDescription = description }) { Text(text) }
}

@Composable
private fun connectionLabel(state: IrcClientState?): String =
    stringResource(
        when (state) {
            is IrcClientState.Ready -> R.string.network_activity_connected
            IrcClientState.Connecting -> R.string.network_activity_connecting
            IrcClientState.Registering -> R.string.network_activity_registering
            is IrcClientState.Failed -> R.string.network_activity_connection_issue
            else -> R.string.network_activity_offline
        },
    )

@Composable
private fun dispositionLabel(disposition: NetworkActivityDisposition): String =
    stringResource(
        when (disposition) {
            NetworkActivityDisposition.CONNECTED -> R.string.network_activity_connected
            NetworkActivityDisposition.STOPPED -> R.string.network_activity_stopped
            NetworkActivityDisposition.REMOVED -> R.string.network_activity_removed
            NetworkActivityDisposition.NO_LONGER_REPORTED -> R.string.network_activity_no_longer_reported
            NetworkActivityDisposition.UNAVAILABLE -> R.string.network_activity_unavailable
            NetworkActivityDisposition.SUPERSEDED -> R.string.network_activity_superseded
        },
    )
