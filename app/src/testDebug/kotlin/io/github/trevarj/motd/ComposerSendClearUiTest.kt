package io.github.trevarj.motd

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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

    /** Renders the real chat surface over an empty timeline, with the draft under test control. */
    private fun setContent(
        draft: () -> ComposerDraftState,
        pages: Flow<PagingData<MessageEntity>> = flowOf(PagingData.from(emptyList())),
        outgoingFlight: () -> OutgoingFlight? = { null },
        onFlightSettled: (Long) -> Unit = {},
        chatBuffer: BufferEntity = buffer,
        connectionState: IrcClientState? = IrcClientState.Ready("me", emptySet(), emptyMap()),
        onInviteUser: () -> Unit = {},
        onSubmit: (String) -> Unit,
    ) {
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            val items = pages.collectAsLazyPagingItems()
            MotdTheme {
                ChatContent(
                    state =
                        ChatState(
                            buffer = chatBuffer,
                            connState = connectionState,
                        ),
                    items = items,
                    composerEnabled = true,
                    onBack = {},
                    onOpenChannelInfo = {},
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
                    onFlightSettled = onFlightSettled,
                    entryState = EntryPositionState.Settled,
                )
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
            chatBuffer = buffer.copy(name = "alice", displayName = "alice", type = BufferType.QUERY),
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
            chatBuffer = buffer.copy(joined = false),
            onSubmit = {},
        )

        compose.onNodeWithTag("chat_overflow").performClick()
        compose.onNodeWithTag("chat_invite_user").assertDoesNotExist()
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
