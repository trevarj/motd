package io.github.trevarj.motd.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Trace
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.ItemSnapshotList
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.trevarj.motd.R
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.sojuFileHostAdvertised
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioCacheStatus
import io.github.trevarj.motd.audio.AudioMetadata
import io.github.trevarj.motd.audio.AudioPlaybackOrigin
import io.github.trevarj.motd.audio.AudioPlaybackRequest
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.audio.AudioWaveform
import io.github.trevarj.motd.audio.CachedAudioMetadata
import io.github.trevarj.motd.audio.VoiceSendProgress
import io.github.trevarj.motd.audio.formatAudioDuration
import io.github.trevarj.motd.avatar.ConversationAvatarOutcome
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.DccTransferEntity
import io.github.trevarj.motd.data.db.JoinedChannelRow
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.matchesConfiguredNick
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.diagnostics.AutoFollowTrace
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.canSendReactionTags
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.format.plainIrcText
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.sidecar.SidecarSecurityState
import io.github.trevarj.motd.ui.channelinfo.ModeCatalog
import io.github.trevarj.motd.ui.components.AudioMiniPlayer
import io.github.trevarj.motd.ui.components.AutocompletePanel
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.components.AvatarEditorSheet
import io.github.trevarj.motd.ui.components.Composer
import io.github.trevarj.motd.ui.components.ComposerReply
import io.github.trevarj.motd.ui.components.HistorySyncSpinner
import io.github.trevarj.motd.ui.components.WaveformScrubber
import io.github.trevarj.motd.ui.components.avatarsHidden
import io.github.trevarj.motd.ui.components.typingText
import io.github.trevarj.motd.ui.share.PendingShare
import io.github.trevarj.motd.ui.theme.ConversationTypography
import io.github.trevarj.motd.ui.theme.LocalSpacing
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdSizes
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import io.github.trevarj.motd.ui.theme.spacingFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Pause after the last keystroke before the nick-autocomplete panel becomes visible, so fast
 *  typing doesn't flash suggestions on every character. */
private const val AUTOCOMPLETE_SHOW_DEBOUNCE_MS = 250L
private const val REACTION_PREFETCH_ROWS = 12

/**
 * Quiet period the seam-prefetch signal has to survive before history is loaded across a seam.
 *
 * A fling crosses every row between its start and its end, and every one of those frames is a new
 * `visibleItemsInfo`. Reporting them all would load a seam the reader merely flew past and, with
 * several seams in the swept range, would ask for a different one on each frame. Debouncing means
 * the signal describes where the viewport SETTLED, so one gesture makes one decision at the depth it
 * actually stopped at. 300 ms is comfortably longer than the frame cadence of a decelerating fling
 * and short enough that scrolling up to a seam still loads without the reader waiting on it.
 */
private const val SEAM_PREFETCH_SETTLE_MS = 300L
private const val MAX_VISIBLE_REACTION_MSGIDS = 80
private const val MAX_UNREAD_BADGE_COUNT = 100

private data class PendingDccAccept(
    val transferId: Long,
    val allowPrivateEndpoint: Boolean,
)

/** How long (ms) the scroll-to-bottom FAB must be held to skip the mention walk and jump to newest. */
internal const val SCROLL_TO_BOTTOM_FAB_HOLD_MS = 450
private const val SCROLL_TO_BOTTOM_FAB_SETTLE_MS = 160
private const val SCROLL_TO_BOTTOM_FAB_HELD_SCALE = 0.92f

/** Continuous icon compression used while the FAB's hold ring fills. */
internal fun scrollToBottomFabIconScale(progress: Float): Float = 1f - (1f - SCROLL_TO_BOTTOM_FAB_HELD_SCALE) * progress.coerceIn(0f, 1f)

internal class ChatForegroundLifecycleGate(
    private val onResume: () -> Unit,
    private val onPause: () -> Unit,
) {
    private var resumed = false

    fun sync(isResumed: Boolean) {
        if (isResumed == resumed) return
        resumed = isResumed
        if (isResumed) onResume() else onPause()
    }

    fun onEvent(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_RESUME -> sync(true)

            Lifecycle.Event.ON_PAUSE,
            Lifecycle.Event.ON_STOP,
            Lifecycle.Event.ON_DESTROY,
            -> sync(false)

            else -> Unit
        }
    }

    fun dispose() = sync(false)
}

/** Ensures the outgoing chat surface releases the IME before navigation reveals the list. */
internal fun dismissKeyboardBeforeNavigating(
    clearFocus: () -> Unit,
    hideKeyboard: () -> Unit,
    onBack: () -> Unit,
) {
    clearFocus()
    hideKeyboard()
    onBack()
}

