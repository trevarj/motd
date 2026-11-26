package io.github.trevarj.motd.ui.chat

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BadParcelableException
import android.os.Trace
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
import io.github.trevarj.motd.ai.AiFeature
import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.UploadRecord
import io.github.trevarj.motd.attachment.advertisedSojuFileHostUrl
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioCacheStatus
import io.github.trevarj.motd.audio.AudioMetadata
import io.github.trevarj.motd.audio.AudioPlaybackOrigin
import io.github.trevarj.motd.audio.AudioPlaybackRequest
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.audio.AudioWaveform
import io.github.trevarj.motd.audio.CachedAudioMetadata
import io.github.trevarj.motd.audio.ReadAloudSelection
import io.github.trevarj.motd.audio.ReadAloudState
import io.github.trevarj.motd.audio.ReadAloudVoices
import io.github.trevarj.motd.audio.VoiceSendProgress
import io.github.trevarj.motd.audio.formatAudioDuration
import io.github.trevarj.motd.avatar.ConversationAvatarOutcome
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.DccDirection
import io.github.trevarj.motd.data.db.DccTransferEntity
import io.github.trevarj.motd.data.db.DccTransferState
import io.github.trevarj.motd.data.db.JoinedChannelRow
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.db.isSchedulableMessage
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.CompactNickBrackets
import io.github.trevarj.motd.data.prefs.ComposerStyle
import io.github.trevarj.motd.data.prefs.EffectSurface
import io.github.trevarj.motd.data.prefs.FoolPreferences
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NicknameTapAction
import io.github.trevarj.motd.data.prefs.PaletteDocument
import io.github.trevarj.motd.data.prefs.WallpaperChatChoiceKind
import io.github.trevarj.motd.data.prefs.WallpaperRenderSpec
import io.github.trevarj.motd.data.prefs.decodeWallpaperChatChoice
import io.github.trevarj.motd.data.prefs.matchesConfiguredNick
import io.github.trevarj.motd.data.prefs.resolveWallpaper
import io.github.trevarj.motd.data.repo.ViewportRefreshAnchor
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.dcc.EbooksResultCache
import io.github.trevarj.motd.diagnostics.AutoFollowTrace
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.dickord.LocalDickordLabsEnabled
import io.github.trevarj.motd.dickord.decodeDickordChannelDescriptor
import io.github.trevarj.motd.dickord.dickordNickLabel
import io.github.trevarj.motd.dickord.isDickordPortalConversation
import io.github.trevarj.motd.dickord.isDickordRelayNick
import io.github.trevarj.motd.dickord.isDiscordDirectMessage
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.canSendReactionTags
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.format.containsIrcFormatting
import io.github.trevarj.motd.irc.format.plainIrcText
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.service.ChannelWatchDuration
import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.service.NotificationMode
import io.github.trevarj.motd.ui.ai.AiCustomStylesSheet
import io.github.trevarj.motd.ui.ai.AiTextSheet
import io.github.trevarj.motd.ui.ai.AiTextSource
import io.github.trevarj.motd.ui.ai.AiTextUiState
import io.github.trevarj.motd.ui.ai.AiTextViewModel
import io.github.trevarj.motd.ui.ai.source
import io.github.trevarj.motd.ui.channelinfo.ModeCatalog
import io.github.trevarj.motd.ui.components.AudioMiniPlayer
import io.github.trevarj.motd.ui.components.AutocompletePanel
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.components.AvatarEditorSheet
import io.github.trevarj.motd.ui.components.ChannelNotificationPresentation
import io.github.trevarj.motd.ui.components.ChannelNotificationSheet
import io.github.trevarj.motd.ui.components.Composer
import io.github.trevarj.motd.ui.components.ComposerReply
import io.github.trevarj.motd.ui.components.HistorySyncModeSheet
import io.github.trevarj.motd.ui.components.HistorySyncSpinner
import io.github.trevarj.motd.ui.components.LocalMessageWallpaperBackdrop
import io.github.trevarj.motd.ui.components.LocalRemoteAvatars
import io.github.trevarj.motd.ui.components.ReadAloudPlayer
import io.github.trevarj.motd.ui.components.ReadAloudVoiceOptions
import io.github.trevarj.motd.ui.components.RemoteAvatarState
import io.github.trevarj.motd.ui.components.SurfaceBackdropPage
import io.github.trevarj.motd.ui.components.TypingIndicatorRow
import io.github.trevarj.motd.ui.components.WaveformScrubber
import io.github.trevarj.motd.ui.components.avatarsHidden
import io.github.trevarj.motd.ui.components.captureSurfaceBackdrop
import io.github.trevarj.motd.ui.components.densityLabel
import io.github.trevarj.motd.ui.components.effectSurface
import io.github.trevarj.motd.ui.components.historySyncModeLabel
import io.github.trevarj.motd.ui.components.notificationIcon
import io.github.trevarj.motd.ui.components.recordMessageWallpaper
import io.github.trevarj.motd.ui.components.rememberMessageWallpaperBackdrop
import io.github.trevarj.motd.ui.components.rememberSurfaceBackdropSource
import io.github.trevarj.motd.ui.components.summary
import io.github.trevarj.motd.ui.components.typingText
import io.github.trevarj.motd.ui.settings.FoolSettingsSheet
import io.github.trevarj.motd.ui.settings.foolEditorPreferences
import io.github.trevarj.motd.ui.share.PendingShare
import io.github.trevarj.motd.ui.theme.ConversationTypography
import io.github.trevarj.motd.ui.theme.LocalAdjustPaletteReadability
import io.github.trevarj.motd.ui.theme.LocalCompactNickBrackets
import io.github.trevarj.motd.ui.theme.LocalMessageBackgroundBlur
import io.github.trevarj.motd.ui.theme.LocalMessageBackgroundOpacityPercent
import io.github.trevarj.motd.ui.theme.LocalSpacing
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdSizes
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import io.github.trevarj.motd.ui.theme.ensureContrast
import io.github.trevarj.motd.ui.theme.readableForeground
import io.github.trevarj.motd.ui.theme.readableTextButtonColors
import io.github.trevarj.motd.ui.theme.spacingFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import io.github.trevarj.motd.ui.components.EffectDropdownMenu as DropdownMenu
import io.github.trevarj.motd.ui.components.EffectFloatingActionButton as FloatingActionButton
import io.github.trevarj.motd.ui.components.EffectModalBottomSheet as ModalBottomSheet
import io.github.trevarj.motd.ui.components.EmojiSnackbarHost as SnackbarHost
import io.github.trevarj.motd.ui.components.EmojiText as Text
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

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
    val ebooksRoomId: Long? = null,
    val ebooksNetworkId: Long? = null,
)

