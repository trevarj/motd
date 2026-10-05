package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.HistorySyncStatus
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
            headlineIssue != null -> issueTitle(headlineIssue)
            connecting != null -> stringResource(if (connecting.connection == IrcClientState.Registering) R.string.network_activity_registering_to else R.string.network_activity_connecting_to, connecting.name)
            sync is ChatListSyncChrome.Waiting -> stringResource(R.string.network_activity_waiting)
            sync is ChatListSyncChrome.Syncing -> stringResource(if (sync.backfill) R.string.network_activity_backfilling else R.string.network_activity_syncing)
            else -> return
        }
    val inspect = stringResource(R.string.network_activity_open)
    val connected = activity.networks.count { it.connection is IrcClientState.Ready }
    Surface(
        color = if (headlineIssue != null && headlineIssue.severity > 1) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("chatlist_status_banner")
                .clickable(role = Role.Button, onClickLabel = inspect, onClick = onInspect)
                .semantics { contentDescription = inspect },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // A separate merge boundary keeps progress/count changes out of the live button.
                Text(headline, modifier = Modifier.weight(1f).testTag("chatlist_status_label").semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (issues.isNotEmpty()) Text(pluralStringResource(R.plurals.network_activity_issue_count, issues.size, issues.size), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("chatlist_status_issue_count"), maxLines = 1, softWrap = false)
                IconButton(onClick = onHide, modifier = Modifier.size(24.dp).testTag("chatlist_status_hide")) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.network_activity_hide_banner))
                }
            }
            val supporting =
                buildList {
                    if (activity.networks.size > 1) add(stringResource(R.string.network_activity_connected_count, connected, activity.networks.size))
                    if (headlineIssue != null && connecting != null) add(stringResource(R.string.network_activity_connecting_to, connecting.name))
                    if (sync is ChatListSyncChrome.Waiting && (headlineIssue != null || connecting != null)) add(stringResource(R.string.network_activity_queued))
                    if (sync is ChatListSyncChrome.Syncing && (headlineIssue != null || connecting != null)) add(stringResource(if (sync.backfill) R.string.network_activity_backfilling else R.string.network_activity_syncing))
                }
            if (supporting.isNotEmpty() || sync is ChatListSyncChrome.Syncing) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (supporting.isNotEmpty()) {
                        Text(supporting.joinToString(" · "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).testTag("chatlist_status_summary"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (sync is ChatListSyncChrome.Syncing) {
                        Text(stringResource(R.string.network_activity_progress_count, sync.done, sync.total), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("chatlist_status_count"), maxLines = 1, softWrap = false)
                    }
                }
            }
            if (sync is ChatListSyncChrome.Syncing) {
                LinearProgressIndicator(progress = { if (sync.total > 0) (sync.done.toFloat() / sync.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().testTag("chatlist_status_progress"))
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
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = Modifier.testTag("network_activity_sheet")) {
        SheetSystemBars()
        NetworkActivitySheetContent(activity, onDismiss, onIssueAction, onConnect, onSettings, onServerMessages)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun NetworkActivitySheetContent(
    activity: NetworkActivityState,
    onDismiss: () -> Unit,
    onIssueAction: (NetworkActivityIssue, NetworkActivityAction) -> Unit,
    onConnect: (Long) -> Unit,
    onSettings: (Long) -> Unit,
    onServerMessages: (Long) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.network_activity_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("network_activity_close")) { Text(stringResource(R.string.network_activity_close)) }
    }
    LazyColumn(Modifier.fillMaxWidth().testTag("network_activity_list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ActivityHeading(stringResource(R.string.network_activity_current)) }
        items(activity.networks, key = { "network_${it.id}" }) { network ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("network_activity_network_${network.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(network.name, style = MaterialTheme.typography.titleSmall)
                Text(connectionLabel(network.connection), style = MaterialTheme.typography.bodyMedium)
                if (network.certificatePending) Text(stringResource(R.string.network_activity_certificate_pending), style = MaterialTheme.typography.bodySmall)
                if (network.history.isEmpty()) Text(stringResource(R.string.network_activity_no_history_status), style = MaterialTheme.typography.bodySmall)
                network.history.forEach { chat -> Text(stringResource(R.string.network_activity_chat_status, chat.name, historyLabel(chat.status)), style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (network.connection !is IrcClientState.Ready && network.connection != IrcClientState.Connecting && network.connection != IrcClientState.Registering) {
                        ActivityAction(stringResource(R.string.network_activity_connect), stringResource(R.string.network_activity_connect_target, network.name), "network_activity_network_${network.id}_connect", !network.certificatePending) { onConnect(network.id) }
                    }
                    ActivityAction(stringResource(R.string.network_activity_settings), stringResource(R.string.network_activity_settings_target, network.name), "network_activity_network_${network.id}_settings") { onSettings(network.id) }
                    ActivityAction(stringResource(R.string.network_activity_server_messages), stringResource(R.string.network_activity_server_target, network.name), "network_activity_network_${network.id}_server") { onServerMessages(network.id) }
                }
            }
        }
        item { ActivityHeading(stringResource(R.string.network_activity_active, activity.unacknowledgedCount)) }
        if (activity.active.isEmpty()) item { Text(stringResource(R.string.network_activity_no_issues), modifier = Modifier.padding(horizontal = 16.dp)) }
        items(activity.active.sortedWith(compareByDescending<NetworkActivityIssue> { it.severity }.thenBy { it.episodeId }), key = { "issue_${it.episodeId}" }) { issue ->
            ActivityIssue(issue, activity.networks.firstOrNull { it.id == issue.networkId }, onIssueAction)
        }
        item { ActivityHeading(stringResource(R.string.network_activity_recent)) }
        if (activity.recent.isEmpty()) item { Text(stringResource(R.string.network_activity_no_recent), modifier = Modifier.padding(horizontal = 16.dp)) }
        items(activity.recent, key = { "recent_${it.episodeId}" }) { issue -> ActivityIssue(issue, activity.networks.firstOrNull { it.id == issue.networkId }, onIssueAction) }
        item { Text(stringResource(R.string.network_activity_session_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
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
        SelectionContainer { Text(issue.reason, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("${tag}_reason")) }
        val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
        Text(stringResource(R.string.network_activity_times, dateFormat.format(Date(issue.firstSeen)), dateFormat.format(Date(issue.lastSeen)), issue.occurrences), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("${tag}_times"))
        when {
            issue.disposition != null -> Text(dispositionLabel(issue.disposition), style = MaterialTheme.typography.labelMedium)
            issue.acknowledged -> Text(stringResource(R.string.network_activity_acknowledged_active), style = MaterialTheme.typography.labelMedium)
            issue.retrying -> Text(stringResource(R.string.network_activity_retrying), style = MaterialTheme.typography.labelMedium)
            !issue.settled -> Text(stringResource(R.string.network_activity_unresolved), style = MaterialTheme.typography.labelMedium)
        }
        if (!issue.targetAvailable && issue.disposition != NetworkActivityDisposition.REMOVED) Text(stringResource(R.string.network_activity_removed), style = MaterialTheme.typography.labelMedium)
        if (active || issue.targetAvailable) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (issue.bufferId == null) {
                    if (active) ActivityAction(stringResource(R.string.network_activity_connect), stringResource(R.string.network_activity_connect_target, issue.networkName), "${tag}_connect", network != null && !network.certificatePending && !issue.retrying) { onAction(issue, NetworkActivityAction.CONNECT) }
                    ActivityAction(stringResource(R.string.network_activity_settings), stringResource(R.string.network_activity_settings_target, issue.networkName), "${tag}_settings", network != null) { onAction(issue, NetworkActivityAction.SETTINGS) }
                    ActivityAction(stringResource(R.string.network_activity_server_messages), stringResource(R.string.network_activity_server_target, issue.networkName), "${tag}_server", network != null) { onAction(issue, NetworkActivityAction.SERVER_MESSAGES) }
                } else {
                    val target = stringResource(R.string.network_activity_chat_target, issue.chatName.orEmpty(), issue.networkName)
                    ActivityAction(stringResource(R.string.network_activity_open_chat), stringResource(R.string.network_activity_open_chat_target, target), "${tag}_open", network != null) { onAction(issue, NetworkActivityAction.OPEN_CHAT) }
                    if (active) ActivityAction(stringResource(R.string.network_activity_retry_history), stringResource(R.string.network_activity_retry_target, target), "${tag}_retry", network?.connection is IrcClientState.Ready && issue.settled) { onAction(issue, NetworkActivityAction.RETRY_HISTORY) }
                }
                val acknowledgementTarget = if (issue.chatName == null) issue.networkName else stringResource(R.string.network_activity_chat_target, issue.chatName, issue.networkName)
                if (active && !issue.acknowledged) ActivityAction(stringResource(R.string.network_activity_acknowledge), stringResource(R.string.network_activity_acknowledge_target, acknowledgementTarget), "${tag}_acknowledge", issue.settled) { onAction(issue, NetworkActivityAction.ACKNOWLEDGE) }
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
private fun issueTitle(issue: NetworkActivityIssue): String {
    // Only the headline is shortened; the ledger and selectable inspector retain the exact cause.
    val firstLine = issue.reason.substringBefore('\n')
    val summary = if (firstLine.length > 160) firstLine.take(157) + "…" else firstLine
    return if (issue.chatName == null) stringResource(R.string.network_activity_network_reason, issue.networkName, summary) else stringResource(R.string.network_activity_chat_reason, issue.chatName, issue.networkName, summary)
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
private fun historyLabel(status: HistorySyncStatus): String =
    stringResource(
        when (status) {
            HistorySyncStatus.Queued -> R.string.network_activity_queued
            HistorySyncStatus.AwaitingConnection -> R.string.network_activity_waiting
            HistorySyncStatus.Syncing -> R.string.network_activity_syncing
            HistorySyncStatus.Unavailable -> R.string.network_activity_unavailable
            is HistorySyncStatus.Failed -> R.string.network_activity_history_failed
            is HistorySyncStatus.Partial -> R.string.network_activity_history_partial
            HistorySyncStatus.Idle -> R.string.network_activity_no_history_status
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
        },
    )