/** Stateful entry: wires the ViewModel, lifecycle mark-read, and navigation. */
@Composable
fun ChatScreen(
    bufferId: Long,
    appearance: AppearanceConfig,
    onBack: () -> Unit = {},
    showBack: Boolean = true,
    onOpenChannelInfo: (Long) -> Unit = {},
    onOpenSearch: (Long) -> Unit = {},
    onOpenImage: (String) -> Unit = {},
    // /msg and /query resolve-or-create a QUERY buffer via the VM, then navigate to it.
    onOpenBuffer: (Long) -> Unit = {},
    onOpenAudioOrigin: (AudioPlaybackOrigin) -> Unit = {},
    // Round 5: /list opens the channel browser. Body lands in WP-V3.
    onOpenChannelList: (Long) -> Unit = {},
    onOpenAccountSetup: (Long) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
    voiceViewModel: VoiceMessageViewModel = hiltViewModel(),
    sidecarAttachmentViewModel: SidecarAttachmentViewModel = hiltViewModel(),
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val onHeaderBack =
        remember(focusManager, keyboardController, onBack) {
            {
                dismissKeyboardBeforeNavigating(
                    clearFocus = focusManager::clearFocus,
                    hideKeyboard = { keyboardController?.hide() },
                    onBack = onBack,
                )
            }
        }
    var mentionRequest by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var avatarEditorOpen by rememberSaveable { mutableStateOf(false) }
    var avatarUploadOpen by rememberSaveable { mutableStateOf(false) }
    var inviteNick by rememberSaveable { mutableStateOf<String?>(null) }
    var inviteChannelOpen by rememberSaveable { mutableStateOf(false) }
    // A prefill aimed at this conversation while it is already open (gesture orb). It arrives as
    // text rather than as an entry to drain, and rides the same append path as a nick-sheet mention.
    LaunchedEffect(viewModel) {
        viewModel.composerPrefills.collect { mentionRequest = System.nanoTime() to it }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Collect paging off the frame-aligned AndroidUiDispatcher. Bounded-window paging swaps the
    // whole Pager when a persisted older page recedes the gap edge, which emits two PagingData
    // back-to-back (the old Pager's invalidation generation plus the new Pager's first). cachedIn
    // multicasts through a one-slot shareIn(replay = 1), so the second emission suspends until this
    // collector consumes the first — and a collector parked on AndroidUiDispatcher.Main only runs
    // with the choreographer, which can stay quiescent indefinitely on an idle screen. That wedge
    // froze automatic history backfill after one page. Main.immediate resumes with ordinary Main
    // messages instead, so generation handoff never depends on frame traffic.
    val items = viewModel.messages.collectAsLazyPagingItems(Dispatchers.Main.immediate)
    val memberNicks by viewModel.memberNicks.collectAsStateWithLifecycle()
    val knownNicks by viewModel.knownNicks.collectAsStateWithLifecycle()
    val joinedChannels by viewModel.joinedChannels.collectAsStateWithLifecycle()
    val voiceState by voiceViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sidecarAttachmentScope = rememberCoroutineScope()
    var grantedSidecarUri by remember { mutableStateOf<Uri?>(null) }
    val sidecarSecurityLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val sidecarAttachmentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            grantedSidecarUri?.let { uri -> context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            grantedSidecarUri = null
        }
    val sendProviderAttachment = { source: AttachmentSource ->
        val buffer = state.buffer
        if (buffer != null) {
            val uri =
                when (source) {
                    is AttachmentSource.Document -> {
                        source.uri
                    }

                    is AttachmentSource.Photo -> {
                        source.uri
                    }

                    is AttachmentSource.LocalFile -> {
                        FileProvider.getUriForFile(context, "${context.packageName}.camera", source.file)
                    }

                    is AttachmentSource.Text -> {
                        null
                    }
                }
            if (uri != null) {
                val fileName =
                    when (source) {
                        is AttachmentSource.Document -> source.name
                        is AttachmentSource.Photo -> source.name
                        is AttachmentSource.LocalFile -> source.name
                        is AttachmentSource.Text -> source.name
                    }
                val mimeType =
                    when (source) {
                        is AttachmentSource.Document -> source.mimeType
                        is AttachmentSource.Photo -> source.mimeType
                        is AttachmentSource.LocalFile -> source.mimeType
                        is AttachmentSource.Text -> "text/plain"
                    }
                sidecarAttachmentScope.launch {
                    sidecarAttachmentViewModel
                        .createIntent(buffer.id, mimeType, fileName, caption = null)
                        ?.apply {
                            grantedSidecarUri = uri
                            clipData = ClipData.newUri(context.contentResolver, fileName, uri)
                            data = uri
                            type = mimeType
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }?.let(sidecarAttachmentLauncher::launch)
                }
            }
        }
    }
    val manageProviderSecurity: () -> Unit = {
        state.buffer?.let { buffer ->
            sidecarAttachmentScope.launch {
                sidecarAttachmentViewModel
                    .createSecurityIntent(buffer.id)
                    ?.let(sidecarSecurityLauncher::launch)
            }
        }
    }
    val voicePermissionGate =
        remember(context, voiceViewModel) {
            VoiceRecordingPermissionGate(
                permissionGranted = {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                },
                onStart = voiceViewModel::startRecording,
                onDenied = voiceViewModel::recordingPermissionDenied,
            )
        }
    val microphonePermission =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            voicePermissionGate.onPermissionResult(granted)
        }

    fun startVoiceRecording(locked: Boolean) {
        if (voicePermissionGate.start(locked)) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Composition survives Home/recents and navigation transitions, so use the destination's
    // resumed lifecycle instead of treating "still composed" as visible.
    val lifecycleOwner = LocalLifecycleOwner.current
    var destinationResumed by remember(lifecycleOwner) { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, viewModel, voiceViewModel) {
        val gate =
            ChatForegroundLifecycleGate(
                onResume = {
                    destinationResumed = true
                    viewModel.onResume()
                },
                onPause = {
                    destinationResumed = false
                    viewModel.onPause()
                    voiceViewModel.stopForBackground()
                },
            )
        val observer = LifecycleEventObserver { _, event -> gate.onEvent(event) }
        lifecycleOwner.lifecycle.addObserver(observer)
        gate.sync(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            gate.dispose()
        }
    }

    val chipsByMsgid by viewModel.reactionChips.collectAsStateWithLifecycle()
    val identityRules by viewModel.identityRules.collectAsStateWithLifecycle()
    val reactionChipsForMessage =
        remember(chipsByMsgid) {
            { msgid: String -> chipsByMsgid[msgid].orEmpty() }
        }
    // The VM resolves the live ISUPPORT normalizer. Memoize the returned lambda so unrelated
    // header/composer state changes do not invalidate every lazy-list row through a new function
    // identity.
    val nickNormalizer = remember(identityRules) { identityRules::normalize }

    val jumpTarget by viewModel.jumpTarget.collectAsStateWithLifecycle()
    val initialTarget by viewModel.initialTarget.collectAsStateWithLifecycle()
    val entryState by viewModel.entryState.collectAsStateWithLifecycle()
    // Held while the post-catch-up correction may still move a settled bottom entry that froze no
    // divider: settled-at-bottom mark-read would otherwise consume the arriving backlog before the
    // corrected position (and its divider) lands. Bounded on the ViewModel side.
    val viewportReadHold by viewModel.viewportReadHold.collectAsStateWithLifecycle()
    // Read marker frozen on entry so the "New messages" divider doesn't flash away.
    val unreadEntrySnapshot by viewModel.unreadEntrySnapshot.collectAsStateWithLifecycle()
    // Live read marker drives the FAB unread badge so it clears as messages are read (not on exit).
    val localReadAnchor by viewModel.localReadAnchor.collectAsStateWithLifecycle()
    val rawNewestAnchor by viewModel.rawNewestAnchor.collectAsStateWithLifecycle()
    val composerDraft by viewModel.composerDraft.collectAsStateWithLifecycle()
    val outgoingFlight by viewModel.outgoingFlight.collectAsStateWithLifecycle()
    // Timeline behavioral settings collected separately from ChatState.
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hiddenFoolsRevealed by viewModel.hiddenFoolsRevealed.collectAsStateWithLifecycle()
    val contentPreviews by viewModel.contentPreviews.collectAsStateWithLifecycle()
    // The global Coil/ExoPlayer stacks fetch directly and cannot honor a per-network proxy, so
    // media rendered through them is withheld entirely on proxied networks (fail closed).
    val directMediaAllowed by viewModel.directMediaAllowed.collectAsStateWithLifecycle()
    val audioPlaybackState by viewModel.audioPlaybackState.collectAsStateWithLifecycle()
    val audioWaveforms by viewModel.audioWaveforms.collectAsStateWithLifecycle()
    val audioCacheStatuses by viewModel.audioCacheStatuses.collectAsStateWithLifecycle()
    val replyConfig by viewModel.replyConfig.collectAsStateWithLifecycle()
    val historyAvailability by viewModel.historyAvailability.collectAsStateWithLifecycle()
    val accountSetupReminder by viewModel.accountSetupReminder.collectAsStateWithLifecycle()
    // Round 5: nick sheet + replay-safe UI events.
    val nickSheet by viewModel.nickSheet.collectAsStateWithLifecycle()
    val uiEvents by viewModel.uiEvents.collectAsStateWithLifecycle()
    val historySyncStatus by viewModel.historySyncStatus.collectAsStateWithLifecycle()
    val timelineSeams by viewModel.timelineSeams.collectAsStateWithLifecycle()
    val isServerBuffer = state.buffer?.type == BufferType.SERVER
    val titleTarget = chatTitleTarget(state.buffer?.type)

    ChatContent(
        state = state,
        items = items,
        composerEnabled =
            (!isServerBuffer || state.connState is IrcClientState.Ready) && !state.parted &&
                (!state.isSidecar || state.sidecarsEnabled),
        friends = settings.friends,
        fools = settings.fools,
        foolsMode = settings.foolsMode,
        hiddenFoolsRevealed = hiddenFoolsRevealed,
        onHiddenFoolsRevealedChange = viewModel::setHiddenFoolsRevealed,
        chatWallpaper = appearance.wallpaper,
        conversationFontScalePercent = appearance.conversationFontScalePercent,
        messageSpacing = appearance.messageSpacing,
        bubbleCornerStyle = appearance.bubbleCornerStyle,
        showComposerEmoji = settings.showComposerEmoji,
        showComposerFormattingTools = settings.showComposerFormattingTools,
        visibleReplyPrefix = replyConfig.visibleChannelPrefix,
        showImages = contentPreviews.showImages && directMediaAllowed,
        showLinkPreviews = contentPreviews.showLinkPreviews,
        reactionChips = reactionChipsForMessage,
        replyPreview = viewModel::replyPreview,
        onReplyPreviewClick = viewModel::jumpToRepliedMessage,
        dccTransfer = viewModel::dccTransfer,
        onAcceptDccTransfer = viewModel::acceptDccTransfer,
        onRejectDccTransfer = viewModel::rejectDccTransfer,
        onRemoveDccTransfer = viewModel::removeDccTransfer,
        onSendDccFile = viewModel::sendDccFile,
        onSendProviderAttachment = sendProviderAttachment,
        memberNicks = memberNicks,
        knownNicks = knownNicks,
        identityRules = identityRules,
        unreadEntrySnapshot = unreadEntrySnapshot,
        readMarkerLive = localReadAnchor,
        rawNewestAnchor = rawNewestAnchor,
        onMarkRead = viewModel::markRead,
        viewportReadEnabled = destinationResumed && !viewportReadHold,
        countUnreadBelowViewport = viewModel::countUnreadBelowViewport,
        nearestUnreadMentionBelow = viewModel::nearestUnreadMentionBelow,
        onBack = onHeaderBack,
        showBack = showBack,
        // Channel titles open Channel Info; query titles describe the other user. SERVER buffers
        // have neither channel nor peer details, so their title remains inert.
        onOpenChannelInfo = { id ->
            when (titleTarget) {
                ChatTitleTarget.CHANNEL_INFO -> onOpenChannelInfo(id)
                ChatTitleTarget.NICK_DETAILS -> state.buffer?.displayName?.let(viewModel::openNickSheet)
                ChatTitleTarget.NONE -> Unit
            }
        },
        onOpenSearch = onOpenSearch,
        onOpenImage = onOpenImage,
        onManageProviderSecurity = manageProviderSecurity,
        onInviteUser = {
            viewModel.ensureMembersObserved()
            inviteChannelOpen = true
        },
        nickNormalizer = nickNormalizer,
        onSubmit = { raw -> viewModel.submit(raw, onOpenBuffer = onOpenBuffer, onOpenChannelList = onOpenChannelList) },
        onTyping = viewModel::sendTyping,
        onSetReply = viewModel::setReply,
        onReact = viewModel::react,
        onRedact = viewModel::redact,
        onRetry = viewModel::retry,
        onDelete = viewModel::deleteFailed,
        onAcceptInvite = viewModel::acceptInvite,
        onDismissInvite = viewModel::dismissInvite,
        onRejoin = viewModel::rejoinChannel,
        loadPreview = viewModel::linkPreview,
        cachedPreview = viewModel::cachedLinkPreview,
        loadAudioMetadata = viewModel::audioMetadata,
        cachedAudioMetadata = viewModel::cachedAudioMetadata,
        audioPlaybackState = audioPlaybackState,
        audioWaveforms = audioWaveforms,
        audioCacheStatuses = audioCacheStatuses,
        onAudioToggle = viewModel::toggleAudio,
        onAudioCacheInspect = viewModel::inspectAudioCache,
        onAudioSeek = viewModel::seekAudio,
        onAudioSpeed = viewModel::setAudioSpeed,
        onAudioToggleActive = viewModel::toggleActiveAudio,
        onAudioCancelLoading = viewModel::cancelAudioLoading,
        onAudioRetry = viewModel::retryActiveAudio,
        onAudioDismiss = viewModel::dismissActiveAudio,
        onOpenAudioOrigin = onOpenAudioOrigin,
        voiceState = voiceState,
        voiceEnabled = !isServerBuffer && (!state.parted),
        onVoiceHoldStart = { startVoiceRecording(locked = false) },
        onVoiceAccessibilityStart = { startVoiceRecording(locked = true) },
        onVoiceHoldStop = voiceViewModel::stopRecording,
        onVoiceHoldCancel = voiceViewModel::cancelRecording,
        onVoiceLock = voiceViewModel::lockRecording,
        onVoiceDelete = voiceViewModel::deleteStaged,
        onVoiceSend = voiceViewModel::send,
        onVoiceToggleEncryption = voiceViewModel::toggleEncryption,
        onVoiceDestinationSelected = voiceViewModel::setDestination,
        onVoiceErrorDismissed = voiceViewModel::clearError,
        onVoiceNoticeDismissed = voiceViewModel::clearNotice,
        consumePrefill = viewModel::consumePrefill,
        consumeSharedFile = viewModel::consumeSharedFile,
        consumeAttachmentRequest = viewModel::consumeAttachmentRequest,
        attachmentSheetRequests = viewModel.attachmentSheetRequests,
        composerDraft = composerDraft,
        onDraftChanged = viewModel::saveDraft,
        outgoingFlight = outgoingFlight,
        onFlightSettled = viewModel::onFlightSettled,
        mentionPrefill = mentionRequest,
        jumpTarget = jumpTarget,
        initialTarget = initialTarget,
        entryState = entryState,
        timelineSeams = timelineSeams,
        onLoadGap = viewModel::fillGap,
        onSeamPrefetchChanged = viewModel::setSeamPrefetch,
        onJumpHandled = viewModel::onJumpHandled,
        onInitialPositionHandled = viewModel::onInitialPositionHandled,
        onJumpToNewest = viewModel::jumpToNewest,
        onFocusRecentMention = viewModel::focusRecentMention,
        onInitialPositionUnresolved = viewModel::onInitialPositionUnresolved,
        onScrollPositionChanged = viewModel::saveScrollPosition,
        onTimelineInteraction = viewModel::onTimelineInteraction,
        onClearScrollPosition = viewModel::clearScrollPosition,
        onForgetScrollPosition = viewModel::forgetScrollPosition,
        onFurthestDisplayedChanged = viewModel::recordFurthestDisplayed,
        onVisibleMsgidsChanged = viewModel::setVisibleMsgids,
        onNeedMembers = viewModel::ensureMembersObserved,
        onJumpUnresolved = viewModel::onJumpUnresolved,
        onReresolveJump = viewModel::reresolveJumpOnce,
        onReresolveInitial = viewModel::reresolveInitialOnce,
        isServerBuffer = isServerBuffer,
        onSenderClick = viewModel::openNickSheet,
        uiEvent = uiEvents.firstOrNull(),
        onUiEventAcknowledged = viewModel::acknowledgeUiEvent,
        onRetryReplyJump = viewModel::retryReplyJump,
        historySyncStatus = historySyncStatus,
        onHistorySyncRetry = viewModel::retryHistorySync,
        onHistorySyncDismiss = viewModel::dismissHistorySyncStatus,
        historyAvailability = historyAvailability,
        accountSetupReminder = accountSetupReminder,
        onAccountSetup = { state.buffer?.networkId?.let(onOpenAccountSetup) },
        onDismissAccountSetup = viewModel::dismissAccountSetupReminder,
        conversationLayout = state.conversationLayout,
        onConversationLayoutSelected = viewModel::setConversationLayoutOverride,
        onPresenceModeSelected = viewModel::setPresenceModeOverride,
        diagnostics = viewModel.diagnostics,
        avatarEvents = viewModel.avatarEvents,
    )

    // Nick sheet: actions render immediately; whois fills in when it lands.
    nickSheet?.let { sheet ->
        val norm = identityRules::normalize
        val myNick = (state.connState as? IrcClientState.Ready)?.nick
        val isSelf = myNick != null && norm(sheet.nick) == norm(myNick)
        val canEditAvatar =
            state.buffer?.type == BufferType.QUERY &&
                norm(sheet.nick) == norm(state.buffer?.displayName.orEmpty())
        NickActionSheet(
            nick = sheet.nick,
            networkId = state.buffer?.networkId,
            isSelf = isSelf,
            isFriend = identityRules.matchesConfiguredNick(sheet.nick, settings.friends),
            isFool = identityRules.matchesConfiguredNick(sheet.nick, settings.fools),
            canModerate = viewModel.canModerate(),
            whois = sheet.details,
            presence =
                state.buffer?.networkId?.let { networkId ->
                    state.presence[
                        io.github.trevarj.motd.service
                            .PresenceKey(networkId, norm(sheet.nick)),
                    ]
                },
            onDismiss = viewModel::dismissNickSheet,
            onMessage = {
                viewModel.dismissNickSheet()
                viewModel.submit("/query ${sheet.nick}", onOpenBuffer)
            },
            onMention = {
                mentionRequest = System.nanoTime() to "${sheet.nick}: "
                viewModel.dismissNickSheet()
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
            showMention = state.buffer?.type == BufferType.CHANNEL,
            modeCatalog = (state.connState as? IrcClientState.Ready)?.isupport?.let(ModeCatalog::from),
            // The sheet already runs a WHOIS on open, so its host is the address to offer here.
            resolvedHost = sheet.details?.host,
            hostLoading = sheet.whois == null,
            conversationModel = state.buffer?.avatarOverrideModel,
            canEditConversationAvatar = canEditAvatar,
            onEditConversationAvatar = {
                viewModel.dismissNickSheet()
                avatarEditorOpen = true
            },
        )
    }

    val currentInviteChannel =
        state.buffer
            ?.takeIf { it.type == BufferType.CHANNEL && it.joined && it.pendingCloseAt == null }
            ?.let { room ->
                joinedChannels.firstOrNull { it.bufferId == room.id }
                    ?: JoinedChannelRow(room.id, room.networkId, room.displayName, room.avatarOverrideModel)
            }
    val inviteTarget =
        inviteNick?.let { nick ->
            InviteSheetTarget.Nick(
                nick = nick,
                excludedBufferId = state.buffer?.id?.takeIf { state.buffer?.type == BufferType.CHANNEL },
            )
        } ?: currentInviteChannel?.takeIf { inviteChannelOpen }?.let(InviteSheetTarget::Channel)
    inviteTarget?.let { target ->
        InviteUserSheet(
            target = target,
            joinedChannels = joinedChannels,
            friends = settings.friends,
            presence = state.presence,
            memberNicks = memberNicks,
            selfNick = (state.connState as? IrcClientState.Ready)?.nick,
            identityRules = identityRules,
            connected = state.connState is IrcClientState.Ready,
            onDismiss = {
                inviteNick = null
                inviteChannelOpen = false
            },
            onInvite = { channel, nick ->
                viewModel.inviteToChannel(channel, nick) { accepted ->
                    if (accepted) {
                        inviteNick = null
                        inviteChannelOpen = false
                    }
                }
            },
        )
    }

    AvatarEditorSheet(
        open = avatarEditorOpen,
        currentModel = state.buffer?.avatarOverrideModel,
        isChannel = false,
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
    )
    AttachmentSheets(
        open = avatarUploadOpen,
        currentDraft = "",
        networkId = state.buffer?.networkId,
        sojuFileHostAvailable =
            (state.connState as? IrcClientState.Ready)
                ?.isupport
                ?.let(::sojuFileHostAdvertised) == true,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContent(
    state: ChatState,
    items: LazyPagingItems<MessageEntity>,
    composerEnabled: Boolean,
    onBack: () -> Unit,
    showBack: Boolean = true,
    onOpenChannelInfo: (Long) -> Unit,
    onOpenSearch: (Long) -> Unit,
    onOpenImage: (String) -> Unit,
    onManageProviderSecurity: () -> Unit = {},
    nickNormalizer: (String) -> String,
    onSubmit: (String) -> Unit,
    onTyping: (Boolean) -> Unit,
    onSetReply: (MessageEntity?) -> Unit,
    // React to a message. Takes the whole entity so a still-pending own message (msgid == null) can
    // be queued rather than silently dropped; the VM defers the send until the msgid lands.
    onReact: (MessageEntity, String) -> Unit,
    onRetry: (MessageEntity) -> Unit,
    loadPreview: suspend (String, Long?) -> io.github.trevarj.motd.data.repo.LinkPreview?,
    cachedPreview: (String, Long?) -> io.github.trevarj.motd.data.repo.CachedLinkPreview? = { _, _ -> null },
    loadAudioMetadata: suspend (String, Long?) -> AudioMetadata? = { _, _ -> null },
    cachedAudioMetadata: (String) -> CachedAudioMetadata? = { null },
    audioPlaybackState: AudioPlaybackState = AudioPlaybackState(),
    audioWaveforms: Map<String, AudioWaveform> = emptyMap(),
    audioCacheStatuses: Map<String, AudioCacheStatus> = emptyMap(),
    onAudioToggle: (AudioPlaybackRequest) -> Unit = {},
    onAudioCacheInspect: (AudioAttachment) -> Unit = {},
    onAudioSeek: (AudioAttachment, Long) -> Unit = { _, _ -> },
    onAudioSpeed: (AudioAttachment, Float) -> Unit = { _, _ -> },
    onAudioToggleActive: () -> Unit = {},
    onAudioCancelLoading: () -> Unit = {},
    onAudioRetry: () -> Unit = {},
    onAudioDismiss: () -> Unit = {},
    onOpenAudioOrigin: (AudioPlaybackOrigin) -> Unit = {},
    voiceState: VoiceMessageUiState = VoiceMessageUiState(),
    voiceEnabled: Boolean = true,
    onVoiceHoldStart: () -> Unit = {},
    onVoiceAccessibilityStart: () -> Unit = {},
    onVoiceHoldStop: () -> Unit = {},
    onVoiceHoldCancel: () -> Unit = {},
    onVoiceLock: () -> Unit = {},
    onVoiceDelete: () -> Unit = {},
    onVoiceSend: () -> Unit = {},
    onVoiceToggleEncryption: () -> Unit = {},
    onVoiceDestinationSelected: (io.github.trevarj.motd.attachment.PasteBackendConfig?) -> Unit = {},
    onVoiceErrorDismissed: () -> Unit = {},
    onVoiceNoticeDismissed: () -> Unit = {},
    reactionChips: (String) -> List<io.github.trevarj.motd.ui.components.ReactionChip> = { emptyList() },
    replyPreview: (String) -> StateFlow<io.github.trevarj.motd.ui.components.ReplyPreviewData?> = {
        kotlinx.coroutines.flow.MutableStateFlow(null)
    },
    onReplyPreviewClick: (String) -> Unit = {},
    dccTransfer: (MessageEntity) -> StateFlow<DccTransferEntity?> = {
        kotlinx.coroutines.flow.MutableStateFlow(null)
    },
    onAcceptDccTransfer: (Long, Uri, Boolean) -> Unit = { _, _, _ -> },
    onRejectDccTransfer: (Long) -> Unit = {},
    onRemoveDccTransfer: (Long) -> Unit = {},
    onSendDccFile: (Uri) -> Unit = {},
    onSendProviderAttachment: (AttachmentSource) -> Unit = {},
    memberNicks: List<String> = emptyList(),
    knownNicks: Set<String> = emptySet(),
    identityRules: IrcIdentityRules = IrcIdentityRules(),
    friends: Set<String> = emptySet(),
    fools: Set<String> = emptySet(),
    foolsMode: FoolsMode = FoolsMode.COLLAPSE,
    hiddenFoolsRevealed: Boolean = false,
    onHiddenFoolsRevealedChange: (Boolean) -> Unit = {},
    chatWallpaper: io.github.trevarj.motd.data.prefs.WallpaperSelection =
        io.github.trevarj.motd.data.prefs
            .WallpaperSelection(),
    conversationFontScalePercent: Int = io.github.trevarj.motd.data.prefs.DEFAULT_FONT_SCALE_PERCENT,
    messageSpacing: io.github.trevarj.motd.data.prefs.MessageSpacing =
        io.github.trevarj.motd.data.prefs.MessageSpacing.DEFAULT,
    bubbleCornerStyle: io.github.trevarj.motd.data.prefs.BubbleCornerStyle =
        io.github.trevarj.motd.data.prefs.BubbleCornerStyle.ROUNDED,
    showComposerEmoji: Boolean = true,
    showComposerFormattingTools: Boolean = true,
    visibleReplyPrefix: Boolean = false,
    showImages: Boolean = true,
    showLinkPreviews: Boolean = true,
    unreadEntrySnapshot: UnreadEntrySnapshot? = null,
    // Live buffer read marker (advances with markRead); drives the FAB unread badge count.
    readMarkerLive: io.github.trevarj.motd.data.db.TimelineAnchor? = null,
    rawNewestAnchor: io.github.trevarj.motd.data.db.TimelineAnchor? = null,
    onMarkRead: (io.github.trevarj.motd.data.db.TimelineAnchor) -> Unit = {},
    viewportReadEnabled: Boolean = true,
    onDelete: (MessageEntity) -> Unit = {},
    onRedact: (MessageEntity) -> Unit = {},
    onAcceptInvite: (Long) -> Unit = {},
    onDismissInvite: (Long) -> Unit = {},
    // Re-join the current channel from the parted banner.
    onRejoin: () -> Unit = {},
    consumePrefill: () -> String? = { null },
    // Consume-once file routed here by the share picker; opens the upload confirmation sheet.
    consumeSharedFile: () -> PendingShare.File? = { null },
    // Consume-once attachment request queued (by the gesture orb) before this screen was entered.
    consumeAttachmentRequest: () -> Boolean = { false },
    // The same request aimed at this conversation while it is already open; see ChatViewModel.
    attachmentSheetRequests: Flow<Unit> = emptyFlow(),
    composerDraft: ComposerDraftState = ComposerDraftState(),
    onDraftChanged: (String) -> Unit = {},
    // The send currently travelling from the composer into the timeline, if any.
    outgoingFlight: OutgoingFlight? = null,
    onFlightSettled: (Long) -> Unit = {},
    // Immediate nick-sheet mention request. The nonce permits mentioning the same nick repeatedly.
    mentionPrefill: Pair<Long, String>? = null,
    jumpTarget: ChatPositionTarget? = null,
    initialTarget: ChatPositionTarget? = null,
    entryState: EntryPositionState = EntryPositionState.Pending,
    // Stored history gaps, rendered as in-row seams by MessageList. Independent of the active
    // window: the seam list describes the gaps, the window decides which rows are on screen.
    timelineSeams: TimelineSeamState = TimelineSeamState(),
    onLoadGap: (Long) -> Unit = {},
    onJumpHandled: (Long) -> Unit = {},
    onInitialPositionHandled: () -> Unit = {},
    onJumpToNewest: () -> Unit = {},
    onFocusRecentMention: (ChatPositionTarget) -> Unit = {},
    onInitialPositionUnresolved: () -> Unit = {},
    onScrollPositionChanged: (ChatScrollPosition) -> Unit = {},
    // The reader started a scroll themselves. Retires the ViewModel's one post-catch-up entry
    // correction: a viewport its reader is driving is no longer entry's to move.
    onTimelineInteraction: () -> Unit = {},
    onClearScrollPosition: () -> Unit = {},
    // Indeterminate snapshot at save time: drop the saved viewport WITHOUT asserting a bottom park.
    onForgetScrollPosition: () -> Unit = {},
    // Deepest row this visit put on screen. Local-only entry input; never read state, never wire.
    onFurthestDisplayedChanged: (io.github.trevarj.motd.data.db.TimelineAnchor) -> Unit = {},
    onVisibleMsgidsChanged: (List<String>) -> Unit = {},
    // Where the reader is (the newest visible row's identity), how far the older edge reached
    // (evidence only, never compared), and the seams within loading reach of that edge. The
    // ViewModel's history rule loads a seam the reader has scrolled to, so this is its whole demand
    // signal — see SEAM_PREFETCH_SETTLE_MS.
    onSeamPrefetchChanged: (io.github.trevarj.motd.data.db.TimelineAnchor?, Int, Set<Long>) -> Unit =
        { _, _, _ -> },
    onNeedMembers: () -> Unit = {},
    onJumpUnresolved: (Long) -> Unit = {},
    onReresolveJump: (Long) -> Unit = {},
    onReresolveInitial: (ChatPositionTarget) -> Unit = {},
    // Round 5: SERVER-buffer raw-send + nick sheet plumbing.
    isServerBuffer: Boolean = false,
    onSenderClick: (String) -> Unit = {},
    onInviteUser: () -> Unit = {},
    uiEvent: QueuedChatUiEvent? = null,
    onUiEventAcknowledged: (Long) -> Unit = {},
    onRetryReplyJump: (ReplyJumpRequest) -> Unit = {},
    historySyncStatus: HistorySyncStatus = HistorySyncStatus.Idle,
    // Re-runs the reconciliation pass behind the failed-sync pill; Paging's own retry runs with it.
    onHistorySyncRetry: () -> Unit = {},
    onHistorySyncDismiss: () -> Unit = {},
    historyAvailability: HistoryAvailability = HistoryAvailability.NegotiatingOrOffline,
    countUnreadBelowViewport: suspend (Int, io.github.trevarj.motd.data.db.TimelineAnchor) -> Int = { _, _ -> 0 },
    nearestUnreadMentionBelow: suspend (Int, io.github.trevarj.motd.data.db.TimelineAnchor) -> ChatPositionTarget? = { _, _ -> null },
    conversationLayout: ConversationLayoutState = ConversationLayoutState(),
    onConversationLayoutSelected: (io.github.trevarj.motd.data.prefs.LayoutDensity?) -> Unit = {},
    onPresenceModeSelected: (io.github.trevarj.motd.data.prefs.PresenceMode?) -> Unit = {},
    // Opt-in journal for the timeline's own Paging generations. Noop by default so previews and
    // hand-built call sites need nothing; the required E2E gate arms the real one for the journey.
    diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
    avatarEvents: Flow<ConversationAvatarOutcome> = emptyFlow(),
    accountSetupReminder: Boolean = false,
    onAccountSetup: () -> Unit = {},
    onDismissAccountSetup: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val autoFollow = remember { AutoFollowTracker(items.itemCount) }
    var liveEntryIds by remember(state.buffer?.id) { mutableStateOf(emptySet<Long>()) }
    val presenceMode = state.conversationPresence.effective
    val visibilityPolicy =
        remember(
            presenceMode,
            fools,
            foolsMode,
            hiddenFoolsRevealed,
            identityRules,
        ) {
            MessageVisibilityPolicy(
                MessageVisibilitySpec(
                    presenceMode = presenceMode,
                    fools = fools,
                    foolsMode = foolsMode,
                    revealHiddenFools = hiddenFoolsRevealed,
                ),
                identityRules,
            )
        }
    // Scroll-driven paging: the footer reflects the Paging APPEND state at the older end directly.
    val olderHistoryLoad = items.loadState.append
    val historyUiState =
        chatHistoryUiState(
            bufferType = state.buffer?.type,
            connectionState = state.connState,
            availability = historyAvailability,
            append = olderHistoryLoad,
            historyComplete = state.buffer?.historyComplete == true,
        )
    val unreadEntryLabel =
        unreadEntrySnapshot?.let { snapshot ->
            pluralStringResource(
                R.plurals.chat_new_messages_count,
                snapshot.loadedCount,
                if (snapshot.lowerBound) "${snapshot.loadedCount}+" else snapshot.loadedCount.toString(),
            )
        }
    val newerHistoryLoad = items.loadState.prepend
    val timelineHistoryStatus =
        timelineHistoryStatus(
            refresh = items.loadState.refresh,
            prepend = newerHistoryLoad,
            itemCount = items.itemCount,
            syncStatus = historySyncStatus,
        )
    val readyRetryGate = remember(items) { HistoryReadyRetryGate() }
    LaunchedEffect(historyAvailability, olderHistoryLoad) {
        if (readyRetryGate.update(historyAvailability, olderHistoryLoad)) {
            items.retry()
        }
    }
    val traceBufferId = state.buffer?.id
    val traceSessionId =
        remember(traceBufferId) {
            traceBufferId?.let { AutoFollowTrace.nextSessionId() }
        }
    val scope = rememberCoroutineScope()
    // Expanded fool rows: keyed by MessageEntity.id, expand-only for the session.
    // Ephemeral by design (lost on config change; an accepted tradeoff).
    var expandedFools by remember { mutableStateOf(setOf<Long>()) }
    val clipboard: Clipboard = LocalClipboard.current
    val ctx = LocalContext.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDccAccept by remember { mutableStateOf<PendingDccAccept?>(null) }
    val dccDestinationPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri ->
            val pending = pendingDccAccept
            pendingDccAccept = null
            if (uri != null && pending != null) {
                onAcceptDccTransfer(pending.transferId, uri, pending.allowPrivateEndpoint)
            }
        }
    LaunchedEffect(voiceState.notice) {
        val notice = voiceState.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice)
        onVoiceNoticeDismissed()
    }
    LaunchedEffect(avatarEvents, resources) {
        avatarEvents.collect { outcome ->
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
    // The ViewModel owns the durable value; this local state only retains cursor/selection details.
    var composerText by remember(traceBufferId) {
        mutableStateOf(TextFieldValue(""))
    }
    // Anchors for the send flight. Held in their own holder so the per-layout writes recompose the
    // overlay that reads them and nothing else on this surface.
    val flightAnchors = remember(traceBufferId) { SendFlightAnchors() }
    // Rows a flight has already delivered. Their entrance was the ghost, so they must never also
    // play the ordinary live-arrival reveal once the flight is gone.
    var flownRowIds by remember(traceBufferId) { mutableStateOf(emptySet<Long>()) }
    val selfNick = (state.connState as? IrcClientState.Ready)?.nick.orEmpty()
    // The silhouette is decided at launch, before the pending row exists: predict whether the
    // landing row opens a new group or continues our own burst, against the newest row the flight
    // is not itself standing in for (its own row may already be in the pager by now).
    val flightShowSender =
        remember(outgoingFlight?.token) {
            val flight = outgoingFlight ?: return@remember true
            if (selfNick.isEmpty()) return@remember true
            val newest =
                (0 until minOf(items.itemCount, MAX_PLACEHOLDER_PROBES))
                    .asSequence()
                    .mapNotNull { items.peek(it) }
                    .firstOrNull { !flight.matches(it) }
            predictFlightShowsSender(
                newest = newest,
                selfNick = selfNick,
                normalizedSelf = nickNormalizer(selfNick),
                nowMs = flight.launchedAtMs,
            )
        }
    // One spring per tap, carrying the bubble and opening the gap it lands in.
    val flightMotion =
        remember(outgoingFlight?.token) {
            // Reset here rather than in the effect below: effects are dispatched on the frame clock
            // and can run after the new tap's first draw, which would then read the previous flight's
            // landing rect and start the ghost mid-timeline instead of at the composer.
            flightAnchors.landingRow = null
            flightAnchors.ghostHeight = 0f
            // The field still holds its pre-clear (possibly multi-line) rect during this
            // composition; layout shrinks it afterwards, and composerShrink() measures against
            // this pinned height.
            flightAnchors.launchFieldHeight = flightAnchors.composerField?.height ?: 0f
            SendFlightMotion()
        }
    val flightProgress = remember(flightMotion) { { flightMotion.progress.value } }
    // Driven here rather than inside the overlay: the landing row's reveal must complete even if
    // the ghost never gets a composer rect to launch from, or the row would stay collapsed.
    LaunchedEffect(outgoingFlight?.token) {
        val token = outgoingFlight?.token ?: return@LaunchedEffect
        try {
            // The lift starts on the tap frame, before the pending row exists: the ghost rises to
            // its hover line above the composer while persistence runs, so a slow send stays
            // visibly in flight. The flight spring itself still waits for the landing report,
            // because the gap must open from zero on the frame the row first composes.
            val liftJob = launch { flightMotion.lift.animateTo(1f, MotdMotion.sendFlightSpring) }
            snapshotFlow { flightAnchors.landingRow }.filterNotNull().first()
            flightMotion.progress.animateTo(1f, MotdMotion.sendFlightSpring)
            liftJob.join()
        } finally {
            // Also runs when this effect dies with the screen. A flight that outlived its UI would
            // hide its row again and fly a ghost of a minutes-old message on the next visit.
            onFlightSettled(token)
        }
    }
    var attachmentSheetOpen by rememberSaveable { mutableStateOf(false) }
    var uploadCurrentDraftDirectly by rememberSaveable { mutableStateOf(false) }
    // A file handed over by the share picker: open the upload confirmation sheet for it directly.
    var sharedFile by remember(traceBufferId) { mutableStateOf<PendingShare.File?>(null) }
    LaunchedEffect(traceBufferId) {
        if (traceBufferId == null) return@LaunchedEffect
        consumeSharedFile()?.let { file ->
            sharedFile = file
            uploadCurrentDraftDirectly = false
            attachmentSheetOpen = true
        }
        if (consumeAttachmentRequest()) {
            sharedFile = null
            uploadCurrentDraftDirectly = false
            attachmentSheetOpen = true
        }
    }
    LaunchedEffect(attachmentSheetRequests) {
        attachmentSheetRequests.collect {
            sharedFile = null
            uploadCurrentDraftDirectly = false
            attachmentSheetOpen = true
        }
    }
    var longDraftPrompt by rememberSaveable { mutableStateOf(false) }
    var overflowOpen by rememberSaveable { mutableStateOf(false) }
    var conversationLayoutSheetOpen by rememberSaveable { mutableStateOf(false) }
    var presenceModeSheetOpen by rememberSaveable { mutableStateOf(false) }
    var highlightMsgid by rememberSaveable { mutableStateOf<String?>(null) }
    // Global fool expand/collapse toggle: when true every collapsed fool row in the
    // buffer renders expanded; per-row toggles still override individually via [expandedFools] and
    // [collapsedFools]. Ephemeral per composition, like expandedFools.
    var expandAllFools by remember { mutableStateOf(false) }
    // Rows the user explicitly re-collapsed while expand-all is on (so a global expand is still
    // individually reversible). Cleared whenever expand-all is toggled off.
    var collapsedFools by remember { mutableStateOf(setOf<Long>()) }

    LaunchedEffect(traceBufferId) {
        if (traceBufferId == null) return@LaunchedEffect
        withFrameNanos {
            AutoFollowTrace.record("first_frame", traceBufferId, traceSessionId) {
                "item_count=${items.itemCount}"
            }
            Trace.beginSection("motd chat first frame")
            try {
                // Instant-like marker for Perfetto/gfx correlation without spanning suspension.
            } finally {
                Trace.endSection()
            }
        }
    }

    // Entry position is resolved once after refresh. Until then do not expose a transient FAB or
    // advance read state from a default index-0 layout.
    val entryInitiallySettled = entryState is EntryPositionState.Settled
    var initialPositionSettled by remember(entryInitiallySettled) {
        mutableStateOf(entryInitiallySettled)
    }
    // The first Paging emission after entry settlement reflects data loaded for the target, not a
    // live arrival. Consume it without auto-follow so an unread target remains on screen.
    var suppressNextAutoFollow by remember { mutableStateOf(!entryInitiallySettled) }

    // Veil over the timeline while entry positioning settles, so the entry frames (the bottom of
    // the buffer, then the alignment passes) are never shown before the divider lands. One-shot per
    // composition, and the composition is per-ChatRoute: entering another buffer is a new route, so
    // nothing that happens within a visit -- a republished post-catch-up repair, a re-resolve -- can
    // re-engage it. A restored visit that already knows where it landed starts lifted.
    val latestEntryState by rememberUpdatedState(entryState)
    var entryVeilLifted by remember {
        mutableStateOf(
            shouldLiftEntryVeil(
                initialPositionSettled = entryInitiallySettled,
                entryUnresolved = entryState is EntryPositionState.Unresolved,
                timedOut = false,
            ),
        )
    }
    // The overlay outlives the lift by the length of the fade; see the veil at the list call site.
    var entryVeilCleared by remember { mutableStateOf(entryVeilLifted) }
    LaunchedEffect(Unit) {
        if (entryVeilLifted) return@LaunchedEffect
        // Bounded wait: a slow or offline entry degrades to today's visible correction rather than
        // a stuck blank pane.
        val resolved =
            withTimeoutOrNull(ENTRY_VEIL_TIMEOUT_MS) {
                snapshotFlow {
                    val entry = latestEntryState
                    // Both statements of settlement: the screen's own flag reveals a frame or two
                    // earlier, and the entry state carries it when the flag's keyed remember is
                    // rebuilt by the very transition that settles it.
                    val settled = initialPositionSettled || entry is EntryPositionState.Settled
                    settled to (entry is EntryPositionState.Unresolved)
                }.first { (settled, unresolved) ->
                    shouldLiftEntryVeil(settled, unresolved, timedOut = false)
                }
            }
        entryVeilLifted =
            shouldLiftEntryVeil(
                initialPositionSettled = resolved?.first == true,
                entryUnresolved = resolved?.second == true,
                timedOut = resolved == null,
            )
        AutoFollowTrace.record("entry_veil_lifted", traceBufferId, traceSessionId) {
            val reason =
                when {
                    resolved == null -> "timeout"
                    resolved.first -> "settled"
                    else -> "unresolved"
                }
            "reason=$reason item_count=${items.itemCount}"
        }
    }

    var prefillConsumed by remember(traceBufferId) { mutableStateOf(false) }

    // Apply hydration/accepted-send clears without re-saving the same value from the screen.
    LaunchedEffect(traceBufferId, composerDraft.hydrated, composerDraft.revision) {
        if (traceBufferId == null || !composerDraft.hydrated) return@LaunchedEffect
        if (composerText.text != composerDraft.text) {
            composerText =
                TextFieldValue(
                    composerDraft.text,
                    TextRange(composerDraft.text.length),
                )
        }
        if (!prefillConsumed) {
            prefillConsumed = true
            consumePrefill()?.let { prefill ->
                composerText = appendPrefill(composerText, prefill)
                onDraftChanged(composerText.text)
            }
        }
    }
    LaunchedEffect(mentionPrefill) {
        mentionPrefill?.second?.let {
            composerText = appendPrefill(composerText, it)
            onDraftChanged(composerText.text)
        }
    }
    val latestComposerText by rememberUpdatedState(composerText)
    val latestBufferType by rememberUpdatedState(state.buffer?.type)
    val latestVisibleReplyPrefix by rememberUpdatedState(visibleReplyPrefix)
    val timelineReply =
        remember(onSetReply, onDraftChanged) {
            { target: MessageEntity ->
                onSetReply(target)
                composerText =
                    composerTextForReply(
                        value = latestComposerText,
                        sender = target.sender,
                        bufferType = latestBufferType,
                        visibleReplyPrefix = latestVisibleReplyPrefix,
                    )
                onDraftChanged(composerText.text)
            }
        }

    val jumpNotLoaded = stringResource(R.string.chat_jump_not_loaded)
    // Only an explicit message destination reports failure; normal entry positioning is silent.
    LaunchedEffect(entryState) {
        if (shouldPresentUnresolvedEntrySnackbar(entryState)) {
            snackbarHostState.showSnackbar(jumpNotLoaded)
        }
    }

    val eventText =
        uiEvent?.value?.let { event ->
            when (event) {
                ChatUiEvent.InvalidCommand -> {
                    stringResource(R.string.chat_server_invalid_command)
                }

                ChatUiEvent.ReactionBlocked -> {
                    stringResource(R.string.chat_reaction_blocked)
                }

                ChatUiEvent.ReactionTargetUnavailable -> {
                    stringResource(R.string.chat_react_failed)
                }

                ChatUiEvent.ReactionSendFailed -> {
                    stringResource(R.string.chat_reaction_send_failed)
                }

                ChatUiEvent.RedactionSendFailed -> {
                    stringResource(R.string.chat_redaction_send_failed)
                }

                ChatUiEvent.SendRejected -> {
                    stringResource(R.string.chat_send_rejected)
                }

                ChatUiEvent.SendDropped -> {
                    stringResource(R.string.chat_send_dropped)
                }

                ChatUiEvent.NotInChannel -> {
                    stringResource(R.string.chat_not_in_channel)
                }

                is ChatUiEvent.InviteRequestSent -> {
                    stringResource(R.string.irc_invite_request_sent, event.nick, event.channel)
                }

                ChatUiEvent.InviteSendFailed -> {
                    stringResource(R.string.irc_invite_send_failed)
                }

                ChatUiEvent.ConversationLayoutWriteFailed -> {
                    stringResource(R.string.chat_layout_write_failed)
                }

                ChatUiEvent.PresenceModeWriteFailed -> {
                    stringResource(R.string.chat_presence_write_failed)
                }

                is ChatUiEvent.ReplyJumpUnavailable -> {
                    jumpNotLoaded
                }
            }
        }
    val retryLabel = stringResource(R.string.chat_retry)
    LaunchedEffect(uiEvent?.id) {
        val pending = uiEvent ?: return@LaunchedEffect
        val text = eventText ?: return@LaunchedEffect
        val actionLabel = retryLabel.takeIf { pending.value.hasRetryAction() }
        val result =
            snackbarHostState.showSnackbar(
                message = text,
                actionLabel = actionLabel,
            )
        handleChatUiEventResult(
            event = pending,
            actionPerformed = result == SnackbarResult.ActionPerformed,
            retryReplyJump = onRetryReplyJump,
            acknowledge = onUiEventAcknowledged,
        )
    }

    suspend fun materializeTarget(
        target: ChatPositionTarget,
        scroll: Boolean,
    ): MaterializedChatTarget? {
        // itemCount is used only to establish that the position is addressable. A non-null peek is
        // the sole proof that the target placeholder has materialized.
        val pageReady =
            withTimeoutOrNull(TARGET_MATERIALIZATION_TIMEOUT_MS) {
                snapshotFlow { Triple(items.loadState.refresh, items.loadState.append, items.itemCount) }
                    .first { (refresh, append, count) ->
                        refresh is LoadState.NotLoading &&
                            initialPagingPage(count, append) != InitialPagingPage.Pending
                    }
            } != null
        if (!pageReady) {
            AutoFollowTrace.record("materialize_page_not_ready", traceBufferId, traceSessionId) {
                "target_index=${target.index} refresh=${items.loadState.refresh} " +
                    "append=${items.loadState.append} item_count=${items.itemCount}"
            }
            return null
        }
        val materializedIndex =
            materializableTargetIndex(
                requestedIndex = target.index,
                itemCount = items.itemCount,
                hasExactIdentity = target.expectedEventId != null || target.expectedMsgid != null,
            )
        if (materializedIndex == null) {
            AutoFollowTrace.record("materialize_index_unaddressable", traceBufferId, traceSessionId) {
                "target_index=${target.index} item_count=${items.itemCount}"
            }
            return null
        }
        val row =
            requestAndAwaitTarget(
                index = materializedIndex,
                request = { index ->
                    val count = items.itemCount
                    if (index !in 0 until count) {
                        false
                    } else {
                        try {
                            if (scroll) listState.scrollToItem(index, target.offset)
                            // This is the only item access: it sends Paging a load hint for the exact target.
                            items[index]
                            true
                        } catch (_: IndexOutOfBoundsException) {
                            // A new Paging generation replaced the snapshot between the bound and access.
                            false
                        }
                    }
                },
                snapshots =
                    snapshotFlow {
                        targetMaterialization(items, materializedIndex)
                    },
            )
        if (row == null) {
            AutoFollowTrace.record("materialize_target_missing", traceBufferId, traceSessionId) {
                "target_index=$materializedIndex refresh=${items.loadState.refresh} " +
                    "prepend=${items.loadState.prepend} append=${items.loadState.append} " +
                    "item_count=${items.itemCount} " +
                    "loaded_start=${items.itemSnapshotList.placeholdersBefore} " +
                    "loaded_count=${items.itemSnapshotList.items.size}"
            }
        }
        return row?.let { MaterializedChatTarget(it, materializedIndex) }
    }

    // Deep jumps request one resolved placeholder, then validate both of its exact identities.
    LaunchedEffect(jumpTarget) {
        val j = jumpTarget ?: return@LaunchedEffect
        AutoFollowTrace.record("deep_jump_start", traceBufferId, traceSessionId) {
            "target_index=${j.index} item_count=${items.itemCount}"
        }
        val targetRow =
            try {
                materializeTarget(j, scroll = true)?.row
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                null
            }
        if (targetRow == null) {
            if (j.expectedEventId != null || j.expectedMsgid != null) {
                onReresolveJump(j.requestToken)
            } else {
                onJumpUnresolved(j.requestToken)
            }
        } else if (!positionTargetMatches(j, targetRow)) {
            onReresolveJump(j.requestToken)
        } else {
            AutoFollowTrace.record("deep_jump_settled", traceBufferId, traceSessionId) {
                "target_index=${j.index} item_count=${items.itemCount}"
            }
            if (visibilityPolicy.isFool(targetRow)) expandedFools += targetRow.id
            highlightMsgid = j.highlightMsgid
            initialPositionSettled = true
            suppressNextAutoFollow = true
            onJumpHandled(j.requestToken)
        }
    }

    // Normal entry shares the deep-link paging mechanics but has no highlight. It is separate so
    // a deep link always wins and so a completed normal entry cannot be replayed on recomposition.
    LaunchedEffect(initialTarget) {
        val target = initialTarget ?: return@LaunchedEffect
        AutoFollowTrace.record("initial_position_start", traceBufferId, traceSessionId) {
            "target_index=${target.index} target_offset=${target.offset} " +
                "saved=${target.fromSavedPosition} item_count=${items.itemCount}"
        }
        val pageReady =
            withTimeoutOrNull(TARGET_MATERIALIZATION_TIMEOUT_MS) {
                snapshotFlow { Triple(items.loadState.refresh, items.loadState.append, items.itemCount) }
                    .first { (refresh, append, count) ->
                        refresh is LoadState.NotLoading &&
                            initialPagingPage(count, append) != InitialPagingPage.Pending
                    }
            } != null
        if (!pageReady) {
            AutoFollowTrace.record("initial_position_page_not_ready", traceBufferId, traceSessionId) {
                "target_index=${target.index} refresh=${items.loadState.refresh} " +
                    "append=${items.loadState.append} item_count=${items.itemCount}"
            }
            onInitialPositionUnresolved()
            return@LaunchedEffect
        }
        val currentlyAtBottom =
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset <= AUTOSCROLL_BOTTOM_TOLERANCE_PX
        val terminalEmpty =
            target.index == 0 && target.expectedEventId == null &&
                target.expectedMsgid == null &&
                initialPagingPage(items.itemCount, items.loadState.append) == InitialPagingPage.TerminalEmpty
        val targetRow =
            if (terminalEmpty) {
                null
            } else if (target.placeAtTop) {
                // Open-at-first-unread: scroll to the target placeholder so Paging receives a viewport
                // load hint even from an empty cold generation, then snap it to the top with the
                // remaining unread below. This all runs before settlement, so it cannot advance read
                // state or be misclassified as a user drag.
                val materialized =
                    try {
                        materializeTarget(target, scroll = true)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: RuntimeException) {
                        null
                    }
                if (materialized != null) {
                    val row = materialized.row
                    // The materialization scroll places the target at the reversed viewport start
                    // (visually the bottom). Align that measured row directly instead of estimating a
                    // second index from placeholder row counts: cold Paging swaps those placeholders
                    // for variable-height messages and can otherwise move the target outside layout.
                    val item =
                        withTimeoutOrNull(TARGET_MATERIALIZATION_TIMEOUT_MS) {
                            snapshotFlow {
                                val visible = listState.layoutInfo.visibleItemsInfo
                                val currentIndex =
                                    materializedTargetVisibleIndex(
                                        visible.map { it.key to it.index },
                                        row.id,
                                    )
                                visible.firstOrNull { it.index == currentIndex }
                            }.first { it != null }
                        }
                    if (item != null) {
                        // A single fire-and-forget correction can race a Paging generation swap (the
                        // reopen backfill deletes the history gap milliseconds after materialization,
                        // regenerating the Pager): the lazy layout clamps the scroll mid-presentation
                        // and silently discards the remainder, leaving the entry row at the reversed
                        // viewport start (visual bottom) with the unread run below it uncomposed.
                        // Re-measure and re-correct across frames until the row rests at the top. A
                        // layout that legitimately cannot align (content shorter than the viewport)
                        // stops moving and exits through the pass cap with the single-shot behavior.
                        var pass = 0
                        var unconsumedPasses = 0
                        while (pass < TOP_ALIGNMENT_MAX_PASSES) {
                            val visible = listState.layoutInfo.visibleItemsInfo
                            val currentIndex =
                                materializedTargetVisibleIndex(
                                    visible.map { it.key to it.index },
                                    row.id,
                                )
                            val current = visible.firstOrNull { it.index == currentIndex }
                            if (current == null) {
                                // A generation presentation can hide the row for a frame; observe the
                                // settled layout before concluding it left the viewport.
                                pass++
                                withFrameNanos { }
                                continue
                            }
                            val layout = listState.layoutInfo
                            val correction =
                                reverseItemTopAlignmentCorrection(
                                    itemOffset = current.offset,
                                    itemSize = current.size,
                                    viewportEndOffset = layout.viewportEndOffset,
                                )
                            AutoFollowTrace.record("initial_position_align", traceBufferId, traceSessionId) {
                                "target_index=${current.index} item_offset=${current.offset} " +
                                    "item_size=${current.size} " +
                                    "viewport_start=${layout.viewportStartOffset} " +
                                    "viewport_end=${layout.viewportEndOffset} " +
                                    "correction=$correction pass=$pass"
                            }
                            if (kotlin.math.abs(correction) <= TOP_ALIGNMENT_TOLERANCE_PX) break
                            val consumed = listState.scrollBy(correction.toFloat())
                            pass++
                            // Let the pending layout (and any racing generation presentation) apply
                            // before re-measuring, so a clamped scroll is observed rather than trusted.
                            withFrameNanos { }
                            // Two consecutive fully unconsumed scrolls mean the list is legitimately
                            // clamped (fewer unread than fit the viewport keep the newest row pinned at
                            // the bottom, as firstUnreadTopAnchorIndex documents) — the rest position is
                            // final. A single one may just be a racing presentation frame; retry.
                            unconsumedPasses = if (consumed == 0f) unconsumedPasses + 1 else 0
                            if (unconsumedPasses >= 2) break
                        }
                        row
                    } else {
                        AutoFollowTrace.record("initial_position_align_missing", traceBufferId, traceSessionId) {
                            "target_index=${target.index} item_count=${items.itemCount}"
                        }
                        null
                    }
                } else {
                    null
                }
            } else {
                try {
                    materializeTarget(
                        target,
                        scroll = shouldScrollToInitialTarget(target, currentlyAtBottom),
                    )?.row
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    null
                }
            }
        if (terminalEmpty) {
            initialPositionSettled = true
            suppressNextAutoFollow = true
            onInitialPositionHandled()
        } else if (targetRow != null && positionTargetMatches(target, targetRow)) {
            AutoFollowTrace.record("initial_position_settled", traceBufferId, traceSessionId) {
                "target_index=${target.index} index=${listState.firstVisibleItemIndex} " +
                    "offset=${listState.firstVisibleItemScrollOffset} at_bottom=$currentlyAtBottom " +
                    "place_at_top=${target.placeAtTop}"
            }
            initialPositionSettled = true
            suppressNextAutoFollow = true
            onInitialPositionHandled()
        } else if (targetRow != null) {
            AutoFollowTrace.record("initial_position_reresolve", traceBufferId, traceSessionId) {
                "target_index=${target.index} reason=identity_mismatch item_count=${items.itemCount}"
            }
            onReresolveInitial(target)
        } else if (target.expectedEventId != null || target.expectedMsgid != null) {
            AutoFollowTrace.record("initial_position_reresolve", traceBufferId, traceSessionId) {
                "target_index=${target.index} reason=target_missing item_count=${items.itemCount}"
            }
            onReresolveInitial(target)
        } else {
            AutoFollowTrace.record("initial_position_unresolved", traceBufferId, traceSessionId) {
                "target_index=${target.index} item_count=${items.itemCount} " +
                    "append=${loadStateName(items.loadState.append)}"
            }
            onInitialPositionUnresolved()
        }
    }

    // Clear the highlight after the pulse settles (~1.6s).
    LaunchedEffect(highlightMsgid) {
        if (highlightMsgid != null) {
            kotlinx.coroutines.delay(1_600)
            highlightMsgid = null
        }
    }

    fun saveCurrentScrollPosition() {
        if (!initialPositionSettled) return
        val index = listState.firstVisibleItemIndex
        // Clearing the saved position ASSERTS "resume at the live bottom next time", so only a
        // provable bottom may clear it — [isAtEffectiveBottom] is that proof, and a viewport parked
        // in history (a deep jump's destination, say) has unloaded rows below it so it does not
        // qualify. An indeterminate snapshot — empty because Paging swapped in the incoming
        // buffer's QUERY snapshot before onDispose (the previous direct peek crashed DM
        // navigation), or one with no anchorable row in probe reach — proves neither a bottom nor
        // a place to save, so it only forgets; see [scrollPositionOutcome].
        when (
            val outcome =
                scrollPositionOutcome(
                    firstVisibleIndex = index,
                    firstVisibleOffset = listState.firstVisibleItemScrollOffset,
                    itemCount = items.itemCount,
                    peek = items::peek,
                    policy = visibilityPolicy,
                )
        ) {
            ScrollPositionOutcome.ParkAtBottom -> {
                onClearScrollPosition()
            }

            ScrollPositionOutcome.Forget -> {
                onForgetScrollPosition()
            }

            is ScrollPositionOutcome.Save -> {
                AutoFollowTrace.record("position_saved", traceBufferId, traceSessionId) {
                    "index=$index anchor_index=${outcome.anchorIndex} " +
                        "offset=${listState.firstVisibleItemScrollOffset} " +
                        "row=${outcome.row.id} at_bottom=false following=${autoFollow.following}"
                }
                onScrollPositionChanged(
                    ChatScrollPosition(
                        index = outcome.anchorIndex,
                        offset =
                            listState.firstVisibleItemScrollOffset
                                .takeIf { outcome.anchorIndex == index } ?: 0,
                        msgid = outcome.row.msgid,
                        serverTime = outcome.row.serverTime,
                        rowId = outcome.row.id,
                    ),
                )
            }
        }
    }

    // The previous collector allocated and wrote to the position cache for nearly every pixel of a
    // fling. We only need the final anchor: persist when scrolling settles, plus once on disposal so
    // a back gesture during an active fling still retains the current location.
    LaunchedEffect(initialPositionSettled, listState, visibilityPolicy) {
        if (!initialPositionSettled) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling -> if (!scrolling) saveCurrentScrollPosition() }
    }
    DisposableEffect(initialPositionSettled, listState, visibilityPolicy) {
        onDispose { saveCurrentScrollPosition() }
    }

    // How far back into history this visit actually got, which is a different question from where
    // the reader stopped: a reader who enters at the unread divider and works FORWARD leaves a park
    // that is newer than the divider, and only this watermark distinguishes that from a backfill
    // landing unread history older than the park. [preferredEntryTarget] is its only consumer.
    //
    // Read state is not involved and must not become involved. The reverse-layout mirror of this
    // (renderedNewestAnchor, above) is what clamps a resumed viewport MARKREAD; this one goes to a
    // process-local map and never to the wire.
    //
    // Cost is kept at the same edges as the saved position — scroll start/stop plus disposal —
    // rather than on every measure pass. Recording at scroll START is what captures the depth a
    // reader entered at before they fling away from it; a single drag that goes deep and returns
    // without settling can still under-report, which degrades to the depth-only rule and never to a
    // wrong "already seen".
    fun recordFurthestDisplayed() {
        if (!initialPositionSettled) return
        val deepest =
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: return
        val anchor =
            displayedDepthAnchor(
                deepestVisibleIndex = deepest,
                itemCount = items.itemCount,
                peek = items::peek,
                policy = visibilityPolicy,
            ) ?: return
        onFurthestDisplayedChanged(anchor)
    }
    LaunchedEffect(initialPositionSettled, listState, visibilityPolicy) {
        if (!initialPositionSettled) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { recordFurthestDisplayed() }
    }
    DisposableEffect(initialPositionSettled, listState, visibilityPolicy) {
        onDispose { recordFurthestDisplayed() }
    }

    LaunchedEffect(initialPositionSettled, listState) {
        snapshotFlow {
            // While scrolling, deliberately stop observing itemCount/index. snapshotFlow then
            // unregisters those hot reads until the idle edge, preventing a DB query restart for
            // every row crossed by a fling while keeping the last reaction map on screen.
            if (!initialPositionSettled || listState.isScrollInProgress) {
                null
            } else {
                items.itemCount to listState.firstVisibleItemIndex
            }
        }.distinctUntilChanged()
            .collect { idleWindow ->
                if (idleWindow != null) {
                    onVisibleMsgidsChanged(visibleReactionMsgids(items, listState))
                }
            }
    }

    // The viewport's demand for history, for the ViewModel's "a seam is the end of the list" rule.
    //
    // Keyed on the SEAM LIST rather than on the whole TimelineSeamState: the in-flight and failed
    // sets change around every load, and restarting for those would re-report a viewport whose load
    // is already running. Seam POSITIONS do move as a load recedes them, and recomputing on that
    // edge is the point — a seam a page pushed past the prefetch reach must stop being reported.
    @OptIn(FlowPreview::class)
    LaunchedEffect(items, listState, timelineSeams.seams) {
        // Report the viewport's demand, and journal every report — including the two that say
        // "nothing". This effect is the ONLY producer of the rule's demand signal and it is
        // edge-triggered, so from the ViewModel's side an empty answer and never answering at all
        // are the same shape. Naming the SOURCE separates them, and pairs with `chat_history
        // seam_rule_evaluated` to show the report actually crossed the setSeamPrefetch hop.
        fun reportDemand(
            source: String,
            firstIndex: Int,
            lastIndex: Int,
            anchor: io.github.trevarj.motd.data.db.TimelineAnchor?,
            gapIds: Set<Long>,
        ) {
            onSeamPrefetchChanged(anchor, lastIndex, gapIds)
            diagnostics.record("chat_timeline", "seam_demand_reported") {
                mapOf(
                    "room_id" to traceBufferId,
                    "source" to source,
                    "first_index" to firstIndex,
                    // Renamed from `last_visible_index`: it is the older EDGE, never a depth. The
                    // rule does not read it; it is here because its movement under a pinned newer
                    // edge is the evidence that an index was the wrong unit.
                    "older_edge_index" to lastIndex,
                    "viewport_event_id" to anchor?.eventId,
                    "item_count" to items.itemCount,
                    "seam_count" to timelineSeams.seams.size,
                    "demand_count" to gapIds.size,
                    "settled" to initialPositionSettled,
                )
            }
        }
        // Rooms without a single stored gap are the overwhelming majority, and they have nothing to
        // demand. Return before observing `layoutInfo` at all rather than deriving a reach that can
        // only ever be empty: this effect would otherwise re-read that snapshot state on every
        // measure pass of every timeline in the app, competing with scrolling and paging for the
        // main thread in exactly the rooms that can never use the answer.
        if (timelineSeams.seams.isEmpty()) {
            reportDemand("no_seams", firstIndex = -1, lastIndex = -1, anchor = null, gapIds = emptySet())
            return@LaunchedEffect
        }
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) {
                null
            } else {
                val newestIndex = visible.first().index
                // Peek the newer edge INSIDE the snapshot read, not after it. The reader's position
                // is that row's IDENTITY, and a placeholder resolving under a stationary viewport
                // changes the identity without changing any index — this effect is edge-triggered,
                // so an identity that is not a snapshot dependency would never be re-reported and a
                // seam the viewport could not name would wait forever.
                Triple(
                    newestIndex,
                    visible.last().index,
                    viewportAnchorAt(newestIndex, items.itemCount, items::peek),
                )
            }
        }.distinctUntilChanged()
            .debounce(SEAM_PREFETCH_SETTLE_MS)
            .collect { range ->
                val (newestIndex, oldestIndex, anchor) =
                    range ?: return@collect reportDemand(
                        "empty_range",
                        firstIndex = -1,
                        lastIndex = -1,
                        anchor = null,
                        gapIds = emptySet(),
                    )
                reportDemand(
                    "range",
                    firstIndex = newestIndex,
                    lastIndex = oldestIndex,
                    anchor = anchor,
                    gapIds =
                        seamsWithinPrefetch(
                            firstVisibleIndex = newestIndex,
                            lastVisibleIndex = oldestIndex,
                            itemCount = items.itemCount,
                            peek = items::peek,
                            seams = timelineSeams.seams,
                        ),
                )
            }
    }

    // Long-press action sheet target.
    var sheetTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var redactionTarget by remember { mutableStateOf<MessageEntity?>(null) }
    val sheetState = rememberModalBottomSheetState()

    // Raw ignored tails do not make the user leave the meaningful bottom of the conversation.
    val atBottom by remember(listState, items, visibilityPolicy) {
        derivedStateOf {
            isAtEffectiveBottom(
                firstVisibleIndex = listState.firstVisibleItemIndex,
                firstVisibleOffset = listState.firstVisibleItemScrollOffset,
                itemCount = items.itemCount,
                peek = items::peek,
                policy = visibilityPolicy,
            )
        }
    }

    // Newest row the timeline has actually placed on screen. Neither rawNewestAnchor (the room's
    // newest stored row) nor the Paging snapshot proves display — both keep advancing while the
    // screen is paused — but a measure pass does. Monotonic, so scrolling back through history
    // never retracts what was already seen. Reverse layout: the first visible item is the newest.
    var renderedNewestAnchor by remember(items, listState, visibilityPolicy) {
        mutableStateOf<io.github.trevarj.motd.data.db.TimelineAnchor?>(null)
    }
    LaunchedEffect(items, listState, visibilityPolicy) {
        snapshotFlow {
            val laidOut = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            renderedBottomAnchor(
                renderedIndex = laidOut?.index ?: -1,
                renderedKey = laidOut?.key,
                itemCount = items.itemCount,
                peek = items::peek,
                policy = visibilityPolicy,
            )
        }.distinctUntilChanged()
            .collect { anchor ->
                val seen = renderedNewestAnchor
                if (anchor != null && (seen == null || anchor > seen)) renderedNewestAnchor = anchor
            }
    }

    // Count nested programmatic scrolls rather than using a Boolean: an incoming pin may supersede
    // an explicit animation, and the cancelled animation must not briefly masquerade as a user drag.
    var programmaticScrolls by remember { mutableIntStateOf(0) }
    val autoScrolling = programmaticScrolls > 0

    suspend fun scrollToNewest(
        animate: Boolean,
        reason: String,
    ) {
        AutoFollowTrace.record("scroll_start", traceBufferId, traceSessionId) {
            "reason=$reason animate=$animate index=${listState.firstVisibleItemIndex} " +
                "offset=${listState.firstVisibleItemScrollOffset} following=${autoFollow.following}"
        }
        autoFollow.requestFollow()
        programmaticScrolls++
        try {
            if (animate) listState.animateScrollToItem(0) else listState.scrollToItem(0)
        } finally {
            programmaticScrolls--
            AutoFollowTrace.record("scroll_end", traceBufferId, traceSessionId) {
                "reason=$reason index=${listState.firstVisibleItemIndex} " +
                    "offset=${listState.firstVisibleItemScrollOffset} following=${autoFollow.following}"
            }
        }
    }

    // Jump to a mid-list row (e.g. the nearest unread @mention) without arming auto-follow: a live
    // arrival must not yank the viewport away from the mention the user just navigated to.
    suspend fun scrollToIndex(
        index: Int,
        animate: Boolean,
        reason: String,
    ) {
        AutoFollowTrace.record("scroll_start", traceBufferId, traceSessionId) {
            "reason=$reason animate=$animate target=$index index=${listState.firstVisibleItemIndex} " +
                "offset=${listState.firstVisibleItemScrollOffset} following=${autoFollow.following}"
        }
        programmaticScrolls++
        try {
            if (animate) listState.animateScrollToItem(index) else listState.scrollToItem(index)
        } finally {
            programmaticScrolls--
            AutoFollowTrace.record("scroll_end", traceBufferId, traceSessionId) {
                "reason=$reason index=${listState.firstVisibleItemIndex} " +
                    "offset=${listState.firstVisibleItemScrollOffset} following=${autoFollow.following}"
            }
        }
    }

    // Record only actual scroll-state/programmatic edges. An index/offset change caused by a Paging
    // prepend does not emit here, so it cannot be mistaken for the user leaving the bottom.
    LaunchedEffect(listState, initialPositionSettled) {
        if (!initialPositionSettled) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress to (programmaticScrolls > 0) }
            .distinctUntilChanged()
            .collect { (scrolling, programmatic) ->
                // The same edge auto-follow reads as "the user is taking over", reported to the
                // ViewModel so its one post-catch-up entry correction stands down. Programmatic
                // scrolls (entry placement, the newest FAB, a send) are excluded here; the actions
                // that issue them retire the correction themselves.
                if (scrolling && !programmatic) onTimelineInteraction()
                val before = autoFollow.following
                autoFollow.onScrollStateChanged(scrolling, programmatic, atBottom)
                AutoFollowTrace.record("scroll_intent", traceBufferId, traceSessionId) {
                    "scrolling=$scrolling programmatic=$programmatic at_bottom=$atBottom " +
                        "following_before=$before following_after=${autoFollow.following} " +
                        "index=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}"
                }
                if (!scrolling) {
                    AutoFollowTrace.record("viewport_settled", traceBufferId, traceSessionId) {
                        "at_bottom=$atBottom following=${autoFollow.following} " +
                            "index=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}"
                    }
                }
            }
    }

    // Keep one collector alive for the whole settled entry. Keying a LaunchedEffect directly on
    // itemCount cancelled an in-flight animateScrollToItem when the next message arrived; because
    // that animation also set isScrollInProgress, the replacement effect believed the user had
    // scrolled away and permanently stopped following a burst. Live arrivals snap to index zero;
    // animation is reserved for explicit send/FAB actions.
    LaunchedEffect(items, initialPositionSettled, visibilityPolicy) {
        if (!initialPositionSettled) return@LaunchedEffect
        snapshotFlow {
            items.itemCount to newestEffectiveMessageId(items.itemCount, items::peek, visibilityPolicy)
        }.distinctUntilChanged()
            .collect { (newCount, newestEffectiveId) ->
                val oldCount = autoFollow.presentedItemCount
                val followingBefore = autoFollow.following
                if (suppressNextAutoFollow) {
                    autoFollow.reset(newCount, atBottom, newestEffectiveId)
                    liveEntryIds = emptySet()
                    suppressNextAutoFollow = false
                    AutoFollowTrace.record("paging_initial", traceBufferId, traceSessionId) {
                        "old_count=$oldCount new_count=$newCount at_bottom=$atBottom " +
                            "following=${autoFollow.following} refresh=${loadStateName(items.loadState.refresh)} " +
                            "append=${loadStateName(items.loadState.append)}"
                    }
                } else {
                    val change = autoFollow.onTimelineChangedWithEntry(newCount, newestEffectiveId)
                    val animatedEntryId =
                        change.liveEntryId?.takeUnless {
                            extendsSystemRun(it, newCount, items::peek)
                        }
                    liveEntryIds = appendLiveEntryId(liveEntryIds, animatedEntryId)
                    val newest = if (newCount > 0) items.peek(0) else null
                    AutoFollowTrace.record("follow_decision", traceBufferId, traceSessionId) {
                        "old_count=$oldCount new_count=$newCount at_bottom=$atBottom " +
                            "following_before=$followingBefore following_after=${autoFollow.following} " +
                            "follow=${change.shouldFollow} live_entry=${change.liveEntryId ?: -1} " +
                            "newest_row=${newest?.id ?: -1} " +
                            "newest_kind=${newest?.kind?.name ?: "NONE"} " +
                            "refresh=${loadStateName(items.loadState.refresh)} " +
                            "append=${loadStateName(items.loadState.append)}"
                    }
                    if (change.shouldFollow) {
                        // Apply the new index-zero anchor to the same remeasure that presents the
                        // Paging update. A suspending scroll can expose the old anchor for one frame.
                        autoFollow.requestFollow()
                        listState.requestScrollToItem(0)
                    }
                }
            }
    }

    // Viewport pin. A Paging presentation whose loaded window no longer covers the viewport turns
    // the anchor row into a placeholder, and a placeholder carries a private positional key rather
    // than the row's id — so LazyListState's key-based re-anchoring fails at exactly the moment it
    // is needed, and the viewport keeps a raw index that the same presentation may have moved every
    // row away from. [timelinePresentationPin] restores that index from a row conserved across both
    // presentations, or declines; see its documentation for why every uncertain case declines.
    //
    // Guards, in order, and all of them mandatory:
    //   * initialPositionSettled — entry positioning owns the viewport until it says otherwise, and
    //     it deliberately scrolls through unloaded rows to get there.
    //   * not scrolling and not mid-programmatic-scroll — a pin landing inside a drag or a fling
    //     would itself be the jump this exists to prevent.
    //   * not following, and not at the bottom — the auto-follow effect above issues its own
    //     requestScrollToItem(0) for the same presentation and must win; two requests in one frame
    //     resolve to the last one issued, and it must not be this.
    //
    // The collector never suspends, so it keeps up with the conflated presentation flow and the
    // request reaches the same remeasure that presents the update.
    LaunchedEffect(items, listState, initialPositionSettled) {
        if (!initialPositionSettled) return@LaunchedEffect
        var previous: ItemSnapshotList<MessageEntity>? = null
        snapshotFlow { items.itemSnapshotList }.collect { snapshot ->
            val prior = previous
            previous = snapshot
            if (prior == null) return@collect
            if (listState.isScrollInProgress || programmaticScrolls > 0) return@collect
            if (autoFollow.following || atBottom) return@collect
            val index = listState.firstVisibleItemIndex
            val key = prior.getOrNull(index)?.id ?: return@collect
            // Fast path, and the same answer timelinePresentationPin gives: a key the presentation
            // still loads is one Compose re-anchors natively. Checked here without materializing
            // either window so an ordinary live arrival allocates nothing.
            if (snapshot.items.any { it.id == key }) return@collect
            val pin =
                timelinePresentationPin(
                    anchor =
                        TimelineViewportAnchor(
                            index = index,
                            offset = listState.firstVisibleItemScrollOffset,
                            key = key,
                        ),
                    previous = prior.toTimelineWindow(),
                    current = snapshot.toTimelineWindow(),
                ) ?: return@collect
            listState.requestScrollToItem(pin.index, pin.offset)
            val fields =
                mapOf(
                    "anchor_key" to key,
                    "from_index" to index,
                    "to_index" to pin.index,
                    "offset" to pin.offset,
                    "item_count" to snapshot.size,
                    "placeholders_before" to snapshot.placeholdersBefore,
                    "loaded_count" to snapshot.items.size,
                )
            diagnostics.record("chat_timeline", "presentation_pin") { fields }
            AutoFollowTrace.record("presentation_pin", traceBufferId, traceSessionId) {
                formatTimelineGenerationFields(fields)
            }
        }
    }

    // Generation watch. Nothing in this app can currently see a Paging GENERATION change from the
    // UI side — the timeline reports counts and nothing else — and that is why the visible churn
    // after a catch-up run has stayed un-arbitrated. Every fetched page persists in its own Room
    // transaction, so one catch-up produces several invalidations in a second, and each
    // regeneration re-places a bounded loaded window under a viewport that never moved.
    //
    // Journal ONE line per PRESENTATION (never per frame) carrying the three quantities that
    // separate the competing explanations: where the loaded window landed, what became of the
    // viewport's anchor row, and whether the snapshot was momentarily empty. Reading guide lives on
    // [timelineGenerationFields].
    //
    // Started only while the journal is armed, so an ordinary device pays nothing at all: the
    // collector does not exist, and the per-presentation id list it reads is never built.
    val journalArmed by diagnostics.enabled.collectAsStateWithLifecycle()
    LaunchedEffect(items, listState, journalArmed, traceBufferId) {
        if (!journalArmed) return@LaunchedEffect
        var previous: TimelineWindow? = null
        var generation = 0L
        snapshotFlow { items.itemSnapshotList.toTimelineWindow() }.collect { current ->
            generation++
            val prior = previous
            previous = current
            // Read before the frame: the lazy list has not remeasured against this presentation
            // yet, so index/offset still describe the layout `prior` was measured into.
            val beforeIndex = listState.firstVisibleItemIndex
            val before =
                TimelineViewportAnchor(
                    index = beforeIndex,
                    offset = listState.firstVisibleItemScrollOffset,
                    key = prior?.idAt(beforeIndex),
                )
            withFrameNanos { }
            val afterIndex = listState.firstVisibleItemIndex
            val after =
                TimelineViewportAnchor(
                    index = afterIndex,
                    offset = listState.firstVisibleItemScrollOffset,
                    key = current.idAt(afterIndex),
                )
            val fields =
                timelineGenerationFields(
                    generation = generation,
                    previous = prior,
                    current = current,
                    before = before,
                    after = after,
                    settled = initialPositionSettled,
                    scrolling = listState.isScrollInProgress,
                    following = autoFollow.following,
                )
            diagnostics.record("chat_timeline", "generation_presented") { fields }
            AutoFollowTrace.record("generation_presented", traceBufferId, traceSessionId) {
                formatTimelineGenerationFields(fields)
            }
        }
    }
    val buffer = state.buffer
    val titleTarget = chatTitleTarget(buffer?.type)
    val titleClickLabel =
        when (titleTarget) {
            ChatTitleTarget.CHANNEL_INFO -> stringResource(R.string.chat_open_channel_info)
            ChatTitleTarget.NICK_DETAILS -> stringResource(R.string.chat_open_nick_details)
            ChatTitleTarget.NONE -> null
        }
    // Mark read on new-message-while-at-bottom only: syncing while scrolled up
    // reading history would clear unread on other clients and destroy the local unread UX. What
    // "at bottom" is allowed to mean is therefore load-bearing, and `atBottom` is the only thing
    // standing between a viewport parked in history and a room-wide MARKREAD: see isAtEffectiveBottom.
    //
    // viewportReadEnabled is a key here, so the pause/resume flip restarts the effect. Arrivals
    // while paused correctly do not acknowledge, but the restart used to acknowledge them anyway
    // the instant the screen came back, before a single one had been measured. Remembering that a
    // pause happened lets the first run back clamp to what was actually rendered; every later run
    // is steady state again. The flag is consumed by the first enabled run whether or not it
    // acknowledges, so a clamp can never leak into a genuinely live viewport.
    val pausedSinceLastRun = remember { mutableStateOf(false) }
    LaunchedEffect(
        rawNewestAnchor,
        renderedNewestAnchor,
        atBottom,
        initialPositionSettled,
        viewportReadEnabled,
    ) {
        if (!viewportReadEnabled) {
            pausedSinceLastRun.value = true
            return@LaunchedEffect
        }
        val resumed = pausedSinceLastRun.value
        pausedSinceLastRun.value = false
        val newest =
            viewportMarkReadAnchor(
                rawNewest = rawNewestAnchor,
                renderedNewest = renderedNewestAnchor,
                resumed = resumed,
            ) ?: return@LaunchedEffect
        val ackable =
            shouldMarkReadFromViewport(
                atBottom = atBottom,
                initialPositionSettled = initialPositionSettled,
                viewportReadEnabled = viewportReadEnabled,
            )
        if (ackable && newest.serverTime > 0) {
            AutoFollowTrace.record("viewport_markread", traceBufferId, traceSessionId) {
                "marker=${newest.serverTime}:${newest.eventId} item_count=${items.itemCount}"
            }
            onMarkRead(newest)
        }
    }
    val recentSpeakers =
        remember(items.itemCount) {
            // Exclude system-event senders and self so recency ranking reflects real conversation
            // partners. Only the newest rows matter for recency, so cap the scan (the list
            // is reverse-laid-out, so index 0.. are the newest) to stay cheap on large loaded windows.
            (0 until minOf(items.itemCount, 60))
                .mapNotNull { items.peek(it) }
                .filterNot { isSystemKind(it.kind) || it.isSelf }
                .map { it.sender }
        }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            Column {
                TopAppBar(
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    title = {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .then(
                                        if (titleClickLabel != null) {
                                            Modifier.clickable(onClickLabel = titleClickLabel) {
                                                buffer?.let { onOpenChannelInfo(it.id) }
                                            }
                                        } else {
                                            Modifier
                                        },
                                    ).testTag("chat_title"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val hideAvatar = avatarsHidden()
                            if (!hideAvatar) {
                                Avatar(
                                    name = buffer?.displayName ?: "",
                                    size = MotdSizes.headerAvatar,
                                    isChannel = buffer?.type == BufferType.CHANNEL,
                                    networkId = buffer?.networkId,
                                    conversationModel = buffer?.avatarOverrideModel,
                                )
                            }
                            // The 10dp gap only separates the title from the avatar beside it.
                            Column(modifier = Modifier.padding(start = if (hideAvatar) 0.dp else 10.dp).weight(1f)) {
                                Text(
                                    text = buffer?.displayName ?: "",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                AnimatedContent(
                                    targetState = chatSubtitleModel(state, ctx),
                                    transitionSpec = {
                                        fadeIn(MotdMotion.microFadeIn) togetherWith
                                            fadeOut(MotdMotion.microFadeOut)
                                    },
                                    label = "chat_subtitle",
                                ) { subtitle ->
                                    when (subtitle) {
                                        is ChatSubtitleModel.Text -> {
                                            Text(
                                                text = subtitle.value,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }

                                        null -> {
                                            Unit
                                        }
                                    }
                                }
                            }
                            ChatTitleSyncSpinner(timelineHistoryStatus)
                            if (titleTarget != ChatTitleTarget.NONE) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (showBack) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.chat_back),
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { buffer?.let { onOpenSearch(it.id) } }) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = stringResource(R.string.chat_search),
                            )
                        }
                        IconButton(
                            onClick = { overflowOpen = true },
                            modifier = Modifier.testTag("chat_overflow"),
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            if (state.isSidecar) {
                                DropdownMenuItem(
                                    modifier = Modifier.testTag("chat_sidecar_security"),
                                    text = { Text(stringResource(R.string.sidecar_manage_security)) },
                                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                                    onClick = {
                                        overflowOpen = false
                                        onManageProviderSecurity()
                                    },
                                )
                            }
                            if (buffer?.type == BufferType.CHANNEL && buffer.joined) {
                                val inviteEnabled = state.connState is IrcClientState.Ready
                                DropdownMenuItem(
                                    modifier = Modifier.testTag("chat_invite_user"),
                                    text = {
                                        Column {
                                            Text(stringResource(R.string.irc_invite_user_title))
                                            if (!inviteEnabled) {
                                                Text(
                                                    stringResource(R.string.irc_invite_disconnected),
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.GroupAdd, contentDescription = null) },
                                    enabled = inviteEnabled,
                                    onClick = {
                                        overflowOpen = false
                                        onInviteUser()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                modifier = Modifier.testTag("chat_layout_menu"),
                                text = {
                                    Column {
                                        Text(stringResource(R.string.chat_layout_title))
                                        Text(
                                            stringResource(
                                                R.string.chat_layout_overflow_summary,
                                                densityLabel(conversationLayout.effective),
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                },
                                onClick = {
                                    overflowOpen = false
                                    conversationLayoutSheetOpen = true
                                },
                            )
                            DropdownMenuItem(
                                modifier = Modifier.testTag("chat_presence_menu"),
                                text = {
                                    Column {
                                        Text(stringResource(R.string.chat_presence_title))
                                        Text(
                                            stringResource(
                                                R.string.chat_presence_overflow_summary,
                                                stringResource(presenceModeLabel(presenceMode)),
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                },
                                onClick = {
                                    overflowOpen = false
                                    presenceModeSheetOpen = true
                                },
                            )
                            if (fools.isNotEmpty()) {
                                val foolsShown =
                                    if (foolsMode == FoolsMode.HIDE) {
                                        hiddenFoolsRevealed
                                    } else {
                                        expandAllFools
                                    }
                                DropdownMenuItem(
                                    modifier = Modifier.testTag("chat_toggle_fools_visibility"),
                                    text = {
                                        Text(
                                            stringResource(
                                                if (foolsShown) {
                                                    R.string.chat_fool_collapse_all
                                                } else {
                                                    R.string.chat_fool_expand_all
                                                },
                                            ),
                                        )
                                    },
                                    onClick = {
                                        overflowOpen = false
                                        if (foolsMode == FoolsMode.HIDE) {
                                            onHiddenFoolsRevealedChange(!hiddenFoolsRevealed)
                                        } else {
                                            expandAllFools = !expandAllFools
                                            expandedFools = emptySet()
                                            collapsedFools = emptySet()
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (foolsShown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = null,
                                        )
                                    },
                                )
                            }
                        }
                    },
                )
                if (accountSetupReminder) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(stringResource(R.string.account_reminder_message), modifier = Modifier.weight(1f))
                            TextButton(onClick = onDismissAccountSetup) { Text(stringResource(R.string.account_reminder_not_now)) }
                            Button(onClick = onAccountSetup, modifier = Modifier.testTag("chat_account_setup")) {
                                Text(stringResource(R.string.account_setup_title))
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        // TopAppBar owns the status-bar inset. The chat surface draws edge-to-edge horizontally,
        // while these consuming modifiers keep the composer above navigation and animated IME
        // insets without double-padding their overlap.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .navigationBarsPadding()
                    .imePadding(),
        ) {
            // No composition-phase IME read here on purpose: the composer samples the animated inset
            // in its own measure phase, so the whole timeline stays skippable while the keyboard
            // animates instead of recomposing once per frame.
            val conversationSpacing =
                remember(conversationLayout.effective, messageSpacing, bubbleCornerStyle) {
                    spacingFor(conversationLayout.effective, messageSpacing, bubbleCornerStyle)
                }
            // The runway: how far the whole timeline slides up while a flight is airborne, so
            // vacated space exists under the ghost from the tap frame onward. Hoisted to this
            // scope because both the timeline (deep in the pane) and the ghost overlay (a
            // sibling drawn over the composer) read it. Deferred lambda: read only in the
            // list's layer and the ghost's, never composed.
            val density = LocalDensity.current
            val flightListShift =
                remember(
                    outgoingFlight?.token,
                    conversationSpacing,
                    density,
                ) {
                    val active = outgoingFlight != null
                    val gapPx =
                        with(density) {
                            bubbleGap(flightShowSender, items.itemCount > 0, conversationSpacing).toPx()
                        }
                    val shift: () -> Float = shift@{
                        if (!active) return@shift 0f
                        val revealed = flightAnchors.landingRow?.second?.height ?: 0f
                        sendFlightListShift(
                            runwayHeight = flightAnchors.ghostHeight + gapPx,
                            liftFraction = flightMotion.lift.value,
                            revealedGap = revealed,
                            footDrop = flightAnchors.composerShrink(),
                        )
                    }
                    shift
                }
            ConversationTypography(conversationFontScalePercent) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            // Subtle IRC-themed wallpaper layered UNDER the message list only (never over the
                            // composer). NONE renders the plain theme background; MessageList is untouched.
                            ChatWallpaperBackground(chatWallpaper, modifier = Modifier.matchParentSize())
                            CompositionLocalProvider(LocalSpacing provides conversationSpacing) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            // The runway slides the whole timeline up past this pane's top edge;
                                            // without the clip those rows would paint over the app bar.
                                            .clipToBounds()
                                            .testTag("chat_layout_effective_${conversationLayout.effective.name.lowercase()}"),
                                ) {
                                    // Keep the timeline's callbacks stable. A freshly allocated lambda on every
                                    // recomposition of this scope defeats MessageList's skipping, which is what
                                    // turned any surrounding state change into a full timeline recomposition.
                                    // Keyed like `liveEntryIds` itself, which is re-remembered per buffer.
                                    val onLiveEntryConsumed =
                                        remember(state.buffer?.id) {
                                            { id: Long -> liveEntryIds = consumeLiveEntryId(liveEntryIds, id) }
                                        }
                                    val onFlightRowPositioned =
                                        remember(flightAnchors) {
                                            { id: Long, bounds: Rect ->
                                                flightAnchors.reportLandingRow(id, bounds)
                                                // Recorded here rather than from the flight's event ids: this fires on
                                                // the row's first layout, before any accept could have named it. The
                                                // guard matters -- this runs on every frame of the reveal.
                                                if (id !in flownRowIds) flownRowIds = flownRowIds + id
                                            }
                                        }
                                    val onTimelineLongPress =
                                        remember {
                                            { message: MessageEntity -> sheetTarget = message }
                                        }
                                    val canRetryMessage =
                                        remember(state.buffer) {
                                            { message: MessageEntity ->
                                                state.buffer?.let { buffer ->
                                                    io.github.trevarj.motd.service
                                                        .isGenericRetryEligible(buffer, message)
                                                } == true
                                            }
                                        }
                                    val onAcceptDccTransferRequest =
                                        remember(dccDestinationPicker) {
                                            { transferId: Long, filename: String, allowPrivate: Boolean ->
                                                pendingDccAccept = PendingDccAccept(transferId, allowPrivate)
                                                dccDestinationPicker.launch(filename)
                                            }
                                        }
                                    // Link-preview tap opens the URL in the system browser.
                                    val onOpenLink =
                                        remember(ctx) {
                                            { url: String -> ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                                        }
                                    // Effective expansion: global expand-all default, minus rows the user
                                    // re-collapsed; otherwise only individually expanded rows show (bug #9).
                                    val foolExpanded =
                                        remember {
                                            { id: Long ->
                                                if (expandAllFools) id !in collapsedFools else id in expandedFools
                                            }
                                        }
                                    // Bidirectional per-row toggle, respecting the global default.
                                    val onToggleFool =
                                        remember {
                                            { id: Long ->
                                                if (expandAllFools) {
                                                    collapsedFools =
                                                        if (id in collapsedFools) collapsedFools - id else collapsedFools + id
                                                } else {
                                                    expandedFools =
                                                        if (id in expandedFools) expandedFools - id else expandedFools + id
                                                }
                                            }
                                        }
                                    // Entry veil: the list is transparent until entry positioning settles, then
                                    // fades in. The animated value is read inside the graphicsLayer lambda so the
                                    // fade never recomposes the timeline subtree. Starting lifted (a restored
                                    // visit) initializes at 1f, so there is no fade-in on re-entry -- which is also
                                    // why the overlay's own gate is seeded from the initial state rather than
                                    // waiting for a finish callback that never fires.
                                    val veilAlpha by animateFloatAsState(
                                        targetValue = if (entryVeilLifted) 1f else 0f,
                                        animationSpec = MotdMotion.fadeIn,
                                        label = "chat_entry_veil_alpha",
                                        finishedListener = { entryVeilCleared = true },
                                    )
                                    Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = veilAlpha }) {
                                        MessageList(
                                            items = items,
                                            listState = listState,
                                            liveEntryIds = liveEntryIds,
                                            onLiveEntryConsumed = onLiveEntryConsumed,
                                            outgoingFlight = outgoingFlight,
                                            flownRowIds = flownRowIds,
                                            flightProgress = flightProgress,
                                            onFlightRowPositioned = onFlightRowPositioned,
                                            listShift = flightListShift,
                                            networkId = state.buffer?.networkId,
                                            bufferId = state.buffer?.id,
                                            conversationName = state.buffer?.displayName,
                                            directMessage = state.buffer?.type == BufferType.QUERY,
                                            collapseSystemEvents = !isServerBuffer,
                                            // Frozen read-marker so the "New messages" divider stays put.
                                            readMarkerTime = unreadEntrySnapshot?.marker,
                                            readMarkerLabel = unreadEntryLabel,
                                            timelineSeams = timelineSeams,
                                            onLoadGap = onLoadGap,
                                            reactionChips = reactionChips,
                                            replyPreview = replyPreview,
                                            onReplyPreviewClick = onReplyPreviewClick,
                                            onLongPress = onTimelineLongPress,
                                            onReply = timelineReply,
                                            onReact = onReact,
                                            onImageClick = onOpenImage,
                                            onRetry = onRetry,
                                            canRetry = canRetryMessage,
                                            onDelete = onDelete,
                                            onAcceptInvite = onAcceptInvite,
                                            onDismissInvite = onDismissInvite,
                                            dccTransfer = dccTransfer,
                                            onAcceptDccTransfer = onAcceptDccTransferRequest,
                                            onRejectDccTransfer = onRejectDccTransfer,
                                            onRemoveDccTransfer = onRemoveDccTransfer,
                                            loadPreview = loadPreview,
                                            richContentReady = initialPositionSettled,
                                            showImages = showImages,
                                            showLinkPreviews = showLinkPreviews,
                                            cachedPreview = cachedPreview,
                                            loadAudioMetadata = loadAudioMetadata,
                                            cachedAudioMetadata = cachedAudioMetadata,
                                            audioPlaybackState = audioPlaybackState,
                                            audioWaveforms = audioWaveforms,
                                            audioCacheStatuses = audioCacheStatuses,
                                            onAudioToggle = onAudioToggle,
                                            onAudioCacheInspect = onAudioCacheInspect,
                                            onAudioSeek = onAudioSeek,
                                            onOpenLink = onOpenLink,
                                            highlightMsgid = highlightMsgid,
                                            knownNicks = knownNicks,
                                            friends = friends,
                                            fools = fools,
                                            foolsMode = foolsMode,
                                            identityRules = identityRules,
                                            historyUiState = historyUiState,
                                            foolExpanded = foolExpanded,
                                            onToggleFool = onToggleFool,
                                            onSenderClick = onSenderClick,
                                        )
                                    }
                                    // Swallow input until the fade has actually finished, not merely started: a
                                    // barely-visible list is still a list, and a drag or long-press landing on a row
                                    // the user cannot see yet is the same bug as one landing on a hidden row. Gated
                                    // on a boolean rather than on veilAlpha directly so the reveal costs two
                                    // recompositions instead of one per animation frame.
                                    if (!entryVeilCleared) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .matchParentSize()
                                                    .testTag(CHAT_ENTRY_VEIL_TAG)
                                                    .pointerInput(Unit) {
                                                        awaitPointerEventScope {
                                                            while (true) {
                                                                awaitPointerEvent(PointerEventPass.Initial)
                                                                    .changes
                                                                    .forEach { it.consume() }
                                                            }
                                                        }
                                                    },
                                        )
                                    }
                                }
                            }

                            // Paging begins with a transient empty refresh before Room delivers its first
                            // page. Only show the empty state once APPEND proves the buffer is terminally
                            // empty; otherwise the large placeholder flashes during every chat entry.
                            if (items.loadState.refresh is LoadState.NotLoading &&
                                initialPagingPage(items.itemCount, items.loadState.append) ==
                                InitialPagingPage.TerminalEmpty &&
                                !historySyncStatus.isActive
                            ) {
                                io.github.trevarj.motd.ui.components.EmptyState(
                                    icon = Icons.Outlined.Forum,
                                    title = stringResource(R.string.chat_empty_title),
                                    message = stringResource(R.string.chat_empty_message),
                                    ghostRows = true,
                                )
                            }

                            // Keep the hot firstVisibleItemIndex read inside the FAB subtree. Reading it in
                            // ChatContent made every row boundary re-run the entire Scaffold/list/composer.
                            ViewportScrollToBottomFab(
                                listState = listState,
                                readMarker = readMarkerLive,
                                visibilityPolicy = visibilityPolicy,
                                countUnreadBelowViewport = countUnreadBelowViewport,
                                nearestUnreadMentionBelow = nearestUnreadMentionBelow,
                                visible = shouldShowNewestFab(atBottom, autoScrolling),
                                onJumpMention = onFocusRecentMention,
                                onJumpNewest = {
                                    onJumpToNewest()
                                    scope.launch { scrollToNewest(animate = true, reason = "jump_fab") }
                                },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                            )

                            val stagedVoicePlaybackId = voiceState.staged?.let { "voice:${it.file.toURI()}" }
                            TimelineTopOverlays(
                                audioPlayer = {
                                    if (audioPlaybackState.activeId != stagedVoicePlaybackId) {
                                        AudioMiniPlayer(
                                            state = audioPlaybackState,
                                            onToggle = onAudioToggleActive,
                                            onCancelLoading = onAudioCancelLoading,
                                            onRetry = onAudioRetry,
                                            onDismiss = onAudioDismiss,
                                            onSeek = { positionMs ->
                                                audioPlaybackState.attachment?.let { onAudioSeek(it, positionMs) }
                                            },
                                            onOpenOrigin = onOpenAudioOrigin,
                                            onSpeed = { speed ->
                                                audioPlaybackState.attachment?.let { onAudioSpeed(it, speed) }
                                            },
                                            includeNetwork =
                                                audioPlaybackState.origin?.networkId != state.buffer?.networkId,
                                        )
                                    }
                                },
                                historyFailure = {
                                    TimelineHistorySyncFailure(
                                        status = timelineHistoryStatus,
                                        retryEnabled = state.connState is IrcClientState.Ready,
                                        // Both halves of history recovery: the coordinator re-runs the
                                        // reconciliation pass (coalescing onto any in-flight one) while
                                        // Paging re-attempts the failed newer/older fetch.
                                        onRetry = {
                                            onHistorySyncRetry()
                                            items.retry()
                                        },
                                    )
                                },
                                staleChip = {
                                    TimelineHistoryStaleChip(
                                        status = timelineHistoryStatus,
                                        timelineEmpty = items.itemCount == 0,
                                        onDismiss = onHistorySyncDismiss,
                                    )
                                },
                            )
                        }

                        val isChannelBuffer = state.buffer?.type == BufferType.CHANNEL
                        val completions =
                            remember(composerText, memberNicks, recentSpeakers, isChannelBuffer) {
                                autocompleteFor(
                                    composerText,
                                    memberNicks,
                                    recentSpeakers,
                                    nickNormalizer,
                                    isChannel = isChannelBuffer,
                                )
                            }
                        val needsMemberCompletion =
                            remember(composerText) {
                                composerNeedsMemberNicks(composerText)
                            }
                        LaunchedEffect(needsMemberCompletion) {
                            if (needsMemberCompletion) onNeedMembers()
                        }
                        // Debounce the SHOW so fast typing doesn't flash the suggestion panel on every
                        // keystroke: only reveal completions after a brief pause. Hiding stays immediate
                        // (an empty result clears the panel at once) so the panel never lingers stale.
                        var showAutocomplete by remember { mutableStateOf(false) }
                        LaunchedEffect(completions) {
                            if (completions.isEmpty()) {
                                showAutocomplete = false
                            } else {
                                kotlinx.coroutines.delay(AUTOCOMPLETE_SHOW_DEBOUNCE_MS)
                                showAutocomplete = true
                            }
                        }
                        // Keyed on the buffer like the sibling composer state so the panel's held exit
                        // content never leaks across a buffer switch.
                        key(traceBufferId) {
                            VoiceComposerPanel(
                                state = voiceState,
                                playbackState = audioPlaybackState,
                                onDelete = onVoiceDelete,
                                onCancelRecording = onVoiceHoldCancel,
                                onStopRecording = onVoiceHoldStop,
                                onSend = onVoiceSend,
                                onPreview = { attachment -> onAudioToggle(AudioPlaybackRequest(attachment, null)) },
                                onPreviewSeek = { attachment, positionMs -> onAudioSeek(attachment, positionMs) },
                                onToggleEncryption = onVoiceToggleEncryption,
                                onDestinationSelected = onVoiceDestinationSelected,
                                onErrorDismissed = onVoiceErrorDismissed,
                            )
                        }
                        // The buffer stays non-null while a rejoin animates the banner out, so the exiting
                        // content can keep reading it without snapshotting.
                        AnimatedVisibility(
                            visible = state.parted,
                            enter = expandVertically(animationSpec = MotdMotion.contentSize) + fadeIn(MotdMotion.fadeIn),
                            exit = shrinkVertically(animationSpec = MotdMotion.contentSize) + fadeOut(MotdMotion.microFadeOut),
                        ) {
                            PartedChannelBanner(
                                channel = state.buffer?.displayName.orEmpty(),
                                onRejoin = onRejoin,
                            )
                        }
                        Composer(
                            value = composerText,
                            onValueChange = {
                                val wasBlank = composerText.text.isBlank()
                                composerText = it
                                onDraftChanged(it.text)
                                if (it.text.isNotBlank()) {
                                    onTyping(true)
                                } else if (!wasBlank) {
                                    onTyping(false)
                                }
                            },
                            onSend = {
                                val text = composerText.text
                                if (plainIrcText(text).isNotBlank()) {
                                    if (isLongDraft(text)) {
                                        AutoFollowTrace.record("long_draft_prompt_open", traceBufferId, traceSessionId)
                                        longDraftPrompt = true
                                    } else {
                                        AutoFollowTrace.record("composer_submit", traceBufferId, traceSessionId) {
                                            "long_draft=false"
                                        }
                                        onSubmit(text)
                                        // Empty the field on the tap frame. The ViewModel still owns the
                                        // durable draft and republishes it if the send never lands, so this
                                        // is presentation only -- notifying onDraftChanged here would count
                                        // as an edit and make the submission itself stale.
                                        composerText = TextFieldValue("")
                                        scope.launch {
                                            scrollToNewest(animate = true, reason = "composer_send_action")
                                        }
                                    }
                                }
                            },
                            enabled = composerEnabled,
                            onFieldPositioned = { flightAnchors.composerField = it },
                            onFieldTextPositioned = { flightAnchors.composerTextOrigin = it },
                            // Keep the reply content mounted while its banner exits, but start that exit on
                            // the send tap rather than after persistence clears the durable draft.
                            reply =
                                outgoingFlight?.let { flight ->
                                    flight.replyText?.let { ComposerReply(flight.replySender.orEmpty(), it) }
                                } ?: state.replyTo?.let { ComposerReply(it.sender, it.text) },
                            replyVisible = outgoingFlight?.replyText == null,
                            onCancelReply = { onSetReply(null) },
                            // SERVER buffers send raw commands; hint that in the placeholder.
                            // Held blank while a flight is airborne: the ghost is born over the input box
                            // at exactly the field-text origin, and the placeholder appearing beneath the
                            // departing line read as two texts fighting for the same spot.
                            placeholder =
                                when {
                                    outgoingFlight != null -> ""
                                    isServerBuffer -> stringResource(R.string.chat_server_composer_hint)
                                    else -> stringResource(R.string.chat_composer_placeholder)
                                },
                            showEmojiTool = showComposerEmoji,
                            showFormattingTools = showComposerFormattingTools,
                            ircFormattingEnabled =
                                !isServerBuffer &&
                                    !state.buffer?.displayName.equals("BouncerServ", ignoreCase = true),
                            onAttachment = {
                                uploadCurrentDraftDirectly = false
                                attachmentSheetOpen = true
                            },
                            onUploadDraft = {
                                uploadCurrentDraftDirectly = true
                                attachmentSheetOpen = true
                            },
                            voiceEnabled = voiceEnabled && voiceState.staged == null && composerText.text.isBlank(),
                            voiceRecording = voiceState.recording != null,
                            onVoiceHoldStart = onVoiceHoldStart,
                            onVoiceAccessibilityStart = onVoiceAccessibilityStart,
                            onVoiceHoldStop = onVoiceHoldStop,
                            onVoiceHoldCancel = onVoiceHoldCancel,
                            onVoiceLock = onVoiceLock,
                            autocomplete =
                                if (showAutocomplete && completions.isNotEmpty()) {
                                    {
                                        AutocompletePanel(
                                            candidates = completions.map { it.display },
                                            isCommand = completions.firstOrNull()?.isCommand == true,
                                            networkId = state.buffer?.networkId,
                                            onPick = { picked ->
                                                composerText = applyPick(composerText, picked)
                                                onDraftChanged(composerText.text)
                                            },
                                        )
                                    }
                                } else {
                                    null
                                },
                        )
                    }
                    // The ghost overlay draws ABOVE the composer layer: a sent bubble materializes over
                    // the input box -- the morph pins its stand-in text to the still-warm field text --
                    // and then rides up into the timeline, instead of being born occluded behind the
                    // input bar. The clip and the coordinate origin sit on this stationary wrapper:
                    // clipping the ghost itself would clip nothing, since its layer translation carries
                    // its bounds along with its pixels. The spacing provider mirrors the timeline
                    // pane's, so the replica renders under exactly the tokens the rows use.
                    CompositionLocalProvider(LocalSpacing provides conversationSpacing) {
                        Box(
                            modifier =
                                Modifier
                                    .matchParentSize()
                                    .clipToBounds()
                                    // Layout-phase write only: the flight resolves the composer's and the
                                    // landing row's window rects against this origin.
                                    .onGloballyPositioned {
                                        flightAnchors.hostOrigin = it.positionInWindow()
                                    },
                        ) {
                            SendFlightOverlay(
                                flight = outgoingFlight,
                                anchors = flightAnchors,
                                motion = flightMotion,
                                listShift = flightListShift,
                                selfNick = selfNick,
                                showSender = flightShowSender,
                                networkId = state.buffer?.networkId,
                                knownNicks = knownNicks,
                                identityRules = identityRules,
                            )
                        }
                    }
                }
            }
        }
    }

    AttachmentSheets(
        open = attachmentSheetOpen,
        currentDraft = composerText.text,
        networkId = state.buffer?.networkId,
        // Offer-only. The upload path binds the advertised endpoint to the connected network's own
        // host and refuses an off-host one there, where the credential is actually attached.
        sojuFileHostAvailable =
            (state.connState as? IrcClientState.Ready)
                ?.isupport
                ?.let(::sojuFileHostAdvertised) == true,
        startWithCurrentDraft = uploadCurrentDraftDirectly,
        sharedFile = sharedFile,
        directFileTransferAvailable =
            state.buffer?.type == BufferType.QUERY &&
                state.connState is IrcClientState.Ready,
        onDismiss = {
            attachmentSheetOpen = false
            uploadCurrentDraftDirectly = false
            sharedFile = null
        },
        onInsertUrl = {
            composerText =
                io.github.trevarj.motd.ui.components
                    .insertAtCursor(composerText, it)
            onDraftChanged(composerText.text)
        },
        onReplaceDraft = {
            composerText =
                TextFieldValue(
                    it,
                    androidx.compose.ui.text
                        .TextRange(it.length),
                )
            onDraftChanged(composerText.text)
        },
        onDirectFile = onSendDccFile,
        providerUploadAvailable = state.isSidecar && state.sidecarsEnabled,
        onProviderUpload = onSendProviderAttachment,
    )
    if (longDraftPrompt) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                AutoFollowTrace.record("long_draft_dismiss", traceBufferId, traceSessionId)
                longDraftPrompt = false
            },
            title = { Text("Long draft") },
            text = { Text("Upload the draft as a paste, or send it as ordinary IRC messages?") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    AutoFollowTrace.record("long_draft_upload", traceBufferId, traceSessionId)
                    longDraftPrompt = false
                    uploadCurrentDraftDirectly = true
                    attachmentSheetOpen = true
                }) { Text("Upload as paste") }
            },
            dismissButton = {
                Row {
                    androidx.compose.material3.TextButton(onClick = {
                        AutoFollowTrace.record("long_draft_send_messages", traceBufferId, traceSessionId)
                        longDraftPrompt = false
                        onSubmit(composerText.text)
                        composerText = TextFieldValue("")
                        scope.launch { scrollToNewest(animate = true, reason = "long_draft_send") }
                    }) { Text("Send as messages") }
                    androidx.compose.material3.TextButton(onClick = {
                        AutoFollowTrace.record("long_draft_cancel", traceBufferId, traceSessionId)
                        longDraftPrompt = false
                    }) { Text("Cancel") }
                }
            },
        )
    }

    sheetTarget?.let { target ->
        // Dismiss with the M3 hide animation, clearing the target only once it settles.
        val hideThen: (() -> Unit) -> Unit = { after ->
            scope.launch { sheetState.hide() }.invokeOnCompletion {
                sheetTarget = null
                after()
            }
        }
        MessageActionSheet(
            sheetState = sheetState,
            isServerBuffer = isServerBuffer,
            onDismiss = { sheetTarget = null },
            onReply = {
                hideThen {
                    onSetReply(target)
                    composerText =
                        composerTextForReply(
                            value = composerText,
                            sender = target.sender,
                            bufferType = state.buffer?.type,
                            visibleReplyPrefix = visibleReplyPrefix,
                        )
                    onDraftChanged(composerText.text)
                }
            },
            // Pass the whole target: the VM queues the react when target.msgid is still null (own
            // pending message) instead of silently dropping it.
            onReact = { emoji -> hideThen { onReact(target, emoji) } },
            reactionEnabled = { emoji ->
                val ready = state.connState as? IrcClientState.Ready
                val mine =
                    target.msgid?.let { msgid ->
                        reactionChips(msgid).firstOrNull { it.emoji == emoji }?.mine
                    } == true
                ready != null && canSendReactionTags(ready.caps, ready.isupport, remove = mine)
            },
            onCopy = {
                hideThen {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", target.text)))
                    }
                }
            },
            onQuote = {
                // Append the quote to the existing draft with the cursor at the end.
                hideThen {
                    composerText = appendPrefill(composerText, "> ${target.text}\n")
                    onDraftChanged(composerText.text)
                }
            },
            canRedact = canRedactMessage(target, state.buffer?.type, state.connState),
            onRedact = { hideThen { redactionTarget = target } },
            // Outbound share: the raw message text, no formatting or attribution.
            onShare = { hideThen { shareMessageText(ctx, target.text) } },
        )
    }

    redactionTarget?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { redactionTarget = null },
            title = { Text(stringResource(R.string.chat_redaction_confirm_title)) },
            text = { Text(stringResource(R.string.chat_redaction_confirm_body)) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        redactionTarget = null
                        onRedact(target)
                    },
                    modifier = Modifier.testTag("message_redact_confirm"),
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { redactionTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            modifier = Modifier.testTag("message_redact_dialog"),
        )
    }

    if (presenceModeSheetOpen) {
        PresenceModeSheet(
            state = state.conversationPresence,
            onSelect = { override ->
                onPresenceModeSelected(override)
                presenceModeSheetOpen = false
            },
            onDismiss = { presenceModeSheetOpen = false },
        )
    }
    if (conversationLayoutSheetOpen) {
        ConversationLayoutSheet(
            state = conversationLayout,
            onSelect = { override ->
                onConversationLayoutSelected(override)
                conversationLayoutSheetOpen = false
            },
            onDismiss = { conversationLayoutSheetOpen = false },
        )
    }
}

