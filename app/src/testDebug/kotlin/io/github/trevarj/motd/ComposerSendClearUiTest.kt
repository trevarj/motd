package io.github.trevarj.motd

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.text.SpannableString
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.room.Room
import coil.Coil
import coil.ImageLoader
import io.github.trevarj.motd.ai.AiCustomStyle
import io.github.trevarj.motd.ai.AiRuntimeFailure
import io.github.trevarj.motd.ai.AiTranslationTarget
import io.github.trevarj.motd.ai.text.TextOperation
import io.github.trevarj.motd.ai.text.TextTermination
import io.github.trevarj.motd.ai.text.TextTransformResult
import io.github.trevarj.motd.audio.AudioPlaybackRequest
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.DccAddressKind
import io.github.trevarj.motd.data.db.DccDirection
import io.github.trevarj.motd.data.db.DccTransferEntity
import io.github.trevarj.motd.data.db.DccTransferProtocol
import io.github.trevarj.motd.data.db.DccTransferState
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.ComposerStyle
import io.github.trevarj.motd.data.prefs.CustomWallpaperStore
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.data.repo.ViewportRefreshAnchor
import io.github.trevarj.motd.dcc.EbooksResultCache
import io.github.trevarj.motd.dickord.LocalDickordLabsEnabled
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.service.ImmediateWireAcceptance
import io.github.trevarj.motd.service.SendRejectionReason
import io.github.trevarj.motd.ui.ai.AiComposerDraftSnapshot
import io.github.trevarj.motd.ui.ai.AiTextAction
import io.github.trevarj.motd.ui.ai.AiTextSheet
import io.github.trevarj.motd.ui.ai.AiTextSource
import io.github.trevarj.motd.ui.ai.AiTextUiState
import io.github.trevarj.motd.ui.ai.source
import io.github.trevarj.motd.ui.chat.CHAT_ENTRY_VEIL_TAG
import io.github.trevarj.motd.ui.chat.CHAT_TITLE_SYNC_SPINNER_TAG
import io.github.trevarj.motd.ui.chat.ChatContent
import io.github.trevarj.motd.ui.chat.ChatPositionTarget
import io.github.trevarj.motd.ui.chat.ChatState
import io.github.trevarj.motd.ui.chat.ChatViewModel
import io.github.trevarj.motd.ui.chat.ChatViewModelTest
import io.github.trevarj.motd.ui.chat.ComposerDraftState
import io.github.trevarj.motd.ui.chat.ComposerDraftStore
import io.github.trevarj.motd.ui.chat.EntryPositionState
import io.github.trevarj.motd.ui.chat.ExternalTranslationRequest
import io.github.trevarj.motd.ui.chat.MessageForwardError
import io.github.trevarj.motd.ui.chat.MessageList
import io.github.trevarj.motd.ui.chat.MessageUrlCache
import io.github.trevarj.motd.ui.chat.OutgoingFlight
import io.github.trevarj.motd.ui.chat.SendFlightAnchors
import io.github.trevarj.motd.ui.chat.SendFlightMotion
import io.github.trevarj.motd.ui.chat.SendFlightOverlay
import io.github.trevarj.motd.ui.chat.UnreadEntrySnapshot
import io.github.trevarj.motd.ui.chat.VoiceMessageUiState
import io.github.trevarj.motd.ui.chat.VoiceRecordingUi
import io.github.trevarj.motd.ui.chat.clickNativeEmoji
import io.github.trevarj.motd.ui.chat.externalTranslationText
import io.github.trevarj.motd.ui.chat.findNativeEmoji
import io.github.trevarj.motd.ui.chat.pickNativeEmojiVariant
import io.github.trevarj.motd.ui.chat.swipeNativeEmojiBody
import io.github.trevarj.motd.ui.chat.tapNativeEmojiCategory
import io.github.trevarj.motd.ui.chat.timelineMessageTag
import io.github.trevarj.motd.ui.chat.touchNativeEmojiView
import io.github.trevarj.motd.ui.components.MessageBubble
import io.github.trevarj.motd.ui.components.ReactionChip
import io.github.trevarj.motd.ui.components.rememberMessageTimeFormatter
import io.github.trevarj.motd.ui.share.PendingShare
import io.github.trevarj.motd.ui.share.PendingShareStore
import io.github.trevarj.motd.ui.theme.LocalLottieMotionEnabled
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The composer must empty on the frame the send is tapped, and must get its text back when the
 * ViewModel republishes a draft the send never consumed.
 *
 * Both halves were unasserted while the field's only path to empty was an accepted send winning a
 * Room write and a wire round-trip, which is how a send that silently failed could leave the text
 * sitting in the box with nothing reported.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComposerSendClearUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    // v2 queues composition on the UI thread; the legacy unconfined rule can resume on an IO worker.
    @get:Rule
    val compose = createComposeRule()

    private var backDispatcher: OnBackPressedDispatcher? = null

    private val buffer =
        BufferEntity(
            id = 1,
            networkId = 1,
            name = "#kotlin",
            displayName = "#kotlin",
            type = BufferType.CHANNEL,
            joined = true,
        )
    private val ebooksBuffer = buffer.copy(name = "#ebooks", displayName = "#ebooks")

    private var pickerRequestCode = -1
    private val pickerLaunchedIntents = mutableListOf<Intent>()
    private var pickerLaunchFailure: RuntimeException? = null
    private val pickerRegistry =
        object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                pickerRequestCode = requestCode
                if (input is Intent) pickerLaunchedIntents += input
                pickerLaunchFailure?.let { throw it }
            }
        }
    private val pickerOwner =
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry = pickerRegistry
        }

    private fun registerTranslationHandler(
        packageName: String = "com.deepl.mobiletranslator",
        action: String = Intent.ACTION_PROCESS_TEXT,
        label: String = "Installed translator",
        activityName: String = "TranslationActivity",
    ): ComponentName {
        val shadow = shadowOf(RuntimeEnvironment.getApplication().packageManager)
        val component = ComponentName(packageName, "$packageName.$activityName")
        shadow.addActivityIfNotPresent(component)
        val appInfo =
            shadow.getInternalMutablePackageInfo(packageName).applicationInfo!!.apply {
                nonLocalizedLabel = label
                enabled = true
            }
        shadow.addOrUpdateActivity(
            ActivityInfo().apply {
                this.packageName = packageName
                name = component.className
                applicationInfo = appInfo
                enabled = true
                exported = true
            },
        )
        shadow.addIntentFilterForActivity(
            component,
            IntentFilter(action).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataType("text/plain")
            },
        )
        return component
    }

    private fun translationMessage() =
        MessageEntity(
            id = 88,
            bufferId = buffer.id,
            serverTime = 1,
            sender = "alice",
            kind = MessageKind.PRIVMSG,
            text = "\u0002Bonjour\u0002 👋\nSalut",
            ircFormattedText = "Protocol-only text is not exported",
            dedupKey = "translation-parent",
        )

    /** Native dispatch fixture only; it neither echoes output nor simulates result acceptance. */
    private fun translationRequest(source: MessageEntity) = ExternalTranslationRequest(1, 0, source.bufferId, source.id, source.text, requireNotNull(externalTranslationText(source)))

    private fun holdMessageBubble(message: MessageEntity) {
        val tag = timelineMessageTag(message.msgid, message.id)
        compose.waitForIdle()
        compose.onNodeWithTag("chat_timeline", useUnmergedTree = true).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
        val row = compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
        val longPressSurface = SemanticsMatcher("message long-press surface") { it.config.contains(SemanticsActions.OnLongClick) }
        val bubble = compose.onNode(hasAnyAncestor(hasTestTag(tag)) and longPressSurface, useUnmergedTree = true).assertIsDisplayed()
        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        val bubbleBounds = bubble.fetchSemanticsNode().boundsInRoot
        // Hold the source surface's bottom padding, not its independently interactive text child.
        val pressPosition = Offset(bubbleBounds.center.x, bubbleBounds.bottom - 1f) - rowBounds.topLeft
        row.performTouchInput { longClick(pressPosition) }
    }

    private fun openTranslationPicker() {
        holdMessageBubble(translationMessage())
        compose.onNodeWithTag("message_action_translate").performClick()
        compose.onNodeWithTag("message_translation_picker").assertIsDisplayed()
    }

    private fun withTranslationOwner(
        replyToParent: Boolean = true,
        sourceText: String = "Bonjour",
        prepare: suspend (MotdDatabase, MessageEntity) -> Unit = { _, _ -> },
        createOwner: (MotdDatabase, CoroutineScope) -> ChatViewModel = { db, scope ->
            ChatViewModelTest.externalTranslationFixture(db, buffer, scope)
        },
        block: (MotdDatabase, ChatViewModel, MessageEntity) -> Unit,
    ) {
        val db =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MotdDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        val parent = translationMessage().copy(text = sourceText, ircFormattedText = null)
        runBlocking {
            db.networkDao().insert(NetworkEntity(id = buffer.networkId, name = "test", role = NetworkRole.DIRECT, host = "irc.example", port = 6697, nick = "me", username = "me", realname = "Me"))
            db.bufferDao().insert(buffer)
            db.messageDao().insertAll(listOf(parent))
            ComposerDraftStore(db).saveDraft(buffer.id, "Unsent draft", parent.id.takeIf { replyToParent })
            prepare(db, parent)
        }
        val store = ViewModelStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var owner: ChatViewModel
        compose.runOnIdle {
            owner = createOwner(db, scope)
            store.put("chat", owner)
        }
        val ownerJob = owner.viewModelScope.coroutineContext[Job]
        try {
            block(db, owner, parent)
        } finally {
            compose.runOnIdle {
                store.clear()
                scope.cancel()
            }
            compose.waitForIdle()
            runBlocking {
                ownerJob?.join()
                scope.coroutineContext[Job]?.join()
            }
            db.close()
        }
    }

    private fun waitForForward(
        owner: ChatViewModel,
        condition: () -> Boolean,
    ) {
        try {
            compose.waitUntil(5_000) {
                // Fresh Room/settings collectors in confirmForward need the paused main looper.
                compose.waitForIdle()
                condition()
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Forward state=${owner.forwardState.value}; targets=${owner.forwardTargets.value}", timeout)
        }
    }

    private fun selectedZip(vararg lines: String): File =
        File.createTempFile("ebook-results-", ".zip", RuntimeEnvironment.getApplication().cacheDir).also { file ->
            ZipOutputStream(file.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("results.txt"))
                output.write(lines.joinToString("\n").toByteArray())
                output.closeEntry()
            }
        }

    private fun incomingOffer(
        id: Long = 7,
        networkId: Long = ebooksBuffer.networkId,
        filename: String = "results.zip",
        state: DccTransferState = DccTransferState.OFFERED,
        destinationUri: String? = null,
        address: String = "8.8.8.8",
    ) = DccTransferEntity(
        id = id,
        networkId = networkId,
        timelineEventId = null,
        offerKey = "offer-$id",
        direction = DccDirection.INCOMING,
        protocol = DccTransferProtocol.SEND,
        peerNick = "books_bot",
        normalizedPeer = "books_bot",
        filename = filename,
        displayFilename = filename,
        address = address,
        addressKind = DccAddressKind.IPV4_DOTTED,
        port = 9000,
        sizeBytes = 100,
        token = null,
        state = state,
        bytesTransferred = if (state == DccTransferState.COMPLETED) 100 else 0,
        destinationUri = destinationUri,
        createdAt = 1,
        expiresAt = null,
        updatedAt = 1,
    )

    private fun chooseResults(file: File) {
        compose.onNodeWithTag("chat_ebooks_open_results").performClick()
        compose.runOnIdle {
            pickerRegistry.dispatchResult(pickerRequestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("chat_ebooks_results").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("chat_ebooks_results_error").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Renders the real chat surface with the draft and optional Paging timeline under test control. */
    private fun setContent(
        liveConnection: (() -> IrcClientState?)? = null,
        draft: () -> ComposerDraftState,
        pages: Flow<PagingData<MessageEntity>> = flowOf(PagingData.from(emptyList())),
        outgoingFlight: () -> OutgoingFlight? = { null },
        onFlightSettled: (Long) -> Unit = {},
        chatBuffer: () -> BufferEntity = { buffer },
        connectionState: IrcClientState? = IrcClientState.Ready("me", emptySet(), emptyMap()),
        memberCount: Int? = null,
        fontScale: Float? = null,
        onBack: () -> Unit = {},
        showBack: Boolean = true,
        onOpenConversationList: (() -> Unit)? = null,
        historySyncStatus: HistorySyncStatus = HistorySyncStatus.Idle,
        onOpenChannelInfo: (Long) -> Unit = {},
        onOpenSearch: (Long) -> Unit = {},
        parted: Boolean = false,
        onInviteUser: () -> Unit = {},
        replyTo: () -> MessageEntity? = { null },
        onSetReply: (MessageEntity?) -> Unit = {},
        onReact: (MessageEntity, String) -> Unit = { _, _ -> },
        reactionChips: (String) -> List<ReactionChip> = { emptyList() },
        ebooksHelperRoomId: () -> Long? = { null },
        ebooksDccOffers: () -> List<DccTransferEntity> = { emptyList() },
        onAcceptDccTransfer: (Long, Uri, Boolean) -> Unit = { _, _, _ -> },
        onRejectDccTransfer: (Long) -> Unit = {},
        onRemoveDccTransfer: (Long) -> Unit = {},
        onSaveDccToDownloads: (suspend (Long) -> Unit)? = null,
        activityResults: ActivityResultRegistryOwner? = null,
        onDraftChanged: (String) -> Unit = {},
        aiEnabled: () -> Boolean = { false },
        aiState: () -> AiTextUiState = { AiTextUiState.Closed },
        onAi: () -> Unit = {},
        aiSheet: @Composable () -> Unit = {},
        dickordEnabled: () -> Boolean = { false },
        layoutDensity: () -> LayoutDensity = { LayoutDensity.COMFORTABLE },
        chatWallpaper: WallpaperSelection = WallpaperSelection(),
        onAudioToggle: (AudioPlaybackRequest) -> Unit = {},
        initialTarget: () -> ChatPositionTarget? = { null },
        entryState: () -> EntryPositionState = { EntryPositionState.Settled },
        unreadEntrySnapshot: UnreadEntrySnapshot? = null,
        onInitialPositionHandled: () -> Unit = {},
        rawNewestAnchor: TimelineAnchor? = null,
        onMarkRead: (TimelineAnchor) -> Unit = {},
        showImages: Boolean = true,
        showLinkPreviews: Boolean = true,
        onTranslateMessage: ((MessageEntity) -> Unit)? = null,
        onBeginExternalTranslation: (suspend (MessageEntity) -> ExternalTranslationRequest?)? = null,
        onCompleteExternalTranslation: ((String?) -> Unit)? = null,
        onAbortExternalTranslationLaunch: (() -> Unit)? = null,
        externalTranslationInFlight: () -> Boolean = { false },
        composerStyle: () -> ComposerStyle = { ComposerStyle.COMFORTABLE },
        memberNicks: List<String> = emptyList(),
        contentHeight: () -> Dp? = { null },
        voiceState: () -> VoiceMessageUiState = { VoiceMessageUiState() },
        onViewportRefreshAnchor: (ViewportRefreshAnchor?) -> Unit = {},
        chatViewModel: ChatViewModel? = null,
        forwardEnabled: Boolean = false,
        onSubmit: (String) -> Unit,
    ) {
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            val items = pages.collectAsLazyPagingItems()
            val ownerState = chatViewModel?.state?.collectAsState()?.value
            val ownerDraft = chatViewModel?.composerDraft?.collectAsState()?.value
            val ownerTranslations = chatViewModel?.externalTranslations?.collectAsState()?.value
            val ownerInFlight = chatViewModel?.externalTranslationInFlight?.collectAsState()?.value
            val forwardingOwner = chatViewModel.takeIf { forwardEnabled }
            val ownerForward = forwardingOwner?.forwardState?.collectAsState()?.value
            val ownerForwardTargets = forwardingOwner?.forwardTargets?.collectAsState()?.value
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides (activityResults ?: checkNotNull(LocalActivityResultRegistryOwner.current)),
                LocalDensity provides (fontScale?.let { Density(LocalDensity.current.density, it) } ?: LocalDensity.current),
                LocalDickordLabsEnabled provides dickordEnabled(),
            ) {
                MotdTheme(layoutDensity = layoutDensity()) {
                    Box(
                        Modifier
                            .then(contentHeight()?.let { Modifier.height(it).fillMaxWidth() } ?: Modifier.fillMaxSize())
                            .testTag("chat_surface_root"),
                    ) {
                        ChatContent(
                            state =
                                ownerState ?: ChatState(
                                    buffer = chatBuffer(),
                                    connState = liveConnection?.invoke() ?: connectionState,
                                    memberCount = memberCount,
                                    replyTo = replyTo(),
                                    parted = parted,
                                ),
                            items = items,
                            composerEnabled = true,
                            onBack = onBack,
                            showBack = showBack,
                            onOpenConversationList = onOpenConversationList,
                            historySyncStatus = historySyncStatus,
                            onOpenChannelInfo = onOpenChannelInfo,
                            ebooksHelperRoomId = ebooksHelperRoomId(),
                            ebooksDccOffers = ebooksDccOffers(),
                            onAcceptDccTransfer = onAcceptDccTransfer,
                            onRejectDccTransfer = onRejectDccTransfer,
                            onRemoveDccTransfer = onRemoveDccTransfer,
                            onSaveDccToDownloads = onSaveDccToDownloads,
                            onOpenSearch = onOpenSearch,
                            onOpenImage = {},
                            onInviteUser = onInviteUser,
                            nickNormalizer = { it.lowercase() },
                            onSubmit = onSubmit,
                            onTyping = {},
                            onSetReply = {
                                onSetReply(it)
                                chatViewModel?.setReply(it)
                            },
                            onReact = onReact,
                            reactionChips = reactionChips,
                            onRetry = {},
                            loadPreview = { _, _ -> null },
                            onAudioToggle = onAudioToggle,
                            composerDraft = ownerDraft ?: draft(),
                            aiTextEnabled = aiEnabled(),
                            aiTextState = aiState(),
                            onAiComposer = onAi,
                            onTranslateMessage = onTranslateMessage,
                            onBeginExternalTranslation = chatViewModel?.let { it::beginExternalTranslation } ?: onBeginExternalTranslation,
                            onCompleteExternalTranslation = chatViewModel?.let { it::completeExternalTranslation } ?: onCompleteExternalTranslation,
                            onAbortExternalTranslationLaunch = chatViewModel?.let { it::abortExternalTranslationLaunch } ?: onAbortExternalTranslationLaunch,
                            externalTranslationInFlight = ownerInFlight ?: externalTranslationInFlight(),
                            externalTranslations = ownerTranslations ?: emptyMap(),
                            onDismissExternalTranslation = { chatViewModel?.dismissExternalTranslation(it) },
                            forwardState = ownerForward,
                            forwardTargets = ownerForwardTargets ?: emptyList(),
                            onOpenForward = forwardingOwner?.let { it::openForward },
                            onSelectForwardDestination = { forwardingOwner?.selectForwardDestination(it) },
                            onChangeForwardDestination = { forwardingOwner?.changeForwardDestination() },
                            onForwardIncludeSenderChanged = { forwardingOwner?.setForwardIncludeSender(it) },
                            onConfirmForward = { forwardingOwner?.confirmForward() },
                            onDismissForward = { forwardingOwner?.dismissForward() },
                            outgoingFlight = outgoingFlight(),
                            onDraftChanged = onDraftChanged,
                            onFlightSettled = onFlightSettled,
                            initialTarget = initialTarget(),
                            entryState = entryState(),
                            unreadEntrySnapshot = unreadEntrySnapshot,
                            onInitialPositionHandled = onInitialPositionHandled,
                            rawNewestAnchor = rawNewestAnchor,
                            onMarkRead = onMarkRead,
                            showImages = showImages,
                            showLinkPreviews = showLinkPreviews,
                            composerStyle = composerStyle(),
                            chatWallpaper = chatWallpaper,
                            memberNicks = memberNicks,
                            voiceState = voiceState(),
                            onViewportRefreshAnchor = onViewportRefreshAnchor,
                        )
                        aiSheet()
                    }
                }
            }
        }
    }

    @Test
    fun dickordAudioUsesRawIdentityInsteadOfCleanOrPendingChatTitle() {
        val url = "https://files.example/voice.ogg?ex=abc&is=def&hm=123"
        var currentBuffer by mutableStateOf(
            buffer.copy(
                name = "#discord.me.chat.alice",
                displayName = "#discord.me.chat.alice",
                dickordChannelJson = """{"v":1,"guild_id":null,"guild_name":null,"channel_id":"456","channel_type":1,"parent_id":null,"channel_name":"Alice Smith"}""",
            ),
        )
        var enabled by mutableStateOf(true)
        val row = MessageEntity(id = 91, bufferId = buffer.id, serverTime = 100, sender = "Alice/discord", kind = MessageKind.PRIVMSG, text = url, dedupKey = "audio")
        val pages = MutableStateFlow(PagingData.from(listOf(row)))
        var played: AudioPlaybackRequest? = null
        setContent(
            draft = { ComposerDraftState("", hydrated = true, revision = 1) },
            pages = pages,
            chatBuffer = { currentBuffer },
            dickordEnabled = { enabled },
            onAudioToggle = { played = it },
        ) {}

        compose.onNodeWithText("Alice Smith", useUnmergedTree = true).assertIsDisplayed()
        for (body in listOf(url, "<$url>")) {
            compose.runOnIdle { pages.value = PagingData.from(listOf(row.copy(text = body))) }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("audio_player", useUnmergedTree = true).fetchSemanticsNodes().size == 1
            }
            compose.onAllNodesWithTag("audio_player", useUnmergedTree = true).assertCountEquals(1)
            compose.onNodeWithText(url, substring = true, useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("<>", useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithTag("audio_player_toggle", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals("Alice Smith", played?.origin?.conversation)
            assertEquals(url, played?.attachment?.url)
            assertEquals(url, played?.attachment?.displayUrl)
            assertEquals(false, played?.attachment?.voice)
        }

        compose.runOnIdle { currentBuffer = currentBuffer.copy(dickordChannelJson = null) }
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.dickord_portal_conversation_pending, buffer.id), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(url, substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodesWithTag("audio_player", useUnmergedTree = true).assertCountEquals(1)

        compose.runOnIdle { enabled = false }
        compose.onNodeWithText("<$url>", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            enabled = true
            currentBuffer = buffer
        }
        compose.onNodeWithText("<$url>", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithTag("audio_player", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun dickordAudioRetainsStyledCaptionAndOtherLinksAcrossChatDensities() {
        val url = "https://files.example/voice.ogg?ex=abc&is=def&hm=123"
        val literal = "https://literal.example/code"
        val other = "https://other.example/page"
        val body = "caption <$url> `$literal` then $other"
        val row =
            MessageEntity(
                id = 92,
                bufferId = buffer.id,
                serverTime = 100,
                sender = "Alice/discord",
                kind = MessageKind.PRIVMSG,
                text = body,
                ircFormattedText = "\u0002caption\u0002 <$url> `$literal` then $other",
                dedupKey = "caption-audio",
            )
        var density by mutableStateOf(LayoutDensity.COMFORTABLE)
        setContent(
            draft = { ComposerDraftState("", hydrated = true, revision = 1) },
            pages = flowOf(PagingData.from(listOf(row))),
            chatBuffer = {
                buffer.copy(
                    name = "#discord.me.chat.alice",
                    displayName = "#discord.me.chat.alice",
                    dickordChannelJson = """{"v":1,"guild_id":null,"guild_name":null,"channel_id":"456","channel_type":1,"parent_id":null,"channel_name":"Alice Smith"}""",
                )
            },
            dickordEnabled = { true },
            layoutDensity = { density },
        ) {}

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("audio_player", useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        for (layout in LayoutDensity.entries) {
            compose.runOnIdle { density = layout }
            val caption = compose.onNodeWithText("caption $literal then $other", substring = true, useUnmergedTree = true)
            caption.assertIsDisplayed()
            val annotated = caption.fetchSemanticsNode().config[SemanticsProperties.Text].single()
            assertTrue(annotated.spanStyles.any { it.item.fontWeight == FontWeight.Bold && annotated.text.substring(it.start, it.end) == "caption" })
            compose.onNodeWithText(url, substring = true, useUnmergedTree = true).assertDoesNotExist()
            compose.onAllNodesWithTag("audio_player", useUnmergedTree = true).assertCountEquals(1)
        }
        compose.onNodeWithTag("audio_player_details", useUnmergedTree = true).performClick()
        compose.onNodeWithText(url, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Conversation", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun dickordAudioLoadsAfterEntryVeilTimeoutWithoutSettling() {
        assertDickordAudioOnUnsettledEntry(EntryPositionState.Pending)
    }

    @Test
    fun dickordAudioLoadsWhenEntryIsDurablyUnresolved() {
        assertDickordAudioOnUnsettledEntry(EntryPositionState.Unresolved(messageUnavailable = false))
    }

    private fun assertDickordAudioOnUnsettledEntry(entryState: EntryPositionState) {
        MessageUrlCache.clearForTest()
        val url = "https://cdn.discordapp.com/attachments/123/777/voice-message.ogg?ex=feed&is=bead&hm=fixture"
        val standalone =
            MessageEntity(
                id = 101,
                bufferId = buffer.id,
                serverTime = 101_000,
                sender = "UiDickordFixture",
                kind = MessageKind.PRIVMSG,
                text = "<$url>",
                dedupKey = "unsettled-audio",
            )
        val caption =
            standalone.copy(
                id = 102,
                serverTime = 102_000,
                text = "Caption retained <$url>",
                ircFormattedText = "\u0002Caption retained\u0002 <$url>",
                dedupKey = "unsettled-caption",
            )
        var positionsHandled = 0
        var marksRead = 0
        setContent(
            draft = { ComposerDraftState("", hydrated = true, revision = 1) },
            pages = flowOf(PagingData.from(listOf(caption, standalone) + headerHistory())),
            chatBuffer = {
                buffer.copy(
                    name = "#discord.fixture.chat.777",
                    displayName = "#discord.fixture.chat.777",
                    dickordChannelJson = """{"v":1,"guild_id":null,"guild_name":null,"channel_id":"777","channel_type":1,"parent_id":null,"channel_name":"Audio fixture"}""",
                )
            },
            dickordEnabled = { true },
            entryState = { entryState },
            onInitialPositionHandled = { positionsHandled++ },
            rawNewestAnchor = TimelineAnchor(caption.serverTime, caption.id),
            onMarkRead = { marksRead++ },
            showImages = false,
            showLinkPreviews = false,
        ) {}

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("audio_player", useUnmergedTree = true).fetchSemanticsNodes().size == 2
        }
        compose.onAllNodesWithTag("audio_player", useUnmergedTree = true).assertCountEquals(2)
        val body = compose.onNodeWithText("Caption retained", useUnmergedTree = true)
        body.assertIsDisplayed()
        val annotated = body.fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(annotated.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        compose.onNodeWithText(url, substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("<>", useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodesWithTag("audio_player_details", useUnmergedTree = true).assertCountEquals(2)
        val timeline = compose.onNodeWithTag("chat_timeline")
        val title = compose.onNodeWithTag("chat_title")
        title.assertHeightIsAtLeast(48.dp)
        timeline.performScrollToIndex(50)
        title.assertHeightIsEqualTo(36.dp).assertHasNoClickAction()
        compose.onNodeWithTag("chat_compact_actions").assertIsDisplayed().assertTouchHeightIsEqualTo(56.dp)
        compose.onNodeWithTag("chat_compact_back").assertIsDisplayed().assertTouchHeightIsEqualTo(56.dp)
        compose.onNodeWithTag("chat_scroll_to_bottom_fab").assertIsDisplayed()
        timeline.performScrollToIndex(0)
        title.assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("chat_compact_actions").assertDoesNotExist()
        compose.onNodeWithTag("chat_compact_back").assertDoesNotExist()
        compose.onNodeWithTag("chat_scroll_to_bottom_fab").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, positionsHandled)
            assertEquals(0, marksRead)
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-w320dp-h360dp", fontScale = 2f)
    fun compactMessageActionsFitActualDialogViewportWithoutMutatingComposer() {
        // This geometry fixture leaves the short timeline visible; reply preservation has separate coverage.
        withTranslationOwner(replyToParent = false) { db, owner, source ->
            val draftChanges = mutableListOf<String>()
            val persistedDraft = runBlocking { db.composerDraftDao().byRoom(buffer.id) }
            val actions =
                listOf(
                    "message_action_reply" to R.string.chat_action_reply,
                    "message_action_translate" to R.string.chat_action_translate,
                    "message_action_copy" to R.string.chat_action_copy,
                    "message_action_share" to R.string.chat_action_share,
                    "message_action_quote" to R.string.chat_action_quote,
                )
            setContent(
                draft = { owner.composerDraft.value },
                chatViewModel = owner,
                pages = flowOf(PagingData.from(listOf(source))),
                onTranslateMessage = { error("Holding or canceling must not translate") },
                onDraftChanged = {
                    draftChanges += it
                    owner.saveDraft(it)
                },
                onSetReply = { error("Holding or canceling must not change the reply") },
                onSubmit = { error("Holding or canceling must not submit the composer") },
            )
            waitForForward(owner) {
                owner.composerDraft.value.hydrated && owner.state.value.buffer
                    ?.id == buffer.id &&
                    owner.state.value.replyTo == null
            }
            compose.onNodeWithTag("chat_composer_field").assertTextEquals("Unsent draft")
            val draft = owner.composerDraft.value
            // Hydration may report the initial cursor; only the following popup interaction is under test.
            val startupChanges = draftChanges.toList()
            compose.runOnIdle {
                assertEquals(persistedDraft, runBlocking { db.composerDraftDao().byRoom(buffer.id) })
                assertNull(owner.state.value.replyTo)
            }
            holdMessageBubble(source)
            compose.onNodeWithTag("message_action_sheet").assertIsDisplayed()
            val configuration = RuntimeEnvironment.getApplication().resources.configuration
            assertEquals("Use the real narrow native window", 320, configuration.screenWidthDp)
            assertEquals("Use the real short native window", 360, configuration.screenHeightDp)
            assertEquals("Use native 2x fonts, not a fixture-only density override", 2f, configuration.fontScale, 0f)
            val bounds = actions.map { (tag, _) -> compose.onNodeWithTag(tag).getUnclippedBoundsInRoot() }
            bounds.forEachIndexed { index, first ->
                bounds.drop(index + 1).forEach { second ->
                    assertTrue(
                        "Action touch targets must not overlap",
                        first.right <= second.left || second.right <= first.left ||
                            first.bottom <= second.top || second.bottom <= first.top,
                    )
                }
            }
            actions.forEach { (tag, resource) ->
                assertMessageActionFitsNativeViewport(tag, RuntimeEnvironment.getApplication().getString(resource))
            }
            compose
                .onNode(
                    SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss),
                    useUnmergedTree = true,
                ).performSemanticsAction(SemanticsActions.Dismiss) { it() }
            compose.onNodeWithTag("message_action_sheet").assertDoesNotExist()
            compose.onNodeWithTag("chat_composer_field").assertTextEquals(draft.text)
            compose.onNodeWithText("Replying to alice").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals("The popup must not emit another draft callback", startupChanges, draftChanges)
                assertEquals(draft, owner.composerDraft.value)
                assertNull(owner.state.value.replyTo)
                assertEquals(source, runBlocking { db.messageDao().byCanonicalId(source.id) })
                assertEquals(persistedDraft, runBlocking { db.composerDraftDao().byRoom(buffer.id) })
            }
        }
    }

    private fun assertMessageActionFitsNativeViewport(
        tag: String,
        label: String,
    ) {
        val action = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        val text = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(
            "The dialog label inherits the native font scale",
            RuntimeEnvironment
                .getApplication()
                .resources.configuration.fontScale,
            layout.layoutInput.density.fontScale,
            0f,
        )
        assertEquals("The entire localized label is laid out", label, layout.layoutInput.text.text)
        val lineBounds =
            (0 until layout.lineCount).map { line ->
                Rect(layout.getLineLeft(line), layout.getLineTop(line), layout.getLineRight(line), layout.getLineBottom(line))
            }
        val diagnostic =
            "$label: didOverflowWidth=${layout.didOverflowWidth}, didOverflowHeight=${layout.didOverflowHeight}, " +
                "size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                "lineCount=${layout.lineCount}, lineBounds=$lineBounds"
        if (layout.hasVisualOverflow) println("Native popup text layout: $diagnostic")
        // Line bounds are paragraph-local, even when centered text has a smaller wrap-content layout size.
        // Check complete lines in that coordinate frame; native label/touch containment is checked below.
        assertFalse("The $label layout must not omit lines: $diagnostic", layout.multiParagraph.didExceedMaxLines)
        assertTrue("The $label label must not ellipsize: $diagnostic", (0 until layout.lineCount).none { layout.isLineEllipsized(it) })
        assertEquals("The $label layout includes its final visible character: $diagnostic", label.trimEnd().length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        lineBounds.forEach { bounds ->
            assertTrue(
                "The complete line must fit the paragraph's painted area: $diagnostic",
                bounds.left >= -1f && bounds.top >= -1f &&
                    bounds.right <= layout.multiParagraph.width + 1f && bounds.bottom <= layout.multiParagraph.height + 1f,
            )
        }
        val node = action.fetchSemanticsNode()
        val touch = action.getUnclippedBoundsInRoot()
        val density = layout.layoutInput.density.density
        val origin = node.positionOnScreen - node.positionInRoot
        val touchOnScreen =
            Rect(
                origin.x + touch.left.value * density,
                origin.y + touch.top.value * density,
                origin.x + touch.right.value * density,
                origin.y + touch.bottom.value * density,
            )
        val textNode = text.fetchSemanticsNode()
        val labelOnScreen =
            Rect(
                textNode.positionOnScreen.x,
                textNode.positionOnScreen.y,
                textNode.positionOnScreen.x + textNode.size.width,
                textNode.positionOnScreen.y + textNode.size.height,
            )
        assertTrue("The $label touch target is at least 48dp wide", touchOnScreen.width + 1f >= 48f * density)
        assertTrue("The $label touch target is at least 48dp tall", touchOnScreen.height + 1f >= 48f * density)
        compose.runOnIdle {
            val view = (checkNotNull(node.root) as ViewRootForTest).view
            val visible = android.graphics.Rect()
            val screen = android.graphics.Rect()
            assertTrue("Use the actual attached dialog viewport", view.isAttachedToWindow && view.getLocalVisibleRect(visible))
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            visible.offset(position[0], position[1])
            view.getWindowVisibleDisplayFrame(screen)
            assertTrue("The dialog intersects the visible native screen", visible.intersect(screen))
            listOf("touch target" to touchOnScreen, "complete label" to labelOnScreen).forEach { (part, bounds) ->
                assertTrue(
                    "The $label $part $bounds must fit entirely inside the native viewport $visible",
                    bounds.left >= visible.left - 1f && bounds.top >= visible.top - 1f &&
                        bounds.right <= visible.right + 1f && bounds.bottom <= visible.bottom + 1f,
                )
            }
        }
    }

    @Test
    fun forwardPreviewToggleCancelAndConfirmPreserveComposer() {
        val destination = buffer.copy(id = 2, networkId = 2, name = "alice", displayName = "alice", type = BufferType.QUERY)
        val sameNamed = destination.copy(id = 3, networkId = buffer.networkId)
        val shares = PendingShareStore()
        val pending = PendingShare.AgentContext(sameNamed.id, "Unrelated", "Untouched payload", "partial")
        shares.set(pending)
        val sends = mutableListOf<List<Any?>>()
        withTranslationOwner(
            sourceText = "Hello\nworld",
            prepare = { db, parent ->
                db.networkDao().insert(NetworkEntity(id = 2, name = "Other network", role = NetworkRole.DIRECT, host = "irc.other", port = 6697, nick = "otherMe", username = "me", realname = "Me"))
                db.networkDao().insert(NetworkEntity(id = 3, name = "Offline network", role = NetworkRole.DIRECT, host = "irc.offline", port = 6697, nick = "offlineMe", username = "me", realname = "Me"))
                db.bufferDao().insert(destination)
                db.bufferDao().insert(sameNamed)
                db.bufferDao().insert(destination.copy(id = 4, networkId = 3))
                db.bufferDao().insert(destination.copy(id = 5, name = "archived", displayName = "archived", archived = true))
                val destinationReply = parent.copy(id = 99, bufferId = destination.id, text = "Destination reply", dedupKey = "destination-reply")
                db.messageDao().insertAll(listOf(destinationReply))
                ComposerDraftStore(db).saveDraft(destination.id, "Destination draft\nstill unsent", destinationReply.id)
            },
            createOwner = { db, scope ->
                ChatViewModelTest.externalTranslationFixture(
                    db,
                    buffer,
                    scope,
                    buffers = ChatViewModelTest.roomBuffers(db),
                    connectionStates =
                        mapOf(
                            buffer.networkId to IrcClientState.Ready("me", emptySet(), emptyMap()),
                            2L to IrcClientState.Ready("otherMe", emptySet(), emptyMap()),
                        ),
                    pendingShares = shares,
                    onSendMessage = { id, text, reply, context -> sends += listOf(id, text, reply, context) },
                )
            },
        ) { db, owner, parent ->
            var draftChanges = 0
            var submits = 0
            var replies = 0
            setContent(
                draft = { owner.composerDraft.value },
                chatViewModel = owner,
                forwardEnabled = true,
                pages = flowOf(PagingData.from(listOf(parent))),
                onDraftChanged = {
                    ++draftChanges
                    owner.saveDraft(it)
                },
                onSetReply = { ++replies },
                onSubmit = { ++submits },
            )
            waitForForward(owner) {
                owner.composerDraft.value.hydrated && owner.state.value.replyTo
                    ?.id == parent.id &&
                    owner.forwardTargets.value.size == 2
            }
            val originalDraft = owner.composerDraft.value
            val sourceDraft = runBlocking { db.composerDraftDao().byRoom(buffer.id) }
            val destinationDraft = runBlocking { db.composerDraftDao().byRoom(destination.id) }

            holdMessageBubble(parent)
            compose.onNodeWithTag("message_action_forward").assertIsDisplayed().performClick()
            compose.onNodeWithTag("message_action_sheet").assertDoesNotExist()
            compose.onNodeWithTag("message_forward_sheet").assertIsDisplayed()
            compose.onNodeWithTag("message_forward_target_${buffer.id}").assertDoesNotExist()
            compose.onNodeWithTag("message_forward_target_4").assertDoesNotExist()
            compose.onNodeWithTag("message_forward_target_5").assertDoesNotExist()
            compose.onNode(hasText("Other network") and hasAnyAncestor(hasTestTag("message_forward_target_${destination.id}")), useUnmergedTree = true).assertIsDisplayed()
            compose.onNode(hasText("test") and hasAnyAncestor(hasTestTag("message_forward_target_${sameNamed.id}")), useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("message_forward_search").performTextReplacement("missing")
            compose.onNodeWithText("No available chats").assertIsDisplayed()
            compose.onNodeWithTag("message_forward_target_${destination.id}").assertDoesNotExist()
            compose.onNodeWithTag("message_forward_search").performTextReplacement("ALIce")
            compose.onNodeWithTag("message_forward_target_${sameNamed.id}").assertIsDisplayed()
            compose.onNodeWithTag("message_forward_target_${destination.id}").assertIsDisplayed().performClick()
            compose.onNodeWithText("Other network").assertIsDisplayed()
            compose.onNodeWithTag("message_forward_preview").assertTextEquals("> alice: Hello\n> alice: world")
            val toggle = compose.onNodeWithTag("message_forward_include_sender")
            toggle.assertIsOn().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Checkbox))
            compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("message_forward_include_sender")), useUnmergedTree = true).assertCountEquals(0)
            toggle.performClick().assertIsOff()
            compose.onNodeWithTag("message_forward_preview").assertTextEquals("> Hello\n> world")
            compose.runOnIdle {
                assertEquals(
                    destination.id,
                    owner.forwardState.value
                        ?.destination
                        ?.bufferId,
                )
                assertTrue(sends.isEmpty())
            }
            compose.onNodeWithTag("message_forward_change_destination").performClick()
            compose.onNodeWithTag("message_forward_preview").assertDoesNotExist()
            compose.onNodeWithTag("message_forward_target_${destination.id}").performClick()
            compose.onNodeWithTag("message_forward_include_sender").assertIsOff()
            compose.onNodeWithTag("message_forward_preview").assertTextEquals("> Hello\n> world")
            compose.onNodeWithTag("message_forward_cancel").performClick()
            compose.onNodeWithTag("message_forward_sheet").assertDoesNotExist()
            compose.onNodeWithTag("chat_composer_field").assertTextEquals(originalDraft.text)
            compose.onNodeWithText("Replying to alice").assertIsDisplayed()
            compose.runOnIdle {
                assertTrue(sends.isEmpty())
                assertNull(owner.forwardState.value)
                assertEquals(originalDraft, owner.composerDraft.value)
                assertEquals(parent, owner.state.value.replyTo)
                assertEquals(sourceDraft, runBlocking { db.composerDraftDao().byRoom(buffer.id) })
                assertEquals(destinationDraft, runBlocking { db.composerDraftDao().byRoom(destination.id) })
                assertEquals(pending, shares.peek())
                assertEquals(0, draftChanges)
                assertEquals(0, submits)
                assertEquals(0, replies)
            }

            holdMessageBubble(parent)
            compose.onNodeWithTag("message_action_forward").performClick()
            compose.onNodeWithTag("message_forward_search").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.onNodeWithTag("message_forward_target_${destination.id}").performClick()
            compose.onNodeWithTag("message_forward_include_sender").assertIsOn()
            compose.onNodeWithTag("message_forward_preview").assertTextEquals("> alice: Hello\n> alice: world")
            compose.onNodeWithTag("message_forward_include_sender").performClick()
            compose.onNodeWithTag("message_forward_preview").assertTextEquals("> Hello\n> world")
            compose.onNodeWithTag("message_forward_confirm").assertIsEnabled().performClick()
            waitForForward(owner) { owner.forwardState.value == null && sends.size == 1 }
            compose.onNodeWithTag("message_forward_sheet").assertDoesNotExist()
            compose.onNodeWithTag("chat_composer_field").assertTextEquals(originalDraft.text)
            compose.onNodeWithText("Replying to alice").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(listOf(listOf(destination.id, "> Hello\n> world", null, null)), sends)
                assertEquals(originalDraft, owner.composerDraft.value)
                assertEquals(parent, owner.state.value.replyTo)
                assertEquals(parent, runBlocking { db.messageDao().byCanonicalId(parent.id) })
                assertEquals(sourceDraft, runBlocking { db.composerDraftDao().byRoom(buffer.id) })
                assertEquals(destinationDraft, runBlocking { db.composerDraftDao().byRoom(destination.id) })
                assertEquals(pending, shares.peek())
                assertEquals(0, draftChanges)
                assertEquals(0, submits)
                assertEquals(0, replies)
            }
        }
    }

    @Test
    fun forwardSendingLocksSheetAndDurableUnsentAcceptanceOffersOnlyClose() {
        val destination = buffer.copy(id = 2, name = "alice", displayName = "alice", type = BufferType.QUERY)
        val gate = CompletableDeferred<Unit>()
        val sends = mutableListOf<String>()
        withTranslationOwner(
            prepare = { db, _ -> db.bufferDao().insert(destination) },
            createOwner = { db, scope ->
                ChatViewModelTest.externalTranslationFixture(
                    db,
                    buffer,
                    scope,
                    buffers = ChatViewModelTest.roomBuffers(db),
                    sendGate = gate,
                    immediateWireAcceptance = ImmediateWireAcceptance.DISCONNECTED,
                    onSendMessage = { _, text, _, _ -> sends += text },
                )
            },
        ) { _, owner, parent ->
            setContent(
                draft = { owner.composerDraft.value },
                chatViewModel = owner,
                forwardEnabled = true,
                pages = flowOf(PagingData.from(listOf(parent))),
                onDraftChanged = { error("forward changed draft") },
                onSubmit = { error("forward submitted composer") },
            )
            waitForForward(owner) { owner.composerDraft.value.hydrated && owner.forwardTargets.value.size == 1 }
            holdMessageBubble(parent)
            compose.onNodeWithTag("message_action_forward").performClick()
            compose.onNodeWithTag("message_forward_target_${destination.id}").performClick()
            compose.onNodeWithTag("message_forward_confirm").performClick()
            waitForForward(owner) { owner.forwardState.value?.sending == true && sends.size == 1 }
            compose.onNodeWithTag("message_forward_include_sender").assertIsNotEnabled().assertIsOn()
            compose.onNodeWithTag("message_forward_change_destination").assertIsNotEnabled()
            compose.onNodeWithTag("message_forward_confirm").assertIsNotEnabled()
            compose.onNodeWithTag("message_forward_cancel").assertIsNotEnabled()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("message_forward_sheet").performTouchInput {
                swipe(Offset(centerX, height * 0.15f), Offset(centerX, height * 0.95f), durationMillis = 400)
            }
            compose.waitForIdle()
            compose.onNodeWithTag("message_forward_sheet").assertIsDisplayed()
            compose.runOnIdle {
                assertTrue(requireNotNull(owner.forwardState.value).sending)
                assertEquals(1, sends.size)
                gate.complete(Unit)
            }
            waitForForward(owner) { owner.forwardState.value?.acceptance != null && owner.forwardState.value?.sending == false }
            compose.onNodeWithTag("message_forward_error").assertTextEquals("Message saved in the destination but not fully sent. Open that chat to review delivery.")
            compose.onNodeWithTag("message_forward_preview").assertTextEquals("> alice: Bonjour")
            compose.onNodeWithTag("message_forward_include_sender").assertDoesNotExist()
            compose.onNodeWithTag("message_forward_change_destination").assertDoesNotExist()
            compose.onNodeWithTag("message_forward_confirm").assertDoesNotExist()
            compose.onNodeWithTag("message_forward_cancel").assertIsEnabled().assertTextEquals("Close")
            compose.runOnIdle {
                assertEquals(
                    ImmediateWireAcceptance.DISCONNECTED,
                    owner.forwardState.value
                        ?.acceptance
                        ?.immediateWireAcceptance,
                )
                assertEquals(listOf("> alice: Bonjour"), sends)
                owner.confirmForward()
                assertEquals(1, sends.size)
            }
            compose.onNodeWithTag("message_forward_cancel").performClick()
            compose.onNodeWithTag("message_forward_sheet").assertDoesNotExist()
            compose.onNodeWithTag("chat_composer_field").assertTextEquals("Unsent draft")
            compose.onNodeWithText("Replying to alice").assertIsDisplayed()
        }
    }

    @Test
    fun forwardChangedSourceDisablesPreviewEditsAndConfirmationWithoutSending() {
        val destination = buffer.copy(id = 2, name = "alice", displayName = "alice", type = BufferType.QUERY)
        withTranslationOwner(
            prepare = { db, _ -> db.bufferDao().insert(destination) },
            createOwner = { db, scope ->
                ChatViewModelTest.externalTranslationFixture(
                    db,
                    buffer,
                    scope,
                    buffers = ChatViewModelTest.roomBuffers(db),
                    onSendMessage = { _, _, _, _ -> error("changed source was sent") },
                )
            },
        ) { db, owner, parent ->
            setContent(
                draft = { owner.composerDraft.value },
                chatViewModel = owner,
                forwardEnabled = true,
                pages = flowOf(PagingData.from(listOf(parent))),
                onDraftChanged = { error("forward changed draft") },
                onSubmit = { error("forward submitted composer") },
            )
            waitForForward(owner) { owner.composerDraft.value.hydrated && owner.forwardTargets.value.size == 1 }
            holdMessageBubble(parent)
            compose.onNodeWithTag("message_action_forward").performClick()
            compose.onNodeWithTag("message_forward_target_${destination.id}").performClick()
            compose.runOnIdle { runBlocking { db.messageDao().update(parent.copy(text = "Changed")) } }
            compose.onNodeWithTag("message_forward_confirm").performClick()
            waitForForward(owner) { owner.forwardState.value?.error == MessageForwardError.SOURCE_UNAVAILABLE }
            compose.onNodeWithTag("message_forward_error").assertTextEquals("This message changed or is no longer available. Reopen Forward.")
            compose.onNodeWithTag("message_forward_preview").assertTextEquals("> alice: Bonjour")
            compose.onNodeWithTag("message_forward_include_sender").assertIsNotEnabled()
            compose.onNodeWithTag("message_forward_change_destination").assertIsNotEnabled()
            compose.onNodeWithTag("message_forward_confirm").assertIsNotEnabled()
            compose
                .onNodeWithTag("message_forward_cancel")
                .assertIsEnabled()
                .assertTextEquals("Close")
                .performClick()
            compose.onNodeWithTag("message_forward_sheet").assertDoesNotExist()
            compose.onNodeWithTag("chat_composer_field").assertTextEquals("Unsent draft")
        }
    }

    @Test
    fun forwardChangedDestinationReturnsToPickerForExplicitReview() {
        val destination = buffer.copy(id = 2, name = "alice", displayName = "alice", type = BufferType.QUERY)
        val sends = mutableListOf<String>()
        withTranslationOwner(
            prepare = { db, _ -> db.bufferDao().insert(destination) },
            createOwner = { db, scope ->
                ChatViewModelTest.externalTranslationFixture(
                    db,
                    buffer,
                    scope,
                    buffers = ChatViewModelTest.roomBuffers(db),
                    onSendMessage = { _, text, _, _ -> sends += text },
                )
            },
        ) { db, owner, parent ->
            setContent(
                draft = { owner.composerDraft.value },
                chatViewModel = owner,
                forwardEnabled = true,
                pages = flowOf(PagingData.from(listOf(parent))),
                onDraftChanged = { error("forward changed draft") },
                onSubmit = { error("forward submitted composer") },
            )
            waitForForward(owner) { owner.composerDraft.value.hydrated && owner.forwardTargets.value.size == 1 }
            holdMessageBubble(parent)
            compose.onNodeWithTag("message_action_forward").performClick()
            compose.onNodeWithTag("message_forward_target_${destination.id}").performClick()
            compose.runOnIdle {
                runBlocking {
                    db.networkDao().update(requireNotNull(db.networkDao().byId(buffer.networkId)).copy(name = "Renamed network"))
                }
            }
            compose.onNodeWithTag("message_forward_confirm").performClick()
            waitForForward(owner) {
                owner.forwardState.value?.error == MessageForwardError.TARGET_UNAVAILABLE &&
                    owner.forwardState.value?.destination == null
            }
            compose.onNodeWithTag("message_forward_error").assertTextEquals("This chat is no longer available for forwarding. Choose another chat.")
            compose.onNodeWithTag("message_forward_preview").assertDoesNotExist()
            compose.onNodeWithTag("message_forward_confirm").assertDoesNotExist()
            compose.runOnIdle {
                assertNull(owner.forwardState.value?.destination)
                assertTrue(sends.isEmpty())
            }
            waitForForward(owner) {
                owner.forwardTargets.value
                    .singleOrNull()
                    ?.networkName == "Renamed network"
            }
            compose.onNodeWithTag("message_forward_target_${destination.id}").performClick()
            compose.onNodeWithText("Renamed network").assertIsDisplayed()
            compose.onNodeWithTag("message_forward_error").assertDoesNotExist()
            compose.onNodeWithTag("message_forward_preview").assertTextEquals("> alice: Bonjour")
            compose.onNodeWithTag("message_forward_confirm").assertIsEnabled()
            compose.runOnIdle { assertTrue(sends.isEmpty()) }
            compose.onNodeWithTag("message_forward_confirm").performClick()
            waitForForward(owner) { owner.forwardState.value == null && sends.size == 1 }
            compose.runOnIdle { assertEquals(listOf("> alice: Bonjour"), sends) }
            compose.onNodeWithTag("chat_composer_field").assertTextEquals("Unsent draft")
        }
    }

    @Test
    fun forwardRejectedSendRetainsReviewAndRetriesOnlyAfterExplicitConfirmation() {
        val destination = buffer.copy(id = 2, name = "alice", displayName = "alice", type = BufferType.QUERY)
        val attempts = mutableListOf<String>()
        withTranslationOwner(
            prepare = { db, _ -> db.bufferDao().insert(destination) },
            createOwner = { db, scope ->
                ChatViewModelTest.externalTranslationFixture(
                    db,
                    buffer,
                    scope,
                    buffers = ChatViewModelTest.roomBuffers(db),
                    sendRejection = SendRejectionReason.PERSISTENCE_FAILED,
                    onSendMessage = { _, text, _, _ -> attempts += text },
                )
            },
        ) { _, owner, parent ->
            setContent(
                draft = { owner.composerDraft.value },
                chatViewModel = owner,
                forwardEnabled = true,
                pages = flowOf(PagingData.from(listOf(parent))),
                onDraftChanged = { error("forward changed draft") },
                onSubmit = { error("forward submitted composer") },
            )
            waitForForward(owner) { owner.composerDraft.value.hydrated && owner.forwardTargets.value.size == 1 }
            holdMessageBubble(parent)
            compose.onNodeWithTag("message_action_forward").performClick()
            compose.onNodeWithTag("message_forward_target_${destination.id}").performClick()
            compose.onNodeWithTag("message_forward_confirm").performClick()
            waitForForward(owner) { owner.forwardState.value?.error == MessageForwardError.SEND_REJECTED && attempts.size == 1 }
            compose.onNodeWithTag("message_forward_error").assertTextEquals("Couldn't save this message")
            compose.onNodeWithTag("message_forward_preview").assertTextEquals("> alice: Bonjour")
            compose.onNodeWithTag("message_forward_include_sender").assertIsEnabled().performClick()
            compose.onNodeWithTag("message_forward_preview").assertTextEquals("> Bonjour")
            compose.onNodeWithTag("message_forward_change_destination").assertIsEnabled().performClick()
            compose.onNodeWithTag("message_forward_error").assertDoesNotExist()
            compose.onNodeWithTag("message_forward_target_${destination.id}").performClick()
            compose.onNodeWithTag("message_forward_confirm").assertIsEnabled()
            compose.runOnIdle { assertEquals(listOf("> alice: Bonjour"), attempts) }
            compose.onNodeWithTag("message_forward_confirm").performClick()
            waitForForward(owner) { owner.forwardState.value?.error == MessageForwardError.SEND_REJECTED && attempts.size == 2 }
            compose.runOnIdle {
                assertNull(owner.forwardState.value?.acceptance)
                assertEquals(listOf("> alice: Bonjour", "> Bonjour"), attempts)
            }
            compose
                .onNodeWithTag("message_forward_cancel")
                .assertIsEnabled()
                .assertTextEquals("Cancel")
                .performClick()
            compose.onNodeWithTag("message_forward_sheet").assertDoesNotExist()
            compose.onNodeWithTag("chat_composer_field").assertTextEquals("Unsent draft")
        }
    }

    @Test
    fun forwardUncertainSendFailureDisablesFurtherSendingFromTheRequest() {
        val destination = buffer.copy(id = 2, name = "alice", displayName = "alice", type = BufferType.QUERY)
        var attempts = 0
        withTranslationOwner(
            prepare = { db, _ -> db.bufferDao().insert(destination) },
            createOwner = { db, scope ->
                ChatViewModelTest.externalTranslationFixture(
                    db,
                    buffer,
                    scope,
                    buffers = ChatViewModelTest.roomBuffers(db),
                    onSendMessage = { _, _, _, _ ->
                        ++attempts
                        error("uncertain send")
                    },
                )
            },
        ) { _, owner, parent ->
            setContent(
                draft = { owner.composerDraft.value },
                chatViewModel = owner,
                forwardEnabled = true,
                pages = flowOf(PagingData.from(listOf(parent))),
                onDraftChanged = { error("forward changed draft") },
                onSubmit = { error("forward submitted composer") },
            )
            waitForForward(owner) { owner.composerDraft.value.hydrated && owner.forwardTargets.value.size == 1 }
            holdMessageBubble(parent)
            compose.onNodeWithTag("message_action_forward").performClick()
            compose.onNodeWithTag("message_forward_target_${destination.id}").performClick()
            compose.onNodeWithTag("message_forward_confirm").performClick()
            waitForForward(owner) { owner.forwardState.value?.error == MessageForwardError.SEND_FAILED && attempts == 1 }
            compose.onNodeWithTag("message_forward_error").assertTextEquals("Forwarding could not be confirmed. Check the destination before trying again.")
            compose.onNodeWithTag("message_forward_preview").assertTextEquals("> alice: Bonjour")
            compose.onNodeWithTag("message_forward_include_sender").assertIsNotEnabled()
            compose.onNodeWithTag("message_forward_change_destination").assertIsNotEnabled()
            compose.onNodeWithTag("message_forward_confirm").assertIsNotEnabled()
            compose.runOnIdle {
                owner.confirmForward()
                assertEquals(1, attempts)
            }
            compose
                .onNodeWithTag("message_forward_cancel")
                .assertIsEnabled()
                .assertTextEquals("Close")
                .performClick()
            compose.onNodeWithTag("message_forward_sheet").assertDoesNotExist()
            compose.onNodeWithTag("chat_composer_field").assertTextEquals("Unsent draft")
        }
    }

    @Test
    fun forwardWithoutWiredOwnerStaysHiddenInExistingFixtures() {
        val parent = translationMessage()
        setContent(
            draft = { ComposerDraftState("Unsent draft", hydrated = true, revision = 17) },
            pages = flowOf(PagingData.from(listOf(parent))),
            onSubmit = { error("forward submitted composer") },
        )
        holdMessageBubble(parent)
        compose.onNodeWithTag("message_action_sheet").assertIsDisplayed()
        compose.onNodeWithTag("message_action_forward").assertDoesNotExist()
    }

    @Test
    fun externalTranslationReturnsInlineWithoutMutatingDraftReplyOrHistory() {
        registerTranslationHandler()
        withTranslationOwner { db, owner, parent ->
            var submits = 0
            var draftChanges = 0
            var replies = 0
            setContent(
                draft = { owner.composerDraft.value },
                chatViewModel = owner,
                pages = flowOf(PagingData.from(listOf(parent))),
                activityResults = pickerOwner,
                onSetReply = { ++replies },
                onDraftChanged = {
                    ++draftChanges
                    owner.saveDraft(it)
                },
                onSubmit = { ++submits },
            )
            compose.waitUntil(5_000) {
                owner.composerDraft.value.hydrated &&
                    owner.state.value.replyTo
                        ?.id == parent.id
            }
            val originalDraft = owner.composerDraft.value
            val originalPersistedDraft = runBlocking { db.composerDraftDao().byRoom(buffer.id) }
            openTranslationPicker()
            compose.onNodeWithTag("message_translation_local").assertDoesNotExist()
            compose.runOnIdle { assertTrue(pickerLaunchedIntents.isEmpty()) }
            compose.onNodeWithTag("message_translation_app_com.deepl.mobiletranslator").performClick()
            compose.waitUntil(5_000) { pickerLaunchedIntents.isNotEmpty() }
            compose.runOnIdle {
                assertEquals("Bonjour", pickerLaunchedIntents.single().getStringExtra(Intent.EXTRA_PROCESS_TEXT))
                assertTrue(owner.externalTranslationInFlight.value)
                pickerRegistry.dispatchResult(pickerRequestCode, Activity.RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, "Hello"))
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("message_translation_88").fetchSemanticsNodes().isNotEmpty() }
            val original = compose.onNodeWithTag("chat_message_88", useUnmergedTree = true)
            val translated = compose.onNodeWithTag("message_translation_88", useUnmergedTree = true)
            original.assertIsDisplayed()
            translated.assertIsDisplayed()
            compose.onNode(hasText("Bonjour") and hasAnyAncestor(hasTestTag("chat_message_88")), useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("Hello", useUnmergedTree = true).assertIsDisplayed()
            assertTrue(original.fetchSemanticsNode().boundsInRoot.bottom <= translated.fetchSemanticsNode().boundsInRoot.top)
            val returnedText = compose.onNodeWithText("Hello", useUnmergedTree = true)
            assertFalse(returnedText.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsActions.CustomActions))
            returnedText.performTouchInput {
                swipe(Offset(width * 0.15f, centerY), Offset(width * 0.85f, centerY), durationMillis = 400)
            }
            compose.runOnIdle {
                assertEquals(0, replies)
                assertEquals(0, submits)
                assertEquals(0, draftChanges)
                assertEquals(originalDraft, owner.composerDraft.value)
                assertEquals(
                    parent.id,
                    owner.state.value.replyTo
                        ?.id,
                )
                assertEquals(parent, runBlocking { db.messageDao().byCanonicalId(parent.id) })
                assertEquals(originalPersistedDraft, runBlocking { db.composerDraftDao().byRoom(buffer.id) })
            }
            compose.onNodeWithTag("message_translation_dismiss_88").performClick()
            compose.onNodeWithTag("message_translation_88").assertDoesNotExist()
            compose.runOnIdle {
                assertTrue(owner.externalTranslations.value.isEmpty())
                assertEquals(originalDraft, owner.composerDraft.value)
                assertEquals(
                    parent.id,
                    owner.state.value.replyTo
                        ?.id,
                )
                assertEquals(originalPersistedDraft, runBlocking { db.composerDraftDao().byRoom(buffer.id) })
            }
        }
    }

    @Test
    fun externalTranslationOriginalSourceSwipeStillSetsReplyInFreshOwner() {
        withTranslationOwner(replyToParent = false) { _, owner, parent ->
            var replies = 0
            setContent(
                draft = { owner.composerDraft.value },
                chatViewModel = owner,
                pages = flowOf(PagingData.from(listOf(parent))),
                onSetReply = { ++replies },
                onDraftChanged = owner::saveDraft,
                onSubmit = { error("reply swipe sent") },
            )
            compose.waitUntil(5_000) { owner.composerDraft.value.hydrated && owner.state.value.buffer != null }
            compose.runOnIdle { assertNull(owner.state.value.replyTo) }
            compose.onNodeWithTag("chat_message_88", useUnmergedTree = true).performTouchInput {
                swipe(Offset(width * 0.15f, centerY), Offset(width * 0.85f, centerY), durationMillis = 400)
            }
            compose.waitUntil(5_000) {
                owner.state.value.replyTo
                    ?.id == parent.id
            }
            compose.runOnIdle { assertEquals(1, replies) }
        }
    }

    @Test
    fun externalTranslationCanceledOrMissingOutputPreservesRealOwnersPreviousInlineResult() {
        registerTranslationHandler()
        withTranslationOwner { db, owner, parent ->
            setContent(
                draft = { owner.composerDraft.value },
                chatViewModel = owner,
                pages = flowOf(PagingData.from(listOf(parent))),
                activityResults = pickerOwner,
                onDraftChanged = { error("external result changed draft") },
                onSubmit = { error("external result sent") },
            )
            compose.waitUntil(5_000) {
                owner.composerDraft.value.hydrated &&
                    owner.state.value.replyTo
                        ?.id == parent.id
            }
            val draft = owner.composerDraft.value
            val persistedDraft = runBlocking { db.composerDraftDao().byRoom(buffer.id) }
            openTranslationPicker()
            compose.onNodeWithTag("message_translation_app_com.deepl.mobiletranslator").performClick()
            compose.waitUntil(5_000) { pickerLaunchedIntents.size == 1 }
            compose.runOnIdle {
                pickerRegistry.dispatchResult(pickerRequestCode, Activity.RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, "Hello"))
            }
            compose.onNodeWithTag("message_translation_88").assertIsDisplayed()
            val canceledResults =
                listOf(
                    Activity.RESULT_CANCELED to Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, "Ignored"),
                    Activity.RESULT_OK to Intent(),
                    Activity.RESULT_OK to Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, " \n"),
                )
            for ((index, result) in canceledResults.withIndex()) {
                openTranslationPicker()
                compose.onNodeWithTag("message_translation_app_com.deepl.mobiletranslator").performClick()
                compose.waitUntil(5_000) { pickerLaunchedIntents.size == index + 2 }
                compose.runOnIdle { pickerRegistry.dispatchResult(pickerRequestCode, result.first, result.second) }
                compose.waitUntil(5_000) { !owner.externalTranslationInFlight.value }
                compose.onNodeWithText("Hello", useUnmergedTree = true).assertIsDisplayed()
                compose.runOnIdle {
                    assertEquals("Hello", owner.externalTranslations.value[parent.id]?.text)
                    assertEquals(draft, owner.composerDraft.value)
                    assertEquals(
                        parent.id,
                        owner.state.value.replyTo
                            ?.id,
                    )
                    assertEquals(parent, runBlocking { db.messageDao().byCanonicalId(parent.id) })
                    assertEquals(persistedDraft, runBlocking { db.composerDraftDao().byRoom(buffer.id) })
                }
            }
        }
    }

    @Test
    fun externalTranslationSendOnlyRealOwnerCreatesNoInlinePlaceholder() {
        registerTranslationHandler(packageName = "ru.yandex.translate", action = Intent.ACTION_SEND)
        withTranslationOwner { db, owner, parent ->
            setContent(
                draft = { owner.composerDraft.value },
                chatViewModel = owner,
                pages = flowOf(PagingData.from(listOf(parent))),
                activityResults = pickerOwner,
                onDraftChanged = { error("external handoff changed draft") },
                onSubmit = { error("external handoff sent") },
            )
            compose.waitUntil(5_000) {
                owner.composerDraft.value.hydrated &&
                    owner.state.value.replyTo
                        ?.id == parent.id
            }
            val draft = owner.composerDraft.value
            val persistedDraft = runBlocking { db.composerDraftDao().byRoom(buffer.id) }
            compose.runOnIdle { shadowOf(RuntimeEnvironment.getApplication()).clearNextStartedActivities() }
            openTranslationPicker()
            compose.onNodeWithText("Opens externally").assertIsDisplayed()
            compose.onNodeWithTag("message_translation_app_ru.yandex.translate").performClick()
            compose.waitUntil(5_000) { shadowOf(RuntimeEnvironment.getApplication()).peekNextStartedActivity() != null }
            compose.onNodeWithTag("message_translation_88").assertDoesNotExist()
            compose.runOnIdle {
                assertFalse(owner.externalTranslationInFlight.value)
                assertTrue(owner.externalTranslations.value.isEmpty())
                assertTrue(pickerLaunchedIntents.isEmpty())
                assertEquals(draft, owner.composerDraft.value)
                assertEquals(
                    parent.id,
                    owner.state.value.replyTo
                        ?.id,
                )
                assertEquals(parent, runBlocking { db.messageDao().byCanonicalId(parent.id) })
                assertEquals(persistedDraft, runBlocking { db.composerDraftDao().byRoom(buffer.id) })
            }
        }
    }

    @Test
    fun externalTranslationNativePickerReturnsPlainTextWithoutMutatingDraft() {
        registerTranslationHandler()
        val parent = translationMessage()
        val draft = ComposerDraftState("Unsent draft", hydrated = true, revision = 17)
        val returned = mutableListOf<String?>()
        val selected = mutableListOf<Long>()
        val db =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MotdDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        val store = ComposerDraftStore(db)
        runBlocking {
            db.networkDao().insert(NetworkEntity(id = buffer.networkId, name = "test", role = NetworkRole.DIRECT, host = "irc.example", port = 6697, nick = "me", username = "me", realname = "Me"))
            db.bufferDao().insert(buffer)
            db.messageDao().insertAll(listOf(parent))
            store.saveDraft(buffer.id, draft.text, parent.id)
        }
        try {
            setContent(
                draft = { draft },
                replyTo = { parent },
                pages = flowOf(PagingData.from(listOf(parent))),
                activityResults = pickerOwner,
                onBeginExternalTranslation = { source ->
                    selected += source.id
                    val canonical = requireNotNull(db.messageDao().byCanonicalId(source.id))
                    assertEquals(source.text, canonical.text)
                    translationRequest(canonical)
                },
                onCompleteExternalTranslation = { returned += it },
                onAbortExternalTranslationLaunch = { error("unexpected launch abort") },
                onDraftChanged = { error("translation changed draft") },
                onSubmit = { error("translation sent") },
            )
            openTranslationPicker()
            compose.onNodeWithTag("message_translation_local").assertDoesNotExist()
            compose.onNodeWithTag("message_translation_app_com.deepl.mobiletranslator").assertHeightIsAtLeast(48.dp)
            compose.runOnIdle {
                assertTrue(pickerLaunchedIntents.isEmpty())
                assertTrue(selected.isEmpty())
            }
            compose.onNodeWithTag("message_translation_app_com.deepl.mobiletranslator").performClick()
            compose.waitUntil(5_000) { pickerLaunchedIntents.isNotEmpty() }
            compose.runOnIdle {
                val intent = pickerLaunchedIntents.single()
                assertEquals(Intent.ACTION_PROCESS_TEXT, intent.action)
                assertEquals("com.deepl.mobiletranslator", intent.`package`)
                assertEquals("text/plain", intent.type)
                assertEquals("Bonjour 👋\nSalut", intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT))
                assertFalse(intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true))
                assertEquals(setOf(Intent.EXTRA_PROCESS_TEXT, Intent.EXTRA_PROCESS_TEXT_READONLY), intent.extras!!.keySet())
                assertNull(intent.component)
                assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
                pickerRegistry.dispatchResult(
                    pickerRequestCode,
                    Activity.RESULT_OK,
                    Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, SpannableString("Hello 👋\nWorld") as CharSequence),
                )
            }
            compose.runOnIdle {
                assertEquals(listOf(88L), selected)
                assertEquals(listOf("Hello 👋\nWorld"), returned)
                assertEquals("Unsent draft", draft.text)
                assertEquals(17L, draft.revision)
                assertEquals(parent.text, runBlocking { db.messageDao().byCanonicalId(parent.id) }?.text)
                val stored = runBlocking { store.loadDraft(buffer.id) }
                assertEquals(draft.text, stored?.text)
                assertEquals(parent.id, stored?.replyToEventId)
            }
            compose.onNodeWithTag("message_translation_picker").assertDoesNotExist()
            compose.onNodeWithTag("chat_message_88", useUnmergedTree = true).assertIsDisplayed()
        } finally {
            db.close()
        }
    }

    @Test
    fun externalTranslationOtherUsesProcessChooserAndCanceledMissingBlankOrMalformedReturnsAreQuiet() {
        registerTranslationHandler(packageName = "example.textprocessor")
        val parent = translationMessage()
        val returned = mutableListOf<String?>()
        setContent(
            draft = { ComposerDraftState("Unsent draft", hydrated = true, revision = 17) },
            pages = flowOf(PagingData.from(listOf(parent))),
            activityResults = pickerOwner,
            onBeginExternalTranslation = { translationRequest(it) },
            onCompleteExternalTranslation = { returned += it },
            onAbortExternalTranslationLaunch = { error("unexpected launch abort") },
            onDraftChanged = { error("translation changed draft") },
            onSubmit = { error("translation sent") },
        )
        val results =
            listOf(
                Activity.RESULT_CANCELED to Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, "Ignored"),
                Activity.RESULT_OK to Intent(),
                Activity.RESULT_OK to Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, " \n"),
                Activity.RESULT_OK to Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, 42),
            )
        for ((index, result) in results.withIndex()) {
            openTranslationPicker()
            compose.onNodeWithText("No supported translation apps installed.").assertIsDisplayed()
            compose.onNodeWithTag("message_translation_app_example.textprocessor").assertDoesNotExist()
            compose
                .onNodeWithTag("message_translation_other")
                .assertIsEnabled()
                .performClick()
            compose.waitUntil(5_000) { pickerLaunchedIntents.size == index + 1 }
            compose.runOnIdle {
                val chooser = pickerLaunchedIntents.last()
                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                assertEquals("Translate message", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
                val process = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                assertEquals(Intent.ACTION_PROCESS_TEXT, process.action)
                assertNull(process.`package`)
                assertEquals("text/plain", process.type)
                assertEquals("Bonjour 👋\nSalut", process.getStringExtra(Intent.EXTRA_PROCESS_TEXT))
                assertFalse(process.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true))
                pickerRegistry.dispatchResult(pickerRequestCode, result.first, result.second)
            }
        }
        compose.runOnIdle { assertEquals(listOf(null, null, null, null), returned) }
        compose.onNodeWithTag("chat_message_88", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun externalTranslationSendOnlyOpensDedicatedAppAndReleasesReservationWithoutReturnLauncher() {
        registerTranslationHandler(packageName = "ru.yandex.translate", action = Intent.ACTION_SEND)
        val parent = translationMessage()
        var reservationsReleased = 0
        setContent(
            draft = { ComposerDraftState("Unsent draft", hydrated = true, revision = 17) },
            pages = flowOf(PagingData.from(listOf(parent))),
            activityResults = pickerOwner,
            onBeginExternalTranslation = { translationRequest(it) },
            onCompleteExternalTranslation = { error("SEND promised a returned result") },
            onAbortExternalTranslationLaunch = { ++reservationsReleased },
            onDraftChanged = { error("translation changed draft") },
            onSubmit = { error("translation sent") },
        )
        compose.runOnIdle { shadowOf(RuntimeEnvironment.getApplication()).clearNextStartedActivities() }
        openTranslationPicker()
        compose.onNodeWithText("Opens externally").assertIsDisplayed()
        compose.onNodeWithTag("message_translation_other").assertIsNotEnabled()
        compose.onNodeWithText("No text-processing apps installed.").assertIsDisplayed()
        compose.onNodeWithTag("message_translation_app_ru.yandex.translate").performClick()
        compose.waitUntil(5_000) { shadowOf(RuntimeEnvironment.getApplication()).peekNextStartedActivity() != null }
        compose.runOnIdle {
            val intent = requireNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("ru.yandex.translate", intent.`package`)
            assertEquals("text/plain", intent.type)
            assertEquals("Bonjour 👋\nSalut", intent.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals(setOf(Intent.EXTRA_TEXT), intent.extras!!.keySet())
            assertEquals(1, reservationsReleased)
            assertTrue(pickerLaunchedIntents.isEmpty())
        }
        compose.onNodeWithTag("message_translation_88").assertDoesNotExist()
        compose.onNodeWithTag("chat_message_88", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun externalTranslationOpeningAndCancelingPickerSharesNothingAndRequeriesInstalledApps() {
        val parent = translationMessage()
        setContent(
            draft = { ComposerDraftState("Unsent draft", hydrated = true, revision = 17) },
            pages = flowOf(PagingData.from(listOf(parent))),
            activityResults = pickerOwner,
            onBeginExternalTranslation = { error("canceling picker reserved a request") },
            onCompleteExternalTranslation = { error("canceling picker completed a request") },
            onAbortExternalTranslationLaunch = { error("canceling picker aborted a request") },
            onDraftChanged = { error("translation changed draft") },
            onSubmit = { error("translation sent") },
        )
        compose.runOnIdle { shadowOf(RuntimeEnvironment.getApplication()).clearNextStartedActivities() }
        openTranslationPicker()
        compose.onNodeWithText("No supported translation apps installed.").assertIsDisplayed()
        compose.onNodeWithTag("message_translation_other").assertIsNotEnabled()
        compose.onNodeWithTag("message_translation_cancel").performClick()
        compose.onNodeWithTag("message_translation_picker").assertDoesNotExist()
        compose.runOnIdle { registerTranslationHandler(label = "Newly installed translator") }
        openTranslationPicker()
        compose.onNodeWithText("Newly installed translator").assertIsDisplayed()
        compose.onNodeWithTag("message_translation_other").assertIsEnabled()
        compose.runOnIdle {
            assertTrue(pickerLaunchedIntents.isEmpty())
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).peekNextStartedActivity())
        }
        compose.onNodeWithTag("message_translation_cancel").performClick()
    }

    @Test
    fun externalTranslationServerMessageOffersPickerWithLabsOffAndNoLocalEntry() {
        val parent = translationMessage()
        setContent(
            draft = { ComposerDraftState("", hydrated = true, revision = 1) },
            pages = flowOf(PagingData.from(listOf(parent))),
            chatBuffer = { buffer.copy(type = BufferType.SERVER) },
            activityResults = pickerOwner,
            onSubmit = { error("translation sent") },
        )
        openTranslationPicker()
        compose.onNodeWithTag("message_translation_local").assertDoesNotExist()
        compose.onNodeWithTag("message_translation_other").assertIsDisplayed()
        compose.onNodeWithTag("message_translation_cancel").performClick()
        compose.runOnIdle { assertTrue(pickerLaunchedIntents.isEmpty()) }
    }

    @Test
    fun externalTranslationSingleSystemHoldOpensPickerDirectlyWithLabsOff() {
        val parent = translationMessage().copy(kind = MessageKind.JOIN, text = "alice joined")
        setContent(
            draft = { ComposerDraftState("", hydrated = true, revision = 1) },
            pages = flowOf(PagingData.from(listOf(parent))),
            activityResults = pickerOwner,
            onSubmit = { error("translation sent") },
        )
        compose.onNodeWithTag("chat_system_pill").performTouchInput { longClick() }
        compose.onNodeWithTag("message_translation_picker").assertIsDisplayed()
        compose.onNodeWithTag("message_action_translate").assertDoesNotExist()
        compose.onNodeWithTag("message_translation_cancel").performClick()
        compose.runOnIdle { assertTrue(pickerLaunchedIntents.isEmpty()) }
    }

    @Test
    fun externalTranslationUnavailableSourceShowsSourceSnackbar() = assertExternalTranslationRefusal(busy = false)

    @Test
    fun externalTranslationBusyRequestShowsBusySnackbar() = assertExternalTranslationRefusal(busy = true)

    private fun assertExternalTranslationRefusal(busy: Boolean) {
        registerTranslationHandler()
        val parent = translationMessage()
        setContent(
            draft = { ComposerDraftState("Unsent draft", hydrated = true, revision = 17) },
            pages = flowOf(PagingData.from(listOf(parent))),
            activityResults = pickerOwner,
            onBeginExternalTranslation = { null },
            onCompleteExternalTranslation = {},
            onAbortExternalTranslationLaunch = { error("unreserved request aborted") },
            externalTranslationInFlight = { busy },
            onDraftChanged = { error("translation changed draft") },
            onSubmit = { error("translation sent") },
        )
        openTranslationPicker()
        compose.onNodeWithTag("message_translation_app_com.deepl.mobiletranslator").performClick()
        compose
            .onNodeWithText(
                if (busy) "Finish the current translation first." else "This message is no longer available for translation.",
            ).assertIsDisplayed()
        compose.runOnIdle { assertTrue(pickerLaunchedIntents.isEmpty()) }
    }

    @Test
    fun externalTranslationMissingCallbacksCannotLaunchFromStatelessPreview() {
        registerTranslationHandler()
        setContent(
            draft = { ComposerDraftState("", hydrated = true, revision = 1) },
            pages = flowOf(PagingData.from(listOf(translationMessage()))),
            activityResults = pickerOwner,
            onSubmit = { error("translation sent") },
        )
        openTranslationPicker()
        compose.onNodeWithTag("message_translation_app_com.deepl.mobiletranslator").performClick()
        compose.onNodeWithText("This message is no longer available for translation.").assertIsDisplayed()
        compose.runOnIdle { assertTrue(pickerLaunchedIntents.isEmpty()) }
    }

    @Test
    fun externalTranslationUnavailableProcessHandlerNeverFallsBackToSend() {
        val process = registerTranslationHandler(activityName = "Process")
        registerTranslationHandler(action = Intent.ACTION_SEND, activityName = "Share")
        setContent(
            draft = { ComposerDraftState("Unsent draft", hydrated = true, revision = 17) },
            pages = flowOf(PagingData.from(listOf(translationMessage()))),
            activityResults = pickerOwner,
            onBeginExternalTranslation = { error("unavailable handler reserved a request") },
            onCompleteExternalTranslation = {},
            onAbortExternalTranslationLaunch = { error("unavailable handler aborted a request") },
            onDraftChanged = { error("translation changed draft") },
            onSubmit = { error("translation sent") },
        )
        compose.runOnIdle { shadowOf(RuntimeEnvironment.getApplication()).clearNextStartedActivities() }
        openTranslationPicker()
        compose.runOnIdle { shadowOf(RuntimeEnvironment.getApplication().packageManager).removeActivity(process) }
        compose.onNodeWithTag("message_translation_app_com.deepl.mobiletranslator").performClick()
        compose.onNodeWithText("Couldn’t open the translation app.").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(pickerLaunchedIntents.isEmpty())
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).peekNextStartedActivity())
        }
        compose.onNodeWithTag("chat_message_88", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun externalTranslationNativeLaunchExceptionsReleaseReservationsWithoutChangingSource() {
        registerTranslationHandler()
        var aborted = 0
        setContent(
            draft = { ComposerDraftState("Unsent draft", hydrated = true, revision = 17) },
            pages = flowOf(PagingData.from(listOf(translationMessage()))),
            activityResults = pickerOwner,
            onBeginExternalTranslation = { translationRequest(it) },
            onCompleteExternalTranslation = { error("failed launch returned a result") },
            onAbortExternalTranslationLaunch = { ++aborted },
            onDraftChanged = { error("translation changed draft") },
            onSubmit = { error("translation sent") },
        )
        for ((index, failure) in listOf(ActivityNotFoundException("Removed after query"), SecurityException("Permission changed")).withIndex()) {
            compose.runOnIdle { pickerLaunchFailure = failure }
            openTranslationPicker()
            compose.onNodeWithTag("message_translation_app_com.deepl.mobiletranslator").performClick()
            compose.onNodeWithText("Couldn’t open the translation app.").assertIsDisplayed()
            compose.runOnIdle { assertEquals(index + 1, aborted) }
        }
        compose.onNodeWithTag("chat_message_88", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun messageTranslationNeverMutatesDraftReplyOrHistory() {
        val draft = ComposerDraftState("Unsent draft", hydrated = true, revision = 17)
        val parent = MessageEntity(id = 88, bufferId = buffer.id, serverTime = 1, sender = "alice", kind = MessageKind.PRIVMSG, text = "The report is ready.", dedupKey = "parent")
        var ai by mutableStateOf<AiTextUiState>(AiTextUiState.Closed)
        val db =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MotdDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        val store = ComposerDraftStore(db)
        runBlocking {
            db.networkDao().insert(NetworkEntity(id = buffer.networkId, name = "test", role = NetworkRole.DIRECT, host = "irc.example", port = 6697, nick = "me", username = "me", realname = "Me"))
            db.bufferDao().insert(buffer)
            db.messageDao().insertAll(listOf(parent))
            store.saveDraft(buffer.id, draft.text, parent.id)
        }
        try {
            setContent(
                draft = { draft },
                replyTo = { parent },
                pages = flowOf(PagingData.from(listOf(parent))),
                onDraftChanged = { error("translation changed draft") },
                onSubmit = { error("translation sent") },
                onTranslateMessage = { ai = AiTextUiState.Choosing(AiTextSource.StoredMessage(it.bufferId, it.id, it.msgid, it.text)) },
                aiSheet = {
                    AiTextSheet(ai, emptyList(), null, onGenerate = {
                        val source = (ai as AiTextUiState.Choosing).source
                        ai = AiTextUiState.Result(1, source, parent.text, TextTransformResult("Le rapport est prêt.", TextTermination.EOG), 1)
                    }, onTargetSelected = {}, onDismiss = { ai = AiTextUiState.Closed }, onOpenSetup = {}, onManageStyles = {})
                },
            )
            compose
                .onNodeWithTag("chat_message_88", useUnmergedTree = true)
                .performTouchInput { longClick() }
            compose.onNodeWithTag("message_action_translate").performClick()
            compose.onNodeWithTag("message_translation_picker").assertIsDisplayed()
            compose.onNodeWithTag("message_translation_local").performClick()
            compose.onNodeWithTag("ai_text_source").assertIsDisplayed()
            compose.onNodeWithTag("ai_text_translate").performClick()
            compose.onNodeWithText("Le rapport est prêt.").assertIsDisplayed()
            compose.onNodeWithTag("ai_text_apply").assertDoesNotExist()
            compose.onNodeWithTag("ai_text_copy").performClick()
            compose.onNodeWithTag("ai_text_close").performClick()
            compose.runOnIdle {
                assertEquals("Unsent draft", draft.text)
                assertEquals(17L, draft.revision)
                val stored = runBlocking { store.loadDraft(buffer.id) }
                assertEquals(draft.text, stored?.text)
                assertEquals(parent.id, stored?.replyToEventId)
                assertEquals(parent.text, runBlocking { db.messageDao().byId(parent.id) }?.text)
            }
        } finally {
            db.close()
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-w411dp-h891dp")
    fun messageBubbleMoreUsesNativePickerForPendingMessageWithoutChangingComposer() {
        val draft = ComposerDraftState("Unsent draft", hydrated = true, revision = 17)
        val parent = translationMessage().copy(sender = "me", text = "Pending message", pendingLabel = "pending", msgid = null)
        val picked = mutableListOf<Pair<MessageEntity, String>>()
        setContent(
            draft = { draft },
            replyTo = { parent },
            pages = flowOf(PagingData.from(listOf(parent))),
            connectionState = IrcClientState.Ready("me", setOf("message-tags"), emptyMap()),
            onReact = { source, emoji -> picked += source to emoji },
            onSetReply = { error("reaction changed reply") },
            onDraftChanged = { error("reaction changed draft") },
            onSubmit = { error("reaction submitted the composer") },
        )
        holdMessageBubble(parent)
        compose.onNodeWithTag("message_more_reactions").performClick()
        compose.onNodeWithTag("message_action_sheet").assertDoesNotExist()
        compose.onNodeWithTag("message_reaction_picker").assertIsDisplayed()
        compose.tapNativeEmojiCategory("FOOD AND DRINK")
        compose.swipeNativeEmojiBody(upward = true)
        compose.swipeNativeEmojiBody(upward = false)
        compose.onNodeWithTag("message_reaction_picker").assertIsDisplayed()
        compose.runOnIdle { assertTrue("Bubble → More cell swipes never send/pick a reaction", picked.isEmpty()) }
        compose.onNodeWithTag("chat_composer_field").assertTextEquals(draft.text)
        compose.touchNativeEmojiView(compose.findNativeEmoji("ANIMALS AND NATURE", "🦑"))
        compose.onNodeWithTag("message_reaction_picker").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_field").assertTextEquals(draft.text)
        compose.onNodeWithText("Replying to me").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(parent to "🦑"), picked)
            assertNull(picked.single().first.msgid)
        }

        holdMessageBubble(parent)
        compose.onNodeWithTag("message_more_reactions").performClick()
        compose
            .onNode(
                androidx.compose.ui.test.SemanticsMatcher
                    .keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Dismiss),
                useUnmergedTree = true,
            ).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss) { it() }
        compose.onNodeWithTag("message_reaction_picker").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_field").assertTextEquals(draft.text)
        compose.onNodeWithText("Replying to me").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, picked.size)
            assertEquals(17L, draft.revision)
        }
    }

    @Test
    fun messageQuickReactionsUseExactOwnershipAndAddRemovePolicy() {
        val parent = translationMessage().copy(text = "React to this", msgid = "source-message")
        var connection by mutableStateOf<IrcClientState>(
            IrcClientState.Ready("me", setOf("message-tags"), mapOf("CLIENTTAGDENY" to "draft/react")),
        )
        val picked = mutableListOf<Pair<MessageEntity, String>>()
        setContent(
            draft = { ComposerDraftState("Unsent draft", hydrated = true) },
            pages = flowOf(PagingData.from(listOf(parent))),
            liveConnection = { connection },
            reactionChips = {
                listOf(
                    ReactionChip("👍🏽", 1, mine = true, reactorDisplayNames = listOf("me")),
                    ReactionChip("❤️", 1, mine = true, reactorDisplayNames = listOf("me")),
                )
            },
            onReact = { source, emoji -> picked += source to emoji },
            onDraftChanged = { error("reaction changed draft") },
            onSubmit = { error("reaction submitted the composer") },
        )
        holdMessageBubble(parent)
        val thumb = compose.onNode(hasText("👍") and hasAnyAncestor(hasTestTag("message_action_sheet")))
        val heart = compose.onNode(hasText("❤️") and hasAnyAncestor(hasTestTag("message_action_sheet")))
        thumb.assertIsNotEnabled()
        heart.assertIsEnabled()
        compose.runOnIdle {
            connection = IrcClientState.Ready("me", setOf("message-tags"), mapOf("CLIENTTAGDENY" to "draft/unreact"))
        }
        thumb.assertIsEnabled()
        heart.assertIsNotEnabled()
        compose.runOnIdle { connection = IrcClientState.Disconnected }
        thumb.assertIsNotEnabled()
        heart.assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue(picked.isEmpty())
            connection = IrcClientState.Ready("me", setOf("message-tags"), mapOf("CLIENTTAGDENY" to "draft/react"))
        }
        heart.assertIsEnabled().performClick()
        compose.onNodeWithTag("message_action_sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(parent to "❤️"), picked) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-w411dp-h891dp")
    fun messageReactionPickerDeniesAddButAllowsOwnedRemoval() {
        val parent = translationMessage().copy(text = "React to this", msgid = "source-message")
        val picked = mutableListOf<Pair<MessageEntity, String>>()
        setContent(
            draft = { ComposerDraftState("Unsent draft", hydrated = true) },
            pages = flowOf(PagingData.from(listOf(parent))),
            connectionState = IrcClientState.Ready("me", setOf("message-tags"), mapOf("CLIENTTAGDENY" to "draft/react")),
            reactionChips = {
                listOf(
                    ReactionChip("👍🏽", 1, mine = true, reactorDisplayNames = listOf("me")),
                    ReactionChip("❤️", 1, mine = true, reactorDisplayNames = listOf("me")),
                )
            },
            onReact = { source, emoji -> picked += source to emoji },
            onDraftChanged = { error("reaction changed draft") },
            onSubmit = { error("reaction submitted the composer") },
        )
        holdMessageBubble(parent)
        compose.onNode(hasText("👍") and hasAnyAncestor(hasTestTag("message_action_sheet"))).assertIsNotEnabled()
        compose.onNode(hasText("❤️") and hasAnyAncestor(hasTestTag("message_action_sheet"))).assertIsEnabled()
        compose.onNodeWithTag("message_more_reactions").performClick()
        compose.clickNativeEmoji("ANIMALS AND NATURE", "🦑")
        compose.onNodeWithTag("message_reaction_picker").assertIsDisplayed()
        compose.onNodeWithTag("message_reaction_unavailable").assertIsDisplayed()
        compose.runOnIdle { assertTrue(picked.isEmpty()) }
        compose.pickNativeEmojiVariant("SMILEYS AND EMOTIONS", "👍", "👍🏽")
        compose.onNodeWithTag("message_reaction_picker").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(parent to "👍🏽"), picked) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-w411dp-h891dp")
    fun messageReactionPickerDeniesRemoveAndUsesCurrentConnectionPolicy() {
        val parent = translationMessage().copy(text = "React to this", msgid = "source-message")
        var connection by mutableStateOf<IrcClientState>(
            IrcClientState.Ready("me", setOf("message-tags"), mapOf("CLIENTTAGDENY" to "draft/unreact")),
        )
        val picked = mutableListOf<Pair<MessageEntity, String>>()
        setContent(
            draft = { ComposerDraftState("Unsent draft", hydrated = true) },
            pages = flowOf(PagingData.from(listOf(parent))),
            liveConnection = { connection },
            reactionChips = { listOf(ReactionChip("🦑", 1, mine = true, reactorDisplayNames = listOf("me"))) },
            onReact = { source, emoji -> picked += source to emoji },
            onDraftChanged = { error("reaction changed draft") },
            onSubmit = { error("reaction submitted the composer") },
        )
        holdMessageBubble(parent)
        compose.onNodeWithTag("message_more_reactions").performClick()
        compose.clickNativeEmoji("ANIMALS AND NATURE", "🦑")
        compose.onNodeWithTag("message_reaction_picker").assertIsDisplayed()
        compose.onNodeWithTag("message_reaction_unavailable").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(picked.isEmpty())
            connection = IrcClientState.Disconnected
        }
        compose.clickNativeEmoji("ANIMALS AND NATURE", "🦑")
        compose.onNodeWithTag("message_reaction_picker").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(picked.isEmpty())
            connection = IrcClientState.Ready("me", setOf("message-tags"), mapOf("CLIENTTAGDENY" to "draft/react"))
        }
        compose.clickNativeEmoji("ANIMALS AND NATURE", "🦑")
        compose.onNodeWithTag("message_reaction_picker").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(parent to "🦑"), picked) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-w411dp-h891dp")
    fun composerAndReactionPickersShareNativeRecentAndStickyChoices() {
        val parent = translationMessage().copy(text = "React to this", msgid = "source-message")
        var draft by mutableStateOf(ComposerDraftState("", hydrated = true, revision = 1))
        val picked = mutableListOf<String>()
        setContent(
            draft = { draft },
            pages = flowOf(PagingData.from(listOf(parent))),
            connectionState = IrcClientState.Ready("me", setOf("message-tags"), emptyMap()),
            onReact = { source, emoji ->
                assertEquals(parent, source)
                picked += emoji
            },
            onDraftChanged = { draft = draft.copy(text = it, revision = draft.revision + 1) },
            onSubmit = { error("picking emoji submitted the composer") },
        )
        holdMessageBubble(parent)
        compose.onNodeWithTag("message_more_reactions").performClick()
        compose.pickNativeEmojiVariant("SMILEYS AND EMOTIONS", "👍", "👍🏽")
        compose.runOnIdle {
            assertEquals(listOf("👍🏽"), picked)
            assertEquals("", draft.text)
        }

        // This composer picker is newly opened, so AndroidX loads its own persisted selections.
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
        compose.clickNativeEmoji("RECENTLY USED", "👍🏽")
        compose.clickNativeEmoji("SMILEYS AND EMOTIONS", "👍🏽")
        compose.onNodeWithTag("chat_composer_field").assertTextEquals("👍🏽👍🏽")
        compose.onNodeWithTag("chat_composer_field").performTouchInput { click(center) }

        holdMessageBubble(parent)
        compose.onNodeWithTag("message_more_reactions").performClick()
        compose.clickNativeEmoji("RECENTLY USED", "👍🏽")
        compose.runOnIdle {
            assertEquals(listOf("👍🏽", "👍🏽"), picked)
            assertEquals("👍🏽👍🏽", draft.text)
        }
    }

    @Test
    fun aiPreviewApplyCancelAndSendKeepAuthoritativeDraft() {
        var draft by mutableStateOf(ComposerDraftState("I has a report.", hydrated = true, revision = 1))
        val parent = MessageEntity(id = 88, bufferId = buffer.id, serverTime = 1, sender = "alice", kind = MessageKind.PRIVMSG, text = "parent", dedupKey = "parent")
        var reply by mutableStateOf<MessageEntity?>(parent)
        var enabled by mutableStateOf(true)
        var connection by mutableStateOf<IrcClientState>(IrcClientState.Disconnected)
        var ai by mutableStateOf<AiTextUiState>(AiTextUiState.Closed)
        val sends = mutableListOf<String>()
        val db =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MotdDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        val store = ComposerDraftStore(db)
        runBlocking {
            db.networkDao().insert(NetworkEntity(id = buffer.networkId, name = "test", role = NetworkRole.DIRECT, host = "irc.example", port = 6697, nick = "me", username = "me", realname = "Me"))
            db.bufferDao().insert(buffer)
            db.messageDao().insertAll(listOf(parent))
            store.saveDraft(buffer.id, draft.text, parent.id)
        }
        try {
            setContent(
                draft = { draft },
                liveConnection = { connection },
                replyTo = { reply },
                aiEnabled = { enabled },
                aiState = { ai },
                onDraftChanged = {
                    draft = draft.copy(text = it, revision = draft.revision + 1)
                    runBlocking { store.saveDraft(buffer.id, draft.text, reply?.id) }
                },
                onAi = { ai = AiTextUiState.Choosing(AiTextSource.Composer(AiComposerDraftSnapshot(1, buffer.id, draft.revision, draft.text, reply?.id))) },
                aiSheet = {
                    AiTextSheet(
                        ai,
                        emptyList(),
                        null,
                        onGenerate = {
                            val source = (ai as AiTextUiState.Choosing).source
                            ai = AiTextUiState.Result(1, source, draft.text, TextTransformResult("I have a report.", TextTermination.EOG), 1)
                        },
                        onTargetSelected = {},
                        onDismiss = { ai = AiTextUiState.Closed },
                        onOpenSetup = {},
                        onManageStyles = {},
                        onApply = {
                            val result = ai as AiTextUiState.Result
                            draft = draft.copy(text = result.result.text, revision = draft.revision + 1)
                            runBlocking { store.saveDraft(buffer.id, draft.text, reply?.id) }
                            ai = AiTextUiState.Closed
                        },
                    )
                },
                onSubmit = {
                    sends += it
                    draft = draft.copy(text = "", revision = draft.revision + 1)
                    reply = null
                    runBlocking { store.saveDraft(buffer.id, "", null) }
                },
            )
            compose.onNodeWithTag("chat_composer_ai").assertIsDisplayed().performClick()
            compose.onNodeWithText("Grammar & spelling").performClick()
            compose.onNodeWithTag("ai_text_result").assertTextEquals("I have a report.")
            compose.runOnIdle {
                assertEquals("I has a report.", draft.text)
                assertEquals(parent, reply)
                assertEquals(emptyList<String>(), sends)
            }
            compose.runOnIdle {
                runBlocking {
                    assertEquals("I has a report.", store.loadDraft(buffer.id)?.text)
                    assertEquals(parent.id, store.loadDraft(buffer.id)?.replyToEventId)
                }
            }
            compose.onNodeWithTag("ai_text_close").performClick()
            compose.onNodeWithTag("chat_composer_field").assertTextEquals("I has a report.")
            compose.onNodeWithTag("chat_composer_ai").performClick()
            compose.onNodeWithText("Grammar & spelling").performClick()
            compose.runOnIdle {
                val result = ai as AiTextUiState.Result
                ai = result.copy(result = result.result.copy(termination = TextTermination.OUTPUT_LIMIT))
            }
            compose.onNodeWithTag("ai_text_apply").assertIsNotEnabled()
            compose.onNodeWithTag("ai_text_copy").assertIsNotEnabled()
            compose.runOnIdle { assertEquals("I has a report.", draft.text) }
            compose.runOnIdle {
                val result = ai as AiTextUiState.Result
                ai = result.copy(result = result.result.copy(termination = TextTermination.EOG))
            }
            compose.onNodeWithTag("ai_text_apply").performClick()
            compose.onNodeWithTag("chat_composer_field").assertTextEquals("I have a report.")
            compose.runOnIdle {
                assertEquals(parent, reply)
                assertEquals(2L, draft.revision)
            }
            compose.runOnIdle {
                runBlocking {
                    assertEquals("I have a report.", store.loadDraft(buffer.id)?.text)
                    assertEquals(parent.id, store.loadDraft(buffer.id)?.replyToEventId)
                }
            }
            compose.onNodeWithTag("chat_composer_field").performTextInput(" More")
            compose.runOnIdle { assertEquals("I have a report. More", draft.text) }
            compose.onNodeWithTag("chat_composer_ai").performClick()
            compose.runOnIdle { ai = AiTextUiState.Running(2, (ai as AiTextUiState.Choosing).source) }
            compose.onNodeWithTag("ai_text_apply").assertDoesNotExist()
            compose.runOnIdle {
                enabled = false
                ai = AiTextUiState.Closed
            }
            compose.onNodeWithTag("chat_composer_ai").assertDoesNotExist()
            compose.runOnIdle { connection = IrcClientState.Ready("me", emptySet(), emptyMap()) }
            compose.onNodeWithTag("chat_composer_send").performClick()
            compose.runOnIdle {
                assertEquals(listOf("I have a report. More"), sends)
                assertEquals("", draft.text)
                assertEquals(null, reply)
            }
            compose.runOnIdle { runBlocking { assertEquals("parent", db.messageDao().byId(parent.id)?.text) } }
        } finally {
            db.close()
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h480dp")
    fun longSourceAndSavedStylesKeepActionsAndCancelReachable() {
        val original = (1..200).joinToString("\n") { "Paragraph $it: please review the report before tomorrow." }
        val draft = ComposerDraftState(original, hydrated = true, revision = 12)
        val source = AiTextSource.Composer(AiComposerDraftSnapshot(7, buffer.id, draft.revision, original, 88))
        val styles = (1..20).map { AiCustomStyle("00000000-0000-4000-8000-${it.toString().padStart(12, '0')}", "Saved style", "Use a friendly tone.") }
        var ai by mutableStateOf<AiTextUiState>(AiTextUiState.Choosing(source))
        var target by mutableStateOf<AiTranslationTarget?>(null)
        var pendingTarget: AiTranslationTarget? = null
        val actions = mutableListOf<AiTextAction>()
        setContent(
            draft = { draft },
            aiEnabled = { true },
            aiState = { ai },
            onDraftChanged = { error("tools changed the original draft") },
            onSubmit = { error("tools sent the original draft") },
            aiSheet = {
                AiTextSheet(
                    ai,
                    styles,
                    target,
                    onGenerate = {
                        actions += it
                        ai = AiTextUiState.Running(actions.size.toLong(), checkNotNull(ai.source()))
                    },
                    onTargetSelected = {
                        pendingTarget = it
                        ai = AiTextUiState.Choosing(checkNotNull(ai.source()), isSavingTarget = true)
                    },
                    onDismiss = { ai = AiTextUiState.Closed },
                    onOpenSetup = {},
                    onManageStyles = {},
                    onApply = { error("no completed result") },
                )
            },
        )

        fun assertPreviewStartsAtTop(tag: String) {
            val root = compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("ai_text_sheet")), useUnmergedTree = true).getUnclippedBoundsInRoot()
            val preview = compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
            val footer = compose.onNodeWithTag("ai_text_close").getUnclippedBoundsInRoot()
            assertTrue("$tag must start inside the fresh viewport", preview.top >= root.top && preview.top < footer.top)
        }
        compose.onNodeWithTag("ai_text_correct").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("ai_text_source").assertTextEquals(original)
        // Duplicate names must still select the exact saved ID at every scroll position.
        styles.forEach { style ->
            compose
                .onNodeWithTag("ai_text_style_${style.id}")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            compose.onNodeWithTag("ai_text_close").assertIsDisplayed()
            compose
                .onNode(
                    SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate) and
                        hasAnyAncestor(hasTestTag("ai_text_sheet")),
                ).assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(style.id, actions.last().customStyleId)
                assertEquals(source, (ai as AiTextUiState.Running).source)
                ai = AiTextUiState.Choosing(source)
            }
        }
        compose.onNodeWithTag("ai_text_target").performScrollTo().assertHeightIsAtLeast(48.dp)
        val targetTop = compose.onNodeWithTag("ai_text_target").getUnclippedBoundsInRoot().top
        compose.onNodeWithTag("ai_text_target").performClick()
        compose.onNodeWithTag("ai_language_fr").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(styles.size, actions.size) // Picking a target must not start inference.
            assertEquals(AiTranslationTarget("fr", "French"), pendingTarget)
            assertEquals(source, (ai as AiTextUiState.Choosing).source)
        }
        assertEquals(
            targetTop.value,
            compose
                .onNodeWithTag("ai_text_target")
                .getUnclippedBoundsInRoot()
                .top
                .value,
            0.5f,
        )
        compose.onNodeWithTag("ai_text_translate").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("ai_text_target").assertIsNotEnabled()
        compose.onNodeWithTag("ai_text_manage_styles").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("ai_text_style_${styles.last().id}").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("ai_text_correct").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("ai_text_close").assertIsDisplayed()
        compose.runOnIdle {
            target = pendingTarget
            ai = AiTextUiState.Choosing(source)
        }
        compose
            .onNodeWithTag("ai_text_translate")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle {
            assertEquals(TextOperation.TRANSLATE, actions.last().operation)
            assertEquals(target, actions.last().translationTarget)
            assertEquals(source, (ai as AiTextUiState.Running).source)
        }
        compose
            .onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate) and
                    hasAnyAncestor(hasTestTag("ai_text_sheet")),
            ).assertIsDisplayed()
        compose.runOnIdle {
            ai = AiTextUiState.Result(21, source, original, TextTransformResult((1..120).joinToString("\n") { "Reviewed paragraph $it." }, TextTermination.EOG), 1)
        }
        assertPreviewStartsAtTop("ai_text_result")
        compose.onNodeWithTag("ai_text_source").performScrollTo()
        compose.runOnIdle {
            ai = AiTextUiState.Failed(source, AiRuntimeFailure.INVALID_OUTPUT)
        }
        assertPreviewStartsAtTop("ai_text_error")
        compose.onNodeWithTag("ai_text_manage_styles").assertDoesNotExist()
        compose.onNodeWithTag("ai_text_style_${styles.last().id}").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("ai_text_formal").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(TextOperation.FORMAL, actions.last().operation)
            assertEquals(source, (ai as AiTextUiState.Running).source)
            assertEquals(original, draft.text)
            assertEquals(12L, draft.revision)
        }
        compose.onNodeWithTag("ai_text_close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("ai_text_sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(original, draft.text) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h480dp")
    fun smallScreenResultFooterRestrictsIncompleteAndMessageActions() {
        val original = (1..80).joinToString("\n") { "Source paragraph $it." }
        val composerSource = AiTextSource.Composer(AiComposerDraftSnapshot(9, buffer.id, 1, original, null))
        val messageSource = AiTextSource.StoredMessage(buffer.id, 88, null, original)
        var ai by mutableStateOf<AiTextUiState>(
            AiTextUiState.Result(9, composerSource, original, TextTransformResult("A reviewed report.", TextTermination.EOG), 1),
        )
        var applied = false
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AiTextSheet(
                    ai,
                    emptyList(),
                    null,
                    onGenerate = { error("results cannot regenerate") },
                    onTargetSelected = {},
                    onDismiss = { ai = AiTextUiState.Closed },
                    onOpenSetup = {},
                    onManageStyles = {},
                    onApply = { applied = true },
                )
            }
        }
        compose.onNodeWithTag("ai_text_source").performScrollTo()
        compose.onNodeWithTag("ai_text_close").assertIsDisplayed()
        compose.onNodeWithTag("ai_text_apply").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("ai_text_copy").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle {
            val result = ai as AiTextUiState.Result
            ai = result.copy(result = result.result.copy(termination = TextTermination.OUTPUT_LIMIT))
        }
        compose.onNodeWithTag("ai_text_incomplete").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ai_text_apply").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("ai_text_copy").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(false, applied)
            ai = (ai as AiTextUiState.Result).copy(source = messageSource)
        }
        compose.onNodeWithTag("ai_text_apply").assertDoesNotExist()
        compose.onNodeWithTag("ai_text_copy").assertIsNotEnabled()
        compose.onNodeWithTag("ai_text_close").assertIsDisplayed()
        compose.runOnIdle {
            val result = ai as AiTextUiState.Result
            ai = result.copy(result = result.result.copy(termination = TextTermination.EOG))
        }
        compose.onNodeWithTag("ai_text_apply").assertDoesNotExist()
        compose.onNodeWithTag("ai_text_copy").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("ai_text_correct").assertDoesNotExist()
        compose.onNodeWithTag("ai_text_translate").assertDoesNotExist()
        compose.onNodeWithTag("ai_text_close").performClick()
        compose.runOnIdle { assertEquals(false, applied) }
    }

    private fun headerHistory(): List<MessageEntity> =
        (100L downTo 1L).map { id ->
            MessageEntity(
                id = id,
                bufferId = buffer.id,
                msgid = "header-$id",
                serverTime = id * 1_000,
                sender = "alice",
                kind = MessageKind.PRIVMSG,
                text = "History message $id",
                dedupKey = "header-$id",
                timelineOrder = id,
            )
        }

    @Test
    fun timelineDrawsBehindComposerAndKeepsNewestAboveHost() {
        var style by mutableStateOf(ComposerStyle.COMFORTABLE)
        var draft by mutableStateOf(ComposerDraftState("hello", hydrated = true, revision = 1))
        val submitted = mutableListOf<String>()
        val read = mutableListOf<TimelineAnchor>()
        val viewportAnchors = mutableListOf<ViewportRefreshAnchor?>()
        setContent(
            draft = { draft },
            composerStyle = { style },
            pages = flowOf(PagingData.from(headerHistory())),
            rawNewestAnchor = TimelineAnchor(100_000, 100, 100),
            onMarkRead = { read += it },
            onViewportRefreshAnchor = { viewportAnchors += it },
            onDraftChanged = { draft = draft.copy(text = it, revision = draft.revision + 1) },
            onSubmit = submitted::add,
        )
        val timeline = compose.onNodeWithTag("chat_timeline")

        for (selectedStyle in ComposerStyle.entries) {
            compose.runOnIdle {
                style = selectedStyle
                draft = draft.copy(text = "hello", revision = draft.revision + 1)
            }
            timeline.performScrollToIndex(0)
            compose.waitForIdle()
            val viewport = timeline.getUnclippedBoundsInRoot()
            val host = compose.onNodeWithTag("chat_bottom_host").getUnclippedBoundsInRoot()
            val pill = compose.onNodeWithTag("chat_composer_pill").getUnclippedBoundsInRoot()
            val newest = compose.onNodeWithTag("chat_message_header-100", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals(host.bottom.value, viewport.bottom.value, 0.5f)
            assertTrue("$selectedStyle: actual viewport $viewport must continue behind pill $pill", viewport.bottom > pill.top)
            assertTrue("$selectedStyle: newest $newest must rest above occupied host $host", newest.bottom <= host.top)
            assertEquals("$selectedStyle: retain the timeline's ordinary bottom breathing room", 8f, (host.top - newest.bottom).value, 1f)
            if (selectedStyle != ComposerStyle.LARGE) compose.onNodeWithTag("chat_composer_tools").performClick()
            val toolbar = compose.onNodeWithTag("chat_composer_format_toolbar").fetchSemanticsNode()
            assertTrue("$selectedStyle exposes one native scrolling tools row", toolbar.config.contains(SemanticsActions.ScrollBy))
            val toolsHost = compose.onNodeWithTag("chat_bottom_host").getUnclippedBoundsInRoot()
            val toolsNewest = compose.onNodeWithTag("chat_message_header-100", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("$selectedStyle: newest $toolsNewest must clear the measured tools host $toolsHost", toolsNewest.bottom <= toolsHost.top)
            assertEquals("$selectedStyle: tools retain ordinary bottom breathing room", 8f, (toolsHost.top - toolsNewest.bottom).value, 1f)
            if (selectedStyle != ComposerStyle.LARGE) {
                compose.onNodeWithTag("chat_composer_tools").performClick()
                compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
            }

            timeline.performScrollToIndex(50)
            compose.waitForIdle()
            val marksBeforeScroll = compose.runOnIdle { read.size }
            val crossingRow = compose.onNodeWithTag("chat_message_header-50", useUnmergedTree = true)
            val beforeRow = crossingRow.getUnclippedBoundsInRoot()
            val beforePill = compose.onNodeWithTag("chat_composer_pill").getUnclippedBoundsInRoot()
            // Center the real row under this style's pill, rather than assuming a toolbar height.
            val dragDp = ((beforePill.top.value + beforePill.bottom.value) - (beforeRow.top.value + beforeRow.bottom.value)) / 2f
            val touchSlopPx =
                android.view.ViewConfiguration
                    .get(RuntimeEnvironment.getApplication())
                    .scaledTouchSlop
                    .toFloat()
            timeline.performTouchInput {
                down(center)
                moveBy(Offset(0f, dragDp.dp.toPx() + touchSlopPx))
                advanceEventTime(200)
                up()
            }
            compose.waitForIdle()
            val crossed = crossingRow.getUnclippedBoundsInRoot()
            val floatingPill = compose.onNodeWithTag("chat_composer_pill").getUnclippedBoundsInRoot()
            val crossedViewport = timeline.getUnclippedBoundsInRoot()
            val crossedHost = compose.onNodeWithTag("chat_bottom_host").getUnclippedBoundsInRoot()
            val geometry = "style=$selectedStyle beforeRow=$beforeRow row=$crossed timeline=$crossedViewport pill=$floatingPill host=$crossedHost dragDp=$dragDp touchSlopPx=$touchSlopPx"
            assertTrue("An older row must scroll through the bar's actual bounds: $geometry", crossed.bottom > floatingPill.top && crossed.top < floatingPill.bottom)
            assertTrue("The crossed row must remain within the full timeline viewport: $geometry", crossed.top < crossedViewport.bottom)
            compose.runOnIdle { assertEquals("Scrolling behind the host must not mark history read", marksBeforeScroll, read.size) }
            val parked = compose.runOnIdle { viewportAnchors.last() as ViewportRefreshAnchor.Parked }
            val parkedRow = compose.onNodeWithTag("chat_message_header-${parked.id}", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val occupiedHost = compose.onNodeWithTag("chat_bottom_host").getUnclippedBoundsInRoot()
            assertTrue("Refresh/read viewport must name a row visible above the host", parkedRow.top < occupiedHost.top)
            for (tag in listOf("chat_scroll_to_bottom_fab", "chat_compact_actions", "chat_compact_back")) {
                val action = compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
                val occupied = compose.onNodeWithTag("chat_bottom_host").getUnclippedBoundsInRoot()
                assertTrue("$tag must clear the occupied bottom host", action.bottom < occupied.top)
            }
            // Physical hit testing, not the semantics click action: the composer wins over the row below.
            compose.onNodeWithTag("chat_composer_send").assertIsEnabled().performTouchInput { click(center) }
            compose.runOnIdle { assertEquals("hello", submitted.last()) }
            compose
                .onNodeWithTag("chat_composer_field")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        }
        compose.runOnIdle { assertEquals(3, submitted.size) }
    }

    @Test
    fun nativeBottomPaddingTracksEachMeasuredPaneAndHostChange() {
        val paneHeight = mutableStateOf(800.dp)
        val hostHeight = mutableStateOf(368.dp)
        var pixelsPerDp = 0f
        lateinit var timeline: LazyListState
        compose.setContent {
            val density = LocalDensity.current
            pixelsPerDp = density.density
            val items = remember { flowOf(PagingData.from(headerHistory())) }.collectAsLazyPagingItems()
            val listState = rememberLazyListState()
            SideEffect { timeline = listState }
            MotdTheme(dynamicColor = false) {
                Scaffold(
                    modifier = Modifier.height(paneHeight.value).fillMaxWidth(),
                    contentWindowInsets = WindowInsets(0),
                    bottomBar = { Box(Modifier.height(hostHeight.value).fillMaxWidth()) },
                ) { padding ->
                    MessageList(
                        items = items,
                        listState = listState,
                        networkId = buffer.networkId,
                        readMarkerTime = null,
                        bottomContentPadding = padding,
                        onLongPress = {},
                        onReply = {},
                        onReact = { _, _ -> },
                        onImageClick = {},
                        onRetry = {},
                        loadPreview = { _, _ -> null },
                        richContentReady = false,
                        showImages = false,
                        showLinkPreviews = false,
                        onOpenLink = {},
                        modifier =
                            Modifier.drawWithContent {
                                val layout = listState.layoutInfo
                                val expectedPane = with(density) { paneHeight.value.roundToPx() }
                                val expectedPadding = with(density) { (hostHeight.value + 8.dp).roundToPx() }
                                // Native layout may place a cached child before remeasuring it in the
                                // same traversal; only the final geometry reaching paint is user-visible.
                                assertEquals("The viewport must retain the entire measured pane", expectedPane, layout.viewportSize.height)
                                assertEquals("The list must use this measure's host height, not the previous frame", expectedPadding, layout.beforeContentPadding)
                                drawContent()
                            },
                    )
                }
            }
        }
        compose.waitForIdle()
        val restingNewestFoot = compose.runOnIdle { timeline.layoutInfo.viewportSize.height - timeline.layoutInfo.beforeContentPadding }
        // Complementary keyboard/picker travel must keep the newest foot still on every paint.
        for ((pane, host) in listOf(780 to 348, 740 to 308, 700 to 268, 740 to 308, 780 to 348, 800 to 368)) {
            compose.runOnIdle {
                paneHeight.value = pane.dp
                hostHeight.value = host.dp
            }
            // Native placement requires Android traversal, not only advancing the Compose clock.
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(Math.round(pane * pixelsPerDp), timeline.layoutInfo.viewportSize.height)
                assertEquals(Math.round((host + 8) * pixelsPerDp), timeline.layoutInfo.beforeContentPadding)
                assertEquals(restingNewestFoot, timeline.layoutInfo.viewportSize.height - timeline.layoutInfo.beforeContentPadding)
            }
        }
        // A multiline clear changes only host height; the same native getter must still remeasure.
        compose.runOnIdle { hostHeight.value = 148.dp }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Math.round(156 * pixelsPerDp), timeline.layoutInfo.beforeContentPadding)
        }
    }

    @Test
    fun measuredBottomHostTracksDraftReplyToolsPickerAndLowerPanels() {
        var draft by mutableStateOf(ComposerDraftState(hydrated = true, revision = 1))
        var reply by mutableStateOf<MessageEntity?>(null)
        var ebooksRoom by mutableStateOf<Long?>(null)
        var voice by mutableStateOf(VoiceMessageUiState())
        var contentHeight by mutableStateOf<Dp?>(null)
        setContent(
            draft = { draft },
            pages = flowOf(PagingData.from(headerHistory())),
            chatBuffer = { ebooksBuffer },
            replyTo = { reply },
            parted = true,
            ebooksHelperRoomId = { ebooksRoom },
            voiceState = { voice },
            contentHeight = { contentHeight },
            memberNicks = listOf("alice", "alex"),
            onDraftChanged = { draft = draft.copy(text = it, revision = draft.revision + 1) },
            onSubmit = {},
        )
        val timeline = compose.onNodeWithTag("chat_timeline")

        fun assertNewestAboveHost(): Float {
            timeline.performScrollToIndex(0)
            compose.waitForIdle()
            val viewport = timeline.getUnclippedBoundsInRoot()
            val host = compose.onNodeWithTag("chat_bottom_host").getUnclippedBoundsInRoot()
            val newest = compose.onNodeWithTag("chat_message_header-100", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals(host.bottom.value, viewport.bottom.value, 0.5f)
            assertTrue("Measured content padding must clear every occupied lower panel", newest.bottom <= host.top)
            return (host.bottom - host.top).value
        }

        fun assertPanelCleared(tag: String) {
            val panel = compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
            val host = compose.onNodeWithTag("chat_bottom_host").getUnclippedBoundsInRoot()
            val newest = compose.onNodeWithTag("chat_message_header-100", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("$tag must be included in the measured bottom host", panel.top >= host.top && panel.bottom <= host.bottom)
            assertTrue("$tag must not cover the resting newest message", newest.bottom <= host.top)
        }
        val collapsed = assertNewestAboveHost()
        assertPanelCleared("chat_parted_banner")
        compose.runOnIdle { draft = draft.copy(text = "first\nsecond\nthird", revision = draft.revision + 1) }
        val multiline = assertNewestAboveHost()
        assertTrue("A multiline draft must increase the occupied host", multiline > collapsed)
        compose.runOnIdle { reply = headerHistory().first().copy(text = "Reply context") }
        val withReply = assertNewestAboveHost()
        assertTrue("The reply banner must increase the occupied host", withReply > multiline)
        compose.onNodeWithTag("chat_composer_tools").performClick()
        val withTools = assertNewestAboveHost()
        assertTrue("Visible formatting tools must increase the occupied host", withTools > withReply)
        val expandedPill = compose.onNodeWithTag("chat_composer_pill").assertIsDisplayed().getUnclippedBoundsInRoot()
        val tools = compose.onNodeWithTag("chat_composer_format_toolbar").getUnclippedBoundsInRoot()
        val newest = compose.onNodeWithTag("chat_message_header-100", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("The pill must grow around the tools", tools.top >= expandedPill.top && tools.bottom <= expandedPill.bottom)
        assertTrue("The newest row must clear the expanded shared pill", newest.bottom <= expandedPill.top)
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
        val withPicker = assertNewestAboveHost()
        assertTrue("The picker replacement must increase the occupied host", withPicker > withReply)
        val row = compose.onNodeWithTag("chat_composer_input_row").getUnclippedBoundsInRoot()
        val picker = compose.onNodeWithTag("chat_composer_emoji_panel").getUnclippedBoundsInRoot()
        assertEquals((row.bottom + 6.dp).value, picker.top.value, 0.5f)
        compose.onNodeWithTag("chat_composer_field").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(withReply, assertNewestAboveHost(), 1f)
        compose.onNodeWithTag("chat_composer_field").assertTextEquals("first\nsecond\nthird")
        compose.runOnIdle { ebooksRoom = buffer.id }
        val withEbooks = assertNewestAboveHost()
        assertPanelCleared("chat_ebooks_help")
        assertPanelCleared("chat_ebooks_open_results")
        assertPanelCleared("chat_ebooks_dcc_offers")
        assertTrue("Eligible ebooks controls must increase the occupied host", withEbooks > withReply)
        compose.runOnIdle { voice = VoiceMessageUiState(recording = VoiceRecordingUi(elapsedMs = 1_000, locked = true)) }
        assertTrue("The recording panel must increase the occupied host", assertNewestAboveHost() > withEbooks)
        assertPanelCleared("voice_recording_panel")
        compose.runOnIdle {
            voice = VoiceMessageUiState()
            ebooksRoom = null
            reply = null
            draft = draft.copy(text = "al", revision = draft.revision + 1)
        }
        // Autocomplete is a Popup above the host, not part of its measured content inset.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("autocomplete_panel").fetchSemanticsNodes().isNotEmpty() }
        val hostBeforePopup = assertNewestAboveHost()
        val popup = compose.onNodeWithTag("autocomplete_panel").getUnclippedBoundsInRoot()
        val inputRow = compose.onNodeWithTag("chat_composer_input_row").getUnclippedBoundsInRoot()
        assertTrue(popup.bottom <= inputRow.top)
        assertEquals(collapsed, hostBeforePopup, 1f)
        // This is the same available-height change that consumed IME insets impose on ChatContent.
        compose.runOnIdle { contentHeight = 580.dp }
        assertEquals(collapsed, assertNewestAboveHost(), 1f)
        compose.onNodeWithTag("chat_composer_field").assertTextEquals("al")
    }

    @Test
    fun unreadEntryUsesFinalHistoryViewportBeforeTargetClears() {
        assertUnreadEntryUsesFinalViewport(firstUnreadIndex = 50, clampedAtBottom = false)
    }

    @Test
    fun shallowUnreadEntryUsesExpandedViewportBeforeTargetClears() {
        assertUnreadEntryUsesFinalViewport(firstUnreadIndex = 1, clampedAtBottom = true)
    }

    private fun assertUnreadEntryUsesFinalViewport(
        firstUnreadIndex: Int,
        clampedAtBottom: Boolean,
    ) {
        val history = headerHistory()
        val firstUnread = history[firstUnreadIndex]
        val marker = history[firstUnreadIndex + 1]
        var target by mutableStateOf<ChatPositionTarget?>(null)
        var entry by mutableStateOf<EntryPositionState>(EntryPositionState.Pending)
        var positionsHandled = 0
        setContent(
            draft = { ComposerDraftState(hydrated = true) },
            // Static PagingData without source states displays rows but leaves refresh Loading.
            pages =
                flowOf(
                    PagingData.from(
                        history,
                        sourceLoadStates =
                            LoadStates(
                                refresh = LoadState.NotLoading(endOfPaginationReached = false),
                                prepend = LoadState.NotLoading(endOfPaginationReached = true),
                                append = LoadState.NotLoading(endOfPaginationReached = true),
                            ),
                    ),
                ),
            initialTarget = { target },
            entryState = { entry },
            unreadEntrySnapshot =
                UnreadEntrySnapshot(
                    marker = TimelineAnchor(marker.serverTime, marker.id, marker.timelineOrder),
                    loadedCount = firstUnreadIndex + 1,
                    lowerBound = false,
                ),
            // Keep the target until the test observes the completed placement, then consume it
            // together with Settled as the ViewModel does.
            onInitialPositionHandled = { positionsHandled++ },
            onSubmit = {},
        )
        compose.onNodeWithTag("chat_message_header-100", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(CHAT_ENTRY_VEIL_TAG).assertExists()
        val bar = compose.onNodeWithTag("chat_top_app_bar", useUnmergedTree = true)
        val expandedHeight = bar.fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle {
            target =
                ChatPositionTarget(
                    index = firstUnreadIndex,
                    expectedEventId = firstUnread.id,
                    expectedMsgid = firstUnread.msgid,
                    serverTime = firstUnread.serverTime,
                    forceScrollOnEntry = true,
                    placeAtTop = true,
                )
        }
        // Robolectric's paused main looper is drained by waitForIdle, not waitUntil.
        compose.waitForIdle()
        compose.waitUntil(5_000) { positionsHandled == 1 }
        compose.waitForIdle()
        val timeline = compose.onNodeWithTag("chat_timeline")
        val divider = compose.onNodeWithTag("chat_read_marker_divider", useUnmergedTree = true)
        val first = compose.onNodeWithTag("chat_message_${firstUnread.msgid}", useUnmergedTree = true)
        val second = compose.onNodeWithTag("chat_message_${history[firstUnreadIndex - 1].msgid}", useUnmergedTree = true)
        divider.assertIsDisplayed()
        first.assertIsDisplayed()
        second.assertIsDisplayed()
        val placedHeight = bar.fetchSemanticsNode().boundsInRoot.height
        val firstTop = first.fetchSemanticsNode().boundsInRoot.top
        val secondTop = second.fetchSemanticsNode().boundsInRoot.top
        val viewportTop = timeline.fetchSemanticsNode().boundsInRoot.top
        val dividerTop = divider.fetchSemanticsNode().boundsInRoot.top
        if (clampedAtBottom) {
            assertEquals("A shallow positive target must settle with expanded chrome", expandedHeight, placedHeight, 1f)
            assertTrue("The short unread run must remain clamped below the viewport top", dividerTop > viewportTop + 1f)
            compose.onNodeWithTag("chat_scroll_to_bottom_fab").assertDoesNotExist()
        } else {
            assertTrue("Unread placement must already use history chrome", placedHeight < expandedHeight)
            assertEquals("The unread boundary must top the final viewport", viewportTop, dividerTop, 1f)
        }
        assertTrue(firstTop >= divider.fetchSemanticsNode().boundsInRoot.bottom)
        assertTrue(firstTop < secondTop)

        fun assertPlacementUnchanged() {
            first.assertIsDisplayed()
            second.assertIsDisplayed()
            assertEquals("Clearing the entry target must not resize the header", placedHeight, bar.fetchSemanticsNode().boundsInRoot.height, 1f)
            assertEquals("Settlement must not move the final viewport", viewportTop, timeline.fetchSemanticsNode().boundsInRoot.top, 1f)
            assertEquals("Settlement must not move the unread boundary", dividerTop, divider.fetchSemanticsNode().boundsInRoot.top, 1f)
            assertEquals("Settlement must not displace the first unread row", firstTop, first.fetchSemanticsNode().boundsInRoot.top, 1f)
            assertEquals("Settlement must not displace the next canonical row", secondTop, second.fetchSemanticsNode().boundsInRoot.top, 1f)
        }

        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            target = null
            entry = EntryPositionState.Settled
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertPlacementUnchanged()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        assertPlacementUnchanged()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithTag(CHAT_ENTRY_VEIL_TAG).assertDoesNotExist()
        assertPlacementUnchanged()
        compose.runOnIdle {
            assertEquals(1, positionsHandled)
            assertEquals(null, target)
            assertEquals(EntryPositionState.Settled, entry)
        }
    }

    @Test
    fun nativeBuiltinWallpaperStaysFixedWhileHistoryHeaderResizes() {
        assertWallpaperAnchoredDuringHeaderResize(WallpaperSelection(ChatWallpaperPreset.MOTD, 100))
    }

    @Test
    fun nativeCroppedImageWallpaperStaysFixedWhileHistoryHeaderResizes() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.filesDir, CustomWallpaperStore.DIRECTORY).apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.image")
        val bitmap = Bitmap.createBitmap(128, 256, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, y, android.graphics.Color.rgb(x * 255 / 127, y, 255 - y))
            }
        }
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        val originalLoader = Coil.imageLoader(context)
        val loader = ImageLoader.Builder(context).allowHardware(false).build()
        Coil.setImageLoader(loader)
        try {
            assertWallpaperAnchoredDuringHeaderResize(WallpaperSelection(ChatWallpaperPreset.NONE, 100, file.name))
        } finally {
            Coil.setImageLoader(originalLoader)
            loader.shutdown()
            file.delete()
        }
    }

    private fun assertWallpaperAnchoredDuringHeaderResize(wallpaper: WallpaperSelection) {
        setContent(
            draft = { ComposerDraftState(hydrated = true) },
            pages = flowOf(PagingData.from(headerHistory())),
            chatWallpaper = wallpaper,
            onSubmit = {},
        )
        val root = compose.onNodeWithTag("chat_surface_root")
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("chat_top_app_bar", useUnmergedTree = true)
        val timeline = compose.onNodeWithTag("chat_timeline")

        fun headerHeight(): Float {
            val bounds = bar.getUnclippedBoundsInRoot()
            return (bounds.bottom - bounds.top).value
        }

        fun wallpaperPixels(): IntArray {
            assertEquals("The screen capture origin must not follow the header", rootBounds, root.fetchSemanticsNode().boundsInRoot)
            val screen = root.captureToImage().asAndroidBitmap()
            // Short fixture bubbles stay left; this fixed screen patch avoids chrome and messages.
            val width = screen.width / 8
            val height = screen.height / 4
            return IntArray(width * height).also {
                screen.getPixels(it, 0, width, screen.width * 3 / 4, screen.height / 4, width, height)
            }
        }

        // Wait for the real asynchronous tile/image, not a flat loading background or gradient.
        compose.waitUntil(10_000) {
            val pixels = wallpaperPixels()
            pixels.maxOf { it and 255 } - pixels.minOf { it and 255 } > 32
        }
        val expandedPixels = wallpaperPixels()
        val expandedHeight = headerHeight()
        compose.mainClock.autoAdvance = false
        timeline.performScrollToIndex(50)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val intermediateHeight = headerHeight()
        assertTrue("The real titlebar must be partway through collapse", intermediateHeight < expandedHeight - 1f && intermediateHeight > expandedHeight - 27f)
        assertArrayEquals("Wallpaper moved during titlebar collapse", expandedPixels, wallpaperPixels())
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val collapsedHeight = headerHeight()
        assertEquals("The real titlebar must finish collapsing", 28f, expandedHeight - collapsedHeight, 1f)
        assertArrayEquals("Wallpaper moved with the collapsed titlebar", expandedPixels, wallpaperPixels())
        timeline.performScrollToIndex(0)
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val expandingHeight = headerHeight()
        assertTrue("Returning to latest must resize the real titlebar", expandingHeight > collapsedHeight + 1f && expandingHeight < expandedHeight - 1f)
        assertArrayEquals("Wallpaper moved during titlebar reexpansion", expandedPixels, wallpaperPixels())
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals("Latest must restore the expanded titlebar", expandedHeight, headerHeight(), 0.5f)
        assertArrayEquals("Wallpaper moved after titlebar reexpansion", expandedPixels, wallpaperPixels())
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun historyHeaderAnimatesPreservesItsAnchorAndRestoresOnlyAtLatest() {
        val history = headerHistory()
        val pages = MutableStateFlow(PagingData.from(history))
        var connection by mutableStateOf<IrcClientState>(IrcClientState.Ready("me", emptySet(), emptyMap()))
        var backs = 0
        var details = 0
        var searches = 0
        var invites = 0
        setContent(
            liveConnection = { connection },
            draft = { ComposerDraftState(hydrated = true) },
            pages = pages,
            memberCount = 42,
            onBack = { backs++ },
            onOpenChannelInfo = {
                assertEquals(buffer.id, it)
                details++
            },
            onOpenSearch = {
                assertEquals(buffer.id, it)
                searches++
            },
            onInviteUser = { invites++ },
            onSubmit = {},
        )
        val bar = compose.onNodeWithTag("chat_top_app_bar", useUnmergedTree = true)

        fun headerHeight(): Float {
            val bounds = bar.getUnclippedBoundsInRoot()
            return (bounds.bottom - bounds.top).value
        }
        val title = compose.onNodeWithTag("chat_title")
        val timeline = compose.onNodeWithTag("chat_timeline")
        val resources = RuntimeEnvironment.getApplication().resources
        val back = compose.onNodeWithContentDescription(resources.getString(R.string.chat_back))
        val search = compose.onNodeWithContentDescription(resources.getString(R.string.chat_search))
        val expandedHeight = headerHeight()
        title.assertHeightIsAtLeast(48.dp)
        back.assertHeightIsAtLeast(48.dp).performClick()
        search.assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("chat_overflow").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("chat_compact_actions").assertDoesNotExist()
        compose.onNodeWithTag("chat_compact_back").assertDoesNotExist()
        compose.onNodeWithText("#kotlin", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("42 members", useUnmergedTree = true).assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        timeline.performScrollToIndex(50)
        compose.waitForIdle()
        val anchor = compose.onNodeWithTag("chat_message_header-50", useUnmergedTree = true)
        anchor.assertIsDisplayed()
        val anchorBottom = anchor.getUnclippedBoundsInRoot().bottom.value
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val intermediateHeight = headerHeight()
        assertTrue("The actual bar must have an intermediate layout height", intermediateHeight < expandedHeight - 1f && intermediateHeight > expandedHeight - 27f)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val compactHeight = headerHeight()
        assertEquals(28f, expandedHeight - compactHeight, 1f)
        assertEquals("Header resizing moved the canonical history row", anchorBottom, anchor.getUnclippedBoundsInRoot().bottom.value, 0.5f)
        compose.mainClock.autoAdvance = true
        title.assertHeightIsEqualTo(36.dp).assertHasNoClickAction()
        val avatarBounds = compose.onNodeWithTag("chat_header_avatar", useUnmergedTree = true).assertHeightIsEqualTo(20.dp).getUnclippedBoundsInRoot()
        assertEquals(
            "The compact avatar must be centered below the status-bar inset",
            bar.getUnclippedBoundsInRoot().bottom.value - 18f,
            (avatarBounds.top.value + avatarBounds.bottom.value) / 2f,
            0.5f,
        )
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("chat_top_app_bar")), useUnmergedTree = true).assertCountEquals(0)
        back.assertIsDisplayed().assertTouchHeightIsEqualTo(56.dp).assertTouchWidthIsEqualTo(56.dp)
        search.assertDoesNotExist()
        compose.onNodeWithTag("chat_overflow").assertDoesNotExist()
        val actions = compose.onNodeWithTag("chat_compact_actions")
        actions.assertIsDisplayed().assertTouchHeightIsEqualTo(56.dp).assertTouchWidthIsEqualTo(56.dp)
        val compactBack = compose.onNodeWithTag("chat_compact_back")
        compactBack
            .assertIsDisplayed()
            .assertTouchHeightIsEqualTo(56.dp)
            .assertTouchWidthIsEqualTo(56.dp)
            .performClick()
        compose.onNodeWithTag("chat_overflow_menu").assertDoesNotExist()
        val actionsBounds = actions.getUnclippedBoundsInRoot()
        val backBounds = compactBack.getUnclippedBoundsInRoot()
        val latestBounds =
            compose
                .onNodeWithTag("chat_scroll_to_bottom_fab")
                .assertTouchHeightIsEqualTo(56.dp)
                .assertTouchWidthIsEqualTo(56.dp)
                .getUnclippedBoundsInRoot()
        assertEquals("Conversation actions must align with latest on the right", latestBounds.right.value, actionsBounds.right.value, 0.5f)
        assertEquals("Back must align with latest on the right", latestBounds.right.value, backBounds.right.value, 0.5f)
        assertEquals("Conversation actions must sit above Back with a gap", 8f, (backBounds.top - actionsBounds.bottom).value, 0.5f)
        assertEquals("Back must sit above latest with a gap", 8f, (latestBounds.top - backBounds.bottom).value, 0.5f)
        actions.performClick()
        compose.onAllNodesWithTag("chat_overflow_menu").assertCountEquals(1)
        compose.onNodeWithText(resources.getString(R.string.chat_back)).assertDoesNotExist()
        compose.onNodeWithTag("chat_compact_details").assertHeightIsAtLeast(48.dp).performClick()
        actions.performClick()
        compose.onNodeWithTag("chat_compact_search").assertHeightIsAtLeast(48.dp).performClick()
        actions.performClick()
        compose.onNodeWithTag("chat_invite_user").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(2, backs)
            assertEquals(1, details)
            assertEquals(1, searches)
            assertEquals(1, invites)
        }

        // A real drag retires auto-follow before the live presentation; no test-only follow state.
        timeline.performTouchInput {
            down(center)
            moveBy(Offset(0f, 80f))
            advanceEventTime(200)
            up()
        }
        compose.waitForIdle()
        val parkedRow = compose.onNodeWithTag("chat_message_header-45", useUnmergedTree = true)
        parkedRow.assertIsDisplayed()
        val parkedBottom = parkedRow.getUnclippedBoundsInRoot().bottom.value
        compose.runOnIdle {
            pages.value =
                PagingData.from(
                    listOf(history.first().copy(id = 101, msgid = "header-101", serverTime = 101_000, dedupKey = "header-101", timelineOrder = 101)) + history,
                )
        }
        compose.waitForIdle()
        parkedRow.assertIsDisplayed()
        assertEquals("Live arrival moved the parked canonical row", parkedBottom, parkedRow.getUnclippedBoundsInRoot().bottom.value, 0.5f)
        assertEquals("A live arrival must not expand parked history", compactHeight, headerHeight(), 0.5f)
        compose.runOnIdle { connection = IrcClientState.Failed("SASL authentication failed", fatal = true) }
        compose.onNodeWithText("SASL authentication failed", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(compactHeight, headerHeight(), 0.5f)

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("chat_scroll_to_bottom_fab").performClick()
        compose.waitForIdle()
        assertEquals("Arming follow must not expand before reaching latest", compactHeight, headerHeight(), 0.5f)
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        compose.onNodeWithTag("chat_message_header-101", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chat_scroll_to_bottom_fab").assertDoesNotExist()
        assertEquals(expandedHeight, headerHeight(), 0.5f)
        actions.assertDoesNotExist()
        compactBack.assertDoesNotExist()
        title.assertHeightIsAtLeast(48.dp)
        back.assertHeightIsAtLeast(48.dp)
        search.assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("chat_overflow").assertHeightIsAtLeast(48.dp)
        compose.mainClock.autoAdvance = true
        timeline.performScrollToIndex(50)
        assertEquals(compactHeight, headerHeight(), 0.5f)
        compose.mainClock.autoAdvance = false
        timeline.performScrollToIndex(0)
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val expandingHeight = headerHeight()
        assertTrue("Returning to latest must animate the actual bar", expandingHeight > compactHeight + 1f && expandingHeight < expandedHeight - 1f)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals("Manual return must restore the same expanded bar", expandedHeight, headerHeight(), 0.5f)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun compactHeaderGrowsForLargeTextAndKeepsFloatingActionsAccessible() {
        val longTitle = "#a-long-conversation-title-that-must-remain-readable-to-accessibility"
        setContent(
            draft = { ComposerDraftState(hydrated = true) },
            pages = flowOf(PagingData.from(headerHistory())),
            chatBuffer = { buffer.copy(displayName = longTitle) },
            connectionState = IrcClientState.Failed("SASL authentication failed", fatal = true),
            fontScale = 2f,
            onSubmit = {},
        )
        compose.onNodeWithTag("chat_timeline").performScrollToIndex(50)
        compose.onNodeWithTag("chat_scroll_to_bottom_fab").assertIsDisplayed()
        val bar = compose.onNodeWithTag("chat_top_app_bar", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val title = compose.onNodeWithTag("chat_title").assertHeightIsAtLeast(64.dp).getUnclippedBoundsInRoot()
        assertTrue("Accessibility text must grow the bar instead of being clipped", title.top >= bar.top && title.bottom <= bar.bottom)
        val avatarBounds = compose.onNodeWithTag("chat_header_avatar", useUnmergedTree = true).assertHeightIsEqualTo(20.dp).getUnclippedBoundsInRoot()
        assertEquals(
            "The avatar must remain centered when accessibility text grows the content bar",
            bar.bottom.value - (title.bottom - title.top).value / 2f,
            (avatarBounds.top.value + avatarBounds.bottom.value) / 2f,
            0.5f,
        )
        val glyphBounds = compose.onNodeWithText("#", useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("The glyph must not inherit the header's oversized line height", glyphBounds.bottom - glyphBounds.top < avatarBounds.bottom - avatarBounds.top)
        assertEquals("The glyph must be centered in its tile", (avatarBounds.top.value + avatarBounds.bottom.value) / 2f, (glyphBounds.top.value + glyphBounds.bottom.value) / 2f, 0.5f)
        compose.onNodeWithText(longTitle, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("SASL authentication failed", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("chat_top_app_bar")), useUnmergedTree = true).assertCountEquals(0)
        val resources = RuntimeEnvironment.getApplication().resources
        val actions =
            compose
                .onNodeWithTag("chat_compact_actions")
                .assertIsDisplayed()
                .assertTouchHeightIsEqualTo(56.dp)
                .assertTouchWidthIsEqualTo(56.dp)
        compose.onNodeWithContentDescription(resources.getString(R.string.action_more)).assertIsDisplayed()
        val compactBack =
            compose
                .onNodeWithTag("chat_compact_back")
                .assertIsDisplayed()
                .assertTouchHeightIsEqualTo(56.dp)
                .assertTouchWidthIsEqualTo(56.dp)
        compose.onNodeWithContentDescription(resources.getString(R.string.chat_back)).assertIsDisplayed()
        val actionsBounds = actions.getUnclippedBoundsInRoot()
        val backBounds = compactBack.getUnclippedBoundsInRoot()
        val latestBounds =
            compose
                .onNodeWithTag("chat_scroll_to_bottom_fab")
                .assertTouchHeightIsEqualTo(56.dp)
                .assertTouchWidthIsEqualTo(56.dp)
                .getUnclippedBoundsInRoot()
        val composerBounds = compose.onNodeWithTag("chat_composer_field").getUnclippedBoundsInRoot()
        assertEquals("Conversation actions must align with latest on the right", latestBounds.right.value, actionsBounds.right.value, 0.5f)
        assertEquals("Back must align with latest on the right", latestBounds.right.value, backBounds.right.value, 0.5f)
        assertEquals("Conversation actions must sit above Back with a gap", 8f, (backBounds.top - actionsBounds.bottom).value, 0.5f)
        assertEquals("Back must sit above latest with a gap", 8f, (latestBounds.top - backBounds.bottom).value, 0.5f)
        assertTrue("Conversation actions escaped the safe message pane", actionsBounds.top > bar.bottom && actionsBounds.bottom <= composerBounds.top)
        actions.performClick()
        compose.onAllNodesWithTag("chat_overflow_menu").assertCountEquals(1)
        compose.onNodeWithText(resources.getString(R.string.chat_back)).assertDoesNotExist()
        compose.onNodeWithTag("chat_compact_details").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose
            .onNodeWithTag("chat_compact_search")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("chat_invite_user").performScrollTo().assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun compactPortalActionsRetainConversationListAndExistingOverflowInOneMenu() {
        var conversationLists = 0
        setContent(
            draft = { ComposerDraftState(hydrated = true) },
            pages = flowOf(PagingData.from(headerHistory())),
            showBack = false,
            onOpenConversationList = { conversationLists++ },
            historySyncStatus = HistorySyncStatus.Syncing,
            onSubmit = {},
        )
        compose.onNodeWithTag("chat_open_conversation_list").assertIsDisplayed()
        compose.onNodeWithTag("chat_timeline").performScrollToIndex(50)
        compose.onNodeWithTag("chat_title").assertHeightIsEqualTo(36.dp).assertHasNoClickAction()
        compose.onNodeWithTag(CHAT_TITLE_SYNC_SPINNER_TAG).assertIsDisplayed()
        compose.onNodeWithTag("chat_open_conversation_list").assertDoesNotExist()
        val actions = compose.onNodeWithTag("chat_compact_actions")
        actions.assertTouchHeightIsEqualTo(56.dp).assertTouchWidthIsEqualTo(56.dp)
        compose.onNodeWithTag("chat_compact_back").assertDoesNotExist()
        compose.onNodeWithContentDescription(RuntimeEnvironment.getApplication().getString(R.string.chat_back)).assertDoesNotExist()
        val actionsBounds = actions.getUnclippedBoundsInRoot()
        val latestBounds =
            compose
                .onNodeWithTag("chat_scroll_to_bottom_fab")
                .assertTouchHeightIsEqualTo(56.dp)
                .assertTouchWidthIsEqualTo(56.dp)
                .getUnclippedBoundsInRoot()
        assertEquals("Conversation actions must align with latest on the right", latestBounds.right.value, actionsBounds.right.value, 0.5f)
        assertEquals("Without Back, conversation actions must sit above latest with a gap", 8f, (latestBounds.top - actionsBounds.bottom).value, 0.5f)
        actions.performClick()
        compose.onAllNodesWithTag("chat_overflow_menu").assertCountEquals(1)
        compose.onNodeWithTag("chat_compact_back").assertDoesNotExist()
        compose.onNodeWithTag("chat_open_conversation_list").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, conversationLists) }
        compose.onNodeWithTag("chat_overflow_menu").assertDoesNotExist()
        actions.performClick()
        listOf(
            "chat_invite_user",
            "chat_watch",
            "chat_layout_menu",
            "chat_read_aloud_toggle",
            "chat_read_aloud_options",
            "chat_history_sync_menu",
            "chat_presence_menu",
        ).forEach { tag ->
            compose.onAllNodesWithTag(tag).assertCountEquals(1)
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun compactQueryDetailsKeepTheirRouteAndServerTitlesRemainInformational() {
        var currentBuffer by mutableStateOf(buffer.copy(name = "alice", displayName = "alice", type = BufferType.QUERY))
        var details = 0
        setContent(
            draft = { ComposerDraftState(hydrated = true) },
            pages = flowOf(PagingData.from(headerHistory())),
            chatBuffer = { currentBuffer },
            onOpenChannelInfo = {
                assertEquals(buffer.id, it)
                details++
            },
            onSubmit = {},
        )
        compose.onNodeWithTag("chat_timeline").performScrollToIndex(50)
        val actions = compose.onNodeWithTag("chat_compact_actions")
        actions.performClick()
        compose.onNodeWithTag("chat_invite_user").assertDoesNotExist()
        compose
            .onNodeWithTag("chat_compact_details")
            .assertTextEquals(RuntimeEnvironment.getApplication().getString(R.string.chat_open_nick_details))
            .performClick()
        compose.runOnIdle {
            assertEquals(1, details)
            currentBuffer = buffer.copy(name = "server", displayName = "server", type = BufferType.SERVER)
        }
        compose.onNodeWithTag("chat_title").assertHeightIsEqualTo(36.dp).assertHasNoClickAction()
        actions.performClick()
        compose.onAllNodesWithTag("chat_overflow_menu").assertCountEquals(1)
        compose.onNodeWithTag("chat_compact_details").assertDoesNotExist()
        compose.onNodeWithTag("chat_invite_user").assertDoesNotExist()
        compose.onNodeWithTag("chat_read_aloud_toggle").assertDoesNotExist()
        compose.onNodeWithTag("chat_history_sync_menu").assertDoesNotExist()
        compose.onNodeWithTag("chat_compact_search").assertIsDisplayed()
    }

    @Test
    fun overflowExposesInviteOnlyForChannels() {
        var invites = 0
        setContent(
            draft = { ComposerDraftState(hydrated = true) },
            onInviteUser = { invites++ },
            onSubmit = {},
        )

        compose.onNodeWithTag("chat_overflow").performClick()
        compose.onNodeWithTag("chat_invite_user").performClick()
        compose.runOnIdle { assertEquals(1, invites) }
    }

    @Test
    fun overflowOmitsInviteForQueries() {
        setContent(
            draft = { ComposerDraftState(hydrated = true) },
            chatBuffer = { buffer.copy(name = "alice", displayName = "alice", type = BufferType.QUERY) },
            onSubmit = {},
        )

        compose.onNodeWithTag("chat_overflow").performClick()
        compose.onNodeWithTag("chat_invite_user").assertDoesNotExist()
    }

    @Test
    fun overflowDisablesInviteWhileDisconnected() {
        setContent(
            draft = { ComposerDraftState(hydrated = true) },
            connectionState = IrcClientState.Disconnected,
            onSubmit = {},
        )

        compose.onNodeWithTag("chat_overflow").performClick()
        compose.onNodeWithTag("chat_invite_user").assertIsNotEnabled()
    }

    @Test
    fun overflowOmitsInviteForPartedChannels() {
        setContent(
            draft = { ComposerDraftState(hydrated = true) },
            chatBuffer = { buffer.copy(joined = false) },
            onSubmit = {},
        )

        compose.onNodeWithTag("chat_overflow").performClick()
        compose.onNodeWithTag("chat_invite_user").assertDoesNotExist()
    }

    @Test
    fun ebooksSearch_waitsForHydrationAndAcceptedClearBeforeOfferingNextSearch() {
        var draft by mutableStateOf(ComposerDraftState())
        val changed = mutableListOf<String>()
        val submitted = mutableListOf<String>()
        setContent(
            chatBuffer = { ebooksBuffer },
            draft = { draft },
            ebooksHelperRoomId = { ebooksBuffer.id },
            onDraftChanged = {
                changed += it
                draft = draft.copy(text = it, revision = draft.revision + 1)
            },
            onSubmit = { submitted += it },
        )

        compose
            .onNodeWithTag("chat_composer_field")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnIdle { assertEquals(emptyList<String>(), changed) }
        compose.runOnIdle { draft = draft.copy(hydrated = true, revision = draft.revision + 1) }
        compose.onNodeWithTag("chat_composer_field").assertTextEquals("@Search ")
        compose.onNodeWithTag("chat_composer_send").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf("@Search "), changed)
            assertEquals(emptyList<String>(), submitted)
        }

        compose.onNodeWithTag("chat_ebooks_help").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat_ebooks_dialog").assertIsDisplayed()
        compose.onNodeWithText("@Searchook", substring = true).assertExists()
        compose.onNodeWithText("@TEXTBOOKS", substring = true).assertExists()
        compose.onNodeWithText("@Oatmeal", substring = true).assertExists()
        compose.onNodeWithText("@sbclient is mIRC help. DCC is required for files.", substring = true).assertExists()
        compose.onNodeWithText("Read the current topic by tapping #ebooks above.", substring = true).assertExists()
        compose.onNodeWithText("Tap DCC offers here to review incoming network offers", substring = true).assertExists()
        compose.onNodeWithText("Close").performClick()

        compose.onNodeWithTag("chat_composer_field").performTextInput("city of brass")
        compose.onNodeWithTag("chat_composer_field").assertTextEquals("@Search city of brass")
        compose.onNodeWithTag("chat_composer_send").assertIsEnabled()
        compose.onNodeWithTag("chat_composer_send").performClick()
        compose.runOnIdle {
            assertEquals(listOf("@Search city of brass"), submitted)
            assertEquals("@Search city of brass", draft.text)
        }
        compose
            .onNodeWithTag("chat_composer_field")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        // The optimistic clear is not an accepted send: wait for the VM's new empty revision.
        compose.runOnIdle { draft = draft.copy(text = "", revision = draft.revision + 1) }
        compose.onNodeWithTag("chat_composer_field").assertTextEquals("@Search ")
        compose.onNodeWithTag("chat_composer_send").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf("@Search ", "@Search city of brass", "@Search "), changed)
            assertEquals(listOf("@Search city of brass"), submitted)
        }
    }

    @Test
    fun ebooksSearch_preservesExistingDraftWhitespaceAndReplyContext() {
        var draft by mutableStateOf(ComposerDraftState("unfinished", hydrated = true, revision = 1))
        var reply by mutableStateOf<MessageEntity?>(null)
        val changed = mutableListOf<String>()
        setContent(
            chatBuffer = { ebooksBuffer },
            draft = { draft },
            replyTo = { reply },
            ebooksHelperRoomId = { ebooksBuffer.id },
            onDraftChanged = { changed += it },
            onSubmit = {},
        )

        compose.onNodeWithTag("chat_composer_field").assertTextEquals("unfinished")
        compose.onNodeWithTag("chat_ebooks_help").performClick()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { draft = ComposerDraftState("  ", hydrated = true, revision = 2) }
        compose.onNodeWithTag("chat_composer_field").assertTextEquals("  ")
        compose.runOnIdle {
            reply =
                MessageEntity(
                    id = 42,
                    bufferId = ebooksBuffer.id,
                    serverTime = 1,
                    sender = "alice",
                    kind = MessageKind.PRIVMSG,
                    text = "original",
                    dedupKey = "original-42",
                )
            draft = ComposerDraftState(hydrated = true, revision = 3)
        }
        compose
            .onNodeWithTag("chat_composer_field")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnIdle { assertEquals(emptyList<String>(), changed) }
        compose.runOnIdle { reply = null }
        compose.onNodeWithTag("chat_composer_field").assertTextEquals("@Search ")
        compose.runOnIdle { assertEquals(listOf("@Search "), changed) }
    }

    @Test
    fun ebooksHelp_keepsGuidanceReadableBeforeHydration() {
        setContent(
            draft = { ComposerDraftState() },
            chatBuffer = { ebooksBuffer },
            ebooksHelperRoomId = { buffer.id },
            onSubmit = {},
        )
        compose.onNodeWithTag("chat_ebooks_help").performClick()
        compose.onNodeWithText("@Searchook", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose
            .onNodeWithTag("chat_composer_field")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test
    fun ebooksHelp_staysReadableWhilePartedAndPrefillsDraft() {
        val changed = mutableListOf<String>()
        setContent(
            draft = { ComposerDraftState(hydrated = true) },
            chatBuffer = { ebooksBuffer.copy(joined = false) },
            parted = true,
            ebooksHelperRoomId = { buffer.id },
            onDraftChanged = { changed += it },
            onSubmit = {},
        )
        compose.onNodeWithTag("chat_ebooks_help").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat_ebooks_dialog").assertIsDisplayed()
        compose.onNodeWithText("Read the current topic by tapping #ebooks above.", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("@Search "), changed) }
    }

    @Test
    fun ebooksHelp_staysReadableWhileOfflineAndPrefillsDraft() {
        setContent(
            draft = { ComposerDraftState(hydrated = true) },
            chatBuffer = { ebooksBuffer },
            connectionState = IrcClientState.Disconnected,
            ebooksHelperRoomId = { buffer.id },
            onSubmit = {},
        )
        compose.onNodeWithTag("chat_ebooks_help").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat_ebooks_dialog").assertIsDisplayed()
        compose.onNodeWithText("Read the current topic by tapping #ebooks above.", substring = true).assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("chat_composer_field").assertTextEquals("@Search ")
    }

    @Test
    fun ebooksHelp_onlyShowsForTheEligibleRoomAndClosesOnEligibilityLoss() {
        var room by mutableStateOf(ebooksBuffer)
        var eligible by mutableStateOf<Long?>(null)
        val changed = mutableListOf<String>()
        setContent(
            draft = { ComposerDraftState(hydrated = true) },
            chatBuffer = { room },
            ebooksHelperRoomId = { eligible },
            onDraftChanged = { changed += it },
            onSubmit = {},
        )
        compose.onNodeWithTag("chat_ebooks_help").assertDoesNotExist()
        compose.runOnIdle { eligible = buffer.id + 1 }
        compose.onNodeWithTag("chat_ebooks_help").assertDoesNotExist()

        compose.runOnIdle { eligible = buffer.id }
        compose.onNodeWithTag("chat_ebooks_help").performClick()
        compose.onNodeWithTag("chat_ebooks_dialog").assertIsDisplayed()
        compose.runOnIdle { room = ebooksBuffer.copy(id = ebooksBuffer.id + 1) }
        compose.onNodeWithTag("chat_ebooks_dialog").assertDoesNotExist()
        compose.onNodeWithTag("chat_ebooks_help").assertDoesNotExist()

        compose.runOnIdle { room = ebooksBuffer }
        compose.onNodeWithTag("chat_ebooks_help").assertIsDisplayed()
        compose.onNodeWithTag("chat_ebooks_dialog").assertDoesNotExist()
        compose.onNodeWithTag("chat_ebooks_help").performClick()
        compose.runOnIdle { eligible = null }
        compose.onNodeWithTag("chat_ebooks_dialog").assertDoesNotExist()
        compose.onNodeWithTag("chat_ebooks_help").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("@Search ", "@Search "), changed) }
    }

    @Test
    fun ebooksDcc_channelRoundtripReceivesPrivatelyViewsRequestsAndSavesBook() {
        val zip = selectedZip("!books_bot token | The City of Brass.epub ::INFO:: 2MB")
        val cache = EbooksResultCache(RuntimeEnvironment.getApplication())
        try {
            var draft by mutableStateOf(ComposerDraftState(hydrated = true))
            var offers by mutableStateOf(emptyList<DccTransferEntity>())
            val accepted = mutableListOf<Triple<Long, Uri, Boolean>>()
            val submitted = mutableListOf<String>()
            val exported = mutableListOf<Long>()
            setContent(
                chatBuffer = { ebooksBuffer },
                ebooksHelperRoomId = { ebooksBuffer.id },
                ebooksDccOffers = { offers },
                activityResults = pickerOwner,
                draft = { draft },
                onDraftChanged = { draft = draft.copy(text = it, revision = draft.revision + 1) },
                onAcceptDccTransfer = { id, uri, allowPrivate -> accepted += Triple(id, uri, allowPrivate) },
                onSaveDccToDownloads = { exported += it },
                onSubmit = { submitted += it },
            )

            compose.onNodeWithTag("chat_ebooks_dcc_offers").assertIsDisplayed()
            compose.onNodeWithText("DCC offers (0) · No offers yet").assertExists()
            compose.onNodeWithTag("chat_ebooks_dcc_offers").performClick()
            compose.onNodeWithTag("chat_ebooks_dcc_empty").assertIsDisplayed()
            compose.runOnIdle { offers = listOf(incomingOffer()) }
            compose.onNodeWithTag("chat_ebooks_dcc_offers").assertTextEquals("DCC offers (1)")
            compose.onNodeWithText("From books_bot").assertIsDisplayed()
            compose.onNodeWithTag("chat_dcc_accept_7").assertDoesNotExist()
            compose.onNodeWithTag("chat_ebooks_receive_results_7").assertIsDisplayed().performClick()
            compose.onNodeWithTag("dcc_download_menu_7").assertDoesNotExist()
            compose.runOnIdle {
                val destination = accepted.single()
                assertEquals(7L, destination.first)
                assertEquals(false, destination.third)
                assertEquals(-1, pickerRequestCode)
                assertEquals(true, cache.isOwned(destination.second, 7))
                RuntimeEnvironment.getApplication().contentResolver.openOutputStream(destination.second, "wt")!!.use { output ->
                    zip.inputStream().use { input -> input.copyTo(output) }
                }
            }

            compose.runOnIdle { offers = listOf(incomingOffer(state = DccTransferState.ACTIVE).copy(bytesTransferred = 50)) }
            compose.onNodeWithTag("chat_dcc_progress_7").assertExists()
            compose.onNodeWithTag("chat_ebooks_receive_results_7").assertDoesNotExist()
            compose.onNodeWithText("100 B · Plain DCC SEND · Transferring").assertExists()
            compose.onNodeWithTag("chat_ebooks_view_results_7").assertDoesNotExist()
            compose.onNodeWithTag("dcc_download_menu_7").assertDoesNotExist()
            compose.runOnIdle {
                offers = listOf(incomingOffer(state = DccTransferState.COMPLETED, destinationUri = accepted.single().second.toString()))
            }
            compose.onNodeWithText("100 B · Plain DCC SEND · Complete").assertExists()
            compose.onNodeWithTag("chat_ebooks_receive_results_7").assertDoesNotExist()
            compose.onNodeWithTag("dcc_download_menu_7").assertIsDisplayed().performClick()
            compose.onNodeWithTag("dcc_save_to_downloads_7").assertIsDisplayed().performClick()
            compose.runOnIdle {
                assertEquals(listOf(7L), exported)
                assertEquals(-1, pickerRequestCode)
                assertTrue(File(requireNotNull(accepted.single().second.path)).exists())
            }
            compose.onNodeWithTag("chat_ebooks_view_results_7").assertIsDisplayed().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("chat_ebooks_results").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("The City of Brass.epub").assertIsDisplayed()
            compose.onNodeWithTag("chat_ebooks_request").assertIsEnabled().performClick()
            val request = "!books_bot token | The City of Brass.epub"
            compose.onNodeWithTag("chat_ebooks_results").assertDoesNotExist()
            compose.onNodeWithTag("chat_composer_field").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.runOnIdle { assertEquals(listOf(request), submitted) }

            compose.runOnIdle { offers = listOf(incomingOffer(id = 8, filename = "The City of Brass.epub"), offers.single()) }
            compose.onNodeWithTag("chat_ebooks_dcc_offers").performClick()
            compose.onNodeWithTag("chat_dcc_accept_8").assertIsDisplayed().performClick()
            compose.runOnIdle {
                pickerRegistry.dispatchResult(pickerRequestCode, Activity.RESULT_OK, Intent().setData(Uri.parse("content://saved/book")))
            }
            compose.runOnIdle {
                assertEquals(listOf(7L, 8L), accepted.map { it.first })
                assertEquals(Uri.parse("content://saved/book"), accepted.last().second)
                assertEquals(false, cache.isOwned(accepted.last().second))
            }
        } finally {
            zip.delete()
            cache.discard(cache.uriFor(7), 7)
        }
    }

    @Test
    fun ebooksDcc_filtersNetworkAndEligibilityAndRequiresCompletedZip() {
        var room by mutableStateOf(ebooksBuffer)
        var eligible by mutableStateOf<Long?>(ebooksBuffer.id)
        var offers by mutableStateOf(listOf(incomingOffer(address = "127.0.0.1")))
        val accepted = mutableListOf<Triple<Long, Uri, Boolean>>()
        setContent(
            chatBuffer = { room },
            ebooksHelperRoomId = { eligible },
            ebooksDccOffers = { offers },
            activityResults = pickerOwner,
            draft = { ComposerDraftState(hydrated = true) },
            onAcceptDccTransfer = { id, uri, allowPrivate -> accepted += Triple(id, uri, allowPrivate) },
            onSubmit = {},
        )
        compose.onNodeWithTag("chat_ebooks_dcc_offers").performClick()
        compose.onNodeWithTag("chat_dcc_accept_7").assertDoesNotExist()
        compose.onNodeWithText("Private or local endpoint", substring = true).assertExists()
        compose.onNodeWithTag("chat_dcc_transfer_7").performTouchInput { swipeUp() }
        compose.onNodeWithTag("chat_ebooks_receive_results_7").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(Triple(7L, EbooksResultCache(RuntimeEnvironment.getApplication()).uriFor(7), true)), accepted)
            assertEquals(-1, pickerRequestCode)
        }
        compose.runOnIdle { offers = listOf(incomingOffer(state = DccTransferState.COMPLETED)) }
        compose.onNodeWithTag("chat_ebooks_view_results_7").assertDoesNotExist()
        val unsupported = selectedZip("not a book result")
        try {
            compose.runOnIdle {
                offers = listOf(incomingOffer(state = DccTransferState.ACTIVE, destinationUri = Uri.fromFile(unsupported).toString()))
            }
            compose.onNodeWithTag("chat_ebooks_view_results_7").assertDoesNotExist()
            compose.runOnIdle {
                offers = listOf(incomingOffer(state = DccTransferState.COMPLETED, destinationUri = Uri.fromFile(unsupported).toString()))
            }
            compose.onNodeWithTag("chat_ebooks_view_results_7").assertIsDisplayed().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("chat_ebooks_results_error").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("No valid book results in ZIP .txt entries").assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
        } finally {
            unsupported.delete()
        }
        compose.onNodeWithTag("chat_ebooks_dcc_offers").performClick()
        compose.runOnIdle { offers = listOf(incomingOffer(networkId = 2)) }
        compose.onNodeWithTag("chat_ebooks_receive_results_7").assertDoesNotExist()
        compose.runOnIdle { room = ebooksBuffer.copy(id = ebooksBuffer.id + 1, networkId = 2) }
        compose.onNodeWithTag("chat_ebooks_dcc_sheet").assertDoesNotExist()
        compose.runOnIdle { eligible = null }
        compose.onNodeWithTag("chat_ebooks_dcc_sheet").assertDoesNotExist()
        compose.onNodeWithTag("chat_ebooks_dcc_offers").assertDoesNotExist()
    }

    @Test
    fun ebooksResults_selectedZipRequestsOnOneTapWithoutComposerSend() {
        val file = selectedZip("!artemis_serv 16d6770d2ba9 | 27 - The Last Hero - Graphic Novel.pdf ::INFO:: 49.78MB")
        try {
            var draft by mutableStateOf(ComposerDraftState(hydrated = true))
            val changed = mutableListOf<String>()
            val submitted = mutableListOf<String>()
            setContent(
                chatBuffer = { ebooksBuffer },
                ebooksHelperRoomId = { ebooksBuffer.id },
                activityResults = pickerOwner,
                draft = { draft },
                onDraftChanged = {
                    changed += it
                    draft = draft.copy(text = it, revision = draft.revision + 1)
                },
                onSubmit = { submitted += it },
            )
            compose.onNodeWithTag("chat_composer_field").assertTextEquals("@Search ")
            chooseResults(file)
            compose.onNodeWithTag("chat_ebooks_results").assertIsDisplayed()
            compose.onNodeWithText("27 - The Last Hero - Graphic Novel.pdf").assertIsDisplayed()
            compose.onNodeWithText("49.78MB").assertIsDisplayed()
            compose.onNodeWithText("Request sends this channel message immediately.", substring = true).assertIsDisplayed()
            compose.onNodeWithTag("chat_ebooks_request").assertIsEnabled().performTouchInput {
                click()
                click()
            }
            val request = "!artemis_serv 16d6770d2ba9 | 27 - The Last Hero - Graphic Novel.pdf"
            compose.onNodeWithTag("chat_ebooks_results").assertDoesNotExist()
            compose.onNodeWithTag("chat_composer_field").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.runOnIdle {
                assertEquals(listOf("@Search ", request), changed)
                assertEquals(listOf(request), submitted)
            }
            chooseResults(file)
            compose.onNodeWithTag("chat_ebooks_request").assertIsNotEnabled()
            compose.runOnIdle { assertEquals(listOf(request), submitted) }
            compose.onNodeWithText("Close").performClick()
            compose.runOnIdle { draft = draft.copy(text = "", revision = draft.revision + 1) }
            compose.onNodeWithTag("chat_composer_field").assertTextEquals("@Search ")
        } finally {
            file.delete()
        }
    }

    @Test
    fun ebooksResults_rejectedRequestRestoresDurableDraft() {
        val file = selectedZip("!bot token | My book.pdf ::INFO:: 2MB")
        try {
            var draft by mutableStateOf(ComposerDraftState(hydrated = true))
            val submitted = mutableListOf<String>()
            setContent(
                draft = { draft },
                chatBuffer = { ebooksBuffer },
                ebooksHelperRoomId = { ebooksBuffer.id },
                activityResults = pickerOwner,
                onDraftChanged = { draft = draft.copy(text = it, revision = draft.revision + 1) },
                onSubmit = { submitted += it },
            )
            chooseResults(file)
            compose.onNodeWithTag("chat_ebooks_request").performClick()
            val request = "!bot token | My book.pdf"
            compose.onNodeWithTag("chat_composer_field").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.runOnIdle {
                assertEquals(request, draft.text)
                assertEquals(listOf(request), submitted)
                // The ViewModel republishes a rejected send under a newer draft revision.
                draft = draft.copy(revision = draft.revision + 1)
            }
            compose.onNodeWithTag("chat_composer_field").assertTextEquals(request)
            compose.runOnIdle { assertEquals(listOf(request), submitted) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun ebooksResults_cannotReplaceOtherDraftReplyOrPartedChannel() {
        val file = selectedZip("!bot token | My book.pdf ::INFO:: 2MB")
        try {
            var draft by mutableStateOf(ComposerDraftState("another draft", hydrated = true, revision = 1))
            var room by mutableStateOf(ebooksBuffer)
            var joined by mutableStateOf(true)
            var eligible by mutableStateOf<Long?>(ebooksBuffer.id)
            var reply by mutableStateOf<MessageEntity?>(null)
            val changed = mutableListOf<String>()
            val submitted = mutableListOf<String>()
            setContent(
                draft = { draft },
                chatBuffer = { room.copy(joined = joined) },
                replyTo = { reply },
                ebooksHelperRoomId = { eligible },
                activityResults = pickerOwner,
                onDraftChanged = {
                    changed += it
                    draft = draft.copy(text = it, revision = draft.revision + 1)
                },
                onSubmit = { submitted += it },
            )
            chooseResults(file)
            compose.onNodeWithTag("chat_ebooks_request").assertIsNotEnabled()
            compose.onNodeWithTag("chat_composer_field").assertTextEquals("another draft")
            compose.runOnIdle { draft = draft.copy(text = "@Search ", revision = draft.revision + 1) }
            compose.onNodeWithTag("chat_ebooks_request").assertIsEnabled()
            compose.runOnIdle {
                reply =
                    MessageEntity(
                        id = 55,
                        bufferId = ebooksBuffer.id,
                        serverTime = 1,
                        sender = "reader",
                        kind = MessageKind.PRIVMSG,
                        text = "reply",
                        dedupKey = "reply-55",
                    )
            }
            compose.onNodeWithTag("chat_ebooks_request").assertIsNotEnabled()
            compose.onNodeWithTag("chat_composer_field").assertTextEquals("@Search ")
            compose.runOnIdle { assertEquals(emptyList<String>(), submitted) }
            compose.runOnIdle {
                reply = null
                joined = false
            }
            compose.onNodeWithTag("chat_ebooks_request").assertIsNotEnabled()
            compose.runOnIdle { room = ebooksBuffer.copy(id = ebooksBuffer.id + 1) }
            compose.onNodeWithTag("chat_ebooks_results").assertDoesNotExist()
            compose.runOnIdle { eligible = null }
            compose.runOnIdle {
                assertEquals(emptyList<String>(), changed)
                assertEquals(emptyList<String>(), submitted)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun ebooksResults_reportsUnsupportedZip() {
        val file = selectedZip("not a supported result")
        try {
            setContent(
                draft = { ComposerDraftState(hydrated = true) },
                chatBuffer = { ebooksBuffer },
                ebooksHelperRoomId = { ebooksBuffer.id },
                activityResults = pickerOwner,
                onSubmit = {},
            )
            chooseResults(file)
            compose.onNodeWithTag("chat_ebooks_results_error").assertIsDisplayed()
            compose.onNodeWithText("No valid book results in ZIP .txt entries").assertIsDisplayed()
        } finally {
            file.delete()
        }
    }

    @Test
    fun ebooksResults_offlineRequestStaysDisabled() {
        val file = selectedZip("!bot token | Book.pdf ::INFO:: 2MB")
        try {
            val submitted = mutableListOf<String>()
            setContent(
                draft = { ComposerDraftState("@Search ", hydrated = true, revision = 1) },
                chatBuffer = { ebooksBuffer },
                ebooksHelperRoomId = { ebooksBuffer.id },
                activityResults = pickerOwner,
                connectionState = IrcClientState.Disconnected,
                onSubmit = { submitted += it },
            )
            chooseResults(file)
            compose.onNodeWithTag("chat_ebooks_request").assertIsNotEnabled()
            compose.onNodeWithTag("chat_composer_field").assertTextEquals("@Search ")
            compose.runOnIdle { assertEquals(emptyList<String>(), submitted) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun ebooksFeed_showsQuietSearchesAndFileRequestsUntilTapped() {
        val posts =
            listOf(
                MessageEntity(
                    id = 104,
                    bufferId = ebooksBuffer.id,
                    serverTime = 4,
                    sender = "reader2",
                    kind = MessageKind.PRIVMSG,
                    text = "!books_bot 16d6770d2ba9 | The City of Brass.epub",
                    dedupKey = "books-104",
                ),
                MessageEntity(
                    id = 103,
                    bufferId = ebooksBuffer.id,
                    serverTime = 3,
                    sender = "bookseller",
                    kind = MessageKind.NOTICE,
                    text = "File   request:\nThe  City of Brass.epub",
                    dedupKey = "books-103",
                ),
                MessageEntity(
                    id = 102,
                    bufferId = ebooksBuffer.id,
                    serverTime = 2,
                    sender = "reader",
                    kind = MessageKind.PRIVMSG,
                    text = "@Search   City of Brass",
                    dedupKey = "books-102",
                ),
                MessageEntity(
                    id = 101,
                    bufferId = ebooksBuffer.id,
                    serverTime = 1,
                    sender = "me",
                    kind = MessageKind.PRIVMSG,
                    text = "My ordinary message",
                    isSelf = true,
                    dedupKey = "books-101",
                ),
            )
        setContent(
            draft = { ComposerDraftState(hydrated = true) },
            chatBuffer = { ebooksBuffer },
            ebooksHelperRoomId = { ebooksBuffer.id },
            pages = flowOf(PagingData.from(posts)),
            onSubmit = {},
        )

        compose
            .onNodeWithTag("chat_ebooks_quiet_104", useUnmergedTree = true)
            .assertTextEquals("reader2 · File request · The City of Brass.epub")
        compose
            .onNodeWithTag("chat_ebooks_quiet_103", useUnmergedTree = true)
            .assertTextEquals("bookseller · File request · The City of Brass.epub")
        compose
            .onNodeWithTag("chat_ebooks_quiet_102", useUnmergedTree = true)
            .assertTextEquals("reader · Search · City of Brass")
        compose.onNodeWithTag("chat_ebooks_quiet_101").assertDoesNotExist()
        compose.onNodeWithText("My ordinary message").assertExists()
        compose.onNodeWithTag("chat_message_102", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("chat_ebooks_quiet_102").assertDoesNotExist()
        compose.onNodeWithText("@Search   City of Brass", substring = true).assertExists()
        compose.onNodeWithTag("chat_message_103", useUnmergedTree = true).performClick()
        compose.onNodeWithText("File   request:", substring = true).assertExists()
        holdMessageBubble(posts.single { it.id == 102L })
        compose.onNodeWithTag("message_action_copy").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("message_action_sheet").assertDoesNotExist()
        compose.runOnIdle {
            val clipboard = RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
            assertEquals(
                "@Search   City of Brass",
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString(),
            )
        }
    }

    @Test
    fun ebooksFeed_andPrefixDoNotAppearOutsideEligibleRoom() {
        val changed = mutableListOf<String>()
        setContent(
            draft = { ComposerDraftState(hydrated = true) },
            chatBuffer = { buffer },
            ebooksHelperRoomId = { ebooksBuffer.id + 1 },
            pages =
                flowOf(
                    PagingData.from(
                        listOf(
                            MessageEntity(
                                id = 104,
                                bufferId = buffer.id,
                                serverTime = 1,
                                sender = "reader",
                                kind = MessageKind.PRIVMSG,
                                text = "@Search   City of Brass",
                                dedupKey = "books-104",
                            ),
                        ),
                    ),
                ),
            onDraftChanged = { changed += it },
            onSubmit = {},
        )
        compose.onNodeWithTag("chat_ebooks_help").assertDoesNotExist()
        compose.onNodeWithTag("chat_ebooks_quiet_104").assertDoesNotExist()
        compose.onNodeWithText("@Search   City of Brass", substring = true).assertExists()
        compose
            .onNodeWithTag("chat_composer_field")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnIdle { assertEquals(emptyList<String>(), changed) }
    }

    @Test
    fun send_emptiesTheFieldWithoutWaitingForTheSendToLand() {
        val submitted = mutableListOf<String>()
        // Nothing acknowledges the send: no accepted result, no cleared draft comes back.
        setContent(draft = { ComposerDraftState("hello", hydrated = true, revision = 1) }) {
            submitted += it
        }

        compose.onNodeWithText("hello").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_send").performClick()
        compose.waitForIdle()

        assertEquals(listOf("hello"), submitted)
        compose.onNodeWithText("hello").assertDoesNotExist()
    }

    @Test
    fun backDismissesComposerToolsBeforeLeavingChat() {
        setContent(draft = { ComposerDraftState("hello", hydrated = true, revision = 1) }) {}

        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_format_toolbar").assertIsDisplayed()
        compose.runOnUiThread { backDispatcher?.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithTag("chat_composer_format_toolbar").assertDoesNotExist()
    }

    @Test
    fun completedMorph_matchesTheRealBubbleWhileLandingIsStillDelayed() {
        val nick = "metadata-wider-than-body"
        val flight = OutgoingFlight(token = 7, text = "\u0002hi\u0002", launchedAtMs = 1_000)
        val anchors = SendFlightAnchors().apply { composerField = Rect(0f, 0f, 380f, 48f) }
        val motion = SendFlightMotion(morphEnabled = true)
        runBlocking { motion.morph.snapTo(1f) }
        var renderFlight by mutableStateOf(false)
        var status by mutableStateOf(true to false)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                val formatTime = rememberMessageTimeFormatter()
                // Compare at the same window origin, not two separately rasterized stacked rows.
                CompositionLocalProvider(LocalLottieMotionEnabled provides false) {
                    Box(
                        Modifier
                            .width(380.dp)
                            .height(120.dp)
                            .background(MaterialTheme.colorScheme.background)
                            .testTag("bubble_sample"),
                    ) {
                        if (!renderFlight) {
                            MessageBubble(
                                sender = nick,
                                text = flight.text,
                                timeMs = flight.launchedAtMs,
                                isSelf = true,
                                kind = MessageKind.PRIVMSG,
                                showSender = true,
                                formattedTime = formatTime(flight.launchedAtMs),
                                pending = status.first,
                                failed = status.second,
                            )
                        } else {
                            SendFlightOverlay(
                                flight = flight,
                                pending = status.first,
                                failed = status.second,
                                anchors = anchors,
                                motion = motion,
                                listShift = { 0f },
                                selfNick = nick,
                                showSender = true,
                                networkId = null,
                                knownNicks = emptySet(),
                                identityRules = IrcIdentityRules(),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        val sample = compose.onNodeWithTag("bubble_sample")
        for (flags in listOf(true to false, false to false, true to true, false to true)) {
            compose.runOnIdle {
                status = flags
                renderFlight = false
            }
            compose.waitForIdle()
            val real = sample.captureToImage().asAndroidBitmap()
            compose.onAllNodesWithText("hi", useUnmergedTree = true).assertCountEquals(1)
            compose.runOnIdle { renderFlight = true }
            compose.waitForIdle()
            val airborne = sample.captureToImage().asAndroidBitmap()
            assertTrue("The delayed ghost must match the real bubble with pending/failed=$flags", real.sameAs(airborne))
            // The merged accessibility tree excludes the overlay's cleared semantics subtree.
            compose.onAllNodesWithText("hi").assertCountEquals(0)
        }
        assertEquals(0f, motion.progress.value, 0.001f)
    }

    @Test
    fun completedMorph_keepsWindowLaunchPositionWhenHeaderMovesOverlayHost() {
        val flight = OutgoingFlight(token = 7, text = "\u0002hi\u0002", launchedAtMs = 1_000)
        val anchors = SendFlightAnchors()
        val motion = SendFlightMotion(morphEnabled = true)
        runBlocking { motion.morph.snapTo(1f) }
        var headerHeight by mutableStateOf(54.dp)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                val density = LocalDensity.current
                Column(
                    Modifier
                        .width(380.dp)
                        .height(400.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .testTag("flight_panel")
                        .onGloballyPositioned {
                            if (anchors.launchField == null) {
                                val origin = it.positionInWindow()
                                anchors.launchField =
                                    with(density) {
                                        Rect(
                                            origin.x,
                                            origin.y + 200.dp.toPx(),
                                            origin.x + 380.dp.toPx(),
                                            origin.y + 248.dp.toPx(),
                                        )
                                    }
                            }
                        },
                ) {
                    Spacer(Modifier.height(headerHeight))
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .onGloballyPositioned { anchors.hostOrigin = it.positionInWindow() },
                    ) {
                        SendFlightOverlay(
                            flight = flight,
                            pending = true,
                            failed = false,
                            anchors = anchors,
                            motion = motion,
                            listShift = { 0f },
                            selfNick = "metadata-wider-than-body",
                            showSender = true,
                            networkId = null,
                            knownNicks = emptySet(),
                            identityRules = IrcIdentityRules(),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        assertTrue("The flight must be rendered before comparing pixels", anchors.ghostHeight > 0f)
        val panel = compose.onNodeWithTag("flight_panel")
        val before = panel.captureToImage().asAndroidBitmap()
        val initialHostY = anchors.hostOrigin.y
        compose.runOnIdle { headerHeight = 96.dp }
        compose.waitForIdle()
        assertTrue("Header expansion must move the overlay host", anchors.hostOrigin.y > initialHostY)
        val after = panel.captureToImage().asAndroidBitmap()
        assertTrue("The ghost must stay at its window launch position when the host moves", before.sameAs(after))
        assertEquals(0f, motion.lift.value, 0.001f)
        assertEquals(0f, motion.progress.value, 0.001f)
    }

    @Test
    fun activeFlight_tracksCanonicalStatusBeforeLandingWithoutGuessingByText() {
        val launchedAt = 1_000L
        val pending =
            MessageEntity(
                id = 42,
                bufferId = buffer.id,
                serverTime = launchedAt + 1,
                sender = "me",
                kind = MessageKind.PRIVMSG,
                text = "hello",
                isSelf = true,
                pendingLabel = "pending-42",
                dedupKey = "pending-42",
                serverTimeAuthoritative = false,
                timelineOrder = 42,
            )
        // Keep the canonical row loaded but outside layout: the completed ghost stays airborne,
        // and no real status glyph can account for a pixel change on the captured screen.
        val history =
            (100L downTo 51L).map { id ->
                MessageEntity(
                    id = id,
                    bufferId = buffer.id,
                    serverTime = launchedAt + id,
                    sender = "other",
                    kind = MessageKind.PRIVMSG,
                    text = "history-$id",
                    dedupKey = "history-$id",
                    timelineOrder = id,
                )
            }
        val confirmed = pending.copy(pendingLabel = null, serverTime = launchedAt - 100)
        val pages = MutableStateFlow(PagingData.from(history + confirmed))
        var flight by mutableStateOf(OutgoingFlight(token = 7, text = pending.text, launchedAtMs = launchedAt))
        var settled = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalLottieMotionEnabled provides false) {
                    Box(Modifier.fillMaxSize().testTag("flight_status_screen")) {
                        ChatContent(
                            state = ChatState(buffer = buffer, connState = IrcClientState.Ready("me", emptySet(), emptyMap())),
                            items = pages.collectAsLazyPagingItems(),
                            composerEnabled = true,
                            onBack = {},
                            onOpenChannelInfo = {},
                            onOpenSearch = {},
                            onOpenImage = {},
                            nickNormalizer = { it.lowercase() },
                            onSubmit = {},
                            onTyping = {},
                            onSetReply = {},
                            onReact = { _, _ -> },
                            onRetry = {},
                            loadPreview = { _, _ -> null },
                            composerDraft = ComposerDraftState(hydrated = true),
                            outgoingFlight = flight,
                            onFlightSettled = { settled++ },
                            entryState = EntryPositionState.Settled,
                        )
                    }
                }
            }
        }

        fun pixels(): Bitmap {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitForIdle()
            compose.runOnIdle { assertEquals("The offscreen row must not settle the flight", 0, settled) }
            compose.onAllNodesWithText("hello").assertCountEquals(0)
            return compose.onNodeWithTag("flight_status_screen").captureToImage().asAndroidBitmap()
        }

        val unknown = pixels()
        compose.runOnIdle { pages.value = PagingData.from(history + pending) }
        val sending = pixels()
        assertTrue("Historical confirmed text without an identity must remain pending", unknown.sameAs(sending))
        compose.runOnIdle { flight = flight.copy(eventIds = setOf(pending.id)) }
        assertTrue("Acceptance alone must not paint a check", sending.sameAs(pixels()))

        val unchangedStatuses = mutableListOf<String>()
        compose.runOnIdle {
            // Same token and ID; an echo may rewrite both its text and server timestamp.
            pages.value = PagingData.from(history + confirmed.copy(text = "echo-rewritten"))
        }
        val sent = pixels()
        if (sending.sameAs(sent)) unchangedStatuses += "confirmed"
        compose.runOnIdle { pages.value = PagingData.from(history + pending.copy(failed = true)) }
        val failed = pixels()
        if (sending.sameAs(failed) || sent.sameAs(failed)) unchangedStatuses += "failed"
        compose.runOnIdle { pages.value = PagingData.from(history + confirmed.copy(failed = true)) }
        assertTrue("Failure must win whether or not the pending label remains", failed.sameAs(pixels()))
        compose.runOnIdle { pages.value = PagingData.from(history + pending) }
        assertTrue("Status must not be cached by flight token", sending.sameAs(pixels()))
        compose.runOnIdle { pages.value = PagingData.from(history + confirmed.copy(id = 41)) }
        assertTrue("A missing accepted ID must not claim a historical same-text row", sending.sameAs(pixels()))
        assertEquals("The active production ghost must render each canonical status", emptyList<String>(), unchangedStatuses)
    }

    @Test
    fun quickLanding_doesNotHandOffBeforeTheMorphCompletes() {
        val launchedAt = 1_000L
        val morphDurationMs =
            MotdMotion.sendMorphGrow.vectorize(Float.VectorConverter).getDurationNanos(
                AnimationVector1D(0f),
                AnimationVector1D(1f),
                AnimationVector1D(0f),
            ) / 1_000_000
        val pages = MutableStateFlow(PagingData.from(emptyList<MessageEntity>()))
        var flight by mutableStateOf<OutgoingFlight?>(null)
        var settled = 0
        var startedAt = 0L
        var settledAt = 0L
        setContent(
            draft = { ComposerDraftState("hello", hydrated = true, revision = 1) },
            pages = pages,
            outgoingFlight = { flight },
            onFlightSettled = { token ->
                assertEquals(7L, token)
                settled++
                settledAt = compose.mainClock.currentTime
                flight = null
            },
            onSubmit = { text ->
                startedAt = compose.mainClock.currentTime
                flight = OutgoingFlight(token = 7, text = text, launchedAtMs = launchedAt)
                pages.value =
                    PagingData.from(
                        listOf(
                            MessageEntity(
                                id = 42,
                                bufferId = buffer.id,
                                serverTime = launchedAt + 1,
                                sender = "me",
                                kind = MessageKind.PRIVMSG,
                                text = text,
                                isSelf = true,
                                pendingLabel = "pending-42",
                                dedupKey = "pending-42",
                                serverTimeAuthoritative = false,
                                timelineOrder = 42,
                            ),
                        ),
                    )
            },
        )
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("chat_composer_send").performClick()
        compose.mainClock.advanceTimeBy(morphDurationMs * 3 / 4)
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(0, settled) }
        compose
            .onNodeWithTag("chat_composer_field")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onAllNodesWithText("hello", useUnmergedTree = true).assertCountEquals(1)
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, settled)
            assertTrue("Quick persistence must not truncate the running morph", settledAt - startedAt >= morphDurationMs)
        }
        compose.onNodeWithContentDescription("Sending…").assertIsDisplayed()
    }

    @Test
    fun delayedLanding_keepsFlightUntilPendingRowTakesOver() {
        val launchedAt = 1_000L
        val pages = MutableStateFlow(PagingData.from(emptyList<MessageEntity>()))
        var flight by mutableStateOf<OutgoingFlight?>(
            OutgoingFlight(token = 7, text = "hello", launchedAtMs = launchedAt),
        )
        var settled = 0
        compose.mainClock.autoAdvance = false
        setContent(
            draft = { ComposerDraftState(hydrated = true) },
            onSubmit = {},
            pages = pages,
            outgoingFlight = { flight },
            onFlightSettled = { token ->
                assertEquals(7L, token)
                settled++
                flight = null
            },
        )

        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertEquals(0, settled) }

        compose.runOnUiThread {
            pages.value =
                PagingData.from(
                    listOf(
                        MessageEntity(
                            id = 42,
                            bufferId = buffer.id,
                            serverTime = launchedAt + 1,
                            sender = "me",
                            kind = MessageKind.PRIVMSG,
                            text = "hello",
                            isSelf = true,
                            pendingLabel = "pending-42",
                            dedupKey = "pending-42",
                            serverTimeAuthoritative = false,
                            timelineOrder = 42,
                        ),
                    ),
                )
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(1, settled) }
        compose.onNodeWithContentDescription("Sending…").assertIsDisplayed()
    }

    @Test
    fun republishedDraft_returnsTextTheSendNeverConsumed() {
        var draft by mutableStateOf(ComposerDraftState("hello", hydrated = true, revision = 1))
        setContent(draft = { draft }) {}

        compose.onNodeWithTag("chat_composer_send").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("hello").assertDoesNotExist()

        // What the ViewModel does when a send is rejected or its draft went stale: the same text
        // under a fresh revision, which is the screen's only signal to restore the field.
        draft = draft.copy(revision = draft.revision + 1)
        compose.waitForIdle()

        compose.onNodeWithText("hello").assertIsDisplayed()
    }
}