private data class MessageActionRequest(
    val message: MessageEntity,
    val initialReactionsTab: Boolean = false,
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
    onOpenPalette: (PaletteDocument, String) -> Unit,
    onOpenWallpaperWorkshop: (Long) -> Unit,
    onBack: () -> Unit = {},
    showBack: Boolean = true,
    onOpenChannelInfo: (Long) -> Unit = {},
    onOpenSearch: (Long) -> Unit = {},
    onOpenMedia: (url: String, networkId: Long?, isVideo: Boolean) -> Unit = { _, _, _ -> },
    // /msg and /query resolve-or-create a QUERY buffer via the VM, then navigate to it.
    onOpenBuffer: (Long) -> Unit = {},
    onOpenAudioOrigin: (AudioPlaybackOrigin) -> Unit = {},
    // Round 5: /list opens the channel browser. Body lands in WP-V3.
    onOpenChannelList: (Long) -> Unit = {},
    onOpenAccountSetup: (Long) -> Unit = {},
    onOpenNetworkSettings: (Long) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
    voiceViewModel: VoiceMessageViewModel = hiltViewModel(),
    aiTextViewModel: AiTextViewModel = hiltViewModel(),
    onOpenAiSetup: () -> Unit = {},
    onOpenConversationList: (() -> Unit)? = null,
    pageCache: io.github.trevarj.motd.data.repo.ChatPageCache? = null,
    presentations: ChatPresentationRegistry? = null,
    onOpenScheduledMessages: ((Long) -> Unit)? = null,
    onOpenNetworkTools: ((Long) -> Unit)? = null,
) {
    val scheduledComposer =
        if (onOpenScheduledMessages == null) {
            null
        } else {
            val scheduledViewModel = hiltViewModel<ScheduledMessagesViewModel>()
            ScheduledComposerActions(
                capture = viewModel::captureScheduledDraft,
                enqueue = { draft, timing ->
                    viewModel.scheduleDraft(draft) { persisted -> scheduledViewModel.enqueue(draft, persisted, timing) }
                },
                openList = onOpenScheduledMessages,
            )
        }
    val dickordEnabled = LocalDickordLabsEnabled.current
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
    val onConversationList =
        onOpenConversationList?.let { navigate ->
            remember(focusManager, keyboardController, navigate) {
                {
                    dismissKeyboardBeforeNavigating(
                        clearFocus = focusManager::clearFocus,
                        hideKeyboard = { keyboardController?.hide() },
                        onBack = navigate,
                    )
                }
            }
        }
    var mentionRequest by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var composerPrefillRequest by remember { mutableStateOf<Pair<Long, List<ComposerPrefill>>?>(null) }
    var avatarEditorOpen by rememberSaveable { mutableStateOf(false) }
    var avatarUploadOpen by rememberSaveable { mutableStateOf(false) }
    var inviteNick by rememberSaveable { mutableStateOf<String?>(null) }
    var inviteChannelOpen by rememberSaveable { mutableStateOf(false) }
    var editingFool by rememberSaveable { mutableStateOf<String?>(null) }
    // Prefills aimed at the already-open conversation are drained without another screen entry.
    LaunchedEffect(viewModel) {
        viewModel.composerPrefills.collect { composerPrefillRequest = System.nanoTime() to it }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ebooksHelperRoomId by viewModel.ebooksHelperRoomId.collectAsStateWithLifecycle()
    val ebooksDccOffers by viewModel.ebooksDccOffers.collectAsStateWithLifecycle()
    val externalTranslations by viewModel.externalTranslations.collectAsStateWithLifecycle()
    val externalTranslationInFlight by viewModel.externalTranslationInFlight.collectAsStateWithLifecycle()
    val forwardState by viewModel.forwardState.collectAsStateWithLifecycle()
    val forwardTargets by viewModel.forwardTargets.collectAsStateWithLifecycle()
    val dccSendFailure by viewModel.dccSendFailure.collectAsStateWithLifecycle()
    val dccSendReview by viewModel.dccSendReview.collectAsStateWithLifecycle()
    val preparingDccSend by viewModel.preparingDccSend.collectAsStateWithLifecycle()
    // The visit owns the lease; the root host owns native items and viewport across its disposal.
    // A pending descriptor is not an empty PagingData and must never reset a retained viewport.
    val pagingGeneration by androidx.compose.runtime.produceState<ChatPagingGeneration?>(null, viewModel, pageCache) {
        val generations = pageCache?.let(viewModel::retainedMessageGenerations) ?: viewModel.messageGeneration
        withContext(Dispatchers.Main.immediate) {
            generations.collect { value = it }
        }
    }
    val presentationLease = pagingGeneration?.lease
    val presentationRevision = presentationLease?.changes?.collectAsState()?.value ?: 0L
    val presentation = presentationLease?.let { presentations?.find(it) }
    val items =
        if (presentationLease != null) {
            presentation?.items
        } else {
            pagingGeneration?.pages?.collectAsLazyPagingItems(Dispatchers.Main.immediate)
        }
    val memberNicks by viewModel.memberNicks.collectAsStateWithLifecycle()
    val knownNicks by viewModel.knownNicks.collectAsStateWithLifecycle()
    val channelSenderPrefixes by viewModel.channelSenderPrefixes.collectAsStateWithLifecycle()
    val joinedChannels by viewModel.joinedChannels.collectAsStateWithLifecycle()
    val channelNotifications by viewModel.channelNotifications.collectAsStateWithLifecycle()
    val readAloudState by viewModel.readAloudState.collectAsStateWithLifecycle()
    val readAloudConfig by viewModel.readAloudConfig.collectAsStateWithLifecycle()
    val readAloudVoices by viewModel.readAloudVoices.collectAsStateWithLifecycle()
    val voiceState by voiceViewModel.state.collectAsStateWithLifecycle()
    val aiTextState by aiTextViewModel.state.collectAsStateWithLifecycle()
    val aiLabsState by aiTextViewModel.labsState.collectAsStateWithLifecycle()
    var managingAiStyles by remember { mutableStateOf(false) }
    LaunchedEffect(aiTextState) {
        if (aiTextState !is AiTextUiState.Choosing) managingAiStyles = false
    }
    val context = LocalContext.current
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
    DisposableEffect(lifecycleOwner, viewModel, voiceViewModel, aiTextViewModel) {
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
                    aiTextViewModel.close()
                    viewModel.invalidateAiDraftTransform()
                },
            )
        val observer = LifecycleEventObserver { _, event -> gate.onEvent(event) }
        lifecycleOwner.lifecycle.addObserver(observer)
        gate.sync(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            gate.dispose()
            aiTextViewModel.close()
            viewModel.invalidateAiDraftTransform()
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
    val entryPreparationReady by viewModel.entryPreparationReady.collectAsStateWithLifecycle()
    val entryPositionIntent by viewModel.entryPositionIntent.collectAsStateWithLifecycle()
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
    val composerUpload by viewModel.composerUpload.collectAsStateWithLifecycle()
    val outgoingFlight by viewModel.outgoingFlight.collectAsStateWithLifecycle()
    LaunchedEffect(composerDraft.revision, state.buffer?.id, aiTextState) {
        val source = aiTextState.source() as? AiTextSource.Composer
        if (source != null && (source.draft.revision != composerDraft.revision || state.buffer?.id?.let { it != source.draft.roomId } == true)) {
            aiTextViewModel.close()
            viewModel.invalidateAiDraftTransform()
        }
    }
    // Timeline behavioral settings collected separately from ChatState.
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hiddenFoolsRevealed by viewModel.hiddenFoolsRevealed.collectAsStateWithLifecycle()
    val expandAllFools by viewModel.expandAllFools.collectAsStateWithLifecycle()
    val contentPreviews by viewModel.contentPreviews.collectAsStateWithLifecycle()
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
    val wallpaperOverride = state.buffer?.takeUnless { isServerBuffer }?.wallpaperOverrideEncoded
    val wallpaperChoice = remember(wallpaperOverride) { decodeWallpaperChatChoice(wallpaperOverride) }
    val wallpaper =
        remember(appearance.wallpaper, appearance.wallpaperLibrary, appearance.wallpaperLibraryReadError, wallpaperChoice) {
            resolveWallpaper(appearance, wallpaperChoice)
        }
    val wallpaperRevealed = remember(viewModel) { mutableStateOf(false) }

    if (items == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val wallpaperName =
        when (wallpaperChoice?.kind) {
            WallpaperChatChoiceKind.NONE -> wallpaperLabel(ChatWallpaperPreset.NONE)
            WallpaperChatChoiceKind.PATTERN -> wallpaperChoice.pattern?.let { wallpaperPatternLabel(it, appearance) }
            null -> null
        }
    ChatContent(
        state = state,
        ebooksHelperRoomId = ebooksHelperRoomId,
        ebooksDccOffers = ebooksDccOffers,
        items = items,
        presentation = presentation,
        presentationLease = presentationLease,
        presentationRevision = presentationRevision,
        entryFallback = pagingGeneration?.fallback,
        entryPreparationReady = entryPreparationReady,
        entryPositionIntent = entryPositionIntent,
        canAdoptRetainedFallback = viewModel::canAdoptRetainedFallback,
        onAdoptRetainedFallback = viewModel::adoptRetainedFallback,
        composerEnabled = (!isServerBuffer || state.connState is IrcClientState.Ready) && !state.parted,
        friends = settings.friends,
        fools = settings.fools,
        foolsMode = settings.foolsMode,
        foolPreferences = settings.foolPreferences,
        presentedVisibilitySpec = pagingGeneration?.spec,
        showRedactedMessages = settings.showRedactedMessages,
        hiddenFoolsRevealed = hiddenFoolsRevealed,
        expandAllFools = expandAllFools,
        onExpandAllFoolsChange = viewModel::setExpandAllFools,
        chatWallpaper = wallpaper,
        wallpaperRevealed = wallpaperRevealed,
        onOpenWallpaperWorkshop = onOpenWallpaperWorkshop,
        wallpaperName = wallpaperName,
        collapseTitleBar = appearance.collapseTitleBar,
        conversationFontScalePercent = appearance.conversationFontScalePercent,
        messageSpacing = appearance.messageSpacing,
        bubbleCornerStyle = appearance.bubbleCornerStyle,
        composerStyle = appearance.composerStyle,
        showComposerEmoji = settings.showComposerEmoji,
        typingIndicatorPlacement = settings.typingIndicatorPlacement,
        showComposerFormattingTools = settings.showComposerFormattingTools,
        visibleReplyPrefix = replyConfig.visibleChannelPrefix,
        swipeToReplyEnabled = replyConfig.swipeToReplyEnabled,
        showImages = contentPreviews.showImages,
        showLinkPreviews = contentPreviews.showLinkPreviews,
        reactionChips = reactionChipsForMessage,
        replyPreview = viewModel::replyPreview,
        onReplyPreviewClick = viewModel::jumpToRepliedMessage,
        dccTransfer = viewModel::dccTransfer,
        onAcceptDccTransfer = viewModel::acceptDccTransfer,
        onRejectDccTransfer = viewModel::rejectDccTransfer,
        onRemoveDccTransfer = viewModel::removeDccTransfer,
        onSaveDccToDownloads = viewModel::saveDccToDownloads,
        onSendDccFile = viewModel::sendDccFile,
        preparingDccSend = preparingDccSend,
        memberNicks = memberNicks,
        knownNicks = knownNicks,
        channelSenderPrefixes = channelSenderPrefixes,
        identityRules = identityRules,
        unreadEntrySnapshot = unreadEntrySnapshot,
        readMarkerLive = localReadAnchor,
        rawNewestAnchor = rawNewestAnchor,
        onMarkRead = viewModel::markRead,
        viewportReadEnabled = destinationResumed && entryPreparationReady && !viewportReadHold,
        countUnreadBelowViewport = viewModel::countUnreadBelowViewport,
        nearestUnreadMentionBelow = viewModel::nearestUnreadMentionBelow,
        onBack = onHeaderBack,
        showBack = showBack,
        onOpenConversationList = onConversationList,
        // Channel titles open Channel Info; query titles describe the other user. SERVER buffers
        // have neither channel nor peer details, so their title remains inert.
        onOpenChannelInfo = { id ->
            when (titleTarget) {
                ChatTitleTarget.CHANNEL_INFO -> {
                    onOpenChannelInfo(id)
                }

                ChatTitleTarget.NICK_DETAILS -> {
                    state.buffer?.displayName?.let { rawNick ->
                        viewModel.openNickSheet(
                            rawNick,
                            loadIrcDetails = !(dickordEnabled && isDickordRelayNick(rawNick)),
                        )
                    }
                }

                ChatTitleTarget.NONE -> {}
            }
        },
        onOpenSearch = onOpenSearch,
        onOpenConversationLinks = viewModel::openConversationLinks,
        onOpenMedia = { url, isVideo -> onOpenMedia(url, state.buffer?.networkId, isVideo) },
        onOpenPalette = onOpenPalette,
        onInviteUser = {
            viewModel.ensureMembersObserved()
            inviteChannelOpen = true
        },
        channelNotifications = channelNotifications,
        onChannelNotificationMode = viewModel::setChannelNotificationMode,
        onStartWatch = viewModel::startWatch,
        onStopWatch = viewModel::stopWatch,
        nickNormalizer = nickNormalizer,
        onSubmit = { raw -> viewModel.submit(raw, onOpenBuffer = onOpenBuffer, onOpenChannelList = onOpenChannelList) },
        onTyping = viewModel::sendTyping,
        onSetReply = { message ->
            viewModel.setReply(
                message,
                checkRoster =
                    !(dickordEnabled && message != null && isDickordRelayNick(message.sender)),
            )
        },
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
        onVoiceTranscribe = voiceViewModel::transcribe,
        onVoiceTranscriptionCancel = voiceViewModel::cancelTranscription,
        voiceEnabled =
            !isServerBuffer && !state.parted &&
                (
                    settings.showVoiceMessageButton ||
                        (dickordEnabled && state.buffer?.let { isDickordPortalConversation(it.type, it.displayName) } == true)
                ),
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
        attachmentViewModel = hiltViewModel(),
        composerDraft = composerDraft,
        composerUpload = composerUpload,
        onBeginComposerUpload = viewModel::beginComposerUpload,
        scheduledComposer = scheduledComposer,
        aiTextState = aiTextState,
        aiTextEnabled = AiFeature.TEXT_TOOLS in aiLabsState.enabledFeatures,
        onTranslateMessage =
            if (AiFeature.TEXT_TOOLS in aiLabsState.enabledFeatures) {
                { message -> aiTextViewModel.openStoredMessage(message.bufferId, message.id) }
            } else {
                null
            },
        externalTranslations = externalTranslations,
        externalTranslationInFlight = externalTranslationInFlight,
        onBeginExternalTranslation = viewModel::beginExternalTranslation,
        onCompleteExternalTranslation = viewModel::completeExternalTranslation,
        onAbortExternalTranslationLaunch = viewModel::abortExternalTranslationLaunch,
        onDismissExternalTranslation = viewModel::dismissExternalTranslation,
        forwardState = forwardState,
        forwardTargets = forwardTargets,
        onOpenForward = viewModel::openForward,
        onSelectForwardDestination = viewModel::selectForwardDestination,
        onChangeForwardDestination = viewModel::changeForwardDestination,
        onForwardIncludeSenderChanged = viewModel::setForwardIncludeSender,
        onConfirmForward = viewModel::confirmForward,
        onDismissForward = viewModel::dismissForward,
        onAiComposer = {
            val snapshot = viewModel.beginAiDraftTransform()
            if (snapshot != null) aiTextViewModel.openComposer(snapshot) else aiTextViewModel.showUnavailable()
        },
        onDraftChanged = viewModel::saveDraft,
        outgoingFlight = outgoingFlight,
        onFlightSettled = viewModel::onFlightSettled,
        mentionPrefill = mentionRequest,
        composerPrefill = composerPrefillRequest,
        nicknameTapAction = settings.nicknameTapAction,
        jumpTarget = jumpTarget,
        initialTarget = initialTarget,
        entryState = entryState,
        timelineSeams = timelineSeams,
        onViewportRefreshAnchor = viewModel::reportViewportRefreshAnchor,
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
        onSenderClick = { rawNick ->
            viewModel.openNickSheet(
                rawNick,
                loadIrcDetails = !(dickordEnabled && isDickordRelayNick(rawNick)),
            )
        },
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
        onCompactNickBracketsOverride = viewModel::setCompactNickBracketsOverride,
        onConversationMessageBackgroundOpacitySelected = viewModel::setConversationMessageBackgroundOpacityOverride,
        onConversationMessageBackgroundBlurSelected = viewModel::setConversationMessageBackgroundBlurOverride,
        onPresenceModeSelected = viewModel::setPresenceModeOverride,
        onPresenceCustomKindSelected = viewModel::togglePresenceCustomKind,
        onHistorySyncModeSelected = viewModel::setHistorySyncModeOverride,
        diagnostics = viewModel.diagnostics,
        avatarEvents = viewModel.avatarEvents,
        onOpenNetworkSettings = onOpenNetworkSettings,
        readAloudState = readAloudState.takeIf { it.roomId == state.buffer?.id } ?: ReadAloudState(),
        readAloudConfig = readAloudConfig,
        readAloudVoices = readAloudVoices,
        onReadAloudToggle = viewModel::toggleReadAloud,
        onReadAloudPrevious = viewModel::readAloudPrevious,
        onReadAloudPauseResume = viewModel::readAloudPauseResume,
        onReadAloudSkip = viewModel::readAloudSkip,
        onReadAloudLatest = viewModel::readAloudLatest,
        onReadAloudMessage = viewModel::readAloudMessage,
        onReadAloudStop = viewModel::stopReadAloud,
        onReadAloudOptions = viewModel::openReadAloudOptions,
        onReadAloudSaveOptions = viewModel::saveReadAloudOptions,
        onReadAloudPreview = viewModel::previewReadAloud,
    )
    val conversationLinks by viewModel.conversationLinks.collectAsStateWithLifecycle()
    state.buffer?.takeIf { it.type == BufferType.CHANNEL || it.type == BufferType.QUERY }?.let { room ->
        conversationLinks?.let { links ->
            ConversationLinksSheet(
                state = links,
                networkId = room.networkId,
                showLinkPreviews = contentPreviews.showLinkPreviews,
                showImages = contentPreviews.showImages,
                cachedPreview = viewModel::cachedLinkPreview,
                onLoadOlder = viewModel::loadOlderConversationLinks,
                onJump = viewModel::jumpToConversationLink,
                onDismiss = viewModel::closeConversationLinks,
            )
        }
    }
    AiTextSheet(
        state = aiTextState,
        styles = aiLabsState.customStyles,
        target = aiLabsState.translationTarget,
        onGenerate = aiTextViewModel::generate,
        onTargetSelected = aiTextViewModel::selectTranslationTarget,
        onDismiss = {
            aiTextViewModel.close()
            viewModel.invalidateAiDraftTransform()
        },
        onOpenSetup = onOpenAiSetup,
        onManageStyles = { managingAiStyles = true },
        onApply =
            if (aiTextState.source() is AiTextSource.Composer) {
                { aiTextViewModel.applyComposerResult(viewModel::applyAiDraftTransform) }
            } else {
                null
            },
    )
    if (managingAiStyles && aiTextState is AiTextUiState.Choosing) {
        AiCustomStylesSheet(aiLabsState.customStyles, aiTextViewModel::upsertCustomStyle, aiTextViewModel::deleteCustomStyle, { managingAiStyles = false })
    }

    dccSendReview?.let { review ->
        DccSendReviewDialog(
            filename = review.filename,
            sizeBytes = review.sizeBytes,
            peerNick = review.peerNick,
            networkName = review.networkName,
            onConfirm = { viewModel.confirmDccSend(review) },
            onDismiss = { viewModel.dismissDccSendReview(review) },
        )
    }

    dccSendFailure?.let { failure ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissDccSendFailure(failure) },
            modifier =
                Modifier
                    .semantics { testTagsAsResourceId = true }
                    .testTag(if (failure.canSendOriginal) "chat_dcc_send_metadata_warning" else "chat_dcc_send_failure"),
            title = {
                Text(
                    stringResource(
                        if (failure.canSendOriginal) R.string.dcc_send_original_title else R.string.dcc_send_failure_title,
                    ),
                )
            },
            text = {
                Text(
                    if (failure.canSendOriginal) {
                        stringResource(R.string.dcc_send_original_body)
                    } else {
                        failure.message ?: stringResource(R.string.dcc_send_failure_body)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    colors = readableTextButtonColors(),
                    onClick = {
                        if (failure.canSendOriginal) {
                            viewModel.sendDccOriginal(failure)
                        } else {
                            viewModel.dismissDccSendFailure(failure)
                        }
                    },
                    modifier =
                        Modifier.testTag(if (failure.canSendOriginal) "chat_dcc_send_metadata_continue" else "chat_dcc_send_failure_close"),
                ) {
                    Text(stringResource(if (failure.canSendOriginal) R.string.dcc_send_review_original else R.string.action_ok))
                }
            },
            dismissButton = {
                if (failure.canSendOriginal) {
                    TextButton(onClick = { viewModel.dismissDccSendFailure(failure) }, colors = readableTextButtonColors()) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }

    editingFool?.let { nick ->
        val preferences = foolEditorPreferences(nick, settings.foolsMode, settings.fools, settings.foolPreferences, identityRules)
        LaunchedEffect(preferences) {
            if (preferences == null) editingFool = null
        }
        if (preferences != null) {
            FoolSettingsSheet(
                nick = nick,
                globalMode = settings.foolsMode,
                preferences = preferences,
                onChange = { viewModel.updateFoolPreferences(nick, it) },
                onDismiss = { editingFool = null },
            )
        }
    }

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
            relayIdentity = dickordEnabled && isDickordRelayNick(sheet.nick),
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
                mentionRequest = System.nanoTime() to sheet.nick
                viewModel.dismissNickSheet()
            },
            onToggleFriend = { viewModel.toggleFriend(sheet.nick) },
            onToggleFool = { viewModel.toggleFool(sheet.nick) },
            onOpenFoolSettings = {
                viewModel.dismissNickSheet()
                editingFool = sheet.nick
            },
            ignoreActions =
                sheet.ignore?.let { ignore ->
                    NickIgnoreActions(
                        state = ignore,
                        onToggle = { viewModel.setNickIgnored(ignore) },
                        onManage = onOpenNetworkTools?.let { open -> { viewModel.openNickIgnoreRules(ignore, open) } },
                    )
                },
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
        identity = "buffer:${state.buffer?.id}:${state.buffer?.displayName}",
        name = state.buffer?.displayName.orEmpty(),
        networkId = state.buffer?.networkId,
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
    if (avatarUploadOpen) {
        AttachmentSheets(
            open = avatarUploadOpen,
            currentDraft = "",
            networkId = state.buffer?.networkId,
            sojuFileHostUrl =
                (state.connState as? IrcClientState.Ready)
                    ?.isupport
                    ?.let(::advertisedSojuFileHostUrl),
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
            onOpenNetworkSettings = onOpenNetworkSettings,
        )
    }
}

@Composable
internal fun DccSendReviewDialog(
    filename: String,
    sizeBytes: Long,
    peerNick: String,
    networkName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("chat_dcc_send_review"),
        title = { Text(stringResource(R.string.dcc_send_review_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.dcc_send_review_file, filename), modifier = Modifier.testTag("chat_dcc_send_review_file"))
                Text(stringResource(R.string.dcc_send_review_size, sizeBytes), modifier = Modifier.testTag("chat_dcc_send_review_size"))
                Text(stringResource(R.string.dcc_send_review_peer, peerNick), modifier = Modifier.testTag("chat_dcc_send_review_peer"))
                Text(stringResource(R.string.dcc_send_review_network, networkName), modifier = Modifier.testTag("chat_dcc_send_review_network"))
                Text(stringResource(R.string.dcc_send_review_disclosure), modifier = Modifier.testTag("chat_dcc_send_review_disclosure"))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = readableTextButtonColors(),
                modifier = Modifier.testTag("chat_dcc_send_review_confirm"),
            ) {
                Text(stringResource(R.string.dcc_send_review_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = readableTextButtonColors(),
                modifier = Modifier.testTag("chat_dcc_send_review_cancel"),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

internal fun String.containsDraftUploadUrl(url: String): Boolean {
    val start = indexOf(url)
    if (start < 0) return false
    val end = start + url.length
    // The usual standalone link needs no parsing. Use chat's URL boundaries for prose punctuation.
    if ((start == 0 || this[start - 1].isWhitespace()) && (end == length || this[end].isWhitespace())) return true
    val text = if (containsIrcFormatting(this)) plainIrcText(this) else this
    return uriTokens(text).any { token ->
        val candidate = token.value
        candidate == url || (candidate.startsWith(url) && trimUrl(candidate) == url)
    }
}

@Composable
private fun UploadedImageDraftCard(record: UploadRecord) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("chat_composer_upload_preview"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UploadRecordPreview(record, Modifier.size(40.dp).testTag("chat_composer_upload_thumbnail"))
            Text(
                record.url,
                modifier = Modifier.weight(1f).testTag("chat_composer_upload_url"),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ChatContent(
    state: ChatState,
    items: LazyPagingItems<MessageEntity>,
    composerEnabled: Boolean,
    presentation: ChatPresentation? = null,
    presentationLease: io.github.trevarj.motd.data.repo.ChatPageCache.Lease? = null,
    presentationRevision: Long = 0L,
    entryFallback: RetainedEntryFallback? = null,
    entryPreparationReady: Boolean = true,
    entryPositionIntent: Long = 0L,
    canAdoptRetainedFallback: ((Long) -> Boolean)? = null,
    onAdoptRetainedFallback: ((ChatScrollPosition, Long) -> Boolean)? = null,
    ebooksHelperRoomId: Long? = null,
    ebooksDccOffers: List<DccTransferEntity> = emptyList(),
    onBack: () -> Unit,
    showBack: Boolean = true,
    onOpenChannelInfo: (Long) -> Unit,
    onOpenSearch: (Long) -> Unit,
    onOpenConversationLinks: (() -> Unit)? = null,
    onOpenMedia: (url: String, isVideo: Boolean) -> Unit,
    onOpenPalette: (PaletteDocument, String) -> Unit,
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
    onVoiceTranscribe: (AudioPlaybackRequest, Boolean) -> Unit = { _, _ -> },
    onVoiceTranscriptionCancel: (String) -> Unit = {},
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
    replyPreview: (ReplyTarget) -> StateFlow<io.github.trevarj.motd.ui.components.ReplyPreviewData?> = {
        kotlinx.coroutines.flow.MutableStateFlow(null)
    },
    onReplyPreviewClick: (ReplyTarget) -> Unit = {},
    dccTransfer: (MessageEntity) -> StateFlow<DccTransferEntity?> = {
        kotlinx.coroutines.flow.MutableStateFlow(null)
    },
    onAcceptDccTransfer: (Long, Uri, Boolean) -> Unit = { _, _, _ -> },
    onRejectDccTransfer: (Long) -> Unit = {},
    onRemoveDccTransfer: (Long) -> Unit = {},
    onSaveDccToDownloads: (suspend (Long) -> Unit)? = null,
    onSendDccFile: (Uri) -> Unit = {},
    preparingDccSend: Boolean = false,
    memberNicks: List<String> = emptyList(),
    knownNicks: Set<String> = emptySet(),
    channelSenderPrefixes: Map<String, Char> = emptyMap(),
    identityRules: IrcIdentityRules = IrcIdentityRules(),
    friends: Set<String> = emptySet(),
    fools: Set<String> = emptySet(),
    foolsMode: FoolsMode = FoolsMode.DIMMED,
    foolPreferences: Map<String, FoolPreferences> = emptyMap(),
    presentedVisibilitySpec: MessageVisibilitySpec? = null,
    showRedactedMessages: Boolean = true,
    hiddenFoolsRevealed: Boolean = false,
    expandAllFools: Boolean = false,
    onExpandAllFoolsChange: (Boolean) -> Unit = {},
    chatWallpaper: WallpaperRenderSpec = remember { resolveWallpaper(AppearanceConfig()) },
    wallpaperRevealed: MutableState<Boolean> = remember { mutableStateOf(false) },
    onOpenWallpaperWorkshop: ((Long) -> Unit)? = null,
    wallpaperName: String? = null,
    collapseTitleBar: Boolean = true,
    conversationFontScalePercent: Int = io.github.trevarj.motd.data.prefs.DEFAULT_FONT_SCALE_PERCENT,
    messageSpacing: io.github.trevarj.motd.data.prefs.MessageSpacing =
        io.github.trevarj.motd.data.prefs.MessageSpacing.DEFAULT,
    bubbleCornerStyle: io.github.trevarj.motd.data.prefs.BubbleCornerStyle =
        io.github.trevarj.motd.data.prefs.BubbleCornerStyle.ROUNDED,
    showComposerEmoji: Boolean = true,
    typingIndicatorPlacement: io.github.trevarj.motd.data.prefs.TypingIndicatorPlacement =
        io.github.trevarj.motd.data.prefs.TypingIndicatorPlacement.HEADER,
    showComposerFormattingTools: Boolean = true,
    visibleReplyPrefix: Boolean = false,
    swipeToReplyEnabled: Boolean = true,
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
    consumePrefill: () -> List<ComposerPrefill>? = { null },
    // Consume-once file routed here by the share picker; opens the upload confirmation sheet.
    consumeSharedFile: () -> PendingShare.File? = { null },
    // Consume-once attachment request queued (by the gesture orb) before this screen was entered.
    consumeAttachmentRequest: () -> Boolean = { false },
    // The same request aimed at this conversation while it is already open; see ChatViewModel.
    attachmentSheetRequests: Flow<Unit> = emptyFlow(),
    attachmentViewModel: AttachmentViewModel? = null,
    composerDraft: ComposerDraftState = ComposerDraftState(),
    composerUpload: ComposerUploadCompletion? = null,
    onBeginComposerUpload: ((Long, String?) -> ComposerUploadAttempt)? = null,
    aiTextState: AiTextUiState = AiTextUiState.Closed,
    aiTextEnabled: Boolean = false,
    onAiComposer: () -> Unit = {},
    // Exclusively the picker's existing On-device AI selection, gated by its caller.
    onTranslateMessage: ((MessageEntity) -> Unit)? = null,
    externalTranslations: Map<Long, InlineTranslation> = emptyMap(),
    externalTranslationInFlight: Boolean = false,
    onBeginExternalTranslation: (suspend (MessageEntity) -> ExternalTranslationRequest?)? = null,
    onCompleteExternalTranslation: ((String?) -> Unit)? = null,
    onAbortExternalTranslationLaunch: (() -> Unit)? = null,
    onDismissExternalTranslation: (Long) -> Unit = {},
    forwardState: MessageForwardState? = null,
    forwardTargets: List<ChatListRow> = emptyList(),
    onOpenForward: ((MessageEntity) -> Unit)? = null,
    onSelectForwardDestination: (ChatListRow) -> Unit = {},
    onChangeForwardDestination: () -> Unit = {},
    onForwardIncludeSenderChanged: (Boolean) -> Unit = {},
    onConfirmForward: () -> Unit = {},
    onDismissForward: () -> Unit = {},
    onDraftChanged: (String) -> Unit = {},
    // The send currently travelling from the composer into the timeline, if any.
    outgoingFlight: OutgoingFlight? = null,
    onFlightSettled: (Long) -> Unit = {},
    // Immediate raw-nickname request. The nonce permits mentioning the same nick repeatedly.
    mentionPrefill: Pair<Long, String>? = null,
    composerPrefill: Pair<Long, List<ComposerPrefill>>? = null,
    nicknameTapAction: NicknameTapAction = NicknameTapAction.MENTION,
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
    onViewportRefreshAnchor: (ViewportRefreshAnchor?) -> Unit = {},
    onClearScrollPosition: (ChatScrollPosition?) -> Unit = {},
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
    onRetryReplyJump: (ReplyTarget) -> Unit = {},
    historySyncStatus: HistorySyncStatus = HistorySyncStatus.Idle,
    // Re-runs the reconciliation pass behind the failed-sync pill; Paging's own retry runs with it.
    onHistorySyncRetry: () -> Unit = {},
    onHistorySyncDismiss: () -> Unit = {},
    historyAvailability: HistoryAvailability = HistoryAvailability.NegotiatingOrOffline,
    countUnreadBelowViewport: suspend (Int, io.github.trevarj.motd.data.db.TimelineAnchor) -> Int = { _, _ -> 0 },
    nearestUnreadMentionBelow: suspend (Int, io.github.trevarj.motd.data.db.TimelineAnchor) -> ChatPositionTarget? = { _, _ -> null },
    conversationLayout: ConversationLayoutState = ConversationLayoutState(),
    onConversationLayoutSelected: (io.github.trevarj.motd.data.prefs.LayoutDensity?) -> Unit = {},
    onCompactNickBracketsOverride: (CompactNickBrackets?) -> Unit = {},
    onConversationMessageBackgroundOpacitySelected: (Int?) -> Unit = {},
    onConversationMessageBackgroundBlurSelected: (Int?) -> Unit = {},
    onPresenceModeSelected: (io.github.trevarj.motd.data.prefs.PresenceMode?) -> Unit = {},
    onPresenceCustomKindSelected: (io.github.trevarj.motd.data.db.MessageKind) -> Unit = {},
    onHistorySyncModeSelected: (io.github.trevarj.motd.data.prefs.HistorySyncMode?) -> Unit = {},
    // Opt-in journal for the timeline's own Paging generations. Noop by default so previews and
    // hand-built call sites need nothing; the required E2E gate arms the real one for the journey.
    diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
    avatarEvents: Flow<ConversationAvatarOutcome> = emptyFlow(),
    accountSetupReminder: Boolean = false,
    onAccountSetup: () -> Unit = {},
    onDismissAccountSetup: () -> Unit = {},
    channelNotifications: ChannelNotificationPresentation = ChannelNotificationPresentation(),
    onChannelNotificationMode: (NotificationMode?) -> Unit = {},
    onStartWatch: (ChannelWatchDuration) -> Unit = {},
    onStopWatch: () -> Unit = {},
    onOpenConversationList: (() -> Unit)? = null,
    onOpenNetworkSettings: (Long) -> Unit = {},
    readAloudState: ReadAloudState = ReadAloudState(),
    readAloudConfig: ReadAloudSelection = ReadAloudSelection(),
    readAloudVoices: ReadAloudVoices = ReadAloudVoices(),
    onReadAloudToggle: () -> Unit = {},
    onReadAloudPrevious: () -> Unit = {},
    onReadAloudPauseResume: () -> Unit = {},
    onReadAloudSkip: () -> Unit = {},
    onReadAloudLatest: () -> Unit = {},
    onReadAloudMessage: ((MessageEntity) -> Unit)? = null,
    onReadAloudStop: () -> Unit = {},
    onReadAloudOptions: () -> Unit = {},
    onReadAloudSaveOptions: (ReadAloudSelection) -> Unit = {},
    onReadAloudPreview: (ReadAloudSelection) -> Unit = {},
    composerStyle: ComposerStyle = ComposerStyle.COMFORTABLE,
    scheduledComposer: ScheduledComposerActions? = null,
) {
    val globalCompactNickBrackets = LocalCompactNickBrackets.current
    val wallpaperSource = rememberSurfaceBackdropSource()
    val pageBackdrop = rememberSurfaceBackdropSource(wallpaperSource)
    val listState = presentation?.listState ?: rememberLazyListState()
    // Scope the page before deriving UI state: capturing the entire derived screen exceeds JVM parameter slots.
    SurfaceBackdropPage(pageBackdrop) {
        val dickordEnabled = LocalDickordLabsEnabled.current
        val buffer = state.buffer
        val activeDickordChannel =
            dickordEnabled &&
                buffer != null &&
                isDickordPortalConversation(buffer.type, buffer.displayName)
        val dickordDescriptor =
            if (activeDickordChannel) {
                decodeDickordChannelDescriptor(buffer.dickordChannelJson)
            } else {
                null
            }
        val conversationLabel =
            when {
                buffer == null -> {
                    null
                }

                activeDickordChannel -> {
                    dickordDescriptor?.channelName
                        ?: stringResource(R.string.dickord_portal_conversation_pending, buffer.id)
                }

                else -> {
                    buffer.displayName
                }
            }
        val presentationVisit = presentationLease?.presentationVisitIdentity
        var restoringHiddenFools by remember(presentationVisit) { mutableStateOf(false) }
        var restoreDispatched by remember(presentationVisit) { mutableStateOf(false) }
        var restorePolicy by remember(presentationVisit) { mutableStateOf<MessageVisibilitySpec?>(null) }
        var restoreIntent by remember(presentationVisit) { mutableLongStateOf(entryPositionIntent) }
        val hiddenFoolVisibility =
            if (fools.isNotEmpty()) updateTransition(!restoringHiddenFools, label = "hidden_fools") else null
        val layoutSnapshot = if (presentationLease != null) items.itemSnapshotList else null
        val measuredSnapshot =
            if (presentationLease != null) {
                remember(presentationLease, items) {
                    mutableStateOf<ItemSnapshotList<MessageEntity>?>(null, referentialEqualityPolicy())
                }
            } else {
                null
            }
        var payloadAdmitted by remember(presentationLease) { mutableStateOf(presentationLease == null) }
        // Keep Scaffold's stable measure-updated padding, not a post-measure height snapshot.
        var bottomHostPadding by remember { mutableStateOf<PaddingValues?>(null) }
        var topHostPadding by remember { mutableStateOf<PaddingValues?>(null) }
        val bottomHostDensity by rememberUpdatedState(LocalDensity.current)
        val readBottomHostHeightPx =
            remember {
                { with(bottomHostDensity) { bottomHostPadding?.calculateBottomPadding()?.roundToPx() ?: 0 } }
            }
        val readTopHostHeightPx =
            remember {
                { with(bottomHostDensity) { topHostPadding?.calculateTopPadding()?.roundToPx() ?: 0 } }
            }
        val rowContentMetrics = remember(items) { mutableStateMapOf<Long, TimelineRowContentMetrics>() }
        val autoFollow = remember { AutoFollowTracker(items.itemCount) }
        val presenceMode = state.conversationPresence.effective
        val visibleSpec =
            remember(
                presenceMode,
                state.conversationPresence.effectiveCustomKinds,
                fools,
                foolsMode,
                foolPreferences,
                hiddenFoolsRevealed,
                showRedactedMessages,
            ) {
                MessageVisibilitySpec(
                    presenceMode = presenceMode,
                    customPresenceKinds = state.conversationPresence.effectiveCustomKinds,
                    showRedactedMessages = showRedactedMessages,
                    fools = fools,
                    foolsMode = foolsMode,
                    foolPreferences = foolPreferences,
                    revealHiddenFools = hiddenFoolsRevealed,
                )
            }
        val visibilityPolicy = remember(visibleSpec, identityRules) { MessageVisibilityPolicy(visibleSpec, identityRules) }
        var liveEntryIds by remember(items, visibilityPolicy) { mutableStateOf(emptySet<Long>()) }
        var awaitingLiveRender by remember(items, visibilityPolicy) { mutableStateOf<TimelineAnchor?>(null) }
        val lastLivePresentation =
            remember(items, visibilityPolicy) {
                object {
                    var rows = emptyList<MessageEntity>()
                    var highWater = Long.MIN_VALUE
                    var heldForFlight: Long? = null
                    var heldIds = emptySet<Long>()
                }
            }
        val presentationContextCompatible =
            presentationLease == null ||
                (
                    state.buffer?.id == presentationLease.entry.key.context.roomId &&
                        state.buffer.networkId == presentationLease.entry.key.context.networkId &&
                        identityRules == presentationLease.entry.key.context.identityRules
                )
        val presentationCompatible =
            presentationContextCompatible && (presentationLease == null || presentedVisibilitySpec == visibleSpec)
        val currentPresentationCompatible by rememberUpdatedState(presentationCompatible)
        val payloadAllowed = payloadAdmitted && presentationLease?.isActive != false && presentationCompatible
        val positioningRetainedLink by rememberUpdatedState(jumpTarget?.retainedLinkRoomId != null)
        // Placement can clear the target while its Room generation still owns the viewport.
        // Its queued boundary is not reader demand until a scroll or another explicit history action.
        var linkOwnsOlderPaging by remember(presentationLease) { mutableStateOf(false) }

        fun setLinkPagingOwnership(ownedByLink: Boolean) {
            presentationLease?.setLinkOwnsOlderPaging(ownedByLink)
            linkOwnsOlderPaging = ownedByLink
        }

        val readerScrollInput =
            remember(presentationLease) {
                object : NestedScrollConnection {
                    override fun onPreScroll(
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        // Reader input can hit the oldest edge without moving the list, or its
                        // entire drag can occur before the scroll-state collector resumes.
                        if (source == NestedScrollSource.UserInput && available.y != 0f &&
                            linkOwnsOlderPaging && !positioningRetainedLink
                        ) {
                            setLinkPagingOwnership(false)
                        }
                        return Offset.Zero
                    }
                }
            }
        val readerOwnsOlderPaging = !positioningRetainedLink && !linkOwnsOlderPaging
        // Scroll-driven paging: the footer reflects the Paging APPEND state at the older end directly.
        val olderHistoryLoad = items.loadState.append
        val historyUiState =
            chatHistoryUiState(
                bufferType = state.buffer?.type,
                connectionState = state.connState,
                availability = historyAvailability,
                append =
                    if (!readerOwnsOlderPaging && olderHistoryLoad is LoadState.Loading) {
                        // The cached generation may have queued APPEND without any wire request.
                        LoadState.NotLoading(endOfPaginationReached = false)
                    } else {
                        olderHistoryLoad
                    },
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
        val ebooksCache = remember(ctx) { EbooksResultCache(ctx) }
        val resources = LocalResources.current
        val snackbarHostState = remember { SnackbarHostState() }
        val latestExternalTranslationInFlight by rememberUpdatedState(externalTranslationInFlight)
        val translationLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val returnedText =
                    if (result.resultCode == Activity.RESULT_OK) {
                        try {
                            result.data
                                ?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                                ?.toString()
                                ?.takeUnless(String::isBlank)
                        } catch (_: BadParcelableException) {
                            null
                        } catch (_: ClassCastException) {
                            null
                        }
                    } else {
                        null
                    }
                onCompleteExternalTranslation?.invoke(returnedText)
            }
        val eligibleEbooksRoomId =
            state.buffer?.let { room ->
                ebooksHelperRoomId?.takeIf {
                    it == room.id && room.type == BufferType.CHANNEL &&
                        identityRules.normalize(room.displayName) == identityRules.normalize("#ebooks")
                }
            }
        val latestEligibleEbooksRoomId by rememberUpdatedState(eligibleEbooksRoomId)
        val latestNetworkId by rememberUpdatedState(state.buffer?.networkId)
        var pendingDccAccept by remember { mutableStateOf<PendingDccAccept?>(null) }
        val dccDestinationPicker =
            rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/octet-stream"),
            ) { uri ->
                val pending = pendingDccAccept
                pendingDccAccept = null
                if (uri != null && pending != null) {
                    val sameRoom =
                        pending.ebooksRoomId == null ||
                            (pending.ebooksRoomId == latestEligibleEbooksRoomId && pending.ebooksNetworkId == latestNetworkId)
                    if (sameRoom) onAcceptDccTransfer(pending.transferId, uri, pending.allowPrivateEndpoint)
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
        val composerReply = composerDraft.replyTo?.takeIf { it.bufferId == traceBufferId }
        var lastComposerReply by remember(traceBufferId) { mutableStateOf<MessageEntity?>(null) }
        SideEffect {
            if (composerReply != null) lastComposerReply = composerReply
        }
        val latestAttachmentRoom by rememberUpdatedState(traceBufferId)
        var pendingEbooksRequestRevision by remember(traceBufferId) { mutableStateOf<Long?>(null) }
        var ebooksHelpOpen by remember(eligibleEbooksRoomId) { mutableStateOf(false) }
        var ebooksResults by remember(eligibleEbooksRoomId) { mutableStateOf<List<EbooksResult>?>(null) }
        var ebooksResultsError by remember(eligibleEbooksRoomId) { mutableStateOf<String?>(null) }
        var ebooksOffersOpen by remember(eligibleEbooksRoomId) { mutableStateOf(false) }
        val visibleEbooksOffers =
            if (eligibleEbooksRoomId != null) {
                ebooksDccOffers.filter {
                    it.networkId == state.buffer.networkId && it.direction == DccDirection.INCOMING &&
                        it.state != DccTransferState.REMOVED
                }
            } else {
                emptyList()
            }
        val latestVisibleEbooksOffers by rememberUpdatedState(visibleEbooksOffers)

        fun showEbooksResults(
            roomId: Long,
            uri: Uri,
        ) {
            if (roomId != latestEligibleEbooksRoomId) return
            scope.launch {
                try {
                    val parsed =
                        withContext(Dispatchers.IO) {
                            ctx.contentResolver.openInputStream(uri)?.use(::parseEbooksResults)
                                ?: throw IllegalArgumentException("Cannot open results ZIP")
                        }
                    if (roomId == latestEligibleEbooksRoomId) ebooksResults = parsed
                } catch (e: IllegalArgumentException) {
                    if (roomId == latestEligibleEbooksRoomId) ebooksResultsError = e.message ?: "Invalid results ZIP file"
                } catch (_: java.io.IOException) {
                    if (roomId == latestEligibleEbooksRoomId) ebooksResultsError = "Could not read results ZIP; choose it again"
                } catch (_: SecurityException) {
                    if (roomId == latestEligibleEbooksRoomId) ebooksResultsError = "Cannot access results ZIP; choose it again"
                }
            }
        }
        var pickingEbooksRoomId by remember { mutableStateOf<Long?>(null) }
        val ebooksResultsPicker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                val roomId = pickingEbooksRoomId
                pickingEbooksRoomId = null
                if (uri != null && roomId != null) showEbooksResults(roomId, uri)
            }

        fun isEbooksSearchOnly(text: String) = eligibleEbooksRoomId != null && plainIrcText(text).trim() == "@Search"

        // Anchors for the send flight. Held in their own holder so the per-layout writes recompose the
        // overlay that reads them and nothing else on this surface.
        val flightAnchors = remember(traceBufferId) { SendFlightAnchors() }
        val flightLandingId by remember(flightAnchors) { derivedStateOf { flightAnchors.landingRow?.first } }
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
        var attachmentSheetOpen by key(traceBufferId) { rememberSaveable { mutableStateOf(false) } }
        var uploadCurrentDraftDirectly by key(traceBufferId) { rememberSaveable { mutableStateOf(false) } }
        // A shared or pasted file opens the same upload confirmation without changing the draft.
        var sharedFile by remember(traceBufferId) { mutableStateOf<PendingShare.File?>(null) }
        val activeComposerUpload = composerUpload?.takeIf { it.roomId == traceBufferId }
        var pendingComposerUploadId by key(traceBufferId) { rememberSaveable { mutableStateOf<Long?>(null) } }
        LaunchedEffect(activeComposerUpload?.id, pendingComposerUploadId) {
            // Completion may close a sheet whose original UI callback was lost on rotation.
            if (activeComposerUpload != null && pendingComposerUploadId == activeComposerUpload.id) {
                pendingComposerUploadId = null
                attachmentSheetOpen = false
                uploadCurrentDraftDirectly = false
                sharedFile = null
            }
        }
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
        LaunchedEffect(traceBufferId, attachmentSheetRequests) {
            attachmentSheetRequests.collect {
                sharedFile = null
                uploadCurrentDraftDirectly = false
                attachmentSheetOpen = true
            }
        }
        var longDraftPrompt by rememberSaveable { mutableStateOf(false) }
        var overflowOpen by rememberSaveable { mutableStateOf(false) }
        val scheduleFocus = LocalFocusManager.current
        val scheduleKeyboard = LocalSoftwareKeyboardController.current
        var scheduledDraft by remember(traceBufferId) { mutableStateOf<ScheduledDraft?>(null) }
        val showScheduleActions =
            scheduledComposer != null && buffer != null &&
                (buffer.type == BufferType.CHANNEL || buffer.type == BufferType.QUERY)
        val scheduleAttachmentPending =
            attachmentSheetOpen || sharedFile != null ||
                voiceState.recording != null || voiceState.staged != null || voiceState.sending

        fun scheduleDraftRejection(text: String): Int? =
            when {
                scheduleAttachmentPending -> R.string.scheduled_attachment_unsupported
                state.replyTo != null -> R.string.scheduled_reply_unsupported
                isSchedulableMessage(text) && !isEbooksSearchOnly(text) -> null
                plainIrcText(text).isBlank() -> R.string.scheduled_text_required
                else -> R.string.scheduled_text_unsupported
            }

        val openSchedule: () -> Unit = {
            if (scheduledComposer != null) {
                val text = composerText.text
                val reason =
                    scheduleDraftRejection(text) ?: when (val capture = scheduledComposer.capture(text)) {
                        is ScheduledDraftCapture.Refused -> {
                            capture.reason
                        }

                        is ScheduledDraftCapture.Captured -> {
                            scheduleFocus.clearFocus()
                            scheduleKeyboard?.hide()
                            scheduledDraft = capture.draft
                            null
                        }
                    }
                if (reason != null) scope.launch { snackbarHostState.showSnackbar(resources.getString(reason)) }
            }
        }
        var conversationLayoutSheetOpen by rememberSaveable { mutableStateOf(false) }
        var notificationSheetOpen by rememberSaveable { mutableStateOf(false) }
        var presenceModeSheetOpen by rememberSaveable { mutableStateOf(false) }
        var historySyncModeSheetOpen by rememberSaveable { mutableStateOf(false) }
        var readAloudOptionsOpen by remember(state.buffer?.id) { mutableStateOf(false) }
        if (readAloudOptionsOpen) {
            ReadAloudVoiceOptions(
                config = readAloudConfig,
                voices = readAloudVoices,
                onSave = onReadAloudSaveOptions,
                onPreview = onReadAloudPreview,
                onDismiss = {
                    readAloudOptionsOpen = false
                    if (!readAloudState.enabled) onReadAloudStop()
                },
                onStopPreview = onReadAloudStop,
                onReloadVoices = onReadAloudOptions,
                previewing = readAloudState.previewing,
                error = readAloudState.error,
            )
        }
        var highlightMsgid by rememberSaveable { mutableStateOf<String?>(null) }
        var highlightEventId by rememberSaveable { mutableStateOf<Long?>(null) }
        // The visit owns expand-all; row exceptions remain local to this presentation.
        val latestExpandAllFools by rememberUpdatedState(expandAllFools && !restoringHiddenFools)
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
        val entryInitiallySettled = entryState is EntryPositionState.Settled && initialTarget == null && presentationLease == null
        var initialPositionSettled by remember(presentationVisit, entryInitiallySettled) {
            mutableStateOf(entryInitiallySettled)
        }
        // The first Paging emission after entry settlement reflects data loaded for the target, not a
        // live arrival. Consume it without auto-follow so an unread target remains on screen.
        var suppressNextAutoFollow by remember { mutableStateOf(!entryInitiallySettled) }
        val retainedViewport =
            remember(presentationLease, entryFallback) {
                entryFallback?.viewport ?: presentation?.confirmedViewport?.takeIf { entryFallback?.useSharedViewport == true }
            }
        var entryTimedOut by remember(presentationLease) { mutableStateOf(false) }
        var selectingRetainedViewport by remember(presentationLease) { mutableStateOf(false) }
        var retainedViewportChosen by remember(presentationLease) { mutableStateOf(false) }
        var supersededAutomaticTarget by remember(presentationLease) { mutableStateOf<ChatPositionTarget?>(null) }
        val automaticPositionJob = remember(presentationLease) { mutableStateOf<Job?>(null) }

        // Veil over the timeline while entry positioning settles, so the entry frames (the bottom of
        // the buffer, then the alignment passes) are never shown before the divider lands. One-shot per
        // composition, and the composition is per-ChatRoute: entering another buffer is a new route, so
        // nothing that happens within a visit -- a republished post-catch-up repair, a re-resolve -- can
        // re-engage it. A restored visit that already knows where it landed starts lifted.
        val latestEntryState by rememberUpdatedState(entryState)
        val latestUnreadEntrySnapshot by rememberUpdatedState(unreadEntrySnapshot)
        var entryVeilLifted by remember(presentationVisit) {
            mutableStateOf(
                shouldLiftEntryVeil(
                    initialPositionSettled = entryInitiallySettled,
                    entryUnresolved = entryState is EntryPositionState.Unresolved,
                    timedOut = false,
                ),
            )
        }
        // A temporary reveal preserves drawing, never the successor's read/position/SQL admission.
        var admittedDrawingSpec by remember(presentationVisit) { mutableStateOf<MessageVisibilitySpec?>(null) }
        var previousExpandAllFools by remember(presentationVisit) { mutableStateOf(expandAllFools) }
        var temporaryFoolToggle by remember(presentationVisit) { mutableStateOf(false) }
        if (previousExpandAllFools != expandAllFools) {
            if (initialPositionSettled && entryVeilLifted && entryState is EntryPositionState.Settled &&
                initialTarget == null && jumpTarget == null && admittedDrawingSpec != null
            ) {
                temporaryFoolToggle = true
                suppressNextAutoFollow = true
            }
            previousExpandAllFools = expandAllFools
        }
        if (payloadAllowed) admittedDrawingSpec = visibleSpec
        val temporaryFoolMotion =
            temporaryFoolToggle && presentationContextCompatible &&
                admittedDrawingSpec?.samePolicyExceptHiddenReveal(visibleSpec) == true
        val drawingAllowed =
            payloadAllowed ||
                (temporaryFoolMotion && initialPositionSettled && entryVeilLifted && entryState is EntryPositionState.Settled && jumpTarget == null)
        val timelineVisible = entryVeilLifted && drawingAllowed
        val showEmptyTimeline =
            items.loadState.refresh is LoadState.NotLoading &&
                initialPagingPage(items.itemCount, olderHistoryLoad) == InitialPagingPage.TerminalEmpty &&
                !historySyncStatus.isActive
        val showWallpaper =
            wallpaperRevealed.value || showEmptyTimeline || timelineHistoryStatus is HistorySyncStatus.Failed ||
                (timelineVisible && items.itemSnapshotList.items.isNotEmpty())
        if (showWallpaper && !wallpaperRevealed.value) {
            SideEffect { wallpaperRevealed.value = true }
        }
        val preserveOutgoingHiddenBodies = restoringHiddenFools || (temporaryFoolMotion && !expandAllFools)
        val restoreStillValid =
            presentationContextCompatible && restorePolicy?.samePolicyExceptHiddenReveal(visibleSpec) == true &&
                restoreIntent == entryPositionIntent && entryState is EntryPositionState.Settled && initialTarget == null && jumpTarget == null
        val currentRestoreValid by rememberUpdatedState(restoreStillValid)
        val latestExpandAllFoolsChange by rememberUpdatedState(onExpandAllFoolsChange)
        LaunchedEffect(restoringHiddenFools, restoreDispatched, restoreStillValid, presentationVisit) {
            if (!restoringHiddenFools || restoreDispatched) return@LaunchedEffect
            if (!restoreStillValid) {
                restoringHiddenFools = false
                return@LaunchedEffect
            }
            hiddenFoolVisibility?.let { transition ->
                snapshotFlow { !transition.currentState && !transition.targetState && !transition.isRunning }.first { it }
            }
            if (restoringHiddenFools && currentRestoreValid) {
                restoreDispatched = true
                latestExpandAllFoolsChange(false)
            }
        }
        LaunchedEffect(restoringHiddenFools, payloadAllowed, expandAllFools, presentedVisibilitySpec) {
            // Keep the old native Hidden hosts exited until the exact false-spec refresh is admitted.
            if (restoringHiddenFools && !expandAllFools && payloadAllowed && presentedVisibilitySpec?.revealHiddenFools != true) {
                restoreDispatched = false
                restoringHiddenFools = false
            }
        }
        LaunchedEffect(temporaryFoolToggle, payloadAllowed, visibleSpec, presentationLease, restoringHiddenFools) {
            if (temporaryFoolToggle && payloadAllowed && !restoringHiddenFools) {
                Animatable(0f).animateTo(1f, MotdMotion.microFadeIn)
                temporaryFoolToggle = false
            }
        }
        LaunchedEffect(presentationLease) {
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
                        val settled = initialPositionSettled || (presentationLease == null && entry is EntryPositionState.Settled)
                        settled to (entry is EntryPositionState.Unresolved)
                    }.first { (settled, unresolved) ->
                        shouldLiftEntryVeil(settled, unresolved, timedOut = false)
                    }
                }
            entryTimedOut = resolved == null
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

        // Raw ignored tails do not make the user leave the meaningful bottom of the conversation.
        val atTimelineLiveEnd =
            remember(listState, items, visibilityPolicy) {
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
        val atBottom by remember(listState, items, visibilityPolicy) {
            derivedStateOf {
                val layout = listState.layoutInfo
                val bottomHostHeightPx = readBottomHostHeightPx()
                // A first/stale measure with no host clearance cannot prove that newest is readable.
                bottomHostHeightPx > 0 &&
                    layout.beforeContentPadding >= bottomHostHeightPx &&
                    layout.viewportEndOffset > layout.viewportStartOffset + bottomHostHeightPx &&
                    atTimelineLiveEnd.value
            }
        }

        // ponytail: top-entry alignment needs the final viewport; settled reader transitions still animate.
        val placingTopEntry = initialTarget?.let { it.placeAtTop && it.index > 0 } == true
        val entrySeams = timelineSeams.takeIf { placingTopEntry }
        val entryLayoutSnapshot = if (placingTopEntry) items.itemSnapshotList else null
        var measuredEntryLayout by remember(initialTarget, items) {
            mutableStateOf<Pair<ItemSnapshotList<MessageEntity>, TimelineSeamState>?>(null, referentialEqualityPolicy())
        }

        fun currentTopEntryLayoutMeasured(): Boolean =
            entrySeams?.observed == true &&
                measuredEntryLayout?.let { (snapshot, seams) -> snapshot === items.itemSnapshotList && seams == entrySeams } == true

        val entryLayoutModifier =
            if (entrySeams != null && entryLayoutSnapshot != null) {
                // The same Paging snapshot can grow a seam without changing item identities.
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.place(0, 0)
                        if (measuredEntryLayout?.first !== entryLayoutSnapshot || measuredEntryLayout?.second != entrySeams) {
                            measuredEntryLayout = entryLayoutSnapshot to entrySeams
                        }
                    }
                }
            } else {
                Modifier
            }

        val latestCollapseTitleBar by rememberUpdatedState(collapseTitleBar)
        var topEntryClampedAtBottom by remember(initialTarget) { mutableStateOf(false) }
        var laidOutEntryHeaderExpansion by remember(initialTarget) { mutableFloatStateOf(-1f) }
        val headerExpansion =
            animateFloatAsState(
                targetValue =
                    if (!collapseTitleBar) {
                        1f
                    } else if (placingTopEntry) {
                        if (topEntryClampedAtBottom) 1f else 0f
                    } else if (presentation != null && !initialPositionSettled && !entryVeilLifted) {
                        presentation.headerExpansion
                    } else if ((initialPositionSettled || entryVeilLifted) && !atBottom) {
                        0f
                    } else {
                        1f
                    },
                animationSpec = if (placingTopEntry) snap() else MotdMotion.fadeIn,
                label = "chat_header_expansion",
            )

        fun currentPlacementReady(target: ChatPositionTarget?): Boolean {
            if (presentationLease != null && measuredSnapshot?.value !== items.itemSnapshotList) return false
            if (target?.expectedEventId == null) return true
            val layout = listState.layoutInfo
            val row = layout.visibleItemsInfo.firstOrNull { it.key == target.expectedEventId } ?: return false
            val hostHeight = readBottomHostHeightPx()
            if (hostHeight <= 0 || layout.beforeContentPadding < hostHeight) return false
            if (target.placeAtTop && placingTopEntry) {
                if (!currentTopEntryLayoutMeasured()) return false
                val expansion = if (!latestCollapseTitleBar || topEntryClampedAtBottom) 1f else 0f
                return laidOutEntryHeaderExpansion == expansion &&
                    (
                        topEntryClampedAtBottom ||
                            kotlin.math.abs(reverseItemTopAlignmentCorrection(row.offset, row.size, layout.viewportEndOffset - readTopHostHeightPx())) <= TOP_ALIGNMENT_TOLERANCE_PX
                    )
            }
            val snapshot = items.itemSnapshotList
            return exactTargetPlacement(
                loadedStart = snapshot.placeholdersBefore,
                loadedItems = snapshot.items,
                visibleItems = layout.visibleItemsInfo.map { it.key to it.index },
                firstVisibleIndex = listState.firstVisibleItemIndex,
                firstVisibleOffset = listState.firstVisibleItemScrollOffset,
                targetOffset = target.offset,
                canScrollForward = listState.canScrollForward,
                targetFullyVisible =
                    row.size > 0 && row.offset >= layout.viewportStartOffset + hostHeight &&
                        row.offset + row.size <= layout.viewportEndOffset - readTopHostHeightPx(),
                eventId = target.expectedEventId,
            ) is ExactTargetPlacement.Positioned
        }

        suspend fun admitPayload(target: ChatPositionTarget? = null) {
            val lease = presentationLease ?: return
            snapshotFlow {
                if (lease.isActive && currentPresentationCompatible &&
                    lease.hasPublishedWindow(items.itemSnapshotList) && currentPlacementReady(target)
                ) {
                    items.itemSnapshotList
                } else {
                    null
                }
            }.filterNotNull().first { snapshot ->
                lease.admit(snapshot) && items.itemSnapshotList === snapshot && lease.isActive &&
                    currentPresentationCompatible && currentPlacementReady(target)
            }
            payloadAdmitted = true
        }
        LaunchedEffect(presentationLease, initialTarget, presentationRevision) {
            if (presentationLease?.continuesPresentation == true && initialPositionSettled && initialTarget == null && !payloadAdmitted) {
                admitPayload()
            }
        }

        // Capture only entry-stage boundaries, through the already opt-in structural journal.
        fun recordEntryStage(
            event: String,
            target: ChatPositionTarget?,
            outcome: String? = null,
        ) {
            diagnostics.record("chat_entry", event) {
                val frozen = latestUnreadEntrySnapshot
                val load = items.loadState
                val snapshot = items.itemSnapshotList
                val layout = listState.layoutInfo
                val targetEventId =
                    target?.expectedEventId ?: target?.expectedMsgid?.let { msgid ->
                        snapshot.items.firstOrNull { it.msgid == msgid }?.id
                    }
                val measured = targetEventId?.let { key -> layout.visibleItemsInfo.firstOrNull { it.key == key } }
                mapOf(
                    "room_id" to traceBufferId,
                    "session_id" to traceSessionId,
                    "outcome" to outcome,
                    "entry_state" to latestEntryState.journalName,
                    "position_settled" to initialPositionSettled,
                    "target_present" to (target != null),
                    "target_index" to target?.index,
                    "target_offset" to target?.offset,
                    "target_event_id" to target?.expectedEventId,
                    "target_msgid_hash" to diagnostics.fingerprint(target?.expectedMsgid),
                    "target_place_at_top" to target?.placeAtTop,
                    "target_saved" to target?.fromSavedPosition,
                    "frozen_present" to (frozen != null),
                    "frozen_marker_time" to frozen?.marker?.serverTime,
                    "frozen_marker_event_id" to frozen?.marker?.eventId,
                    "frozen_marker_order" to frozen?.marker?.timelineOrder,
                    "frozen_loaded_count" to frozen?.loadedCount,
                    "frozen_lower_bound" to frozen?.lowerBound,
                    "refresh" to loadStateName(load.refresh),
                    "prepend" to loadStateName(load.prepend),
                    "append" to loadStateName(load.append),
                    "source_refresh" to loadStateName(load.source.refresh),
                    "source_prepend" to loadStateName(load.source.prepend),
                    "source_append" to loadStateName(load.source.append),
                    "mediator_present" to (load.mediator != null),
                    "mediator_refresh" to load.mediator?.refresh?.let(::loadStateName),
                    "mediator_prepend" to load.mediator?.prepend?.let(::loadStateName),
                    "mediator_append" to load.mediator?.append?.let(::loadStateName),
                    "page" to initialPagingPage(items.itemCount, load.append).name,
                    "item_count" to items.itemCount,
                    "loaded_count" to snapshot.items.size,
                    "loaded_start" to snapshot.placeholdersBefore,
                    "placing_top_entry" to placingTopEntry,
                    "clamped_at_bottom" to topEntryClampedAtBottom,
                    "header_expansion" to headerExpansion.value,
                    "laid_out_header_expansion" to laidOutEntryHeaderExpansion,
                    "viewport_index" to listState.firstVisibleItemIndex,
                    "viewport_offset" to listState.firstVisibleItemScrollOffset,
                    "viewport_start" to layout.viewportStartOffset,
                    "viewport_end" to layout.viewportEndOffset,
                    "target_loaded_index" to
                        targetEventId?.let {
                            materializedTargetSnapshotIndex(snapshot.placeholdersBefore, snapshot.items, it)
                        },
                    "target_measured" to (measured != null),
                    "target_measured_key" to (measured?.key as? Long),
                    "target_measured_index" to measured?.index,
                    "target_measured_offset" to measured?.offset,
                    "target_measured_size" to measured?.size,
                )
            }
        }

        var prefillConsumed by remember(traceBufferId) { mutableStateOf(false) }

        // Apply hydration/accepted-send clears without re-saving the same value from the screen.
        LaunchedEffect(traceBufferId, composerDraft.hydrated, composerDraft.revision) {
            if (traceBufferId == null || !composerDraft.hydrated) return@LaunchedEffect
            // Saving a Request immediately before submitting creates a draft revision that must not
            // rehydrate the optimistically cleared field. A rejection republishes a newer revision.
            if (composerDraft.revision == pendingEbooksRequestRevision) return@LaunchedEffect
            pendingEbooksRequestRevision = null
            if (composerText.text != composerDraft.text) {
                composerText =
                    TextFieldValue(
                        composerDraft.text,
                        TextRange(composerDraft.text.length),
                    )
            }
            if (!prefillConsumed) {
                prefillConsumed = true
                consumePrefill()?.forEach { prefill ->
                    composerText =
                        if (prefill.isNickname) {
                            insertNickname(composerText, prefill.text)
                        } else {
                            appendPrefill(composerText, prefill.text)
                        }
                    onDraftChanged(composerText.text)
                }
            }
        }
        LaunchedEffect(mentionPrefill) {
            mentionPrefill?.second?.let {
                composerText = insertNickname(composerText, it)
                onDraftChanged(composerText.text)
            }
        }
        LaunchedEffect(composerPrefill) {
            composerPrefill?.second?.forEach { prefill ->
                composerText =
                    if (prefill.isNickname) {
                        insertNickname(composerText, prefill.text)
                    } else {
                        appendPrefill(composerText, prefill.text)
                    }
                onDraftChanged(composerText.text)
            }
        }
        // An eligibility/reply change must not rehydrate an optimistic send's old draft. Only the
        // accepted empty revision (or initial hydration) offers another search, after share/mention prefills.
        LaunchedEffect(
            eligibleEbooksRoomId,
            composerDraft.hydrated,
            composerDraft.revision,
            prefillConsumed,
            state.replyTo,
        ) {
            if (
                eligibleEbooksRoomId != null &&
                composerDraft.hydrated &&
                prefillConsumed &&
                composerDraft.text.isEmpty() &&
                composerText.text.isEmpty() &&
                state.replyTo == null
            ) {
                composerText = TextFieldValue("@Search ", selection = TextRange(8))
                onDraftChanged("@Search ")
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

                    ChatUiEvent.HistorySyncModeWriteFailed -> {
                        stringResource(R.string.settings_history_sync_write_failed)
                    }

                    ChatUiEvent.NotificationSettingsWriteFailed -> {
                        stringResource(R.string.notification_settings_save_failed)
                    }

                    is ChatUiEvent.ReplyJumpUnavailable -> {
                        jumpNotLoaded
                    }

                    ChatUiEvent.ConversationLinkUnavailable -> {
                        stringResource(R.string.chat_links_message_unavailable)
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

        val currentPresentedVisibilitySpec by rememberUpdatedState(presentedVisibilitySpec)
        val currentVisibilityPolicy by rememberUpdatedState(visibilityPolicy)

        suspend fun materializeTarget(
            target: ChatPositionTarget,
            scroll: Boolean,
        ): MaterializedChatTarget? {
            val retainedLinkJump = target.retainedLinkRoomId != null

            fun canPlaceRetainedLink(row: MessageEntity): Boolean = positionTargetMatches(target, row) && currentVisibilityPolicy.allowsConversationLink(row)

            target.requiredVisibilitySpec?.let { required ->
                val matched =
                    withTimeoutOrNull(TARGET_MATERIALIZATION_TIMEOUT_MS) {
                        snapshotFlow { currentPresentedVisibilitySpec }.first { it == required }
                    }
                if (matched == null) return null
            }
            // itemCount is used only to establish that the position is addressable. A non-null peek is
            // the sole proof that the target placeholder has materialized.
            val pageReady =
                withTimeoutOrNull(TARGET_MATERIALIZATION_TIMEOUT_MS) {
                    snapshotFlow { Triple(items.loadState.refresh, items.loadState.append, items.itemCount) }
                        .first { (refresh, append, count) ->
                            (refresh is LoadState.NotLoading || presentationLease?.hasPublishedWindow(items.itemSnapshotList) == true) &&
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

            // JOIN/PART commits can change the raw rank after the entry snapshot was resolved.
            // A loaded exact row is authoritative; the old index is only a load hint while absent.
            fun currentIndex(): Int? {
                val snapshot = items.itemSnapshotList
                val loaded =
                    target.expectedEventId?.let {
                        materializedTargetSnapshotIndex(snapshot.placeholdersBefore, snapshot.items, it)
                    }
                return materializableTargetIndex(
                    requestedIndex = loaded ?: target.index,
                    itemCount = items.itemCount,
                    hasExactIdentity = target.expectedEventId != null || target.expectedMsgid != null,
                )
            }
            val materializedIndex = currentIndex()
            if (materializedIndex == null) {
                AutoFollowTrace.record("materialize_index_unaddressable", traceBufferId, traceSessionId) {
                    "target_index=${target.index} item_count=${items.itemCount}"
                }
                return null
            }
            val row =
                requestAndAwaitTarget(
                    index = materializedIndex,
                    request = { _ ->
                        val index = currentIndex()
                        if (index == null) {
                            false
                        } else {
                            try {
                                if (scroll && !retainedLinkJump &&
                                    (listState.firstVisibleItemIndex != index || listState.firstVisibleItemScrollOffset != target.offset)
                                ) {
                                    listState.requestScrollToItem(index, target.offset)
                                }
                                // Retained links wait for their native Room refresh instead of hinting Paging.
                                if (!retainedLinkJump) items[index]
                                true
                            } catch (_: IndexOutOfBoundsException) {
                                // A new Paging generation replaced the snapshot between the bound and access.
                                false
                            }
                        }
                    },
                    snapshots =
                        snapshotFlow {
                            val index = currentIndex() ?: materializedIndex
                            targetMaterialization(items, index).let { snapshot ->
                                if (target.expectedEventId != null && snapshot.item?.id != target.expectedEventId) {
                                    snapshot.copy(item = null)
                                } else {
                                    snapshot
                                }
                            }
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
            return row?.let { targetRow ->
                if (retainedLinkJump && !canPlaceRetainedLink(targetRow)) return@let null
                if (!retainedLinkJump && positionTargetMatches(target, targetRow) &&
                    (target.requestToken != 0L || target.highlightMsgid != null || target.highlightEventId != null) &&
                    visibilityPolicy.isFool(targetRow)
                ) {
                    expandedFools += targetRow.id
                    collapsedFools -= targetRow.id
                }
                if (!scroll || target.expectedEventId != targetRow.id) {
                    return@let MaterializedChatTarget(targetRow, currentIndex() ?: materializedIndex)
                }
                // A replacement snapshot can shift the exact row while the viewport is settling.
                // For local links, this is also the first point that may scroll the viewport.

                fun exactPlacement(): ExactTargetPlacement? {
                    val snapshot = items.itemSnapshotList
                    val layout = listState.layoutInfo
                    val measured = layout.visibleItemsInfo.firstOrNull { it.key == targetRow.id }
                    val bottomHostHeightPx = readBottomHostHeightPx()
                    return exactTargetPlacement(
                        loadedStart = snapshot.placeholdersBefore,
                        loadedItems = snapshot.items,
                        visibleItems = layout.visibleItemsInfo.map { it.key to it.index },
                        firstVisibleIndex = listState.firstVisibleItemIndex,
                        firstVisibleOffset = listState.firstVisibleItemScrollOffset,
                        targetOffset = target.offset,
                        canScrollForward = listState.canScrollForward,
                        targetFullyVisible =
                            measured != null && measured.size > 0 &&
                                bottomHostHeightPx > 0 && layout.beforeContentPadding >= bottomHostHeightPx &&
                                measured.offset >= layout.viewportStartOffset + bottomHostHeightPx &&
                                measured.offset + measured.size <= layout.viewportEndOffset - readTopHostHeightPx(),
                        eventId = targetRow.id,
                    ).also { placement ->
                        if (retainedLinkJump) {
                            val index =
                                when (placement) {
                                    is ExactTargetPlacement.Positioned -> placement.index
                                    is ExactTargetPlacement.NeedsScroll -> placement.index
                                    ExactTargetPlacement.Missing -> null
                                }
                            if (index != null && !canPlaceRetainedLink(snapshot.items[index - snapshot.placeholdersBefore])) return null
                        }
                    }
                }
                withTimeoutOrNull(TARGET_MATERIALIZATION_TIMEOUT_MS) {
                    var placement = exactPlacement()
                    while (true) {
                        when (placement) {
                            null -> {
                                return@withTimeoutOrNull null
                            }

                            ExactTargetPlacement.Missing -> {
                                // A replacement may temporarily drop the old loaded window. Wait for its
                                // Parked(id) refresh instead of settling on whichever row took the old slot.
                                placement = snapshotFlow { exactPlacement() }.first { it !is ExactTargetPlacement.Missing }
                            }

                            is ExactTargetPlacement.Positioned -> {
                                return@withTimeoutOrNull MaterializedChatTarget(targetRow, placement.index)
                            }

                            is ExactTargetPlacement.NeedsScroll -> {
                                val requestedIndex = placement.index
                                listState.requestScrollToItem(requestedIndex, target.offset)
                                placement =
                                    snapshotFlow { exactPlacement() }.first { next ->
                                        next !is ExactTargetPlacement.NeedsScroll || next.index != requestedIndex
                                    }
                            }
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }
            }
        }

        // Only the existing target timeout selects a retained viewport. Explicit jumps remain separate.
        LaunchedEffect(
            entryVeilLifted,
            entryTimedOut,
            initialPositionSettled,
            presentationLease,
            presentationRevision,
            measuredSnapshot?.value,
            visibleSpec,
            presentationCompatible,
            entryPositionIntent,
        ) {
            if (!entryVeilLifted || payloadAdmitted || initialPositionSettled) return@LaunchedEffect
            val lease = presentationLease
            val fallback = entryFallback
            val saved = retainedViewport
            val snapshot = items.itemSnapshotList
            val index = saved?.let { materializedTargetSnapshotIndex(snapshot.placeholdersBefore, snapshot.items, it.rowId) }
            val target =
                if (saved != null && index != null) {
                    ChatPositionTarget(
                        index = index,
                        offset = saved.offset,
                        expectedEventId = saved.rowId,
                        expectedMsgid = saved.msgid,
                        serverTime = saved.serverTime,
                        fromSavedPosition = true,
                    )
                } else {
                    null
                }
            if (!entryTimedOut || lease == null || fallback == null || target == null ||
                fallback.intent != entryPositionIntent || canAdoptRetainedFallback?.invoke(fallback.intent) != true ||
                !positionTargetMatches(target, items.peek(target.index))
            ) {
                // Cold, unavailable and explicit-jump fallbacks retain their prior behavior, but never
                // bypass the independent committed-payload admission barrier.
                if (!payloadAdmitted) admitPayload()
                return@LaunchedEffect
            }
            if (!currentPresentationCompatible || !lease.admit(snapshot) || items.itemSnapshotList !== snapshot) return@LaunchedEffect
            if (canAdoptRetainedFallback.invoke(fallback.intent) != true) return@LaunchedEffect
            selectingRetainedViewport = true
            try {
                automaticPositionJob.value?.cancelAndJoin()
                if (canAdoptRetainedFallback.invoke(fallback.intent) != true) return@LaunchedEffect
                lease.prepareTarget(
                    target.index,
                    target.expectedEventId,
                    io.github.trevarj.motd.data.repo
                        .entryAnchorPagingKey(target.index),
                    ViewportRefreshAnchor.Parked(checkNotNull(target.expectedEventId)),
                )
                val placed = materializeTarget(target, scroll = true) ?: return@LaunchedEffect
                if (!currentPlacementReady(target) || listState.firstVisibleItemIndex != placed.index ||
                    listState.firstVisibleItemScrollOffset != target.offset
                ) {
                    return@LaunchedEffect
                }
                val presented = items.itemSnapshotList
                if (!lease.admit(presented) || items.itemSnapshotList !== presented || !currentPlacementReady(target)) return@LaunchedEffect
                val position =
                    ChatScrollPosition(
                        index = placed.index,
                        offset = target.offset,
                        msgid = placed.row.msgid,
                        serverTime = placed.row.serverTime,
                        rowId = placed.row.id,
                    )
                if (onAdoptRetainedFallback?.invoke(position, fallback.intent) == true) {
                    supersededAutomaticTarget = initialTarget
                    retainedViewportChosen = true
                    presentation?.confirmedViewport = position
                    payloadAdmitted = true
                    initialPositionSettled = true
                    suppressNextAutoFollow = true
                }
            } finally {
                selectingRetainedViewport = false
            }
        }
        var retainedLinkPrepared by remember(presentationLease, jumpTarget?.requestToken) { mutableStateOf(false) }

        // Deep jumps request one resolved placeholder, then validate both of its exact identities.
        LaunchedEffect(jumpTarget, items, presentationLease, presentationRevision, visibleSpec) {
            val j = jumpTarget ?: return@LaunchedEffect
            setLinkPagingOwnership(j.retainedLinkRoomId != null)
            if (j.retainedLinkRoomId != null && presentationLease != null) {
                if (!retainedLinkPrepared && currentPresentedVisibilitySpec == j.requiredVisibilitySpec) {
                    val eventId = checkNotNull(j.expectedEventId)
                    val anchor = ViewportRefreshAnchor.Parked(eventId)
                    retainedLinkPrepared = true
                    // Retarget the native Room source, not a remote refresh or a speculative scroll.
                    presentationLease.prepareTarget(j.index, eventId, null, anchor)
                    onViewportRefreshAnchor(anchor)
                }
            } else {
                onViewportRefreshAnchor(null)
            }
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
                admitPayload(j)
                AutoFollowTrace.record("deep_jump_settled", traceBufferId, traceSessionId) {
                    "target_index=${j.index} item_count=${items.itemCount}"
                }
                highlightMsgid = j.highlightMsgid
                highlightEventId = j.highlightEventId
                initialPositionSettled = true
                suppressNextAutoFollow = true
                onJumpHandled(j.requestToken)
            }
        }

        // Normal entry shares the deep-link paging mechanics but has no highlight. It is separate so
        // a deep link always wins and so a completed normal entry cannot be replayed on recomposition.
        LaunchedEffect(
            initialTarget,
            items,
            presentationLease,
            presentationRevision,
            visibleSpec,
            selectingRetainedViewport,
            entrySeams,
        ) {
            if (selectingRetainedViewport || (retainedViewportChosen && initialTarget == supersededAutomaticTarget)) return@LaunchedEffect
            if (placingTopEntry && initialPositionSettled) return@LaunchedEffect
            recordEntryStage("initial_position_start", initialTarget, if (initialTarget == null) "no_target" else "waiting")
            val target = initialTarget ?: return@LaunchedEffect
            if (placingTopEntry) topEntryClampedAtBottom = false
            automaticPositionJob.value = currentCoroutineContext()[Job]
            AutoFollowTrace.record("initial_position_start", traceBufferId, traceSessionId) {
                "target_index=${target.index} target_offset=${target.offset} " +
                    "saved=${target.fromSavedPosition} item_count=${items.itemCount}"
            }
            val pageReady =
                withTimeoutOrNull(TARGET_MATERIALIZATION_TIMEOUT_MS) {
                    snapshotFlow {
                        (items.loadState.refresh is LoadState.NotLoading || presentationLease?.hasPublishedWindow(items.itemSnapshotList) == true) &&
                            initialPagingPage(items.itemCount, items.loadState.append) != InitialPagingPage.Pending &&
                            entrySeams?.observed != false &&
                            (!placingTopEntry || laidOutEntryHeaderExpansion == if (latestCollapseTitleBar) 0f else 1f)
                    }.first { it }
                } != null
            if (!pageReady) {
                AutoFollowTrace.record("initial_position_page_not_ready", traceBufferId, traceSessionId) {
                    "target_index=${target.index} refresh=${items.loadState.refresh} " +
                        "append=${items.loadState.append} item_count=${items.itemCount}"
                }
                recordEntryStage(
                    "initial_position_page_not_ready",
                    target,
                    when {
                        items.loadState.refresh !is LoadState.NotLoading -> "refresh_not_ready"
                        initialPagingPage(items.itemCount, items.loadState.append) == InitialPagingPage.Pending -> "page_pending"
                        entrySeams?.observed == false -> "local_seams_pending"
                        else -> "header_layout_pending"
                    },
                )
                onInitialPositionUnresolved()
                return@LaunchedEffect
            }
            recordEntryStage("initial_position_page_ready", target)
            val currentlyAtBottom =
                listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset <= AUTOSCROLL_BOTTOM_TOLERANCE_PX
            val terminalEmpty =
                target.index == 0 && target.expectedEventId == null &&
                    target.expectedMsgid == null &&
                    initialPagingPage(items.itemCount, items.loadState.append) == InitialPagingPage.TerminalEmpty
            if (!terminalEmpty) recordEntryStage("initial_position_materializing", target)
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
                        recordEntryStage("initial_position_materialized", target)
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
                            recordEntryStage("initial_position_align", target)
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
                                if (placingTopEntry && !currentTopEntryLayoutMeasured()) {
                                    snapshotFlow { currentTopEntryLayoutMeasured() }.first { it }
                                }
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
                                        viewportEndOffset = layout.viewportEndOffset - readTopHostHeightPx(),
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
                            if (placingTopEntry && atBottom) {
                                // A short unread run clamps at newest. Restore its expanded viewport
                                // under the veil, and wait for that layout before consuming the target.
                                topEntryClampedAtBottom = true
                                recordEntryStage("initial_position_waiting_expanded_header", target)
                                snapshotFlow { laidOutEntryHeaderExpansion }.first { it == 1f }
                                recordEntryStage("initial_position_expanded_header_ready", target)
                            }
                            row
                        } else {
                            AutoFollowTrace.record("initial_position_align_missing", traceBufferId, traceSessionId) {
                                "target_index=${target.index} item_count=${items.itemCount}"
                            }
                            recordEntryStage("initial_position_align_missing", target, "target_not_measured")
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
                admitPayload(target)
                initialPositionSettled = true
                suppressNextAutoFollow = true
                recordEntryStage("initial_position_settled", target, "terminal_empty")
                onInitialPositionHandled()
            } else if (targetRow != null && positionTargetMatches(target, targetRow)) {
                admitPayload(target)
                AutoFollowTrace.record("initial_position_settled", traceBufferId, traceSessionId) {
                    "target_index=${target.index} index=${listState.firstVisibleItemIndex} " +
                        "offset=${listState.firstVisibleItemScrollOffset} at_bottom=$currentlyAtBottom " +
                        "place_at_top=${target.placeAtTop}"
                }
                initialPositionSettled = true
                suppressNextAutoFollow = true
                recordEntryStage("initial_position_settled", target, "identity_matched")
                onInitialPositionHandled()
            } else if (targetRow != null) {
                AutoFollowTrace.record("initial_position_reresolve", traceBufferId, traceSessionId) {
                    "target_index=${target.index} reason=identity_mismatch item_count=${items.itemCount}"
                }
                recordEntryStage("initial_position_reresolve", target, "identity_mismatch")
                onReresolveInitial(target)
            } else if (target.expectedEventId != null || target.expectedMsgid != null) {
                AutoFollowTrace.record("initial_position_reresolve", traceBufferId, traceSessionId) {
                    "target_index=${target.index} reason=target_missing item_count=${items.itemCount}"
                }
                recordEntryStage("initial_position_reresolve", target, "target_missing")
                onReresolveInitial(target)
            } else {
                AutoFollowTrace.record("initial_position_unresolved", traceBufferId, traceSessionId) {
                    "target_index=${target.index} item_count=${items.itemCount} " +
                        "append=${loadStateName(items.loadState.append)}"
                }
                recordEntryStage("initial_position_unresolved", target, "target_missing")
                onInitialPositionUnresolved()
            }
        }

        // Clear both identities together after the pulse settles (~1.6s).
        LaunchedEffect(highlightMsgid, highlightEventId) {
            if (highlightMsgid != null || highlightEventId != null) {
                kotlinx.coroutines.delay(1_600)
                highlightMsgid = null
                highlightEventId = null
            }
        }

        fun saveCurrentScrollPosition() {
            if (!initialPositionSettled || !entryVeilLifted || !payloadAllowed || !currentPresentationCompatible ||
                presentationLease?.isActive == false || presentationLease?.ownsSlot() == false ||
                (presentationLease != null && measuredSnapshot?.value !== items.itemSnapshotList)
            ) {
                return
            }
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
                    val position =
                        nearestAnchorRow(index, items.itemCount, items::peek, visibilityPolicy)?.let { (anchorIndex, row) ->
                            ChatScrollPosition(
                                index = anchorIndex,
                                offset = listState.firstVisibleItemScrollOffset.takeIf { anchorIndex == index } ?: 0,
                                msgid = row.msgid,
                                serverTime = row.serverTime,
                                rowId = row.id,
                            )
                        }
                    if (position != null) presentation?.confirmedViewport = position
                    onClearScrollPosition(position)
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
                    val position =
                        ChatScrollPosition(
                            index = outcome.anchorIndex,
                            offset =
                                listState.firstVisibleItemScrollOffset
                                    .takeIf { outcome.anchorIndex == index } ?: 0,
                            msgid = outcome.row.msgid,
                            serverTime = outcome.row.serverTime,
                            rowId = outcome.row.id,
                        )
                    presentation?.confirmedViewport = position
                    onScrollPositionChanged(position)
                }
            }
        }
        // The previous collector allocated and wrote to the position cache for nearly every pixel of a
        // fling. We only need the final anchor: persist when scrolling settles, plus once on disposal so
        // a back gesture during an active fling still retains the current location.
        LaunchedEffect(initialPositionSettled, entryVeilLifted, payloadAllowed, listState, visibilityPolicy, items) {
            if (!initialPositionSettled) return@LaunchedEffect
            snapshotFlow { listState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { scrolling -> if (!scrolling) saveCurrentScrollPosition() }
        }
        DisposableEffect(initialPositionSettled, entryVeilLifted, payloadAllowed, listState, visibilityPolicy, items) {
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
            if (!initialPositionSettled || !payloadAllowed || !currentPresentationCompatible ||
                presentationLease?.isActive == false || presentationLease?.ownsSlot() == false ||
                (presentationLease != null && measuredSnapshot?.value !== items.itemSnapshotList)
            ) {
                return
            }
            val layout = listState.layoutInfo
            val bottomHostHeightPx = readBottomHostHeightPx()
            val deepest =
                layout.visibleItemsInfo
                    .lastOrNull {
                        timelineItemIsVisible(it, layout, bottomHostHeightPx, rowContentMetrics, readTopHostHeightPx())
                    }?.index ?: return
            val anchor =
                displayedDepthAnchor(
                    deepestVisibleIndex = deepest,
                    itemCount = items.itemCount,
                    peek = items::peek,
                    policy = visibilityPolicy,
                ) ?: return
            onFurthestDisplayedChanged(anchor)
        }
        LaunchedEffect(initialPositionSettled, listState, visibilityPolicy, items, presentationLease, payloadAllowed) {
            if (!initialPositionSettled) return@LaunchedEffect
            snapshotFlow { listState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { recordFurthestDisplayed() }
        }
        DisposableEffect(initialPositionSettled, listState, visibilityPolicy, items, presentationLease, payloadAllowed) {
            onDispose { recordFurthestDisplayed() }
        }

        LaunchedEffect(initialPositionSettled, listState, items, presentationLease, payloadAllowed) {
            snapshotFlow {
                // While scrolling, deliberately stop observing itemCount/index. snapshotFlow then
                // unregisters those hot reads until the idle edge, preventing a DB query restart for
                // every row crossed by a fling while keeping the last reaction map on screen.
                if (!initialPositionSettled || listState.isScrollInProgress) {
                    null
                } else {
                    Triple(
                        items.itemCount,
                        listState.firstVisibleItemIndex,
                        listState.layoutInfo.visibleItemsInfo
                            .lastOrNull { it.index >= 0 && it.index < items.itemCount }
                            ?.index ?: -1,
                    )
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
        LaunchedEffect(items, listState, timelineSeams.seams, readerOwnsOlderPaging) {
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
            if (!readerOwnsOlderPaging) {
                reportDemand("retained_link", firstIndex = -1, lastIndex = -1, anchor = null, gapIds = emptySet())
                return@LaunchedEffect
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
                val layout = listState.layoutInfo
                val bottomHostHeightPx = readBottomHostHeightPx()
                val first =
                    layout.visibleItemsInfo.firstOrNull {
                        timelineItemIsVisible(it, layout, bottomHostHeightPx, rowContentMetrics, readTopHostHeightPx())
                    }
                val last =
                    layout.visibleItemsInfo.lastOrNull {
                        timelineItemIsVisible(it, layout, bottomHostHeightPx, rowContentMetrics, readTopHostHeightPx())
                    }
                if (first == null || last == null) {
                    null
                } else {
                    val newestIndex = first.index
                    // Peek the newer edge INSIDE the snapshot read, not after it. The reader's position
                    // is that row's IDENTITY, and a placeholder resolving under a stationary viewport
                    // changes the identity without changing any index — this effect is edge-triggered,
                    // so an identity that is not a snapshot dependency would never be re-reported and a
                    // seam the viewport could not name would wait forever.
                    Triple(
                        newestIndex,
                        last.index,
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

        var sheetRequest by remember(state.buffer?.id) { mutableStateOf<MessageActionRequest?>(null) }
        var selectionTarget by remember(state.buffer?.id) { mutableStateOf<MessageEntity?>(null) }
        val latestSelectionBufferId by rememberUpdatedState(state.buffer?.id)
        var selectionBounds by remember { mutableStateOf(Rect.Zero) }
        var selectionHostOrigin by remember { mutableStateOf(Offset.Zero) }

        fun canSelectMessage(target: MessageEntity): Boolean {
            if (plainIrcText(target.text).isBlank()) return false
            val expanded = if (latestExpandAllFools) target.id !in collapsedFools else target.id in expandedFools
            return when (visibilityPolicy.foolPreferences(target)?.visibility) {
                null, FoolsMode.DIMMED -> true
                FoolsMode.BLUR, FoolsMode.COLLAPSE -> expanded
                FoolsMode.HIDE -> hiddenFoolsRevealed && !restoringHiddenFools && expanded
            }
        }
        // A local callable reference compares equal across compositions despite changed captures.
        val latestCanSelectMessage by rememberUpdatedState { target: MessageEntity -> canSelectMessage(target) }
        val activeSelectionId = selectionTarget?.takeIf(::canSelectMessage)?.id
        LaunchedEffect(selectionTarget, activeSelectionId) {
            if (activeSelectionId == null) selectionTarget = null
        }
        androidx.activity.compose.BackHandler(enabled = activeSelectionId != null) { selectionTarget = null }
        var redactionTarget by remember { mutableStateOf<MessageEntity?>(null) }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var reactionPickerTarget by remember { mutableStateOf<MessageEntity?>(null) }
        val reactionPickerState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var translationTarget by remember { mutableStateOf<MessageEntity?>(null) }
        var translationTargets by remember { mutableStateOf(emptyList<ExternalTranslationTarget>()) }
        var otherTranslationAvailable by remember { mutableStateOf(false) }

        fun openTranslationPicker(message: MessageEntity) {
            if (externalTranslationText(message) == null) return
            translationTargets = externalTranslationTargets(ctx.packageManager)
            otherTranslationAvailable = externalTranslationAvailable(ctx.packageManager, null)
            translationTarget = message
        }

        fun launchTranslation(
            message: MessageEntity,
            target: ExternalTranslationTarget?,
        ) {
            scope.launch {
                if (!externalTranslationAvailable(ctx.packageManager, target)) {
                    snackbarHostState.showSnackbar(resources.getString(R.string.chat_translation_open_failed))
                    return@launch
                }
                val begin = onBeginExternalTranslation
                val abort = onAbortExternalTranslationLaunch
                if (begin == null || onCompleteExternalTranslation == null || abort == null) {
                    snackbarHostState.showSnackbar(resources.getString(R.string.chat_translation_source_unavailable))
                    return@launch
                }
                val request = begin(message)
                if (request == null) {
                    snackbarHostState.showSnackbar(
                        resources.getString(
                            if (latestExternalTranslationInFlight) R.string.chat_translation_busy else R.string.chat_translation_source_unavailable,
                        ),
                    )
                    return@launch
                }
                val intent = externalTranslationIntent(request.plainText, target, resources.getString(R.string.chat_translation_picker_title))
                try {
                    if (target?.action == Intent.ACTION_SEND) {
                        // SEND is an external-only handoff; release the native-result reservation first.
                        abort()
                        ctx.startActivity(intent)
                    } else {
                        translationLauncher.launch(intent)
                    }
                } catch (_: ActivityNotFoundException) {
                    abort()
                    snackbarHostState.showSnackbar(resources.getString(R.string.chat_translation_open_failed))
                } catch (_: SecurityException) {
                    abort()
                    snackbarHostState.showSnackbar(resources.getString(R.string.chat_translation_open_failed))
                }
            }
        }

        fun reactionEnabled(
            target: MessageEntity,
            emoji: String,
        ): Boolean {
            val ready = state.connState as? IrcClientState.Ready
            val mine =
                target.msgid?.let { msgid ->
                    reactionChips(msgid).firstOrNull { it.emoji == emoji }?.mine
                } == true
            return ready != null && canSendReactionTags(ready.caps, ready.isupport, remove = mine)
        }

        ReportMessageViewport(
            items = items,
            listState = listState,
            policy = visibilityPolicy,
            enabled = initialPositionSettled && payloadAllowed && jumpTarget?.retainedLinkRoomId == null,
            scopeId = state.buffer?.id,
            followingAtBottom = autoFollow.following && atBottom,
            onAnchor = onViewportRefreshAnchor,
            bottomContentPadding = bottomHostPadding,
            topContentPadding = topHostPadding,
            rowContentMetrics = rowContentMetrics,
        )

        // Newest row the timeline has actually placed on screen. Neither rawNewestAnchor (the room's
        // newest stored row) nor the Paging snapshot proves display — both keep advancing while the
        // screen is paused — but a measure pass does. Monotonic, so scrolling back through history
        // never retracts what was already seen. Reverse layout: the first visible item is the newest.
        var renderedNewestAnchor by remember(items, listState, visibilityPolicy) {
            mutableStateOf<io.github.trevarj.motd.data.db.TimelineAnchor?>(null)
        }
        LaunchedEffect(items, listState, visibilityPolicy) {
            snapshotFlow {
                val layout = listState.layoutInfo
                val bottomHostHeightPx = readBottomHostHeightPx()
                val laidOut =
                    layout.visibleItemsInfo.firstOrNull {
                        timelineItemIsVisible(it, layout, bottomHostHeightPx, rowContentMetrics, readTopHostHeightPx())
                    }
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
        val newestLiveEntryNotRendered by remember(items, visibilityPolicy) {
            derivedStateOf {
                awaitingLiveRender?.let { pending -> renderedNewestAnchor?.let { it < pending } != false } == true
            }
        }

        // Count nested programmatic scrolls rather than using a Boolean: an incoming pin may supersede
        // an explicit animation, and the cancelled animation must not briefly masquerade as a user drag.
        var programmaticScrolls by remember { mutableIntStateOf(0) }
        val autoScrolling = programmaticScrolls > 0
        val userScrolling by remember(listState) {
            derivedStateOf { listState.isScrollInProgress && programmaticScrolls == 0 }
        }
        val flightToken = outgoingFlight?.token
        var handoff by remember(flightAnchors) { mutableStateOf<SendFlightHandoff?>(null) }
        val imeInsets = WindowInsets.ime
        val imeDensity = LocalDensity.current

        fun sampleFlightViewport() =
            SendFlightViewport(
                eligible = handoff == null && initialPositionSettled && atBottom && !listState.isScrollInProgress,
                imeBottom = imeInsets.getBottom(imeDensity),
            )

        fun captureFlightLaunch() = flightAnchors.captureLaunch(sampleFlightViewport())

        val launchViewport =
            remember(flightToken, flightAnchors) {
                if (flightToken == null) null else flightAnchors.beginFlight(resetLanding = handoff == null) ?: sampleFlightViewport()
            }
        val flightMotion =
            remember(flightToken, flightAnchors) {
                SendFlightMotion(
                    morphEnabled =
                        outgoingFlight != null && outgoingFlight.replyText == null &&
                            conversationLayout.effective == LayoutDensity.COMFORTABLE,
                )
            }
        val landingMotion = handoff?.motion ?: flightMotion
        val flightProgress = remember(landingMotion) { { landingMotion.progress.value } }
        val ghostFlight = outgoingFlight.takeIf { handoff == null && launchViewport?.eligible == true && !flightMotion.detached }
        val landingFlight = handoff?.landingFlight ?: ghostFlight
        val holdEntriesForFlight = ghostFlight?.token.takeUnless { userScrolling }
        val latestOutgoingFlight by rememberUpdatedState(outgoingFlight)
        LaunchedEffect(flightToken, flightAnchors) {
            val flight = outgoingFlight ?: return@LaunchedEffect
            try {
                if (ghostFlight == null || launchViewport == null) return@LaunchedEffect

                fun detachFlight() {
                    val current = latestOutgoingFlight?.takeIf { it.token == flight.token } ?: flight
                    handoff = flightMotion.detach(current, flightAnchors.landingRow)
                }

                val animation =
                    launch {
                        val lift = launch { flightMotion.lift.animateTo(1f, MotdMotion.sendFlightSpring) }
                        val morph =
                            if (flightMotion.morphEnabled) {
                                launch { flightMotion.morph.animateTo(1f, MotdMotion.sendMorphGrow) }
                            } else {
                                null
                            }
                        val landing =
                            withTimeoutOrNull(1_000L) {
                                snapshotFlow { flightAnchors.landingRow }.filterNotNull().first()
                            }
                        if (landing == null) {
                            detachFlight()
                            lift.cancel()
                            morph?.cancel()
                        } else {
                            flightMotion.progress.animateTo(1f, MotdMotion.sendFlightSpring)
                            lift.join()
                            morph?.join()
                        }
                    }
                val viewportMotion =
                    launch {
                        snapshotFlow { listState.isScrollInProgress || imeInsets.getBottom(imeDensity) != launchViewport.imeBottom }.first { it }
                        if (animation.isActive && handoff == null) {
                            detachFlight()
                            animation.cancel()
                        }
                    }
                animation.join()
                viewportMotion.cancel()
            } finally {
                onFlightSettled(flight.token)
            }
        }
        LaunchedEffect(handoff) {
            val active = handoff ?: return@LaunchedEffect
            try {
                val reveal =
                    launch {
                        snapshotFlow { flightAnchors.landingRow }.filterNotNull().first()
                        active.motion.progress.animateTo(1f, MotdMotion.sendFlightHandoff)
                    }
                active.drain.animateTo(1f, MotdMotion.sendFlightHandoff)
                if (flightAnchors.landingRow == null) reveal.cancel()
                reveal.join()
            } finally {
                if (handoff === active) handoff = null
            }
        }

        suspend fun scrollToNewest(
            animate: Boolean,
            reason: String,
        ) {
            onViewportRefreshAnchor(if (presentationLease != null) ViewportRefreshAnchor.Newest else null)
            autoFollow.requestFollow()
            val settlingAdoption = presentationLease != null && !initialPositionSettled
            if (!settlingAdoption && !listState.isScrollInProgress &&
                listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            ) {
                return
            }
            AutoFollowTrace.record("scroll_start", traceBufferId, traceSessionId) {
                "reason=$reason animate=$animate index=${listState.firstVisibleItemIndex} " +
                    "offset=${listState.firstVisibleItemScrollOffset} following=${autoFollow.following}"
            }
            programmaticScrolls++
            try {
                if (settlingAdoption) {
                    // An explicit newest/send action owns this demand even while automatic hints are
                    // still gated. The old retained window may not contain index zero.
                    if (items.itemCount == 0 || items.peek(0) == null) items.refresh()
                    snapshotFlow {
                        items.itemCount > 0 ||
                            initialPagingPage(items.itemCount, items.loadState.append) == InitialPagingPage.TerminalEmpty
                    }.first { it }
                    if (items.itemCount > 0) items[0]
                }
                if (animate) listState.animateScrollToItem(0) else listState.scrollToItem(0)
                if (settlingAdoption && !initialPositionSettled) {
                    snapshotFlow {
                        latestEntryState is EntryPositionState.Settled && atBottom && !listState.isScrollInProgress
                    }.first { it }
                    admitPayload()
                    initialPositionSettled = true
                    suppressNextAutoFollow = true
                }
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
            onViewportRefreshAnchor(null)
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
        LaunchedEffect(listState, initialPositionSettled, presentationLease) {
            if (!initialPositionSettled) return@LaunchedEffect
            snapshotFlow { listState.isScrollInProgress to (programmaticScrolls > 0) }
                .distinctUntilChanged()
                .collect { (scrolling, programmatic) ->
                    // The same edge auto-follow reads as "the user is taking over", reported to the
                    // ViewModel so its one post-catch-up entry correction stands down. Programmatic
                    // scrolls (entry placement, the newest FAB, a send) are excluded here; the actions
                    // that issue them retire the correction themselves.
                    if (scrolling && !programmatic) {
                        if (linkOwnsOlderPaging && !positioningRetainedLink) setLinkPagingOwnership(false)
                        onTimelineInteraction()
                        onViewportRefreshAnchor(null)
                    }
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
        LaunchedEffect(items, initialPositionSettled, visibilityPolicy, payloadAllowed) {
            if (!initialPositionSettled) return@LaunchedEffect
            snapshotFlow {
                items.itemCount to newestEffectiveMessageId(items.itemCount, items::peek, visibilityPolicy)
            }.distinctUntilChanged()
                .collect { (newCount, newestEffectiveId) ->
                    val oldCount = autoFollow.presentedItemCount
                    val followingBefore = autoFollow.following
                    if (suppressNextAutoFollow || (temporaryFoolMotion && !payloadAllowed)) {
                        autoFollow.reset(newCount, atBottom, newestEffectiveId)
                        liveEntryIds = emptySet()
                        suppressNextAutoFollow = temporaryFoolMotion && !payloadAllowed
                        AutoFollowTrace.record("paging_initial", traceBufferId, traceSessionId) {
                            "old_count=$oldCount new_count=$newCount at_bottom=$atBottom " +
                                "following=${autoFollow.following} refresh=${loadStateName(items.loadState.refresh)} " +
                                "append=${loadStateName(items.loadState.append)}"
                        }
                    } else {
                        val change = autoFollow.onTimelineChangedWithEntry(newCount, newestEffectiveId)
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
        // Entry positioning owns the viewport until settled; a drag, fling or explicit scroll
        // also takes precedence. While following, the only correction here is for a same-ID echo
        // that moves to the head without qualifying as a new arrival above. Otherwise, pin a
        // parked reader only when they are not already at the bottom.
        //
        // The collector never suspends, so it keeps up with the conflated presentation flow and the
        // request reaches the same remeasure that presents the update.
        val latestPayloadAllowed by rememberUpdatedState(payloadAllowed)
        val explicitJumpPending by rememberUpdatedState(jumpTarget != null)
        LaunchedEffect(items, listState, initialPositionSettled) {
            if (!initialPositionSettled) return@LaunchedEffect
            var previous: ItemSnapshotList<MessageEntity>? = null
            snapshotFlow { items.itemSnapshotList }.collect { snapshot ->
                val prior = previous
                previous = snapshot
                if (prior == null) return@collect
                if (listState.isScrollInProgress || programmaticScrolls > 0) return@collect
                if (autoFollow.following) {
                    // Exact jump placement also uses requestScrollToItem, so scroll state alone
                    // cannot tell whether the next remeasure belongs to the jump.
                    if (!explicitJumpPending && latestPayloadAllowed && !suppressNextAutoFollow &&
                        hasConfirmedOwnHeadPromotion(prior, snapshot)
                    ) {
                        listState.requestScrollToItem(0)
                    }
                    return@collect
                }
                if (atBottom) return@collect
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
            payloadAllowed,
            holdEntriesForFlight,
            newestLiveEntryNotRendered,
        ) {
            if (!viewportReadEnabled || !payloadAllowed || !currentPresentationCompatible || presentationLease?.isActive == false) {
                pausedSinceLastRun.value = true
                return@LaunchedEffect
            }
            if (holdEntriesForFlight != null || newestLiveEntryNotRendered) return@LaunchedEffect
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
                    viewportReadEnabled = viewportReadEnabled && payloadAllowed,
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
        val compactHeader by remember {
            derivedStateOf {
                // Keep 48dp controls out until the animated bar can contain them without jumping.
                36.dp + (TopAppBarDefaults.TopAppBarExpandedHeight - 36.dp) * headerExpansion.value < 48.dp
            }
        }
        val conversationActionsMenu: @Composable () -> Unit = {
            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("chat_overflow_menu")) {
                if (compactHeader) {
                    if (titleClickLabel != null) {
                        DropdownMenuItem(
                            modifier = Modifier.testTag("chat_compact_details"),
                            text = { Text(titleClickLabel) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null) },
                            onClick = {
                                overflowOpen = false
                                buffer?.let { onOpenChannelInfo(it.id) }
                            },
                        )
                    }
                    onOpenConversationList?.let { openConversationList ->
                        DropdownMenuItem(
                            modifier = Modifier.testTag("chat_open_conversation_list"),
                            text = { Text(stringResource(R.string.dickord_portal_channels)) },
                            onClick = {
                                overflowOpen = false
                                openConversationList()
                            },
                        )
                    }
                    DropdownMenuItem(
                        modifier = Modifier.testTag("chat_compact_search"),
                        text = { Text(stringResource(R.string.chat_search)) },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            buffer?.let { onOpenSearch(it.id) }
                        },
                    )
                }
                if (onOpenConversationLinks != null && (buffer?.type == BufferType.CHANNEL || buffer?.type == BufferType.QUERY)) {
                    DropdownMenuItem(
                        modifier = Modifier.testTag("chat_open_links"),
                        text = { Text(stringResource(R.string.chat_links_title)) },
                        leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onOpenConversationLinks()
                        },
                    )
                }
                if (showScheduleActions) {
                    val canScheduleDraft =
                        composerEnabled && composerDraft.hydrated && buffer.canScheduleMessage() && scheduleDraftRejection(composerText.text) == null
                    DropdownMenuItem(
                        modifier = Modifier.testTag("scheduled_open_chat"),
                        text = { Text(stringResource(if (canScheduleDraft) R.string.scheduled_open else R.string.scheduled_title)) },
                        leadingIcon = { Icon(Icons.Outlined.DateRange, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            if (canScheduleDraft) {
                                openSchedule()
                            } else {
                                scheduleFocus.clearFocus()
                                scheduleKeyboard?.hide()
                                scheduledComposer.openList(buffer.id)
                            }
                        },
                    )
                }
                val showInvite = buffer?.type == BufferType.CHANNEL && buffer.joined
                if (showInvite) {
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
                if (fools.isNotEmpty()) {
                    val foolsShown = expandAllFools && !restoringHiddenFools
                    DropdownMenuItem(
                        modifier = Modifier.testTag("chat_toggle_fools_visibility"),
                        text = {
                            Column {
                                Text(stringResource(if (foolsShown) R.string.chat_fool_collapse_all else R.string.chat_fool_expand_all))
                                Text(stringResource(R.string.settings_chat_visit_scope), style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = {
                            overflowOpen = false
                            if (!foolsShown) {
                                restoreDispatched = false
                                restoringHiddenFools = false
                                onExpandAllFoolsChange(true)
                            } else if (initialPositionSettled && entryVeilLifted && entryState is EntryPositionState.Settled &&
                                initialTarget == null && jumpTarget == null && admittedDrawingSpec != null && presentationContextCompatible
                            ) {
                                restorePolicy = visibleSpec
                                restoreIntent = entryPositionIntent
                                temporaryFoolToggle = true
                                restoreDispatched = false
                                restoringHiddenFools = true
                            } else {
                                onExpandAllFoolsChange(false)
                            }
                            expandedFools = emptySet()
                            collapsedFools = emptySet()
                        },
                        leadingIcon = {
                            Icon(
                                if (foolsShown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null,
                            )
                        },
                    )
                }

                if (compactHeader || showScheduleActions || showInvite || fools.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp).testTag("chat_overflow_actions_divider"))
                }

                val isConversation = buffer != null && (buffer.type == BufferType.CHANNEL || buffer.type == BufferType.QUERY)
                if (isConversation) {
                    DropdownMenuItem(
                        modifier = Modifier.testTag("chat_read_aloud_toggle").semantics { selected = readAloudState.enabled },
                        text = {
                            Column {
                                Text(stringResource(if (readAloudState.enabled) R.string.read_aloud_stop else R.string.read_aloud_start))
                                Text(stringResource(R.string.settings_chat_visit_scope), style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        leadingIcon = { Icon(Icons.Outlined.RecordVoiceOver, null) },
                        onClick = {
                            overflowOpen = false
                            onReadAloudToggle()
                        },
                    )
                    DropdownMenuItem(
                        modifier = Modifier.testTag("chat_read_aloud_options"),
                        text = { Text(stringResource(R.string.read_aloud_options)) },
                        leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onReadAloudOptions()
                            readAloudOptionsOpen = true
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp).testTag("chat_overflow_settings_divider"))
                }

                if (buffer?.type == BufferType.CHANNEL) {
                    DropdownMenuItem(
                        modifier = Modifier.testTag("chat_watch").semantics { selected = !channelNotifications.loading && channelNotifications.available && channelNotifications.watch != null },
                        text = {
                            Column {
                                Text(stringResource(R.string.channelinfo_notifications))
                                Text(channelNotifications.summary(), style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        leadingIcon = {
                            Icon(
                                channelNotifications.notificationIcon(),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            overflowOpen = false
                            notificationSheetOpen = true
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
                                    when {
                                        conversationLayout.override != null -> R.string.chat_layout_overflow_override
                                        conversationLayout.bufferDefault != null -> R.string.chat_layout_overflow_server
                                        else -> R.string.chat_layout_overflow_global
                                    },
                                    densityLabel(conversationLayout.effective),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    leadingIcon = { Icon(Icons.Outlined.ViewAgenda, contentDescription = null) },
                    onClick = {
                        overflowOpen = false
                        conversationLayoutSheetOpen = true
                    },
                )
                if (onOpenWallpaperWorkshop != null && isConversation) {
                    DropdownMenuItem(
                        modifier = Modifier.testTag("chat_wallpaper_menu"),
                        text = {
                            Column {
                                Text(stringResource(R.string.settings_wallpaper))
                                Text(
                                    wallpaperName ?: stringResource(R.string.wallpaper_workshop_inherit_global),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onOpenWallpaperWorkshop(buffer.id)
                        },
                    )
                }
                if (isConversation) {
                    val historySync = state.conversationHistorySync
                    DropdownMenuItem(
                        modifier = Modifier.testTag("chat_history_sync_menu"),
                        text = {
                            Column {
                                Text(stringResource(R.string.settings_history_sync_mode))
                                Text(
                                    if (historySync.override == null) {
                                        stringResource(
                                            R.string.chat_history_sync_inherit,
                                            stringResource(historySyncModeLabel(historySync.global)),
                                        )
                                    } else {
                                        stringResource(historySyncModeLabel(historySync.effective))
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        leadingIcon = { Icon(Icons.Outlined.Sync, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            historySyncModeSheetOpen = true
                        },
                    )
                }
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
                    leadingIcon = { Icon(Icons.Outlined.PeopleOutline, contentDescription = null) },
                    onClick = {
                        overflowOpen = false
                        presenceModeSheetOpen = true
                    },
                )
            }
        }
        Scaffold(
            modifier =
                Modifier
                    .onGloballyPositioned { selectionHostOrigin = it.positionInWindow() }
                    .pointerInput(activeSelectionId) {
                        if (activeSelectionId != null) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                if (!selectionBounds.contains(down.position + selectionHostOrigin)) selectionTarget = null
                            }
                        }
                    },
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
                ) { data ->
                    if (data.visuals is ScheduledConfirmation) {
                        ScheduledConfirmationSnackbar(data)
                    } else {
                        io.github.trevarj.motd.ui.components
                            .EmojiSnackbar(data)
                    }
                }
            },
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
            topBar = {
                Column {
                    val expansion = headerExpansion.value
                    TopAppBar(
                        modifier =
                            Modifier.effectSurface(EffectSurface.CHAT_HEADER, MaterialTheme.colorScheme.surfaceContainerLow).testTag("chat_top_app_bar").onGloballyPositioned {
                                if (presentationLease?.ownsSlot() != false) {
                                    if (placingTopEntry) laidOutEntryHeaderExpansion = expansion
                                    if (payloadAllowed) presentation?.headerExpansion = expansion
                                }
                            },
                        // Material grows beyond this minimum when accessibility text needs more room.
                        expandedHeight = 36.dp + (TopAppBarDefaults.TopAppBarExpandedHeight - 36.dp) * expansion,
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                                scrolledContainerColor = Color.Transparent,
                            ),
                        title = {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = if (compactHeader) 36.dp else 48.dp)
                                        .then(
                                            if (!compactHeader && titleClickLabel != null) {
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
                                    val remoteAvatars = LocalRemoteAvatars.current
                                    val dickordDirectMessage =
                                        dickordDescriptor?.let { isDiscordDirectMessage(it.channelType) } == true
                                    CompositionLocalProvider(
                                        LocalRemoteAvatars provides
                                            if (dickordDirectMessage) RemoteAvatarState() else remoteAvatars,
                                    ) {
                                        Avatar(
                                            name = if (dickordDirectMessage) conversationLabel.orEmpty() else buffer?.displayName.orEmpty(),
                                            modifier = Modifier.testTag("chat_header_avatar"),
                                            size = 20.dp + (MotdSizes.headerAvatar - 20.dp) * headerExpansion.value,
                                            isChannel = !dickordDirectMessage && buffer?.type == BufferType.CHANNEL,
                                            networkId = buffer?.networkId,
                                            conversationModel =
                                                if (dickordDirectMessage) {
                                                    dickordDescriptor.channelIconUrl.takeIf { remoteAvatars.enabled }
                                                } else {
                                                    buffer?.avatarOverrideModel
                                                },
                                        )
                                    }
                                }
                                // The 10dp gap only separates the title from the avatar beside it.
                                val subtitleModel =
                                    chatSubtitleModel(
                                        state,
                                        ctx,
                                        dickordEnabled,
                                        includeTyping = typingIndicatorPlacement == io.github.trevarj.motd.data.prefs.TypingIndicatorPlacement.HEADER,
                                    )
                                Column(modifier = Modifier.padding(start = if (hideAvatar) 0.dp else 10.dp).weight(1f)) {
                                    if (compactHeader && subtitleModel is ChatSubtitleModel.Text && subtitleModel.isMemberCount) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                text = conversationLabel.orEmpty(),
                                                modifier = Modifier.weight(1f, fill = false),
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = subtitleModel.value,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = conversationLabel.orEmpty(),
                                            style = if (compactHeader) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        AnimatedContent(
                                            targetState = subtitleModel,
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

                                                is ChatSubtitleModel.Typing -> {
                                                    TypingIndicatorRow(subtitle.value, singleLine = true)
                                                }

                                                null -> {}
                                            }
                                        }
                                    }
                                }
                                ChatTitleSyncSpinner(timelineHistoryStatus)
                                if (!compactHeader && titleTarget != ChatTitleTarget.NONE) {
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
                            if (!compactHeader && showBack) {
                                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.chat_back),
                                    )
                                }
                            }
                        },
                        actions = {
                            if (!compactHeader) {
                                onOpenConversationList?.let { openConversationList ->
                                    TextButton(
                                        colors = readableTextButtonColors(),
                                        onClick = openConversationList,
                                        modifier = Modifier.heightIn(min = 48.dp).testTag("chat_open_conversation_list"),
                                    ) {
                                        Text(stringResource(R.string.dickord_portal_channels))
                                    }
                                }
                                IconButton(onClick = { buffer?.let { onOpenSearch(it.id) } }, modifier = Modifier.size(48.dp)) {
                                    Icon(
                                        Icons.Outlined.Search,
                                        contentDescription = stringResource(R.string.chat_search),
                                    )
                                }
                                IconButton(
                                    onClick = { overflowOpen = true },
                                    modifier = Modifier.size(48.dp).testTag("chat_overflow"),
                                ) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                                }
                                conversationActionsMenu()
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
                                TextButton(
                                    onClick = onDismissAccountSetup,
                                    colors =
                                        readableTextButtonColors(
                                            background = MaterialTheme.colorScheme.secondaryContainer,
                                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                        ),
                                ) { Text(stringResource(R.string.account_reminder_not_now)) }
                                Button(onClick = onAccountSetup, modifier = Modifier.testTag("chat_account_setup")) {
                                    Text(stringResource(R.string.account_setup_title))
                                }
                            }
                        }
                    }
                    if (preparingDccSend) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .semantics {
                                        liveRegion = LiveRegionMode.Polite
                                        testTagsAsResourceId = true
                                    }.testTag("chat_dcc_send_preparing"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.dcc_send_preparing))
                        }
                    }
                }
            },
        ) { padding ->
            if (topHostPadding !== padding) topHostPadding = padding
            val consumedWallpaperInsets = remember { mutableStateOf<WindowInsets>(WindowInsets(0)) }
            Box(
                modifier = Modifier.fillMaxSize().onConsumedWindowInsetsChanged { consumedWallpaperInsets.value = it },
            ) {
                val effectiveBlur = conversationLayout.effectiveMessageBackgroundBlur(LocalMessageBackgroundBlur.current)
                val wallpaperBackdrop = rememberMessageWallpaperBackdrop(effectiveBlur)
                val wallpaperIme = WindowInsets.ime
                val wallpaperDensity = LocalDensity.current
                val photoLoaded = rememberWallpaperPhotoLoaded(chatWallpaper)
                val photoVisible = photoLoaded.value
                val wallpaperBacking = wallpaperNeedsReadableBacking(chatWallpaper, photoVisible)
                val wallpaperCanvas = MaterialTheme.colorScheme.background
                val wallpaperTag =
                    remember(chatWallpaper.selection, photoVisible) {
                        val selection = chatWallpaper.selection
                        when {
                            photoVisible -> {
                                "chat_wallpaper_photo"
                            }

                            selection.preset == ChatWallpaperPreset.PATTERN -> {
                                val ref = selection.patternRef
                                if (ref == null) "chat_wallpaper_pattern_default" else "chat_wallpaper_pattern_${ref.kind.name.lowercase()}_${ref.id}"
                            }

                            else -> {
                                "chat_wallpaper_${selection.preset.name.lowercase()}"
                            }
                        }
                    }
                ChatWallpaperBackground(
                    chatWallpaper,
                    modifier =
                        Modifier
                            .matchParentSize()
                            .testTag(wallpaperTag)
                            .captureSurfaceBackdrop(wallpaperSource)
                            .recordMessageWallpaper(wallpaperBackdrop, MaterialTheme.colorScheme.surface)
                            .then(
                                if (showWallpaper) Modifier else Modifier.drawWithContent { drawRect(wallpaperCanvas) },
                            ).then(
                                if (chatWallpaper.selection.preset != ChatWallpaperPreset.NONE || chatWallpaper.selection.localImageName != null) {
                                    Modifier.fadeChatEdges(
                                        wallpaperCanvas,
                                        readTopHostHeightPx,
                                        readBottomHostHeightPx,
                                        readBottomOffsetPx = {
                                            (wallpaperIme.getBottom(wallpaperDensity) - consumedWallpaperInsets.value.getBottom(wallpaperDensity)).coerceAtLeast(0)
                                        },
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    photoLoaded = photoLoaded,
                )
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
                // sibling drawn over the composer) read it. Deferred until list placement or
                // ghost drawing, never read during composition.
                val density = LocalDensity.current
                val flightListShift =
                    remember(
                        ghostFlight?.token,
                        handoff,
                        flightMotion,
                        conversationSpacing,
                        density,
                    ) {
                        val active = ghostFlight != null
                        val interruption = handoff
                        val gapPx =
                            with(density) {
                                bubbleGap(flightShowSender, items.itemCount > 0, conversationSpacing).toPx()
                            }
                        val shift: () -> Float = shift@{
                            if (!active && interruption == null) return@shift 0f
                            val revealed = flightAnchors.landingRow?.second?.height ?: 0f
                            if (interruption != null) return@shift interruption.residualShift(revealed)
                            sendFlightListShift(
                                runwayHeight = flightAnchors.ghostHeight + gapPx,
                                liftFraction = flightMotion.lift.value,
                                revealedGap = revealed,
                                footDrop = flightAnchors.composerFootDrop(),
                            )
                        }
                        shift
                    }
                val timelineFlightShift =
                    remember(flightListShift, flightMotion) {
                        { flightMotion.recordDrawnShift(flightListShift()) }
                    }
                ConversationTypography(conversationFontScalePercent) {
                    // TopAppBar owns the status-bar inset. Only the foreground follows Scaffold padding;
                    // the wallpaper keeps a fixed origin and size while the header and IME animate.
                    // Only the bottom host consumes navigation padding, so messages scroll under the gesture handle.
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(start = padding.calculateStartPadding(LocalLayoutDirection.current), end = padding.calculateEndPadding(LocalLayoutDirection.current), bottom = padding.calculateBottomPadding())
                                .imePadding(),
                    ) {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            contentWindowInsets = WindowInsets(0),
                            containerColor = Color.Transparent,
                            bottomBar = {
                                Column(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .testTag("chat_bottom_host")
                                            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
                                ) {
                                    AboveComposerTyping(
                                        summary =
                                            if (typingIndicatorPlacement == io.github.trevarj.motd.data.prefs.TypingIndicatorPlacement.ABOVE_COMPOSER &&
                                                atTimelineLiveEnd.value
                                            ) {
                                                chatTypingSummary(state, ctx, dickordEnabled)
                                            } else {
                                                null
                                            },
                                    )
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
                                            channel = conversationLabel.orEmpty(),
                                            onRejoin = onRejoin,
                                        )
                                    }
                                    if (eligibleEbooksRoomId != null) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(
                                                colors = readableTextButtonColors(),
                                                onClick = { ebooksHelpOpen = true },
                                                modifier = Modifier.weight(1f).testTag("chat_ebooks_help"),
                                            ) { Text("Book search · How it works") }
                                            TextButton(
                                                colors = readableTextButtonColors(),
                                                onClick = {
                                                    pickingEbooksRoomId = eligibleEbooksRoomId
                                                    ebooksResultsPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                                                },
                                                modifier = Modifier.testTag("chat_ebooks_open_results"),
                                            ) { Text("Open results ZIP") }
                                        }
                                        TextButton(
                                            colors = readableTextButtonColors(),
                                            onClick = { ebooksOffersOpen = true },
                                            modifier = Modifier.fillMaxWidth().testTag("chat_ebooks_dcc_offers"),
                                        ) {
                                            Text(
                                                if (visibleEbooksOffers.isEmpty()) {
                                                    "DCC offers (0) · No offers yet"
                                                } else {
                                                    "DCC offers (${visibleEbooksOffers.size})"
                                                },
                                            )
                                        }
                                    }
                                    activeComposerUpload?.imageReceipt?.let { (url, uploadedAt) ->
                                        val containsUpload =
                                            remember(composerText.text, url) {
                                                composerText.text.containsDraftUploadUrl(url)
                                            }
                                        if (containsUpload && attachmentViewModel != null) {
                                            val recent by attachmentViewModel.recent.collectAsStateWithLifecycle()
                                            recent
                                                .firstOrNull { it.url == url && it.uploadedAt == uploadedAt }
                                                ?.let { UploadedImageDraftCard(it) }
                                        }
                                    }
                                    Composer(
                                        value = composerText,
                                        onAi = if (aiTextEnabled && plainIrcText(composerText.text).isNotBlank()) onAiComposer else null,
                                        onValueChange = {
                                            val previousText = composerText.text
                                            composerText = it
                                            if (it.text != previousText) {
                                                onDraftChanged(it.text)
                                                if (it.text.isNotBlank()) {
                                                    onTyping(true)
                                                } else if (previousText.isNotBlank()) {
                                                    onTyping(false)
                                                }
                                            }
                                        },
                                        onSend = {
                                            val text = composerText.text
                                            if (plainIrcText(text).isNotBlank() && !isEbooksSearchOnly(text)) {
                                                if (isLongDraft(text)) {
                                                    AutoFollowTrace.record("long_draft_prompt_open", traceBufferId, traceSessionId)
                                                    longDraftPrompt = true
                                                } else {
                                                    AutoFollowTrace.record("composer_submit", traceBufferId, traceSessionId) {
                                                        "long_draft=false"
                                                    }
                                                    captureFlightLaunch()
                                                    // Empty the field on the tap frame. The ViewModel still owns the
                                                    // durable draft and republishes it if the send never lands, so this
                                                    // is presentation only -- notifying onDraftChanged here would count
                                                    // as an edit and make the submission itself stale.
                                                    composerText = TextFieldValue("")
                                                    onSubmit(text)
                                                    scope.launch {
                                                        scrollToNewest(animate = true, reason = "composer_send_action")
                                                    }
                                                }
                                            }
                                        },
                                        enabled = composerEnabled,
                                        sendEnabled = !isEbooksSearchOnly(composerText.text),
                                        onSchedule =
                                            openSchedule.takeIf {
                                                showScheduleActions && buffer?.canScheduleMessage() == true && plainIrcText(composerText.text).isNotBlank() &&
                                                    !isEbooksSearchOnly(composerText.text)
                                            },
                                        composerStyle = composerStyle,
                                        currentNick = state.composerNick,
                                        onFieldPositioned = { flightAnchors.composerField = it },
                                        onFieldTextPositioned = { flightAnchors.composerTextOrigin = it },
                                        reply =
                                            (composerReply ?: lastComposerReply)?.let {
                                                ComposerReply(dickordNickLabel(it.sender, activeDickordChannel), it.text)
                                            },
                                        replyVisible = composerReply != null && !composerDraft.submittingReply,
                                        replyWarning =
                                            composerReply
                                                ?.takeIf {
                                                    !composerDraft.submittingReply && state.replySenderNotInChannel && state.replyTo?.id == it.id
                                                }?.let {
                                                    stringResource(
                                                        R.string.chat_reply_sender_not_in_channel,
                                                        dickordNickLabel(it.sender, activeDickordChannel),
                                                    )
                                                },
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
                                        knownNicks = knownNicks,
                                        identityRules = identityRules,
                                        commandContextIsChannel =
                                            when (state.buffer?.type) {
                                                BufferType.CHANNEL -> true
                                                BufferType.QUERY -> false
                                                BufferType.SERVER, null -> null
                                            },
                                        ircFormattingEnabled =
                                            !isServerBuffer &&
                                                !state.buffer?.displayName.equals("BouncerServ", ignoreCase = true),
                                        onAttachment = {
                                            uploadCurrentDraftDirectly = false
                                            attachmentSheetOpen = true
                                        },
                                        onImageContent = { uri, mime ->
                                            if (attachmentSheetOpen) {
                                                false
                                            } else {
                                                sharedFile = PendingShare.File(uri, mime)
                                                uploadCurrentDraftDirectly = false
                                                attachmentSheetOpen = true
                                                true
                                            }
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
                                                val candidates = completions.map { it.display }
                                                val isCommand = completions.firstOrNull()?.isCommand == true
                                                val completionNetworkId = state.buffer?.networkId
                                                val panel: @Composable () -> Unit = {
                                                    AutocompletePanel(
                                                        candidates = candidates,
                                                        isCommand = isCommand,
                                                        networkId = completionNetworkId,
                                                        onPick = { picked ->
                                                            composerText = applyPick(composerText, picked)
                                                            onDraftChanged(composerText.text)
                                                        },
                                                    )
                                                }
                                                panel
                                            } else {
                                                null
                                            },
                                    )
                                }
                            },
                        ) { innerPadding ->
                            // Scaffold measures the host before the full-area timeline in this same pass.
                            if (bottomHostPadding !== innerPadding) bottomHostPadding = innerPadding
                            Box(modifier = Modifier.fillMaxSize()) {
                                CompositionLocalProvider(
                                    LocalSpacing provides conversationSpacing,
                                    LocalCompactNickBrackets provides conversationLayout.effectiveCompactNickBrackets(globalCompactNickBrackets),
                                    LocalMessageBackgroundOpacityPercent provides conversationLayout.effectiveMessageBackgroundOpacity(LocalMessageBackgroundOpacityPercent.current),
                                    LocalMessageBackgroundBlur provides effectiveBlur,
                                    LocalMessageWallpaperBackdrop provides wallpaperBackdrop,
                                    LocalWallpaperNeedsReadableBacking provides wallpaperBacking,
                                ) {
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
                                        val onLiveEntryConsumed =
                                            remember(items, visibilityPolicy) {
                                                { id: Long -> liveEntryIds -= id }
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
                                            remember(state.buffer?.id) {
                                                { message: MessageEntity ->
                                                    selectionTarget = null
                                                    sheetRequest = MessageActionRequest(message)
                                                }
                                            }
                                        val onTimelineOpenReactions =
                                            remember(state.buffer?.id) {
                                                { message: MessageEntity ->
                                                    selectionTarget = null
                                                    sheetRequest = MessageActionRequest(message, initialReactionsTab = true)
                                                }
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
                                                    if (latestExpandAllFools) id !in collapsedFools else id in expandedFools
                                                }
                                            }
                                        // Bidirectional per-row toggle, respecting the global default.
                                        val onToggleFool =
                                            remember {
                                                { id: Long ->
                                                    if (latestExpandAllFools) {
                                                        collapsedFools =
                                                            if (id in collapsedFools) collapsedFools - id else collapsedFools + id
                                                    } else {
                                                        expandedFools =
                                                            if (id in expandedFools) expandedFools - id else expandedFools + id
                                                    }
                                                }
                                            }
                                        // Keep positioning hidden, then paint and release input together on
                                        // readiness; a post-placement fade only delays already-available rows.
                                        Box(
                                            modifier =
                                                Modifier
                                                    .fillMaxSize()
                                                    .captureSurfaceBackdrop(pageBackdrop)
                                                    .graphicsLayer {
                                                        alpha = if (timelineVisible) 1f else 0f
                                                        compositingStrategy = CompositingStrategy.Offscreen
                                                    }.fadeChatEdges(
                                                        Color.Black,
                                                        readTopHostHeightPx,
                                                        readBottomHostHeightPx,
                                                        blendMode = BlendMode.DstOut,
                                                    ).then(if (timelineVisible) Modifier else Modifier.clearAndSetSemantics {}),
                                        ) {
                                            val snapshot = items.itemSnapshotList
                                            val presented =
                                                snapshot.items.takeIf {
                                                    snapshot.placeholdersBefore == 0 && it.isNotEmpty() && it !== lastLivePresentation.rows
                                                }
                                            val arrivals =
                                                if (presented != null && initialPositionSettled && payloadAllowed && !suppressNextAutoFollow &&
                                                    autoFollow.following && !userScrolling
                                                ) {
                                                    leadingLiveEntries(
                                                        lastLivePresentation.rows,
                                                        lastLivePresentation.highWater,
                                                        presented,
                                                        visibilityPolicy,
                                                        !isServerBuffer,
                                                        landingFlight,
                                                    )
                                                } else {
                                                    emptySet()
                                                }
                                            val retained =
                                                lastLivePresentation.heldIds.takeIf { holdEntriesForFlight == lastLivePresentation.heldForFlight }.orEmpty()
                                            val held =
                                                when {
                                                    holdEntriesForFlight == null -> emptySet()
                                                    arrivals.isEmpty() -> retained
                                                    retained.size + arrivals.size > MAX_LIVE_ENTRANCE_BATCH -> retained
                                                    retained.isEmpty() -> arrivals
                                                    else -> retained + arrivals
                                                }
                                            val previousEntries = liveEntryIds
                                            val entries =
                                                if (presented == null) previousEntries else liveEntriesInPresentation(previousEntries, arrivals, presented, held)
                                            SideEffect {
                                                lastLivePresentation.heldForFlight = holdEntriesForFlight
                                                lastLivePresentation.heldIds = held
                                                if (presented != null) {
                                                    lastLivePresentation.rows = presented
                                                    lastLivePresentation.highWater = maxOf(lastLivePresentation.highWater, presented.maxOf { it.id })
                                                    awaitingLiveRender =
                                                        presented
                                                            .firstOrNull(visibilityPolicy::effectiveBottom)
                                                            ?.takeIf { it.id in entries }
                                                            ?.timelineAnchor()
                                                    if (entries != previousEntries) {
                                                        liveEntryIds =
                                                            if (liveEntryIds === previousEntries) {
                                                                entries
                                                            } else {
                                                                liveEntriesInPresentation(liveEntryIds, arrivals, presented, held)
                                                            }
                                                    }
                                                }
                                            }
                                            MessageList(
                                                items = items,
                                                listState = listState,
                                                modifier =
                                                    Modifier
                                                        .onGloballyPositioned {
                                                            if (presentationLease?.ownsSlot() == true) measuredSnapshot?.value = layoutSnapshot
                                                        }.then(entryLayoutModifier)
                                                        .nestedScroll(readerScrollInput),
                                                pagingHintsEnabled =
                                                    initialPositionSettled && payloadAllowed && entryPreparationReady && !positioningRetainedLink,
                                                readerOwnsOlderPaging = readerOwnsOlderPaging,
                                                onHistoryRetry = { setLinkPagingOwnership(false) },
                                                temporaryFoolVisibilityMotion = temporaryFoolMotion,
                                                hiddenFoolVisibility = hiddenFoolVisibility,
                                                preserveOutgoingHiddenBodies = preserveOutgoingHiddenBodies,
                                                bottomContentPadding = innerPadding,
                                                topContentPadding = padding.calculateTopPadding(),
                                                rowContentMetrics = rowContentMetrics,
                                                externalTranslations = externalTranslations,
                                                onDismissExternalTranslation = onDismissExternalTranslation,
                                                liveEntryIds = entries,
                                                onLiveEntryConsumed = onLiveEntryConsumed,
                                                outgoingFlight = landingFlight,
                                                heldLiveEntryIds = held,
                                                flownRowIds = flownRowIds,
                                                flightProgress = flightProgress,
                                                showFlightLanding = handoff != null,
                                                onFlightRowPositioned = onFlightRowPositioned,
                                                listShift = timelineFlightShift,
                                                networkId = state.buffer?.networkId,
                                                bufferId = state.buffer?.id,
                                                ebooksQuietFeed = eligibleEbooksRoomId != null,
                                                conversationName = conversationLabel,
                                                hideInlineAudioLinks = activeDickordChannel,
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
                                                selectionMessageId = activeSelectionId,
                                                onSelectionDismiss = { selectionTarget = null },
                                                onSelectionBounds = { selectionBounds = it },
                                                onReadMessage =
                                                    onReadAloudMessage.takeIf {
                                                        readAloudState.enabled && !readAloudState.previewing &&
                                                            readAloudState.roomId == buffer?.id
                                                    },
                                                onReply = timelineReply,
                                                swipeToReplyEnabled = swipeToReplyEnabled,
                                                onReact = onReact,
                                                onOpenReactions = onTimelineOpenReactions,
                                                onOpenMedia = onOpenMedia,
                                                onRetry = onRetry,
                                                canRetry = canRetryMessage,
                                                onDelete = onDelete,
                                                onAcceptInvite = onAcceptInvite,
                                                onDismissInvite = onDismissInvite,
                                                dccTransfer = dccTransfer,
                                                onAcceptDccTransfer = onAcceptDccTransferRequest,
                                                onRejectDccTransfer = onRejectDccTransfer,
                                                onRemoveDccTransfer = onRemoveDccTransfer,
                                                onSaveDccToDownloads = onSaveDccToDownloads,
                                                loadPreview = loadPreview,
                                                // A visible fallback entry can remain unresolved indefinitely.
                                                richContentReady = drawingAllowed && (initialPositionSettled || entryVeilLifted),
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
                                                voiceTranscripts = voiceState.transcripts,
                                                voiceTranscriptionEnabled = voiceState.transcriptionEnabled,
                                                voiceTranscriptionReady = voiceState.transcriptionReady,
                                                onVoiceTranscribe = onVoiceTranscribe,
                                                onVoiceTranscriptionCancel = onVoiceTranscriptionCancel,
                                                onOpenLink = onOpenLink,
                                                onOpenPalette = onOpenPalette,
                                                highlightMsgid = highlightMsgid,
                                                highlightEventId = highlightEventId,
                                                knownNicks = knownNicks,
                                                channelSenderPrefixes = channelSenderPrefixes.takeIf { state.buffer?.type == BufferType.CHANNEL }.orEmpty(),
                                                friends = friends,
                                                fools = fools,
                                                foolsMode = foolsMode,
                                                foolPreferences = foolPreferences,
                                                identityRules = identityRules,
                                                historyUiState = historyUiState,
                                                foolExpanded = foolExpanded,
                                                onToggleFool = onToggleFool,
                                                onSenderClick = { nick ->
                                                    if (nicknameTapAction == NicknameTapAction.MENTION) {
                                                        composerText = insertNickname(composerText, nick)
                                                        onDraftChanged(composerText.text)
                                                    } else {
                                                        onSenderClick(nick)
                                                    }
                                                },
                                                onSenderLongClick = onSenderClick,
                                            )
                                        }
                                        // Hidden rows cannot receive input; readiness removes this immediately.
                                        if (!timelineVisible) {
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
                                if (showEmptyTimeline) {
                                    io.github.trevarj.motd.ui.components.EmptyState(
                                        icon = Icons.Outlined.Forum,
                                        title = stringResource(R.string.chat_empty_title),
                                        message = stringResource(R.string.chat_empty_message),
                                        modifier = Modifier.padding(innerPadding),
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
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(innerPadding).padding(end = 16.dp, bottom = 16.dp),
                                )

                                if (compactHeader) {
                                    Column(
                                        modifier =
                                            Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(innerPadding)
                                                .padding(end = 16.dp, bottom = 16.dp + MotdSizes.floatingActionButton + 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Box {
                                            FloatingActionButton(
                                                onClick = { overflowOpen = true },
                                                modifier = Modifier.size(MotdSizes.floatingActionButton).testTag("chat_compact_actions"),
                                            ) {
                                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                                            }
                                            conversationActionsMenu()
                                        }
                                        if (showBack) {
                                            FloatingActionButton(
                                                onClick = onBack,
                                                modifier = Modifier.size(MotdSizes.floatingActionButton).testTag("chat_compact_back"),
                                            ) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.ArrowBack,
                                                    contentDescription = stringResource(R.string.chat_back),
                                                )
                                            }
                                        }
                                    }
                                }

                                val stagedVoicePlaybackId = voiceState.staged?.let { "voice:${it.file.toURI()}" }
                                TimelineTopOverlays(
                                    modifier = Modifier.padding(top = padding.calculateTopPadding()),
                                    audioPlayer = {
                                        ReadAloudPlayer(
                                            state = readAloudState,
                                            onPrevious = onReadAloudPrevious,
                                            onPauseResume = onReadAloudPauseResume,
                                            onSkip = onReadAloudSkip,
                                            onLatest = onReadAloudLatest,
                                            onStop = onReadAloudStop,
                                            onOptions = {
                                                onReadAloudOptions()
                                                readAloudOptionsOpen = true
                                            },
                                        )
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
                                    senderContext = { precedingHeightPx ->
                                        if (timelineVisible && !isServerBuffer && activeSelectionId == null && outgoingFlight == null) {
                                            val edgeSlackPx =
                                                with(LocalDensity.current) {
                                                    (8.dp + conversationSpacing.bubbleBurstGap).roundToPx()
                                                }
                                            TimelineSenderContext(
                                                listState = listState,
                                                metrics = rowContentMetrics,
                                                networkId = state.buffer?.networkId,
                                                readTopHostHeightPx = readTopHostHeightPx,
                                                readBottomHostHeightPx = readBottomHostHeightPx,
                                                precedingHeightPx = precedingHeightPx,
                                                edgeSlackPx = edgeSlackPx,
                                                userScrolling = userScrolling,
                                                visibleMessageAt = remember(items) { { index -> items.itemSnapshotList.getOrNull(index) } },
                                            )
                                        }
                                    },
                                )
                            }
                        }
                        // The ghost overlay draws ABOVE the composer layer: a sent bubble materializes over
                        // the input box -- the morph pins its real body to the still-warm field text --
                        // and then rides up into the timeline, instead of being born occluded behind the
                        // input bar. The clip and the coordinate origin sit on this stationary wrapper:
                        // clipping the ghost itself would clip nothing, since its layer translation carries
                        // its bounds along with its pixels. The spacing provider mirrors the timeline
                        // pane's, so the replica renders under exactly the tokens the rows use.
                        CompositionLocalProvider(
                            LocalSpacing provides conversationSpacing,
                            LocalCompactNickBrackets provides conversationLayout.effectiveCompactNickBrackets(globalCompactNickBrackets),
                            LocalMessageBackgroundOpacityPercent provides conversationLayout.effectiveMessageBackgroundOpacity(LocalMessageBackgroundOpacityPercent.current),
                            LocalMessageBackgroundBlur provides effectiveBlur,
                            LocalMessageWallpaperBackdrop provides wallpaperBackdrop,
                            LocalWallpaperNeedsReadableBacking provides wallpaperBacking,
                        ) {
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
                                val flightMessage =
                                    ghostFlight?.let { flight ->
                                        val landingId = if (flight.eventIds.isEmpty()) flightLandingId else null
                                        items.itemSnapshotList.items.firstOrNull { row ->
                                            if (landingId != null) {
                                                row.id == landingId
                                            } else {
                                                flight.matches(row)
                                            }
                                        }
                                    }
                                SendFlightOverlay(
                                    flight = ghostFlight,
                                    pending = flightMessage == null || flightMessage.pendingLabel != null,
                                    failed = flightMessage?.failed == true,
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

        if (eligibleEbooksRoomId != null && ebooksOffersOpen) {
            ModalBottomSheet(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
                onDismissRequest = { ebooksOffersOpen = false },
                modifier = Modifier.testTag("chat_ebooks_dcc_sheet"),
            ) {
                SheetSystemBars()
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    Text(
                        "DCC offers (${visibleEbooksOffers.size})",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                    Text(
                        "Incoming offers on this network; they are not matched to a search. Review the sender and endpoint before saving.",
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    if (visibleEbooksOffers.isEmpty()) {
                        Text(
                            "No offers yet. Send a search or request in #ebooks, then check here. Offers may arrive later.",
                            modifier = Modifier.padding(20.dp).testTag("chat_ebooks_dcc_empty"),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                            items(visibleEbooksOffers, key = { it.id }) { transfer ->
                                Text("From ${transfer.peerNick}", modifier = Modifier.padding(start = 28.dp, top = 12.dp))
                                ActiveDccTransferCard(
                                    transfer = transfer,
                                    onAccept = { id, filename, allowPrivate ->
                                        pendingDccAccept =
                                            PendingDccAccept(id, allowPrivate, eligibleEbooksRoomId, transfer.networkId)
                                        dccDestinationPicker.launch(filename)
                                    },
                                    onReceiveResultsPrivately = { offered, allowPrivate ->
                                        val canReceive =
                                            offered.state == DccTransferState.OFFERED ||
                                                offered.state == DccTransferState.PARTIAL ||
                                                offered.state == DccTransferState.FAILED
                                        if (eligibleEbooksRoomId == latestEligibleEbooksRoomId &&
                                            offered in latestVisibleEbooksOffers && canReceive &&
                                            offered.displayFilename.endsWith(".zip", ignoreCase = true)
                                        ) {
                                            onAcceptDccTransfer(offered.id, ebooksCache.uriFor(offered.id), allowPrivate)
                                        }
                                    },
                                    onReject = onRejectDccTransfer,
                                    onRemove = onRemoveDccTransfer,
                                    onSaveToDownloads = onSaveDccToDownloads,
                                    onViewResults = { completed ->
                                        val uri = completed.destinationUri
                                        if (completed in visibleEbooksOffers && completed.state == DccTransferState.COMPLETED &&
                                            uri != null && completed.displayFilename.endsWith(".zip", ignoreCase = true)
                                        ) {
                                            ebooksOffersOpen = false
                                            showEbooksResults(eligibleEbooksRoomId, uri.toUri())
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (eligibleEbooksRoomId != null && ebooksHelpOpen) {
            AlertDialog(
                onDismissRequest = { ebooksHelpOpen = false },
                modifier = Modifier.testTag("chat_ebooks_dialog"),
                title = { Text("Book search in #ebooks") },
                text = {
                    Text(
                        "An empty draft starts with @Search; add title/author and tap send yourself.\n" +
                            "@Search or @Searchook plus title/author is ordinary channel text.\n" +
                            "@TEXTBOOKS points to the channel's textbook instructions.\n" +
                            "@Oatmeal is the new-book route.\n" +
                            "@sbclient is mIRC help. DCC is required for files.\n\n" +
                            "Read the current topic by tapping #ebooks above.\n\n" +
                            "Tap DCC offers here to review incoming network offers (not linked to a search). " +
                            "Choose Receive results privately on a ZIP offer to use temporary app-private storage " +
                            "(16 MiB limit); private/local endpoints still require Allow once. " +
                            "Other Save actions pick a destination. The sender's conversation still has the same offer.\n\n" +
                            "After receiving a results ZIP, tap View results on its completed offer, or use Open results ZIP " +
                            "to choose one manually. Request sends this channel message immediately. " +
                            "Save the later book DCC from DCC offers here too.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { ebooksHelpOpen = false }, colors = readableTextButtonColors()) { Text("Close") }
                },
            )
        }

        val selectedEbooksResults = ebooksResults
        if (eligibleEbooksRoomId != null && selectedEbooksResults != null) {
            fun canRequest() =
                eligibleEbooksRoomId == state.buffer.id &&
                    composerDraft.hydrated && composerDraft.text == "@Search " && composerText.text == "@Search " &&
                    state.replyTo == null && state.buffer.joined == true && !state.parted &&
                    state.connState is IrcClientState.Ready
            AlertDialog(
                onDismissRequest = { ebooksResults = null },
                modifier = Modifier.testTag("chat_ebooks_results"),
                title = { Text("Book results") },
                text = {
                    Column {
                        Text("Incoming offers are in DCC offers here and in the sender's conversation; no offer is matched to this search. Request sends this channel message immediately.")
                        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                            items(selectedEbooksResults) { result ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(result.filename, style = MaterialTheme.typography.bodyMedium)
                                        Text(result.size, style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(
                                        colors = readableTextButtonColors(),
                                        onClick = {
                                            if (ebooksResults === selectedEbooksResults && canRequest()) {
                                                ebooksResults = null
                                                pendingEbooksRequestRevision = composerDraft.revision + 1
                                                onDraftChanged(result.request)
                                                captureFlightLaunch()
                                                onSubmit(result.request)
                                                // Only the ViewModel clears the durable draft after acceptance;
                                                // a rejected send republishes it to restore this optimistic clear.
                                                composerText = TextFieldValue("")
                                            }
                                        },
                                        enabled = canRequest(),
                                        modifier = Modifier.testTag("chat_ebooks_request"),
                                    ) { Text("Request") }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { ebooksResults = null }, colors = readableTextButtonColors()) { Text("Close") } },
            )
        }
        val selectedEbooksError = ebooksResultsError
        if (eligibleEbooksRoomId != null && selectedEbooksError != null) {
            AlertDialog(
                onDismissRequest = { ebooksResultsError = null },
                modifier = Modifier.testTag("chat_ebooks_results_error"),
                title = { Text("Cannot open results ZIP") },
                text = { Text(selectedEbooksError) },
                confirmButton = { TextButton(onClick = { ebooksResultsError = null }, colors = readableTextButtonColors()) { Text("Close") } },
            )
        }

        val schedule = scheduledDraft
        if (schedule != null && scheduledComposer != null) {
            ScheduleMessageSheet(
                target = schedule.room.ircTarget,
                initialText = schedule.text,
                onDismiss = { scheduledDraft = null },
                onOpenList = {
                    scheduledDraft = null
                    scheduledComposer.openList(schedule.room.id)
                },
                onSave = { _, timing ->
                    if (scheduleAttachmentPending) R.string.scheduled_attachment_unsupported else scheduledComposer.enqueue(schedule, timing)
                },
                onSaved = {
                    scheduledDraft = null
                    scope.launch {
                        val result =
                            snackbarHostState.showSnackbar(
                                ScheduledConfirmation(
                                    resources.getString(R.string.scheduled_queued_confirmation),
                                    resources.getString(R.string.scheduled_view),
                                ),
                            )
                        if (result == SnackbarResult.ActionPerformed) scheduledComposer.openList(schedule.room.id)
                    }
                },
            )
        }

        if (attachmentSheetOpen) {
            AttachmentSheets(
                open = true,
                currentDraft = composerText.text,
                networkId = state.buffer?.networkId,
                // Disclose the advertised destination; the uploader also validates credential authority.
                sojuFileHostUrl =
                    (state.connState as? IrcClientState.Ready)
                        ?.isupport
                        ?.let(::advertisedSojuFileHostUrl),
                preferSojuFileHost = activeDickordChannel,
                startWithCurrentDraft = uploadCurrentDraftDirectly,
                sharedFile = sharedFile,
                directFileTransferAvailable =
                    state.buffer?.type == BufferType.QUERY &&
                        state.connState is IrcClientState.Ready,
                onDismiss = {
                    if (traceBufferId != latestAttachmentRoom) return@AttachmentSheets
                    attachmentSheetOpen = false
                    uploadCurrentDraftDirectly = false
                    sharedFile = null
                },
                onOpenNetworkSettings = onOpenNetworkSettings,
                onInsertUrl = {
                    if (traceBufferId != latestAttachmentRoom) return@AttachmentSheets
                    composerText =
                        io.github.trevarj.motd.ui.components
                            .insertAtCursor(composerText, it)
                    onDraftChanged(composerText.text)
                },
                onReplaceDraft = {
                    if (traceBufferId != latestAttachmentRoom) return@AttachmentSheets
                    composerText =
                        TextFieldValue(
                            it,
                            androidx.compose.ui.text
                                .TextRange(it.length),
                        )
                    onDraftChanged(composerText.text)
                },
                onDirectFile = onSendDccFile,
                viewModel = attachmentViewModel ?: hiltViewModel(),
                prepareUploadCompletion =
                    if (traceBufferId != null && onBeginComposerUpload != null) {
                        { replacedDraft ->
                            val attempt = onBeginComposerUpload(traceBufferId, replacedDraft)
                            pendingComposerUploadId = attempt.id
                            val complete: (UploadRecord) -> Unit = { record ->
                                val editor = if (scope.isActive && traceBufferId == latestAttachmentRoom) composerText else null
                                val updated = attempt.complete(record, editor)
                                if (editor != null && pendingComposerUploadId == attempt.id) {
                                    if (updated != null) composerText = updated
                                    pendingComposerUploadId = null
                                }
                            }
                            complete
                        }
                    } else {
                        null
                    },
            )
        }
        if (longDraftPrompt) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    AutoFollowTrace.record("long_draft_dismiss", traceBufferId, traceSessionId)
                    longDraftPrompt = false
                },
                title = { Text("Long draft") },
                text = { Text("Upload the draft as a paste, or send it as ordinary IRC messages?") },
                confirmButton = {
                    androidx.compose.material3.TextButton(colors = readableTextButtonColors(), onClick = {
                        AutoFollowTrace.record("long_draft_upload", traceBufferId, traceSessionId)
                        longDraftPrompt = false
                        uploadCurrentDraftDirectly = true
                        attachmentSheetOpen = true
                    }) { Text("Upload as paste") }
                },
                dismissButton = {
                    Row {
                        androidx.compose.material3.TextButton(colors = readableTextButtonColors(), onClick = {
                            AutoFollowTrace.record("long_draft_send_messages", traceBufferId, traceSessionId)
                            if (!isEbooksSearchOnly(composerText.text)) {
                                captureFlightLaunch()
                                onSubmit(composerText.text)
                                composerText = TextFieldValue("")
                                scope.launch { scrollToNewest(animate = true, reason = "long_draft_send") }
                            }
                        }) { Text("Send as messages") }
                        androidx.compose.material3.TextButton(colors = readableTextButtonColors(), onClick = {
                            AutoFollowTrace.record("long_draft_cancel", traceBufferId, traceSessionId)
                            longDraftPrompt = false
                        }) { Text("Cancel") }
                    }
                },
            )
        }

        sheetRequest?.let { (target, initialReactionsTab) ->
            val selectionBufferId = state.buffer?.id
            val eventInfo =
                target.takeIf {
                    when (it.kind) {
                        MessageKind.INVITE, MessageKind.NETSPLIT, MessageKind.NETJOIN, MessageKind.DCC_TRANSFER -> true
                        else -> !isServerBuffer && isSystemKind(it.kind)
                    }
                }
            // Dismiss with the M3 hide animation, clearing the target only once it settles.
            val hideThen: (() -> Unit) -> Unit = { after ->
                scope.launch { sheetState.hide() }.invokeOnCompletion {
                    sheetRequest = null
                    after()
                }
            }
            MessageActionSheet(
                sheetState = sheetState,
                initialReactionsTab = initialReactionsTab,
                isServerBuffer = isServerBuffer,
                eventInfo = eventInfo,
                canSelectText = eventInfo == null && canSelectMessage(target),
                onSelectText = {
                    scope.launch {
                        sheetState.hide()
                        sheetRequest = null
                        if (selectionBufferId == latestSelectionBufferId && latestCanSelectMessage(target)) selectionTarget = target
                    }
                },
                onDismiss = { sheetRequest = null },
                onTranslate =
                    if (externalTranslationText(target) != null) {
                        { hideThen { openTranslationPicker(target) } }
                    } else {
                        null
                    },
                onForward =
                    if (onOpenForward != null && canForwardMessage(target, state.buffer?.type)) {
                        { hideThen { onOpenForward(target) } }
                    } else {
                        null
                    },
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
                onMoreReactions = { hideThen { reactionPickerTarget = target } },
                reactions = target.msgid?.let(reactionChips).orEmpty(),
                reactionEnabled = { emoji -> reactionEnabled(target, emoji) },
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

        if (sheetRequest == null) {
            forwardState?.let { request ->
                MessageForwardSheet(
                    state = request,
                    targets = forwardTargets,
                    onSelectDestination = onSelectForwardDestination,
                    onChangeDestination = onChangeForwardDestination,
                    onIncludeSenderChanged = onForwardIncludeSenderChanged,
                    onConfirm = onConfirmForward,
                    onDismiss = onDismissForward,
                )
            }
        }

        reactionPickerTarget?.let { target ->
            MessageReactionPicker(
                sheetState = reactionPickerState,
                onDismiss = { reactionPickerTarget = null },
                onReact = { emoji -> onReact(target, emoji) },
                reactionEnabled = { emoji -> reactionEnabled(target, emoji) },
            )
        }

        translationTarget?.let { target ->
            MessageTranslationPicker(
                targets = translationTargets,
                localAvailable = onTranslateMessage != null,
                otherAvailable = otherTranslationAvailable,
                onLocal = { onTranslateMessage?.invoke(target) },
                onTarget = { launchTranslation(target, it) },
                onOther = { launchTranslation(target, null) },
                onDismiss = { translationTarget = null },
            )
        }

        redactionTarget?.let { target ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { redactionTarget = null },
                title = { Text(stringResource(R.string.chat_redaction_confirm_title)) },
                text = { Text(stringResource(R.string.chat_redaction_confirm_body)) },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        colors = readableTextButtonColors(),
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
                    androidx.compose.material3.TextButton(onClick = { redactionTarget = null }, colors = readableTextButtonColors()) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
                modifier = Modifier.testTag("message_redact_dialog"),
            )
        }

        if (historySyncModeSheetOpen) {
            HistorySyncModeSheet(
                selected = state.conversationHistorySync.override,
                global = state.conversationHistorySync.global,
                includeInherit = true,
                unsupported = historyAvailability == HistoryAvailability.Unsupported,
                onSelect = onHistorySyncModeSelected,
                onDismiss = { historySyncModeSheetOpen = false },
                tag = "chat_history_sync_sheet",
            )
        }

        if (presenceModeSheetOpen) {
            PresenceModeSheet(
                state = state.conversationPresence,
                onSelect = onPresenceModeSelected,
                onCustomKindSelected = onPresenceCustomKindSelected,
                onDismiss = { presenceModeSheetOpen = false },
            )
        }
        if (conversationLayoutSheetOpen) {
            ConversationLayoutSheet(
                state = conversationLayout,
                onSelect = onConversationLayoutSelected,
                onOpacity = onConversationMessageBackgroundOpacitySelected,
                globalOpacity = LocalMessageBackgroundOpacityPercent.current,
                onBlur = onConversationMessageBackgroundBlurSelected,
                globalBlur = LocalMessageBackgroundBlur.current,
                globalCompactNickBrackets = globalCompactNickBrackets,
                onCompactNickBracketsOverride = onCompactNickBracketsOverride,
                onDismiss = { conversationLayoutSheetOpen = false },
                wallpaper = chatWallpaper,
                conversationFontScalePercent = conversationFontScalePercent,
                messageSpacing = messageSpacing,
                bubbleCorners = bubbleCornerStyle,
            )
        }
        if (notificationSheetOpen && buffer?.type == BufferType.CHANNEL) {
            ChannelNotificationSheet(
                presentation = channelNotifications,
                onMode = onChannelNotificationMode,
                onStart = onStartWatch,
                onStop = onStopWatch,
                onDismiss = { notificationSheetOpen = false },
                tagPrefix = "chat",
            )
        }
    }
}

/** Only a measured, loaded, non-suppressed row can park a refresh in history. */
@Composable
internal fun ReportMessageViewport(
    items: LazyPagingItems<MessageEntity>,
    listState: LazyListState,
    policy: MessageVisibilityPolicy,
    enabled: Boolean,
    scopeId: Long?,
    followingAtBottom: Boolean,
    onAnchor: (ViewportRefreshAnchor?) -> Unit,
    bottomContentPadding: PaddingValues? = null,
    topContentPadding: PaddingValues? = null,
    rowContentMetrics: Map<Long, TimelineRowContentMetrics> = emptyMap(),
) {
    val latestOnAnchor by rememberUpdatedState(onAnchor)
    var lastVisibleIndex by remember(items, listState, scopeId) { mutableStateOf<Int?>(null) }
    var lastItemCount by remember(items, listState, scopeId) { mutableIntStateOf(-1) }
    val latestBottomPadding by rememberUpdatedState(bottomContentPadding)
    val latestTopPadding by rememberUpdatedState(topContentPadding)
    val latestDensity by rememberUpdatedState(LocalDensity.current)
    LaunchedEffect(items, listState, policy, followingAtBottom, enabled, scopeId, rowContentMetrics) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val bottomOcclusionPx = latestBottomPadding?.let { with(latestDensity) { it.calculateBottomPadding().roundToPx() } } ?: 0
            val topOcclusionPx = latestTopPadding?.let { with(latestDensity) { it.calculateTopPadding().roundToPx() } } ?: 0
            val snapshot = items.itemSnapshotList
            var newestVisible = false
            var row: MessageEntity? = null
            for (visible in layout.visibleItemsInfo) {
                if (!timelineItemIsVisible(visible, layout, bottomOcclusionPx, rowContentMetrics, topOcclusionPx)) {
                    continue
                }
                if (visible.index == 0) newestVisible = true
                val message = snapshot.getOrNull(visible.index)
                if (message != null && visible.key == message.id && policy.anchor(message)) {
                    row = message
                    break
                }
            }
            // A replacement can briefly have no loaded visible row. Keep its parked identity
            // while the viewport stays put; a jump to another placeholder must retire it.
            // An arriving row can shift the index without the reader scrolling.
            val anchor =
                when {
                    followingAtBottom && (newestVisible || row != null) -> ViewportRefreshAnchor.Newest
                    row != null -> ViewportRefreshAnchor.Parked(row.id)
                    else -> null
                }
            val firstVisible =
                layout.visibleItemsInfo.firstOrNull {
                    timelineItemIsVisible(it, layout, bottomOcclusionPx, rowContentMetrics, topOcclusionPx)
                }
            Triple(firstVisible?.index, anchor, items.itemCount)
        }.distinctUntilChanged().collect { (index, anchor, count) ->
            if (anchor != null || (index != null && lastVisibleIndex != null && index != lastVisibleIndex && count == lastItemCount)) {
                latestOnAnchor(anchor)
            }
            if (index != null) lastVisibleIndex = index
            lastItemCount = count
        }
    }
}

private fun timelineItemIsVisible(
    item: LazyListItemInfo,
    layout: LazyListLayoutInfo,
    bottomOcclusionPx: Int,
    rowContentMetrics: Map<Long, TimelineRowContentMetrics>,
    topOcclusionPx: Int = 0,
): Boolean {
    val metrics = (item.key as? Long)?.let { rowContentMetrics[it] }
    return timelineItemIsVisible(
        item.offset,
        item.size,
        layout.viewportStartOffset,
        layout.viewportEndOffset - topOcclusionPx,
        bottomOcclusionPx,
        leadingGapPx = metrics?.leadingGapPx ?: 0,
        fullItemSize = metrics?.fullHeightPx ?: item.size,
    )
}

// Reverse-layout offsets start at the padded bottom. Rows may draw under the host, not count as read there.
internal fun timelineItemIsVisible(
    itemOffset: Int,
    itemSize: Int,
    viewportStartOffset: Int,
    viewportEndOffset: Int,
    bottomOcclusionPx: Int = 0,
    leadingGapPx: Int = 0,
    fullItemSize: Int = itemSize,
): Boolean {
    // Native entrances reveal from the bottom: their clipped prefix no longer contains the gap.
    val visibleLeadingGap = (leadingGapPx - (fullItemSize - itemSize).coerceAtLeast(0)).coerceAtLeast(0)
    return itemSize > visibleLeadingGap &&
        itemOffset < viewportEndOffset &&
        itemOffset + itemSize - visibleLeadingGap > viewportStartOffset + bottomOcclusionPx
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

/** Generic shares, snippets and quotes append; nickname requests use [insertNickname]. */
fun appendPrefill(
    value: TextFieldValue,
    prefill: String,
): TextFieldValue {
    val current = value.text
    val separator = if (current.isNotEmpty() && !current.last().isWhitespace()) " " else ""
    val text = current + separator + prefill
    return TextFieldValue(text, TextRange(text.length))
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

/** Stacks transient chrome; callers inset it below the title bar when the timeline draws behind it. */
@Composable
internal fun BoxScope.TimelineTopOverlays(
    audioPlayer: @Composable () -> Unit,
    historyFailure: @Composable () -> Unit,
    staleChip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    senderContext: (@Composable (Int) -> Unit)? = null,
) {
    Column(
        modifier =
            modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        var precedingHeightPx by remember { mutableStateOf<Int?>(null) }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = if (senderContext == null) Modifier else Modifier.onSizeChanged { precedingHeightPx = it.height },
        ) {
            audioPlayer()
            historyFailure()
            staleChip()
        }
        precedingHeightPx?.let { senderContext?.invoke(it) }
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
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
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
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Icon(
                    imageVector = Icons.Default.Close,
                    // The clickable's label already announces the action; the icon is decorative.
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
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
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
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
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
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
    val value: String

    data class Text(
        override val value: String,
        val isMemberCount: Boolean = false,
    ) : ChatSubtitleModel

    data class Typing(
        override val value: String,
    ) : ChatSubtitleModel
}

internal fun chatSubtitle(
    state: ChatState,
    context: android.content.Context,
): String? = chatSubtitleModel(state, context)?.value

internal fun chatSubtitleModel(
    state: ChatState,
    context: android.content.Context,
    dickordEnabled: Boolean = false,
    includeTyping: Boolean = true,
): ChatSubtitleModel? {
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
    val buffer = state.buffer
    val activeDickordChannel =
        dickordEnabled &&
            buffer != null &&
            isDickordPortalConversation(buffer.type, buffer.displayName)
    if (includeTyping) {
        chatTypingSummary(state, context, dickordEnabled)?.let { return ChatSubtitleModel.Typing(it) }
    }
    if (activeDickordChannel) {
        return ChatSubtitleModel.Text(context.getString(R.string.dickord_channel_subtitle))
    }
    if (buffer == null) return null
    return if (buffer.type == BufferType.CHANNEL && state.memberCount != null) {
        val n = state.memberCount
        ChatSubtitleModel.Text(context.resources.getQuantityString(R.plurals.chat_member_count, n, n), isMemberCount = true)
    } else {
        null
    }
}

internal fun chatTypingSummary(
    state: ChatState,
    context: android.content.Context,
    dickordEnabled: Boolean = false,
): String? {
    if (state.connState !is IrcClientState.Ready || state.typingNicks.isEmpty()) return null
    val buffer = state.buffer
    val portal = dickordEnabled && buffer != null && isDickordPortalConversation(buffer.type, buffer.displayName)
    val nicks = if (portal) state.typingNicks.map { dickordNickLabel(it, true) } else state.typingNicks
    return typingText(context, nicks)
}

@Composable
internal fun AboveComposerTyping(summary: String?) {
    AnimatedContent(
        targetState = summary,
        transitionSpec = {
            (fadeIn(MotdMotion.microFadeIn) togetherWith fadeOut(MotdMotion.microFadeOut))
                .using(SizeTransform(clip = false))
        },
        label = "above_composer_typing",
    ) { text ->
        if (text != null) {
            TypingIndicatorRow(
                summary = text,
                modifier = Modifier.fillMaxWidth().testTag("chat_typing_above_composer").padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
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
    val newestLabel = stringResource(R.string.chat_jump_to_newest)
    val jumpLabel = if (mentionPending) stringResource(R.string.chat_next_unread_mention) else newestLabel
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
                        .semantics {
                            contentDescription = jumpLabel
                            if (mentionPending) {
                                customActions =
                                    listOf(
                                        CustomAccessibilityAction(newestLabel) {
                                            latestOnLongClick()
                                            true
                                        },
                                    )
                            }
                        }.pointerInput(Unit) {
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
                    contentDescription = null,
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
 * to the nearest such mention, walking toward newer messages on successive taps. Otherwise the
 * badge shows the unread count and a tap scrolls to the newest row.
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
    val panelColor = MaterialTheme.colorScheme.secondaryContainer
    val adjustPaletteReadability = LocalAdjustPaletteReadability.current
    val recordingInkSeed = MaterialTheme.colorScheme.error
    val recordingInk =
        if (adjustPaletteReadability) {
            remember(recordingInkSeed, panelColor) { ensureContrast(recordingInkSeed, listOf(panelColor)) }
        } else {
            MaterialTheme.colorScheme.readableForeground(recordingInkSeed, background = panelColor)
        }
    val previewInkSeed = MaterialTheme.colorScheme.primary
    val previewInk =
        if (adjustPaletteReadability) {
            remember(previewInkSeed, panelColor) { ensureContrast(previewInkSeed, listOf(panelColor)) }
        } else {
            MaterialTheme.colorScheme.readableForeground(previewInkSeed, background = panelColor)
        }
    // Hold the last non-null panel models so the exit animation still has content to draw after
    // the state field clears; live fields (state.sending, state.progress, playbackState) keep reading current
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
            color = panelColor,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            tonalElevation = 3.dp,
        ) {
            Column {
                HorizontalDivider(thickness = Dp.Hairline, color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Mic, null, tint = recordingInk)
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
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
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
            color = panelColor,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            tonalElevation = 3.dp,
        ) {
            Column {
                HorizontalDivider(thickness = Dp.Hairline, color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Mic, null, tint = previewInk)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Voice message", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${formatAudioDuration(staged.durationMs)} · ${staged.mimeType} · ${formatBytes(staged.sizeBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        IconButton(onClick = { onPreview(preview) }, enabled = !state.sending, modifier = Modifier.testTag("voice_preview_play")) {
                            Icon(if (previewPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (previewPlaying) "Pause" else "Play")
                        }
                        IconButton(onClick = onDelete, enabled = !state.sending, modifier = Modifier.testTag("voice_delete")) {
                            Icon(Icons.Filled.Delete, "Delete")
                        }
                    }
                    WaveformScrubber(
                        value = (previewPositionMs.toFloat() / previewDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f),
                        onValueChange = { fraction ->
                            onPreviewSeek(preview, (fraction * previewDurationMs).toLong())
                        },
                        onValueChangeFinished = {},
                        enabled = !state.sending && previewActive && !playbackState.loading,
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
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Switch(
                            checked = staged.encrypted,
                            onCheckedChange = { onToggleEncryption() },
                            enabled = !state.sending,
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
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        TextButton(
                            onClick = { destinationSheet = true },
                            enabled = !state.sending,
                            modifier = Modifier.testTag("voice_destination"),
                            colors = readableTextButtonColors(background = panelColor),
                        ) {
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

                        null -> {
                            if (state.sending) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        is VoiceSendProgress.Complete -> {}
                    }
                    Button(onClick = onSend, enabled = !state.sending, modifier = Modifier.fillMaxWidth().testTag("voice_send")) {
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
        if (destinationSheet && !state.sending) {
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
            confirmButton = { TextButton(onClick = onErrorDismissed, colors = readableTextButtonColors()) { Text("OK") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceDestinationSheet(
    staged: StagedVoiceMessage,
    config: io.github.trevarj.motd.attachment.PasteBackendConfig,
    onSelect: (io.github.trevarj.motd.attachment.PasteBackendConfig?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetSystemBars()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("voice_destination_sheet"),
        ) {
            Text("Voice destination", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            uploadDestinations(staged.source, config, sojuFileHostAvailable = true).forEach { destination ->
                val soju = destination.config.backend == AttachmentBackend.SOJU_FILEHOST
                androidx.compose.material3.ListItem(
                    headlineContent = { Text(destination.label, fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        Text(if (soju) "Use the file host advertised by this IRC network" else backendRetention(destination.config))
                    },
                    modifier = Modifier.clickable { onSelect(destination.config.takeUnless { soju }) },
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
            TextButton(
                onClick = onRejoin,
                modifier = Modifier.testTag("chat_parted_rejoin"),
                colors = readableTextButtonColors(background = MaterialTheme.colorScheme.errorContainer),
            ) {
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