/**
 * Pixel delta for [androidx.compose.foundation.gestures.ScrollableState.scrollBy] that top-aligns
 * an item in a `reverseLayout` list. Item offsets grow along the layout axis from the viewport
 * start, which reverse layout places at the visual BOTTOM — so the visual top is the viewport END,
 * and a top-aligned item has `offset + size == viewportEndOffset`. `scrollBy(delta)` moves an
 * item's offset to `offset - delta`, hence the correction below lands the item exactly there.
 * (The previous form aligned to `viewportStartOffset`, i.e. pinned the entry row to the visual
 * bottom with the unread run below it uncomposed — the blank-reopen regression.)
 */
internal fun reverseItemTopAlignmentCorrection(
    itemOffset: Int,
    itemSize: Int,
    viewportEndOffset: Int,
): Int = itemOffset - (viewportEndOffset - itemSize)

internal enum class ChatTitleTarget { CHANNEL_INFO, NICK_DETAILS, NONE }

internal data class MaterializedChatTarget(
    val row: MessageEntity,
    val index: Int,
)

/** The title bar destination is a property of the buffer, not its display-name prefix. */
internal fun chatTitleTarget(type: BufferType?): ChatTitleTarget =
    when (type) {
        BufferType.CHANNEL -> ChatTitleTarget.CHANNEL_INFO
        BufferType.QUERY -> ChatTitleTarget.NICK_DETAILS
        BufferType.SERVER, null -> ChatTitleTarget.NONE
    }

