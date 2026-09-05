package io.github.trevarj.motd.ui.channelinfo

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.avatar.ConversationAvatarOutcome
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.JoinedChannelRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.prefs.matchesConfiguredNick
import io.github.trevarj.motd.service.ChannelWatchDuration
import io.github.trevarj.motd.service.RosterLoadState
import io.github.trevarj.motd.ui.chat.AttachmentSheets
import io.github.trevarj.motd.ui.chat.InviteSheetTarget
import io.github.trevarj.motd.ui.chat.InviteUserSheet
import io.github.trevarj.motd.ui.chat.LagTone
import io.github.trevarj.motd.ui.chat.NickActionSheet
import io.github.trevarj.motd.ui.chat.lagTone
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.components.AvatarEditorSheet
import io.github.trevarj.motd.ui.components.ChannelWatchDialog
import io.github.trevarj.motd.ui.components.MuteBacklogUndoEffect
import io.github.trevarj.motd.ui.components.avatarsHidden
import io.github.trevarj.motd.ui.components.botDisplayName
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme

/** Stateful entry: wires the ViewModel and drives navigation/leave. */
@Composable
fun ChannelInfoScreen(
    bufferId: Long,
    onBack: () -> Unit = {},
    onOpenBuffer: (Long) -> Unit = {},
    onCreateInvite: (Long) -> Unit = {},
    viewModel: ChannelInfoViewModel = hiltViewModel(),
) {
    LaunchedEffect(bufferId) { viewModel.init(bufferId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val nickSheet by viewModel.nickSheet.collectAsStateWithLifecycle()
    val topicMutation by viewModel.topicMutation.collectAsStateWithLifecycle()
    val leaveMutation by viewModel.leaveMutation.collectAsStateWithLifecycle()
    val inviteFeedback by viewModel.inviteFeedback.collectAsStateWithLifecycle()
    val presenceStates by viewModel.presenceStates.collectAsStateWithLifecycle()
    val notifyLevel by viewModel.notifyLevel.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel, onBack) {
        viewModel.operationEvents.collect { event ->
            if (event is ChannelInfoOperationEvent.LeaveAccepted) onBack()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    MuteBacklogUndoEffect(
        suppressions = viewModel.muteBacklogSuppressions,
        hostState = snackbarHostState,
        onUndo = viewModel::undoMuteBacklogSuppression,
    )
    val resolvedHost by viewModel.resolvedHost.collectAsStateWithLifecycle()
    val resolvingHost by viewModel.resolvingHost.collectAsStateWithLifecycle()
    var avatarEditorOpen by rememberSaveable { mutableStateOf(false) }
    var avatarUploadOpen by rememberSaveable { mutableStateOf(false) }
    var inviteNick by rememberSaveable { mutableStateOf<String?>(null) }
    var inviteChannelOpen by rememberSaveable { mutableStateOf(false) }

    // Operator feedback. The ViewModel reports what happened; the wording lives here, resolved in
    // composition so it follows configuration changes. The sequence number makes two identical
    // events in a row still show two snackbars.
    var toolFeedback by remember { mutableStateOf<Pair<Long, ChannelToolEvent>?>(null) }
    LaunchedEffect(viewModel) {
        var seq = 0L
        viewModel.toolEvents.collect { toolFeedback = ++seq to it }
    }
    val toolMessage =
        when (val event = toolFeedback?.second) {
            null -> {
                null
            }

            ChannelToolEvent.NotConnected -> {
                stringResource(R.string.channelinfo_tool_not_connected)
            }

            is ChannelToolEvent.InviteRequestSent -> {
                stringResource(R.string.irc_invite_request_sent, event.nick, event.channel)
            }

            ChannelToolEvent.InviteSendFailed -> {
                stringResource(R.string.irc_invite_send_failed)
            }

            is ChannelToolEvent.Sent -> {
                when (event.summary) {
                    ChannelToolSummary.MODE -> {
                        stringResource(R.string.channelinfo_tool_sent_mode)
                    }

                    ChannelToolSummary.BAN -> {
                        stringResource(R.string.channelinfo_tool_sent_ban, event.arg.orEmpty())
                    }

                    ChannelToolSummary.UNBAN -> {
                        stringResource(R.string.channelinfo_tool_sent_unban)
                    }

                    ChannelToolSummary.EXCEPTION -> {
                        stringResource(R.string.channelinfo_tool_sent_exception)
                    }
                }
            }
        }
    LaunchedEffect(toolFeedback?.first) {
        snackbarHostState.showSnackbar(toolMessage ?: return@LaunchedEffect)
    }
    val inviteMessage =
        when (val event = inviteFeedback?.event) {
            is ChannelToolEvent.InviteRequestSent -> {
                stringResource(R.string.irc_invite_request_sent, event.nick, event.channel)
            }

            ChannelToolEvent.InviteSendFailed -> {
                stringResource(R.string.irc_invite_send_failed)
            }

            else -> {
                null
            }
        }
    LaunchedEffect(inviteFeedback?.id) {
        val pending = inviteFeedback ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(inviteMessage ?: return@LaunchedEffect)
        viewModel.acknowledgeInviteFeedback(pending.id)
    }
    LaunchedEffect(viewModel, resources) {
        viewModel.avatarEvents.collect { outcome ->
            val message =
                when (outcome) {
                    ConversationAvatarOutcome.LocalOnly -> R.string.avatar_result_local_only
                    ConversationAvatarOutcome.Shared -> R.string.avatar_result_shared
                    ConversationAvatarOutcome.RequestSent -> R.string.avatar_result_request_sent
                    ConversationAvatarOutcome.LocalReset -> R.string.avatar_result_reset
                    ConversationAvatarOutcome.SharedCleared -> R.string.avatar_result_shared_cleared
                    ConversationAvatarOutcome.Invalid -> R.string.avatar_result_invalid
                    ConversationAvatarOutcome.Failed -> R.string.avatar_result_failed
                }
            snackbarHostState.showSnackbar(resources.getString(message))
        }
    }

    ChannelInfoContent(
        state = state,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        onSetPinned = viewModel::setPinned,
        onSetMuted = viewModel::setMuted,
        onLeave = viewModel::part,
        leaveMutation = leaveMutation,
        onBeginLeave = viewModel::beginLeave,
        onMemberClick = viewModel::openNickSheet,
        onSetTopic = viewModel::setTopic,
        topicMutation = topicMutation,
        onBeginTopicEdit = viewModel::beginTopicEdit,
        onInvite = { inviteChannelOpen = true },
        onSetChannelMode = viewModel::setChannelMode,
        onFlagMode = viewModel::setFlagMode,
        onSetKey = viewModel::setKey,
        onSetLimit = viewModel::setLimit,
        onSetListMask = viewModel::setListMask,
        onBanWithMask = viewModel::banWithMask,
        resolvedHost = resolvedHost,
        hostLoading = resolvingHost,
        onNickSelected = viewModel::resolveHostFor,
        onRetryMembers = viewModel::retryMembers,
        onQueryChange = viewModel::setQuery,
        onEditAvatar = { avatarEditorOpen = true },
        onCreateInvite = { onCreateInvite(bufferId) },
        notifyLevel = notifyLevel,
        onStartWatch = viewModel::startWatch,
        onStopWatch = viewModel::stopWatch,
    )

    AvatarEditorSheet(
        open = avatarEditorOpen,
        currentModel = state.buffer?.avatarOverrideModel,
        isChannel = true,
        onDismiss = { avatarEditorOpen = false },
        onImport = {
            viewModel.importAvatar(it)
            avatarEditorOpen = false
        },
        onUpload = {
            avatarEditorOpen = false
            avatarUploadOpen = true
        },
        onUrl = {
            viewModel.setAvatarUrl(it)
            avatarEditorOpen = false
        },
        onReset = {
            viewModel.resetAvatar()
            avatarEditorOpen = false
        },
        onShare = {
            viewModel.shareAvatar()
            avatarEditorOpen = false
        },
        onClearShared = {
            viewModel.clearSharedAvatar()
            avatarEditorOpen = false
        },
    )
    if (avatarUploadOpen) {
        AttachmentSheets(
            open = avatarUploadOpen,
            currentDraft = "",
            networkId = state.buffer?.networkId,
            sojuFileHostAvailable = state.sojuFileHostAvailable,
            imageOnly = true,
            onDismiss = { avatarUploadOpen = false },
            onInsertUrl = {
                viewModel.setAvatarUrl(it)
                avatarUploadOpen = false
            },
            onReplaceDraft = {
                viewModel.setAvatarUrl(it)
                avatarUploadOpen = false
            },
        )
    }

    // Nick sheet: shared with the chat timeline. Moderation shown only when op.
    nickSheet?.let { sheet ->
        NickActionSheet(
            nick = sheet.nick,
            networkId = state.buffer?.networkId,
            isSelf = state.selfNick?.let { state.identityRules.normalize(it) == state.identityRules.normalize(sheet.nick) } == true,
            isFriend = state.identityRules.matchesConfiguredNick(sheet.nick, state.friends),
            isFool = state.identityRules.matchesConfiguredNick(sheet.nick, state.fools),
            canModerate = state.canModerate,
            whois = sheet.details,
            presence = sheet.presence,
            onDismiss = viewModel::dismissNickSheet,
            onMessage = {
                viewModel.dismissNickSheet()
                viewModel.messageMember(sheet.nick, onOpenBuffer)
            },
            onMention = {
                viewModel.dismissNickSheet()
                viewModel.mentionMember(sheet.nick, onDone = onBack)
            },
            onToggleFriend = { viewModel.toggleFriend(sheet.nick) },
            onToggleFool = { viewModel.toggleFool(sheet.nick) },
            onIgnoreNetwork = { viewModel.ignoreNickOnNetwork(sheet.nick) },
            onInviteToChannel = {
                viewModel.dismissNickSheet()
                inviteNick = sheet.nick
            },
            onOp = { grant -> viewModel.setMemberMode(sheet.nick, 'o', grant) },
            onVoice = { grant -> viewModel.setMemberMode(sheet.nick, 'v', grant) },
            onKick = { reason ->
                viewModel.dismissNickSheet()
                viewModel.kick(sheet.nick, reason)
            },
            onBan = { mask, alsoKick ->
                viewModel.dismissNickSheet()
                viewModel.banWithMask(sheet.nick, mask, alsoKick)
            },
            modeCatalog = state.modeCatalog,
            resolvedHost = resolvedHost,
            hostLoading = resolvingHost,
            onNickSelected = viewModel::resolveHostFor,
        )
    }

    val currentInviteChannel =
        state.buffer
            ?.takeIf { it.type == BufferType.CHANNEL && it.joined && it.pendingCloseAt == null }
            ?.let { room ->
                state.joinedChannels.firstOrNull { it.bufferId == room.id }
                    ?: JoinedChannelRow(room.id, room.networkId, room.displayName, room.avatarOverrideModel)
            }
    val inviteTarget =
        inviteNick?.let { nick -> InviteSheetTarget.Nick(nick, state.buffer?.id) }
            ?: currentInviteChannel?.takeIf { inviteChannelOpen }?.let(InviteSheetTarget::Channel)
    inviteTarget?.let { target ->
        InviteUserSheet(
            target = target,
            joinedChannels = state.joinedChannels,
            friends = state.friends,
            presence = presenceStates,
            memberNicks = state.memberNicks,
            selfNick = state.selfNick,
            identityRules = state.identityRules,
            connected = state.connected,
            onDismiss = {
                inviteNick = null
                inviteChannelOpen = false
            },
            onInvite = { channel, nick ->
                viewModel.invite(channel, nick) { accepted ->
                    if (accepted) {
                        inviteNick = null
                        inviteChannelOpen = false
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ChannelInfoContent(
    state: ChannelInfoUiState,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onSetPinned: (Boolean) -> Unit,
    onSetMuted: (Boolean) -> Unit,
    onLeave: () -> Unit,
    leaveMutation: LeaveMutationState = LeaveMutationState.Idle,
    onBeginLeave: () -> Unit = {},
    onMemberClick: (String) -> Unit = {},
    onSetTopic: (String) -> Unit = {},
    topicMutation: TopicMutationState = TopicMutationState.Idle,
    onBeginTopicEdit: () -> Unit = {},
    onInvite: () -> Unit = {},
    onSetChannelMode: (String, String) -> Unit = { _, _ -> },
    onFlagMode: (Char, Boolean) -> Unit = { _, _ -> },
    onSetKey: (String?) -> Unit = {},
    onSetLimit: (Int?) -> Unit = {},
    onSetListMask: (Char, String, Boolean) -> Unit = { _, _, _ -> },
    onBanWithMask: (String?, String, Boolean) -> Unit = { _, _, _ -> },
    resolvedHost: String? = null,
    hostLoading: Boolean = false,
    onNickSelected: (String?) -> Unit = {},
    onRetryMembers: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onEditAvatar: () -> Unit = {},
    onCreateInvite: () -> Unit = {},
    notifyLevel: ChannelNotifyLevel = ChannelNotifyLevel.MentionsOnly,
    onStartWatch: (ChannelWatchDuration) -> Unit = {},
    onStopWatch: () -> Unit = {},
) {
    var showNotifyPicker by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showTopicEdit by remember { mutableStateOf(false) }
    // Fools section is collapsed by default; state is local to the screen.
    var foolsExpanded by remember { mutableStateOf(false) }
    val buffer = state.buffer
    // Visible query lives in local IME state so keystrokes aren't dropped and the cursor is
    // preserved; the ViewModel query drives the filter only. Seeded once from incoming state.
    var queryText by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.query))
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    lagMs = state.lagMs,
                    connected = state.connected,
                    onRetryMembers = onRetryMembers,
                    onEditAvatar = onEditAvatar,
                    onEditTopic = {
                        onBeginTopicEdit()
                        showTopicEdit = true
                    },
                )
            }
            item(key = "actions") {
                ActionsRow(
                    buffer = buffer,
                    onSetPinned = onSetPinned,
                    onSetMuted = onSetMuted,
                    onLeave = {
                        onBeginLeave()
                        showLeaveConfirm = true
                    },
                    onEditAvatar = onEditAvatar,
                    onCreateInvite = onCreateInvite,
                )
            }
            if (buffer?.type == BufferType.CHANNEL) {
                item(key = "notify_level") {
                    NotifyLevelRow(level = notifyLevel, onClick = { showNotifyPicker = true })
                }
            }
            // Non-ops get no section at all rather than a wall of disabled controls; the gate
            // resolves after the roster loads, so it can appear a moment after the screen does.
            if (state.canModerate && buffer?.type == BufferType.CHANNEL) {
                item(key = "channel-tools") {
                    // The deferred gate is intentional; only the insert is softened. The rows it
                    // shoves ease via placement-only animateItem below.
                    Box(
                        modifier =
                            Modifier.animateItem(
                                fadeInSpec = MotdMotion.fadeIn,
                                placementSpec = MotdMotion.rowPlacement,
                                fadeOutSpec = MotdMotion.microFadeOut,
                            ),
                    ) {
                        ChannelControlsSection(
                            catalog = state.modeCatalog,
                            members = state.sections.flatMap { section -> section.members.map { it.nick } },
                            resolvedHost = resolvedHost,
                            hostLoading = hostLoading,
                            onNickSelected = onNickSelected,
                            onFlagMode = onFlagMode,
                            onInvite = onInvite,
                            onSetKey = onSetKey,
                            onSetLimit = onSetLimit,
                            onSetListMask = onSetListMask,
                            onBanWithMask = onBanWithMask,
                            onSetChannelMode = onSetChannelMode,
                        )
                    }
                }
            }
            item(key = "search-field") {
                OutlinedTextField(
                    value = queryText,
                    onValueChange = {
                        queryText = it
                        onQueryChange(it.text)
                    },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.channelinfo_member_search_hint)) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = {
                        if (queryText.text.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    queryText = TextFieldValue("")
                                    onQueryChange("")
                                },
                            ) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.channelinfo_member_search_clear),
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { /* in-memory filter; nothing to fetch */ }),
                    // Placement-only: eases the shove when the channel-tools item lands above,
                    // without gaining appearance fades.
                    modifier =
                        Modifier
                            .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = MotdMotion.rowPlacement)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("channelinfo_member_search_field"),
                )
            }
            val searchResults = state.searchResults
            if (searchResults != null) {
                if (searchResults.isEmpty()) {
                    item(key = "search-empty") {
                        Text(
                            text = stringResource(R.string.channelinfo_member_search_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                } else {
                    items(searchResults, key = { "search-${it.nick}" }) { member ->
                        MemberRow(
                            member = member,
                            networkId = buffer?.networkId,
                            isFriend = state.identityRules.matchesConfiguredNick(member.nick, state.friends),
                            onClick = { onMemberClick(member.nick) },
                            // Placement-only, fades explicitly nulled: per-keystroke filtering
                            // must never gain appearance animations.
                            modifier =
                                Modifier.animateItem(
                                    fadeInSpec = null,
                                    fadeOutSpec = null,
                                    placementSpec = MotdMotion.rowPlacement,
                                ),
                        )
                    }
                }
            } else {
                state.sections.forEach { section ->
                    item(key = "sec-${section.prefix ?: "regular"}") {
                        Text(
                            text = section.prefix?.let { "$it" } ?: stringResource(R.string.channelinfo_section_regular),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            // Headers move with their rows but never fade: their identity is the
                            // section, not the membership.
                            modifier =
                                Modifier
                                    .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = MotdMotion.rowPlacement)
                                    .padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(section.members, key = { "${section.prefix}-${it.nick}" }) { member ->
                        MemberRow(
                            member = member,
                            networkId = buffer?.networkId,
                            isFriend = state.identityRules.matchesConfiguredNick(member.nick, state.friends),
                            onClick = { onMemberClick(member.nick) },
                            // Keys are stable per section, so joins fade in, parts fade out, and a
                            // prefix change (key moves sections) reads as fade-out + fade-in.
                            modifier =
                                Modifier.animateItem(
                                    fadeInSpec = MotdMotion.microFadeIn,
                                    placementSpec = MotdMotion.rowPlacement,
                                    fadeOutSpec = MotdMotion.microFadeOut,
                                ),
                        )
                    }
                }
            }
            if (state.searchResults == null && state.foolMembers.isNotEmpty()) {
                item(key = "fools-header") {
                    FoolsSectionHeader(
                        count = state.foolMembers.size,
                        expanded = foolsExpanded,
                        onToggle = { foolsExpanded = !foolsExpanded },
                    )
                }
                if (foolsExpanded) {
                    items(state.foolMembers, key = { "fool-${it.nick}" }) { member ->
                        // animateItem lives on the dimming Box so the expand/collapse fade covers
                        // the whole dimmed row.
                        Box(
                            modifier =
                                Modifier
                                    .animateItem(
                                        fadeInSpec = MotdMotion.microFadeIn,
                                        placementSpec = MotdMotion.rowPlacement,
                                        fadeOutSpec = MotdMotion.microFadeOut,
                                    ).alpha(0.55f),
                        ) {
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

    if (showNotifyPicker) {
        ChannelWatchDialog(
            watchActive = notifyLevel is ChannelNotifyLevel.All,
            onStart = onStartWatch,
            onStop = onStopWatch,
            onDismiss = { showNotifyPicker = false },
            tagPrefix = "channelinfo",
        )
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = {
                if (leaveMutation !is LeaveMutationState.Submitting) showLeaveConfirm = false
            },
            // An AlertDialog is its own Compose window, so the Activity root's
            // testTagsAsResourceId does not reach it and every tag inside this dialog is invisible
            // to uiautomator. Opt the dialog window in the same way the root does.
            modifier =
                Modifier
                    .semantics { testTagsAsResourceId = true }
                    .testTag("channelinfo_leave_dialog"),
            title = { Text(stringResource(R.string.channelinfo_leave_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.channelinfo_leave_confirm_message))
                    if (leaveMutation is LeaveMutationState.Failed) {
                        Text(
                            text = stringResource(R.string.channelinfo_leave_failed),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier =
                                Modifier
                                    .padding(top = 8.dp)
                                    .testTag("channelinfo_leave_error"),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onLeave,
                    enabled = leaveMutation !is LeaveMutationState.Submitting,
                    modifier = Modifier.testTag("channelinfo_leave_confirm"),
                ) {
                    Text(stringResource(R.string.channelinfo_leave))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLeaveConfirm = false },
                    enabled = leaveMutation !is LeaveMutationState.Submitting,
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // Topic edit: a multiline dialog prefilled with the current topic. Always
    // offered for CHANNEL buffers; a 482 (no privileges) lands in the server buffer.
    if (showTopicEdit && buffer != null) {
        TopicEditDialog(
            initial = buffer.topic.orEmpty(),
            mutation = topicMutation,
            onDismiss = { showTopicEdit = false },
            onAccepted = { showTopicEdit = false },
            onSave = onSetTopic,
        )
    }
}

internal fun topicEditSaveEnabled(mutation: TopicMutationState): Boolean = mutation !is TopicMutationState.Submitting

internal fun topicEditShowsError(mutation: TopicMutationState): Boolean = mutation is TopicMutationState.Failed

internal fun topicEditAccepted(mutation: TopicMutationState): Boolean = mutation is TopicMutationState.Accepted

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun TopicEditDialog(
    initial: String,
    mutation: TopicMutationState,
    onDismiss: () -> Unit,
    onAccepted: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val submitting = !topicEditSaveEnabled(mutation)
    LaunchedEffect(mutation) {
        if (topicEditAccepted(mutation)) onAccepted()
    }
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        // Same as the leave dialog: a separate Compose window needs its own opt-in, otherwise
        // channelinfo_topic_edit_text and _save never appear as resource ids in a uiautomator dump.
        modifier =
            Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("channelinfo_topic_edit_dialog"),
        title = { Text(stringResource(R.string.channelinfo_topic_edit_title)) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.channelinfo_topic_edit_hint)) },
                    minLines = 2,
                    maxLines = 6,
                    enabled = !submitting,
                    modifier = Modifier.testTag("channelinfo_topic_edit_text"),
                )
                if (topicEditShowsError(mutation)) {
                    Text(
                        text = stringResource(R.string.channelinfo_topic_edit_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier
                                .padding(top = 8.dp)
                                .testTag("channelinfo_topic_edit_error"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text) },
                enabled = !submitting,
                modifier = Modifier.testTag("channelinfo_topic_edit_save"),
            ) {
                Text(stringResource(R.string.channelinfo_topic_edit_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun FoolsSectionHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
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
    lagMs: Long?,
    connected: Boolean,
    onEditAvatar: () -> Unit = {},
    onEditTopic: () -> Unit = {},
    onRetryMembers: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val name = buffer?.displayName ?: ""
        val hideAvatar = avatarsHidden()
        if (!hideAvatar) {
            Avatar(
                name = name,
                size = 88.dp,
                isChannel = buffer?.type == BufferType.CHANNEL,
                networkId = buffer?.networkId,
                conversationModel = buffer?.avatarOverrideModel,
                modifier = Modifier.clickable(onClick = onEditAvatar).testTag("channelinfo_avatar"),
            )
        }
        Text(
            text = name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            // The 12dp gap only exists to separate the name from the avatar above it.
            modifier = Modifier.padding(top = if (hideAvatar) 0.dp else 12.dp),
        )
        // Topic + edit affordance (CHANNEL buffers only). Shown even when the topic is blank so an
        // op can set an initial topic.
        if (buffer?.type == BufferType.CHANNEL) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                val topic = buffer.topic?.takeIf { it.isNotBlank() }
                if (topic != null) {
                    Text(
                        text =
                            io.github.trevarj.motd.ui.components.linkifiedBody(
                                text = topic,
                                linkColor = MaterialTheme.colorScheme.primary,
                                mentionsActive = false,
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false).testTag("channelinfo_topic"),
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
        val rosterText =
            when {
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
        // Subtle network latency readout (#34). Only shown once a PONG round-trip has completed on
        // a Ready connection, so an offline/loading channel info page stays uncluttered.
        val resolvedLag = lagMs?.takeIf { connected && it >= 0 }
        if (resolvedLag != null) {
            LagReadout(
                lagMs = resolvedLag,
                modifier = Modifier.padding(top = 6.dp).testTag("channelinfo_lag"),
            )
        }
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
private fun NotifyLevelRow(
    level: ChannelNotifyLevel,
    onClick: () -> Unit,
) {
    val supporting =
        when (level) {
            ChannelNotifyLevel.MentionsOnly -> {
                stringResource(R.string.channelinfo_notify_mentions)
            }

            ChannelNotifyLevel.Muted -> {
                stringResource(R.string.channelinfo_notify_muted)
            }

            is ChannelNotifyLevel.All -> {
                val minutesLeft = level.minutesLeft
                if (minutesLeft == null) {
                    stringResource(
                        if (level.overridesMute) {
                            R.string.channelinfo_notify_all_forever_overrides_mute
                        } else {
                            R.string.channelinfo_notify_all_forever
                        },
                    )
                } else {
                    stringResource(
                        if (level.overridesMute) {
                            R.string.channelinfo_notify_overrides_mute
                        } else {
                            R.string.channelinfo_notify_all
                        },
                        minutesLeft,
                    )
                }
            }
        }
    ListItem(
        headlineContent = { Text(stringResource(R.string.channelinfo_notifications)) },
        supportingContent = { Text(supporting) },
        leadingContent = {
            Icon(
                if (level is ChannelNotifyLevel.Muted) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                contentDescription = null,
            )
        },
        modifier = Modifier.testTag("channelinfo_notify_level").clickable(onClick = onClick),
    )
}

@Composable
private fun ActionsRow(
    buffer: BufferEntity?,
    onSetPinned: (Boolean) -> Unit,
    onSetMuted: (Boolean) -> Unit,
    onLeave: () -> Unit,
    onEditAvatar: () -> Unit = {},
    onCreateInvite: () -> Unit = {},
) {
    val pinned = buffer?.pinned == true
    val muted = buffer?.muted == true
    if (avatarsHidden()) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.avatar_editor_action)) },
            leadingContent = { Icon(Icons.Outlined.Edit, contentDescription = null) },
            modifier = Modifier.testTag("channelinfo_avatar_action").clickable(onClick = onEditAvatar),
        )
    }
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
                icon = Icons.Filled.QrCode2,
                label = stringResource(R.string.invite_friend),
                onClick = onCreateInvite,
            )
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
private fun MemberRow(
    member: MemberEntity,
    networkId: Long?,
    isFriend: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(member.prefixes.take(1) + botDisplayName(member.nick, member.isBot)) },
        // Dropping the whole slot (rather than an empty one) lets the nick start at the content edge.
        leadingContent =
            if (avatarsHidden()) {
                null
            } else {
                { Avatar(name = member.nick, size = 36.dp, networkId = networkId) }
            },
        trailingContent =
            if (isFriend) {
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
        modifier = modifier.testTag("channelinfo_member_${member.nick}").clickable(onClick = onClick),
    )
}

@Preview
@Composable
private fun ChannelInfoContentPreview() {
    MotdTheme {
        ChannelInfoContent(
            state =
                ChannelInfoUiState(
                    buffer =
                        BufferEntity(
                            id = 1,
                            networkId = 1,
                            name = "#kotlin",
                            displayName = "#kotlin",
                            type = BufferType.CHANNEL,
                            topic = "Kotlin discussion — be nice",
                            pinned = true,
                            muted = false,
                        ),
                    sections =
                        sectionMembers(
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
            onBack = {},
            onSetPinned = {},
            onSetMuted = {},
            onLeave = {},
            onQueryChange = {},
        )
    }
}

/**
 * Subtle latency readout for the Channel Info header (#34): a small status dot whose tone follows
 * [lagTone] alongside the millisecond value. Inline (not a pill) so it reads as supporting metadata
 * under the channel name rather than a prominent status banner.
 */
@Composable
private fun LagReadout(
    lagMs: Long,
    modifier: Modifier = Modifier,
) {
    val tone = lagTone(lagMs)
    val dotColor =
        when (tone) {
            LagTone.GOOD -> MaterialTheme.colorScheme.primary
            LagTone.DEGRADED -> MaterialTheme.colorScheme.tertiary
            LagTone.BAD -> MaterialTheme.colorScheme.error
        }
    val description = stringResource(R.string.chat_lag_content_description, lagMs)
    Row(
        modifier =
            modifier.semantics(mergeDescendants = true) {
                contentDescription = description
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(dotColor),
        )
        Text(
            text = stringResource(R.string.chat_lag_ms, lagMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
