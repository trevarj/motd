package io.github.trevarj.motd.ui.channelinfo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.theme.MotdTheme

/** Stateful entry: wires the ViewModel and drives navigation/leave. */
@Composable
fun ChannelInfoScreen(
    bufferId: Long,
    onBack: () -> Unit = {},
    onOpenBuffer: (Long) -> Unit = {},
    viewModel: ChannelInfoViewModel = hiltViewModel(),
) {
    LaunchedEffect(bufferId) { viewModel.init(bufferId) }
    val state by viewModel.state.collectAsState()

    ChannelInfoContent(
        state = state,
        onBack = onBack,
        onSetPinned = viewModel::setPinned,
        onSetMuted = viewModel::setMuted,
        onLeave = { viewModel.part(onBack) },
        onMessageMember = { nick -> viewModel.messageMember(nick, onOpenBuffer) },
        // Queue "$nick: " on the chat's composer draft, then pop back to the chat.
        onMentionMember = { nick -> viewModel.mentionMember(nick, onDone = onBack) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelInfoContent(
    state: ChannelInfoUiState,
    onBack: () -> Unit,
    onSetPinned: (Boolean) -> Unit,
    onSetMuted: (Boolean) -> Unit,
    onLeave: () -> Unit,
    onMessageMember: (String) -> Unit,
    onMentionMember: (String) -> Unit,
) {
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var sheetMember by remember { mutableStateOf<String?>(null) }
    val buffer = state.buffer

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.channelinfo_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.onboarding_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item(key = "header") {
                ChannelHeader(buffer = buffer, memberCount = state.memberCount)
            }
            item(key = "actions") {
                ActionsRow(
                    buffer = buffer,
                    onSetPinned = onSetPinned,
                    onSetMuted = onSetMuted,
                    onLeave = { showLeaveConfirm = true },
                )
            }
            state.sections.forEach { section ->
                item(key = "sec-${section.prefix ?: "regular"}") {
                    Text(
                        text = section.prefix?.let { "$it" } ?: stringResource(R.string.channelinfo_section_regular),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(section.members, key = { "${section.prefix}-${it.nick}" }) { member ->
                    MemberRow(member = member, onClick = { sheetMember = member.nick })
                }
            }
        }
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text(stringResource(R.string.channelinfo_leave_confirm_title)) },
            text = { Text(stringResource(R.string.channelinfo_leave_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { showLeaveConfirm = false; onLeave() }) {
                    Text(stringResource(R.string.channelinfo_leave))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    sheetMember?.let { nick ->
        ModalBottomSheet(onDismissRequest = { sheetMember = null }) {
            ListItem(
                headlineContent = { Text(nick) },
                leadingContent = { Avatar(name = nick, size = 36.dp) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.channelinfo_member_message)) },
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.Message, contentDescription = null) },
                modifier = Modifier.clickable { onMessageMember(nick); sheetMember = null },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.channelinfo_member_mention)) },
                leadingContent = { Icon(Icons.Outlined.AlternateEmail, contentDescription = null) },
                modifier = Modifier.clickable { onMentionMember(nick); sheetMember = null },
            )
        }
    }
}

@Composable
private fun ChannelHeader(buffer: BufferEntity?, memberCount: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val name = buffer?.displayName ?: ""
        Avatar(name = name, size = 88.dp, isChannel = buffer?.type == BufferType.CHANNEL)
        Text(
            text = name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp),
        )
        buffer?.topic?.takeIf { it.isNotBlank() }?.let { topic ->
            Text(
                text = topic,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Text(
            text = pluralStringResource(R.plurals.channelinfo_members, memberCount, memberCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ActionsRow(
    buffer: BufferEntity?,
    onSetPinned: (Boolean) -> Unit,
    onSetMuted: (Boolean) -> Unit,
    onLeave: () -> Unit,
) {
    val pinned = buffer?.pinned == true
    val muted = buffer?.muted == true
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ActionItem(
            icon = if (muted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
            label = stringResource(if (muted) R.string.channelinfo_unmute else R.string.channelinfo_mute),
            onClick = { onSetMuted(!muted) },
        )
        ActionItem(
            icon = Icons.Outlined.PushPin,
            label = stringResource(if (pinned) R.string.channelinfo_unpin else R.string.channelinfo_pin),
            onClick = { onSetPinned(!pinned) },
        )
        if (buffer?.type == BufferType.CHANNEL) {
            ActionItem(
                icon = Icons.AutoMirrored.Outlined.Logout,
                label = stringResource(R.string.channelinfo_leave),
                onClick = onLeave,
            )
        }
    }
}

@Composable
private fun ActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun MemberRow(member: MemberEntity, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(member.prefixes.take(1) + member.nick) },
        leadingContent = { Avatar(name = member.nick, size = 36.dp) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Preview
@Composable
private fun ChannelInfoContentPreview() {
    MotdTheme {
        ChannelInfoContent(
            state = ChannelInfoUiState(
                buffer = BufferEntity(
                    id = 1, networkId = 1, name = "#kotlin", displayName = "#kotlin",
                    type = BufferType.CHANNEL, topic = "Kotlin discussion — be nice",
                    pinned = true, muted = false,
                ),
                sections = sectionMembers(
                    listOf(
                        MemberEntity(1, "owner", "~"),
                        MemberEntity(1, "op", "@"),
                        MemberEntity(1, "voiced", "+"),
                        MemberEntity(1, "alice", ""),
                        MemberEntity(1, "bob", ""),
                    ),
                ),
                memberCount = 5,
            ),
            onBack = {}, onSetPinned = {}, onSetMuted = {}, onLeave = {},
            onMessageMember = {}, onMentionMember = {},
        )
    }
}