internal data class PagingTargetGeneration(
    val itemCount: Int,
    val placeholdersBefore: Int,
    val placeholdersAfter: Int,
    val firstLoadedId: Long?,
    val lastLoadedId: Long?,
)

internal fun relevantTargetLoadState(
    index: Int,
    loadedStart: Int,
    loadedEnd: Int,
    prepend: LoadState,
    append: LoadState,
): LoadState? =
    when {
        index < loadedStart -> prepend
        index >= loadedEnd -> append
        else -> null
    }

/** Snapshot only the requested position and the load direction capable of materializing it. */
internal fun targetMaterialization(
    items: LazyPagingItems<MessageEntity>,
    index: Int,
): TargetMaterialization<MessageEntity> {
    val itemCount = items.itemCount
    val snapshot = items.itemSnapshotList
    val loadedStart = snapshot.placeholdersBefore
    val loadedEnd = loadedStart + snapshot.items.size
    val directionalState =
        relevantTargetLoadState(
            index,
            loadedStart,
            loadedEnd,
            items.loadState.prepend,
            items.loadState.append,
        )
    val refresh = items.loadState.refresh
    return TargetMaterialization(
        item = pagingSnapshotItemOrNull(index, itemCount, items::peek),
        loading = refresh is LoadState.Loading || directionalState is LoadState.Loading,
        addressable = index in 0 until itemCount,
        failed = refresh is LoadState.Error || directionalState is LoadState.Error,
        generation =
            PagingTargetGeneration(
                itemCount = itemCount,
                placeholdersBefore = snapshot.placeholdersBefore,
                placeholdersAfter = snapshot.placeholdersAfter,
                firstLoadedId = snapshot.items.firstOrNull()?.id,
                lastLoadedId = snapshot.items.lastOrNull()?.id,
            ),
    )
}

