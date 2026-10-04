package io.github.trevarj.motd

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.room.Room
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
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.dcc.EbooksResultCache
import io.github.trevarj.motd.dickord.LocalDickordLabsEnabled
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.ui.ai.AiComposerDraftSnapshot
import io.github.trevarj.motd.ui.ai.AiTextAction
import io.github.trevarj.motd.ui.ai.AiTextSheet
import io.github.trevarj.motd.ui.ai.AiTextSource
import io.github.trevarj.motd.ui.ai.AiTextUiState
import io.github.trevarj.motd.ui.ai.source
import io.github.trevarj.motd.ui.chat.CHAT_TITLE_SYNC_SPINNER_TAG
import io.github.trevarj.motd.ui.chat.ChatContent
import io.github.trevarj.motd.ui.chat.ChatState
import io.github.trevarj.motd.ui.chat.ComposerDraftState
import io.github.trevarj.motd.ui.chat.ComposerDraftStore
import io.github.trevarj.motd.ui.chat.EntryPositionState
import io.github.trevarj.motd.ui.chat.MessageUrlCache
import io.github.trevarj.motd.ui.chat.OutgoingFlight
import io.github.trevarj.motd.ui.chat.SendFlightAnchors
import io.github.trevarj.motd.ui.chat.SendFlightMotion
import io.github.trevarj.motd.ui.chat.SendFlightOverlay
import io.github.trevarj.motd.ui.components.MessageBubble
import io.github.trevarj.motd.ui.components.rememberMessageTimeFormatter
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
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
    private val pickerRegistry =
        object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                pickerRequestCode = requestCode
            }
        }
    private val pickerOwner =
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry = pickerRegistry
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

    /** Renders the real chat surface over an empty timeline, with the draft under test control. */
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
        onAudioToggle: (AudioPlaybackRequest) -> Unit = {},
        entryState: EntryPositionState = EntryPositionState.Settled,
        onInitialPositionHandled: () -> Unit = {},
        rawNewestAnchor: TimelineAnchor? = null,
        onMarkRead: (TimelineAnchor) -> Unit = {},
        showImages: Boolean = true,
        showLinkPreviews: Boolean = true,
        onTranslateMessage: ((MessageEntity) -> Unit)? = null,
        onSubmit: (String) -> Unit,
    ) {
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            val items = pages.collectAsLazyPagingItems()
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides (activityResults ?: checkNotNull(LocalActivityResultRegistryOwner.current)),
                LocalDensity provides (fontScale?.let { Density(LocalDensity.current.density, it) } ?: LocalDensity.current),
                LocalDickordLabsEnabled provides dickordEnabled(),
            ) {
                MotdTheme(layoutDensity = layoutDensity()) {
                    ChatContent(
                        state =
                            ChatState(
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
                        onSetReply = {},
                        onReact = { _, _ -> },
                        onRetry = {},
                        loadPreview = { _, _ -> null },
                        onAudioToggle = onAudioToggle,
                        composerDraft = draft(),
                        aiTextEnabled = aiEnabled(),
                        aiTextState = aiState(),
                        onAiComposer = onAi,
                        onTranslateMessage = onTranslateMessage,
                        outgoingFlight = outgoingFlight(),
                        onDraftChanged = onDraftChanged,
                        onFlightSettled = onFlightSettled,
                        entryState = entryState,
                        onInitialPositionHandled = onInitialPositionHandled,
                        rawNewestAnchor = rawNewestAnchor,
                        onMarkRead = onMarkRead,
                        showImages = showImages,
                        showLinkPreviews = showLinkPreviews,
                    )
                    aiSheet()
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
            entryState = entryState,
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
        compose.onNodeWithTag("chat_compact_actions").assertIsDisplayed().assertTouchHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("chat_scroll_to_bottom_fab").assertIsDisplayed()
        timeline.performScrollToIndex(0)
        title.assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("chat_compact_actions").assertDoesNotExist()
        compose.onNodeWithTag("chat_scroll_to_bottom_fab").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, positionsHandled)
            assertEquals(0, marksRead)
        }
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
        back.assertHeightIsAtLeast(48.dp)
        search.assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("chat_overflow").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("chat_compact_actions").assertDoesNotExist()
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
        back.assertDoesNotExist()
        search.assertDoesNotExist()
        compose.onNodeWithTag("chat_overflow").assertDoesNotExist()
        val actions = compose.onNodeWithTag("chat_compact_actions")
        actions.assertTouchHeightIsEqualTo(48.dp).assertTouchWidthIsEqualTo(48.dp)
        val actionsBounds = actions.getUnclippedBoundsInRoot()
        val latestBounds = compose.onNodeWithTag("chat_scroll_to_bottom_fab").getUnclippedBoundsInRoot()
        assertEquals("Conversation actions must align with latest on the right", latestBounds.right.value, actionsBounds.right.value, 0.5f)
        assertEquals("Conversation actions must sit above latest with a gap", 8f, (latestBounds.top - actionsBounds.bottom).value, 0.5f)
        actions.performClick()
        compose.onAllNodesWithTag("chat_overflow_menu").assertCountEquals(1)
        compose.onNodeWithTag("chat_compact_details").assertHeightIsAtLeast(48.dp).performClick()
        actions.performClick()
        compose.onNodeWithTag("chat_compact_back").assertHeightIsAtLeast(48.dp).performClick()
        actions.performClick()
        compose.onNodeWithTag("chat_compact_search").assertHeightIsAtLeast(48.dp).performClick()
        actions.performClick()
        compose.onNodeWithTag("chat_invite_user").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, backs)
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
                .assertTouchHeightIsEqualTo(48.dp)
                .assertTouchWidthIsEqualTo(48.dp)
        compose.onNodeWithContentDescription(resources.getString(R.string.action_more)).assertIsDisplayed()
        val actionsBounds = actions.getUnclippedBoundsInRoot()
        val latestBounds = compose.onNodeWithTag("chat_scroll_to_bottom_fab").getUnclippedBoundsInRoot()
        val composerBounds = compose.onNodeWithTag("chat_composer_field").getUnclippedBoundsInRoot()
        assertEquals("Conversation actions must align with latest on the right", latestBounds.right.value, actionsBounds.right.value, 0.5f)
        assertEquals("Conversation actions must sit above latest with a gap", 8f, (latestBounds.top - actionsBounds.bottom).value, 0.5f)
        assertTrue("Conversation actions escaped the safe message pane", actionsBounds.top > bar.bottom && actionsBounds.bottom <= composerBounds.top)
        actions.performClick()
        compose.onAllNodesWithTag("chat_overflow_menu").assertCountEquals(1)
        compose.onNodeWithTag("chat_compact_back").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
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
        actions.assertTouchHeightIsEqualTo(48.dp).assertTouchWidthIsEqualTo(48.dp).performClick()
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
        compose.onNodeWithText("@Search   City of Brass", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText("Copy").assertIsDisplayed()
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
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                val formatTime = rememberMessageTimeFormatter()
                // Compare at the same window origin, not two separately rasterized stacked rows.
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
                            pending = true,
                        )
                    } else {
                        SendFlightOverlay(
                            flight = flight,
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
        compose.waitForIdle()

        val sample = compose.onNodeWithTag("bubble_sample")
        val real = sample.captureToImage().asAndroidBitmap()
        compose.onAllNodesWithText("hi", useUnmergedTree = true).assertCountEquals(1)
        compose.runOnIdle { renderFlight = true }
        compose.waitForIdle()
        val airborne = sample.captureToImage().asAndroidBitmap()
        assertTrue("A completed delayed morph must match the real formatted bubble", real.sameAs(airborne))
        // The merged accessibility tree excludes the overlay's cleared semantics subtree.
        compose.onAllNodesWithText("hi").assertCountEquals(0)
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
