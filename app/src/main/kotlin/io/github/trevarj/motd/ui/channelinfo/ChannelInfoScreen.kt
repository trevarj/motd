package io.github.trevarj.motd.ui.channelinfo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
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
import io.github.trevarj.motd.data.prefs.normalizeNick
import io.github.trevarj.motd.ui.chat.NickActionSheet
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.service.RosterLoadState

/** Stateful entry: wires the ViewModel and drives navigation/leave. */
@Composable
fun ChannelInfoScreen(
    bufferId: Long,
    onBack: () -> Unit = {},
    onOpenBuffer: (Long) -> Unit = {},
    viewModel: ChannelInfoViewModel = hiltViewModel(),
) {
    LaunchedEffect(bufferId) { viewModel.init(bufferId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nickSheet by viewModel.nickSheet.collectAsStateWithLifecycle()

    ChannelInfoContent(
        state = state,
        onBack = onBack,
        onSetPinned = viewModel::setPinned,
        onSetMuted = viewModel::setMuted,
        onLeave = { viewModel.part(onBack) },
        onMemberClick = viewModel::openNickSheet,
        onSetTopic = viewModel::setTopic,
        onRetryMembers = viewModel::retryMembers,
    )

    // Nick sheet (plans/16 §5.8): shared with the chat timeline. Moderation shown only when op.
    nickSheet?.let { sheet ->
        val norm = io.github.trevarj.motd.data.prefs.normalizeNick(sheet.nick)
        NickActionSheet(
            nick = sheet.nick,
            isSelf = false,
            isFriend = norm in state.friends,
            isFool = norm in state.fools,
            canModerate = state.canModerate,
            whois = sheet.details,
            presence = sheet.presence,
            onDismiss = viewModel::dismissNickSheet,
            onMessage = { viewModel.dismissNickSheet(); viewModel.messageMember(sheet.nick, onOpenBuffer) },
            onMention = { viewModel.dismissNickSheet(); viewModel.mentionMember(sheet.nick, onDone = onBack) },
            onToggleFriend = { viewModel.toggleFriend(sheet.nick) },
            onToggleFool = { viewModel.toggleFool(sheet.nick) },
            onOp = { grant -> viewModel.setMemberMode(sheet.nick, 'o', grant) },
            onVoice = { grant -> viewModel.setMemberMode(sheet.nick, 'v', grant) },
            onKick = { reason -> viewModel.dismissNickSheet(); viewModel.kick(sheet.nick, reason) },
            onBan = { viewModel.dismissNickSheet(); viewModel.ban(sheet.nick) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelInfoContent(
    state: ChannelInfoUiState,
    onBack: () -> Unit,
    onSetPinned: (Boolean) -> Unit,
    onSetMuted: (Boolean) -> Unit,
    onLeave: () -> Unit,
    onMemberClick: (String) -> Unit = {},
    onSetTopic: (String) -> Unit = {},
    onRetryMembers: () -> Unit = {},
) {
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showTopicEdit by remember { mutableStateOf(false) }
    // Fools section is collapsed by default; state is local to the screen (plans/13 §3.6).
    var foolsExpanded by remember { mutableStateOf(false) }
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
                ChannelHeader(
                    buffer = buffer,
                    memberCount = state.memberCount,
                    rosterState = state.rosterState,
                    hasStaleMembers = state.hasStaleMembers,
                    onRetryMembers = onRetryMembers,
                    onEditTopic = { showTopicEdit = true },
                )
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
                    MemberRow(
                        member = member,
                        networkId = buffer?.networkId,
                        isFriend = normalizeNick(member.nick) in state.friends,
                        onClick = { onMemberClick(member.nick) },
                    )
                }
            }
            if (state.foolMembers.isNotEmpty()) {
                item(key = "fools-header") {
                    FoolsSectionHeader(
                        count = state.foolMembers.size,
                        expanded = foolsExpanded,
                        onToggle = { foolsExpanded = !foolsExpanded },
                    )
                }
                if (foolsExpanded) {
                    items(state.foolMembers, key = { "fool-${it.nick}" }) { member ->
                        Box(modifier = Modifier.alpha(0.55f)) {
                            MemberRow(
                                member = member,
                                networkId = buffer?.networkId,
                                isFriend = false,
                                onClick = { onMemberClick(member.nick) },
                            )
                        }
                    }
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

    // Topic edit (plans/16 §5.8): a multiline dialog prefilled with the current topic. Always
    // offered for CHANNEL buffers; a 482 (no privileges) lands in the server buffer.
    if (showTopicEdit && buffer != null) {
        TopicEditDialog(
            initial = buffer.topic.orEmpty(),
            onDismiss = { showTopicEdit = false },
            onSave = { text -> showTopicEdit = false; onSetTopic(text) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopicEditDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.channelinfo_topic_edit_title)) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.channelinfo_topic_edit_hint)) },
                minLines = 2,
                maxLines = 6,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) {
                Text(stringResource(R.string.channelinfo_topic_edit_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun FoolsSectionHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.channelinfo_fools_section, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ChannelHeader(
    buffer: BufferEntity?,
    memberCount: Int?,
    rosterState: RosterLoadState,
    hasStaleMembers: Boolean,
    onEditTopic: () -> Unit = {},
    onRetryMembers: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val name = buffer?.displayName ?: ""
        Avatar(
            name = name,
            size = 88.dp,
            isChannel = buffer?.type == BufferType.CHANNEL,
            networkId = buffer?.networkId,
        )
        Text(
            text = name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp),
        )
        // Topic + edit affordance (CHANNEL buffers only). Shown even when the topic is blank so an
        // op can set an initial topic (plans/16 §5.8).
        if (buffer?.type == BufferType.CHANNEL) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                val topic = buffer.topic?.takeIf { it.isNotBlank() }
                if (topic != null) {
                    Text(
                        text = topic,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                IconButton(onClick = onEditTopic) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.channelinfo_topic_edit_action),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        val rosterText = when {
            memberCount != null -> pluralStringResource(R.plurals.channelinfo_members, memberCount, memberCount)
            hasStaleMembers -> stringResource(R.string.channelinfo_members_stale)
            rosterState == RosterLoadState.FAILED -> stringResource(R.string.channelinfo_members_failed)
            else -> stringResource(R.string.channelinfo_members_loading)
        }
        Text(
            text = rosterText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp).testTag("channelinfo_roster_state"),
        )
        if (rosterState == RosterLoadState.FAILED) {
            TextButton(
                onClick = onRetryMembers,
                modifier = Modifier.testTag("channelinfo_roster_retry"),
            ) {
                Text(stringResource(R.string.channelinfo_members_retry))
            }
        }
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
private fun MemberRow(member: MemberEntity, networkId: Long?, isFriend: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(member.prefixes.take(1) + member.nick) },
        leadingContent = { Avatar(name = member.nick, size = 36.dp, networkId = networkId) },
        trailingContent = if (isFriend) {
            {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
        // Per-member handle so the harness selects a specific member row.
        modifier = Modifier.testTag("channelinfo_member_${member.nick}").clickable(onClick = onClick),
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
                rosterState = RosterLoadState.LOADED,
            ),
            onBack = {}, onSetPinned = {}, onSetMuted = {}, onLeave = {},
        )
    }
}