/** Safely read a Paging snapshot that may be replaced between its count read and item lookup. */
internal inline fun <T> pagingSnapshotItemOrNull(
    index: Int,
    itemCount: Int,
    lookup: (Int) -> T?,
): T? {
    if (index !in 0 until itemCount) return null
    return try {
        lookup(index)
    } catch (_: IndexOutOfBoundsException) {
        null
    }
}

/**
 * Append [prefill] to [value], inserting a single space when the current text is non-empty and
 * doesn't already end in whitespace. Places the cursor at the end.
 */
fun appendPrefill(
    value: TextFieldValue,
    prefill: String,
): TextFieldValue {
    val current = value.text
    val sep = if (current.isNotEmpty() && !current.last().isWhitespace()) " " else ""
    val text = current + sep + prefill
    return TextFieldValue(
        text = text,
        selection =
            androidx.compose.ui.text
                .TextRange(text.length),
    )
}

/** Hand the raw message text to the system share sheet (mirrors the image viewer's share). */
private fun shareMessageText(
    context: android.content.Context,
    text: String,
) {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    context.startActivity(
        Intent.createChooser(intent, context.getString(R.string.chat_action_share_chooser)),
    )
}

/** Restore a buffer draft, then merge a one-shot mention prefill without losing the cursor. */
internal fun restoreComposerDraft(
    draft: String?,
    prefill: String?,
): TextFieldValue {
    val saved = draft.orEmpty()
    val value = TextFieldValue(saved, TextRange(saved.length))
    return prefill?.let { appendPrefill(value, it) } ?: value
}

