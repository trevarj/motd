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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.AnnotatedString
import androidx.core.app.ActivityOptionsCompat
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.DccAddressKind
import io.github.trevarj.motd.data.db.DccDirection
import io.github.trevarj.motd.data.db.DccTransferEntity
import io.github.trevarj.motd.data.db.DccTransferProtocol
import io.github.trevarj.motd.data.db.DccTransferState
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.dcc.EbooksResultCache
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.chat.ChatContent
import io.github.trevarj.motd.ui.chat.ChatState
import io.github.trevarj.motd.ui.chat.ComposerDraftState
import io.github.trevarj.motd.ui.chat.EntryPositionState
import io.github.trevarj.motd.ui.chat.OutgoingFlight
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
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
        draft: () -> ComposerDraftState,
        pages: Flow<PagingData<MessageEntity>> = flowOf(PagingData.from(emptyList())),
        outgoingFlight: () -> OutgoingFlight? = { null },
        onFlightSettled: (Long) -> Unit = {},
        chatBuffer: () -> BufferEntity = { buffer },
        connectionState: IrcClientState? = IrcClientState.Ready("me", emptySet(), emptyMap()),
        parted: Boolean = false,
        onInviteUser: () -> Unit = {},
        replyTo: () -> MessageEntity? = { null },
        ebooksHelperRoomId: () -> Long? = { null },
        ebooksDccOffers: () -> List<DccTransferEntity> = { emptyList() },
        onAcceptDccTransfer: (Long, Uri, Boolean) -> Unit = { _, _, _ -> },
        onRejectDccTransfer: (Long) -> Unit = {},
        onRemoveDccTransfer: (Long) -> Unit = {},
        activityResults: ActivityResultRegistryOwner? = null,
        onDraftChanged: (String) -> Unit = {},
        onSubmit: (String) -> Unit,
    ) {
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            val items = pages.collectAsLazyPagingItems()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides (activityResults ?: checkNotNull(LocalActivityResultRegistryOwner.current))) {
                MotdTheme {
                    ChatContent(
                        state =
                            ChatState(
                                buffer = chatBuffer(),
                                connState = connectionState,
                                replyTo = replyTo(),
                                parted = parted,
                            ),
                        items = items,
                        composerEnabled = true,
                        onBack = {},
                        onOpenChannelInfo = {},
                        ebooksHelperRoomId = ebooksHelperRoomId(),
                        ebooksDccOffers = ebooksDccOffers(),
                        onAcceptDccTransfer = onAcceptDccTransfer,
                        onRejectDccTransfer = onRejectDccTransfer,
                        onRemoveDccTransfer = onRemoveDccTransfer,
                        onOpenSearch = {},
                        onOpenImage = {},
                        onInviteUser = onInviteUser,
                        nickNormalizer = { it.lowercase() },
                        onSubmit = onSubmit,
                        onTyping = {},
                        onSetReply = {},
                        onReact = { _, _ -> },
                        onRetry = {},
                        loadPreview = { _, _ -> null },
                        composerDraft = draft(),
                        outgoingFlight = outgoingFlight(),
                        onDraftChanged = onDraftChanged,
                        onFlightSettled = onFlightSettled,
                        entryState = EntryPositionState.Settled,
                    )
                }
            }
        }
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
            setContent(
                chatBuffer = { ebooksBuffer },
                ebooksHelperRoomId = { ebooksBuffer.id },
                ebooksDccOffers = { offers },
                activityResults = pickerOwner,
                draft = { draft },
                onDraftChanged = { draft = draft.copy(text = it, revision = draft.revision + 1) },
                onAcceptDccTransfer = { id, uri, allowPrivate -> accepted += Triple(id, uri, allowPrivate) },
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
            compose.runOnIdle {
                offers = listOf(incomingOffer(state = DccTransferState.COMPLETED, destinationUri = accepted.single().second.toString()))
            }
            compose.onNodeWithText("100 B · Plain DCC SEND · Complete").assertExists()
            compose.onNodeWithTag("chat_ebooks_receive_results_7").assertDoesNotExist()
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