/** Add the visible channel-reply prefix while preserving the current selection. */
fun prependReplyPrefix(
    value: TextFieldValue,
    sender: String,
): TextFieldValue {
    if (sender.isBlank()) return value
    val prefix = "$sender: "
    if (value.text.startsWith(prefix)) return value
    val text = prefix + value.text
    return value.copy(
        text = text,
        selection =
            androidx.compose.ui.text.TextRange(
                value.selection.start + prefix.length,
                value.selection.end + prefix.length,
            ),
    )
}

/** Apply the configured visible prefix consistently for every reply gesture. */
internal fun composerTextForReply(
    value: TextFieldValue,
    sender: String,
    bufferType: BufferType?,
    visibleReplyPrefix: Boolean,
): TextFieldValue =
    if (visibleReplyPrefix && bufferType == BufferType.CHANNEL) {
        prependReplyPrefix(value, sender)
    } else {
        value
    }

/**
 * Header subtitle: typing summary if anyone is typing, else a localized member count for channels.
 * Uses the [Context] typing overload and a plural for the count.
 */

/** A durable explicit-jump failure is the sole source of the not-loaded snackbar. */
internal fun shouldPresentUnresolvedEntrySnackbar(entryState: EntryPositionState): Boolean = entryState is EntryPositionState.Unresolved && entryState.messageUnavailable

/**
 * A completed REFRESH may still be followed by a Room/RemoteMediator APPEND. Do not decide entry
 * positioning from a transient empty window; only rows or a terminal empty append are conclusive.
 */
internal fun initialPagingPage(
    itemCount: Int,
    append: LoadState,
): InitialPagingPage =
    when {
        itemCount > 0 -> InitialPagingPage.RowsAvailable
        append is LoadState.Error -> InitialPagingPage.TerminalEmpty
        append is LoadState.NotLoading && append.endOfPaginationReached -> InitialPagingPage.TerminalEmpty
        else -> InitialPagingPage.Pending
    }

internal enum class InitialPagingPage { Pending, RowsAvailable, TerminalEmpty }

internal fun loadStateName(state: LoadState): String =
    when (state) {
        is LoadState.Loading -> "LOADING"
        is LoadState.NotLoading -> if (state.endOfPaginationReached) "DONE" else "IDLE"
        is LoadState.Error -> "ERROR"
    }

internal fun visibleReactionMsgids(
    items: LazyPagingItems<MessageEntity>,
    listState: androidx.compose.foundation.lazy.LazyListState,
): List<String> {
    val visible =
        listState.layoutInfo.visibleItemsInfo
            .map { it.index }
            .filter { it >= 0 && it < items.itemCount }
    val start: Int
    val endExclusive: Int
    if (visible.isEmpty()) {
        start = 0
        endExclusive = minOf(items.itemCount, REACTION_PREFETCH_ROWS * 2)
    } else {
        start = (visible.minOrNull() ?: 0).minus(REACTION_PREFETCH_ROWS).coerceAtLeast(0)
        endExclusive =
            ((visible.maxOrNull() ?: 0) + REACTION_PREFETCH_ROWS + 1)
                .coerceAtMost(items.itemCount)
    }
    if (start >= endExclusive) return emptyList()
    return (start until endExclusive)
        .asSequence()
        .mapNotNull { items.peek(it)?.msgid }
        .distinct()
        .take(MAX_VISIBLE_REACTION_MSGIDS)
        .toList()
}

internal fun composerNeedsMemberNicks(value: TextFieldValue): Boolean {
    val text = value.text
    if (text.startsWith("/") && !text.startsWith("//") && !text.contains(' ')) return false
    val token = nickTokenAt(text, value.selection.end) ?: return false
    val atPrefixed = token.start < text.length && text[token.start] == '@'
    return token.text.length >= 2 || atPrefixed
}

const val CHAT_HISTORY_SYNC_FAILURE_TAG = "chat_history_sync_indicator"
const val CHAT_HISTORY_SYNC_RETRY_TAG = "chat_history_sync_retry"
const val CHAT_TITLE_SYNC_SPINNER_TAG = "chat_title_sync_spinner"
const val CHAT_HISTORY_PARTIAL_CHIP_TAG = "chat_history_partial_chip"

/** Present exactly while the entry veil hides the timeline, so a test can wait the reveal out. */
const val CHAT_ENTRY_VEIL_TAG = "chat_entry_veil"

internal val HistorySyncStatus.isActive: Boolean
    get() = this == HistorySyncStatus.Queued || this == HistorySyncStatus.Syncing

/**
 * The chat's entire in-progress sync report: a spinner beside the title, matching the chat list's
 * per-row cue.
 *
 * It lives in the title bar rather than over the timeline precisely because a sync is not worth
 * interrupting a reader for — nothing is drawn over the conversation while one runs. There is
 * deliberately no appearance grace period: the micro fade turns a sub-100 ms sync into a faint
 * shimmer rather than a flash, and a grace timer would reintroduce the delay this cue removes.
 *
 * No liveRegion: a chat's sync restarts on every reconnect and routine prepend loads would spam
 * polite announcements. Only the failure pill, which asks for an action, announces itself.
 */
@Composable
internal fun ChatTitleSyncSpinner(
    status: HistorySyncStatus,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = status.isActive,
        modifier = modifier,
        enter = fadeIn(MotdMotion.microFadeIn),
        exit = fadeOut(MotdMotion.microFadeOut),
    ) {
        HistorySyncSpinner(
            contentDescription = stringResource(R.string.chatlist_sync_syncing),
            // Padding inside the visibility scope so a settled sync leaves no gap in the title.
            modifier =
                Modifier
                    .padding(start = 8.dp)
                    .testTag(CHAT_TITLE_SYNC_SPINNER_TAG),
        )
    }
}

/** Pins transient timeline chrome below the title bar without allowing the stacked layers to overlap. */
@Composable
internal fun BoxScope.TimelineTopOverlays(
    audioPlayer: @Composable () -> Unit,
    historyFailure: @Composable () -> Unit,
    staleChip: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Keep playback controls stationary when a delayed sync failure becomes visible.
        audioPlayer()
        historyFailure()
        // Column child, not a sibling overlay: the staleness chip must never cover the player.
        staleChip()
    }
}

/**
 * Advisory staleness only: a Partial pass left readable cached rows behind, so the chip states that
 * without offering an action. An empty timeline has nothing to qualify, and a Failed pass keeps the
 * retry on its pill, so neither renders this.
 */
internal fun showsStaleChip(
    status: HistorySyncStatus,
    timelineEmpty: Boolean,
): Boolean = status is HistorySyncStatus.Partial && !timelineEmpty

/** Quiet companion to the sync pill for a settled-but-incomplete history pass; tap to dismiss. */
@Composable
internal fun TimelineHistoryStaleChip(
    status: HistorySyncStatus,
    timelineEmpty: Boolean,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
) {
    AnimatedVisibility(
        visible = showsStaleChip(status, timelineEmpty),
        modifier =
            modifier
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag(CHAT_HISTORY_PARTIAL_CHIP_TAG),
        enter = fadeIn(MotdMotion.microFadeIn),
        exit = fadeOut(MotdMotion.microFadeOut),
    ) {
        val dismissLabel = stringResource(R.string.chat_history_partial_dismiss)
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier =
                Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .clickable(onClickLabel = dismissLabel, onClick = onDismiss),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_history_partial_chip),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = Icons.Default.Close,
                    // The clickable's label already announces the action; the icon is decorative.
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * A stable overlay so timeline inserts cannot move a failed sync's retry action.
 *
 * Failure is the only history-sync state that still interrupts the reader: it asks for a decision.
 * Progress is reported quietly by [ChatTitleSyncSpinner], and a settled-but-incomplete pass by
 * [TimelineHistoryStaleChip], so neither reaches this pill.
 */
@Composable
internal fun TimelineHistorySyncFailure(
    status: HistorySyncStatus,
    retryEnabled: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = status is HistorySyncStatus.Failed,
        modifier =
            modifier
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .testTag(CHAT_HISTORY_SYNC_FAILURE_TAG),
        enter = fadeIn(MotdMotion.microFadeIn) + scaleIn(MotdMotion.microFadeIn, initialScale = 0.96f),
        exit = fadeOut(MotdMotion.microFadeOut) + scaleOut(MotdMotion.microFadeOut, targetScale = 0.96f),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 3.dp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_history_sync_failed_inline),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Button(
                    onClick = onRetry,
                    enabled = retryEnabled,
                    modifier =
                        Modifier
                            .heightIn(min = 48.dp)
                            .testTag(CHAT_HISTORY_SYNC_RETRY_TAG),
                ) {
                    Text(stringResource(R.string.chat_retry))
                }
            }
        }
    }
}

internal sealed interface ChatSubtitleModel {
    data class Text(
        val value: String,
    ) : ChatSubtitleModel
}

internal fun chatSubtitle(
    state: ChatState,
    context: android.content.Context,
): String? = (chatSubtitleModel(state, context) as? ChatSubtitleModel.Text)?.value

internal fun chatSubtitleModel(
    state: ChatState,
    context: android.content.Context,
): ChatSubtitleModel? {
    if (state.isSidecar && !state.sidecarsEnabled) {
        return ChatSubtitleModel.Text(context.getString(R.string.sidecar_disabled_in_labs))
    }
    when (val connection = state.connState) {
        null -> return null

        IrcClientState.Connecting -> return ChatSubtitleModel.Text(context.getString(R.string.drawer_state_connecting))

        IrcClientState.Registering -> return ChatSubtitleModel.Text(context.getString(R.string.drawer_state_registering))

        IrcClientState.Disconnected -> return ChatSubtitleModel.Text(context.getString(R.string.drawer_state_disconnected))

        is IrcClientState.Failed -> return if (connection.fatal) {
            ChatSubtitleModel.Text(connection.reason)
        } else {
            ChatSubtitleModel.Text(context.getString(R.string.drawer_state_connecting))
        }

        is IrcClientState.Ready -> Unit
    }
    if (state.typingNicks.isNotEmpty()) {
        return ChatSubtitleModel.Text(typingText(context, state.typingNicks))
    }
    val buffer = state.buffer ?: return null
    val securityText =
        when (buffer.sidecarSecurity) {
            SidecarSecurityState.PLAINTEXT -> R.string.sidecar_security_plaintext
            SidecarSecurityState.E2EE_UNVERIFIED -> R.string.sidecar_security_unverified
            SidecarSecurityState.E2EE_VERIFIED -> R.string.sidecar_security_verified
            SidecarSecurityState.BLOCKED -> R.string.sidecar_security_blocked
            null -> null
        }
    if (securityText != null) return ChatSubtitleModel.Text(context.getString(securityText))
    return if (buffer.type == BufferType.CHANNEL && state.memberCount != null) {
        val n = state.memberCount
        ChatSubtitleModel.Text(context.resources.getQuantityString(R.plurals.chat_member_count, n, n))
    } else {
        null
    }
}

@Composable
internal fun ScrollToBottomFab(
    visible: Boolean,
    unread: Int,
    mentionPending: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // Keep the latest callbacks so the long-lived pointerInput gesture always dispatches to the
    // current mention/bottom resolution, even as mentionTarget updates between recompositions.
    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnLongClick by rememberUpdatedState(onLongClick)
    // Hold progress 0..1: fills a ring around the FAB while pressed, so the user can see how long
    // to hold before the long-press fires. The same value gently compresses the arrow so progress
    // and completion read as one continuous motion rather than two competing animations.
    val holdProgress = remember { Animatable(0f) }
    val ringColor = MaterialTheme.colorScheme.onPrimaryContainer

    fun settle() {
        scope.launch {
            holdProgress.animateTo(
                targetValue = 0f,
                animationSpec =
                    tween(
                        durationMillis = SCROLL_TO_BOTTOM_FAB_SETTLE_MS,
                        easing = FastOutSlowInEasing,
                    ),
            )
        }
    }

    fun fire() {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        settle()
        latestOnLongClick()
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(MotdMotion.microFadeIn) + scaleIn(MotdMotion.microFadeIn, initialScale = 0.96f),
        exit = fadeOut(MotdMotion.microFadeOut) + scaleOut(MotdMotion.microFadeOut, targetScale = 0.96f),
        modifier = modifier,
    ) {
        BadgedBox(
            badge = {
                // An unread @mention of our nick takes priority over the plain unread count: the
                // "@" badge signals the next tap stops at that mention before continuing to bottom.
                when {
                    mentionPending -> Badge { Text("@") }
                    unread > 0 -> Badge { Text(if (unread >= MAX_UNREAD_BADGE_COUNT) "99+" else "$unread") }
                }
            },
        ) {
            // A custom hold-to-fire gesture owns both tap and long-press: a quick tap performs the
            // mention-walk/bottom jump via onClick, while holding past HOLD_MS draws a filling
            // progress ring and then fires onLongClick (skip straight to newest). The semantic
            // onClick remains available to accessibility and non-touch input.
            FloatingActionButton(
                // Keep the semantic click functional for accessibility and non-touch input. The
                // custom pointer recognizer consumes physical releases before Surface sees them.
                onClick = latestOnClick,
                modifier =
                    Modifier
                        .testTag("chat_scroll_to_bottom_fab")
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                val holdJob =
                                    scope.launch {
                                        holdProgress.snapTo(0f)
                                        holdProgress.animateTo(
                                            1f,
                                            tween(
                                                durationMillis = SCROLL_TO_BOTTOM_FAB_HOLD_MS,
                                                easing = LinearEasing,
                                            ),
                                        )
                                    }
                                // Observe and consume the release before the FAB's internal Surface
                                // click so the physical tap dispatches exactly once through this path.
                                val releaseResult =
                                    withTimeoutOrNull(SCROLL_TO_BOTTOM_FAB_HOLD_MS.toLong()) {
                                        Result.success(waitForUpOrCancellation(PointerEventPass.Initial))
                                    }
                                holdJob.cancel()
                                when {
                                    releaseResult == null -> {
                                        // Held past the threshold: skip mentions, jump to newest.
                                        fire()
                                        // Swallow the trailing release so it doesn't leak to handlers
                                        // behind the FAB.
                                        waitForUpOrCancellation(PointerEventPass.Initial)?.consume()
                                    }

                                    releaseResult.getOrNull() != null -> {
                                        // Released early: settle the partial ring and perform a tap.
                                        releaseResult.getOrNull()?.consume()
                                        settle()
                                        latestOnClick()
                                    }

                                    else -> {
                                        // Leaving the gesture bounds or another recognizer taking over
                                        // cancels cleanly instead of being mistaken for a completed hold.
                                        settle()
                                    }
                                }
                            }
                        }.drawWithContent {
                            drawContent()
                            val progress = holdProgress.value
                            if (progress > 0f) {
                                val stroke = 3.dp.toPx()
                                val inset = stroke / 2 + 2.dp.toPx()
                                val diameter = minOf(size.width, size.height) - inset * 2
                                if (diameter > 0f) {
                                    val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                                    val arcSize = Size(diameter, diameter)
                                    drawArc(
                                        color = ringColor.copy(alpha = 0.22f * (progress * 4f).coerceAtMost(1f)),
                                        startAngle = -90f,
                                        sweepAngle = 360f,
                                        useCenter = false,
                                        topLeft = topLeft,
                                        size = arcSize,
                                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                                    )
                                    drawArc(
                                        color = ringColor,
                                        startAngle = -90f,
                                        sweepAngle = 360f * progress,
                                        useCenter = false,
                                        topLeft = topLeft,
                                        size = arcSize,
                                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                                    )
                                }
                            }
                        },
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.chat_scroll_to_bottom),
                    modifier = Modifier.scale(scrollToBottomFabIconScale(holdProgress.value)),
                )
            }
        }
    }
}

/**
 * Viewport-aware FAB wrapper. This is intentionally its own restart scope: the first visible index
 * changes repeatedly during a fling, while the expensive chat scaffold and lazy-list declaration do
 * not. Only this small badge subtree recomposes at message boundaries.
 *
 * When an unread @mention of our nick sits below the viewport, the badge shows "@" and a tap jumps
 * to the nearest such mention (recomputed each tap, so repeated taps walk through mentions newest-
 * to-oldest before falling through to the bottom). Otherwise the badge shows the unread count and a
 * tap scrolls to the newest row.
 */
@Composable
private fun ViewportScrollToBottomFab(
    listState: androidx.compose.foundation.lazy.LazyListState,
    readMarker: io.github.trevarj.motd.data.db.TimelineAnchor?,
    visibilityPolicy: MessageVisibilityPolicy,
    countUnreadBelowViewport: suspend (Int, io.github.trevarj.motd.data.db.TimelineAnchor) -> Int,
    nearestUnreadMentionBelow: suspend (Int, io.github.trevarj.motd.data.db.TimelineAnchor) -> ChatPositionTarget?,
    visible: Boolean,
    onJumpMention: (ChatPositionTarget) -> Unit,
    onJumpNewest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstVisible by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex }
    }
    val latestCounter by rememberUpdatedState(countUnreadBelowViewport)
    val latestMentionJump by rememberUpdatedState(nearestUnreadMentionBelow)
    var unread by remember(readMarker, visibilityPolicy) { mutableIntStateOf(0) }
    var mentionTarget by remember(readMarker, visibilityPolicy) {
        mutableStateOf<ChatPositionTarget?>(null)
    }
    LaunchedEffect(firstVisible, readMarker, visibilityPolicy) {
        if (readMarker == null || firstVisible <= 0) {
            unread = 0
            mentionTarget = null
        } else {
            unread = latestCounter(firstVisible, readMarker).coerceIn(0, MAX_UNREAD_BADGE_COUNT)
            mentionTarget = latestMentionJump(firstVisible, readMarker)
        }
    }
    val pending = mentionTarget
    // A tap follows the nearest pending @mention (the walk) before falling through to newest; a
    // long-press skips the walk and always goes to newest. Routing lives in a pure helper so it is
    // unit-testable without composition.
    val dispatch: (Boolean) -> Unit = { longPress ->
        when (val jump = scrollToBottomFabJump(longPress, pending)) {
            is ScrollToBottomFabJump.Mention -> onJumpMention(jump.target)
            ScrollToBottomFabJump.Newest -> onJumpNewest()
        }
    }
    ScrollToBottomFab(
        visible = visible,
        unread = unread,
        mentionPending = pending != null,
        onClick = { dispatch(false) },
        onLongClick = { dispatch(true) },
        modifier = modifier,
    )
}

@Preview
@Composable
private fun ChatContentPreview() {
    MotdTheme {
        ChatContentPreviewBody()
    }
}

@Preview(name = "Conversation 140% + large system font", fontScale = 1.5f)
@Composable
private fun ChatContentLargeTextPreview() {
    MotdTheme {
        ChatContentPreviewBody(conversationFontScalePercent = 140)
    }
}

@Composable
internal fun VoiceComposerPanel(
    state: VoiceMessageUiState,
    playbackState: AudioPlaybackState,
    onDelete: () -> Unit,
    onCancelRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onSend: () -> Unit,
    onPreview: (AudioAttachment) -> Unit,
    onPreviewSeek: (AudioAttachment, Long) -> Unit,
    onToggleEncryption: () -> Unit,
    onDestinationSelected: (io.github.trevarj.motd.attachment.PasteBackendConfig?) -> Unit,
    onErrorDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var destinationSheet by remember { mutableStateOf(false) }
    // Hold the last non-null panel models so the exit animation still has content to draw after
    // the state field clears; live fields (state.progress, playbackState) keep reading current
    // state as before.
    var lastRecording by remember { mutableStateOf(state.recording) }
    state.recording?.let { lastRecording = it }
    var lastStaged by remember { mutableStateOf(state.staged) }
    state.staged?.let { lastStaged = it }
    AnimatedVisibility(
        visible = state.recording != null,
        enter =
            expandVertically(animationSpec = MotdMotion.contentSize, expandFrom = Alignment.Top) +
                fadeIn(MotdMotion.microFadeIn),
        exit =
            shrinkVertically(animationSpec = MotdMotion.contentSize, shrinkTowards = Alignment.Top) +
                fadeOut(MotdMotion.microFadeOut),
    ) {
        val recording = lastRecording ?: return@AnimatedVisibility
        Surface(
            modifier =
                modifier
                    .fillMaxWidth()
                    .testTag("voice_recording_panel")
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription =
                            if (recording.locked) {
                                "Recording locked. Cancel recording or stop and review."
                            } else {
                                "Recording. Slide left to cancel or swipe up to lock."
                            }
                    },
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 3.dp,
        ) {
            Column {
                HorizontalDivider(thickness = Dp.Hairline, color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Mic, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (recording.locked) "Recording locked" else "Recording",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (recording.locked) {
                                "${formatAudioDuration(recording.elapsedMs)} · Tap stop to review"
                            } else {
                                "${formatAudioDuration(recording.elapsedMs)} · Slide left to cancel"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (recording.locked) {
                        IconButton(onClick = onCancelRecording, modifier = Modifier.testTag("voice_cancel_locked")) {
                            Icon(Icons.Filled.Delete, "Cancel recording")
                        }
                        IconButton(onClick = onStopRecording, modifier = Modifier.testTag("voice_stop_locked")) {
                            Icon(Icons.Filled.Stop, "Stop and review recording")
                        }
                    } else {
                        Column(
                            // Match IconButton's 48 dp slot so locking swaps controls without
                            // changing the recording strip's height.
                            modifier = Modifier.size(48.dp).testTag("voice_lock_hint"),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(20.dp))
                            Text("Swipe up", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
    AnimatedVisibility(
        visible = state.staged != null,
        enter =
            expandVertically(animationSpec = MotdMotion.contentSize, expandFrom = Alignment.Top) +
                fadeIn(MotdMotion.microFadeIn),
        exit =
            shrinkVertically(animationSpec = MotdMotion.contentSize, shrinkTowards = Alignment.Top) +
                fadeOut(MotdMotion.microFadeOut),
    ) {
        val staged = lastStaged ?: return@AnimatedVisibility
        val progress = state.progress
        val destination = staged.destination
        val preview =
            remember(staged.file, staged.durationMs, staged.mimeType, staged.sizeBytes) {
                AudioAttachment(
                    url = staged.file.toURI().toString(),
                    title = "Voice message",
                    mimeType = staged.mimeType,
                    durationMs = staged.durationMs,
                    sizeBytes = staged.sizeBytes,
                    voice = true,
                )
            }
        val previewActive = playbackState.activeId == preview.playbackId
        val previewPlaying = previewActive && playbackState.playing
        val previewDurationMs = playbackState.durationMs?.takeIf { previewActive && it > 0 } ?: staged.durationMs
        val previewPositionMs = playbackState.positionMs.takeIf { previewActive } ?: 0L
        Surface(
            modifier = modifier.fillMaxWidth().testTag("voice_preview_panel"),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 3.dp,
        ) {
            Column {
                HorizontalDivider(thickness = Dp.Hairline, color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Mic, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Voice message", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${formatAudioDuration(staged.durationMs)} · ${staged.mimeType} · ${formatBytes(staged.sizeBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onPreview(preview) }, enabled = progress == null, modifier = Modifier.testTag("voice_preview_play")) {
                            Icon(if (previewPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (previewPlaying) "Pause" else "Play")
                        }
                        IconButton(onClick = onDelete, enabled = progress == null, modifier = Modifier.testTag("voice_delete")) {
                            Icon(Icons.Filled.Delete, "Delete")
                        }
                    }
                    WaveformScrubber(
                        value = (previewPositionMs.toFloat() / previewDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f),
                        onValueChange = { fraction ->
                            onPreviewSeek(preview, (fraction * previewDurationMs).toLong())
                        },
                        onValueChangeFinished = {},
                        seed = preview.playbackId,
                        enabled = progress == null && previewActive && !playbackState.loading,
                        bufferedValue =
                            if (previewActive && previewDurationMs > 0) {
                                (playbackState.bufferedMs.toFloat() / previewDurationMs).coerceIn(0f, 1f)
                            } else {
                                0f
                            },
                        waveform = staged.waveform,
                        modifier = Modifier.fillMaxWidth().testTag("voice_preview_scrubber"),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lock, null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Encrypt upload", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                if (staged.encrypted) {
                                    "The host cannot listen. IRC servers and bouncers can see the key in the link."
                                } else {
                                    "Standard audio link. The host and anyone with the link can play it in any client."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = staged.encrypted,
                            onCheckedChange = { onToggleEncryption() },
                            enabled = progress == null,
                            modifier = Modifier.testTag("voice_encryption_toggle"),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                destination?.backend?.label ?: "Soju file host",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                destination?.let(::backendRetention)
                                    ?: "Uses the file host advertised by this IRC network",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { destinationSheet = true }, enabled = progress == null, modifier = Modifier.testTag("voice_destination")) {
                            Text("Change")
                        }
                    }
                    when (progress) {
                        is VoiceSendProgress.Uploading -> {
                            if (progress.totalBytes != null && progress.totalBytes > 0) {
                                LinearProgressIndicator(
                                    progress = { (progress.bytesSent.toFloat() / progress.totalBytes).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }

                        is VoiceSendProgress.Complete,
                        null,
                        -> {
                            Unit
                        }
                    }
                    Button(onClick = onSend, enabled = progress == null, modifier = Modifier.fillMaxWidth().testTag("voice_send")) {
                        Icon(Icons.Outlined.CloudUpload, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Send")
                    }
                }
            }
        }
    }
    // The sheet stays keyed to the live staged value (not the held one) so it can never outlive
    // the message it configures.
    state.staged?.let { staged ->
        if (destinationSheet) {
            VoiceDestinationSheet(
                staged = staged,
                config =
                    staged.destination ?: io.github.trevarj.motd.attachment
                        .PasteBackendConfig(),
                onSelect = {
                    destinationSheet = false
                    onDestinationSelected(it)
                },
                onDismiss = { destinationSheet = false },
            )
        }
    }
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = onErrorDismissed,
            title = { Text("Voice message") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = onErrorDismissed) { Text("OK") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceDestinationSheet(
    staged: StagedVoiceMessage,
    config: io.github.trevarj.motd.attachment.PasteBackendConfig,
    onSelect: (io.github.trevarj.motd.attachment.PasteBackendConfig?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Voice destination", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            androidx.compose.material3.ListItem(
                headlineContent = { Text("Soju file host", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Use the file host advertised by this IRC network") },
                modifier = Modifier.clickable { onSelect(null) },
            )
            uploadDestinations(staged.source, config).forEach { destination ->
                androidx.compose.material3.ListItem(
                    headlineContent = { Text(destination.label, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(backendRetention(destination.config)) },
                    modifier = Modifier.clickable { onSelect(destination.config) },
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * Persistent banner shown when the user has parted the current channel (locally or via a
 * bouncer-reflected self-PART). Disables the composer and offers a one-tap rejoin so a message
 * never silently disappears into a channel the user is no longer a member of.
 */
@Composable
private fun PartedChannelBanner(
    channel: String,
    onRejoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("chat_parted_banner"),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.chat_parted_banner_text, channel),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            TextButton(onClick = onRejoin, modifier = Modifier.testTag("chat_parted_rejoin")) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Login,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(stringResource(R.string.chat_parted_banner_rejoin))
            }
        }
    }
}
